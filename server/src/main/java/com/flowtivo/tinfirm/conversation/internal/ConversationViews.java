package com.flowtivo.tinfirm.conversation.internal;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.account.AccountInfo;
import com.flowtivo.tinfirm.account.Accounts;
import com.flowtivo.tinfirm.agent.AgentInfo;
import com.flowtivo.tinfirm.agent.Agents;
import com.flowtivo.tinfirm.agent.EffectiveSettings;
import com.flowtivo.tinfirm.agent.Settings;
import com.flowtivo.tinfirm.company.GroupMembership;
import com.flowtivo.tinfirm.conversation.BindingInfo;
import com.flowtivo.tinfirm.conversation.ConversationCosts;
import com.flowtivo.tinfirm.conversation.ConversationInfo;
import com.flowtivo.tinfirm.conversation.TriggerAudience;
import com.flowtivo.tinfirm.model.ModelAccessChoice;
import com.flowtivo.tinfirm.principal.PrincipalInfo;
import com.flowtivo.tinfirm.principal.PrincipalKind;
import com.flowtivo.tinfirm.principal.Principals;
import com.flowtivo.tinfirm.run.RunInfo;
import com.flowtivo.tinfirm.run.RunReads;
import com.flowtivo.tinfirm.shared.api.model.AgentRepositoryAccess;
import com.flowtivo.tinfirm.shared.api.model.AgentRuntime;
import com.flowtivo.tinfirm.shared.api.model.Conversation;
import com.flowtivo.tinfirm.shared.api.model.ConversationBinding;
import com.flowtivo.tinfirm.shared.api.model.ConversationChat;
import com.flowtivo.tinfirm.shared.api.model.ConversationCost;
import com.flowtivo.tinfirm.shared.api.model.ConversationEndedReason;
import com.flowtivo.tinfirm.shared.api.model.ConversationMessage;
import com.flowtivo.tinfirm.shared.api.model.ConversationPayer;
import com.flowtivo.tinfirm.shared.api.model.ConversationRepository;
import com.flowtivo.tinfirm.shared.api.model.ConversationState;
import com.flowtivo.tinfirm.shared.api.model.ConversationSubject;
import com.flowtivo.tinfirm.shared.api.model.ConversationSummary;
import com.flowtivo.tinfirm.shared.api.model.ConversationTokens;
import com.flowtivo.tinfirm.shared.api.model.ConversationTrigger;
import com.flowtivo.tinfirm.shared.api.model.ConversationTurnStatus;
import com.flowtivo.tinfirm.shared.api.model.ConversationWaitingOn;
import com.flowtivo.tinfirm.shared.api.model.RunModelAccess;
import com.flowtivo.tinfirm.shared.api.model.RunResult;
import com.flowtivo.tinfirm.shared.api.model.Subject;
import com.flowtivo.tinfirm.shared.security.AuthenticatedUser;
import com.flowtivo.tinfirm.shared.security.CurrentActor;
import com.flowtivo.tinfirm.system.ChatSystemInfo;
import com.flowtivo.tinfirm.system.ChatSystems;
import com.flowtivo.tinfirm.system.SystemInfo;
import com.flowtivo.tinfirm.system.SystemKind;
import com.flowtivo.tinfirm.system.Systems;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The API's views of conversations, built in the caller's transaction from Tinfirm's rows only. */
@Component
class ConversationViews {

    private final ConversationStore store;

    private final BindingStore bindings;

    private final BindingTexts texts;

    private final WorkspaceService workspace;

    private final Agents agents;

    private final Settings settings;

    private final Principals principals;

    private final Systems systems;

    private final RunReads runs;

    private final ConversationOutputs outputs;

    private final DecisionPresenter decisions;

    private final TriggerPorts triggers;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ChatSystems chatSystems;

    private final DecisionReads decisionReads;

    private final GroupMembership groups;

    private final ObjectProvider<ConversationCosts> costs;

    private final Accounts accounts;

