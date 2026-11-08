package com.flowtivo.tinfirm.identity.internal.saml;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseCookie;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.saml2.provider.service.authentication.AbstractSaml2AuthenticationRequest;
import org.springframework.security.saml2.provider.service.authentication.Saml2RedirectAuthenticationRequest;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistration;
import org.springframework.security.saml2.provider.service.web.Saml2AuthenticationRequestRepository;
import org.springframework.stereotype.Component;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import com.flowtivo.tinfirm.identity.internal.ReturnPaths;
import com.flowtivo.tinfirm.identity.internal.SamlSignIns;
import com.flowtivo.tinfirm.shared.time.TimeSource;

/**
 * The AuthnRequests sent, kept server-side until their response arrives (ADR-0086, decision 4): in
 * {@code saml_authn_request}, keyed by the request's ID, for 5 minutes, used once. The IdP's response arrives as a
 * cross-site POST, which carries no {@code SameSite=Lax} session cookie, so the request is bound to the browser by a
 * random value in a cookie of its own, {@code TINFIRM_SAML} ({@code HttpOnly}, {@code Secure},
 * {@code SameSite=None}, {@code Path=/login/saml2/sso/}, 5 minutes), whose SHA-256 the row holds. A request is found
 * only by that cookie, for the provider the response is posted to. It is deleted only once a response has passed
 * every check ({@link #consume}), so that it answers one response; a response that fails, such as one a page of another
 * site posts from the browser while its sign-in is under way, leaves the request and the cookie for the IdP's own
 * response. The row also keeps what the sign-in returns to: the OAuth authorization request it interrupted, and the
 * checked page the start named ({@code returnTo}, {@link ReturnPaths}); {@code RelayState} carries neither. A new
 * start in the same browser sets a new cookie (the old one never reaches the start's path), so the
 * earlier request is left to the sweep.
 */
@Component
class SamlAuthnRequests implements Saml2AuthenticationRequestRepository<AbstractSaml2AuthenticationRequest> {

    static final String COOKIE = "TINFIRM_SAML";

    static final Duration LIFETIME = Duration.ofMinutes(5);

    /** A request attribute that says the response's browser held no binding to a saved request. */
    static final String NO_BINDING = SamlAuthnRequests.class.getName() + ".noBinding";

    private static final Pattern BINDING = Pattern.compile("^[A-Za-z0-9_-]{43}$");

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final TypeReference<Map<String, String>> FIELDS = new TypeReference<>() {
    };

    private final SecureRandom random = new SecureRandom();

    private final JdbcClient jdbc;

    private final SamlRegistrations registrations;

    private final TimeSource time;

    private final SamlSignIns signIns;

    SamlAuthnRequests(JdbcClient jdbc, SamlRegistrations registrations, TimeSource time, SamlSignIns signIns) {
        this.jdbc = jdbc;
        this.registrations = registrations;
        this.time = time;
        this.signIns = signIns;
    }

    @Override
    public void saveAuthenticationRequest(@Nullable AbstractSaml2AuthenticationRequest authenticationRequest,
            HttpServletRequest request, HttpServletResponse response) {
        if (authenticationRequest == null) {
            removeAuthenticationRequest(request, response);
            return;
        }
        if (!(authenticationRequest instanceof Saml2RedirectAuthenticationRequest redirect)
                || redirect.getId() == null || redirect.getRelyingPartyRegistrationId() == null) {
            throw new IllegalStateException("Only a redirect AuthnRequest with an ID is sent (ADR-0086, decision 4)");
        }
        byte[] value = new byte[32];
        random.nextBytes(value);
        String binding = Base64.getUrlEncoder().withoutPadding().encodeToString(value);
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("samlRequest", redirect.getSamlRequest());
        fields.put("relayState", redirect.getRelayState());
        fields.put("uri", redirect.getAuthenticationRequestUri());
        fields.put("sigAlg", redirect.getSigAlg());
        fields.put("signature", redirect.getSignature());
        fields.put("returnTo", signIns.pendingAuthorizationRequest(request, response));
        // The page the start named, checked (Q44): kept here, with this one request, never read from the response.
        fields.put("returnPath", signIns.returnPath(request));
        jdbc.sql("""
                INSERT INTO saml_authn_request (id, registration_id, binding_hash, request, created_at)
                VALUES (?, ?, ?, ?::jsonb, ?)
                """)
            .params(redirect.getId(), redirect.getRelyingPartyRegistrationId(), sha256(binding),
                    JSON.writeValueAsString(fields), OffsetDateTime.ofInstant(time.now(), ZoneOffset.UTC))
            .update();
        response.addHeader("Set-Cookie", cookie(binding, LIFETIME).toString());
    }

