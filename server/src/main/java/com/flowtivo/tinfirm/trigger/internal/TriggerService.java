package com.flowtivo.tinfirm.trigger.internal;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.flowtivo.tinfirm.account.AccountInfo;
import com.flowtivo.tinfirm.account.Accounts;
import com.flowtivo.tinfirm.account.GrantInfo;
import com.flowtivo.tinfirm.agent.AgentDefinition;
import com.flowtivo.tinfirm.agent.AgentInfo;
import com.flowtivo.tinfirm.agent.Agents;
import com.flowtivo.tinfirm.agent.EffectiveAgent;
import com.flowtivo.tinfirm.agent.TriggerSettings;
import com.flowtivo.tinfirm.audit.AuditAction;
import com.flowtivo.tinfirm.audit.AuditLog;
import com.flowtivo.tinfirm.audit.AuditRecord;
import com.flowtivo.tinfirm.company.CompanyDirectory;
import com.flowtivo.tinfirm.company.GroupMembership;
import com.flowtivo.tinfirm.conversation.ConversationInfo;
import com.flowtivo.tinfirm.conversation.TriggerConversations;
import com.flowtivo.tinfirm.model.ModelAccessChoice;
import com.flowtivo.tinfirm.model.RunModelAccess;
import com.flowtivo.tinfirm.principal.PrincipalInfo;
import com.flowtivo.tinfirm.principal.PrincipalKind;
import com.flowtivo.tinfirm.principal.PrincipalStatus;
import com.flowtivo.tinfirm.principal.Principals;
import com.flowtivo.tinfirm.shared.access.CompanyAccess;
import com.flowtivo.tinfirm.shared.access.CompanyAccessGuard;
import com.flowtivo.tinfirm.shared.access.CompanyPermission;
import com.flowtivo.tinfirm.shared.api.model.AgentSettings;
import com.flowtivo.tinfirm.shared.api.model.AgentSettingsRequest;
import com.flowtivo.tinfirm.shared.api.model.CompanySettings;
import com.flowtivo.tinfirm.shared.api.model.FiringLimits;
import com.flowtivo.tinfirm.shared.api.model.WebhookDeliveryLimits;
import com.flowtivo.tinfirm.shared.api.model.Trigger;
import com.flowtivo.tinfirm.shared.api.model.TriggerAccountInput;
import com.flowtivo.tinfirm.shared.api.model.TriggerAccountsRequest;
import com.flowtivo.tinfirm.shared.api.model.TriggerChannel;
import com.flowtivo.tinfirm.shared.api.model.TriggerChannelInput;
import com.flowtivo.tinfirm.shared.api.model.TriggerChoice;
import com.flowtivo.tinfirm.shared.api.model.TriggerChoiceSystem;
import com.flowtivo.tinfirm.shared.api.model.TriggerChoices;
import com.flowtivo.tinfirm.shared.api.model.TriggerCreateRequest;
import com.flowtivo.tinfirm.shared.api.model.TriggerFiring;
import com.flowtivo.tinfirm.shared.api.model.TriggerFiringPage;
import com.flowtivo.tinfirm.shared.api.model.TriggerHostCheck;
import com.flowtivo.tinfirm.shared.api.model.TriggerOpenToAnyone;
import com.flowtivo.tinfirm.shared.api.model.TriggerOpenWrite;
import com.flowtivo.tinfirm.shared.api.model.TriggerPage;
import com.flowtivo.tinfirm.shared.api.model.TriggerPausedReason;
import com.flowtivo.tinfirm.shared.api.model.TriggerPoll;
import com.flowtivo.tinfirm.shared.api.model.TriggerPollList;
import com.flowtivo.tinfirm.shared.api.model.TriggerPreview;
import com.flowtivo.tinfirm.shared.api.model.TriggerPreviewAccount;
import com.flowtivo.tinfirm.shared.api.model.TriggerPreviewRequest;
import com.flowtivo.tinfirm.shared.api.model.TriggerReassignRequest;
import com.flowtivo.tinfirm.shared.api.model.TriggerRights;
import com.flowtivo.tinfirm.shared.api.model.TriggerSubjectHit;
import com.flowtivo.tinfirm.shared.api.model.TriggerSubjectList;
import com.flowtivo.tinfirm.shared.api.model.TriggerSummary;
import com.flowtivo.tinfirm.shared.api.model.TriggerSystemRef;
import com.flowtivo.tinfirm.shared.api.model.TriggerUpdateRequest;
import com.flowtivo.tinfirm.shared.api.model.TriggerWaitingReason;
import com.flowtivo.tinfirm.shared.id.Ids;
import com.flowtivo.tinfirm.shared.pagination.Page;
import com.flowtivo.tinfirm.shared.pagination.PageRequest;
import com.flowtivo.tinfirm.shared.security.CurrentActor;
import com.flowtivo.tinfirm.shared.sql.Constraints;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.web.ApiException;
import com.flowtivo.tinfirm.shared.web.ProblemCode;
import com.flowtivo.tinfirm.system.SystemInfo;
import com.flowtivo.tinfirm.system.Systems;
import com.flowtivo.tinfirm.system.WebhookSystems;
import com.flowtivo.tinfirm.trigger.FiringOutcome;
import com.flowtivo.tinfirm.trigger.PausedReason;
import com.flowtivo.tinfirm.trigger.ChannelSources;
import com.flowtivo.tinfirm.trigger.TriggerAccount;
import com.flowtivo.tinfirm.trigger.TriggerEvents;
import com.flowtivo.tinfirm.trigger.TriggerGate;
import com.flowtivo.tinfirm.trigger.TriggerInfo;
import com.flowtivo.tinfirm.trigger.TriggerRepeat;
import com.flowtivo.tinfirm.trigger.TriggerSchedule;
import com.flowtivo.tinfirm.trigger.TriggerSource;
import com.flowtivo.tinfirm.trigger.TriggerStatus;

/**
 * The trigger API (the package 4 contract, §6): create, change, delete, pause, resume, run now and reassign triggers
 * (§5.1, §5.2, §5.11, with §11), the editor's one choice and preview, a trigger's settings, firings and polls, and the
 * company's firing limits. Who may do what is {@link TriggerCallers}; every choice is checked for the trigger's author
 * ({@link TriggerEditor}), an admin's on the author's behalf too. A trigger's row is locked first, then its
 * conversations (CONVENTIONS §10); each change is audited and announced ({@link TriggerEvents}).
 */
@Service
class TriggerService {

    /** V030's unique indexes on a channel's rows: one row per agent, one default. */
    private static final String CHANNEL_AGENT_INDEX = "trigger_channel_agent_uq";

    private static final String CHANNEL_DEFAULT_INDEX = "trigger_channel_default_uq";

    /** V011's unique index on the names of the company's live triggers, ignoring case. */
    private static final String NAME_INDEX = "trigger_name_uq";

    /** The most live triggers one system has, and the most schedules a company has (§3.1). */
    static final int MAX_PER_SYSTEM = 100;

    static final int MAX_SCHEDULES = 50;

    /** The highest firing limit the database takes ({@code trigger_rate_ck}). */
    static final int MAX_LIMIT = 10_000;

    /** The most a company's webhook deliveries a minute may be (the package 5 contract, §6). */
    static final int MAX_WEBHOOK_LIMIT = 6_000;

    private final TriggerStore store;

    private final TriggerEditor editor;

    private final TriggerCallers callers;

    private final TriggerStates states;

    private final TriggerGateImpl gate;

    private final TriggerSettings triggerSettings;

    private final TriggerConversations conversations;

    private final FiringConversations firings;

    private final ObjectProvider<ManualFirings> manualFirings;

    private final ObjectProvider<SubjectSearch> subjectSearch;

    private final Principals principals;

    private final Agents agents;

    private final Accounts accounts;

    private final Systems systems;

    private final GroupMembership groups;

    private final CompanyAccessGuard companyAccess;

    private final AuditLog auditLog;

    private final ApplicationEventPublisher events;

    private final TriggerProperties properties;

    private final TimeSource time;

    /** A short read-only transaction: the caller's check before a call to a system (CONVENTIONS §10). */
    private final TransactionTemplate readOnly;

    private final ObjectProvider<ChannelSources> channelSources;

    private final WebhookLimitsImpl webhookLimits;

    private final WebhookSystems webhookSystems;

    private final CompanyDirectory companies;

    private final TriggerExposures exposures;

    TriggerService(TriggerStore store, TriggerEditor editor, TriggerCallers callers, TriggerStates states,
            TriggerGateImpl gate, TriggerSettings triggerSettings, TriggerConversations conversations,
            FiringConversations firings, ObjectProvider<ManualFirings> manualFirings,
            ObjectProvider<SubjectSearch> subjectSearch, Principals principals, Agents agents, Accounts accounts,
            Systems systems, GroupMembership groups, CompanyAccessGuard companyAccess, AuditLog auditLog,
            ApplicationEventPublisher events, TriggerProperties properties, TimeSource time,
            PlatformTransactionManager transactionManager, ObjectProvider<ChannelSources> channelSources,
            WebhookLimitsImpl webhookLimits, WebhookSystems webhookSystems, CompanyDirectory companies,
            TriggerExposures exposures) {
        this.companies = companies;
        this.exposures = exposures;
        this.channelSources = channelSources;
        this.webhookLimits = webhookLimits;
        this.webhookSystems = webhookSystems;
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
        this.store = store;
        this.editor = editor;
        this.callers = callers;
        this.states = states;
        this.gate = gate;
        this.triggerSettings = triggerSettings;
        this.conversations = conversations;
        this.firings = firings;
        this.manualFirings = manualFirings;
        this.subjectSearch = subjectSearch;
        this.principals = principals;
        this.agents = agents;
        this.accounts = accounts;
        this.systems = systems;
        this.groups = groups;
        this.companyAccess = companyAccess;
        this.auditLog = auditLog;
        this.events = events;
        this.properties = properties;
        this.time = time;
    }

    // ---- who -------------------------------------------------------------------------------------------------------

    /** The caller and a trigger they see, with their rights; 404 for anything else (T15). */
    private record Seen(TriggerCallers.Caller caller, TriggerInfo trigger, TriggerCallers.Rights rights) {
    }

    private TriggerCallers.Caller caller(UUID companyId) {
        CompanyAccess access = companyAccess.require(companyId, CompanyPermission.VIEW_COMPANY);
        return callers.of(access, CurrentActor.requireUser().id());
    }

    private Seen seen(UUID companyId, UUID triggerId, boolean lock) {
        TriggerCallers.Caller caller = caller(companyId);
        Optional<TriggerInfo> found = lock ? store.lock(companyId, triggerId) : store.find(companyId, triggerId);
        TriggerInfo trigger = found.filter(TriggerInfo::live).orElseThrow(TriggerService::notFound);
        TriggerCallers.Rights rights = caller.rights(trigger);
        if (!rights.sees()) {
            throw notFound();
        }
        return new Seen(caller, trigger, rights);
    }

    /**
     * A live trigger whose audience the caller is (its author, its team, OWNER, ADMIN; T15), for its memory
     * ({@link TriggerMemory}); 404 for anything else.
     */
    TriggerInfo audience(UUID companyId, UUID triggerId, boolean lock) {
        return seen(companyId, triggerId, lock).trigger();
    }

    /** A trigger the caller edits: its author, or an admin on the author's behalf (§11); else 403. */
    private Seen editable(UUID companyId, UUID triggerId) {
        Seen seen = seen(companyId, triggerId, true);
        if (!seen.rights().edits()) {
            throw ApiException.forbidden(ProblemCode.TRIGGER_NOT_EDITABLE, "Only the trigger's author and admins "
                    + "change it");
        }
        return seen;
    }

    static ApiException notFound() {
        return ApiException.notFound("Trigger not found");
    }

    /** The author a change is checked for (§11: never the admin who makes it). */
    private TriggerEditor.Author author(TriggerInfo trigger) {
        PrincipalInfo author = principals.find(trigger.companyId(), trigger.authorPrincipalId())
            .filter(found -> found.status() != PrincipalStatus.LEFT && found.userId() != null)
            .orElseThrow(() -> ApiException.conflict(ProblemCode.TRIGGER_PAUSED, "The trigger's author left: an "
                    + "admin reassigns it first", Map.of("details", Map.of("reason", "AUTHOR_LEFT"))));
        return new TriggerEditor.Author(author.id(), Objects.requireNonNull(author.userId()));
    }

