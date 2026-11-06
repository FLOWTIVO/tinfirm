package com.flowtivo.tinfirm.system.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import com.flowtivo.tinfirm.system.CallCredential;
import com.flowtivo.tinfirm.system.CallResult;
import com.flowtivo.tinfirm.system.ChangeRequests;
import com.flowtivo.tinfirm.system.RepositoryInfo;
import com.flowtivo.tinfirm.system.SystemCalls;
import com.flowtivo.tinfirm.system.SystemKind;
import com.flowtivo.tinfirm.system.ToolEffect;
import com.flowtivo.tinfirm.system.ToolOutcome;
import com.flowtivo.tinfirm.system.internal.mcp.McpClient;

import tools.jackson.databind.json.JsonMapper;

/**
 * A tool call goes only to the endpoint its credential was revealed for (ADR-0079; {@link EndpointPin}): an MCP
 * server moved between the reveal and the call is as gone, no client is asked, and the refusal names no token and no
 * address; a server still at the credential's endpoint is called. TEST FIXTURE ONLY: the tokens are test values.
 */
class SystemToolsImplTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final UUID COMPANY = UUID.randomUUID();

    private static final UUID SYSTEM = UUID.randomUUID();

    private static final UUID REPOSITORY = UUID.randomUUID();

    private final SystemStore systems = mock(SystemStore.class);

    private final CatalogueStore catalogue = mock(CatalogueStore.class);

    private final SystemClients clients = mock(SystemClients.class);

    private final SystemToolsImpl tools = new SystemToolsImpl(systems, catalogue, clients,
            mock(PlatformTransactionManager.class), Duration.ofSeconds(5));

    /** The credential as it was revealed while the server was at a.test. */
    private final CallCredential atA = CallCredential.of(new SystemCalls.Credential("old-token-0001", false), null,
            new CallCredential.Endpoint(SystemKind.MCP, "https://a.test/mcp"));

    @AfterEach
    void close() {
        tools.close();
    }

    private static SystemStore.SystemRow row(SystemKind kind, String baseUrl) {
        Instant now = Instant.now();
        return new SystemStore.SystemRow(SYSTEM, COMPANY, "Wiki", "wiki", kind, baseUrl, null, null, null, null, null,
                JSON.createObjectNode(), "OK", null, now, now, null, null, true);
    }

    private static CatalogueStore.ToolRow enabled(String name) {
        return new CatalogueStore.ToolRow(name, null, null, JSON.createObjectNode(), ToolEffect.READ, true, false,
                true, true);
    }

    @Test
    void aServerMovedSinceTheRevealIsNotCalledWithTheOldToken() {
        when(systems.find(COMPANY, SYSTEM)).thenReturn(Optional.of(row(SystemKind.MCP, "https://b.test/mcp")));
        when(catalogue.findTool(COMPANY, SYSTEM, "search")).thenReturn(Optional.of(enabled("search")));

        ToolOutcome outcome = tools.call(COMPANY, SYSTEM, atA, "search", JSON.createObjectNode());

        assertThat(outcome).isEqualTo(new ToolOutcome.Refused(ToolOutcome.Code.UNKNOWN_TOOL,
                "The endpoint of Wiki changed meanwhile: nothing was sent to the new one"));
        assertThat(((ToolOutcome.Refused) outcome).message()).doesNotContain("old-token", "b.test");
        verifyNoInteractions(clients);
    }

    @Test
    void aServerStillAtTheRevealsEndpointIsCalled() {
        SystemStore.SystemRow row = row(SystemKind.MCP, "https://a.test/mcp");
        McpClient mcp = mock(McpClient.class);
        when(systems.find(COMPANY, SYSTEM)).thenReturn(Optional.of(row));
        when(catalogue.findTool(COMPANY, SYSTEM, "search")).thenReturn(Optional.of(enabled("search")));
        when(clients.mcp(row)).thenReturn(mcp);
        when(mcp.callTool(any(), eq("search"), any())).thenReturn(CallResult.ok(new McpClient.Called(List.of(JSON
            .createObjectNode()
            .put("type", "text")
            .put("text", "found")), null, false)));

        ToolOutcome outcome = tools.call(COMPANY, SYSTEM, atA, "search", JSON.createObjectNode());

        assertThat(outcome).isInstanceOf(ToolOutcome.Done.class);
        assertThat(((ToolOutcome.Done) outcome).result().text()).isEqualTo("found");
        verify(mcp).callTool(eq(new McpClient.Target("https://a.test/mcp", null, "old-token-0001")), eq("search"),
                any());
    }

    @Test
    void aCredentialPinnedToNoEndpointIsNotHeldBack() {
        // No token, no endpoint to protect (ADR-0071): the call goes where the row is.
        SystemStore.SystemRow row = row(SystemKind.MCP, "https://b.test/mcp");
        McpClient mcp = mock(McpClient.class);
        when(systems.find(COMPANY, SYSTEM)).thenReturn(Optional.of(row));
        when(catalogue.findTool(COMPANY, SYSTEM, "search")).thenReturn(Optional.of(enabled("search")));
        when(clients.mcp(row)).thenReturn(mcp);
        when(mcp.callTool(any(), eq("search"), any())).thenReturn(CallResult.ok(new McpClient.Called(List.of(), null,
                false)));

        assertThat(tools.call(COMPANY, SYSTEM, CallCredential.none(), "search", JSON.createObjectNode()))
            .isInstanceOf(ToolOutcome.Done.class);
        verify(mcp).callTool(eq(new McpClient.Target("https://b.test/mcp", null, null)), eq("search"), any());
    }

    @Test
    void aChangeRequestOfAMovedSystemIsNotReadOrOpenedWithTheOldToken() {
        CallCredential github = CallCredential.of(new SystemCalls.Credential("gh-token-0001", false), null,
                new CallCredential.Endpoint(SystemKind.GITHUB, "https://github.example"));
        when(catalogue.findRepository(COMPANY, REPOSITORY)).thenReturn(Optional.of(new RepositoryInfo(REPOSITORY,
                COMPANY, SYSTEM, "https://github.example/acme/shop", "acme/shop", "main")));
        when(systems.find(COMPANY, SYSTEM)).thenReturn(Optional.of(row(SystemKind.GITHUB, "https://other.example")));

        CallResult<ChangeRequests.Read> state = tools.changeRequestState(COMPANY, REPOSITORY, github, 7);
        CallResult<ChangeRequests.Opened> opened = tools.openChangeRequest(COMPANY, REPOSITORY, github,
                new ChangeRequests.New("t", "agent/x-1", null, "b"));
        CallResult<String> body = tools.changeRequestBody(COMPANY, REPOSITORY, github, 7);

        for (CallResult<?> result : new CallResult<?>[] { state, opened, body }) {
            assertThat(result.failure()).isNotNull();
            assertThat(result.failure().kind()).isEqualTo(CallResult.Kind.INVALID);
            assertThat(result.failureMessage()).isEqualTo("The endpoint of Wiki changed meanwhile: nothing was sent "
                    + "to the new one");
        }
        verifyNoInteractions(clients);
    }
}
