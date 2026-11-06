package com.flowtivo.tinfirm.system;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;

/**
 * An OAuth client's own credentials as a credential's secret keeps them ({@code CLIENT}): the client's id and secret,
 * and the access token it minted last, whose members are those of {@link OAuthTokens}. TEST FIXTURE ONLY: every
 * secret and token here is a test value.
 */
class ClientCredentialsTest {

    private static final Instant EXPIRES = Instant.parse("2026-10-26T12:00:00Z");

    @Test
    void theClientAndItsLastTokenRoundTrip() {
        OAuthTokens minted = new OAuthTokens("test-access", null, "Bearer", EXPIRES, "read:jira-work");
        ClientCredentials client = new ClientCredentials("test-client", "test-secret", null).minted(minted);

        ClientCredentials read = ClientCredentials.fromJson(client.json());

        assertThat(read).isEqualTo(client);
        assertThat(read.tokens()).isEqualTo(minted);
        // The same members as a sign-in's grant.
        assertThat(OAuthTokens.fromJson(client.json())).isEqualTo(minted);
    }

    @Test
    void aClientThatMintedNothingYetHasNoToken() {
        ClientCredentials client = new ClientCredentials("test-client", "test-secret", null);

        ClientCredentials read = ClientCredentials.fromJson(client.json());

        assertThat(read).isNotNull();
        assertThat(read.clientId()).isEqualTo("test-client");
        assertThat(read.clientSecret()).isEqualTo("test-secret");
        assertThat(read.tokens()).isNull();
    }

    @Test
    void aSecretWithoutBothOfTheClientsCredentialsIsNone() {
        assertThat(ClientCredentials.fromJson("not json")).isNull();
        assertThat(ClientCredentials.fromJson("[]")).isNull();
        assertThat(ClientCredentials.fromJson("{\"clientId\":\"test-client\"}")).isNull();
        assertThat(ClientCredentials.fromJson("{\"clientId\":\"test-client\",\"clientSecret\":\" \"}")).isNull();
        // A sign-in's grant is no client.
        assertThat(ClientCredentials.fromJson(new OAuthTokens("test-access", "test-refresh", "Bearer", EXPIRES, null)
            .json())).isNull();
    }

    @Test
    void neitherTheSecretNorTheTokenIsShown() {
        ClientCredentials client = new ClientCredentials("test-client", "test-secret", null)
            .minted(new OAuthTokens("test-access", null, "Bearer", EXPIRES, null));

        assertThat(client.toString()).contains("test-client").doesNotContain("test-secret")
            .doesNotContain("test-access");
    }
}