    /**
     * Whether the caller saves on the author's behalf (an admin who is not the author, §11): such a save names the
     * author's own accounts and MCP servers only where the trigger had them (H2).
     */
    private static boolean onBehalf(Seen seen) {
        return !seen.caller().author(seen.trigger());
    }

    /**
     * A member who may author a trigger: active, a member's principal with a user, holding a seat (OWNER, ADMIN or
     * MEMBER; never a VIEWER, M7 of the security review of 2026-10-27); else {@code 400 VALIDATION_FAILED} naming the
     * field.
     */
    private PrincipalInfo seatHolder(UUID companyId, @Nullable UUID principalId, String field) {
        return principals.find(companyId, principalId != null ? principalId : new UUID(0, 0))
            .filter(found -> found.kind() == PrincipalKind.MEMBER && found.active() && found.userId() != null)
            .filter(found -> TriggerGateImpl.holdsSeat(companies.roleOf(companyId, Objects.requireNonNull(
                    found.userId())).orElse(null)))
            .orElseThrow(() -> ApiException.validation(field + " is not an active member of the company who holds a "
                    + "seat: a Viewer authors no trigger"));
    }

    /** Whether the trigger may be reassigned: only after its author's loss, or a reassignment not accepted yet (H2). */
    static boolean reassignable(TriggerInfo trigger) {
        return trigger.paused() && (trigger.pausedReason() == PausedReason.AUTHOR_LEFT
                || trigger.pausedReason() == PausedReason.NOT_ENTITLED
                || trigger.pausedReason() == PausedReason.TO_ACCEPT);
    }

    /** After a change: the author's own clears an admin's note; an admin's leaves one for the author (§11). */
    private void changedBy(TriggerCallers.Caller caller, TriggerInfo trigger, String what) {
        if (caller.author(trigger)) {
            store.authorChanged(trigger.companyId(), trigger.id());
        }
        else {
            store.adminChanged(trigger.companyId(), trigger.id(), caller.principalId(), what, time.now());
        }
    }

    // ---- reads -----------------------------------------------------------------------------------------------------

    /**
     * The triggers the caller sees, by name (T15), filtered; {@code accountHolderPrincipalId} keeps those whose table
     * holds an account of that principal (a service identity's page).
     */
    @Transactional(readOnly = true)
    public TriggerPage list(UUID companyId, @Nullable UUID systemId, @Nullable UUID agentId, @Nullable UUID authorId,
            @Nullable UUID teamGroupId, @Nullable TriggerStatus status, @Nullable UUID accountHolderPrincipalId,
            @Nullable Integer limit, @Nullable String cursor) {
        TriggerCallers.Caller caller = caller(companyId);
        if (!caller.admin() && caller.principalId() == null) {
            return new TriggerPage(new ArrayList<>());
        }
        PageRequest request = PageRequest.of(limit, cursor);
        TriggerStore.Filter filter = new TriggerStore.Filter(systemId, agentId, authorId, teamGroupId, status,
                caller.admin(), caller.principalId(), caller.groupIds(), accountHolderPrincipalId);
        List<TriggerInfo> rows = request.firstPage()
                ? store.page(companyId, filter, null, null, request.fetchSize())
                : store.page(companyId, filter, request.key(0, 2), request.uuidKey(1, 2), request.fetchSize());
        Page<TriggerInfo> page = Page.of(rows, request, row -> List.of(row.name(), row.id().toString()));
        Names names = new Names(companyId);
        Map<UUID, FiringView> last = store.lastFirings(companyId, page.items().stream().map(TriggerInfo::id).toList());
        List<TriggerSummary> items = new ArrayList<>();
        for (TriggerInfo trigger : page.items()) {
            FiringView firing = last.get(trigger.id());
            items.add(new TriggerSummary(trigger.id(), trigger.name(),
                    TriggerViews.sentence(trigger, names.system(trigger.systemId()), names.agent(trigger.agentId())),
                    com.flowtivo.tinfirm.shared.api.model.TriggerSource.fromValue(trigger.source().name()),
                    trigger.agentId(), com.flowtivo.tinfirm.shared.api.model.TriggerStatus.fromValue(
                            trigger.status().name()), trigger.authorPrincipalId(), TriggerViews.ts(trigger.createdAt()),
                    TriggerViews.ts(trigger.updatedAt()))
                .systemId(trigger.systemId())
                .systemName(names.system(trigger.systemId()))
                .agentName(names.agent(trigger.agentId()))
                .pausedReason(trigger.pausedReason() != null
                        ? TriggerPausedReason.fromValue(trigger.pausedReason().name()) : null)
                .waitingReason(trigger.waitingReason() != null
                        ? TriggerWaitingReason.fromValue(trigger.waitingReason().name()) : null)
                .authorName(names.principal(trigger.authorPrincipalId()))
                .teamGroupId(trigger.teamGroupId())
                .teamName(names.group(trigger.teamGroupId()))
                .payerPrincipalId(trigger.payerPrincipalId())
                .payerName(names.principal(trigger.payerPrincipalId()))
                .lastFiring(firing != null ? TriggerViews.firing(firing) : null));
        }
        return new TriggerPage(items).nextCursor(page.nextCursor());
    }

    @Transactional(readOnly = true)
    public Trigger get(UUID companyId, UUID triggerId) {
        Seen seen = seen(companyId, triggerId, false);
        return view(seen.caller(), seen.trigger());
    }

    /** The trigger in full, as the caller sees it. */
    Trigger view(TriggerCallers.Caller caller, TriggerInfo trigger) {
        UUID companyId = trigger.companyId();
        Names names = new Names(companyId);
        AgentInfo agent = agents.find(companyId, trigger.agentId()).orElse(null);
        int accepted = triggerSettings.acceptedVersion(companyId, trigger.id());
        int current = agent != null ? agent.version() : accepted;
        List<TriggerAccount> rows = store.accounts(companyId, trigger.id());
        List<com.flowtivo.tinfirm.shared.api.model.TriggerAccount> accountViews = new ArrayList<>();
        for (TriggerAccount row : rows) {
            PrincipalInfo holder = principals.find(companyId, row.holderPrincipalId()).orElse(null);
            AccountInfo account = row.accountId() != null ? accounts.find(companyId, row.accountId()).orElse(null)
                    : null;
            accountViews.add(new com.flowtivo.tinfirm.shared.api.model.TriggerAccount(row.systemId(),
                    row.holderPrincipalId(), com.flowtivo.tinfirm.shared.api.model.TriggerAccount.FilledFromEnum
                        .fromValue(row.filledFrom().name()), rowStatus(trigger, row, account, holder))
                .systemName(names.system(row.systemId()))
                .accountId(row.accountId())
                .holderName(holder != null ? holder.name() : null)
                .holderKind(holder != null && holder.kind() == PrincipalKind.SERVICE
                        ? com.flowtivo.tinfirm.shared.api.model.TriggerAccount.HolderKindEnum.SERVICE
                        : com.flowtivo.tinfirm.shared.api.model.TriggerAccount.HolderKindEnum.MEMBER)
                .credentialName(account != null ? account.name() : null)
                .externalLogin(account != null ? account.externalLogin() : null)
                .access(account != null ? access(accounts.fixedAccess(companyId, row.systemId(),
                        row.holderPrincipalId(), account.id())) : null));
        }
        List<TriggerSystemRef> unavailable = new ArrayList<>();
        List<TriggerSystemRef> noAccount = new ArrayList<>();
        EffectiveAgent effective = agent != null && agent.live()
                ? agents.effective(companyId, agent.id(), accepted).orElse(null) : null;
        // A newer version that widens nothing waits for no acceptance (as an agent's own page decides).
        boolean wideningPending = current > accepted && effective != null && effective.wideningPending();
        if (effective != null) {
            Set<UUID> withRow = new HashSet<>();
            rows.forEach(row -> withRow.add(row.systemId()));
            Map<UUID, SystemInfo> infos = systems.findAll(companyId, effective.definition().systems()
                .stream()
                .map(AgentDefinition.SystemEntry::systemId)
                .toList());
            for (var system : effective.definition().systems()) {
                if (!withRow.contains(system.systemId())) {
                    SystemInfo info = infos.get(system.systemId());
                    // An MCP server that needs no account is the runs' without a row (ADR-0071).
                    (info != null && !info.accountsRequired() ? noAccount : unavailable)
                        .add(new TriggerSystemRef(system.systemId()).systemName(names.system(system.systemId())));
                }
            }
        }
        TriggerCallers.Rights rights = caller.rights(trigger);
        Trigger view = new Trigger(trigger.id(), trigger.name(),
                TriggerViews.sentence(trigger, names.system(trigger.systemId()), agent != null ? agent.name() : null),
                com.flowtivo.tinfirm.shared.api.model.TriggerSource.fromValue(trigger.source().name()),
                TriggerViews.events(trigger),
                com.flowtivo.tinfirm.shared.api.model.TriggerSubject.fromValue(trigger.subject().name()),
                TriggerViews.conditions(trigger),
                com.flowtivo.tinfirm.shared.api.model.TriggerRepeat.fromValue(trigger.repeat().name()),
                trigger.agentId(), accepted, current, wideningPending, trigger.instruction(),
                trigger.authorPrincipalId(), TriggerViews.results(trigger.results()), trigger.maxFiringsPerHour(),
                com.flowtivo.tinfirm.shared.api.model.TriggerStatus.fromValue(trigger.status().name()),
                TriggerViews.ts(trigger.armedAt()), trigger.missedCount(), trigger.rateSkippedCount(), accountViews,
                unavailable, noAccount, new TriggerRights(rights.edits(), rights.runs(), rights.pauses(),
                        rights.resumes(), rights.deletes(), rights.reassigns()), TriggerViews.ts(trigger.createdAt()),
                TriggerViews.ts(trigger.updatedAt()))
            .systemId(trigger.systemId())
            .systemName(names.system(trigger.systemId()))
            .scope(trigger.scope())
            .agentName(agent != null ? agent.name() : null)
            .authorName(names.principal(trigger.authorPrincipalId()))
            .teamGroupId(trigger.teamGroupId())
            .teamName(names.group(trigger.teamGroupId()))
            .pausedReason(trigger.pausedReason() != null
                    ? TriggerPausedReason.fromValue(trigger.pausedReason().name()) : null)
            .pausedAt(TriggerViews.ts(trigger.pausedAt()))
            .waitingReason(trigger.waitingReason() != null
                    ? TriggerWaitingReason.fromValue(trigger.waitingReason().name()) : null)
            .waitingSince(TriggerViews.ts(trigger.waitingSince()))
            .openEventsAcknowledgedAt(TriggerViews.ts(trigger.openEventsAcknowledgedAt()))
            .openConversations(conversations.openOf(companyId, trigger.id()).size())
            .deletedAt(TriggerViews.ts(trigger.deletedAt()));
        if (trigger.source() == TriggerSource.CHANNEL && trigger.systemId() != null) {
            List<TriggerStore.ChannelRow> channels = store.channels(companyId, trigger.id());
            Map<String, String> channelNames = new HashMap<>();
            ChannelSources sources = channelSources.getIfAvailable();
            if (sources != null && !channels.isEmpty()) {
                sources.channels(companyId, trigger.systemId(), channels.stream()
                    .map(TriggerStore.ChannelRow::channelId)
                    .collect(java.util.stream.Collectors.toSet()))
                    .forEach(channel -> {
                        if (channel.name() != null) {
                            channelNames.put(channel.channelId(), channel.name());
                        }
                    });
            }
            view.channels(new ArrayList<>(channels.stream()
                .map(channel -> new TriggerChannel(channel.channelId(), channel.isDefault(), channel.available()
                        ? TriggerChannel.StateEnum.ACTIVE : TriggerChannel.StateEnum.UNAVAILABLE)
                    .name(channelNames.get(channel.channelId())))
                .toList()));
        }
        if (trigger.source() == TriggerSource.WEBHOOK) {
            view.webhookConditions(savedWebhookConditions(trigger));
        }
        TriggerSchedule schedule = trigger.schedule();
        if (schedule != null) {
            List<Instant> next = List.of();
            try {
                next = Schedule.parse(schedule.cron(), schedule.zone()).next(time.now(), 3);
            }
            catch (RuntimeException ex) {
                next = List.of();
            }
            view.schedule(new com.flowtivo.tinfirm.shared.api.model.TriggerSchedule(schedule.cron(), schedule.zone(),
                    com.flowtivo.tinfirm.shared.api.model.TriggerSchedule.OverlapEnum.fromValue(
                            schedule.overlap().name()),
                    com.flowtivo.tinfirm.shared.api.model.TriggerSchedule.CatchUpEnum.fromValue(
                            schedule.catchUp().name()),
                    new ArrayList<>(next.stream().map(TriggerViews::ts).toList()))
                .nextRunAt(TriggerViews.ts(schedule.nextRunAt()))
                .lastRunAt(TriggerViews.ts(schedule.lastRunAt()))
                .lastOutcome(schedule.lastOutcome() != null
                        ? com.flowtivo.tinfirm.shared.api.model.TriggerFiringOutcome.fromValue(
                                schedule.lastOutcome().name()) : null));
        }
        UUID payer = trigger.payerPrincipalId();
        if (payer != null) {
            PrincipalInfo payerInfo = principals.find(companyId, payer).orElse(null);
            view.payer(TriggerViews.payer(payer, payerInfo != null ? payerInfo.name() : null,
                    payerInfo != null && payerInfo.kind() == PrincipalKind.SERVICE, modelAccess(trigger), null));
        }
        FiringView last = store.lastFirings(companyId, List.of(trigger.id())).get(trigger.id());
        if (last != null) {
            view.lastFiring(TriggerViews.firing(last));
        }
        return view;
    }

