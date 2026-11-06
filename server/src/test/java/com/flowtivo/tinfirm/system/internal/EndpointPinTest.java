package com.flowtivo.tinfirm.system.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import com.flowtivo.tinfirm.system.CallCredential;
import com.flowtivo.tinfirm.system.CallResult;
import com.flowtivo.tinfirm.system.SystemCalls;
import com.flowtivo.tinfirm.system.SystemKind;
import com.flowtivo.tinfirm.system.SystemPolls;
import com.flowtivo.tinfirm.system.SystemWrites;
import com.flowtivo.tinfirm.system.internal.http.OutboundProperties;

import tools.jackson.databind.json.JsonMapper;

/**
 * The pin of a credential to the endpoint it was revealed for ({@link EndpointPin}, ADR-0079) holds a poll's session
 * and a tracker's write back as it holds a tool call ({@link SystemToolsImplTest}): a row whose kind or base URL is
 * another is {@code INVALID}, nothing is sent, and the message names no token. TEST FIXTURE ONLY: the tokens are test
 * values.
 */
class EndpointPinTest {

    private static final UUID COMPANY = UUID.randomUUID();

    private static final UUID SYSTEM = UUID.randomUUID();

    private final SystemStore systems = mock(SystemStore.class);

    private final SystemClients clients = mock(SystemClients.class);

    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);

    private static SystemStore.SystemRow row(String baseUrl) {
        Instant now = Instant.now();
        return new SystemStore.SystemRow(SYSTEM, COMPANY, "Jira", "jira", SystemKind.JIRA_DC, baseUrl, null, null,
                null, null, null, JsonMapper.builder().build().createObjectNode(), "OK", null, now, now, null, null,
                true);
    }

    private static CallCredential revealedAt(String baseUrl) {
        return CallCredential.of(new SystemCalls.Credential("jira-token-0001", false), null,
                new CallCredential.Endpoint(SystemKind.JIRA_DC, baseUrl));
    }

    @Test
    void theRowStillAtTheEndpointPasses() {
        SystemStore.SystemRow row = row("https://jira.test");
        assertThat(EndpointPin.moved(row, revealedAt("https://jira.test"))).isNull();
        assertThat(EndpointPin.moved(row, CallCredential.none())).isNull();
        assertThat(EndpointPin.moved(row, SystemKind.JIRA_DC, "https://jira.test")).isNull();
    }

    @Test
    void anotherBaseUrlOrKindIsAMove() {
        SystemStore.SystemRow row = row("https://jira.test");
        CallResult.Failure moved = EndpointPin.moved(row, revealedAt("https://jira.example"));
        assertThat(moved).isNotNull();
        assertThat(moved.kind()).isEqualTo(CallResult.Kind.INVALID);
        assertThat(moved.message()).isEqualTo("The endpoint of Jira changed meanwhile: nothing was sent to the new one");
        assertThat(EndpointPin.moved(row, SystemKind.JIRA_CLOUD, "https://jira.test")).isNotNull();
    }

    @Test
    void aPollOfAMovedSystemOpensNoSession() {
        when(systems.find(COMPANY, SYSTEM)).thenReturn(Optional.of(row("https://jira.example")));
        SystemPolls polls = new SystemPollsImpl(systems, clients, transactions);

        CallResult<SystemPolls.Session> opened = polls.open(COMPANY, SYSTEM, revealedAt("https://jira.test"));

        assertThat(opened.failure()).isNotNull();
        assertThat(opened.failure().kind()).isEqualTo(CallResult.Kind.INVALID);
        assertThat(opened.failureMessage()).contains("endpoint of Jira changed").doesNotContain("jira-token");
        verifyNoInteractions(clients);
    }

    @Test
    void aWriteToAMovedSystemSendsNothing() {
        when(systems.find(COMPANY, SYSTEM)).thenReturn(Optional.of(row("https://jira.example")));
        SystemWrites writes = new SystemWritesImpl(systems, clients, transactions, new OutboundProperties(false, false,
                Duration.ofSeconds(5), Duration.ofSeconds(5), Set.of(), Set.of()));

        CallResult<String> linked = writes.remoteLink(COMPANY, SYSTEM, revealedAt("https://jira.test"), "SHOP-1",
                "https://tinfirm.test/c/1", "Conversation");
        CallResult<List<SystemWrites.Attachment>> attachments = writes.attachments(COMPANY, SYSTEM,
                revealedAt("https://jira.test"), "SHOP-1");

        for (CallResult<?> result : new CallResult<?>[] { linked, attachments }) {
            assertThat(result.failure()).isNotNull();
            assertThat(result.failure().kind()).isEqualTo(CallResult.Kind.INVALID);
            assertThat(result.failureMessage()).contains("endpoint of Jira changed").doesNotContain("jira-token");
        }
        verifyNoInteractions(clients);
    }
}
