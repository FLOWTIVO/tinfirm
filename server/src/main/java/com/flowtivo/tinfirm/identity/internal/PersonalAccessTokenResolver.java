package com.flowtivo.tinfirm.identity.internal;

import java.util.Optional;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * The first {@link BearerCredentialResolver}: personal access tokens, recognised by their {@code tfp_} prefix. A token
 * that names its companies is accepted only on the endpoint of one of them (CONVENTIONS §5).
 */
@Component
@Order(PersonalAccessTokenResolver.ORDER)
class PersonalAccessTokenResolver implements BearerCredentialResolver {

    static final int ORDER = 0;

    private final ApiTokenService tokens;

    PersonalAccessTokenResolver(ApiTokenService tokens) {
        this.tokens = tokens;
    }

    @Override
    public boolean supports(String token) {
        return ApiTokens.hasPrefix(token);
    }

    @Override
    public Optional<ResolvedBearer> resolve(String token) {
        return tokens.resolve(token, OAuthResources.companyOfCurrentRequest().orElse(null));
    }
}
