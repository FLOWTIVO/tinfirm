package com.flowtivo.tinfirm.run.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.runner.RunnerMessages;

/** ADR-0116: the message a revoked run's host gets, for its log and the agent. */
class GitRevocationMessageTest {

    @Test
    void namesTheRepositoriesAndTheCause() {
        assertThat(GitRevocationService.message(List.of("acme/shop"), "the shared credential CI bot was disabled."))
            .isEqualTo("Tinfirm revoked this run's access to acme/shop: the shared credential CI bot was disabled. "
                    + "Fetching from and pushing to it no longer works in this run.");
        assertThat(GitRevocationService.message(List.of("acme/shop", "acme/web", "acme/api"), "Shop was paused"))
            .startsWith("Tinfirm revoked this run's access to acme/shop, acme/web and acme/api: Shop was paused. ")
            .endsWith("to them no longer works in this run.");
    }

    @Test
    void staysWithinTheProtocolsLength() {
        String message = GitRevocationService.message(List.of("x".repeat(900), "y".repeat(900)), "why");
        assertThat(message).hasSize(RunnerMessages.MAX_GIT_REVOKE_MESSAGE).endsWith("…");
    }
}
