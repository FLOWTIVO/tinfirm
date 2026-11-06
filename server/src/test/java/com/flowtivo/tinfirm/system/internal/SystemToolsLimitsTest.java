package com.flowtivo.tinfirm.system.internal;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;

import com.flowtivo.tinfirm.shared.security.SystemContext;
import com.flowtivo.tinfirm.shared.tx.SystemTransactions;
import com.flowtivo.tinfirm.support.ApiClient;
import com.flowtivo.tinfirm.support.IntegrationTest;
import com.flowtivo.tinfirm.support.JiraStub;
import com.flowtivo.tinfirm.support.McpStub;
import com.flowtivo.tinfirm.system.CallCredential;
import com.flowtivo.tinfirm.system.ChangeRequests;
import com.flowtivo.tinfirm.system.SystemTools;
import com.flowtivo.tinfirm.system.ToolCallResult;
import com.flowtivo.tinfirm.system.ToolOutcome;

import tools.jackson.databind.json.JsonMapper;

/**
 * The bounds of a tool call (the package 3 contract, §5.4; CONVENTIONS §10): no call runs inside a database
 * transaction; a call that does not finish within the tool timeout is answered {@code TOOL_FAILED}; a result larger
 * than the protocol carries is {@code RESULT_TOO_LARGE} with a message to narrow the query. TEST FIXTURE ONLY: the
 * tokens are test values.
 */
class SystemToolsLimitsTest extends IntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final CallCredential TOKEN = new CallCredential("test-limits-token-0001", false, null, null);

    @Autowired
    private SystemTools tools;

    @Autowired
    private SystemTransactions transactions;

    @Autowired
    private SystemStore systems;

    @Autowired
    private CatalogueStore catalogue;

    @Autowired
    private SystemClients clients;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private record Jira(TestCompany company, UUID systemId, JiraStub.Instance stub) {
    }

    private Jira jira() {
        TestCompany company = companyWithRoles();
        ApiClient admin = loginAs(company.email("ADMIN"));
        JiraStub.Instance stub = JiraStub.newInstance();
        return new Jira(company, createSystem(admin, company, "Jira", "JIRA_DC", stub.baseUrl(), false), stub);
    }

    @Test
    void noCallRunsInsideATransaction() {
        Jira jira = jira();
        UUID companyId = jira.company().id();

        assertThatThrownBy(() -> transactions.inCompany(companyId, () -> tools.call(companyId, jira.systemId(), TOKEN,
                "get", JSON.readTree("{\"key\":\"SHOP-1\"}"))))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("outside a database transaction");
        assertThatThrownBy(() -> transactions.inCompany(companyId, () -> tools.openChangeRequest(companyId,
                UUID.randomUUID(), TOKEN, new ChangeRequests.New("t", "agent/x-1", null, "b"))))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> transactions.inCompany(companyId, () -> tools.changeRequestState(companyId,
                UUID.randomUUID(), TOKEN, 1)))
            .isInstanceOf(IllegalStateException.class);
        assertThat(JiraStub.server().findAll(com.github.tomakehurst.wiremock.client.WireMock
            .getRequestedFor(urlPathEqualTo(jira.stub().url("/rest/api/2/issue/SHOP-1"))))).isEmpty();

        // Outside a transaction, in the company's tenant context, the call goes.
        JiraStub.server().stubFor(get(urlPathEqualTo(jira.stub().url("/rest/api/2/issue/SHOP-1")))
            .willReturn(aResponse().withStatus(404)));
        assertThat(SystemContext.callInCompany(companyId, () -> tools.call(companyId, jira.systemId(), TOKEN, "get",
                JSON.readTree("{\"key\":\"SHOP-1\"}")))).isInstanceOf(ToolOutcome.Done.class);
    }

    @Test
    void aCallThatDoesNotFinishWithinTheTimeoutFails() {
        Jira jira = jira();
        UUID companyId = jira.company().id();
        JiraStub.server().stubFor(get(urlPathEqualTo(jira.stub().url("/rest/api/2/issue/SLOW-1")))
            .willReturn(aResponse().withStatus(200).withFixedDelay(3000).withBody("{}")));
        SystemToolsImpl bounded = new SystemToolsImpl(systems, catalogue, clients, transactionManager,
                Duration.ofSeconds(1));
        try {
            long started = System.nanoTime();
            ToolOutcome outcome = SystemContext.callInCompany(companyId, () -> bounded.call(companyId,
                    jira.systemId(), TOKEN, "get", JSON.readTree("{\"key\":\"SLOW-1\"}")));
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(2500));
            assertThat(outcome).isEqualTo(new ToolOutcome.Refused(ToolOutcome.Code.TOOL_FAILED, "`get` on Jira did not "
                    + "finish within 1 s."));
        }
        finally {
            bounded.close();
        }
    }

    @Test
    void aResultLargerThanTheProtocolCarriesAsksToNarrowTheQuery() {
        TestCompany company = companyWithRoles();
        ApiClient admin = loginAs(company.email("ADMIN"));
        McpStub.Instance mcp = McpStub.newInstance().serves(List.of(McpStub.tool("dump", null),
                McpStub.tool("small", null)));
        mcp.answers("dump", McpStub.textResult("x".repeat(SystemTools.MAX_RESULT_BYTES), false));
        mcp.answers("small", McpStub.textResult("x".repeat(SystemTools.MAX_RESULT_BYTES - 200), false));
        UUID systemId = createSystem(admin, company, "Files", "MCP", mcp.url(), false);
        assertThat(admin.post(company.path("/systems/" + systemId + "/tools/refresh"), Map.of()).status())
            .isEqualTo(200);

        ToolOutcome large = SystemContext.callInCompany(company.id(), () -> tools.call(company.id(), systemId,
                CallCredential.none(), "dump", JSON.createObjectNode()));
        assertThat(large).isEqualTo(new ToolOutcome.Refused(ToolOutcome.Code.RESULT_TOO_LARGE, "The result of `dump` "
                + "on Files is larger than the 800 KiB a result may have. Narrow the query (a smaller `limit`, a more "
                + "precise query, fewer fields) and call it again."));

        ToolOutcome fits = SystemContext.callInCompany(company.id(), () -> tools.call(company.id(), systemId,
                CallCredential.none(), "small", JSON.createObjectNode()));
        assertThat(fits).isInstanceOf(ToolOutcome.Done.class);
    }

    @Test
    void theTokenIsTakenOutOfTheTextAndTheStructuredContent() {
        String token = TOKEN.token();
        ToolOutcome echoed = new ToolOutcome.Done(ToolCallResult.text("Echo: " + token, JSON.readTree("{\"echo\": "
                + "{\"auth\": \"Bearer " + token + "\", \"list\": [\"" + token + "\", 1]}, \"kept\": \"other\"}"),
                false), null, null);

        ToolOutcome.Done scrubbed = (ToolOutcome.Done) SystemToolsImpl.scrubbed(echoed, token);

        assertThat(scrubbed.result().text()).isEqualTo("Echo: ***");
        assertThat(scrubbed.result().structuredContent()).isEqualTo(JSON.readTree("{\"echo\": {\"auth\": "
                + "\"Bearer ***\", \"list\": [\"***\", 1]}, \"kept\": \"other\"}"));
        assertThat(JSON.writeValueAsString(scrubbed.result())).doesNotContain(token);
    }
}
