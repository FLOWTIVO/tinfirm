package com.flowtivo.tinfirm.conversation.internal;

import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.agent.AgentInfo;
import com.flowtivo.tinfirm.agent.Agents;
import com.flowtivo.tinfirm.company.CompanyDirectory;
import com.flowtivo.tinfirm.company.GroupMembership;
import com.flowtivo.tinfirm.conversation.ConversationInfo;
import com.flowtivo.tinfirm.conversation.TriggerAudience;
import com.flowtivo.tinfirm.principal.PrincipalInfo;
import com.flowtivo.tinfirm.principal.Principals;
import com.flowtivo.tinfirm.shared.access.CompanyAccess;
import com.flowtivo.tinfirm.shared.access.CompanyRole;
import com.flowtivo.tinfirm.shared.api.model.AgentRepositoryAccess;
import com.flowtivo.tinfirm.shared.api.model.Decision;
import com.flowtivo.tinfirm.shared.api.model.DecisionKind;
import com.flowtivo.tinfirm.shared.api.model.DecisionOutcome;
import com.flowtivo.tinfirm.shared.api.model.DecisionScope;
import com.flowtivo.tinfirm.shared.api.model.DecisionStatus;
import com.flowtivo.tinfirm.shared.api.model.DecisionSubjectType;
import com.flowtivo.tinfirm.shared.api.model.DecisionWriteKind;
import com.flowtivo.tinfirm.system.RepositoryInfo;
import com.flowtivo.tinfirm.system.SystemInfo;
import com.flowtivo.tinfirm.system.Systems;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Builds the Decisions API's {@code Decision}: the row, with the names of its conversation, agent, people, system and
 * repository resolved at read time, and whether the caller decides it.
 */
@Component
class DecisionPresenter {

    private static final TypeReference<Map<String, Object>> ARGUMENTS = new TypeReference<>() {
    };

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ConversationCore core;

    private final Agents agents;

    private final Principals principals;

    private final Systems systems;

    private final DecisionStore store;

    private final GroupMembership groups;

    private final TriggerPorts triggers;

    private final BindingStore bindings;

    private final BindingTexts texts;

    private final CompanyDirectory roles;

    DecisionPresenter(ConversationCore core, Agents agents, Principals principals, Systems systems,
            DecisionStore store, GroupMembership groups, TriggerPorts triggers, BindingStore bindings,
            BindingTexts texts, CompanyDirectory roles) {
        this.roles = roles;
        this.bindings = bindings;
        this.texts = texts;
        this.store = store;
        this.core = core;
        this.agents = agents;
        this.principals = principals;
        this.systems = systems;
        this.groups = groups;
        this.triggers = triggers;
    }

    /**
     * {@code Decisions.mayDecide(caller, decision)} (the package 3 contract, §5.8; the package 4 contract, §5.8, T12):
     * a personal conversation's decision by its person, the approver; a trigger's approval by its approver, its
     * author or a member of its team ({@code approver_group_id}); a trigger's question by its author and team in
     * Tinfirm. OWNER and ADMIN only as one of them; no agent decides.
     *
     * @param userId      the caller; null for a caller who decides nothing
     * @param principalId the caller's principal; null for a caller without one
     * @param groupIds    the caller's groups
     * @param acting      the triggers in whose conversations the caller acts (their author or team)
     * @param admin       OWNER or ADMIN, who decide what only Viewers could have (ADR-0077, decision 5)
     */
    record Decider(@Nullable UUID userId, @Nullable UUID principalId, Set<UUID> groupIds,
            TriggerAudience.Seen acting, boolean admin) {

        static final Decider NONE = new Decider(null, null, Set.of(), TriggerAudience.Seen.NONE, false);

        Decider {
            groupIds = Set.copyOf(groupIds);
        }

        boolean decides(DecisionStore.DecisionRow row, @Nullable ConversationInfo conversation) {
            return row.open() && approves(row, conversation);
        }

        /** Whether the caller is the decision's approver, open or not. */
        boolean approves(DecisionStore.DecisionRow row, @Nullable ConversationInfo conversation) {
            if (principalId == null) {
                return false;
            }
            if (principalId.equals(row.approverPrincipalId())) {
                return true;
            }
            if (row.approverAdmins()) {
                return admin;
            }
            if (row.approverGroupId() != null) {
                return groupIds.contains(row.approverGroupId());
            }
            return row.kind() == DecisionStore.Kind.QUESTION && row.approverPrincipalId() == null
                    && conversation != null && conversation.triggered() && acting.sees(conversation.triggerId());
        }
    }

