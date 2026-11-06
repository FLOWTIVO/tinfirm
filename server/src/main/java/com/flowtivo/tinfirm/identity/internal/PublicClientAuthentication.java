package com.flowtivo.tinfirm.identity.internal;

import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.web.authentication.AuthenticationConverter;

/**
 * Client authentication of a public client (ADR-0045) on the two requests Spring's own does not cover: a refresh
 * ({@code grant_type=refresh_token}) and a revocation, each with a {@code client_id} and no secret. Spring
 * authenticates a public client only on the code exchange, by its PKCE verifier; here the client is identified by its
 * id, and the token it presents is the proof: the refresh and revocation providers accept only a token issued to that
 * client. Tokens rotate on every refresh, so a stolen refresh token works at most once before its theft shows.
 */
final class PublicClientAuthentication {

    /** Marks a client authentication made here, so that only {@link Provider} completes it. */
    static final String MARKER = "tinfirm_public_client";

    private PublicClientAuthentication() {
    }

    /** Recognises a public client's refresh or revocation request on the token or revocation endpoint. */
    static final class Converter implements AuthenticationConverter {

        private final String revocationEndpoint;

        Converter(String revocationEndpoint) {
            this.revocationEndpoint = revocationEndpoint;
        }

        @Override
        public @Nullable Authentication convert(HttpServletRequest request) {
            if (!"POST".equals(request.getMethod()) || request.getHeader(HttpHeaders.AUTHORIZATION) != null
                    || request.getParameter(OAuth2ParameterNames.CLIENT_SECRET) != null
                    || request.getParameter(OAuth2ParameterNames.CLIENT_ASSERTION) != null) {
                return null;
            }
            boolean refresh = AuthorizationGrantType.REFRESH_TOKEN.getValue()
                .equals(request.getParameter(OAuth2ParameterNames.GRANT_TYPE));
            boolean revocation = request.getRequestURI().equals(request.getContextPath() + revocationEndpoint);
            if (!refresh && !revocation) {
                return null;
            }
            String[] clientIds = request.getParameterValues(OAuth2ParameterNames.CLIENT_ID);
            if (clientIds == null) {
                return null;
            }
            if (clientIds.length != 1 || clientIds[0].isBlank()) {
                throw new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_REQUEST);
            }
            return new OAuth2ClientAuthenticationToken(clientIds[0], ClientAuthenticationMethod.NONE, null,
                    Map.of(MARKER, true));
        }
    }

    /** Completes {@link Converter}'s authentication for a registered public client. */
    static final class Provider implements AuthenticationProvider {

        private final RegisteredClientRepository clients;

        Provider(RegisteredClientRepository clients) {
            this.clients = clients;
        }

        @Override
        public @Nullable Authentication authenticate(Authentication authentication) throws AuthenticationException {
            OAuth2ClientAuthenticationToken client = (OAuth2ClientAuthenticationToken) authentication;
            if (!ClientAuthenticationMethod.NONE.equals(client.getClientAuthenticationMethod())
                    || !Boolean.TRUE.equals(client.getAdditionalParameters().get(MARKER))) {
                return null;
            }
            RegisteredClient registered = clients.findByClientId(String.valueOf(client.getPrincipal()));
            if (registered == null
                    || !registered.getClientAuthenticationMethods().contains(ClientAuthenticationMethod.NONE)) {
                throw new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_CLIENT);
            }
            return new OAuth2ClientAuthenticationToken(registered, ClientAuthenticationMethod.NONE, null);
        }

        @Override
        public boolean supports(Class<?> authentication) {
            return OAuth2ClientAuthenticationToken.class.isAssignableFrom(authentication);
        }
    }
}