    /**
     * The model access the trigger's settings name: a subscription only while the company allows one (ADR-0085,
     * decision 7; the gate pauses a trigger that still names one once the company turned it off).
     */
    private ModelAccessChoice modelAccess(TriggerInfo trigger) {
        try {
            AgentSettings settings = triggerSettings.view(trigger.companyId(), trigger.agentId(), trigger.id());
            if (settings.getModelAccess() != null && "SUBSCRIPTION".equals(settings.getModelAccess().getValue())
                    && settings.getSubscription() != null && settings.getSubscription().getRunnerHostId() != null) {
                return new ModelAccessChoice.Subscription(settings.getSubscription().getRunnerHostId(),
                        settings.getSubscription().getLabel());
            }
        }
        catch (RuntimeException ex) {
            // A deleted agent: the budget, as the row says nothing more.
        }
        return ModelAccessChoice.BUDGET;
    }

    /** Whether the trigger's conversations can bind the row now ({@code Accounts.check} for the author). */
    private com.flowtivo.tinfirm.shared.api.model.TriggerAccount.StatusEnum rowStatus(TriggerInfo trigger,
            TriggerAccount row, @Nullable AccountInfo account, @Nullable PrincipalInfo holder) {
        if (account == null) {
            return com.flowtivo.tinfirm.shared.api.model.TriggerAccount.StatusEnum.ACCOUNT_REMOVED;
        }
        var check = accounts.check(new com.flowtivo.tinfirm.account.AccountBinding.Stored(trigger.companyId(),
                row.systemId(), trigger.authorPrincipalId(), row.holderPrincipalId(), account.id(), null,
                row.externalId()));
        if (check instanceof com.flowtivo.tinfirm.account.AccountBinding.Usable) {
            return com.flowtivo.tinfirm.shared.api.model.TriggerAccount.StatusEnum.OK;
        }
        return switch (((com.flowtivo.tinfirm.account.AccountBinding.Stopped) check).reason()) {
            case RECONNECT_YOURS -> com.flowtivo.tinfirm.shared.api.model.TriggerAccount.StatusEnum.RECONNECT_YOURS;
            case RECONNECT_COMPANY ->
                com.flowtivo.tinfirm.shared.api.model.TriggerAccount.StatusEnum.RECONNECT_COMPANY;
            case PRINCIPAL_INACTIVE -> holder != null && holder.status() == PrincipalStatus.SUSPENDED
                    ? com.flowtivo.tinfirm.shared.api.model.TriggerAccount.StatusEnum.SUSPENDED
                    : com.flowtivo.tinfirm.shared.api.model.TriggerAccount.StatusEnum.NOT_ENTITLED;
            case IDENTITY_CHANGED, SYSTEM_GONE ->
                com.flowtivo.tinfirm.shared.api.model.TriggerAccount.StatusEnum.ACCOUNT_REMOVED;
            default -> com.flowtivo.tinfirm.shared.api.model.TriggerAccount.StatusEnum.NOT_ENTITLED;
        };
    }

    @Transactional(readOnly = true)
    public TriggerFiringPage firings(UUID companyId, UUID triggerId, @Nullable Integer limit,
            @Nullable String cursor) {
        seen(companyId, triggerId, false);
        PageRequest request = PageRequest.of(limit, cursor);
        List<FiringView> rows = request.firstPage()
                ? store.firings(companyId, triggerId, null, null, request.fetchSize())
                : store.firings(companyId, triggerId, request.instantKey(0, 2), request.uuidKey(1, 2),
                        request.fetchSize());
        Page<FiringView> page = Page.of(rows, request,
                row -> List.of(row.createdAt().toString(), row.id().toString()));
        return new TriggerFiringPage(new ArrayList<>(page.items().stream().map(TriggerViews::firing).toList()))
            .nextCursor(page.nextCursor());
    }

    @Transactional(readOnly = true)
    public TriggerPollList polls(UUID companyId, UUID systemId) {
        TriggerCallers.Caller caller = caller(companyId);
        if (!caller.admin()) {
            throw ApiException.forbidden("Only admins see a system's polls");
        }
        systems.find(companyId, systemId).orElseThrow(() -> ApiException.notFound("System not found"));
        List<TriggerStore.PollRow> polls = store.polls(companyId, systemId);
        Map<UUID, String> names = principals.displayNames(companyId, polls.stream()
            .map(TriggerStore.PollRow::holderPrincipalId)
            .filter(java.util.Objects::nonNull)
            .distinct()
            .toList());
        List<TriggerPoll> items = new ArrayList<>();
        for (TriggerStore.PollRow poll : polls) {
            // A member's own account's poll names its member; a credential's, which every service account's triggers
            // share, the credential (ADR-0072).
            UUID holder = poll.holderPrincipalId();
            items.add(new TriggerPoll(poll.id(), poll.accountId(), TriggerPoll.StatusEnum.fromValue(poll.status()),
                    new ArrayList<>(poll.triggerIds()), TriggerViews.ts(poll.nextPollAt()), poll.failures())
                .holderPrincipalId(holder)
                .holderName(holder != null ? names.get(holder) : null)
                .credentialName(holder == null ? accounts.find(companyId, poll.accountId())
                    .filter(AccountInfo::credential)
                    .map(AccountInfo::name)
                    .orElse(null) : null)
                .lastPolledAt(TriggerViews.ts(poll.lastPolledAt()))
                .lastError(poll.lastError())
                .backoffUntil(TriggerViews.ts(poll.backoffUntil()))
                .failingSince(TriggerViews.ts(poll.failingSince()))
                .rateLimit(poll.rateLimit())
                .rateRemaining(poll.rateRemaining())
                .rateResetsAt(TriggerViews.ts(poll.rateResetsAt())));
        }
        return new TriggerPollList(items);
    }

    /** The subjects of run now (§6): the caller is checked in a short transaction, the system read outside any. */
    public TriggerSubjectList subjects(UUID companyId, UUID triggerId, @Nullable String query) {
        Seen seen = Objects.requireNonNull(readOnly.execute(status -> seen(companyId, triggerId, false)));
        if (seen.trigger().source() != TriggerSource.TRACKER) {
            return new TriggerSubjectList(new ArrayList<>());
        }
        SubjectSearch search = subjectSearch.getIfAvailable();
        if (search == null) {
            return new TriggerSubjectList(new ArrayList<>());
        }
        return new TriggerSubjectList(new ArrayList<>(search.search(companyId, triggerId, query).stream()
            .map(hit -> new TriggerSubjectHit(hit.key(), TriggerSubjectHit.KindEnum.fromValue(hit.kind()))
                .title(hit.title())
                .status(hit.status())
                .url(hit.url()))
            .toList()));
    }

    // ---- the editor ------------------------------------------------------------------------------------------------

    /**
     * The one choice (§5.2) for the caller, or with {@code forPrincipalId} for that member as a save checks it for a
     * trigger's author (§11). Another member's only for OWNER and ADMIN, and only for the trigger they name
     * ({@code triggerId}): its author, for an edit on the author's behalf, or the new author of a reassignment it may
     * have (paused {@code AUTHOR_LEFT}, {@code NOT_ENTITLED} or {@code TO_ACCEPT}), a member who holds a seat; anyone
     * else only themselves (403). On the author's behalf the author's own accounts are offered only where the trigger's
     * row names them already, and a reassignment's new author's never: only the author adds them (H2).
     */
    @Transactional(readOnly = true)
    public TriggerChoices choices(UUID companyId, @Nullable UUID agentId, @Nullable UUID systemId,
            @Nullable UUID forPrincipalId, @Nullable UUID triggerId) {
        TriggerCallers.Caller caller = caller(companyId);
        if (agentId == null) {
            throw ApiException.validation("agentId names the agent the trigger would use");
        }
        TriggerEditor.Author author;
        AgentInfo agent;
        // The author's own accounts an admin may see offered: those the trigger's rows name already (H2).
        Set<UUID> ownOffered = null;
        if (forPrincipalId == null || forPrincipalId.equals(caller.principalId())) {
            author = new TriggerEditor.Author(caller.requirePrincipal(), caller.userId());
            agent = usableAgent(companyId, agentId, caller.userId());
        }
        else {
            if (!caller.admin()) {
                throw ApiException.forbidden("Only admins ask for another member's choices, for a trigger they edit "
                        + "on its author's behalf or reassign");
            }
            if (triggerId == null) {
                throw ApiException.validation("triggerId names the trigger whose author, or whose reassignment's new "
                        + "author, forPrincipalId is");
            }
            Seen seen = seen(companyId, triggerId, false);
            TriggerInfo trigger = seen.trigger();
            boolean ofTheAuthor = forPrincipalId.equals(trigger.authorPrincipalId()) && seen.rights().edits();
            boolean ofANewAuthor = !ofTheAuthor && seen.rights().reassigns() && reassignable(trigger);
            if (!ofTheAuthor && !ofANewAuthor) {
                throw ApiException.forbidden("Another member's choices are answered only for the author of a trigger "
                        + "the caller edits, or for a new author of a trigger whose author left or lost a right");
            }
            PrincipalInfo member = ofTheAuthor
                    ? principals.find(companyId, forPrincipalId)
                        .filter(found -> found.kind() == PrincipalKind.MEMBER && found.status() != PrincipalStatus.LEFT
                                && found.userId() != null)
                        .orElseThrow(() -> ApiException.validation("forPrincipalId is not a member of the company"))
                    : seatHolder(companyId, forPrincipalId, "forPrincipalId");
            author = new TriggerEditor.Author(member.id(), Objects.requireNonNull(member.userId()));
            agent = agents.find(companyId, agentId).filter(AgentInfo::live)
                .orElseThrow(() -> ApiException.notFound("Agent not found"));
            if (!agents.access(companyId, agent.id(), author.userId()).uses()) {
                throw ApiException.forbidden(ProblemCode.AGENT_NOT_USABLE, member.name() + " may not use the agent "
                        + agent.name());
            }
            ownOffered = new HashSet<>();
            if (ofTheAuthor) {
                for (TriggerAccount row : store.accounts(companyId, trigger.id())) {
                    if (row.accountId() != null && row.holderPrincipalId().equals(member.id())) {
                        ownOffered.add(row.accountId());
                    }
                }
            }
        }
        Names names = new Names(companyId);
        List<TriggerChoice> items = new ArrayList<>();
        for (TriggerEditor.Choice each : editor.choices(companyId, author, agent, systemId)) {
            TriggerEditor.Choice choice = ownOffered != null && each.principal().id().equals(author.principalId())
                    ? onlyOffered(each, ownOffered) : each;
            List<TriggerChoiceSystem> perSystem = new ArrayList<>();
            choice.accounts().forEach((system, account) -> perSystem.add(new TriggerChoiceSystem(system,
                    choice.noAccount().contains(system) ? TriggerChoiceSystem.StatusEnum.NOT_NEEDED
                            : account.isEmpty() ? TriggerChoiceSystem.StatusEnum.NONE
                            : account.get().ok() ? TriggerChoiceSystem.StatusEnum.OK
                                    : TriggerChoiceSystem.StatusEnum.RECONNECT)
                .systemName(names.system(system))
                .accountId(account.map(AccountInfo::id).orElse(null))
                .credentialName(account.map(AccountInfo::name).orElse(null))
                .externalLogin(account.map(AccountInfo::externalLogin).orElse(null))
                .access(account.map(found -> access(accounts.fixedAccess(companyId, system,
                        choice.principal().id(), found.id()))).orElse(null))));
            boolean service = choice.principal().kind() == PrincipalKind.SERVICE;
            items.add(new TriggerChoice(choice.principal().id(), service ? TriggerChoice.KindEnum.SERVICE
                    : TriggerChoice.KindEnum.MEMBER, perSystem, TriggerViews.payer(choice.principal().id(),
                            choice.principal().name(), service, ModelAccessChoice.BUDGET, choice.budget())
                        .models(new ArrayList<>(choice.models())))
                .name(choice.principal().name()));
        }
        return new TriggerChoices(items);
    }

