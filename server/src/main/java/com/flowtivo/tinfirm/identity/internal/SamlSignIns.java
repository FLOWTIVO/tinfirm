package com.flowtivo.tinfirm.identity.internal;

import java.io.IOException;
import java.io.Serial;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import com.flowtivo.tinfirm.shared.security.SystemContext;

/**
 * What the core does for {@code ee/}'s SAML sign-in (ADR-0086, decisions 4, 7, 12 and 13): provisions the user an
 * accepted assertion names, through the same code as OIDC ({@link ExternalProvisioning}); audits a refusal; counts it
 * against the client address; and ends a sign-in on the session, the way an OIDC login ends.
 */
@Component
public class SamlSignIns {

    private static final Logger log = LoggerFactory.getLogger(SamlSignIns.class);

    /**
     * The request attribute that holds the OAuth authorization request a sign-in interrupted, saved with the
     * AuthnRequest at the start: the IdP's cross-site POST carries no {@code SameSite=Lax} session cookie, so the
     * session's request cache is not there at the ACS.
     */
    public static final String RETURN_TO = SamlSignIns.class.getName() + ".returnTo";

    /** The session attribute that ends a SAML session at the assertion's {@code SessionNotOnOrAfter}. */
    static final String SESSION_NOT_ON_OR_AFTER = SessionMaxAgeFilter.NOT_ON_OR_AFTER;

    private final ExternalProvisioning provisioning;

    private final LoginAudit loginAudit;

    private final LoginThrottle throttle;

    private final SamlSettings settings;

    /** The refusals audited in the last minute, by address and reason (decision 13). */
    private final Cache<String, Boolean> recentlyAudited = Caffeine.newBuilder()
        .maximumSize(10_000)
        .expireAfterWrite(Duration.ofMinutes(1))
        .build();

    SamlSignIns(ExternalProvisioning provisioning, LoginAudit loginAudit, LoginThrottle throttle,
            SamlSettings settings) {
        this.provisioning = provisioning;
        this.loginAudit = loginAudit;
        this.throttle = throttle;
        this.settings = settings;
    }

    /**
     * An assertion the library and Tinfirm's checks accepted.
     *
     * @param provider       the registration id
     * @param entityId       the IdP's entity ID
     * @param subject        the NameID, or the value of the attribute {@code _SUBJECT} names
     * @param email          the email the provider's attributes assert, or null
     * @param emailAttribute the attribute it came from, or null
     * @param name           the person's name, or null
     * @param roleValues     the values of the provider's role attribute, empty without one
     */
    public record Assertion(String provider, String entityId, String subject, @Nullable String email,
            @Nullable String emailAttribute, @Nullable String name, List<String> roleValues) {
    }

    /** The outcome of a sign-in: the user, or the refusal already audited. */
    public sealed interface Outcome permits SignedIn, Refused {
    }

    public record SignedIn(UUID userId) implements Outcome {
    }

    /**
     * @param reason the audited reason
     * @param code   the login page's error code
     */
    public record Refused(String reason, String code) implements Outcome {
    }

    /**
     * Signs the person in, or refuses them (decisions 5 to 7). A provider that fills companies (ADR-0075, ADR-0089) is
     * provisioned as actor SYSTEM in the tenant context of its company, which the server resolves from the
     * deployment, never from the response.
     */
    public Outcome signIn(Assertion assertion) {
        ProviderCompanies companies = settings.companiesOf(assertion.provider());
        UUID companyId = companies.context();
        ExternalProvisioning.SamlAssertion login = new ExternalProvisioning.SamlAssertion(assertion.provider(),
                assertion.entityId(), assertion.subject(), assertion.email(), assertion.emailAttribute(),
                assertion.name(), assertion.roleValues(), companies);
        Supplier<ExternalProvisioning.Outcome> provision = companyId == null ? () -> provisioning.provisionSaml(login)
                : () -> SystemContext.callInCompany(companyId, () -> provisioning.provisionSaml(login));
        ExternalProvisioning.Outcome outcome;
        try {
            outcome = provision.get();
        }
        catch (DataIntegrityViolationException concurrentFirstLogin) {
            // Another request provisioned the same identity or email concurrently: decide again on committed data.
            outcome = provision.get();
        }
        return switch (outcome) {
            case ExternalProvisioning.Granted granted -> new SignedIn(granted.userId());
            case ExternalProvisioning.Denied denied -> new Refused(denied.reason(), denied.code());
        };
    }

