package com.flowtivo.tinfirm.trigger.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.conversation.TriggerConversations;
import com.flowtivo.tinfirm.run.RunInfo;
import com.flowtivo.tinfirm.run.RunStatus;
import com.flowtivo.tinfirm.run.TurnReason;
import com.flowtivo.tinfirm.runner.RunnerMessages;
import com.flowtivo.tinfirm.runner.RuntimeKind;
import com.flowtivo.tinfirm.system.SystemKind;
import com.flowtivo.tinfirm.trigger.OutboxCause;
import com.flowtivo.tinfirm.trigger.OutboxKind;
import com.flowtivo.tinfirm.trigger.TriggerAction;
import com.flowtivo.tinfirm.trigger.TriggerResults;

/**
 * What the write-back writes for each cause (the package 4 contract, §5.8, §5.9), as a table: the result's comment
 * and the trigger's actions that the system's kind has, the conversation's link once on Jira, a failure, the questions,
 * an answer given in Tinfirm, a change request's link; the review's words; the retries' backoff; the text a tracker's
 * markup keeps.
 */
class OutboxPlansTest {

    static final UUID RUN = UUID.randomUUID();

    static final List<TriggerAction> EVERY_ACTION = List.of(new TriggerAction(TriggerAction.Kind.TRANSITION,
            "In Review"), new TriggerAction(TriggerAction.Kind.ATTACH_DOCUMENTS, null),
            new TriggerAction(TriggerAction.Kind.LABEL_ADD, "ready"),
            new TriggerAction(TriggerAction.Kind.LABEL_REMOVE, "triage"),
            new TriggerAction(TriggerAction.Kind.CLOSE, null));

    static OutboxPlans.Place place(SystemKind kind, TriggerResults results, boolean linked) {
        return new OutboxPlans.Place(UUID.randomUUID(), "shop-1-ba-1", UUID.randomUUID(), kind, UUID.randomUUID(),
                kind == SystemKind.JIRA_DC ? "SHOP-1" : "acme/shop#57", "Prepare", results, "BA",
                "https://tinfirm.example.test/c/1/conversations/2", linked);
    }

    static RunInfo run(RunStatus status, @Nullable String failureClass, @Nullable String failureMessage,
            RunInfo.@Nullable Result result) {
        return new RunInfo(RUN, UUID.randomUUID(), UUID.randomUUID(), "shop-1-ba-1", UUID.randomUUID(),
                RuntimeKind.CLAUDE_CODE, TurnReason.FIRST, 1, UUID.randomUUID(), UUID.randomUUID(), null, status, null,
                null, null, null, failureClass, failureMessage, result != null ? result.summary() : null, result,
                null, 1, Instant.now(), null, null);
    }

    static RunInfo.Result completed(String summary, List<String> nextSteps) {
        return new RunInfo.Result(RunnerMessages.ResultOutcome.COMPLETED, summary, nextSteps, List.of());
    }

    static final List<TriggerConversations.RunOutput> OUTPUTS = List.of(
            new TriggerConversations.RunOutput(UUID.randomUUID(), "DOCUMENT", "reports/q3.md", null,
                    UUID.randomUUID()),
            new TriggerConversations.RunOutput(UUID.randomUUID(), "CHANGE_REQUEST", "acme/shop#5 Fix EUR",
                    "https://github.example.test/acme/shop/pull/5", null));

