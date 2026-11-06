package com.flowtivo.tinfirm.conversation.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.run.RunInfo;
import com.flowtivo.tinfirm.run.RunStatus;
import com.flowtivo.tinfirm.run.TurnReason;
import com.flowtivo.tinfirm.runner.RuntimeKind;
import com.flowtivo.tinfirm.system.ChangeRequests;
import com.flowtivo.tinfirm.system.SystemKind;

/**
 * The conversation module's pure rules: a key's base and a title (the package 3 contract, §3.8), a subject's link, the
 * prompt's sections in their order (§5.9), the git user name per code host, and the repositories' directories as the
 * run module makes them.
 */
class ConversationRulesTest {

    @Test
    void aKeysBaseIsTheSubjectsKeyAsASlugAndATitleIsTheFirstLine() {
        assertThat(ConversationService.slug("SHOP-123")).isEqualTo("shop-123");
        assertThat(ConversationService.slug("owner/repo#57")).isEqualTo("owner-repo-57");
        assertThat(ConversationService.slug("Ärger über Ölpreise")).isEqualTo("arger-uber-olpreise");
        assertThat(ConversationService.slug("--")).isEmpty();
        assertThat(ConversationService.title("\n\n## Fix the checkout  \nThe cart loses items."))
            .isEqualTo("Fix the checkout");
        assertThat(ConversationService.title("x".repeat(300))).hasSize(200);
        assertThat(ConversationService.title("#")).isEqualTo("Conversation");
    }

    @Test
    void aSubjectsLinkIsHttpWithoutUserInfo() {
        assertThat(ConversationService.link("https://jira.example.test/browse/SHOP-123")).isTrue();
        assertThat(ConversationService.link("http://jira.example.test")).isTrue();
        assertThat(ConversationService.link("https://user:secret@jira.example.test/browse/SHOP-1")).isFalse();
        assertThat(ConversationService.link("javascript:alert(1)")).isFalse();
        assertThat(ConversationService.link("https://jira.example.test/a b")).isFalse();
    }

    @Test
    void thePromptHasItsSectionsInTheContractsOrder() {
        PromptRenderer.Context context = new PromptRenderer.Context("Builder", "You build the shop.",
                "Prefer small commits.", "Alice", "shop-123-builder-1", "Fix the checkout",
                "About Jira SHOP-123: Cart loses items; read it with `jira_get`.", List.of("Jira SHOP"),
                List.of(new PromptRenderer.Message("Alice", Instant.parse("2026-10-12T10:00:00Z"), "Fix it.")), null,
                List.of(new PromptRenderer.Message("Builder (you)", Instant.parse("2026-10-12T09:00:00Z"),
                        "Earlier.")), 1, "**Q:** Which DB?\n**A:** Postgres",
                List.of(new PromptRenderer.Account("Jira", "jira", "Alice's own account")), "agent/shop-123-builder-1",
                List.of(new PromptRenderer.Repository("shop", "acme/shop", "https://git.example.test/acme/shop.git",
                        "main", true, "the account of this conversation may only read it")),
                true, "Files of the conversation.", List.of(new PromptRenderer.Tool("jira_get", "Reads an issue.")),
                "A change request.");
        String prompt = PromptRenderer.prompt(context);
        List<String> headings = List.of("You build the shop.", "## Added by Alice", "---",
                "# Conversation shop-123-builder-1: Fix the checkout", "# Request", "# Conversation so far",
                "# Answers", "# Accounts", "# Repositories", "# Files", "# Tools", "# Outputs", "# How to work");
        List<Integer> positions = new ArrayList<>();
        headings.forEach(heading -> positions.add(prompt.indexOf(heading)));
        assertThat(positions).doesNotContain(-1).isSorted();
        assertThat(prompt).contains("> Fix it.", "read-only (the account of this conversation may only read it)",
                "`jira_get`: Reads an issue.", "Alice's own account", "Alice's usual projects: Jira SHOP.");
        // Without part 2's and part 3's sections they are left out.
        PromptRenderer.Context bare = new PromptRenderer.Context("Builder", "", null, "Alice", "builder-1", "Hi",
                null, List.of(), List.of(), "Go on.", List.of(), 0, null, List.of(), "agent/builder-1", List.of(),
                false, null, List.of(), null);
        assertThat(PromptRenderer.prompt(bare)).doesNotContain("# Answers", "# Files", "# Tools", "# Outputs",
                "Added by").startsWith("---\n# Conversation builder-1: Hi");
        assertThat(PromptRenderer.resumePrompt(context, List.of())).endsWith(PromptRenderer.RESUME_LINE);
    }

    @Test
    void thePromptSaysTinfirmOpensTheChangeRequestsByTheirMode() {
        List<PromptRenderer.Repository> repositories = List.of(
                new PromptRenderer.Repository("shop", "acme/shop", "https://github.example.test/acme/shop.git", "main",
                        false, null, new PromptRenderer.ChangeRequest("pull request",
                                PromptRenderer.ChangeRequestMode.OPENS)),
                new PromptRenderer.Repository("web", "acme/web", "https://gitlab.example.test/acme/web.git", "develop",
                        false, null, new PromptRenderer.ChangeRequest("merge request",
                                PromptRenderer.ChangeRequestMode.AFTER_APPROVAL)),
                new PromptRenderer.Repository("docs", "acme/docs", "https://github.example.test/acme/docs.git", "main",
                        false, null, new PromptRenderer.ChangeRequest("pull request",
                                PromptRenderer.ChangeRequestMode.NONE)),
                new PromptRenderer.Repository("tools", "acme/tools", "https://git.example.test/acme/tools.git", "main",
                        true, "the agent may only read it"));
        assertThat(PromptRenderer.repositorySection("agent/shop-1", repositories, false, "Alice")).endsWith(
                "\nTinfirm opens the change requests of `agent/shop-1` itself once the turn has pushed it: you have no "
                + "tool for them and need none."
                + "\n- acme/shop: Tinfirm opens a pull request into `main`, with your summary as its description, "
                + "unless this conversation has one open there, which the push updates."
                + "\n- acme/web: Tinfirm opens a merge request into `develop` once Alice approves it, with your summary "
                + "as its description, unless this conversation has one open there, which the push updates."
                + "\n- acme/docs: Tinfirm opens no pull request: the agent may not open one there, so the branch stays "
                + "pushed without one. If the work needs one, say so in your summary.");
        // Without a writable repository of a code host with change requests, nothing is said.
        assertThat(PromptRenderer.repositorySection("agent/shop-1", repositories.subList(3, 4), false, "Alice"))
            .doesNotContain("opens the change requests");
    }