    ConversationViews(ConversationStore store, BindingStore bindings, BindingTexts texts, WorkspaceService workspace,
            Agents agents, Settings settings, Principals principals, Systems systems, RunReads runs,
            ConversationOutputs outputs, DecisionPresenter decisions, TriggerPorts triggers, ChatSystems chatSystems,
            DecisionReads decisionReads, GroupMembership groups, ObjectProvider<ConversationCosts> costs,
            Accounts accounts) {
        this.costs = costs;
        this.accounts = accounts;
        this.chatSystems = chatSystems;
        this.decisionReads = decisionReads;
        this.groups = groups;
        this.triggers = triggers;
        this.decisions = decisions;
        this.store = store;
        this.bindings = bindings;
        this.texts = texts;
        this.workspace = workspace;
        this.agents = agents;
        this.settings = settings;
        this.principals = principals;
        this.systems = systems;
        this.runs = runs;
        this.outputs = outputs;
    }

    /** The list's rows, with their agents' and persons' names. */
    List<ConversationSummary> summaries(UUID companyId, List<ConversationStore.ConversationRow> rows) {
        Map<UUID, String> agentNames = new HashMap<>();
        Set<UUID> people = new HashSet<>();
        Set<UUID> subjectSystems = new HashSet<>();
        for (ConversationStore.ConversationRow row : rows) {
            agentNames.computeIfAbsent(row.info().agentId(),
                    id -> agents.find(companyId, id).map(AgentInfo::name).orElse("A deleted agent"));
            people.add(row.info().actingPrincipalId());
            if (row.info().subject() != null && row.info().subject().systemId() != null) {
                subjectSystems.add(row.info().subject().systemId());
            }
            UUID placeSystem = webhookSystem(row);
            if (placeSystem != null) {
                subjectSystems.add(placeSystem);
            }
        }
        Map<UUID, String> names = principals.displayNames(companyId, people);
        Map<UUID, SystemInfo> infos = systems.findAll(companyId, subjectSystems);
        ConversationCosts cost = costs.getIfAvailable();
        Map<UUID, ConversationCosts.Total> totals = cost == null || rows.isEmpty() ? null
                : cost.totals(companyId, rows.stream().map(ConversationStore.ConversationRow::id).toList());
        List<ConversationSummary> summaries = new ArrayList<>();
        for (ConversationStore.ConversationRow row : rows) {
            ConversationInfo info = row.info();
            ConversationCosts.Total spent = totals != null ? total(totals, info.id()) : null;
            summaries.add(new ConversationSummary().id(info.id())
                .key(info.key())
                .title(info.title())
                .agentId(info.agentId())
                .agentName(agentNames.get(info.agentId()))
                .personPrincipalId(info.personPrincipalId())
                .personName(info.personPrincipalId() != null ? names.get(info.personPrincipalId()) : null)
                .trigger(trigger(row, names))
                .subject(subject(row, infos))
                .state(ConversationState.fromValue(info.state().name()))
                .endedReason(info.endedReason() != null ? ConversationEndedReason.fromValue(info.endedReason().name())
                        : null)
                .endedAt(ts(info.endedAt()))
                .turnStatus(info.turnStatus() != null ? ConversationTurnStatus.fromValue(info.turnStatus().name())
                        : null)
                .waitingOn(ConversationWaitingOn.fromValue(info.waitingOn().name()))
                .waitingReason(info.waitingReason())
                .waitingSince(ts(info.waitingSince()))
                .failureClass(row.failureClass())
                .turnCount(info.turnCount())
                .lastTurnAt(ts(info.lastTurnAt()))
                .costUsd(spent != null ? spent.costUsd() : null)
                .tokens(spent != null ? spent.tokens() : null)
                .contentPurgedAt(ts(row.contentPurgedAt()))
                .erasedAt(ts(row.erasedAt()))
                .createdAt(ts(info.createdAt()))
                .updatedAt(ts(info.updatedAt())));
        }
        return summaries;
    }

