package com.flowtivo.tinfirm.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import com.flowtivo.tinfirm.support.ApiClient;
import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.IntegrationTest;

/**
 * The rate limit of the MCP endpoint (ADR-0045, threat model 10.9), with a small limit set through the property that
 * {@code TINFIRM_MCP_RATE_LIMIT_PER_MINUTE} sets: 30 requests per minute per credential, one back every 2 seconds.
 */
@TestPropertySource(properties = "tinfirm.mcp.rate-limit-per-minute=30")
class McpRateLimitIntegrationTest extends IntegrationTest {

    private static final int LIMIT = 30;

    private String token(ApiClient session) {
        ApiResponse response = session.post("/api/me/tokens", Map.of("currentPassword", PASSWORD,
                "name", "rate test", "scope", "READ"));
        assertThat(response.status()).as("create token: %s", response).isEqualTo(201);
        return response.json().get("token").asString();
    }

    private ApiResponse ping(UUID companyId, String token, int id) {
        return client().send("POST", "/api/companies/" + companyId + "/mcp",
                Map.of("jsonrpc", "2.0", "id", id, "method", "ping"), Map.of("Authorization", "Bearer " + token));
    }

    @Test
    void aCredentialOverTheLimitGets429WithRetryAfterAndRecovers() throws Exception {
        TestCompany company = companyWithRoles();
        ApiClient member = loginAs(company.email("MEMBER"));
        String token = token(member);
        String other = token(member);

        int admitted = 0;
        ApiResponse refused = null;
        for (int id = 1; id <= LIMIT + 5 && refused == null; id++) {
            ApiResponse response = ping(company.id(), token, id);
            if (response.status() == 429) {
                refused = response;
            }
            else {
                assertThat(response.status()).as("%s", response).isEqualTo(200);
                admitted++;
            }
        }
        assertThat(refused).as("the bucket empties").isNotNull();
        // At least the burst passes (a slow machine may get one or two back while it sends).
        assertThat(admitted).isBetween(LIMIT, LIMIT + 3);
        long retryAfter = Long.parseLong(refused.header("Retry-After"));
        assertThat(retryAfter).isBetween(1L, 2L);
        assertThat(refused.json().get("jsonrpc").asString()).isEqualTo("2.0");
        assertThat(refused.json().get("id").asInt()).isEqualTo(admitted + 1);
        assertThat(refused.json().get("error").get("data").get("code").asString()).isEqualTo("RATE_LIMITED");

        // Another token of the same person has its own bucket.
        assertThat(ping(company.id(), other, 1).status()).isEqualTo(200);

        // After Retry-After the credential may send again.
        Thread.sleep(retryAfter * 1000 + 200);
        ApiResponse recovered = ping(company.id(), token, 99);
        assertThat(recovered.status()).as("%s", recovered).isEqualTo(200);
        assertThat(recovered.json().get("id").asInt()).isEqualTo(99);
    }
}
