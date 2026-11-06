package com.flowtivo.tinfirm.identity.internal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationException;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationToken;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UriComponents;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

import tools.jackson.databind.json.JsonMapper;

/**
 * The responses of the authorization endpoint (ADR-0045), in place of Spring's:
 * <ul>
 * <li>both the code and an error carry {@code iss} (RFC 9207), so a client can tell Tinfirm's answer from another
 * authorization server's (mix-up attacks);</li>
 * <li>the consent page posts the person's decision with {@code fetch} ({@code Accept: application/json}), because
 * the strict CSP ({@code form-action 'self'}) would stop a form whose answer redirects to the client's loopback
 * address; such a request gets {@code 200 {"redirectUri": "..."}} and the page navigates there itself. Every other
 * request gets the usual 302;</li>
 * <li>a code issued on a consent is audited as {@code oauth.consent.granted} in the chain of the grant's company;
 * when that audit fails, the authorization is removed and the client gets {@code server_error} instead of the
 * code.</li>
 * </ul>
 * An error that cannot be sent to the client (no valid redirect URI, an unknown client) is 400 with the OAuth error.
 */
final class AuthorizationResponses implements AuthenticationSuccessHandler, AuthenticationFailureHandler {

    private static final Logger log = LoggerFactory.getLogger(AuthorizationResponses.class);

    private static final OAuth2TokenType CODE = new OAuth2TokenType(OAuth2ParameterNames.CODE);

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final OAuthResources resources;

    private final OAuth2AuthorizationService authorizations;

    private final OAuthGrantService grants;

    AuthorizationResponses(OAuthResources resources, OAuth2AuthorizationService authorizations,
            OAuthGrantService grants) {
        this.resources = resources;
        this.authorizations = authorizations;
        this.grants = grants;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
            Authentication authentication) throws IOException {
        OAuth2AuthorizationCodeRequestAuthenticationToken result =
                (OAuth2AuthorizationCodeRequestAuthenticationToken) authentication;
        String code = result.getAuthorizationCode() != null ? result.getAuthorizationCode().getTokenValue() : null;
        if (code == null || result.getRedirectUri() == null) {
            sendJsonError(response, new OAuth2Error(OAuth2ErrorCodes.SERVER_ERROR));
            return;
        }
        if ("POST".equals(request.getMethod()) && !recordConsent(code)) {
            // No code outlives a missing audit record: the authorization is gone, and the client hears server_error.
            UriComponentsBuilder uri = UriComponentsBuilder.fromUriString(result.getRedirectUri())
                .queryParam(OAuth2ParameterNames.ERROR, OAuth2ErrorCodes.SERVER_ERROR);
            state(uri, result.getState());
            uri.queryParam("iss", UriUtils.encode(resources.issuer(), StandardCharsets.UTF_8));
            send(request, response, uri.build(true));
            return;
        }
        UriComponentsBuilder uri = UriComponentsBuilder.fromUriString(result.getRedirectUri())
            .queryParam(OAuth2ParameterNames.CODE, code);
        state(uri, result.getState());
        uri.queryParam("iss", UriUtils.encode(resources.issuer(), StandardCharsets.UTF_8));
        send(request, response, uri.build(true));
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException exception) throws IOException {
        OAuth2Error error = exception instanceof OAuth2AuthenticationException oauth ? oauth.getError()
                : new OAuth2Error(OAuth2ErrorCodes.SERVER_ERROR);
        OAuth2AuthorizationCodeRequestAuthenticationToken failed = null;
        if (exception instanceof OAuth2AuthorizationCodeRequestAuthenticationException codeRequest) {
            failed = codeRequest.getAuthorizationCodeRequestAuthentication();
        }
        if (failed == null || !StringUtils.hasText(failed.getRedirectUri())) {
            sendJsonError(response, error);
            return;
        }
        UriComponentsBuilder uri = UriComponentsBuilder.fromUriString(failed.getRedirectUri())
            .queryParam(OAuth2ParameterNames.ERROR, error.getErrorCode());
        if (StringUtils.hasText(error.getDescription())) {
            uri.queryParam(OAuth2ParameterNames.ERROR_DESCRIPTION,
                    UriUtils.encode(error.getDescription(), StandardCharsets.UTF_8));
        }
        state(uri, failed.getState());
        uri.queryParam("iss", UriUtils.encode(resources.issuer(), StandardCharsets.UTF_8));
        send(request, response, uri.build(true));
    }

