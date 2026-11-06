package com.flowtivo.tinfirm.conversation.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.agent.AgentDefinition;
import com.flowtivo.tinfirm.agent.EffectiveSettings;
import com.flowtivo.tinfirm.agent.NetworkHosts;
import com.flowtivo.tinfirm.agent.RepositoryAccess;
import com.flowtivo.tinfirm.agent.Settings;
import com.flowtivo.tinfirm.conversation.ConversationInfo;
import com.flowtivo.tinfirm.run.ActiveRun;
import com.flowtivo.tinfirm.runner.RunnerMessages;
import com.flowtivo.tinfirm.system.RepositoryInfo;
import com.flowtivo.tinfirm.system.SystemInfo;
import com.flowtivo.tinfirm.system.Systems;

import tools.jackson.databind.JsonNode;

/**
 * A run's platform requests other than a tool call (the package 3 contract, §5.5, §5.8; {@code RunRequests.request}),
 * in the transaction that checked the run, as actor SYSTEM; each raised decision is audited by the agent. A request id
 * sent again gets the decision it raised; nothing is raised in an ended conversation ({@code CONVERSATION_ENDED}).
 * <ul>
 * <li>{@code ASK}: a question of 1 to 2000 characters with at most 10 options of 1 to 100 ({@code INVALID_REQUEST}),
 * at most 10 open ({@code TOO_MANY_QUESTIONS}).</li>
 * <li>{@code ACCESS}: an approval of a repository of a system the agent lists that the workspace lacks
 * ({@code UNKNOWN_REPOSITORY}, {@code ALREADY_IN_WORKSPACE}, {@code ALREADY_PENDING}); with {@code tool} instead,
 * {@code TOOL_DENIED}: tools belong to the agent's definition.</li>
 * <li>{@code NETWORK}: an approval of a network host, with {@code NetworkHosts}' grammar ({@code INVALID_REQUEST})
 * and checks ({@code ALREADY_ALLOWED}); an open request of the same host answers its decision.</li>
 * <li>{@code ARTIFACT}: part 3's ({@link ArtifactRequests}); {@code REQUEST_UNAVAILABLE} without it.</li>
 * </ul>
 * At most 10 approvals are open at once ({@code TOO_MANY_REQUESTS}).
 */
@Component
class PlatformRequests {

    static final String INVALID_REQUEST = "INVALID_REQUEST";

    static final String TOO_MANY_QUESTIONS = "TOO_MANY_QUESTIONS";

    static final String TOO_MANY_REQUESTS = "TOO_MANY_REQUESTS";

    static final String UNKNOWN_REPOSITORY = "UNKNOWN_REPOSITORY";

    static final String ALREADY_IN_WORKSPACE = "ALREADY_IN_WORKSPACE";

    static final String ALREADY_PENDING = "ALREADY_PENDING";

    static final String ALREADY_ALLOWED = "ALREADY_ALLOWED";

    static final String REQUEST_UNAVAILABLE = "REQUEST_UNAVAILABLE";

    private final ConversationCore core;

    private final ConversationTools tools;

    private final DecisionStore store;

    private final DecisionOps decisions;

    private final Settings settings;

    private final Systems systems;

    private final ObjectProvider<ArtifactRequests> artifacts;

    PlatformRequests(ConversationCore core, ConversationTools tools, DecisionStore store, DecisionOps decisions,
            Settings settings, Systems systems, ObjectProvider<ArtifactRequests> artifacts) {
        this.core = core;
        this.tools = tools;
        this.store = store;
        this.decisions = decisions;
        this.settings = settings;
        this.systems = systems;
        this.artifacts = artifacts;
    }

