package com.flowtivo.tinfirm.run.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.principal.PrincipalSwitches;
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
    void aSuspensionSaysWhenTheHolderWasDeactivated() {
        assertThat(GitRevocationService.message(List.of("acme/shop"),
                RunSwitches.BindingSuspended.why(PrincipalSwitches.Cause.USER_DEACTIVATED)))
            .isEqualTo("Tinfirm revoked this run's access to acme/shop: the account's holder was suspended because "
                    + "their Tinfirm account was deactivated. Fetching from and pushing to it no longer works in this "
                    + "run.");
        assertThat(RunSwitches.BindingSuspended.why(PrincipalSwitches.Cause.SCIM))
            .isEqualTo("the account's holder was suspended because the company's identity provider deactivated them");
        assertThat(RunSwitches.BindingSuspended.why(PrincipalSwitches.Cause.ADMIN))
            .isEqualTo("the account's holder was suspended");
        assertThat(RunSwitches.BindingSuspended.why(null)).isEqualTo("the account's holder was suspended");
    }

    @Test
    void staysWithinTheProtocolsLength() {
        String message = GitRevocationService.message(List.of("x".repeat(900), "y".repeat(900)), "why");
        assertThat(message).hasSize(RunnerMessages.MAX_GIT_REVOKE_MESSAGE).endsWith("…");
    }

    @Test
    void cutsOnACodePointWhenAnEmojiEndsAtTheCut() {
        String prefix = "Tinfirm revoked this run's access to ";
        // The emoji's two UTF-16 units are the 999th and 1000th characters: a cut at 999 would split them.
        String name = "x".repeat(RunnerMessages.MAX_GIT_REVOKE_MESSAGE - 2 - prefix.length()) + "\uD83D\uDE00"
                + "y".repeat(50);
        String message = GitRevocationService.message(List.of(name), "why");
        assertThat(message).endsWith("x…").hasSizeLessThanOrEqualTo(RunnerMessages.MAX_GIT_REVOKE_MESSAGE);
        assertThat(new String(message.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8)).isEqualTo(message);
        // The frame takes it, and so does the codec.
        assertThat(new RunnerMessages.RunGitRevoke(UUID.randomUUID(), 1, List.of(UUID.randomUUID()), "SUSPENDED",
                message).message()).isEqualTo(message);

        // One unit earlier, the emoji is whole before the cut and stays.
        String whole = GitRevocationService.message(List.of(name.substring(1)), "why");
        assertThat(whole).hasSize(RunnerMessages.MAX_GIT_REVOKE_MESSAGE).endsWith("\uD83D\uDE00…");
    }
}