    /** A conversation in full. */
    Conversation conversation(ConversationStore.ConversationRow row) {
        ConversationInfo info = row.info();
        UUID companyId = info.companyId();
        AgentInfo agent = agents.find(companyId, info.agentId()).orElse(null);
        EffectiveSettings effective = null;
        if (agent != null && agent.live()) {
            try {
                effective = settings.effective(companyId, info.agentId(),
                        info.settingsOwner());
            }
            catch (IllegalArgumentException ex) {
                effective = null;
            }
        }
        List<ConversationStore.MessageRow> messages = store.messages(companyId, info.id());
        Set<UUID> people = new HashSet<>();
        people.add(info.actingPrincipalId());
        people.add(info.payerPrincipalId());
        messages.stream().map(ConversationStore.MessageRow::authorPrincipalId).filter(id -> id != null)
            .forEach(people::add);
        Map<UUID, String> names = principals.displayNames(companyId, people);
        Set<UUID> subjectSystems = new HashSet<>();
        if (info.subject() != null && info.subject().systemId() != null) {
            subjectSystems.add(info.subject().systemId());
        }
        UUID placeSystem = webhookSystem(row);
        if (placeSystem != null) {
            subjectSystems.add(placeSystem);
        }
        Conversation view = new Conversation().id(info.id())
            .key(info.key())
            .title(info.title())
            .agentId(info.agentId())
            .agentName(agent != null ? agent.name() : "A deleted agent")
            .personPrincipalId(info.personPrincipalId())
            .personName(info.personPrincipalId() != null ? names.get(info.personPrincipalId()) : null)
            .trigger(trigger(row, names))
            .place(Conversation.PlaceEnum.fromValue(info.place().name()))
            .origin(Conversation.OriginEnum.fromValue(info.origin().name()))
            .chat(info.place() == ConversationInfo.Place.CHAT_THREAD ? chat(companyId, row) : null)
            .subject(subject(row, systems.findAll(companyId, subjectSystems)))
            .runtime(AgentRuntime.fromValue(info.runtime().name()))
            .payer(payer(row, names))
            .cost(cost(companyId, info.id()))
            .branch("agent/" + info.key())
            .state(ConversationState.fromValue(info.state().name()))
            .endedReason(info.endedReason() != null ? ConversationEndedReason.fromValue(info.endedReason().name())
                    : null)
            .endedAt(ts(info.endedAt()))
            .turnStatus(info.turnStatus() != null ? ConversationTurnStatus.fromValue(info.turnStatus().name()) : null)
            .waitingOn(ConversationWaitingOn.fromValue(info.waitingOn().name()))
            .waitingReason(info.waitingReason())
            .waitingSince(ts(info.waitingSince()))
            .turnCount(info.turnCount())
            .activeRunId(info.activeRunId())
            .lastTurnAt(ts(info.lastTurnAt()))
            .result(result(row))
            .failureClass(row.failureClass())
            .failureCause(row.failureCause())
            .seenByOwner(true)
            .contentPurgedAt(ts(row.contentPurgedAt()))
            .erasedAt(ts(row.erasedAt()))
            .workspace(workspace(info, effective))
            .grantedHosts(new ArrayList<>(info.grantedHosts()))
            .hosts(hosts(info, effective))
            .bindings(new ArrayList<>(bindings(companyId, info.id())))
            .messages(new ArrayList<>(messages.stream().map(message -> message(message, names)).toList()))
            .turns(new ArrayList<>(runs.views(companyId, info.id())))
            .decisions(new ArrayList<>(decisions.ofConversation(companyId, info.id())))
            .outputs(new ArrayList<>(outputs.outputViews(companyId, info.id())))
            .attachments(new ArrayList<>(outputs.attachments(companyId, info.id())))
            .createdAt(ts(info.createdAt()))
            .updatedAt(ts(info.updatedAt()));
        waitingGroup(view, info);
        return mayPost(view, info);
    }

    /** A conversation's settled cost and tokens from the port's answer: nothing when it has none the caller sees. */
    private static ConversationCosts.Total total(Map<UUID, ConversationCosts.Total> totals, UUID conversationId) {
        ConversationCosts.Total total = totals.get(conversationId);
        return total != null ? total
                : new ConversationCosts.Total(BigDecimal.ZERO, new ConversationTokens(0L, 0L, 0L, 0L));
    }