    @Test
    void aJiraResultIsItsCommentItsJiraActionsAndTheConversationsLinkOnce() {
        OutboxPlans.Place place = place(SystemKind.JIRA_DC, new TriggerResults(true, false, EVERY_ACTION, List.of()),
                false);
        OutboxPlans.Plan plan = OutboxPlans.success(place, run(RunStatus.SUCCEEDED, null, null,
                completed("The issue is ready.", List.of("Ask QA."))), OUTPUTS);

        assertThat(plan.rows()).extracting(OutboxStore.NewRow::kind).containsExactly(OutboxKind.COMMENT,
                OutboxKind.TRANSITION, OutboxKind.ATTACHMENT, OutboxKind.REMOTE_LINK);
        assertThat(plan.rows()).extracting(OutboxStore.NewRow::cause).containsExactly(OutboxCause.RESULT,
                OutboxCause.ON_SUCCESS, OutboxCause.ON_SUCCESS, OutboxCause.RESULT);
        assertThat(plan.rows()).extracting(OutboxStore.NewRow::causeKey).containsExactly("RESULT:" + RUN,
                "ON_SUCCESS:" + RUN + ":0", "ON_SUCCESS:" + RUN + ":ATTACHMENT:" + OUTPUTS.getFirst().attachmentId(),
                OutboxPlans.CONVERSATION_LINK);
        String body = plan.rows().getFirst().body();
        assertThat(body).startsWith("The issue is ready.\n\n**Next steps**\n\n- Ask QA.")
            .contains("- Document `reports/q3.md`, attached to this issue",
                    "- Change request [acme/shop#5 Fix EUR](https://github.example.test/acme/shop/pull/5)",
                    "[Open the conversation in Tinfirm](https://tinfirm.example.test/c/1/conversations/2)");
        assertThat(plan.rows().get(2).attachmentId()).isEqualTo(OUTPUTS.getFirst().attachmentId());
        assertThat(plan.rows().get(3).url()).isEqualTo(place.conversationUrl());
        assertThat(plan.writes()).containsExactly("A comment on SHOP-1:\n\n**BA (Tinfirm agent):** " + body,
                "Move SHOP-1 through “In Review”", "Attach reports/q3.md to SHOP-1",
                "Link SHOP-1 to this conversation in Tinfirm (" + place.conversationUrl() + ")");
        // The same writes as the review shows them: each its row's kind, the issue, and what it names.
        assertThat(plan.items()).extracting(TriggerConversations.ReviewWrite::kind).containsExactly("COMMENT",
                "TRANSITION", "ATTACHMENT", "REMOTE_LINK");
        assertThat(plan.items()).extracting(TriggerConversations.ReviewWrite::kind)
            .containsExactlyElementsOf(plan.rows().stream().map(row -> row.kind().name()).toList());
        assertThat(plan.items()).extracting(TriggerConversations.ReviewWrite::target).containsOnly("SHOP-1");
        assertThat(plan.items()).extracting(TriggerConversations.ReviewWrite::value).containsExactly(null,
                "In Review", "reports/q3.md", place.conversationUrl());

        OutboxPlans.Plan again = OutboxPlans.success(place(SystemKind.JIRA_DC, new TriggerResults(true, false,
                List.of(), List.of()), true), run(RunStatus.SUCCEEDED, null, null, completed("Again.", List.of())),
                List.of());
        assertThat(again.rows()).extracting(OutboxStore.NewRow::kind).containsExactly(OutboxKind.COMMENT);
    }

    @Test
    void aCodeHostsResultHasItsLabelsAndCloseAndNoLink() {
        OutboxPlans.Plan plan = OutboxPlans.success(place(SystemKind.GITHUB, new TriggerResults(true, false,
                EVERY_ACTION, List.of()), false), run(RunStatus.SUCCEEDED, null, null, completed("Done.",
                        List.of())), OUTPUTS);

        assertThat(plan.rows()).extracting(OutboxStore.NewRow::kind).containsExactly(OutboxKind.COMMENT,
                OutboxKind.LABEL_ADD, OutboxKind.LABEL_REMOVE, OutboxKind.CLOSE);
        assertThat(plan.rows().get(1).value()).isEqualTo("ready");
        assertThat(plan.rows().getFirst().body()).contains("Document `reports/q3.md`, in Tinfirm");
        assertThat(plan.writes()).containsExactly("A comment on acme/shop#57:\n\n**BA (Tinfirm agent):** "
                + plan.rows().getFirst().body(), "Add the label “ready” to acme/shop#57",
                "Remove the label “triage” from acme/shop#57", "Close acme/shop#57");
        assertThat(plan.items()).extracting(TriggerConversations.ReviewWrite::kind).containsExactly("COMMENT",
                "LABEL_ADD", "LABEL_REMOVE", "CLOSE");
        assertThat(plan.items()).extracting(TriggerConversations.ReviewWrite::target).containsOnly("acme/shop#57");
        assertThat(plan.items()).extracting(TriggerConversations.ReviewWrite::value).containsExactly(null, "ready",
                "triage", null);
    }

    @Test
    void withoutTheCommentOnlyTheActionsAreWrittenAndNothingWithoutEither() {
        OutboxPlans.Place place = place(SystemKind.GITLAB, new TriggerResults(false, false,
                List.of(new TriggerAction(TriggerAction.Kind.CLOSE, null)), List.of()), false);
        assertThat(OutboxPlans.success(place, run(RunStatus.SUCCEEDED, null, null, completed("x", List.of())),
                List.of()).rows()).extracting(OutboxStore.NewRow::kind).containsExactly(OutboxKind.CLOSE);
        assertThat(OutboxPlans.success(place(SystemKind.GITLAB, new TriggerResults(false, false, List.of(),
                List.of()), false), run(RunStatus.SUCCEEDED, null, null, completed("x", List.of())), List.of())
            .isEmpty()).isTrue();
    }

