package com.flowtivo.tinfirm.identity.internal;

import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.flowtivo.tinfirm.identity.IdentityProviderNames;

@Component
class IdentityProviderNamesImpl implements IdentityProviderNames {

    private final TinfirmClientRegistrations registrations;

    private final SamlProperties saml;

    IdentityProviderNamesImpl(TinfirmClientRegistrations registrations, SamlProperties saml) {
        this.registrations = registrations;
        this.saml = saml;
    }

    /** An OIDC registration's client name, or a SAML provider's {@code _NAME} (ADR-0086); ids are unique across both. */
    @Override
    public String nameOf(String registrationId) {
        ClientRegistration registration = registrations.findByRegistrationId(registrationId);
        if (registration != null && StringUtils.hasText(registration.getClientName())) {
            return registration.getClientName();
        }
        SamlProperties.Provider provider = saml.provider(registrationId);
        return provider != null && provider.name() != null ? provider.name() : registrationId;
    }
}