    /**
     * The request this browser's binding holds for the provider in the path, left in place: only a response that
     * passes every check uses it up ({@link #consume}). Null when the browser holds no binding, when the binding names no request of this provider younger
     * than 5 minutes, or when the provider has no registration now.
     */
    @Override
    public @Nullable AbstractSaml2AuthenticationRequest loadAuthenticationRequest(HttpServletRequest request) {
        String registrationId = registrationIdOf(request);
        String binding = cookie(request);
        if (registrationId == null || binding == null) {
            request.setAttribute(NO_BINDING, Boolean.TRUE);
            return null;
        }
        Optional<Saved> saved = jdbc.sql("""
                SELECT id, request FROM saml_authn_request
                 WHERE binding_hash = ? AND registration_id = ? AND created_at > ?
                """)
            .params(sha256(binding), registrationId,
                    OffsetDateTime.ofInstant(time.now().minus(LIFETIME), ZoneOffset.UTC))
            .query((rs, rowNum) -> new Saved(rs.getString("id"), rs.getString("request")))
            .optional();
        if (saved.isEmpty()) {
            request.setAttribute(NO_BINDING, Boolean.TRUE);
            return null;
        }
        RelyingPartyRegistration registration = registrations.findByRegistrationId(registrationId);
        if (registration == null) {
            return null;
        }
        Map<String, String> fields = JSON.readValue(saved.get().request(), FIELDS);
        if (fields.get("returnTo") != null) {
            request.setAttribute(SamlSignIns.RETURN_TO, fields.get("returnTo"));
        }
        // Read only by the success handler, which checks it again; a refused response never goes there.
        if (fields.get("returnPath") != null) {
            request.setAttribute(ReturnPaths.REQUEST_ATTRIBUTE, fields.get("returnPath"));
        }
        return Saml2RedirectAuthenticationRequest.withRelyingPartyRegistration(registration)
            .id(saved.get().id())
            .samlRequest(fields.get("samlRequest"))
            .relayState(fields.get("relayState"))
            .authenticationRequestUri(fields.get("uri"))
            .sigAlg(fields.get("sigAlg"))
            .signature(fields.get("signature"))
            .build();
    }

    /**
     * Leaves the request and the cookie in place. Spring Security calls this before it checks the response, so a
     * response that then fails must not cost the browser its request: {@link #consume} deletes the request once a
     * response has passed, and {@link #forget} expires the cookie once the person is signed in.
     */
    @Override
    public @Nullable AbstractSaml2AuthenticationRequest removeAuthenticationRequest(HttpServletRequest request,
            HttpServletResponse response) {
        return null;
    }

    /**
     * Uses up the request a response that passed every check answers: true for the first such response, false when
     * another one used it up first.
     */
    boolean consume(String registrationId, String requestId) {
        return jdbc.sql("DELETE FROM saml_authn_request WHERE id = ? AND registration_id = ?")
            .params(requestId, registrationId)
            .update() == 1;
    }

    /** Expires the binding cookie, once the person is signed in. */
    void forget(HttpServletResponse response) {
        response.addHeader("Set-Cookie", cookie("", Duration.ZERO).toString());
    }

    /**
     * Whether a saved request of this provider younger than 5 minutes has this ID, whatever browser asked for it: a
     * response that answers it from another browser is refused as {@code browser_mismatch}.
     */
    boolean exists(String registrationId, String requestId) {
        return jdbc
            .sql("SELECT count(*) FROM saml_authn_request WHERE id = ? AND registration_id = ? AND created_at > ?")
            .params(requestId, registrationId, OffsetDateTime.ofInstant(time.now().minus(LIFETIME), ZoneOffset.UTC))
            .query(Integer.class)
            .single() > 0;
    }

    /** Deletes the requests nobody answered. */
    int sweep(Instant now) {
        return jdbc.sql("DELETE FROM saml_authn_request WHERE created_at < ?")
            .param(OffsetDateTime.ofInstant(now.minus(LIFETIME), ZoneOffset.UTC))
            .update();
    }

    private record Saved(String id, String request) {
    }

    private static ResponseCookie cookie(String value, Duration maxAge) {
        return ResponseCookie.from(COOKIE, value)
            .httpOnly(true)
            .secure(true)
            .sameSite("None")
            .path(SamlRegistrations.ACS_PATH)
            .maxAge(maxAge)
            .build();
    }

    private static @Nullable String cookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (COOKIE.equals(cookie.getName()) && BINDING.matcher(cookie.getValue()).matches()) {
                return cookie.getValue();
            }
        }
        return null;
    }

    /** The provider of an ACS path, {@code /login/saml2/sso/{id}}; null for another path. */
    static @Nullable String registrationIdOf(HttpServletRequest request) {
        String path = SamlGuardFilter.path(request);
        if (!path.startsWith(SamlRegistrations.ACS_PATH)) {
            return null;
        }
        String id = path.substring(SamlRegistrations.ACS_PATH.length());
        return id.matches("[a-z0-9]{1,64}") ? id : null;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII)));
        }
        catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