    /** What its settled turns cost, as the caller sees spend; null while no module serves it. */
    private @Nullable ConversationCost cost(UUID companyId, UUID conversationId) {
        ConversationCosts cost = costs.getIfAvailable();
        return cost != null ? cost.of(companyId, conversationId) : null;
    }

    /**
     * The group whose members the conversation waits on, when a group holds the decision it waits on (a trigger's
     * team's approval or review): "Waits for Analysts · Review".
     */
    private void waitingGroup(Conversation view, ConversationInfo info) {
        if (!info.open() || info.waitingOn() != ConversationInfo.WaitingOn.PERSON) {
            return;
        }
        decisionReads.open(info.companyId(), info.id()).stream().findFirst()
            .map(DecisionReads.Open::approverGroupId)
            .ifPresent(group -> view.waitingGroupId(group)
                .waitingGroupName(groups.groupNames(info.companyId(), List.of(group)).get(group)));
    }

    /**
     * What the caller may write in it, by the rules {@code POST …/messages} checks: its people (its person; a trigger's
     * author or a member of its team, whatever their role) while it is open; a plain message only where its place is
     * Tinfirm (not a tracker's issue, not a chat's thread), an answer to the agent's open questions anywhere.
     */
    private Conversation mayPost(Conversation view, ConversationInfo info) {
        UUID userId = CurrentActor.user()
            .map(AuthenticatedUser::id)
            .orElse(null);
        boolean person = false;
        if (userId != null && info.open()) {
            UUID principalId = principals.ofMember(info.companyId(), userId)
                .filter(PrincipalInfo::active)
                .map(PrincipalInfo::id)
                .orElse(null);
            ConversationsImpl.Caller caller = new ConversationsImpl.Caller(userId, principalId, false,
                    TriggerAudience.Seen.NONE,
                    info.triggered() ? triggers.actsOn(info.companyId(), userId)
                            : TriggerAudience.Seen.NONE);
            person = principalId != null && caller.isPerson(info);
        }
        boolean external = info.place() == ConversationInfo.Place.TRACKER_ISSUE
                || info.place() == ConversationInfo.Place.CHAT_THREAD;
        boolean questions = person && decisionReads.open(info.companyId(), info.id()).stream()
            .anyMatch(open -> "QUESTION".equals(open.kind()));
        return view.mayPost(person && !external).mayAnswer(person && (external ? questions : true));
    }

    /** A webhook conversation's system, from its place: it has no subject of its own. */
    private static @Nullable UUID webhookSystem(ConversationStore.ConversationRow row) {
        if (row.info().place() != ConversationInfo.Place.WEBHOOK || row.placeRef() == null) {
            return null;
        }
        String systemId = text(JSON.readTree(row.placeRef()), "systemId");
        return systemId != null ? UUID.fromString(systemId) : null;
    }

    /**
     * What the conversation is about: its subject, or a webhook conversation's from its place, the mapped subject's
     * key, and its link and title where the mapping gave them (the package 5 contract, §5.12).
     */
    private static @Nullable ConversationSubject subject(ConversationStore.ConversationRow row,
            Map<UUID, SystemInfo> systems) {
        ConversationSubject subject = subject(row.info().subject(), systems);
        if (subject != null || row.info().place() != ConversationInfo.Place.WEBHOOK || row.placeRef() == null) {
            return subject;
        }
        JsonNode ref = JSON.readTree(row.placeRef());
        String key = text(ref, "subjectKey");
        if (key == null) {
            return null;
        }
        UUID systemId = webhookSystem(row);
        SystemInfo system = systemId != null ? systems.get(systemId) : null;
        return new ConversationSubject().systemId(systemId)
            .systemName(system != null ? system.name() : null)
            .key(key)
            .url(text(ref, "subjectUrl"))
            .title(text(ref, "subjectTitle"));
    }