    /** The author's choice with only their own accounts an admin may name for them: those the trigger has (H2). */
    private static TriggerEditor.Choice onlyOffered(TriggerEditor.Choice choice, Set<UUID> offered) {
        Map<UUID, Optional<AccountInfo>> accounts = new LinkedHashMap<>();
        choice.accounts().forEach((system, account) -> accounts.put(system,
                account.filter(found -> offered.contains(found.id()))));
        return new TriggerEditor.Choice(choice.principal(), accounts, choice.noAccount(), choice.budget(),
                choice.models());
    }

    @Transactional(readOnly = true)
    public TriggerPreview preview(UUID companyId, TriggerPreviewRequest request) {
        return preview(companyId, request, null);
    }

    /** A preview, with what the chat said of the author's channels ({@link #joined}). */
    @Transactional(readOnly = true)
    public TriggerPreview preview(UUID companyId, TriggerPreviewRequest request,
            ChannelSources.@Nullable Joined joined) {
        TriggerCallers.Caller caller = caller(companyId);
        TriggerInfo existing = null;
        TriggerEditor.Author author;
        boolean onBehalf = false;
        if (request.getTriggerId() != null) {
            Seen seen = seen(companyId, request.getTriggerId(), false);
            if (!seen.rights().edits()) {
                throw ApiException.forbidden(ProblemCode.TRIGGER_NOT_EDITABLE, "Only the trigger's author and admins "
                        + "change it");
            }
            existing = seen.trigger();
            author = author(existing);
            onBehalf = onBehalf(seen);
        }
        else {
            author = new TriggerEditor.Author(caller.requirePrincipal(), caller.userId());
        }
        TriggerCreateRequest draft = Objects.requireNonNull(request.getDraft(), "draft");
        TriggerEditor.Checked checked = editor.check(companyId, author, draft(draft, existing, joined), existing,
                time.now(), true, onBehalf);
        Names names = new Names(companyId);
        List<TriggerPreviewAccount> rows = new ArrayList<>();
        for (TriggerEditor.Row row : checked.rows()) {
            rows.add(new TriggerPreviewAccount(row.systemId(), TriggerPreviewAccount.StatusEnum.fromValue(
                    row.status().name()))
                .systemName(names.system(row.systemId()))
                .accountId(row.account().id())
                .holderPrincipalId(row.holderPrincipalId())
                .holderName(names.principal(row.holderPrincipalId()))
                .holderKind(row.own() ? TriggerPreviewAccount.HolderKindEnum.MEMBER
                        : TriggerPreviewAccount.HolderKindEnum.SERVICE)
                .credentialName(row.account().name())
                .externalLogin(row.account().externalLogin())
                .access(access(accounts.fixedAccess(companyId, row.systemId(), row.holderPrincipalId(),
                        row.account().id()))));
        }
        TriggerHostCheck hosts = switch (checked.eligibility()) {
            case com.flowtivo.tinfirm.model.Hosts.Eligibility.Eligible eligible -> new TriggerHostCheck(
                    !eligible.hosts().isEmpty(), eligible.hosts().size()).reason(eligible.whyNone());
            case com.flowtivo.tinfirm.model.Hosts.Eligibility.Nowhere nowhere -> new TriggerHostCheck(false, 0)
                .reason(nowhere.refusal().detail());
        };
        TriggerInfo shape = shape(companyId, existing, checked, author.principalId(), draft);
        TriggerPreview preview = new TriggerPreview(rows, new ArrayList<>(checked.unavailable().stream()
            .map(system -> new TriggerSystemRef(system.id()).systemName(system.name()))
            .toList()), new ArrayList<>(checked.noAccount().stream()
                .map(system -> new TriggerSystemRef(system.id()).systemName(system.name()))
                .toList()), TriggerViews.payer(checked.payer().id(), checked.payer().name(),
                    checked.payer().kind() == PrincipalKind.SERVICE, checked.modelAccess(), checked.payment()),
                hosts, TriggerViews.sentence(shape, checked.definition().system() != null
                        ? checked.definition().system().name() : null, checked.agent().name()),
                new ArrayList<>(checked.nextFirings().stream().map(TriggerViews::ts).toList()));
        if (checked.definition().source() == TriggerSource.WEBHOOK && checked.definition().system() != null) {
            // Echoes (§5.12): a write back to the sender comes back as a delivery.
            boolean keyed = webhookSystems.find(companyId, checked.definition().system().id())
                .map(system -> system.pointer(WebhookSystems.MappingField.SUBJECT_KEY) != null)
                .orElse(false);
            preview.warnings(new ArrayList<>(List.of(keyed
                    ? "When the agent writes to " + checked.definition().system().name() + " with its tools, the "
                            + "sender's next delivery about that subject is an echo: it fires one deeper, and a chain "
                            + "deeper than " + Firings.MAX_CHAIN + " starts nothing."
                    : checked.definition().system().name() + "'s mapping has no subject.key: a delivery caused by "
                            + "the agent's own write cannot be recognised as an echo, and only maxFiringsPerHour and "
                            + "the payer's budget bound such a loop. Map subject.key on the system.")));
        }
        if (!checked.openWrites().isEmpty() || checked.definition().source() == TriggerSource.TRACKER
                || checked.definition().source() == TriggerSource.CHANNEL
                || checked.definition().source() == TriggerSource.WEBHOOK) {
            String drivers = TriggerEditor.drivers(checked.definition());
            preview.openToAnyone(new TriggerOpenToAnyone(Character.toLowerCase(drivers.charAt(0))
                    + drivers.substring(1), new ArrayList<>(checked.openWrites().stream()
                        .map(write -> new TriggerOpenWrite(write.systemId(), write.tool())
                            .systemName(names.system(write.systemId()))
                            .holderName(names.principal(write.holderPrincipalId()))
                            .own(write.own()))
                        .toList()), checked.acknowledgementNeeded())
                .acknowledgementMissing(checked.unacknowledged() != null));
        }
        if (checked.unacknowledged() != null) {
            // The save would refuse it (OPEN_EVENTS_NOT_ACKNOWLEDGED): the editor shows it before the acknowledgement.
            preview.addWarningsItem(checked.unacknowledged() + ".");
        }
        if (existing != null) {
            preview.openConversationsKeepingAccounts(conversations.openOf(companyId, existing.id()).size());
        }
        return preview;
    }

    /** A request as the editor checks it; a change keeps what it does not name. */
    private TriggerEditor.Draft draft(TriggerCreateRequest request, @Nullable TriggerInfo existing) {
        return draft(request, existing, null);
    }

    /** {@link #draft(TriggerCreateRequest, TriggerInfo)}, with what the chat said of the author's channels (§5.7). */
    private TriggerEditor.Draft draft(TriggerCreateRequest request, @Nullable TriggerInfo existing,
            ChannelSources.@Nullable Joined joined) {
        List<TriggerEditor.RowInput> rows = new ArrayList<>();
        if (request.getAccounts() != null) {
            for (TriggerAccountInput row : request.getAccounts()) {
                rows.add(new TriggerEditor.RowInput(row.getSystemId(), row.getAccountId(),
                        row.getHolderPrincipalId()));
            }
        }
        return new TriggerEditor.Draft(request.getName(), TriggerSource.valueOf(request.getSource().getValue()),
                request.getSystemId(), TriggerViews.events(request.getEvents()),
                TriggerViews.subject(request.getSubject()), TriggerRules.blankToNull(request.getScope()),
                TriggerViews.conditions(request.getConditions()), TriggerViews.repeat(request.getRepeat()),
                TriggerViews.schedule(request.getSchedule()), request.getAgentId(),
                request.getAgentVersion() != null ? request.getAgentVersion() : 0, request.getInstruction(), rows,
                request.getPayerPrincipalId(), request.getSettings(), request.getTeamGroupId(),
                TriggerViews.results(request.getResults()), request.getMaxFiringsPerHour(),
                Boolean.TRUE.equals(request.getAcknowledgeOpenEvents()), channelInputs(request.getChannels()), joined,
                webhookConditions(request.getWebhookConditions()));
    }

    /** A webhook trigger's conditions as requested. */
    static List<WebhookCondition> webhookConditions(
            @Nullable List<com.flowtivo.tinfirm.shared.api.model.WebhookCondition> conditions) {
        if (conditions == null) {
            return List.of();
        }
        return conditions.stream()
            .map(condition -> {
                if (condition.getPointer() == null || condition.getOp() == null) {
                    throw ApiException.validation("A webhook condition names its pointer and op");
                }
                return new WebhookCondition(condition.getPointer(), WebhookCondition.Op.valueOf(
                        condition.getOp().getValue()), condition.getValues() != null ? condition.getValues()
                                : List.of());
            })
            .toList();
    }

    /** A webhook trigger's saved conditions, as the API shows them. */
    private List<com.flowtivo.tinfirm.shared.api.model.WebhookCondition> savedWebhookConditions(
            TriggerInfo trigger) {
        return new ArrayList<>(store.webhookConditions(trigger.companyId(), trigger.id()).stream()
            .map(condition -> new com.flowtivo.tinfirm.shared.api.model.WebhookCondition(condition.pointer(),
                    com.flowtivo.tinfirm.shared.api.model.WebhookCondition.OpEnum.fromValue(condition.op().name()))
                .values(new ArrayList<>(condition.values())))
            .toList());
    }

    private static List<TriggerEditor.ChannelInput> channelInputs(@Nullable List<TriggerChannelInput> channels) {
        if (channels == null) {
            return List.of();
        }
        return channels.stream()
            .map(channel -> new TriggerEditor.ChannelInput(channel.getChannelId(),
                    Boolean.TRUE.equals(channel.getDefault())))
            .toList();
    }

    /** A channel trigger's saved channels, as a change's request keeps them. */
    private List<TriggerChannelInput> savedChannels(TriggerInfo trigger) {
        return new ArrayList<>(store.channels(trigger.companyId(), trigger.id()).stream()
            .map(channel -> new TriggerChannelInput(channel.channelId())._default(channel.isDefault()))
            .toList());
    }

    // ---- the author's channels, asked outside any transaction (package 5, §5.7) ----------------------------------

