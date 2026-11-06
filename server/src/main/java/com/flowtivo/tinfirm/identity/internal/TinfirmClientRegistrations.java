package com.flowtivo.tinfirm.identity.internal;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.util.StringUtils;

/**
 * The OIDC client registrations of the instance, from the environment
 * ({@code SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_*}); {@code GET /api/auth/providers} lists them. An empty
 * repository is valid: the OIDC login stays configured, and every registration id is unknown.
 */
final class TinfirmClientRegistrations implements ClientRegistrationRepository, Iterable<ClientRegistration> {

    private final Map<String, ClientRegistration> instance;

    TinfirmClientRegistrations(Collection<ClientRegistration> instance) {
        Map<String, ClientRegistration> byId = new LinkedHashMap<>();
        for (ClientRegistration registration : instance) {
            byId.put(registration.getRegistrationId(), registration);
        }
        this.instance = java.util.Collections.unmodifiableMap(byId);
    }

    @Override
    public @Nullable ClientRegistration findByRegistrationId(String registrationId) {
        return instance.get(registrationId);
    }

    /**
     * Why the registrations cannot sign people in, one sentence each; empty when they can. Every registration is an
     * OpenID Connect provider (L32 and M17 of the security review of 2026-10-27):
     * <ul>
     * <li>with an {@code issuer-uri}: Spring then checks the ID token's {@code iss} against it and reads the keys from
     * that issuer's discovery, and a user is keyed by (issuer, subject), so that no provider names another's issuer
     * or claims an Entra tenant it is not;</li>
     * <li>with the {@code openid} scope: without it the sign-in takes Spring's plain OAuth 2.0 path, which skips the
     * provisioning (no identity, no deactivation check) and leaves a session whose principal is no Tinfirm user.</li>
     * </ul>
     * Two registrations may share an issuer: their keys then come from the same discovery, and they are one IdP.
     */
    static List<String> problems(Collection<ClientRegistration> registrations) {
        List<String> problems = new ArrayList<>();
        for (ClientRegistration registration : registrations) {
            String id = registration.getRegistrationId();
            if (!StringUtils.hasText(registration.getProviderDetails().getIssuerUri())) {
                problems.add("the registration " + id + " has no issuer-uri: set "
                        + "SPRING_SECURITY_OAUTH2_CLIENT_PROVIDER_<PROVIDER>_ISSUER_URI to the provider's issuer, "
                        + "exactly as its ID tokens name it");
            }
            if (!registration.getScopes().contains(OidcScopes.OPENID)) {
                problems.add("the registration " + id + " does not ask for the openid scope: set "
                        + "SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_<ID>_SCOPE to openid,profile,email");
            }
        }
        return problems;
    }

    /** Whether the id names a registration of the instance (the environment's settings may only name those). */
    boolean isInstanceRegistration(String registrationId) {
        return instance.containsKey(registrationId);
    }

    @Override
    public Iterator<ClientRegistration> iterator() {
        return instance.values().iterator();
    }
}
