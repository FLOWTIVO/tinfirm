package com.flowtivo.tinfirm.trigger.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.flowtivo.tinfirm.conversation.TriggerConversations;
import com.flowtivo.tinfirm.run.RunInfo;
import com.flowtivo.tinfirm.system.FixedTools;
import com.flowtivo.tinfirm.system.SystemKind;
import com.flowtivo.tinfirm.trigger.OutboxCause;
import com.flowtivo.tinfirm.trigger.OutboxKind;
import com.flowtivo.tinfirm.trigger.TriggerAction;
import com.flowtivo.tinfirm.trigger.TriggerResults;

/**
 * What the write-back writes to a trigger's conversation's issue for each cause (the package 4 contract, §5.8, §5.9),
 * as outbox rows, and the same in words for the review of a result ({@code writes}, exactly as they would be written).
 * Pure: the caller reads the conversation, the trigger, the run and its outputs.
 * <ul>
 * <li>{@code RESULT}: a comment with the summary, the next steps and the turn's outputs (change requests with their
 * links, documents by name, links), and the conversation's link; on Jira, once per conversation, a remote link to the
 * conversation in Tinfirm.</li>
 * <li>{@code ON_SUCCESS}, {@code ON_FAILURE}: the trigger's actions that the system's kind has (Jira's
 * {@code TRANSITION} and {@code ATTACH_DOCUMENTS}, one attachment per document of the turn; GitHub's and GitLab's
 * {@code LABEL_ADD}, {@code LABEL_REMOVE} and {@code CLOSE}); another kind's action is left out.</li>
 * <li>{@code FAILURE}: "{agent} could not finish this: {cause}. The team of {trigger} was told."</li>
 * <li>{@code QUESTION}: the turn's open questions in one comment, ending "Reply in a comment on this issue to
 * answer."</li>
 * <li>{@code ANSWER}: "{member} answered in Tinfirm:", the questions quoted, the answer.</li>
 * <li>{@code CHANGE_REQUEST}: on Jira, a remote link to a change request the conversation opened.</li>
 * </ul>
 * Every comment is written with the label {@code {agent} (Tinfirm agent):} in front (§5.7), which the writer adds.
 */
final class OutboxPlans {

    /** A comment's body leaves room for the writer's marker within the fixed comment tool's limit. */
    static final int MAX_BODY = FixedTools.MAX_COMMENT_LENGTH - 200;

    /** The cause key of the conversation's remote link, written once per conversation. */
    static final String CONVERSATION_LINK = "RESULT:LINK";

    private OutboxPlans() {
    }

    /**
     * The place of a trigger's conversation and what writes to it.
     *
     * @param linked whether the conversation's remote link was queued before
     */
    record Place(UUID conversationId, String conversationKey, UUID systemId, SystemKind kind, UUID bindingId,
            String subjectKey, String triggerName, TriggerResults results, String agentName, String conversationUrl,
            boolean linked) {

        boolean jira() {
            return kind == SystemKind.JIRA_DC || kind == SystemKind.JIRA_CLOUD;
        }

        boolean codeHost() {
            return kind == SystemKind.GITHUB || kind == SystemKind.GITLAB;
        }

        /** The label in front of every comment. */
        String label() {
            return OutboxPlans.label(agentName);
        }
    }

    /** The rows of a cause, and the same as a review shows them: each with its kind, target and words. */
    record Plan(List<OutboxStore.NewRow> rows, List<TriggerConversations.ReviewWrite> items) {

        Plan {
            rows = List.copyOf(rows);
            items = List.copyOf(items);
        }

        /** The writes in words, in order. */
        List<String> writes() {
            return items.stream().map(TriggerConversations.ReviewWrite::text).toList();
        }

        boolean isEmpty() {
            return rows.isEmpty();
        }
    }

    /** {@code {agent} (Tinfirm agent):}. */
    static String label(String agentName) {
        return agentName + " (Tinfirm agent):";
    }

