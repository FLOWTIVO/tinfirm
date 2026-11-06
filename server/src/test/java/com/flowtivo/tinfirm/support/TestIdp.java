package com.flowtivo.tinfirm.support;

import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * TEST FIXTURE ONLY: the issuer of the test profile's registration {@code test-idp} ({@code application-test.yaml}).
 * The server does not start on an OIDC registration without an {@code issuer-uri} (L32 of the security review of
 * 2026-10-27), and Spring reads the issuer's discovery document at the start, so every context of the test profile
 * names this stub's issuer. The registration's endpoints stay the profile's ({@code http://idp.test/...}): set
 * explicitly, they take precedence over the discovered ones.
 */
public final class TestIdp {

    private static final OidcProviderStub STUB = new OidcProviderStub("tinfirm-test", "test-only-client-secret");

    private TestIdp() {
    }

    /** Names the stub as the issuer of {@code test-idp}. */
    public static void register(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.client.provider.test-idp.issuer-uri", STUB::issuer);
    }
}