    /**
     * Audits a refused response or start ({@code auth.login.failed}, {@code method: saml}), at most once a minute per
     * address and reason, logs it, and counts it against the client address.
     *
     * @param provider the registration id when the path names a declared one, else null
     * @param detail   what failed, for the log only: never in the audit or the page
     */
    public void refused(HttpServletRequest request, @Nullable String provider, String reason, String detail) {
        String address = request.getRemoteAddr();
        failed(request);
        log.info("SAML sign-in refused ({}) through {}: {}", reason, provider, detail);
        if (recentlyAudited.asMap().putIfAbsent(address + "|" + reason, Boolean.TRUE) == null) {
            loginAudit.failed(null, reason, provider, "saml");
        }
    }

    /**
     * Counts a refusal against the client address without auditing it: for a refusal provisioning audited already,
     * such as {@code ROLE_NOT_MAPPED}, which must use up the address's attempts as any other (decision 13).
     */
    public void failed(HttpServletRequest request) {
        throttle.recordSamlFailure(request.getRemoteAddr());
    }

    /** Whether the client address has used up its attempts: empty when it may go on, else the time to wait. */
    public Optional<Duration> blocked(HttpServletRequest request) {
        return throttle.samlBlocked(request.getRemoteAddr());
    }

    /** The login page's URL for an error code. */
    public String loginError(String code) {
        return settings.publicBaseUrl() + "/login?error=" + code;
    }

    /**
     * The session's authentication of a signed-in user: the same principal as a local login's, and the end the
     * assertion's {@code SessionNotOnOrAfter} sets, if any.
     */
    public Authentication authenticated(UUID userId, @Nullable Instant sessionNotOnOrAfter) {
        return new SamlAuthentication(new SessionPrincipal(userId), sessionNotOnOrAfter);
    }

    /**
     * The path and query of the OAuth authorization request the start interrupted (ADR-0045), from the session's
     * request cache, which the start's same-site GET carries; null for none.
     */
    public @Nullable String pendingAuthorizationRequest(HttpServletRequest request, HttpServletResponse response) {
        org.springframework.security.web.savedrequest.SavedRequest saved = AuthorizationServerConfiguration
            .authorizationRequestCache()
            .getRequest(request, response);
        return saved == null ? null : OidcLoginSuccessHandler.authorizationRequest(saved.getRedirectUrl());
    }

    /**
     * Where a SAML sign-in ends: as an OIDC login, on {@code /} or on the OAuth authorization request it interrupted
     * (saved with the request, {@link #RETURN_TO}; threat model 3.4); {@code RelayState} is never a destination. The
     * session ends at the earlier of {@code TINFIRM_SESSION_MAX_AGE} and the assertion's {@code SessionNotOnOrAfter}.
     */
    public AuthenticationSuccessHandler successHandler() {
        OidcLoginSuccessHandler redirect = new OidcLoginSuccessHandler(settings.publicBaseUrl(),
                AuthorizationServerConfiguration.authorizationRequestCache());
        return (HttpServletRequest request, HttpServletResponse response, Authentication authentication) -> {
            if (authentication instanceof SamlAuthentication saml && saml.sessionNotOnOrAfter() != null) {
                HttpSession session = request.getSession(false);
                if (session != null) {
                    session.setAttribute(SESSION_NOT_ON_OR_AFTER, saml.sessionNotOnOrAfter().toEpochMilli());
                }
            }
            String returnTo = request.getAttribute(RETURN_TO) instanceof String saved
                    ? OidcLoginSuccessHandler.authorizationRequest(settings.publicBaseUrl() + saved) : null;
            try {
                if (returnTo != null) {
                    response.sendRedirect(settings.publicBaseUrl() + returnTo);
                    return;
                }
                redirect.onAuthenticationSuccess(request, response, authentication);
            }
            catch (IOException ex) {
                throw new java.io.UncheckedIOException(ex);
            }
        };
    }

    /** A SAML session's authentication: the user, as a local login's, and when the IdP ends the session. */
    static final class SamlAuthentication extends AbstractAuthenticationToken {

        @Serial
        private static final long serialVersionUID = 1L;

        private final SessionPrincipal principal;

        private final @Nullable Instant sessionNotOnOrAfter;

        SamlAuthentication(SessionPrincipal principal, @Nullable Instant sessionNotOnOrAfter) {
            super(AuthorityUtils.NO_AUTHORITIES);
            this.principal = principal;
            this.sessionNotOnOrAfter = sessionNotOnOrAfter;
            setAuthenticated(true);
        }

        @Override
        public @Nullable Object getCredentials() {
            return null;
        }

        @Override
        public SessionPrincipal getPrincipal() {
            return principal;
        }

        @Nullable Instant sessionNotOnOrAfter() {
            return sessionNotOnOrAfter;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof SamlAuthentication that && principal.equals(that.principal) && super.equals(other);
        }

        @Override
        public int hashCode() {
            return 31 * super.hashCode() + principal.hashCode();
        }
    }
}
