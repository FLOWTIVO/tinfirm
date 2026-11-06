package com.flowtivo.tinfirm.identity.internal;

import org.jspecify.annotations.Nullable;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsent;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;

/**
 * The authorization server's view of stored consents (ADR-0045): it records each consent, and never finds one.
 * Spring keeps one consent per client and user and, on a later consent of the same client, silently adds every scope
 * consented before; Tinfirm's consent is per company and asked every time, so a consent for company A must never
 * widen a consent for company B, and a person who ticks only "read" gets only read. The stored row is the latest
 * consent of that client by that user; "Connected apps" removes it with the client's last grant.
 */
final class RememberNoConsentService implements OAuth2AuthorizationConsentService {

    private final OAuth2AuthorizationConsentService stored;

    RememberNoConsentService(OAuth2AuthorizationConsentService stored) {
        this.stored = stored;
    }

    @Override
    public void save(OAuth2AuthorizationConsent authorizationConsent) {
        stored.save(authorizationConsent);
    }

    @Override
    public void remove(OAuth2AuthorizationConsent authorizationConsent) {
        stored.remove(authorizationConsent);
    }

    @Override
    public @Nullable OAuth2AuthorizationConsent findById(String registeredClientId, String principalName) {
        return null;
    }
}