    /**
     * Which channels of a channel trigger its author is a member of, as the chat platform says now: asked <b>outside
     * any transaction</b> (CONVENTIONS §10) before the save that checks it (§5.7, {@code CHANNEL_NOT_JOINED}). Null for
     * another source, or when no chat module serves this server.
     *
     * @param triggerId       a change's or a reassignment's trigger; null for a create or a new draft's preview
     * @param newAuthor       a reassignment's new author; null: the trigger's, or the caller for a create
     * @param channels        the channels the save names; null: the trigger's saved ones
     * @param source          a create's source; null for a change
     * @param systemId        a create's system; null for a change
     */
    ChannelSources.@Nullable Joined joined(UUID companyId, @Nullable UUID triggerId, @Nullable UUID newAuthor,
            @Nullable List<TriggerChannelInput> channels, @Nullable String source, @Nullable UUID systemId) {
        ChannelSources sources = channelSources.getIfAvailable();
        if (sources == null || triggerId == null && !TriggerSource.CHANNEL.name().equals(source)) {
            return null;
        }
        record Probe(UUID systemId, UUID authorPrincipalId, Set<String> channels) {
        }
        Probe probe = readOnly.execute(status -> {
            TriggerCallers.Caller caller = caller(companyId);
            UUID system = systemId;
            UUID author = newAuthor;
            List<TriggerChannelInput> named = channels;
            if (triggerId != null) {
                TriggerInfo trigger = store.find(companyId, triggerId).filter(TriggerInfo::live).orElse(null);
                if (trigger == null || trigger.source() != TriggerSource.CHANNEL || trigger.systemId() == null) {
                    return null;
                }
                system = trigger.systemId();
                author = author != null ? author : trigger.authorPrincipalId();
                named = named != null ? named : savedChannels(trigger);
            }
            else {
                author = caller.principalId();
            }
            if (system == null || author == null || named == null || named.isEmpty()) {
                return null;
            }
            Set<String> ids = new LinkedHashSet<>();
            named.forEach(channel -> ids.add(channel.getChannelId()));
            return new Probe(system, author, ids);
        });
        if (probe == null) {
            return null;
        }
        return sources.joined(companyId, probe.systemId(), probe.authorPrincipalId(), probe.channels());
    }

    /** The trigger a checked draft describes, for its sentence and its row. */
    private TriggerInfo shape(UUID companyId, @Nullable TriggerInfo existing, TriggerEditor.Checked checked,
            UUID authorPrincipalId, @Nullable TriggerCreateRequest request) {
        Instant now = time.now();
        TriggerEditor.Definition definition = checked.definition();
        TriggerSchedule schedule = null;
        if (definition.schedule() != null && definition.scheduleInput() != null) {
            String cron = Objects.requireNonNull(definition.scheduleInput().cron()).strip();
            String zone = Objects.requireNonNull(definition.scheduleInput().zone()).strip();
            TriggerSchedule was = existing != null ? existing.schedule() : null;
            // The next firing stays unless the cron or the zone changed: a firing due but not yet claimed
            // by the tick is not lost to a rename. A paused schedule has none; its resume computes it.
            @Nullable Instant next;
            if (was != null && was.cron().equals(cron) && was.zone().equals(zone)) {
                next = was.nextRunAt();
            }
            else if (existing != null && existing.status() == TriggerStatus.PAUSED) {
                next = null;
            }
            else {
                next = definition.schedule().next(now).orElse(null);
            }
            schedule = new TriggerSchedule(cron, zone, definition.scheduleInput().overlap(),
                    definition.scheduleInput().catchUp(), next, was != null ? was.lastRunAt() : null,
                    was != null ? was.lastOutcome() : null);
        }
        int limit = request != null && request.getMaxFiringsPerHour() != null ? request.getMaxFiringsPerHour()
                : existing != null ? existing.maxFiringsPerHour() : limits(companyId)[0];
        return new TriggerInfo(existing != null ? existing.id() : Ids.newId(), companyId, definition.name(),
                definition.source(), definition.system() != null ? definition.system().id() : null,
                definition.events(), definition.subject(), definition.scope(), definition.conditions(),
                definition.repeat(), schedule, checked.agent().id(), definition.instruction(), authorPrincipalId,
                checked.teamGroupId(), checked.payer().id(), definition.results(), limit,
                existing != null ? existing.status() : TriggerStatus.ACTIVE,
                existing != null ? existing.pausedReason() : null, existing != null ? existing.pausedAt() : null,
                existing != null ? existing.waitingReason() : null, existing != null ? existing.waitingSince() : null,
                existing != null ? existing.armedAt() : now, existing != null ? existing.missedCount() : 0,
                existing != null ? existing.rateSkippedCount() : 0,
                checked.acknowledgementNeeded() ? now : existing != null ? existing.openEventsAcknowledgedAt() : null,
                null, null, existing != null ? existing.createdAt() : now, now);
    }

    private AgentInfo usableAgent(UUID companyId, UUID agentId, UUID userId) {
        AgentInfo agent = agents.find(companyId, agentId).filter(AgentInfo::live)
            .orElseThrow(() -> ApiException.notFound("Agent not found"));
        var access = agents.access(companyId, agent.id(), userId);
        if (!access.sees()) {
            throw ApiException.notFound("Agent not found");
        }
        if (!access.uses()) {
            throw ApiException.forbidden(ProblemCode.AGENT_NOT_USABLE, "You may not use the agent " + agent.name());
        }
        return agent;
    }

    // ---- create, change, delete ------------------------------------------------------------------------------------

    @Transactional
    public Trigger create(UUID companyId, TriggerCreateRequest request) {
        return create(companyId, request, null);
    }

    /** A create, with what the chat said of the author's channels ({@link #joined}). */
    @Transactional
    public Trigger create(UUID companyId, TriggerCreateRequest request, ChannelSources.@Nullable Joined joined) {
        TriggerCallers.Caller caller = caller(companyId);
        UUID principalId = caller.requirePrincipal();
        usableAgent(companyId, request.getAgentId(), caller.userId());
        if (request.getAgentVersion() == null) {
            throw ApiException.validation("agentVersion is the agent's version the editor showed");
        }
        TriggerEditor.Author author = new TriggerEditor.Author(principalId, caller.userId());
        Instant now = time.now();
        TriggerEditor.Checked checked = editor.check(companyId, author, draft(request, null, joined), null, now);
        TriggerEditor.Definition definition = checked.definition();
        if (store.nameTaken(companyId, definition.name(), null)) {
            throw duplicateName(definition.name());
        }
        // The caps (§3.1): a source system's live triggers, whatever their source, and the company's schedules,
        // each counted under its advisory lock, so that parallel creates at a cap let one through (§10).
        if (definition.source() == TriggerSource.SCHEDULE) {
            store.lockScheduleCap(companyId);
            if (store.countLiveSchedules(companyId) >= MAX_SCHEDULES) {
                throw ApiException.conflict(ProblemCode.TOO_MANY_TRIGGERS, "A company has at most " + MAX_SCHEDULES
                        + " schedules");
            }
        }
        else {
            UUID systemId = Objects.requireNonNull(definition.system()).id();
            store.lockSystemCap(systemId);
            if (store.countLiveOnSystem(companyId, systemId) >= MAX_PER_SYSTEM) {
                throw ApiException.conflict(ProblemCode.TOO_MANY_TRIGGERS, "A system has at most " + MAX_PER_SYSTEM
                        + " triggers");
            }
        }
        int[] limits = limits(companyId);
        if (request.getMaxFiringsPerHour() != null && request.getMaxFiringsPerHour() > limits[1]) {
            throw tooHigh(limits[1]);
        }
        TriggerInfo trigger = shape(companyId, null, checked, principalId, request);
        if (!checked.acknowledgementNeeded() || !Boolean.TRUE.equals(request.getAcknowledgeOpenEvents())) {
            trigger = withAcknowledged(trigger, null);
        }
        try {
            store.insert(trigger, caller.userId(),
                    trigger.openEventsAcknowledgedAt() != null ? caller.userId() : null);
        }
        catch (DataIntegrityViolationException ex) {
            throw nameTakenMeanwhile(ex, trigger.name());
        }
        List<TriggerAccount> rows = checked.rows().stream()
            .map(row -> row.stored(TriggerAccount.FilledFrom.ROW, now))
            .toList();
        store.replaceAccounts(companyId, trigger.id(), rows, caller.userId());
        exposures.record(trigger);
        if (definition.source() == TriggerSource.CHANNEL) {
            replaceChannels(companyId, trigger.id(), Objects.requireNonNull(trigger.systemId()), trigger.agentId(),
                    definition.channels(), caller.userId(), now);
        }
        if (definition.source() == TriggerSource.WEBHOOK) {
            store.saveWebhookConditions(companyId, trigger.id(), definition.webhookConditions());
        }
        Map<String, Object> settings = triggerSettings.create(companyId, trigger.agentId(),
                Objects.requireNonNull(request.getAgentVersion()), owner(trigger, checked), request.getSettings());
        auditLog.record(AuditRecord.company(companyId, AuditAction.TRIGGER_CREATED, CurrentActor.actor())
            .target(TriggerStates.TARGET, trigger.id())
            .details(TriggerViews.auditDetails(trigger))
            .build());
        auditAccounts(trigger, rows, checked.modelAccess(), "created");
        auditLog.record(AuditRecord.company(companyId, AuditAction.TRIGGER_SETTINGS_CHANGED, CurrentActor.actor())
            .target(TriggerStates.TARGET, trigger.id())
            .details(settings)
            .build());
        if (trigger.openEventsAcknowledgedAt() != null) {
            auditAcknowledged(trigger, checked);
        }
        events.publishEvent(new TriggerEvents.TriggerCreated(companyId, trigger.id(), trigger.source(),
                trigger.systemId()));
        // A row that needs signing in again pauses it at once (ACCOUNT_BROKEN, §11).
        gate.check(companyId, trigger.id());
        return view(caller, store.find(companyId, trigger.id()).orElseThrow());
    }

    private TriggerInfo withAcknowledged(TriggerInfo trigger, @Nullable Instant at) {
        return new TriggerInfo(trigger.id(), trigger.companyId(), trigger.name(), trigger.source(), trigger.systemId(),
                trigger.events(), trigger.subject(), trigger.scope(), trigger.conditions(), trigger.repeat(),
                trigger.schedule(), trigger.agentId(), trigger.instruction(), trigger.authorPrincipalId(),
                trigger.teamGroupId(), trigger.payerPrincipalId(), trigger.results(), trigger.maxFiringsPerHour(),
                trigger.status(), trigger.pausedReason(), trigger.pausedAt(), trigger.waitingReason(),
                trigger.waitingSince(), trigger.armedAt(), trigger.missedCount(), trigger.rateSkippedCount(), at,
                trigger.deletedAt(), trigger.deletedCause(), trigger.createdAt(), trigger.updatedAt());
    }

    private TriggerSettings.Owner owner(TriggerInfo trigger, TriggerEditor.Checked checked) {
        boolean own = checked.rows().stream().allMatch(TriggerEditor.Row::own);
        // A subscription pays only when the company allows it and the payer is the author (ADR-0085, decision 7).
        boolean subscription = store.subscriptionsOn(trigger.companyId())
                && checked.payer().id().equals(trigger.authorPrincipalId());
        return new TriggerSettings.Owner(trigger.id(), trigger.authorPrincipalId(), checked.payer().id(), own,
                subscription);
    }

    /** The trigger's whole table as it is now, audited as {@code trigger.accounts_changed} for the reason. */
    void auditAccounts(TriggerInfo trigger, String why) {
        auditAccounts(trigger, store.accounts(trigger.companyId(), trigger.id()), modelAccess(trigger), why);
    }

    private void auditAccounts(TriggerInfo trigger, List<TriggerAccount> rows, ModelAccessChoice access,
            String why) {
        auditLog.record(AuditRecord.company(trigger.companyId(), AuditAction.TRIGGER_ACCOUNTS_CHANGED,
                CurrentActor.actor())
            .target(TriggerStates.TARGET, trigger.id())
            .detail("why", why)
            .detail("accounts", rows.stream().map(row -> {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("systemId", row.systemId().toString());
                entry.put("accountId", row.accountId() != null ? row.accountId().toString() : null);
                entry.put("holderPrincipalId", row.holderPrincipalId().toString());
                entry.put("filledFrom", row.filledFrom().name());
                return entry;
            }).toList())
            .detail("payerPrincipalId", trigger.payerPrincipalId() != null ? trigger.payerPrincipalId().toString()
                    : null)
            .detail("modelAccess", access instanceof ModelAccessChoice.Subscription ? "SUBSCRIPTION" : "BUDGET")
            .build());
    }

