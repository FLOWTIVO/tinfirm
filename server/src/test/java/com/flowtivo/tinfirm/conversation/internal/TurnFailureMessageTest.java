package com.flowtivo.tinfirm.conversation.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.run.NewTurn;
import com.flowtivo.tinfirm.run.RunStatus;
import com.flowtivo.tinfirm.run.TurnEnded;
import com.flowtivo.tinfirm.run.TurnReason;

/**
 * Tinfirm's note of a failed turn (package 3's open point 8): stored text, read in any locale and zone, so a spent
 * budget's reset is written in one fixed human form in UTC, never as an instant, and left out when it is not known.
 * An automatic retry (ADR-0073) is said with its wait and its number, and retries spent so; which failures may pass.
 */
class TurnFailureMessageTest {

    private static TurnEnded.TurnEnd failed(String failureClass, @Nullable Instant retryAt) {
        return new TurnEnded.TurnEnd(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                TurnReason.FIRST, 1, UUID.randomUUID(), UUID.randomUUID(), null, RunStatus.FAILED, failureClass,
                "The model gateway refused the request: the budget is spent.", null, null, retryAt, List.of(),
                List.of(), null);
    }

    @Test
    void aSpentBudgetsResetIsWrittenInUtcInWords() {
        assertThat(TurnService.failureMessage(failed("USAGE_LIMIT", Instant.parse("2026-11-12T00:00:00Z")),
                "USAGE_LIMIT")).isEqualTo("The turn failed (USAGE_LIMIT). The model gateway refused the request: the "
                        + "budget is spent. The budget resets on 12 Nov at 00:00 UTC; retry the turn then.");
        assertThat(TurnService.failureMessage(failed("USAGE_LIMIT", Instant.parse("2026-03-05T17:42:31Z")),
                "USAGE_LIMIT")).endsWith("The budget resets on 5 Mar at 17:42 UTC; retry the turn then.")
            .doesNotContain("2026-");
    }

    @Test
    void aTurnStoppedAtItsTimeLimitIsNotToldAsAFailure() {
        String note = TurnService.failureMessage(failed("TIME_LIMIT", null), "TIME_LIMIT");
        assertThat(note).isEqualTo("The turn reached its time limit and stopped. Its work so far is kept: retry the "
                + "turn to go on from where it stopped, or say what to do next.")
            .doesNotContain("failed");
        assertThat(TurnService.failureMessage(failed("GUARD_STOPPED", null), "GUARD_STOPPED"))
            .startsWith("The turn failed (GUARD_STOPPED).");
    }

    private static TurnEnded.TurnEnd lost(String cause) {
        return new TurnEnded.TurnEnd(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                TurnReason.FIRST, 1, UUID.randomUUID(), UUID.randomUUID(), null, RunStatus.LOST, "LOST",
                "The runner host restarted.", null, null, null, List.of(), List.of(), cause);
    }

    @Test
    void anAutomaticRetryIsSaidWithItsWaitAndItsNumberAndSpentRetriesSo() {
        TurnEnded.TurnEnd end = failed("SERVER_LIMITED", null);
        assertThat(TurnService.failureMessage(end, "SERVER_LIMITED", new NewTurn.Scheduled(Instant.now(), 1, 3),
                false)).isEqualTo("The turn failed (SERVER_LIMITED). The model gateway refused the request: the budget "
                        + "is spent. Tinfirm retries it by itself in 1 minute (automatic retry 1 of 3); cancel the "
                        + "turn to stop it.");
        assertThat(TurnService.failureMessage(end, "SERVER_LIMITED", new NewTurn.Scheduled(Instant.now(), 3, 3),
                false)).endsWith(" in 4 minutes (automatic retry 3 of 3); cancel the turn to stop it.");
        assertThat(TurnService.failureMessage(end, "SERVER_LIMITED", null, true))
            .endsWith(" Tinfirm retried it by itself 3 times in a row; retry the turn, or say what to do next.");
        assertThat(TurnService.failureMessage(lost("HOST_RESTARTED"), "LOST", new NewTurn.Scheduled(Instant.now(), 2,
                3), false)).isEqualTo("The turn was lost: its runner host stopped reporting it. The runner host "
                        + "restarted. Tinfirm retries it by itself in 2 minutes (automatic retry 2 of 3); cancel the "
                        + "turn to stop it.");
        assertThat(TurnService.waitBefore(1)).isEqualTo(Duration.ofMinutes(1));
        assertThat(TurnService.waitBefore(2)).isEqualTo(Duration.ofMinutes(2));
        assertThat(TurnService.waitBefore(3)).isEqualTo(Duration.ofMinutes(4));
    }

    @Test
    void onlyAProviderOrGatewayThatMayPassOrARunLostForGoodIsRetriedByItself() {
        assertThat(TurnService.mayPass(failed("SERVER_LIMITED", null), "SERVER_LIMITED")).isTrue();
        assertThat(TurnService.mayPass(lost("HOST_RESTARTED"), "LOST")).isTrue();
        assertThat(TurnService.mayPass(lost("STALE_ON_RECONNECT"), "LOST")).isTrue();
        // Its host may still take a run whose lease expired back within the resume window.
        assertThat(TurnService.mayPass(lost("LEASE_EXPIRED"), "LOST")).isFalse();
        for (String other : List.of("USAGE_LIMIT", "CONTEXT_OVERFLOW", "LOGIN_EXPIRED", "VENDOR_BLOCKED",
                "CREDENTIAL_LEAK", "PROTOCOL_ERROR", "GUARD_STOPPED", "TIME_LIMIT", "GIT_ERROR", "INFRA",
                "MODEL_REFUSED", "MODEL_NOT_SERVED", "RUNTIME_REMOVED")) {
            assertThat(TurnService.mayPass(failed(other, null), other)).as(other).isFalse();
        }
        assertThat(TurnService.mayPass(failed("SERVER_LIMITED", null), null)).isFalse();
    }

    @Test
    void anUnknownResetIsLeftOut() {
        assertThat(TurnService.failureMessage(failed("USAGE_LIMIT", null), "USAGE_LIMIT"))
            .endsWith(" Retry the turn, or say what to do next.")
            .doesNotContain("resets");
    }
}