    /** The caller as a decider in the company. */
    Decider decider(CompanyAccess access, UUID userId) {
        return decider(access.companyId(), userId);
    }

    /** {@link #decider(CompanyAccess, UUID)}: who decides depends on the member, never on their role. */
    Decider decider(UUID companyId, UUID userId) {
        // A suspended member decides nothing (the package 6 contract, G6): not as the conversation's person, nor as a
        // trigger's approver, author or team. Nor does a Viewer (ADR-0077, decision 5).
        CompanyRole role = roles.roleOf(companyId, userId).orElse(null);
        if (role == CompanyRole.VIEWER) {
            return Decider.NONE;
        }
        UUID principalId = principals.ofMember(companyId, userId)
            .filter(PrincipalInfo::active)
            .map(PrincipalInfo::id)
            .orElse(null);
        if (principalId == null) {
            return Decider.NONE;
        }
        return new Decider(userId, principalId, groups.groupsOf(companyId, userId),
                triggers.actsOn(companyId, userId), role == CompanyRole.OWNER || role == CompanyRole.ADMIN);
    }

    /**
     * The decisions of one company, in the given order.
     *
     * @param decider the caller: whether they decide each
     */
    List<Decision> decisions(UUID companyId, List<DecisionStore.DecisionRow> rows, Decider decider) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Set<UUID> people = new HashSet<>();
        Set<UUID> teams = new HashSet<>();
        Map<UUID, ConversationInfo> conversations = new HashMap<>();
        for (DecisionStore.DecisionRow row : rows) {
            if (row.approverPrincipalId() != null) {
                people.add(row.approverPrincipalId());
            }
            if (row.approverGroupId() != null) {
                teams.add(row.approverGroupId());
            }
            if (row.decidedByPrincipalId() != null) {
                people.add(row.decidedByPrincipalId());
            }
            conversations.computeIfAbsent(row.conversationId(),
                    id -> core.conversations().find(companyId, id).orElse(null));
        }
        Map<UUID, String> names = principals.displayNames(companyId, people);
        Map<UUID, String> teamNames = teams.isEmpty() ? Map.of() : groups.groupNames(companyId, teams);
        Map<UUID, String> agentNames = new HashMap<>();
        Map<UUID, Boolean> editable = new HashMap<>();
        Map<UUID, SystemInfo> systemsById = new HashMap<>();
        Map<UUID, RepositoryInfo> repositories = new HashMap<>();
        return rows.stream().map(row -> {
            ConversationInfo conversation = conversations.get(row.conversationId());
            String agentName = conversation == null ? null : agentNames.computeIfAbsent(conversation.agentId(),
                    id -> agents.find(companyId, id).map(AgentInfo::name).orElse(null));
            Decision dto = decision(row, conversation, agentName, names, decider, systemsById, repositories);
            if (row.approverGroupId() != null) {
                dto.approverGroupId(row.approverGroupId()).approverGroupName(teamNames.get(row.approverGroupId()));
            }
            // A request approved for the agent for good edits it: whether the caller may, so that the app offers it.
            DecisionSubject subject = row.subject();
            if (conversation != null && decider.userId() != null && subject != null
                    && (subject.type() == DecisionSubject.Type.REPOSITORY
                            || subject.type() == DecisionSubject.Type.HOST)) {
                UUID userId = decider.userId();
                dto.agentEditableByMe(editable.computeIfAbsent(conversation.agentId(),
                        id -> agents.access(companyId, id, userId).edits()));
            }
            return dto;
        }).toList();
    }

    /**
     * The conversation's decisions, oldest first, for its page ({@code GET /conversations/{id}}), whether the current
     * user decides each. The caller checked that they see the conversation.
     */
    List<Decision> ofConversation(UUID companyId, UUID conversationId) {
        Decider decider = com.flowtivo.tinfirm.shared.security.CurrentActor.user()
            .map(user -> decider(companyId, user.id()))
            .orElse(Decider.NONE);
        return decisions(companyId, store.ofConversation(conversationId), decider);
    }

    Decision decision(DecisionStore.DecisionRow row, Decider decider) {
        return decisions(row.companyId(), List.of(row), decider).getFirst();
    }

    private Decision decision(DecisionStore.DecisionRow row, @Nullable ConversationInfo conversation,
            @Nullable String agentName, Map<UUID, String> names, Decider decider,
            Map<UUID, SystemInfo> systemsById, Map<UUID, RepositoryInfo> repositories) {
        Decision dto = new Decision(row.id(), row.conversationId(), DecisionKind.fromValue(row.kind().name()),
                row.options(), DecisionStatus.fromValue(row.status().name()),
                row.createdAt().atOffset(ZoneOffset.UTC), decider.decides(row, conversation))
            .approverPrincipalId(row.approverPrincipalId());
        if (conversation != null) {
            dto.conversationKey(conversation.key()).conversationTitle(conversation.title())
                .agentId(conversation.agentId())
                .agentName(agentName);
        }
        dto.runId(row.runId())
            .question(row.question())
            .reason(row.reason())
            .approverName(row.approverPrincipalId() != null ? names.get(row.approverPrincipalId()) : null)
            .outcome(row.outcome() != null ? DecisionOutcome.fromValue(row.outcome().name()) : null)
            .answer(row.answer())
            .option(row.option())
            .note(row.note())
            .scope(row.scope() != null ? DecisionScope.fromValue(row.scope().name()) : null)
            .access(row.access() != null ? AgentRepositoryAccess.fromValue(row.access().name()) : null)
            .result(row.result())
            .decidedByPrincipalId(row.decidedByPrincipalId())
            .decidedByName(row.decidedByPrincipalId() != null ? names.get(row.decidedByPrincipalId())
                    : row.decidedByName())
            .decidedAt(row.decidedAt() != null ? row.decidedAt().atOffset(ZoneOffset.UTC) : null)
            .cancelCause(row.cancelCause())
            .purgedAt(row.purgedAt() != null ? row.purgedAt().atOffset(ZoneOffset.UTC) : null);
        DecisionSubject subject = row.subject();
        if (subject != null) {
            var out = new com.flowtivo.tinfirm.shared.api.model.DecisionSubject(
                    DecisionSubjectType.fromValue(subject.type().name()));
            if (subject.type() == DecisionSubject.Type.TOOL_CALL && subject.systemId() != null) {
                // The account the call runs as, as the conversation's binding on the system says it.
                bindings.of(row.companyId(), row.conversationId(), subject.systemId())
                    .ifPresent(binding -> out.accountText(texts.text(binding.info())));
            }
            out.systemId(subject.systemId()).name(subject.name()).tool(subject.tool()).what(subject.what())
                .repositoryId(subject.repositoryId())
                .access(subject.access() != null ? AgentRepositoryAccess.fromValue(subject.access().name()) : null)
                .host(subject.host())
                // A result's review (the package 4 contract, §5.8): its turn, its summary and exactly what approving
                // it writes to the place, for the card to show.
                .runId(subject.runId())
                .summary(subject.summary())
                .writes(subject.writeTexts() != null ? new java.util.ArrayList<>(subject.writeTexts()) : null)
                .writeItems(subject.writes() != null ? new java.util.ArrayList<>(subject.writes().stream()
                    .map(write -> new com.flowtivo.tinfirm.shared.api.model.DecisionWrite(write.text())
                        .kind(write.kind() != null ? DecisionWriteKind.fromValue(write.kind()) : null)
                        .target(write.target())
                        .value(write.value()))
                    .toList()) : null);
            if (subject.arguments() != null) {
                out.arguments(JSON.convertValue(subject.arguments(), ARGUMENTS));
            }
            if (subject.systemId() != null) {
                SystemInfo system = systemsById.computeIfAbsent(subject.systemId(),
                        id -> systems.find(row.companyId(), id).orElse(null));
                out.systemName(system != null ? system.name() : null);
            }
            if (subject.repositoryId() != null) {
                RepositoryInfo repository = repository(row.companyId(), subject.repositoryId(), conversation,
                        repositories);
                out.repositoryName(repository != null ? repository.name() : null);
            }
            dto.subject(out);
        }
        return dto;
    }

    /** A repository by its id, among the code hosts of the company (read once per presentation). */
    private @Nullable RepositoryInfo repository(UUID companyId, UUID repositoryId,
            @Nullable ConversationInfo conversation, Map<UUID, RepositoryInfo> repositories) {
        if (repositories.isEmpty()) {
            for (SystemInfo system : systems.visibleTo(companyId, conversation != null
                    ? conversation.actingPrincipalId() : null)) {
                if (system.kind().hasRepositories()) {
                    systems.repositories(companyId, system.id())
                        .forEach(repository -> repositories.put(repository.id(), repository));
                }
            }
        }
        return repositories.get(repositoryId);
    }
}
