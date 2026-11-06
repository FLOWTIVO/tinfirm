package com.flowtivo.tinfirm.identity.internal;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;

import org.jspecify.annotations.Nullable;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.web.authentication.OAuth2AuthorizationCodeRequestAuthenticationConverter;
import org.springframework.security.web.authentication.AuthenticationConverter;

/**
 * Lets the consent page offer every level (ADR-0045). An authorization request that names any of the three
 * {@code tinfirm:*} scopes becomes a request for all three, so the person may consent to any level whatever the
 * client asked for. The scopes the client sent stay on the request as the additional parameter
 * {@value #REQUESTED_SCOPE}, which the consent page reads to preselect a level. Every other scope of the request is
 * kept, and the request validators refuse it as before. A request without any of the three is left as it is, and so
 * refused.
 * <p>
 * It wraps Spring's {@link OAuth2AuthorizationCodeRequestAuthenticationConverter} on the authorization endpoint. The
 * consent POST, which Spring's other converter reads, is not touched: a consent may still authorize only scopes of
 * the (widened) request, so a scope outside the three stays {@code invalid_scope}.
 */
final class ScopeWideningRequestConverter implements AuthenticationConverter {

    /** The additional parameter that keeps the scopes the client sent, space-separated. */
    static final String REQUESTED_SCOPE = "tinfirm_requested_scope";

    private final AuthenticationConverter delegate;

    ScopeWideningRequestConverter(AuthenticationConverter delegate) {
        this.delegate = delegate;
    }

    /** Wraps Spring's authorization request converter in the endpoint's list; the others stay as they are. */
    static void wrap(List<AuthenticationConverter> converters) {
        converters.replaceAll(converter -> converter instanceof OAuth2AuthorizationCodeRequestAuthenticationConverter
                ? new ScopeWideningRequestConverter(converter) : converter);
    }

    @Override
    public @Nullable Authentication convert(HttpServletRequest request) {
        Authentication converted = delegate.convert(request);
        if (!(converted instanceof OAuth2AuthorizationCodeRequestAuthenticationToken token)) {
            return converted;
        }
        Set<String> sent = token.getScopes();
        if (OAuthScopes.known(sent).isEmpty()) {
            return token;
        }
        Map<String, Object> parameters = new HashMap<>(token.getAdditionalParameters());
        parameters.put(REQUESTED_SCOPE, OAuthScopes.joined(sent));
        return new OAuth2AuthorizationCodeRequestAuthenticationToken(token.getAuthorizationUri(), token.getClientId(),
                (Authentication) token.getPrincipal(), token.getRedirectUri(), token.getState(), widened(sent),
                parameters);
    }

    /** The three {@code tinfirm:*} scopes, lowest first, then whatever else the client sent. */
    static Set<String> widened(Collection<String> sent) {
        Set<String> scopes = new LinkedHashSet<>(OAuthScopes.ALL);
        scopes.addAll(sent);
        return scopes;
    }

    /** The scopes the client sent, from a stored authorization request's additional parameters. */
    static @Nullable String requestedScope(Map<String, Object> additionalParameters) {
        return additionalParameters.get(REQUESTED_SCOPE) instanceof String scope ? scope : null;
    }
}
