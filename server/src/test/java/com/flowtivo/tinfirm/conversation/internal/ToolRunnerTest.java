package com.flowtivo.tinfirm.conversation.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import com.flowtivo.tinfirm.account.AccountInfo;
import com.flowtivo.tinfirm.account.Accounts;
import com.flowtivo.tinfirm.account.GrantInfo;
import com.flowtivo.tinfirm.conversation.BindingInfo;
import com.flowtivo.tinfirm.conversation.ConversationBindings;
import com.flowtivo.tinfirm.system.CallCredential;
import com.flowtivo.tinfirm.system.SystemCalls;
import com.flowtivo.tinfirm.system.SystemKind;
import com.flowtivo.tinfirm.system.SystemTools;
import com.flowtivo.tinfirm.system.ToolCallResult;
import com.flowtivo.tinfirm.system.ToolEffect;
import com.flowtivo.tinfirm.system.ToolOutcome;

import tools.jackson.databind.json.JsonMapper;

/**
 * A tool call hands {@code SystemTools} the binding's token pinned to the endpoint it was revealed for (ADR-0079), so
 * that the system module sends it nowhere else; a binding without an account calls with no credential and no pin.
 * TEST FIXTURE ONLY: the token is a test value.
 */
class ToolRunnerTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final UUID COMPANY = UUID.randomUUID();

    private static final UUID SYSTEM = UUID.randomUUID();

    private static final UUID BINDING = UUID.randomUUID();

    private static final UUID ACCOUNT = UUID.randomUUID();

    private final ConversationCore core = mock(ConversationCore.class);

    private final ConversationBindings bindings = mock(ConversationBindings.class);

    private final SystemTools systemTools = mock(SystemTools.class);

    private final ToolRunner runner = new ToolRunner(core, systemTools, mock(Accounts.class), mock(ToolAudit.class),
            mock(ApplicationEventPublisher.class));

    private final ToolRunner.Prepared call = new ToolRunner.Prepared(COMPANY, UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), BINDING, SYSTEM, "wiki_search", "search", JSON.createObjectNode(), ToolEffect.READ,
            "Analyst", "Alice");

    private final BindingInfo bound = new BindingInfo(BINDING, COMPANY, call.conversationId(), SYSTEM,
            BindingInfo.State.BOUND, BindingInfo.How.OWN, ACCOUNT, UUID.randomUUID(), "alice", null, null, List.of(),
            null, Instant.now());

    private final AccountInfo account = new AccountInfo(ACCOUNT, COMPANY, SYSTEM, bound.holderPrincipalId(), null,
            null, AccountInfo.Kind.TOKEN, "alice", "alice", AccountInfo.Status.OK, true);

    ToolRunnerTest() {
        when(core.bindings()).thenReturn(bindings);
        when(systemTools.call(any(), any(), any(), any(), any(), any())).thenReturn(new ToolOutcome.Done(
                ToolCallResult.text("found", null, false), null, null));
    }

    @Test
    void theTokenGoesToSystemToolsPinnedToTheEndpointItWasRevealedFor() {
        CallCredential.Endpoint atA = new CallCredential.Endpoint(SystemKind.MCP, "https://a.test/mcp");
        when(bindings.use(COMPANY, BINDING, "run-tool:1")).thenReturn(new ConversationBindings.BindingUse.Token(bound,
                account, GrantInfo.Access.WRITE, new Accounts.RevealedCredential(new SystemCalls.Credential(
                        "tool-token-0001", false), null, atA)));

        ToolRunner.Ran ran = runner.run(call, "run-tool:1");

        assertThat(ran).isInstanceOf(ToolRunner.Ran.Called.class);
        ArgumentCaptor<CallCredential> credential = ArgumentCaptor.forClass(CallCredential.class);
        verify(systemTools).call(eq(COMPANY), eq(SYSTEM), credential.capture(), eq("search"), any(),
                eq("Analyst (Tinfirm agent):"));
        assertThat(credential.getValue().token()).isEqualTo("tool-token-0001");
        assertThat(credential.getValue().endpoint()).isEqualTo(atA);
        assertThat(credential.getValue().toString()).doesNotContain("tool-token-0001");
    }

    @Test
    void anOpenBindingCallsWithoutACredential() {
        when(bindings.use(COMPANY, BINDING, "run-tool:2")).thenReturn(new ConversationBindings.BindingUse.Open(bound));

        ToolRunner.Ran ran = runner.run(call, "run-tool:2");

        assertThat(ran).isInstanceOf(ToolRunner.Ran.Called.class);
        verify(systemTools).call(eq(COMPANY), eq(SYSTEM), eq(CallCredential.none()), eq("search"), any(),
                eq("Analyst (Tinfirm agent):"));
    }
}