    // ---- causes ----------------------------------------------------------------------------------------------------

    /** A turn that succeeded with a completed result: {@code RESULT} and {@code ON_SUCCESS}. */
    static Plan success(Place place, RunInfo run, List<TriggerConversations.RunOutput> outputs) {
        List<OutboxStore.NewRow> rows = new ArrayList<>();
        List<TriggerConversations.ReviewWrite> writes = new ArrayList<>();
        boolean attaches = place.jira() && place.results().onSuccess().stream()
            .anyMatch(action -> action.kind() == TriggerAction.Kind.ATTACH_DOCUMENTS);
        if (place.results().comment()) {
            String body = resultBody(place, run, outputs, attaches);
            rows.add(comment(place, OutboxCause.RESULT, "RESULT:" + run.id(), run.id(), null, body));
            writes.add(write(OutboxKind.COMMENT, place, null, commentWords(place, body)));
        }
        actions(place, run.id(), place.results().onSuccess(), OutboxCause.ON_SUCCESS, outputs, rows, writes);
        if (place.results().comment() && place.jira() && !place.linked()) {
            rows.add(row(place, OutboxKind.REMOTE_LINK, OutboxCause.RESULT, CONVERSATION_LINK, run.id(), null, null,
                    null, null, null, linkTitle(place), place.conversationUrl()));
            writes.add(write(OutboxKind.REMOTE_LINK, place, place.conversationUrl(), "Link " + place.subjectKey()
                    + " to this conversation in Tinfirm (" + place.conversationUrl() + ")"));
        }
        return new Plan(rows, writes);
    }

    /** A turn that failed or was lost: {@code FAILURE} and {@code ON_FAILURE}. */
    static Plan failure(Place place, RunInfo run) {
        List<OutboxStore.NewRow> rows = new ArrayList<>();
        List<TriggerConversations.ReviewWrite> writes = new ArrayList<>();
        if (place.results().comment()) {
            String body = place.agentName() + " could not finish this: " + cause(run) + ". The team of "
                    + place.triggerName() + " was told.\n\n" + conversationLine(place);
            rows.add(comment(place, OutboxCause.FAILURE, "FAILURE:" + run.id(), run.id(), null, body));
            writes.add(write(OutboxKind.COMMENT, place, null, commentWords(place, body)));
        }
        actions(place, run.id(), place.results().onFailure(), OutboxCause.ON_FAILURE, List.of(), rows, writes);
        return new Plan(rows, writes);
    }

    /** The turn's open questions in one comment ({@code QUESTION}); empty without one. */
    static Optional<OutboxStore.NewRow> questions(Place place, UUID runId,
            List<TriggerConversations.Question> questions) {
        if (questions.isEmpty()) {
            return Optional.empty();
        }
        StringBuilder body = new StringBuilder(place.agentName())
            .append(questions.size() == 1 ? " has a question:\n\n" : " has questions:\n\n");
        int n = 1;
        for (TriggerConversations.Question question : questions) {
            body.append(n++).append(". ").append(oneLine(question.question()));
            if (!question.options().isEmpty()) {
                body.append(" (").append(String.join(" / ", question.options())).append(')');
            }
            body.append('\n');
        }
        body.append("\nReply in a comment on this issue to answer.");
        return Optional.of(comment(place, OutboxCause.QUESTION, "QUESTION:" + runId, runId, null,
                body.toString()));
    }

    /** An answer given in Tinfirm by the trigger's team or author ({@code ANSWER}, §5.8, open point 4). */
    static OutboxStore.NewRow answer(Place place, TriggerConversations.Answer answer, String memberName) {
        StringBuilder body = new StringBuilder("**").append(memberName).append(" answered in Tinfirm:**\n\n");
        for (String question : answer.questions()) {
            body.append("> ").append(oneLine(question)).append("\n\n");
        }
        body.append(answer.answer().strip());
        String key = "ANSWER:" + (answer.messageId() != null ? answer.messageId() : answer.decisionId());
        return row(place, OutboxKind.COMMENT, OutboxCause.ANSWER, key, null, answer.decisionId(), null, null,
                answer.messageId(), cutBody(body.toString()), null, null);
    }