    RunnerMessages.RunRequestAck request(ActiveRun run, RunnerMessages.RequestKind kind, UUID requestId,
            JsonNode payload) {
        if (kind == RunnerMessages.RequestKind.ARTIFACT) {
            ArtifactRequests part3 = artifacts.getIfUnique();
            return part3 != null ? part3.request(run, requestId, payload) : rejected(run, requestId,
                    REQUEST_UNAVAILABLE, "Tinfirm does not save outputs on this server. Go on without it, and "
                            + "say in your summary what you wanted to save.");
        }
        if (kind == RunnerMessages.RequestKind.TOOL) {
            return rejected(run, requestId, INVALID_REQUEST, "A tool is called, not requested.");
        }
        // The conversation's row first, then read (the run module locked it already, at no cost now): an end that
        // committed meanwhile is seen, and nothing is raised in an ended conversation.
        store.lockConversation(run.conversationId());
        ConversationInfo conversation = core.conversations().find(run.companyId(), run.conversationId()).orElse(null);
        if (conversation == null) {
            return rejected(run, requestId, ToolCalls.RUN_NOT_ACTIVE, "The run is not active.");
        }
        ConversationTools.Texts texts = tools.texts(conversation);
        Optional<DecisionStore.DecisionRow> repeated = store.byRequest(conversation.id(), requestId);
        if (repeated.isPresent()) {
            return pending(run, requestId, repeated.get(), texts);
        }
        if (!conversation.open()) {
            return rejected(run, requestId, ToolCalls.CONVERSATION_ENDED, "The conversation ended, so nobody will "
                    + "answer. Put what you needed in your summary.");
        }
        return switch (kind) {
            case ASK -> ask(run, requestId, payload, conversation, texts);
            case ACCESS -> access(run, requestId, payload, conversation, texts);
            case NETWORK -> network(run, requestId, payload, conversation, texts);
            default -> rejected(run, requestId, INVALID_REQUEST, "Tinfirm does not take " + kind + " requests.");
        };
    }

    // ---- ASK ---------------------------------------------------------------------------------------------------------

    private RunnerMessages.RunRequestAck ask(ActiveRun run, UUID requestId, JsonNode payload,
            ConversationInfo conversation, ConversationTools.Texts texts) {
        String question = text(payload, "question");
        List<String> options = options(payload.get("options"));
        if (question == null || DecisionOps.characters(question) > DecisionOps.MAX_QUESTION_LENGTH
                || options == null) {
            return rejected(run, requestId, INVALID_REQUEST, "A question has 1 to " + DecisionOps.MAX_QUESTION_LENGTH
                    + " characters, and at most " + DecisionOps.MAX_OPTIONS + " options of 1 to "
                    + DecisionOps.MAX_OPTION_LENGTH + " characters each. Nothing was asked.");
        }
        if (store.countOpen(conversation.id(), DecisionStore.Kind.QUESTION) >= DecisionOps.MAX_OPEN) {
            return rejected(run, requestId, TOO_MANY_QUESTIONS, "This conversation has " + DecisionOps.MAX_OPEN
                    + " open questions already, the most it may have. End your turn now: Tinfirm wakes you with the "
                    + "answers.");
        }
        DecisionOps.Raised raised = decisions.question(conversation, run.runId(), requestId, question, options, true);
        return pending(run, requestId, raised.decision(), texts);
    }

