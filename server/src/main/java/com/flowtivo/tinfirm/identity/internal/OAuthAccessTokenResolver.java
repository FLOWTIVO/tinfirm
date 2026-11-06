package com.flowtivo.tinfirm.identity.internal;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.shared.security.ApiScope;
import com.flowtivo.tinfirm.shared.security.AuthenticatedUser;
import com.flowtivo.tinfirm.shared.security.BearerGrant;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The second {@link BearerCredentialResolver} of the MCP chain (ADR-0045): the access tokens of Tinfirm's own
 * authorization server, recognised by their JWT shape. A token is accepted when
 * <ul>
 * <li>its signature verifies against the instance's signing key (the same {@code JWKSource} the JWK set publishes),
 * its {@code iss} is the issuer, and it has not expired;</li>
 * <li>its {@code aud} holds the canonical URI of the company endpoint of this very request, so a token for company A
 * is refused at company B;</li>
 * <li>its authorization still exists with this access token, not revoked: revoking the grant on {@code /me}, or the
 * token at {@code /oauth2/revoke}, takes effect at once, not after the token's hour;</li>
 * <li>it carries a {@code tinfirm:*} scope (the highest one counts);</li>
 * <li>its user ({@code sub}) is active.</li>
 * </ul>
 * The grant of the request is the OAuth grant (client, user, company) with {@link OAuthGrantIds its derived id}.
 */
@Component
@Order(OAuthAccessTokenResolver.ORDER)
class OAuthAccessTokenResolver implements BearerCredentialResolver {

    static final int ORDER = PersonalAccessTokenResolver.ORDER + 10;

    private static final Logger log = LoggerFactory.getLogger(OAuthAccessTokenResolver.class);

    private static final Pattern JWT = Pattern.compile("^[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+$");

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final NimbusJwtDecoder decoder;

    private final OAuthResources resources;

    private final OAuthCompanyAccess users;

    private final JdbcClient jdbc;

    OAuthAccessTokenResolver(JWKSource<SecurityContext> jwkSource, OAuthResources resources, OAuthCompanyAccess users,
            JdbcClient jdbc) {
        this.decoder = NimbusJwtDecoder.withJwkSource(jwkSource).build();
        this.decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(resources.issuer()));
        this.resources = resources;
        this.users = users;
        this.jdbc = jdbc;
    }

    @Override
    public boolean supports(String token) {
        return token.length() <= 8192 && JWT.matcher(token).matches();
    }

    @Override
    public Optional<ResolvedBearer> resolve(String token) {
        Optional<UUID> company = OAuthResources.companyOfCurrentRequest();
        if (company.isEmpty()) {
            return Optional.empty();
        }
        Jwt jwt;
        try {
            jwt = decoder.decode(token);
        }
        catch (JwtException ex) {
            log.debug("Access token refused: {}", ex.getMessage());
            return Optional.empty();
        }
        List<String> audience = jwt.getAudience();
        if (audience == null || !audience.contains(resources.resource(company.get()))) {
            return Optional.empty();
        }
        String clientId = jwt.getClaimAsString(OAuthAccessTokenCustomizer.CLIENT_ID_CLAIM);
        String authorizationId = jwt.getClaimAsString(OAuthAccessTokenCustomizer.AUTHORIZATION_ID_CLAIM);
        Optional<ApiScope> scope = OAuthScopes.highest(scopes(jwt));
        if (clientId == null || authorizationId == null || scope.isEmpty() || !stillAuthorized(authorizationId, token)) {
            return Optional.empty();
        }
        Optional<AuthenticatedUser> user = users.activeUser(jwt.getSubject());
        return user.map(active -> new ResolvedBearer(active, new BearerGrant(BearerGrant.Kind.OAUTH_GRANT,
                OAuthGrantIds.of(clientId, active.id(), company.get()), scope.get())));
    }

    private static Collection<String> scopes(Jwt jwt) {
        Object scope = jwt.getClaim("scope");
        if (scope instanceof String text) {
            return List.of(text.split(" "));
        }
        if (scope instanceof Collection<?> values) {
            return values.stream().map(String::valueOf).toList();
        }
        return List.of();
    }

    /** Whether the authorization still holds this access token, not invalidated (revoked). */
    private boolean stillAuthorized(String authorizationId, String token) {
        Optional<String> metadata = jdbc.sql("""
                SELECT coalesce(access_token_metadata, '{}') FROM oauth2_authorization
                 WHERE id = ? AND access_token_value = ?""")
            .params(authorizationId, token)
            .query(String.class)
            .optional();
        if (metadata.isEmpty()) {
            return false;
        }
        try {
            JsonNode invalidated = JSON.readTree(metadata.get()).path("metadata.token.invalidated");
            return !invalidated.asBoolean(false);
        }
        catch (RuntimeException ex) {
            return false;
        }
    }
}