    /** A change request the conversation opened, linked to its Jira issue ({@code CHANGE_REQUEST}). */
    static Optional<OutboxStore.NewRow> changeRequest(Place place, @Nullable UUID outputId, String url,
            String title) {
        if (!place.jira()) {
            return Optional.empty();
        }
        String key = "CHANGE_REQUEST:" + (outputId != null ? outputId.toString() : url);
        return Optional.of(row(place, OutboxKind.REMOTE_LINK, OutboxCause.CHANGE_REQUEST, cutKey(key), null, null,
                outputId, null, null, null, cut(title, 255), url));
    }

    // ---- parts -----------------------------------------------------------------------------------------------------

    /** The trigger's actions that the system's kind has, in their order. */
    private static void actions(Place place, UUID runId, List<TriggerAction> actions, OutboxCause cause,
            List<TriggerConversations.RunOutput> outputs, List<OutboxStore.NewRow> rows,
            List<TriggerConversations.ReviewWrite> writes) {
        String key = place.subjectKey();
        for (int i = 0; i < actions.size(); i++) {
            TriggerAction action = actions.get(i);
            String causeKey = cause + ":" + runId + ":" + i;
            String value = action.value() != null ? action.value().strip() : null;
            switch (action.kind()) {
                case TRANSITION -> {
                    if (place.jira() && value != null && !value.isEmpty()) {
                        rows.add(row(place, OutboxKind.TRANSITION, cause, causeKey, runId, null, null, null, null,
                                null, cut(value, 255), null));
                        writes.add(write(OutboxKind.TRANSITION, place, value, "Move " + key + " through “" + value
                                + "”"));
                    }
                }
                case ATTACH_DOCUMENTS -> {
                    if (place.jira()) {
                        for (TriggerConversations.RunOutput document : documents(outputs)) {
                            rows.add(row(place, OutboxKind.ATTACHMENT, cause, cutKey(cause + ":" + runId
                                    + ":ATTACHMENT:" + document.attachmentId()), runId, null, document.id(),
                                    document.attachmentId(), null, null, cut(document.title(), 255), null));
                            writes.add(write(OutboxKind.ATTACHMENT, place, document.title(), "Attach "
                                    + document.title() + " to " + key));
                        }
                    }
                }
                case LABEL_ADD -> {
                    if (place.codeHost() && value != null && !value.isEmpty()) {
                        rows.add(row(place, OutboxKind.LABEL_ADD, cause, causeKey, runId, null, null, null, null,
                                null, cut(value, 100), null));
                        writes.add(write(OutboxKind.LABEL_ADD, place, value, "Add the label “" + value + "” to "
                                + key));
                    }
                }
                case LABEL_REMOVE -> {
                    if (place.codeHost() && value != null && !value.isEmpty()) {
                        rows.add(row(place, OutboxKind.LABEL_REMOVE, cause, causeKey, runId, null, null, null, null,
                                null, cut(value, 100), null));
                        writes.add(write(OutboxKind.LABEL_REMOVE, place, value, "Remove the label “" + value
                                + "” from " + key));
                    }
                }
                case CLOSE -> {
                    if (place.codeHost()) {
                        rows.add(row(place, OutboxKind.CLOSE, cause, causeKey, runId, null, null, null, null, null,
                                null, null));
                        writes.add(write(OutboxKind.CLOSE, place, null, "Close " + key));
                    }
                }
            }
        }
    }

    /** A write as the review shows it: the row's kind, the place's subject, what it names, in words. */
    private static TriggerConversations.ReviewWrite write(OutboxKind kind, Place place, @Nullable String value,
            String text) {
        return new TriggerConversations.ReviewWrite(kind.name(), place.subjectKey(), value, text);
    }

