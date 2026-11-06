package com.flowtivo.tinfirm.identity.internal.saml;

import java.io.IOException;
import java.time.Duration;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.jspecify.annotations.Nullable;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

import com.flowtivo.tinfirm.identity.internal.SamlSignIns;
import com.flowtivo.tinfirm.shared.licence.Feature;
import com.flowtivo.tinfirm.shared.licence.Licensing;

/**
 * What the start ({@code /saml2/authenticate/{id}}) and the assertion consumer service ({@code /login/saml2/sso/{id}})
 * check before Spring Security's SAML filters see a request (ADR-0086, decisions 4, 8, 13 and 14), each refusal a
 * redirect to the login page:
 * <ul>
 * <li>at the ACS, a post the browser says is not a navigation ({@code Sec-Fetch-Dest} other than {@code document}):
 * {@code 403}, neither parsed nor counted. An IdP's response is always a top-level form post; a post from a hidden
 * frame or a script of another site, which carries the {@code SameSite=None} binding cookie, must not use up the
 * address's attempts. A client that sends no such header, such as an older browser, goes on to the checks below;</li>
 * <li>a client address whose failures used up its attempts: {@code 429}, before anything is parsed;</li>
 * <li>an id the deployment does not declare: back to the login page, not audited (anyone can ask for any id);</li>
 * <li>a licence that does not serve SAML: {@code EDITION_REQUIRED}, audited {@code edition_required};</li>
 * <li>a provider without metadata in force: {@code metadata_expired};</li>
 * <li>at the ACS, a request that is not a POST, or whose body is over 256 KiB: {@code invalid_response}.</li>
 * </ul>
 */
final class SamlGuardFilter extends OncePerRequestFilter {

    static final long MAX_BODY = 256 * 1024;

    /** Decodes the path, as Spring Security's path matchers do. */
    private static final UrlPathHelper URL_PATHS = new UrlPathHelper();

    private static final Pattern START = Pattern.compile("^/saml2/authenticate/([^/]*)$");

    private static final Pattern ACS = Pattern.compile("^/login/saml2/sso(?:/([^/]*))?$");

    private final SamlRegistrations registrations;

    private final SamlSignIns signIns;

    private final Licensing licensing;

    private final java.util.function.Predicate<String> declared;

    SamlGuardFilter(SamlRegistrations registrations, SamlSignIns signIns, Licensing licensing,
            java.util.function.Predicate<String> declared) {
        this.registrations = registrations;
        this.signIns = signIns;
        this.licensing = licensing;
        this.declared = declared;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = path(request);
        return !START.matcher(path).matches() && !ACS.matcher(path).matches();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = path(request);
        Matcher acs = ACS.matcher(path);
        boolean atAcs = acs.matches();
        Matcher start = START.matcher(path);
        String id = atAcs ? acs.group(1) : start.matches() ? start.group(1) : null;
        String destination = request.getHeader("Sec-Fetch-Dest");
        if (atAcs && destination != null && !"document".equals(destination)) {
            response.sendError(403);
            return;
        }
        Optional<Duration> blocked = signIns.blocked(request);
        if (blocked.isPresent()) {
            response.setHeader("Retry-After", Long.toString(Math.max(1, blocked.get().toSeconds())));
            response.sendError(429);
            return;
        }
        if (id == null || !declared.test(id)) {
            response.sendRedirect(signIns.loginError(SamlRefusal.SAML_LOGIN_FAILED));
            return;
        }
        if (!licensing.has(Feature.SAML)) {
            refuse(request, response, id, SamlRefusal.editionRequired());
            return;
        }
        if (registrations.findByRegistrationId(id) == null) {
            refuse(request, response, id, SamlRefusal.of("metadata_expired", "the provider has no IdP metadata in "
                    + "force"));
            return;
        }
        if (atAcs) {
            if (!"POST".equals(request.getMethod())) {
                refuse(request, response, id, SamlRefusal.of("invalid_response", "the ACS takes a POST only"));
                return;
            }
            long length = request.getContentLengthLong();
            if (length < 0 || length > MAX_BODY) {
                refuse(request, response, id, SamlRefusal.of("invalid_response", "the request body is "
                        + (length < 0 ? "of no stated length" : length + " bytes") + "; at most " + MAX_BODY));
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private void refuse(HttpServletRequest request, HttpServletResponse response, @Nullable String id,
            SamlRefusal refusal) throws IOException {
        signIns.refused(request, id, refusal.reason(), refusal.getMessage());
        response.sendRedirect(signIns.loginError(refusal.code()));
    }

    /**
     * The path as Spring Security's matchers see it, decoded: a percent-encoded path such as
     * {@code /login/saml2/ss%6F/kc} reaches the same filters, so it must meet this guard too.
     */
    static String path(HttpServletRequest request) {
        return URL_PATHS.getPathWithinApplication(request);
    }
}
