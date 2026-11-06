package com.flowtivo.tinfirm.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;

/**
 * L32 and M17 of the security review of 2026-10-27: the server does not start on an OIDC registration without an
 * {@code issuer-uri}, whose ID tokens' {@code iss} Spring would not check, or without the {@code openid} scope, whose
 * sign-in would skip the provisioning.
 */
class TinfirmClientRegistrationsTest {

    private static final String ISSUER = "https://idp.example.com/realms/acme";

    @Test
    void anOidcRegistrationWithAnIssuerPasses() {
        assertThat(TinfirmClientRegistrations.problems(List.of(registration("acme", ISSUER, "openid", "email"),
                registration("acmetwo", ISSUER, "openid")))).as("two registrations may share an issuer").isEmpty();
    }

    @Test
    void aRegistrationWithoutAnIssuerOrTheOpenidScopeIsRefused() {
        assertThat(TinfirmClientRegistrations.problems(List.of(registration("explicit", null, "openid"))))
            .singleElement()
            .asString()
            .contains("the registration explicit has no issuer-uri");
        assertThat(TinfirmClientRegistrations.problems(List.of(registration("plain", ISSUER, "profile", "email"))))
            .singleElement()
            .asString()
            .contains("the registration plain does not ask for the openid scope");
    }

    private static ClientRegistration registration(String id, String issuer, String... scopes) {
        return ClientRegistration.withRegistrationId(id)
            .clientId("tinfirm")
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri("https://tinfirm.example.com/login/oauth2/code/" + id)
            .authorizationUri("https://idp.example.com/auth")
            .tokenUri("https://idp.example.com/token")
            .issuerUri(issuer)
            .scope(scopes)
            .build();
    }
}