    private static void state(UriComponentsBuilder uri, @Nullable String state) {
        if (StringUtils.hasText(state)) {
            uri.queryParam(OAuth2ParameterNames.STATE, UriUtils.encode(state, StandardCharsets.UTF_8));
        }
    }

    /**
     * Sends the client back to its redirect URI. The target is built only from the redirect URI of Spring's
     * authentication result, which its authorization code request validators have matched against the client's
     * registered redirect URIs (the loopback rule included) before a success or a redirectable failure is
     * reported; nothing here reads {@code redirect_uri} from the request.
     */
    private static void send(HttpServletRequest request, HttpServletResponse response, UriComponents target)
            throws IOException {
        if (wantsJson(request)) {
            response.setStatus(HttpStatus.OK.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write(JSON.writeValueAsString(Map.of("redirectUri", target.toUriString())));
            return;
        }
        response.sendRedirect(target.toUriString());
    }

    /** The consent page's {@code fetch}: a POST that accepts JSON. */
    static boolean wantsJson(HttpServletRequest request) {
        String accept = request.getHeader("Accept");
        return "POST".equals(request.getMethod()) && accept != null && accept.contains(MediaType.APPLICATION_JSON_VALUE);
    }

    private static void sendJsonError(HttpServletResponse response, OAuth2Error error) throws IOException {
        response.setStatus(HttpStatus.BAD_REQUEST.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        Map<String, String> body = error.getDescription() != null
                ? Map.of("error", error.getErrorCode(), "error_description", error.getDescription())
                : Map.of("error", error.getErrorCode());
        response.getWriter().write(JSON.writeValueAsString(body));
    }

    /**
     * Audits the consent that just became {@code code} as {@code oauth.consent.granted}. Spring's provider has saved
     * the authorization (and so the code) before this handler runs, outside any transaction this class could join;
     * so when the audit cannot be written, the authorization is removed here and {@code false} tells the caller to
     * answer {@code server_error} instead of the code: no code outlives a missing audit record.
     */
    private boolean recordConsent(String code) {
        OAuth2Authorization authorization = null;
        try {
            authorization = authorizations.findByToken(code, CODE);
            if (authorization == null) {
                // Not stored, so not redeemable either.
                return false;
            }
            OAuth2AuthorizationRequest authorizationRequest = authorization
                .getAttribute(OAuth2AuthorizationRequest.class.getName());
            Object resource = authorizationRequest != null
                    ? authorizationRequest.getAdditionalParameters().get(OAuthAccessTokenCustomizer.RESOURCE) : null;
            Optional<UUID> company = resource instanceof String value ? resources.companyOfResource(value)
                    : Optional.empty();
            if (company.isEmpty() || authorizationRequest == null) {
                throw new IllegalStateException("The authorization names no company to audit the consent in");
            }
            grants.recordConsent(UUID.fromString(authorization.getPrincipalName()),
                    authorizationRequest.getClientId(), company.get(), authorization.getAuthorizedScopes());
            return true;
        }
        catch (RuntimeException ex) {
            log.error("Could not audit a granted OAuth consent; the authorization is withdrawn", ex);
            if (authorization != null) {
                try {
                    authorizations.remove(authorization);
                }
                catch (RuntimeException removal) {
                    // The code is never sent, so nobody holds it; the row expires with the code.
                    log.error("Could not remove the authorization of an unaudited consent", removal);
                }
            }
            return false;
        }
    }
}