    private void auditAcknowledged(TriggerInfo trigger, TriggerEditor.Checked checked) {
        auditLog.record(AuditRecord.company(trigger.companyId(), AuditAction.TRIGGER_OPEN_EVENTS_ACKNOWLEDGED,
                CurrentActor.actor())
            .target(TriggerStates.TARGET, trigger.id())
            .detail("events", trigger.events().stream().map(spec -> spec.event().name()).toList())
            .detail("writes", checked.openWrites().stream()
                .map(write -> write.systemId() + "/" + write.tool()).toList())
            .detail("accounts", checked.rows().stream().map(row -> row.account().id().toString()).toList())
            .build());
    }

    private static ApiException duplicateName(String name) {
        return ApiException.conflict(ProblemCode.DUPLICATE_TRIGGER_NAME, "A trigger of the company is called " + name
                + " already");
    }

    /**
     * A save refused by the names' unique index: another save took the name after {@code nameTaken} read the names
     * without locks, and it is answered as that check would have been ({@code 409 DUPLICATE_TRIGGER_NAME}), not as a
     * failure. Any other refusal stays what it is.
     */
    private static RuntimeException nameTakenMeanwhile(DataIntegrityViolationException ex, String name) {
        return Constraints.violated(ex, NAME_INDEX) ? duplicateName(name) : ex;
    }

    private static ApiException tooHigh(int max) {
        return ApiException.badRequest(ProblemCode.TRIGGER_LIMIT_TOO_HIGH, "A trigger fires at most " + max
                + " times an hour in this company");
    }

    @Transactional
    public Trigger update(UUID companyId, UUID triggerId, TriggerUpdateRequest request) {
        return update(companyId, triggerId, request, null);
    }

    /** A change, with what the chat said of the author's channels ({@link #joined}). */
    @Transactional
    public Trigger update(UUID companyId, UUID triggerId, TriggerUpdateRequest request,
            ChannelSources.@Nullable Joined joined) {
        Seen seen = editable(companyId, triggerId);
        TriggerInfo existing = seen.trigger();
        TriggerEditor.Author author = author(existing);
        TriggerCreateRequest merged = merged(existing, request);
        Instant now = time.now();
        TriggerEditor.Checked checked = editor.check(companyId, author, draft(merged, existing, joined), existing,
                now, false, onBehalf(seen));
        TriggerEditor.Definition definition = checked.definition();
        if (store.nameTaken(companyId, definition.name(), existing.id())) {
            throw duplicateName(definition.name());
        }
        if (request.getMaxFiringsPerHour() != null && request.getMaxFiringsPerHour() > limits(companyId)[1]) {
            throw tooHigh(limits(companyId)[1]);
        }
        TriggerInfo changed = shape(companyId, existing, checked, existing.authorPrincipalId(), merged);
        boolean acknowledged = checked.acknowledgementNeeded() && Boolean.TRUE.equals(request.getAcknowledgeOpenEvents());
        changed = withAcknowledged(changed, acknowledged ? now
                : checked.acknowledgementNeeded() ? existing.openEventsAcknowledgedAt() : null);
        Set<String> fields = changedFields(existing, changed);
        if (existing.source() == TriggerSource.CHANNEL && request.getChannels() != null) {
            List<TriggerStore.ChannelRow> before = store.channels(companyId, existing.id());
            Map<String, Boolean> was = new HashMap<>();
            before.forEach(channel -> was.put(channel.channelId(), channel.isDefault()));
            Map<String, Boolean> now2 = new HashMap<>();
            checked.definition().channels().forEach(channel -> now2.put(channel.channelId(), channel.isDefault()));
            if (!was.equals(now2)) {
                fields.add("channels");
            }
        }
        if (existing.source() == TriggerSource.WEBHOOK
                && !store.webhookConditions(companyId, existing.id()).equals(definition.webhookConditions())) {
            fields.add("webhookConditions");
        }
        try {
            store.updateDefinition(changed, acknowledged ? seen.caller().userId() : null);
        }
        catch (DataIntegrityViolationException ex) {
            throw nameTakenMeanwhile(ex, changed.name());
        }
        if (existing.source() == TriggerSource.WEBHOOK) {
            store.saveWebhookConditions(companyId, existing.id(), definition.webhookConditions());
        }
        if (existing.source() == TriggerSource.CHANNEL && request.getChannels() != null) {
            replaceChannels(companyId, existing.id(), Objects.requireNonNull(existing.systemId()), existing.agentId(),
                    checked.definition().channels(), seen.caller().userId(), now);
        }
        if (!Objects.equals(existing.teamGroupId(), changed.teamGroupId())) {
            conversations.approversChanged(companyId, existing.id(), changed.teamGroupId(),
                    existing.authorPrincipalId());
        }
        if (!fields.isEmpty()) {
            auditLog.record(AuditRecord.company(companyId, AuditAction.TRIGGER_CHANGED, CurrentActor.actor())
                .target(TriggerStates.TARGET, existing.id())
                .details(TriggerViews.auditDetails(changed))
                .detail("changed", List.copyOf(fields))
                .detail("editorPrincipalId", seen.caller().principalId() != null
                        ? seen.caller().principalId().toString() : null)
                .build());
            changedBy(seen.caller(), existing, String.join(", ", fields));
            events.publishEvent(new TriggerEvents.TriggerChanged(companyId, existing.id(), fields));
        }
        if (acknowledged) {
            auditAcknowledged(changed, checked);
        }
        return view(seen.caller(), store.find(companyId, existing.id()).orElseThrow());
    }

    /** A change's request over the trigger as it is: what it does not name stays. */
    private TriggerCreateRequest merged(TriggerInfo existing, TriggerUpdateRequest request) {
        List<TriggerAccountInput> rows = store.accounts(existing.companyId(), existing.id()).stream()
            .filter(row -> row.accountId() != null)
            .map(row -> new TriggerAccountInput(row.systemId(), row.accountId())
                .holderPrincipalId(row.holderPrincipalId()))
            .toList();
        TriggerCreateRequest merged = new TriggerCreateRequest(
                request.getName() != null ? request.getName() : existing.name(),
                com.flowtivo.tinfirm.shared.api.model.TriggerSource.fromValue(existing.source().name()),
                existing.agentId(), triggerSettings.acceptedVersion(existing.companyId(), existing.id()),
                request.getInstruction() != null ? request.getInstruction() : existing.instruction(),
                new ArrayList<>(rows), Objects.requireNonNull(existing.payerPrincipalId()));
        merged.systemId(existing.systemId())
            .events(request.getEvents() != null ? request.getEvents() : TriggerViews.events(existing))
            .subject(request.getSubject() != null ? request.getSubject()
                    : com.flowtivo.tinfirm.shared.api.model.TriggerSubject.fromValue(existing.subject().name()))
            .scope(request.getScope() != null ? request.getScope() : existing.scope())
            .conditions(request.getConditions() != null ? request.getConditions() : TriggerViews.conditions(existing))
            .repeat(request.getRepeat() != null ? request.getRepeat()
                    : com.flowtivo.tinfirm.shared.api.model.TriggerRepeat.fromValue(existing.repeat().name()))
            .teamGroupId(Boolean.TRUE.equals(request.getClearTeam()) ? null
                    : request.getTeamGroupId() != null ? request.getTeamGroupId() : existing.teamGroupId())
            .results(request.getResults() != null ? request.getResults() : TriggerViews.results(existing.results()))
            .maxFiringsPerHour(request.getMaxFiringsPerHour() != null ? request.getMaxFiringsPerHour()
                    : existing.maxFiringsPerHour())
            .acknowledgeOpenEvents(request.getAcknowledgeOpenEvents());
        if (existing.source() == TriggerSource.CHANNEL) {
            merged.channels(request.getChannels() != null ? new ArrayList<>(request.getChannels())
                    : savedChannels(existing));
        }
        if (existing.source() == TriggerSource.WEBHOOK) {
            merged.webhookConditions(request.getWebhookConditions() != null
                    ? new ArrayList<>(request.getWebhookConditions()) : savedWebhookConditions(existing));
        }
        if (existing.source() == TriggerSource.SCHEDULE) {
            TriggerSchedule schedule = Objects.requireNonNull(existing.schedule());
            merged.schedule(request.getSchedule() != null ? request.getSchedule()
                    : new com.flowtivo.tinfirm.shared.api.model.TriggerScheduleInput(schedule.cron(), schedule.zone())
                        .overlap(com.flowtivo.tinfirm.shared.api.model.TriggerScheduleInput.OverlapEnum.fromValue(
                                schedule.overlap().name()))
                        .catchUp(com.flowtivo.tinfirm.shared.api.model.TriggerScheduleInput.CatchUpEnum.fromValue(
                                schedule.catchUp().name())));
        }
        return merged;
    }

    private static Set<String> changedFields(TriggerInfo before, TriggerInfo after) {
        Set<String> fields = new LinkedHashSet<>();
        if (!before.name().equals(after.name())) {
            fields.add("name");
        }
        if (!before.instruction().equals(after.instruction())) {
            fields.add("instruction");
        }
        if (!before.events().equals(after.events())) {
            fields.add("events");
        }
        if (before.subject() != after.subject()) {
            fields.add("subject");
        }
        if (!Objects.equals(before.scope(), after.scope())) {
            fields.add("scope");
        }
        if (!before.conditions().equals(after.conditions())) {
            fields.add("conditions");
        }
        if (before.repeat() != after.repeat()) {
            fields.add("repeat");
        }
        if (before.schedule() != null && after.schedule() != null
                && (!before.schedule().cron().equals(after.schedule().cron())
                        || !before.schedule().zone().equals(after.schedule().zone())
                        || before.schedule().overlap() != after.schedule().overlap()
                        || before.schedule().catchUp() != after.schedule().catchUp())) {
            fields.add("schedule");
        }
        if (!Objects.equals(before.teamGroupId(), after.teamGroupId())) {
            fields.add("team");
        }
        if (!before.results().equals(after.results())) {
            fields.add("results");
        }
        if (before.maxFiringsPerHour() != after.maxFiringsPerHour()) {
            fields.add("maxFiringsPerHour");
        }
        return fields;
    }

    @Transactional
    public Trigger replaceAccounts(UUID companyId, UUID triggerId, TriggerAccountsRequest request) {
        Seen seen = editable(companyId, triggerId);
        TriggerInfo existing = seen.trigger();
        TriggerEditor.Author author = author(existing);
        TriggerCreateRequest merged = merged(existing, new TriggerUpdateRequest()
            .acknowledgeOpenEvents(request.getAcknowledgeOpenEvents()));
        merged.accounts(new ArrayList<>(request.getAccounts() != null ? request.getAccounts() : List.of()))
            .payerPrincipalId(request.getPayerPrincipalId());
        Instant now = time.now();
        TriggerEditor.Checked checked = editor.check(companyId, author, draft(merged, existing), existing, now, false,
                onBehalf(seen));
        List<TriggerAccount> rows = checked.rows().stream()
            .map(row -> row.stored(TriggerAccount.FilledFrom.ROW, now))
            .toList();
        store.replaceAccounts(companyId, existing.id(), rows, seen.caller().userId());
        store.setPayer(companyId, existing.id(), checked.payer().id(), now);
        exposures.record(existing);
        if (checked.acknowledgementNeeded() && Boolean.TRUE.equals(request.getAcknowledgeOpenEvents())) {
            store.updateDefinition(withAcknowledged(shape(companyId, existing, checked, existing.authorPrincipalId(),
                    merged), now), seen.caller().userId());
            auditAcknowledged(existing, checked);
        }
        TriggerInfo after = store.find(companyId, existing.id()).orElseThrow();
        auditAccounts(after, rows, checked.modelAccess(), "changed");
        changedBy(seen.caller(), existing, "accounts and payer");
        events.publishEvent(new TriggerEvents.TriggerAccountsChanged(companyId, existing.id()));
        if (after.active()) {
            gate.check(companyId, existing.id());
        }
        return view(seen.caller(), store.find(companyId, existing.id()).orElseThrow());
    }

