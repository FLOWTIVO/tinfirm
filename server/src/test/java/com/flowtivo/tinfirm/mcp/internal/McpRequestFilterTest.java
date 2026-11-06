package com.flowtivo.tinfirm.mcp.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;

import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import com.flowtivo.tinfirm.ee.licence.TestLicences;
import com.flowtivo.tinfirm.identity.McpChallenges;
import com.flowtivo.tinfirm.shared.security.ActorAuthentication;
import com.flowtivo.tinfirm.shared.security.ApiScope;
import com.flowtivo.tinfirm.shared.security.AuthenticatedUser;
import com.flowtivo.tinfirm.shared.security.BearerGrant;
import com.flowtivo.tinfirm.shared.security.BearerGrantHolder;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The rate limit, the scope check, the body cap and the batch refusal of the MCP endpoint (ADR-0045), with stub tools
 * of each scope; {@code McpEndpointIntegrationTest} and {@code McpRateLimitIntegrationTest} exercise the same rules
 * with the real tools end to end.
 */
class McpRequestFilterTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final UUID COMPANY = UUID.fromString("0190f5a4-1c2d-7e3f-8a9b-0c1d2e3f4a5b");

    /** A stub tool set: one read tool, one write tool. */
    static final class StubTools implements McpToolSet {

        @McpTool(name = "stub_read", description = "Reads.")
        @ToolScope(ApiScope.READ)
        String read() {
            return "read";
        }

        @McpTool(name = "stub_write", description = "Writes.")
        @ToolScope(ApiScope.WRITE)
        String write() {
            return "written";
        }

        @McpTool(name = "stub_decide", description = "Decides.")
        @ToolScope(ApiScope.DECIDE)
        String decide() {
            return "decided";
        }
    }

    static final class Unscoped implements McpToolSet {

        @McpTool(name = "unscoped", description = "Has no scope.")
        String unscoped() {
            return "";
        }
    }

    private final McpChallenges challenges = (companyId, required) -> "Bearer error=\"insufficient_scope\", scope=\""
            + required.oauthScope() + "\", resource_metadata=\"https://tinfirm.test/.well-known/oauth-protected-resource"
            + "/api/companies/" + companyId + "/mcp\"";

    private final McpRequestFilter filter = new McpRequestFilter(McpToolCatalogue.of(List.of(StubTools.class)),
            challenges, McpRateLimiter.off(), TestLicences.licensing(TestLicences.ENTERPRISE), companyId -> true);

    private record Outcome(MockHttpServletResponse response, HttpServletRequest passed) {
    }

    private Outcome post(String body, ApiScope scope) throws Exception {
        return post(filter, body, new BearerGrant(BearerGrant.Kind.PERSONAL_ACCESS_TOKEN, UUID.randomUUID(), scope));
    }

    private static Outcome post(McpRequestFilter filter, String body, @org.jspecify.annotations.Nullable BearerGrant grant)
            throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/companies/" + COMPANY + "/mcp");
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        request.setContentType("application/json");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<HttpServletRequest> passed = new AtomicReference<>();
        FilterChain chain = (req, res) -> passed.set((HttpServletRequest) req);
        if (grant == null) {
            filter.doFilter(request, response, chain);
        }
        else {
            try (BearerGrantHolder.Scope ignored = BearerGrantHolder.open(grant)) {
                filter.doFilter(request, response, chain);
            }
        }
        return new Outcome(response, passed.get());
    }

    private McpRequestFilter limited(int perMinute) {
        return new McpRequestFilter(McpToolCatalogue.of(List.of(StubTools.class)), challenges,
                new McpRateLimiter(perMinute), TestLicences.licensing(TestLicences.ENTERPRISE), companyId -> true);
    }

    private static String toolCall(String tool) {
        return "{\"jsonrpc\":\"2.0\",\"id\":42,\"method\":\"tools/call\",\"params\":{\"name\":\"" + tool
                + "\",\"arguments\":{}}}";
    }

    /** ADR-0077, decision 4: the Outlook add-in's task pane, the one caller with an Origin, needs Pro or Enterprise. */
    @Test
    void theOutlookAddInsCallsNeedAPaidEdition() throws Exception {
        McpRequestFilter community = new McpRequestFilter(McpToolCatalogue.of(List.of(StubTools.class)), challenges,
                McpRateLimiter.off(), TestLicences.licensing(com.flowtivo.tinfirm.shared.licence.LicenceStatus.COMMUNITY),
                companyId -> true);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/companies/" + COMPANY + "/mcp");
        request.setContent(toolCall("stub_read").getBytes(StandardCharsets.UTF_8));
        request.setContentType("application/json");
        request.addHeader("Origin", "https://tinfirm.test");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<HttpServletRequest> passed = new AtomicReference<>();

        community.doFilter(request, response, (req, res) -> passed.set((HttpServletRequest) req));

        assertThat(passed.get()).isNull();
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("EDITION_REQUIRED")
            .contains("\"feature\":\"OUTLOOK_ADDIN\"", "\"edition\":\"PRO\"");
        assertThat(post(community, toolCall("stub_read"), new BearerGrant(BearerGrant.Kind.PERSONAL_ACCESS_TOKEN,
                UUID.randomUUID(), ApiScope.READ)).passed()).as("an AI client sends no Origin").isNotNull();
    }

    /**
     * The edition is answered after the access check: a caller who may not reach the company never learns it. They
     * are refused as every non-member is, whatever the method: 404, as the REST API answers them, and no code.
     */
    @Test
    void aCallerWhoMayNotReachTheCompanyGetsTheUsualAnswerNotTheEdition() throws Exception {
        McpRequestFilter community = new McpRequestFilter(McpToolCatalogue.of(List.of(StubTools.class)), challenges,
                McpRateLimiter.off(), TestLicences.licensing(com.flowtivo.tinfirm.shared.licence.LicenceStatus.COMMUNITY),
                companyId -> false);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/companies/" + COMPANY + "/mcp");
        request.setContent(toolCall("stub_read").getBytes(StandardCharsets.UTF_8));
        request.setContentType("application/json");
        request.addHeader("Origin", "https://tinfirm.test");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<HttpServletRequest> passed = new AtomicReference<>();

        try (BearerGrantHolder.Scope ignored = BearerGrantHolder.open(new BearerGrant(
                BearerGrant.Kind.PERSONAL_ACCESS_TOKEN, UUID.randomUUID(), ApiScope.READ))) {
            community.doFilter(request, response, (req, res) -> passed.set((HttpServletRequest) req));
        }

        assertThat(passed.get()).as("refused before the endpoint, as a non-member").isNull();
        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(response.getContentAsString()).contains("Company not found").doesNotContain("EDITION_REQUIRED")
            .doesNotContain("\"code\":\"");
    }

    @Test
    void aToolBeyondTheScopeIsRefusedWithTheStepUpChallenge() throws Exception {
        Outcome refused = post(toolCall("stub_write"), ApiScope.READ);
        assertThat(refused.passed()).isNull();
        assertThat(refused.response().getStatus()).isEqualTo(403);
        assertThat(refused.response().getHeader("WWW-Authenticate"))
            .isEqualTo("Bearer error=\"insufficient_scope\", scope=\"tinfirm:write\", resource_metadata=\""
                    + "https://tinfirm.test/.well-known/oauth-protected-resource/api/companies/" + COMPANY + "/mcp\"");
        assertThat(refused.response().getContentType()).startsWith("application/json");
        JsonNode body = JSON.readTree(refused.response().getContentAsString());
        assertThat(body.get("jsonrpc").asString()).isEqualTo("2.0");
        assertThat(body.get("id").asInt()).isEqualTo(42);
        assertThat(body.get("error").get("code").asInt()).isEqualTo(McpRequestFilter.INSUFFICIENT_SCOPE_CODE);
        assertThat(body.get("error").get("message").asString()).contains("stub_write").contains("tinfirm:write");
        assertThat(body.get("error").get("data").get("code").asString()).isEqualTo("INSUFFICIENT_SCOPE");
        assertThat(body.get("error").get("data").get("scope").asString()).isEqualTo("tinfirm:write");
    }

    @Test
    void aToolWithinTheScopePassesWithItsBodyAndAnAcceptTheTransportTakes() throws Exception {
        for (ApiScope scope : List.of(ApiScope.WRITE, ApiScope.DECIDE)) {
            Outcome passed = post(toolCall("stub_write"), scope);
            assertThat(passed.passed()).as("%s", scope).isNotNull();
            assertThat(new String(passed.passed().getInputStream().readAllBytes(), StandardCharsets.UTF_8))
                .isEqualTo(toolCall("stub_write"));
            assertThat(passed.passed().getHeader("Accept")).isEqualTo("application/json, text/event-stream");
        }
        assertThat(post(toolCall("stub_read"), ApiScope.READ).passed()).isNotNull();
        // The server's other methods, and names that are no tool, are the server's to answer.
        assertThat(post("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}", ApiScope.READ).passed())
            .isNotNull();
        assertThat(post(toolCall("no_such_tool"), ApiScope.READ).passed()).isNotNull();
    }

    @Test
    void aMethodTheServerDoesNotServeIsMethodNotFoundNeverTheTransportsFailure() throws Exception {
        for (String method : List.of("resources/list", "prompts/list", "completion/complete", "logging/setLevel")) {
            Outcome unserved = post("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"" + method + "\"}",
                    ApiScope.DECIDE);
            assertThat(unserved.passed()).as(method).isNull();
            assertThat(unserved.response().getStatus()).isEqualTo(200);
            JsonNode body = JSON.readTree(unserved.response().getContentAsString());
            assertThat(body.get("id").asInt()).isEqualTo(3);
            assertThat(body.get("error").get("code").asInt()).isEqualTo(McpRequestFilter.METHOD_NOT_FOUND_CODE);
            assertThat(body.get("error").get("message").asString()).isEqualTo("Method not found: " + method);
        }
        for (String method : McpRequestFilter.SERVED_METHODS) {
            assertThat(post("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"" + method + "\",\"params\":{\"name\":"
                    + "\"stub_read\"}}", ApiScope.READ).passed()).as(method).isNotNull();
        }
        // A notification gets no answer: the server takes it.
        assertThat(post("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}", ApiScope.READ).passed())
            .isNotNull();
    }

    @Test
    void whatTheFilterCannotReadNeverReachesTheTransport() throws Exception {
        Outcome malformed = post("{not json", ApiScope.DECIDE);
        assertThat(malformed.passed()).isNull();
        assertThat(malformed.response().getStatus()).isEqualTo(400);
        assertThat(JSON.readTree(malformed.response().getContentAsString()).get("error").get("code").asInt())
            .isEqualTo(-32700);
        // A duplicate key cannot smuggle a write tool past the check behind a read tool's name.
        Outcome duplicate = post("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":"
                + "{\"name\":\"stub_read\",\"name\":\"stub_write\",\"arguments\":{}}}", ApiScope.READ);
        assertThat(duplicate.passed()).isNull();
        assertThat(duplicate.response().getStatus()).isEqualTo(400);
    }

    @Test
    void aBatchIsRefused() throws Exception {
        Outcome batch = post("[" + toolCall("stub_read") + "," + toolCall("stub_write") + "]", ApiScope.DECIDE);
        assertThat(batch.passed()).isNull();
        assertThat(batch.response().getStatus()).isEqualTo(400);
        JsonNode body = JSON.readTree(batch.response().getContentAsString());
        assertThat(body.get("id").isNull()).isTrue();
        assertThat(body.get("error").get("code").asInt()).isEqualTo(-32600);
    }

    @Test
    void aBodyAboveOneMebibyteIsRefused() throws Exception {
        String large = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\",\"params\":{\"x\":\""
                + "a".repeat(McpRequestFilter.MAX_BODY_BYTES) + "\"}}";
        Outcome refused = post(large, ApiScope.DECIDE);
        assertThat(refused.passed()).isNull();
        assertThat(refused.response().getStatus()).isEqualTo(413);
        String justUnder = "{\"x\":\"" + "a".repeat(McpRequestFilter.MAX_BODY_BYTES - 10) + "\"}";
        assertThat(justUnder.length()).isLessThanOrEqualTo(McpRequestFilter.MAX_BODY_BYTES);
        assertThat(post(justUnder, ApiScope.READ).passed()).isNotNull();
    }

    @Test
    void onlyPostsToTheEndpointItselfAreFiltered() throws Exception {
        MockHttpServletRequest get = new MockHttpServletRequest("GET", "/api/companies/" + COMPANY + "/mcp");
        assertThat(filter.shouldNotFilter(get)).isTrue();
        assertThat(filter.shouldNotFilter(new MockHttpServletRequest("POST", "/api/companies/" + COMPANY + "/members")))
            .isTrue();
        assertThat(filter.shouldNotFilter(new MockHttpServletRequest("POST", "/api/companies/not-a-uuid/mcp")))
            .isTrue();
        assertThat(filter.shouldNotFilter(new MockHttpServletRequest("POST", "/api/companies/" + COMPANY + "/mcp")))
            .isFalse();
    }

    @Test
    void theCatalogueNeedsAScopeOnEveryTool() {
        McpToolCatalogue catalogue = McpToolCatalogue.of(List.of(StubTools.class));
        assertThat(catalogue.requiredScope("stub_read")).contains(ApiScope.READ);
        assertThat(catalogue.requiredScope("stub_write")).contains(ApiScope.WRITE);
        assertThat(catalogue.requiredScope("nothing")).isEmpty();
        assertThatThrownBy(() -> McpToolCatalogue.of(List.of(Unscoped.class)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("unscoped")
            .hasMessageContaining("@ToolScope");
    }

    @Test
    void theDecideScopeIsNeededForADecideTool() throws Exception {
        for (ApiScope scope : List.of(ApiScope.READ, ApiScope.WRITE)) {
            Outcome refused = post(toolCall("stub_decide"), scope);
            assertThat(refused.passed()).as("%s", scope).isNull();
            assertThat(refused.response().getStatus()).isEqualTo(403);
            assertThat(refused.response().getHeader("WWW-Authenticate")).contains("scope=\"tinfirm:decide\"");
            assertThat(JSON.readTree(refused.response().getContentAsString()).get("error").get("data").get("scope")
                .asString()).isEqualTo("tinfirm:decide");
        }
        assertThat(post(toolCall("stub_decide"), ApiScope.DECIDE).passed()).isNotNull();
    }

    @Test
    void aCredentialOverItsRateLimitIsRefusedWith429AndRetryAfterBeforeTheScopeCheck() throws Exception {
        McpRequestFilter limited = limited(2);
        BearerGrant grant = new BearerGrant(BearerGrant.Kind.OAUTH_GRANT, UUID.randomUUID(), ApiScope.READ);
        assertThat(post(limited, toolCall("stub_read"), grant).passed()).isNotNull();
        // A refused scope spends the bucket as well: every request counts.
        assertThat(post(limited, toolCall("stub_write"), grant).response().getStatus()).isEqualTo(403);
        Outcome refused = post(limited, toolCall("stub_write"), grant);
        assertThat(refused.passed()).isNull();
        assertThat(refused.response().getStatus()).isEqualTo(429);
        assertThat(Long.parseLong(refused.response().getHeader("Retry-After"))).isBetween(1L, 30L);
        assertThat(refused.response().getHeader("WWW-Authenticate")).isNull();
        JsonNode body = JSON.readTree(refused.response().getContentAsString());
        assertThat(body.get("jsonrpc").asString()).isEqualTo("2.0");
        assertThat(body.get("id").asInt()).isEqualTo(42);
        assertThat(body.get("error").get("code").asInt()).isEqualTo(McpRequestFilter.RATE_LIMITED_CODE);
        assertThat(body.get("error").get("data").get("code").asString()).isEqualTo("RATE_LIMITED");
        // Another credential has its own bucket.
        BearerGrant other = new BearerGrant(BearerGrant.Kind.PERSONAL_ACCESS_TOKEN, UUID.randomUUID(), ApiScope.READ);
        assertThat(post(limited, toolCall("stub_read"), other).passed()).isNotNull();
        // A body the filter would refuse is refused for the rate first, with no id.
        assertThat(post(limited, "{not json", grant).response().getStatus()).isEqualTo(429);
        assertThat(JSON.readTree(post(limited, "{not json", grant).response().getContentAsString()).get("id").isNull())
            .isTrue();
    }

    @Test
    void withoutAGrantTheUsersBucketIsSpent() throws Exception {
        McpRequestFilter limited = limited(1);
        AuthenticatedUser user = new AuthenticatedUser(UUID.randomUUID(), "u@example.test", "U", false);
        SecurityContextHolder.getContext().setAuthentication(new ActorAuthentication(user));
        try {
            assertThat(post(limited, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}", null).passed())
                .isNotNull();
            assertThat(post(limited, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\"}", null).response()
                .getStatus()).isEqualTo(429);
        }
        finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void aLimitOfZeroAdmitsEverything() throws Exception {
        McpRequestFilter unlimited = limited(0);
        BearerGrant grant = new BearerGrant(BearerGrant.Kind.OAUTH_GRANT, UUID.randomUUID(), ApiScope.READ);
        for (int i = 0; i < 500; i++) {
            assertThat(post(unlimited, toolCall("stub_read"), grant).passed()).as("request %d", i).isNotNull();
        }
        assertThatThrownBy(() -> new McpRateLimiter(-1)).isInstanceOf(IllegalArgumentException.class);
    }
}
