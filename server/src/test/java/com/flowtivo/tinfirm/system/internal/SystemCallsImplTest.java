package com.flowtivo.tinfirm.system.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.system.CallResult;
import com.flowtivo.tinfirm.system.OAuthTokens;
import com.flowtivo.tinfirm.system.SystemCalls;
import com.flowtivo.tinfirm.system.SystemInfo;
import com.flowtivo.tinfirm.system.SystemKind;

import tools.jackson.databind.json.JsonMapper;

/**
 * A call goes where the caller's {@code SystemInfo} snapshot has the system (ADR-0079): an MCP server moved to another
 * endpoint between the snapshot and the call is as gone, and no client is asked, so that a credential prepared for the
 * endpoint before (a pasted token, a client's token, a sign-in's code, a grant to refresh or revoke) never reaches the
 * one after.
 */
class SystemCallsImplTest {

    private static final UUID COMPANY = UUID.randomUUID();

    private static final UUID SYSTEM = UUID.randomUUID();

    private final SystemStore store = mock(SystemStore.class);

    private final SystemClients clients = mock(SystemClients.class);

    private final SystemCallsImpl calls = new SystemCallsImpl(store, clients, mock(PlatformTransactionManager.class),
            new TimeSource(Clock.systemUTC()));

    private final SystemInfo atA = new SystemInfo(SYSTEM, COMPANY, "Wiki", "wiki", SystemKind.MCP,
            "https://a.test/mcp", null, null, true);

    private final SystemCalls.Credential token = new SystemCalls.Credential("pasted-token", false);

    private static SystemStore.SystemRow row(String baseUrl) {
        Instant now = Instant.now();
        return new SystemStore.SystemRow(SYSTEM, COMPANY, "Wiki", "wiki", SystemKind.MCP, baseUrl, null, "client-id",
                null, null, null, JsonMapper.builder().build().createObjectNode(), "NEW", null, now, now, null, null,
                true);
    }

    @Test
    void aServerMovedSinceTheSnapshotIsAsGoneAndNoClientIsAsked() {
        when(store.find(COMPANY, SYSTEM)).thenReturn(Optional.of(row("https://b.test/mcp")));

        CallResult<SystemCalls.Identity> who = calls.whoAmI(atA, token);
        CallResult<SystemCalls.Identity> client = calls.clientIdentity(atA, "minted-token");
        CallResult<OAuthTokens> exchanged = calls.exchange(atA, "code", "verifier");
        CallResult<OAuthTokens> refreshed = calls.refresh(atA, "refresh-token");
        CallResult<Boolean> revoked = calls.revoke(atA, new OAuthTokens("access", "refresh", "Bearer", null, null));

        for (CallResult<?> result : new CallResult<?>[] { who, client, exchanged, refreshed }) {
            assertThat(result.failure()).isNotNull();
            assertThat(result.failure().kind()).isEqualTo(CallResult.Kind.INVALID);
            assertThat(result.failureMessage()).contains("endpoint of Wiki changed")
                .doesNotContain("pasted-token", "minted-token", "refresh-token", "b.test");
        }
        assertThat(revoked.value()).isFalse();
        assertThat(calls.oauthAvailable(atA)).isFalse();
        verifyNoInteractions(clients);
    }

    @Test
    void aGoneSystemIsToldApartFromAMovedOne() {
        when(store.find(COMPANY, SYSTEM)).thenReturn(Optional.empty());
        assertThat(calls.whoAmI(atA, token).failureMessage()).isEqualTo("The system is gone");
        verifyNoInteractions(clients);
    }

    @Test
    void theSnapshotsEndpointIsCalled() {
        SystemStore.SystemRow row = row("https://a.test/mcp");
        when(store.find(COMPANY, SYSTEM)).thenReturn(Optional.of(row));
        when(clients.whoAmI(any(), any())).thenReturn(CallResult.ok(new SystemCalls.Identity("u1", "alice", null)));

        assertThat(calls.whoAmI(atA, token).value()).isEqualTo(new SystemCalls.Identity("u1", "alice", null));
        verify(clients).whoAmI(row, token);
    }
}