    /** The result's comment: the summary, the next steps, the turn's outputs, the conversation's link. */
    static String resultBody(Place place, RunInfo run, List<TriggerConversations.RunOutput> outputs,
            boolean attaches) {
        StringBuilder body = new StringBuilder();
        String summary = run.result() != null && !run.result().summary().isBlank() ? run.result().summary()
                : run.summary();
        body.append(summary != null && !summary.isBlank() ? summary.strip()
                : place.agentName() + " left no summary of its work.");
        List<String> next = run.result() != null ? run.result().nextSteps() : List.of();
        if (!next.isEmpty()) {
            body.append("\n\n**Next steps**\n");
            next.forEach(step -> body.append("\n- ").append(oneLine(step)));
        }
        if (!outputs.isEmpty()) {
            body.append("\n\n**Outputs**\n");
            for (TriggerConversations.RunOutput output : outputs) {
                body.append("\n- ").append(switch (output.kind()) {
                    case "CHANGE_REQUEST" -> "Change request "
                            + (output.url() != null ? "[" + output.title() + "](" + output.url() + ")"
                                    : output.title());
                    case "DOCUMENT" -> "Document `" + output.title().replace("`", "'") + "`"
                            + (attaches ? ", attached to this issue" : ", in Tinfirm");
                    default -> output.url() != null ? "[" + output.title() + "](" + output.url() + ")"
                            : output.title();
                });
            }
        }
        body.append("\n\n").append(conversationLine(place));
        return cutBody(body.toString());
    }

    private static String conversationLine(Place place) {
        return "[Open the conversation in Tinfirm](" + place.conversationUrl() + ")";
    }

    private static String linkTitle(Place place) {
        return cut(place.triggerName() + ": " + place.conversationKey() + " in Tinfirm", 255);
    }

    /** Why the turn did not finish, in words: the runner's message, else its class. */
    static String cause(RunInfo run) {
        String message = run.failureMessage() != null ? oneLine(run.failureMessage()) : "";
        if (!message.isEmpty()) {
            return cut(message.replaceAll("[.]+$", ""), 300);
        }
        if (run.failureClass() == null || "LOST".equals(run.failureClass())) {
            return "its run was lost";
        }
        return run.failureClass().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    private static List<TriggerConversations.RunOutput> documents(List<TriggerConversations.RunOutput> outputs) {
        return outputs.stream()
            .filter(output -> "DOCUMENT".equals(output.kind()) && output.attachmentId() != null)
            .toList();
    }

    private static OutboxStore.NewRow comment(Place place, OutboxCause cause, String causeKey, @Nullable UUID runId,
            @Nullable UUID decisionId, String body) {
        return row(place, OutboxKind.COMMENT, cause, causeKey, runId, decisionId, null, null, null, cutBody(body),
                null, null);
    }

    private static OutboxStore.NewRow row(Place place, OutboxKind kind, OutboxCause cause, String causeKey,
            @Nullable UUID runId, @Nullable UUID decisionId, @Nullable UUID outputId, @Nullable UUID attachmentId,
            @Nullable UUID messageId, @Nullable String body, @Nullable String value, @Nullable String url) {
        return new OutboxStore.NewRow(place.systemId(), place.bindingId(), place.subjectKey(), kind, cause, causeKey,
                runId, decisionId, outputId, attachmentId, messageId, body, value, url);
    }

    /** A comment as the review shows it: where, then exactly as written, with its label. */
    private static String commentWords(Place place, String body) {
        return "A comment on " + place.subjectKey() + ":\n\n**" + place.label() + "** " + body;
    }

    private static String oneLine(String text) {
        return text.strip().replaceAll("\\s*\\R\\s*", " ");
    }

    private static String cutBody(String body) {
        return cut(body, MAX_BODY);
    }

    private static String cutKey(String key) {
        return cut(key, 200);
    }

    static String cut(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }
}