    private static @Nullable ConversationSubject subject(ConversationInfo.@Nullable Subject subject,
            Map<UUID, SystemInfo> systems) {
        if (subject == null) {
            return null;
        }
        SystemInfo system = subject.systemId() != null ? systems.get(subject.systemId()) : null;
        return new ConversationSubject().systemId(subject.systemId())
            .systemName(system != null ? system.name() : null)
            .key(subject.key())
            .url(subject.url())
            .title(subject.title());
    }

    private static ConversationPayer payer(ConversationStore.ConversationRow row, Map<UUID, String> names) {
        ConversationInfo info = row.info();
        boolean budget = info.modelAccess() instanceof ModelAccessChoice.Budget;
        return new ConversationPayer().principalId(info.payerPrincipalId())
            .name(names.get(info.payerPrincipalId()))
            .modelAccess(budget ? RunModelAccess.BUDGET : RunModelAccess.SUBSCRIPTION)
            .subscriptionRunnerHostId(row.subscriptionHostId())
            .subscriptionLabel(info.subscriptionLabel());
    }

    private static @Nullable RunResult result(ConversationStore.ConversationRow row) {
        if (row.resultOutcome() == null) {
            return null;
        }
        return new RunResult().outcome(RunResult.OutcomeEnum.fromValue(row.resultOutcome()))
            .summary(row.resultSummary() != null ? row.resultSummary() : "")
            .nextSteps(new ArrayList<>(row.resultNextSteps()))
            .openQuestions(new ArrayList<>(row.resultOpenQuestions()));
    }

    private List<ConversationRepository> workspace(ConversationInfo info, @Nullable EffectiveSettings effective) {
        List<ConversationRepository> views = new ArrayList<>();
        if (effective == null) {
            for (ConversationInfo.WorkspaceEntry entry : info.workspace()) {
                views.add(new ConversationRepository().repositoryId(entry.repositoryId())
                    .name(entry.repositoryId().toString())
                    .source(ConversationRepository.SourceEnum.fromValue(entry.source().name()))
                    .readOnly(entry.readOnly()));
            }
            return views;
        }
        for (ConversationWorkspace.Repository repository : workspace.of(info, effective)) {
            views.add(new ConversationRepository().repositoryId(repository.repositoryId())
                .systemId(repository.systemId())
                .name(repository.name())
                .url(repository.url())
                .source(ConversationRepository.SourceEnum.fromValue(repository.source().name()))
                .readOnly(repository.readOnly())
                .readOnlyCause(repository.readOnlyCause() != null
                        ? ConversationRepository.ReadOnlyCauseEnum.fromValue(repository.readOnlyCause().name())
                        : null));
        }
        return views;
    }

    private static List<String> hosts(ConversationInfo info, @Nullable EffectiveSettings effective) {
        Set<String> hosts = new TreeSet<>(info.grantedHosts());
        if (effective != null) {
            hosts.addAll(effective.hosts());
        }
        return new ArrayList<>(hosts);
    }

    /** The conversation's bindings, as its page shows them. */
    List<ConversationBinding> bindings(UUID companyId, UUID conversationId) {
        List<BindingStore.BindingRow> rows = bindings.ofConversation(companyId, conversationId);
        Map<UUID, SystemInfo> infos = systems.findAll(companyId,
                rows.stream().map(row -> row.info().systemId()).toList());
        Map<UUID, Integer> turns = new HashMap<>();
        return rows.stream()
            .map(row -> binding(row.info(), infos.get(row.info().systemId()), row.runId(), turns))
            .toList();
    }

    ConversationBinding binding(BindingInfo binding) {
        UUID runId = bindings.find(binding.companyId(), binding.id()).map(BindingStore.BindingRow::runId)
            .orElse(null);
        return binding(binding, systems.find(binding.companyId(), binding.systemId()).orElse(null), runId,
                new HashMap<>());
    }