    @Test
    void aCommitIsAHexNameAndAnAbbreviationNamesTheSameCommit() {
        assertThat(ChangeRequests.commit("21a02534de2d9805c3f2907966f5021453676003"))
            .isEqualTo("21a02534de2d9805c3f2907966f5021453676003");
        assertThat(ChangeRequests.commit("21a0253")).isEqualTo("21a0253");
        assertThat(ChangeRequests.commit("21A0253")).isNull();
        assertThat(ChangeRequests.commit("abc123")).isNull();
        assertThat(ChangeRequests.commit("21a0253; rm -rf")).isNull();
        assertThat(ChangeRequests.commit(null)).isNull();
        assertThat(ChangeRequestPolicy.sameCommit("21a02534de2d9805c3f2907966f5021453676003", "21a0253")).isTrue();
        assertThat(ChangeRequestPolicy.sameCommit("21A02534DE2D9805", "21a02534de2d9805c3f2")).isTrue();
        assertThat(ChangeRequestPolicy.sameCommit("21a02534de2d9805", "039828eb2ef6c859")).isFalse();
        // An abbreviation shorter than git's shortest names no commit for sure.
        assertThat(ChangeRequestPolicy.sameCommit("21a02534de2d9805", "21a025")).isFalse();
        assertThat(ChangeRequestPolicy.sameCommit("abc123", "abc123")).isTrue();
    }

    @Test
    void gitUserNamesAndDirectoriesFollowTheCodeHostAndTheRunModule() {
        assertThat(TurnSourceImpl.gitUsername(SystemKind.GITHUB)).isEqualTo("x-access-token");
        assertThat(TurnSourceImpl.gitUsername(SystemKind.GITLAB)).isEqualTo("oauth2");
        assertThat(TurnSourceImpl.gitUsername(SystemKind.GIT)).isEqualTo("git");
        List<String> taken = new ArrayList<>();
        taken.add(TurnSourceImpl.uniqueDir("Shop Server", taken));
        taken.add(TurnSourceImpl.uniqueDir("shop-server", taken));
        taken.add(TurnSourceImpl.uniqueDir("---", taken));
        assertThat(taken).containsExactly("shop-server", "shop-server-2", "repo");
    }

    @Test
    void aRetryOfATurnStoppedAtItsTimeLimitGoesOnFromWhereItStopped() {
        // Stopped at its time limit, then a retry whose sandbox did not start: the next retry still goes on.
        List<RunInfo> runs = List.of(run(1, TurnReason.FIRST, null), run(2, TurnReason.MESSAGE, "TIME_LIMIT"),
                run(3, TurnReason.RETRY, "INFRA"));
        assertThat(TurnSourceImpl.retriesTimeLimit(runs, 4)).isTrue();
        assertThat(TurnSourceImpl.retriesTimeLimit(runs, 3)).isTrue();
        assertThat(TurnSourceImpl.requestText(TurnReason.RETRY, "Alice", true)).startsWith(
                "Your previous turn reached its time limit and was stopped. Go on from where it stopped");
        // Another guard, or a person's message after the stop, retries from the start.
        assertThat(TurnSourceImpl.retriesTimeLimit(List.of(run(1, TurnReason.FIRST, "GUARD_STOPPED")), 2)).isFalse();
        assertThat(TurnSourceImpl.retriesTimeLimit(List.of(run(1, TurnReason.FIRST, "TIME_LIMIT"),
                run(2, TurnReason.MESSAGE, "INFRA")), 3)).isFalse();
        // So does a retry in which the agent ran (it has a session) and then failed for another reason.
        assertThat(TurnSourceImpl.retriesTimeLimit(List.of(run(1, TurnReason.FIRST, "TIME_LIMIT"),
                run(2, TurnReason.RETRY, "GUARD_STOPPED", "session-2")), 3)).isFalse();
        assertThat(TurnSourceImpl.requestText(TurnReason.RETRY, "Alice", false))
            .isEqualTo("Your previous turn did not finish. Do what it was asked again.");
    }

    private static RunInfo run(int turnNumber, TurnReason reason, @Nullable String failureClass) {
        return run(turnNumber, reason, failureClass, null);
    }

    private static RunInfo run(int turnNumber, TurnReason reason, @Nullable String failureClass,
            @Nullable String sessionId) {
        return new RunInfo(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "builder-1", UUID.randomUUID(),
                RuntimeKind.PI, reason, turnNumber, UUID.randomUUID(), UUID.randomUUID(), null,
                failureClass != null ? RunStatus.FAILED : RunStatus.SUCCEEDED, null, null, null, null, failureClass,
                null, null, null, sessionId, 1, Instant.now(), null, null);
    }
}