    @Transactional
    public void delete(UUID companyId, UUID triggerId) {
        Seen seen = seen(companyId, triggerId, true);
        if (!seen.rights().deletes()) {
            throw ApiException.forbidden(ProblemCode.TRIGGER_NOT_EDITABLE, "Only the trigger's author and admins "
                    + "delete it");
        }
        states.delete(seen.trigger(), "USER", seen.caller().userId());
    }

    // ---- settings --------------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public AgentSettings settings(UUID companyId, UUID triggerId) {
        Seen seen = seen(companyId, triggerId, false);
        return triggerSettings.view(companyId, seen.trigger().agentId(), triggerId);
    }

    @Transactional
    public AgentSettings saveSettings(UUID companyId, UUID triggerId, AgentSettingsRequest input) {
        Seen seen = editable(companyId, triggerId);
        TriggerInfo existing = seen.trigger();
        TriggerEditor.Author author = author(existing);
        TriggerCreateRequest merged = merged(existing, new TriggerUpdateRequest());
        merged.settings(input);
        TriggerEditor.Checked checked = editor.check(companyId, author, draft(merged, existing), existing,
                time.now(), false, onBehalf(seen));
        Map<String, Object> changed = triggerSettings.save(companyId, existing.agentId(), owner(existing, checked),
                input);
        if (!changed.isEmpty()) {
            auditLog.record(AuditRecord.company(companyId, AuditAction.TRIGGER_SETTINGS_CHANGED, CurrentActor.actor())
                .target(TriggerStates.TARGET, existing.id())
                .details(changed)
                .detail("editorPrincipalId", seen.caller().principalId() != null
                        ? seen.caller().principalId().toString() : null)
                .build());
            changedBy(seen.caller(), existing, "settings");
            events.publishEvent(new TriggerEvents.TriggerChanged(companyId, existing.id(), Set.of("settings")));
        }
        return triggerSettings.view(companyId, existing.agentId(), existing.id());
    }

    /**
     * The author accepts (H2, the owner's decision Q2): the agent's widening up to {@code version}, and for a trigger
     * reassigned to them (paused {@code TO_ACCEPT}) its accounts, its payer and, where its open events would write
     * without approval, their acknowledgement ({@code acknowledgeOpenEvents}), which resumes it through the gate. Only
     * its author accepts: an admin never does on their behalf ({@code 403 TRIGGER_NOT_EDITABLE}).
     */
    @Transactional
    public Trigger accept(UUID companyId, UUID triggerId, int version, boolean acknowledgeOpenEvents) {
        Seen seen = editable(companyId, triggerId);
        TriggerInfo existing = seen.trigger();
        if (!seen.caller().author(existing)) {
            throw ApiException.forbidden(ProblemCode.TRIGGER_NOT_EDITABLE, "Only the trigger's author accepts what "
                    + "it acts with: a new version of its agent, or the trigger reassigned to them");
        }
        TriggerEditor.Author author = author(existing);
        int previous = triggerSettings.accept(companyId, existing.agentId(), existing.id(), version);
        if (version > previous) {
            auditLog.record(AuditRecord.company(companyId, AuditAction.TRIGGER_ACCEPTED, CurrentActor.actor())
                .target(TriggerStates.TARGET, existing.id())
                .detail("version", version)
                .detail("previousVersion", previous)
                .detail("editorPrincipalId", seen.caller().principalId() != null
                        ? seen.caller().principalId().toString() : null)
                .build());
            changedBy(seen.caller(), existing, "accepted version " + version);
            events.publishEvent(new TriggerEvents.TriggerChanged(companyId, existing.id(), Set.of("acceptedVersion")));
        }
        if (existing.paused() && existing.pausedReason() == PausedReason.TO_ACCEPT) {
            acceptReassignment(seen, author, acknowledgeOpenEvents);
        }
        return view(seen.caller(), store.find(companyId, existing.id()).orElseThrow());
    }

    /**
     * The new author accepts a reassigned trigger (H2): its table and payer are checked for them as their own save
     * would check them, the open events' acknowledgement included ({@code 400 OPEN_EVENTS_NOT_ACKNOWLEDGED} without
     * it), then it resumes through the gate ({@code 409 TRIGGER_PAUSED} when the gate refuses, with nothing accepted).
     * Audited as {@code trigger.accepted} with {@code reassignment}, the accounts and the payer.
     */
    private void acceptReassignment(Seen seen, TriggerEditor.Author author, boolean acknowledge) {
        TriggerInfo existing = seen.trigger();
        UUID companyId = existing.companyId();
        TriggerCreateRequest merged = merged(existing, new TriggerUpdateRequest().acknowledgeOpenEvents(acknowledge));
        Instant now = time.now();
        TriggerEditor.Checked checked = editor.check(companyId, author, draft(merged, existing), existing, now);
        if (checked.acknowledgementNeeded() && acknowledge) {
            store.updateDefinition(withAcknowledged(shape(companyId, existing, checked, existing.authorPrincipalId(),
                    merged), now), seen.caller().userId());
            auditAcknowledged(existing, checked);
        }
        store.authorChanged(companyId, existing.id());
        auditLog.record(AuditRecord.company(companyId, AuditAction.TRIGGER_ACCEPTED, CurrentActor.actor())
            .target(TriggerStates.TARGET, existing.id())
            .detail("reassignment", true)
            .detail("accounts", checked.rows().stream().map(row -> row.account().id().toString()).toList())
            .detail("payerPrincipalId", checked.payer().id().toString())
            .detail("openEventsAcknowledged", checked.acknowledgementNeeded())
            .build());
        resumeThroughGate(seen.caller(), store.find(companyId, existing.id()).orElseThrow());
    }

    // ---- pause, resume, run, reassign ------------------------------------------------------------------------------

    @Transactional
    public Trigger pause(UUID companyId, UUID triggerId, boolean stopRunning) {
        Seen seen = seen(companyId, triggerId, true);
        if (!seen.rights().pauses()) {
            throw notFound();
        }
        states.pause(seen.trigger(), seen.trigger().paused() && seen.trigger().pausedReason() != null
                ? seen.trigger().pausedReason() : PausedReason.USER, seen.caller().userId(), stopRunning);
        return view(seen.caller(), store.find(companyId, triggerId).orElseThrow());
    }

    @Transactional
    public Trigger resume(UUID companyId, UUID triggerId) {
        Seen seen = seen(companyId, triggerId, true);
        if (!seen.rights().resumes()) {
            throw ApiException.forbidden(ProblemCode.TRIGGER_NOT_EDITABLE, "Only the trigger's author and admins "
                    + "resume it");
        }
        TriggerInfo trigger = seen.trigger();
        if (!trigger.paused()) {
            return view(seen.caller(), trigger);
        }
        if (trigger.pausedReason() == PausedReason.TO_ACCEPT) {
            // H2: a reassigned trigger resumes only when its new author accepts it.
            throw ApiException.conflict(ProblemCode.TRIGGER_PAUSED, "The trigger was reassigned: it resumes when its "
                    + "new author accepts its accounts, its payer and its open events (accept)",
                    Map.of("details", Map.of("reason", PausedReason.TO_ACCEPT.name())));
        }
        resumeThroughGate(seen.caller(), trigger);
        return view(seen.caller(), store.find(companyId, triggerId).orElseThrow());
    }

    /** Resumes the locked, paused trigger when the gate passes (or only waits); {@code 409 TRIGGER_PAUSED} otherwise. */
    private void resumeThroughGate(TriggerCallers.Caller caller, TriggerInfo trigger) {
        UUID companyId = trigger.companyId();
        UUID triggerId = trigger.id();
        TriggerGateImpl.Verdict verdict = gate.verdict(trigger);
        switch (verdict) {
            case TriggerGateImpl.Verdict.Pause pause -> throw ApiException.conflict(ProblemCode.TRIGGER_PAUSED,
                    "The trigger cannot be resumed: " + pause.detail(),
                    Map.of("details", Map.of("reason", pause.reason().name())));
            case TriggerGateImpl.Verdict.Delete delete -> throw ApiException.conflict(ProblemCode.TRIGGER_PAUSED,
                    "The trigger cannot be resumed: its " + ("agent.deleted".equals(delete.cause()) ? "agent"
                            : "system") + " is gone", Map.of("details", Map.of("reason", delete.cause())));
            default -> {
            }
        }
        Instant next = null;
        if (trigger.source() == TriggerSource.SCHEDULE && trigger.schedule() != null) {
            next = Schedule.parse(trigger.schedule().cron(), trigger.schedule().zone()).next(time.now()).orElse(null);
        }
        states.resume(trigger, next, caller.principalId());
        if (verdict instanceof TriggerGateImpl.Verdict.Wait wait) {
            states.waiting(store.find(companyId, triggerId).orElseThrow(), wait.reason());
        }
    }

    /**
     * Run now (§5.6): who may run it is checked in a short transaction of its own, then part b's
     * {@link ManualFirings} reads the subject with the source account and fires, outside any transaction (CONVENTIONS
     * §10), auditing {@code trigger.run} and {@code trigger.fired}.
     */
    public TriggerFiring run(UUID companyId, UUID triggerId, @Nullable String subjectKey) {
        Seen seen = Objects.requireNonNull(readOnly.execute(status -> seen(companyId, triggerId, false)));
        if (!seen.rights().runs()) {
            throw notFound();
        }
        TriggerInfo trigger = seen.trigger();
        if (trigger.paused()) {
            throw ApiException.conflict(ProblemCode.TRIGGER_PAUSED, "The trigger is paused: resume it first",
                    Map.of("details", Map.of("reason", String.valueOf(trigger.pausedReason()))));
        }
        ManualFirings manual = manualFirings.getIfAvailable();
        if (manual == null) {
            throw ApiException.conflict(ProblemCode.SUBJECT_NOT_VISIBLE, "Tinfirm does not run this trigger now on "
                    + "this server");
        }
        return TriggerViews.firing(manual.run(companyId, triggerId, subjectKey, seen.caller().userId()));
    }

    @Transactional
    public Trigger reassign(UUID companyId, UUID triggerId, TriggerReassignRequest request) {
        return reassign(companyId, triggerId, request, null);
    }

    /**
     * A reassignment, with what the chat said of the new author's channels ({@link #joined}, §5.7). Only after the
     * author's loss (H2, the owner's decision Q2): a trigger paused {@code AUTHOR_LEFT} or {@code NOT_ENTITLED}, or one
     * reassigned and not accepted yet ({@code TO_ACCEPT}); a live trigger is {@code 409 TRIGGER_PAUSED} with the reason
     * it would need. The new author holds a seat (a VIEWER is {@code 400 VALIDATION_FAILED}, M7). The admin names no
     * row of the new author's own accounts nor their own MCP servers ({@code 403 AUTHOR_ACCOUNT_NOT_ADMINS}); the
     * trigger is paused {@code TO_ACCEPT}, its open events' acknowledgement cleared, until the new author accepts it.
     */
    @Transactional
    public Trigger reassign(UUID companyId, UUID triggerId, TriggerReassignRequest request,
            ChannelSources.@Nullable Joined joined) {
        Seen seen = seen(companyId, triggerId, true);
        if (!seen.rights().reassigns()) {
            throw ApiException.forbidden("Only admins reassign a trigger");
        }
        TriggerInfo existing = seen.trigger();
        if (!reassignable(existing)) {
            throw ApiException.conflict(ProblemCode.TRIGGER_PAUSED, "Only a trigger whose author left or lost a right "
                    + "it depends on is reassigned: this one is " + (existing.paused() ? "paused " + existing
                        .pausedReason() : "active"), Map.of("details", Map.of("reason", existing.paused()
                            ? String.valueOf(existing.pausedReason()) : existing.status().name())));
        }
        PrincipalInfo newAuthor = seatHolder(companyId, request.getAuthorPrincipalId(), "authorPrincipalId");
        TriggerEditor.Author author = new TriggerEditor.Author(newAuthor.id(),
                Objects.requireNonNull(newAuthor.userId()));
        TriggerCreateRequest merged = merged(existing, new TriggerUpdateRequest());
        if (request.getAccounts() != null) {
            merged.accounts(new ArrayList<>(request.getAccounts()));
        }
        else {
            // The rows that pass for the new author stay: the credentials of service accounts they may use. Never an
            // account of their own: they add those themselves (H2).
            merged.accounts(new ArrayList<>(merged.getAccounts().stream()
                .filter(row -> accounts.find(companyId, row.getAccountId())
                    .map(account -> account.credential() && row.getHolderPrincipalId() != null
                            && principals.mayUse(companyId, author.userId(), row.getHolderPrincipalId()))
                    .orElse(false))
                .toList()));
        }
        UUID payer = request.getPayerPrincipalId() != null ? request.getPayerPrincipalId()
                : existing.payerPrincipalId() != null && !existing.payerPrincipalId().equals(
                        existing.authorPrincipalId()) ? existing.payerPrincipalId() : newAuthor.id();
        merged.payerPrincipalId(payer);
        Instant now = time.now();
        // Checked for the new author as an admin's save on their behalf (H2); their acknowledgement of the open events
        // is theirs to give when they accept it, so its absence is not refused here.
        TriggerEditor.Checked checked = editor.check(companyId, author, draft(merged, existing, joined), existing,
                now, true, true);
        List<TriggerAccount> rows = checked.rows().stream()
            .map(row -> row.stored(TriggerAccount.FilledFrom.ROW, now))
            .toList();
        states.pause(existing, PausedReason.TO_ACCEPT, seen.caller().userId(), false);
        store.replaceAccounts(companyId, existing.id(), rows, seen.caller().userId());
        store.reassign(companyId, existing.id(), newAuthor.id(), checked.payer().id(), now);
        store.clearAcknowledgement(companyId, existing.id(), now);
        store.arm(companyId, existing.id(), now);
        if (existing.teamGroupId() == null) {
            conversations.approversChanged(companyId, existing.id(), null, newAuthor.id());
        }
        TriggerInfo after = store.find(companyId, existing.id()).orElseThrow();
        auditLog.record(AuditRecord.company(companyId, AuditAction.TRIGGER_REASSIGNED, CurrentActor.actor())
            .target(TriggerStates.TARGET, existing.id())
            .detail("fromAuthorPrincipalId", existing.authorPrincipalId().toString())
            .detail("toAuthorPrincipalId", newAuthor.id().toString())
            .detail("fromPayerPrincipalId", existing.payerPrincipalId() != null
                    ? existing.payerPrincipalId().toString() : null)
            .detail("toPayerPrincipalId", checked.payer().id().toString())
            .build());
        auditAccounts(after, rows, checked.modelAccess(), "reassigned");
        exposures.record(after);
        events.publishEvent(new TriggerEvents.TriggerReassigned(companyId, existing.id(),
                existing.authorPrincipalId(), newAuthor.id()));
        events.publishEvent(new TriggerEvents.TriggerAccountsChanged(companyId, existing.id()));
        return view(seen.caller(), store.find(companyId, existing.id()).orElseThrow());
    }

    // ---- the company's firing limits -------------------------------------------------------------------------------

    /** The company's limits, {@code [default, max]}: its admins', else the instance's (§11, open point 15). */
    int[] limits(UUID companyId) {
        return store.limits(companyId)
            .orElse(new int[] {properties.firingsPerHourDefault(), properties.firingsPerHourMax()});
    }

    @Transactional(readOnly = true)
    public CompanySettings companySettings(UUID companyId) {
        caller(companyId);
        return settingsView(companyId);
    }

    /**
     * The settings as they are now, with their version (0 until the company set them): one read of the row, so that
     * the version answered is the values' own, and a save that names it changes nothing newer.
     */
    private CompanySettings settingsView(UUID companyId) {
        InForce stored = inForce(store.settings(companyId).orElse(null));
        return new CompanySettings(new FiringLimits(stored.limits()[0], stored.limits()[1]), stored.version())
            .webhookDeliveriesPerMinute(new WebhookDeliveryLimits(stored.webhook()[0], stored.webhook()[1]))
            .triggerSubscriptions(stored.subscriptions());
    }

    /** The settings in force, each the company's, else the instance's; {@code version} 0 without a row. */
    private record InForce(int[] limits, int[] webhook, boolean subscriptions, int version) {
    }

    private InForce inForce(TriggerStore.@Nullable Settings row) {
        if (row == null) {
            return new InForce(new int[] {properties.firingsPerHourDefault(), properties.firingsPerHourMax()},
                    webhookLimits.instance(), false, 0);
        }
        return new InForce(new int[] {row.firingsDefault(), row.firingsMax()},
                row.webhook() != null ? row.webhook() : webhookLimits.instance(), row.subscriptions(), row.version());
    }

    /**
     * Saves the settings as a whole, under optimistic locking (V060): the change names the version it read, and one
     * made meanwhile is {@code 409 SETTINGS_CHANGED} with nothing written. The firing limits are required; the webhook
     * limits and the subscriptions switch keep their value when absent.
     */
    @Transactional
    public CompanySettings saveCompanySettings(UUID companyId, CompanySettings input) {
        TriggerCallers.Caller caller = caller(companyId);
        if (!caller.admin()) {
            throw ApiException.forbidden("Only admins change the company's settings");
        }
        Integer version = input.getVersion();
        if (version == null || version < 0) {
            throw ApiException.validation("version: the version of the settings the change read");
        }
        FiringLimits limits = input.getTriggerFiringsPerHour();
        if (limits == null || limits.getDefault() == null || limits.getMax() == null || limits.getDefault() < 1
                || limits.getMax() > MAX_LIMIT || limits.getDefault() > limits.getMax()) {
            throw ApiException.validation("triggerFiringsPerHour: a default of at least 1, at most the maximum, itself "
                    + "at most " + MAX_LIMIT);
        }
        WebhookDeliveryLimits webhook = input.getWebhookDeliveriesPerMinute();
        if (webhook != null && (webhook.getDefault() == null || webhook.getMax() == null || webhook.getDefault() < 1
                || webhook.getMax() > MAX_WEBHOOK_LIMIT || webhook.getDefault() > webhook.getMax())) {
            throw ApiException.validation("webhookDeliveriesPerMinute: a default of at least 1, at most the "
                    + "maximum, itself at most " + MAX_WEBHOOK_LIMIT);
        }
        // One read of the row for what the change keeps and the audit's "from"; the conditional save below is what
        // refuses a change made since.
        TriggerStore.Settings row = store.settings(companyId).orElse(null);
        InForce was = inForce(row);
        int[] before = was.limits();
        int[] webhookBefore = was.webhook();
        boolean subscriptionsBefore = was.subscriptions();
        int[] webhookStored = webhook != null ? new int[] {webhook.getDefault(), webhook.getMax()}
                : row != null ? row.webhook() : null;
        boolean subscriptions = input.getTriggerSubscriptions() != null ? input.getTriggerSubscriptions()
                : subscriptionsBefore;
        Instant now = time.now();
        if (!store.saveSettings(companyId, version, limits.getDefault(), limits.getMax(), webhookStored, subscriptions,
                caller.userId(), now)) {
            throw ApiException.conflict(ProblemCode.SETTINGS_CHANGED, "The company's settings changed since version "
                    + version + " was read: read them again and make the change on them");
        }
        // Lowering the maximum lowers the triggers above it, each locked in the order of their ids.
        for (UUID triggerId : store.liveAbove(companyId, limits.getMax())) {
            TriggerInfo trigger = store.lock(companyId, triggerId).orElseThrow();
            store.setMaxFirings(companyId, triggerId, limits.getMax(), now);
            auditLog.record(AuditRecord.company(companyId, AuditAction.TRIGGER_CHANGED, CurrentActor.actor())
                .target(TriggerStates.TARGET, triggerId)
                .details(TriggerViews.auditDetails(trigger))
                .detail("changed", List.of("maxFiringsPerHour"))
                .detail("maxFiringsPerHour", limits.getMax())
                .detail("cause", "company_settings.changed")
                .build());
            events.publishEvent(new TriggerEvents.TriggerChanged(companyId, triggerId, Set.of("maxFiringsPerHour")));
        }
        AuditRecord.Builder audit = AuditRecord.company(companyId, AuditAction.COMPANY_SETTINGS_CHANGED,
                CurrentActor.actor())
            .detail("triggerFiringsPerHourDefault", Map.of("from", before[0], "to", limits.getDefault()))
            .detail("triggerFiringsPerHourMax", Map.of("from", before[1], "to", limits.getMax()));
        // The webhook's deliveries a minute (package 5, §5.12): lowering the maximum lowers the systems above it.
        if (webhook != null) {
            webhookSystems.lowerPerMinute(companyId, webhook.getMax());
            audit.detail("webhookDeliveriesPerMinuteDefault", Map.of("from", webhookBefore[0], "to",
                    webhook.getDefault()))
                .detail("webhookDeliveriesPerMinuteMax", Map.of("from", webhookBefore[1], "to", webhook.getMax()));
        }
        // Whether a trigger may pay with its author's subscription (ADR-0085, decision 7): off by default; the company
        // that turns it on takes the vendors' terms for personal plans on itself. Turning it off pauses nothing here:
        // the gate pauses a trigger that still pays with one at its next firing, for its author to choose a model.
        if (subscriptions != subscriptionsBefore) {
            audit.detail("triggerSubscriptions", Map.of("from", subscriptionsBefore, "to", subscriptions));
        }
        auditLog.record(audit.build());
        return settingsView(companyId);
    }

    // ---- names -----------------------------------------------------------------------------------------------------

    /** The names a view shows, each read once. */
    private final class Names {

        private final UUID companyId;

        private final Map<UUID, Optional<String>> systemNames = new LinkedHashMap<>();

        private final Map<UUID, Optional<String>> agentNames = new LinkedHashMap<>();

        private final Map<UUID, Optional<String>> principalNames = new LinkedHashMap<>();

        private final Map<UUID, Optional<String>> groupNames = new LinkedHashMap<>();

        Names(UUID companyId) {
            this.companyId = companyId;
        }

        @Nullable String system(@Nullable UUID systemId) {
            return systemId == null ? null : systemNames.computeIfAbsent(systemId,
                    id -> systems.find(companyId, id).map(SystemInfo::name)).orElse(null);
        }

        @Nullable String agent(UUID agentId) {
            return agentNames.computeIfAbsent(agentId, id -> agents.find(companyId, id).map(AgentInfo::name))
                .orElse(null);
        }

        @Nullable String principal(@Nullable UUID principalId) {
            return principalId == null ? null : principalNames.computeIfAbsent(principalId,
                    id -> Optional.ofNullable(principals.displayNames(companyId, List.of(id)).get(id))).orElse(null);
        }

        @Nullable String group(@Nullable UUID groupId) {
            return groupId == null ? null : groupNames.computeIfAbsent(groupId,
                    id -> Optional.ofNullable(groups.groupNames(companyId, List.of(id)).get(id))).orElse(null);
        }
    }

    /** The run's model access, for the preview's payer: what package 2 answered. */
    static @Nullable RunModelAccess payment(TriggerEditor.Checked checked) {
        return checked.payment();
    }

    /**
     * The trigger's channels replaced. The editor read the other triggers' claims without locks
     * ({@code CHANNEL_TAKEN}); a save that claimed the same channel since is refused by V030's unique indexes, and
     * answered as the editor would have ({@code 409 CHANNEL_TAKEN}), not as a failure.
     */
    private void replaceChannels(UUID companyId, UUID triggerId, UUID systemId, UUID agentId,
            List<TriggerEditor.ChannelInput> channels, @Nullable UUID addedBy, Instant now) {
        try {
            store.replaceChannels(companyId, triggerId, systemId, agentId, channels, Set.of(), addedBy, now);
        }
        catch (DataIntegrityViolationException ex) {
            if (Constraints.violated(ex, CHANNEL_AGENT_INDEX, CHANNEL_DEFAULT_INDEX)) {
                throw ApiException.conflict(ProblemCode.CHANNEL_TAKEN, "Another trigger took one of the channels "
                        + "just now; reload and choose again");
            }
            throw ex;
        }
    }

    /** What a row may do there, as the API says it (ADR-0072). */
    private static com.flowtivo.tinfirm.shared.api.model.AccountAccess access(GrantInfo.Access access) {
        return com.flowtivo.tinfirm.shared.api.model.AccountAccess.fromValue(access.name());
    }
}