    /**
     * The binding as the API shows it.
     *
     * @param runId the run that first asked for it, with its turn's number (each read once into {@code turns})
     */
    private ConversationBinding binding(BindingInfo binding, @Nullable SystemInfo system, @Nullable UUID runId,
            Map<UUID, Integer> turns) {
        ConversationBinding view = new ConversationBinding().id(binding.id())
            .systemId(binding.systemId())
            .systemName(system != null ? system.name() : null)
            .systemKind(system != null ? com.flowtivo.tinfirm.shared.api.model.SystemKind.fromValue(
                    system.kind().name()) : null)
            .state(ConversationBinding.StateEnum.fromValue(binding.state().name()))
            .how(binding.how() != null ? ConversationBinding.HowEnum.fromValue(binding.how().name()) : null)
            .reason(binding.reason() != null ? ConversationBinding.ReasonEnum.fromValue(binding.reason().name())
                    : null)
            .holderPrincipalId(binding.holderPrincipalId())
            .grantId(binding.grantId())
            .text(texts.text(binding))
            .waitingSince(ts(binding.waitingSince()))
            .boundAt(ts(binding.boundAt()))
            .runId(runId);
        if (runId != null) {
            Integer number = turns.computeIfAbsent(runId, id -> runs.find(binding.companyId(), id)
                .map(RunInfo::turnNumber)
                .orElse(null));
            view.turnNumber(number);
        }
        if (binding.holderPrincipalId() != null) {
            // Whose account it is, for the reader's own words (the person's own one too), and of what kind: a
            // member's name is their user's, a service account's its own.
            UUID holderId = binding.holderPrincipalId();
            principals.find(binding.companyId(), holderId).ifPresent(holder -> view
                .holderName(principals.displayNames(binding.companyId(), List.of(holderId)).get(holderId))
                .holderKind(holder.kind() == PrincipalKind.SERVICE ? ConversationBinding.HolderKindEnum.SERVICE
                        : ConversationBinding.HolderKindEnum.MEMBER));
        }
        texts.grant(binding.companyId(), binding.grantId()).ifPresent(grant -> view.groupId(grant.groupId())
            .groupName(grant.groupName())
            .access(AgentRepositoryAccess.fromValue(grant.access().name())));
        if (binding.accountId() != null && binding.how() != BindingInfo.How.OWN) {
            // The system's credential it acts with (ADR-0072): a grant's, or a trigger's service account's.
            accounts.find(binding.companyId(), binding.accountId())
                .filter(AccountInfo::credential)
                .ifPresent(credential -> view.credentialName(credential.name())
                    .credentialKind(ConversationBinding.CredentialKindEnum.fromValue(credential.kind().name()))
                    .credentialEnabled(credential.enabled()));
        }
        if (binding.how() == BindingInfo.How.OWN || binding.open()) {
            view.access(AgentRepositoryAccess.WRITE);
        }
        else if (binding.how() == BindingInfo.How.FIXED && binding.accountId() != null
                && binding.holderPrincipalId() != null) {
            // A trigger's row: its author's own account, or a service account's credential as it is lent now.
            view.access(AgentRepositoryAccess.fromValue(accounts.fixedAccess(binding.companyId(), binding.systemId(),
                    binding.holderPrincipalId(), binding.accountId()).name()));
        }
        return view.choices(texts.choices(binding.companyId(), binding.choices()));
    }

    ConversationMessage message(ConversationStore.MessageRow message, Map<UUID, String> names) {
        return new ConversationMessage().id(message.id())
            .kind(ConversationMessage.KindEnum.fromValue(message.kind()))
            .authorPrincipalId(message.authorPrincipalId())
            .authorName(message.authorPrincipalId() != null ? names.get(message.authorPrincipalId())
                    : message.externalName())
            .externalId(message.externalId())
            .body(message.body())
            .purgedAt(ts(message.purgedAt()))
            .answersOpenQuestions(message.answersOpenQuestions())
            .runId(message.runId())
            .decisionId(message.decisionId())
            .waiting(message.waiting())
            .unanswered(message.unanswered())
            .steering(message.steering() != null ? ConversationMessage.SteeringEnum.fromValue(message.steering()) : null)
            .createdAt(ts(message.createdAt()));
    }

