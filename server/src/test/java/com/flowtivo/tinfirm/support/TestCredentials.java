package com.flowtivo.tinfirm.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;

/**
 * A system's credentials through the API (ADR-0072): adding one with a token, and lending it to a service account,
 * which a trigger's "Use the accounts of" then fills its row with. The system's "who am I" must accept the token: the
 * caller stubs it first. TEST FIXTURE ONLY: tokens are test values.
 */
public final class TestCredentials {

    private TestCredentials() {
    }

    /**
     * Adds the token as a credential of the system and answers its id.
     *
     * @param companyPath the company's API path, {@code /api/companies/{companyId}}
     */
    public static UUID add(ApiClient admin, String companyPath, UUID systemId, String name, String token) {
        ApiResponse response = admin.post(companyPath + "/systems/" + systemId + "/credentials", Map.of("name", name,
                "token", token));
        assertThat(response.status()).as("credential: %s", response).isEqualTo(201);
        return UUID.fromString(response.json().get("id").asString());
    }

    /** Lends the credential to the service account, {@code READ} or {@code WRITE}. */
    public static void lend(ApiClient admin, String companyPath, UUID systemId, UUID credentialId,
            UUID serviceIdentityId, String access) {
        ApiResponse response = admin.put(companyPath + "/systems/" + systemId + "/credentials/" + credentialId
                + "/service-accounts/" + serviceIdentityId, Map.of("access", access));
        assertThat(response.status()).as("lending: %s", response).isEqualTo(200);
    }

    /**
     * What a service identity's own account was before ADR-0072: the token as a credential of the system, named after
     * the service account, lent to it {@code WRITE}. Answers the credential's id.
     */
    public static UUID lendToken(ApiClient admin, String companyPath, UUID serviceIdentityId, UUID systemId,
            String token) {
        UUID credential = add(admin, companyPath, systemId, "Credential of " + serviceIdentityId, token);
        lend(admin, companyPath, systemId, credential, serviceIdentityId, "WRITE");
        return credential;
    }
}