    @Test
    void aFailureSaysWhyAndWhoWasToldWithItsActions() {
        OutboxPlans.Place place = place(SystemKind.JIRA_DC, new TriggerResults(true, true, List.of(),
                List.of(new TriggerAction(TriggerAction.Kind.TRANSITION, "Blocked"))), false);
        OutboxPlans.Plan plan = OutboxPlans.failure(place, run(RunStatus.FAILED, "INFRA", "The sandbox stopped.",
                null));
        assertThat(plan.rows()).extracting(OutboxStore.NewRow::cause).containsExactly(OutboxCause.FAILURE,
                OutboxCause.ON_FAILURE);
        assertThat(plan.rows().getFirst().body()).startsWith("BA could not finish this: The sandbox stopped. The "
                + "team of Prepare was told.");
        assertThat(OutboxPlans.cause(run(RunStatus.LOST, "LOST", null, null))).isEqualTo("its run was lost");
        assertThat(OutboxPlans.cause(run(RunStatus.FAILED, "USAGE_LIMIT", null, null))).isEqualTo("usage limit");
    }

    @Test
    void theQuestionsAreOneCommentAndAnAnswerNamesWhoGaveIt() {
        OutboxPlans.Place place = place(SystemKind.JIRA_DC, TriggerResults.DEFAULT, false);
        assertThat(OutboxPlans.questions(place, RUN, List.of())).isEmpty();
        OutboxStore.NewRow asked = OutboxPlans.questions(place, RUN, List.of(
                new TriggerConversations.Question(UUID.randomUUID(), "Which currency?", List.of("EUR", "USD")),
                new TriggerConversations.Question(UUID.randomUUID(), "Which\nrounding?", List.of()))).orElseThrow();
        assertThat(asked.cause()).isEqualTo(OutboxCause.QUESTION);
        assertThat(asked.body()).isEqualTo("""
                BA has questions:

                1. Which currency? (EUR / USD)
                2. Which rounding?

                Reply in a comment on this issue to answer.""");

        UUID message = UUID.randomUUID();
        OutboxStore.NewRow answer = OutboxPlans.answer(place, new TriggerConversations.Answer(UUID.randomUUID(),
                List.of("Which currency?", "Which rounding?"), " EUR, half up. ", UUID.randomUUID(), null, message),
                "Bob Brown");
        assertThat(answer.cause()).isEqualTo(OutboxCause.ANSWER);
        assertThat(answer.causeKey()).isEqualTo("ANSWER:" + message);
        assertThat(answer.messageId()).isEqualTo(message);
        assertThat(answer.body()).isEqualTo("""
                **Bob Brown answered in Tinfirm:**

                > Which currency?

                > Which rounding?

                EUR, half up.""");
    }

    @Test
    void aChangeRequestIsLinkedToAJiraIssueOnly() {
        UUID output = UUID.randomUUID();
        OutboxStore.NewRow link = OutboxPlans.changeRequest(place(SystemKind.JIRA_CLOUD, TriggerResults.DEFAULT,
                true), output, "https://github.example.test/acme/shop/pull/5", "Fix EUR").orElseThrow();
        assertThat(link.kind()).isEqualTo(OutboxKind.REMOTE_LINK);
        assertThat(link.cause()).isEqualTo(OutboxCause.CHANGE_REQUEST);
        assertThat(link.causeKey()).isEqualTo("CHANGE_REQUEST:" + output);
        assertThat(OutboxPlans.changeRequest(place(SystemKind.GITHUB, TriggerResults.DEFAULT, true), output,
                "https://github.example.test/acme/shop/pull/5", "Fix EUR")).isEmpty();
    }

    @Test
    void aLongResultIsCutWithinTheCommentToolsLimit() {
        OutboxStore.NewRow comment = OutboxPlans.success(place(SystemKind.GITHUB, TriggerResults.DEFAULT, false),
                run(RunStatus.SUCCEEDED, null, null, completed("x".repeat(40_000), List.of())), List.of()).rows()
            .getFirst();
        assertThat(comment.body()).hasSize(OutboxPlans.MAX_BODY).endsWith("…");
    }

    @Test
    void retriesBackOffFromAMinuteToAnHour() {
        OutboxProperties properties = new OutboxProperties(true, Duration.ofMinutes(5), 20, 10, 10,
                Duration.ofMinutes(1), Duration.ofHours(1), Duration.ofDays(7));
        assertThat(List.of(1, 2, 3, 4, 7, 10).stream().map(properties::retryDelay).toList()).containsExactly(
                Duration.ofMinutes(1), Duration.ofMinutes(2), Duration.ofMinutes(4), Duration.ofMinutes(8),
                Duration.ofHours(1), Duration.ofHours(1));
    }

    @Test
    void aCommentsFingerprintSurvivesATrackersMarkup() {
        assertThat(TrackerWriter.fingerprint("*BA (Tinfirm agent):* The *issue* is [ready|https://x.test/a]"))
            .contains(TrackerWriter.fingerprint("The **issue** is [ready](https://x.test/a)"));
    }
}
