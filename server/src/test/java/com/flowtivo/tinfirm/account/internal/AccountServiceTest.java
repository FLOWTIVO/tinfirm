package com.flowtivo.tinfirm.account.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;

import com.flowtivo.tinfirm.audit.AuditLog;
import com.flowtivo.tinfirm.principal.PrincipalInfo;
import com.flowtivo.tinfirm.principal.PrincipalKind;
import com.flowtivo.tinfirm.principal.PrincipalStatus;
import com.flowtivo.tinfirm.principal.Principals;
import com.flowtivo.tinfirm.shared.access.CompanyAccessGuard;
import com.flowtivo.tinfirm.shared.security.ActorAuthentication;
import com.flowtivo.tinfirm.shared.security.AuthenticatedUser;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.web.ApiException;
import com.flowtivo.tinfirm.shared.web.ProblemCode;
import com.flowtivo.tinfirm.system.CallResult;
import com.flowtivo.tinfirm.system.ChatSystems;
import com.flowtivo.tinfirm.system.OAuthTokens;
import com.flowtivo.tinfirm.system.SystemCalls;
import com.flowtivo.tinfirm.system.SystemInfo;
import com.flowtivo.tinfirm.system.SystemKind;
import com.flowtivo.tinfirm.system.Systems;

/**
 * A connect whose check ran at the old endpoint of an MCP server stores nothing (ADR-0079): the transaction that
 * stores the account locks the system's row shared and compares its base URL with the one the "who am I" went to,
 * {@code 409 SYSTEM_MOVED} and nothing inserted when it changed, {@code system_moved} for a sign-in's callback; the
 * problem never carries the token.
 */
class AccountServiceTest {

    private static final UUID COMPANY = UUID.randomUUID();

    private static final UUID SYSTEM = UUID.randomUUID();

    private static final UUID MEMBER = UUID.randomUUID();

    private static final UUID USER = UUID.randomUUID();

    private static final String TOKEN = "pasted-secret-token";

    private final AccountStore store = mock(AccountStore.class);

    private final Principals principals = mock(Principals.class);

    private final Systems systems = mock(Systems.class);

    private final SystemCalls calls = mock(SystemCalls.class);

    private final RefusalAudit refusals = mock(RefusalAudit.class);

    private final AccountService service = new AccountService(store, mock(SignInStore.class),
            mock(AccountSecrets.class), mock(Grants.class), principals, systems, calls, mock(CompanyAccessGuard.class),
            refusals, mock(AuditLog.class), mock(ApplicationEventPublisher.class), mock(AccountMapper.class),
            mock(AccountRevocations.class), mock(SignInVerifiers.class), mock(PlatformTransactionManager.class),
            new TimeSource(Clock.systemUTC()), mock(ChatSystems.class));

    private final SystemInfo atA = new SystemInfo(SYSTEM, COMPANY, "Wiki", "wiki", SystemKind.MCP,
            "https://a.test/mcp", null, null, true);

    private final SystemInfo atB = new SystemInfo(SYSTEM, COMPANY, "Wiki", "wiki", SystemKind.MCP,
            "https://b.test/mcp", null, null, true);

    private final PrincipalInfo member = new PrincipalInfo(MEMBER, COMPANY, PrincipalKind.MEMBER, USER, "Alice",
            PrincipalStatus.ACTIVE);

    private final AccountService.Holder holder = new AccountService.Holder(atA, member, null, null, USER);

    private final SystemCalls.Identity alice = new SystemCalls.Identity("u1", "alice", null);

    @BeforeEach
    void theCheckRanAtAWhileTheServerMovedToB() {
        when(principals.find(COMPANY, MEMBER)).thenReturn(Optional.of(member));
        when(principals.ofMember(COMPANY, USER)).thenReturn(Optional.of(member));
        when(systems.find(COMPANY, SYSTEM)).thenReturn(Optional.of(atA));
        when(systems.lockShared(COMPANY, SYSTEM)).thenReturn(Optional.of(atB));
        when(calls.whoAmI(eq(atA), any())).thenReturn(CallResult.ok(alice));
    }

    @Test
    void aTokenCheckedAtTheOldEndpointIsNotStored() {
        assertThatThrownBy(() -> service.storeToken(() -> holder, TOKEN))
            .isInstanceOfSatisfying(ApiException.class, problem -> {
                assertThat(problem.code()).isEqualTo(ProblemCode.SYSTEM_MOVED);
                assertThat(problem.toString() + problem.getMessage()).contains("Wiki").doesNotContain(TOKEN);
            });
        verify(store, never()).insert(any(), any());
        verify(store, never()).reconnect(any(), any());
    }

    @Test
    void aTokenTheSystemModuleDidNotSendIsNoRefusalOfTheSystems() {
        when(calls.whoAmI(eq(atA), any())).thenReturn(CallResult.failed(CallResult.Kind.INVALID,
                "The endpoint of Wiki changed meanwhile: nothing was sent to the new one"));
        assertThatThrownBy(() -> service.storeToken(() -> holder, TOKEN))
            .isInstanceOfSatisfying(ApiException.class,
                    problem -> assertThat(problem.code()).isEqualTo(ProblemCode.SYSTEM_MOVED));
        verify(refusals, never()).refused(any(), any(), any(), any(), anyString());
        verify(store, never()).insert(any(), any());
    }

    @Test
    void aSignInCheckedAtTheOldEndpointComesBackAsSystemMoved() {
        Instant now = Instant.now();
        SignInStore.Request request = new SignInStore.Request("state-hash", COMPANY, SYSTEM, MEMBER, null, null, USER,
                "session-hash", "/accounts", now, now.plus(Duration.ofMinutes(10)));
        when(calls.exchange(eq(atA), eq("code"), anyString())).thenReturn(CallResult.ok(new OAuthTokens(
                "access-token", "refresh-token", "Bearer", null, null)));

        String outcome = as(() -> service.completeSignIn(request, "verifier", "code"));

        assertThat(outcome).isEqualTo("system_moved");
        verify(store, never()).insert(any(), any());
        verify(store, never()).reconnect(any(), any());
    }

    @Test
    void aServerStillAtTheEndpointCheckedStoresTheAccount() {
        when(systems.lockShared(COMPANY, SYSTEM)).thenReturn(Optional.of(atA));
        as(() -> service.storeToken(() -> holder, TOKEN));
        verify(store).insert(any(), eq(USER));
    }

    /** The work as the member's request would run it: their authentication on the thread. */
    private static <T> T as(Supplier<T> work) {
        SecurityContext previous = SecurityContextHolder.getContext();
        SecurityContext acting = SecurityContextHolder.createEmptyContext();
        acting.setAuthentication(new ActorAuthentication(new AuthenticatedUser(USER, "alice@example.test", "Alice",
                false)));
        SecurityContextHolder.setContext(acting);
        try {
            return work.get();
        }
        finally {
            SecurityContextHolder.setContext(previous);
        }
    }
}