    /** The options trimmed, duplicates ignoring case dropped; null when one is blank or too long, or too many. */
    static @Nullable List<String> options(@Nullable JsonNode node) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            return null;
        }
        List<String> kept = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        for (JsonNode option : node) {
            String text = option.isString() ? option.asString().strip() : "";
            if (text.isEmpty() || DecisionOps.characters(text) > DecisionOps.MAX_OPTION_LENGTH) {
                return null;
            }
            String folded = text.toLowerCase(Locale.ROOT);
            if (!seen.contains(folded)) {
                seen.add(folded);
                kept.add(text);
            }
        }
        return kept.size() <= DecisionOps.MAX_OPTIONS ? kept : null;
    }

    // ---- ACCESS ------------------------------------------------------------------------------------------------------

    private RunnerMessages.RunRequestAck access(ActiveRun run, UUID requestId, JsonNode payload,
            ConversationInfo conversation, ConversationTools.Texts texts) {
        String tool = text(payload, "tool");
        String repository = text(payload, "repository");
        if (tool != null && repository == null) {
            return rejected(run, requestId, ToolRules.TOOL_DENIED, "You may not request tools: they belong to "
                    + texts.agent() + "'s definition, and whoever edits the agent adds them. Nothing was requested; "
                    + "tell " + texts.person() + " in your summary when your work needs them.");
        }
        if (repository == null) {
            return rejected(run, requestId, INVALID_REQUEST, "Name the repository you need, as "
                    + "`list_repositories` shows it.");
        }
        EffectiveSettings effective = settings.effective(run.companyId(), conversation.agentId(),
                conversation.settingsOwner());
        Found found = find(run.companyId(), effective, repository);
        if (found == null) {
            return rejected(run, requestId, UNKNOWN_REPOSITORY, "There is no repository " + repository + " on the "
                    + "systems " + texts.agent() + " uses. `list_repositories` shows those you may request.");
        }
        UUID repositoryId = found.repository().id();
        if (conversation.workspace().stream().anyMatch(entry -> entry.repositoryId().equals(repositoryId))) {
            return rejected(run, requestId, ALREADY_IN_WORKSPACE, found.repository().name() + " is in your "
                    + "workspace already.");
        }
        if (store.openApproval(conversation.id(), DecisionSubject.Type.REPOSITORY, "repositoryId",
                repositoryId.toString()).isPresent()) {
            return rejected(run, requestId, ALREADY_PENDING, "Your request of " + found.repository().name()
                    + " waits for " + texts.person() + " already. End your turn now: Tinfirm wakes you with the "
                    + "decision.");
        }
        if (store.countOpen(conversation.id(), DecisionStore.Kind.APPROVAL) >= DecisionOps.MAX_OPEN) {
            return tooManyRequests(run, requestId);
        }
        String reason = text(payload, "reason");
        // Open point 10: the agent's access when it lists the repository, else READ unless the approver picks WRITE.
        RepositoryAccess access = effective.repositories()
            .stream()
            .filter(entry -> entry.repositoryId().equals(repositoryId))
            .map(AgentDefinition.RepositoryEntry::access)
            .findFirst()
            .orElse(RepositoryAccess.READ);
        DecisionOps.Raised raised = decisions.approval(conversation, run.runId(), requestId,
                DecisionSubject.repository(repositoryId, access), reason, true, texts.agent() + " asks for the "
                        + "repository " + found.repository().name() + " on " + found.system().name()
                        + (reason != null ? ": " + reason : ".") + "\n\nIt joins this conversation's workspace "
                        + "once you approve it.");
        return pending(run, requestId, raised.decision(), texts);
    }

    private record Found(RepositoryInfo repository, SystemInfo system) {
    }

    /**
     * The repository a request names, among those of the code hosts the agent lists (as the person's turns use it):
     * by id, by name, by URL, then by name ignoring case.
     */
    private @Nullable Found find(UUID companyId, EffectiveSettings effective, String named) {
        List<Found> candidates = new ArrayList<>();
        for (SystemInfo system : systems.findAll(companyId, effective.systems()).values()) {
            if (!system.kind().hasRepositories()) {
                continue;
            }
            systems.repositories(companyId, system.id())
                .forEach(repository -> candidates.add(new Found(repository, system)));
        }
        String wanted = named.strip();
        for (Found candidate : candidates) {
            if (candidate.repository().id().toString().equals(wanted) || candidate.repository().name().equals(wanted)
                    || candidate.repository().url().equals(wanted)) {
                return candidate;
            }
        }
        return candidates.stream()
            .filter(candidate -> candidate.repository().name().equalsIgnoreCase(wanted))
            .findFirst()
            .orElse(null);
    }

    // ---- NETWORK -----------------------------------------------------------------------------------------------------

    private RunnerMessages.RunRequestAck network(ActiveRun run, UUID requestId, JsonNode payload,
            ConversationInfo conversation, ConversationTools.Texts texts) {
        NetworkHosts.Parsed parsed = NetworkHosts.parse(text(payload, "host"));
        if (!parsed.valid()) {
            return rejected(run, requestId, INVALID_REQUEST, parsed.problem() + " Nothing was requested.");
        }
        String host = java.util.Objects.requireNonNull(parsed.host());
        EffectiveSettings effective = settings.effective(run.companyId(), conversation.agentId(),
                conversation.settingsOwner());
        boolean allowed = effective.hosts().stream().anyMatch(known -> NetworkHosts.covers(known, host))
                || conversation.grantedHosts().stream().anyMatch(known -> NetworkHosts.covers(known, host));
        if (allowed) {
            return rejected(run, requestId, ALREADY_ALLOWED, host + " is allowed for this conversation already: "
                    + "your next turn reaches it. When it cannot, say so in your summary.");
        }
        Optional<DecisionStore.DecisionRow> open = store.openApproval(conversation.id(), DecisionSubject.Type.HOST,
                "host", host);
        if (open.isPresent()) {
            return pending(run, requestId, open.get(), texts);
        }
        if (store.countOpen(conversation.id(), DecisionStore.Kind.APPROVAL) >= DecisionOps.MAX_OPEN) {
            return tooManyRequests(run, requestId);
        }
        String reason = text(payload, "reason");
        DecisionOps.Raised raised = decisions.approval(conversation, run.runId(), requestId,
                DecisionSubject.host(host), reason, true, texts.agent() + " asks to reach the network host `" + host
                        + "`" + (reason != null ? ": " + reason : ".") + "\n\nApprove it for this conversation, or "
                        + "for the agent from now on.");
        return pending(run, requestId, raised.decision(), texts);
    }

    // ---- answers -----------------------------------------------------------------------------------------------------

    /** {@code PENDING} with the decision, and what the agent does now (ADR-0036). */
    private static RunnerMessages.RunRequestAck pending(ActiveRun run, UUID requestId,
            DecisionStore.DecisionRow decision, ConversationTools.Texts texts) {
        String message;
        if (decision.kind() == DecisionStore.Kind.QUESTION) {
            message = "Your question is posted in the conversation, where " + texts.person() + " answers it. Ask "
                    + "every question you need, then end your turn without a summary: Tinfirm wakes you with all the "
                    + "answers at once.";
        }
        else {
            DecisionSubject subject = decision.requireSubject();
            String what = switch (subject.type()) {
                case HOST -> "Your request of the network host " + subject.host();
                case REPOSITORY -> "Your request of the repository";
                case TOOL_CALL -> "Your call of " + ToolRules.quoted(String.valueOf(subject.name()));
                case CHANGE_REQUEST -> "The change request";
                case RESULT -> "Your result";
            };
            message = what + " waits for " + texts.person() + "'s decision. When you have nothing else to do, end "
                    + "your turn without a summary: Tinfirm wakes you with the decision.";
        }
        return RunnerMessages.RunRequestAck.pending(run.runId(), requestId, decision.id(), message);
    }

    private static RunnerMessages.RunRequestAck tooManyRequests(ActiveRun run, UUID requestId) {
        return rejected(run, requestId, TOO_MANY_REQUESTS, "This conversation has " + DecisionOps.MAX_OPEN
                + " requests open already, the most it may have. End your turn now: Tinfirm wakes you with the "
                + "decisions.");
    }

    private static @Nullable String text(JsonNode payload, String field) {
        JsonNode node = payload.get(field);
        if (node == null || !node.isString() || node.asString().isBlank()) {
            return null;
        }
        return node.asString().strip();
    }

    private static RunnerMessages.RunRequestAck rejected(ActiveRun run, UUID requestId, String code,
            String message) {
        return RunnerMessages.RunRequestAck.rejected(run.runId(), requestId, code, message);
    }
}