    /**
     * A chat thread's place (the package 5 contract, §3.9), from its {@code place_ref}, with a link that opens it in
     * the chat: Slack's {@code app_redirect} to the channel in the workspace, Teams' chat with the bot. Null when the
     * place names no system.
     */
    @Nullable ConversationChat chat(UUID companyId, ConversationStore.ConversationRow row) {
        if (row.placeRef() == null) {
            return null;
        }
        JsonNode ref = JSON.readTree(row.placeRef());
        String systemId = text(ref, "systemId");
        if (systemId == null) {
            return null;
        }
        UUID id = UUID.fromString(systemId);
        ChatSystemInfo system = chatSystems.find(companyId, id).orElse(null);
        String channel = text(ref, "channelId");
        String thread = text(ref, "threadId");
        String permalink = null;
        if (system != null && system.slack()) {
            String team = text(ref, "teamId") != null ? text(ref, "teamId") : system.teamId();
            if (team != null && channel != null) {
                permalink = "https://slack.com/app_redirect?team=" + encode(team) + "&channel=" + encode(channel);
            }
        }
        else if (system != null && system.teams() && system.appId() != null) {
            permalink = "https://teams.microsoft.com/l/chat/0/0?users=" + encode("28:" + system.appId());
        }
        return new ConversationChat(id)
            .systemName(system != null ? system.name() : null)
            .systemKind(system != null ? com.flowtivo.tinfirm.shared.api.model.SystemKind.fromValue(
                    system.kind().name()) : null)
            .channelId(channel)
            .threadId(thread)
            .permalink(permalink);
    }

    private static @Nullable String text(JsonNode ref, String name) {
        return ref.hasNonNull(name) ? ref.get(name).stringValue() : null;
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** A trigger's conversation's trigger and author (the package 4 contract, §3.4); null for a personal one. */
    @Nullable ConversationTrigger trigger(ConversationStore.ConversationRow row, Map<UUID, String> names) {
        ConversationInfo info = row.info();
        if (!info.triggered() || info.authorPrincipalId() == null) {
            return null;
        }
        ConversationTrigger view = new ConversationTrigger(info.triggerId(),
                triggers.name(info.companyId(), info.triggerId()), info.authorPrincipalId(), info.chainDepth())
            .authorName(names.get(info.authorPrincipalId()));
        if (row.placeRef() != null) {
            JsonNode ref = JSON.readTree(row.placeRef());
            if (ref.hasNonNull("kind")) {
                view.subjectKind(ConversationTrigger.SubjectKindEnum.fromValue(ref.get("kind").stringValue()));
            }
            if (ref.hasNonNull("dueAt")) {
                view.dueAt(OffsetDateTime.parse(ref.get("dueAt").stringValue()));
            }
        }
        return view;
    }

    /** The subjects of the conversations a caller sees. */
    List<Subject> subjects(UUID companyId, List<ConversationStore.SubjectRow> rows) {
        Collection<UUID> ids = rows.stream().map(ConversationStore.SubjectRow::systemId).filter(id -> id != null)
            .toList();
        Map<UUID, SystemInfo> infos = systems.findAll(companyId, ids);
        return rows.stream().map(row -> {
            SystemInfo system = row.systemId() != null ? infos.get(row.systemId()) : null;
            return new Subject().systemId(row.systemId())
                .systemName(system != null ? system.name() : null)
                .key(row.key())
                .title(row.title())
                .url(row.url())
                .conversations(row.conversations())
                .open(row.open())
                .lastActivityAt(ts(row.lastActivityAt()));
        }).toList();
    }

    /** Whether the system's kind has issues or change requests a conversation may be about. */
    static boolean subjectKind(SystemKind kind) {
        return kind == SystemKind.JIRA_DC || kind == SystemKind.JIRA_CLOUD || kind == SystemKind.GITHUB
                || kind == SystemKind.GITLAB;
    }

    static @Nullable OffsetDateTime ts(@Nullable Instant instant) {
        return instant != null ? instant.atOffset(ZoneOffset.UTC) : null;
    }
}
