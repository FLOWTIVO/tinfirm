package com.flowtivo.tinfirm.trigger.internal;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.account.AccountBinding;
import com.flowtivo.tinfirm.account.AccountInfo;
import com.flowtivo.tinfirm.account.Accounts;
import com.flowtivo.tinfirm.account.GrantInfo;
import com.flowtivo.tinfirm.agent.AgentAccess;
import com.flowtivo.tinfirm.agent.AgentDefinition;
import com.flowtivo.tinfirm.agent.AgentInfo;
import com.flowtivo.tinfirm.agent.Agents;
import com.flowtivo.tinfirm.agent.EffectiveAgent;
import com.flowtivo.tinfirm.agent.ToolMode;
import com.flowtivo.tinfirm.agent.TriggerSettings;
import com.flowtivo.tinfirm.company.GroupMembership;
import com.flowtivo.tinfirm.model.Hosts;
import com.flowtivo.tinfirm.model.ModelAccess;
import com.flowtivo.tinfirm.model.ModelAccessChoice;
import com.flowtivo.tinfirm.model.RunModelAccess;
import com.flowtivo.tinfirm.model.RunModelContext;
import com.flowtivo.tinfirm.principal.PrincipalInfo;
import com.flowtivo.tinfirm.principal.PrincipalKind;
import com.flowtivo.tinfirm.principal.Principals;
import com.flowtivo.tinfirm.runner.RuntimeKind;
import com.flowtivo.tinfirm.shared.api.model.AgentOwnServer;
import com.flowtivo.tinfirm.shared.api.model.AgentSettingsRequest;
import com.flowtivo.tinfirm.shared.api.model.AgentSettingsToolMode;
import com.flowtivo.tinfirm.shared.web.ApiException;
import com.flowtivo.tinfirm.shared.web.ProblemCode;
import com.flowtivo.tinfirm.system.SystemInfo;
import com.flowtivo.tinfirm.system.SystemKind;
import com.flowtivo.tinfirm.system.Systems;
import com.flowtivo.tinfirm.system.ToolEffect;
import com.flowtivo.tinfirm.system.ToolInfo;
import com.flowtivo.tinfirm.trigger.ChannelSources;
import com.flowtivo.tinfirm.trigger.ConditionGroup;
import com.flowtivo.tinfirm.trigger.TriggerAccount;
import com.flowtivo.tinfirm.trigger.TriggerAction;
import com.flowtivo.tinfirm.trigger.TriggerEvent;
import com.flowtivo.tinfirm.trigger.TriggerEventSpec;
import com.flowtivo.tinfirm.trigger.TriggerInfo;
import com.flowtivo.tinfirm.trigger.TriggerRepeat;
import com.flowtivo.tinfirm.trigger.TriggerResults;
import com.flowtivo.tinfirm.trigger.TriggerSchedule;
import com.flowtivo.tinfirm.trigger.TriggerSource;
import com.flowtivo.tinfirm.trigger.TriggerSubject;
import com.flowtivo.tinfirm.trigger.WaitingReason;

/**
 * {@code TriggerEditor.check(author, draft)} (the package 4 contract, §5.2 with §11): the preview, storing nothing, and
 * the guard of every save, a create and every change, which pass it whole. Always for the trigger's author: an admin
 * editing on the author's behalf passes the author's entitlement, never their own (§11, open point 7), and adds no row
 * that names the author's own account and no own MCP server of theirs that the trigger did not have
 * ({@code 403 AUTHOR_ACCOUNT_NOT_ADMINS}: the security review of 2026-10-27, H2; a reassignment's too). In order:
 * <ol>
 * <li>the agent: live, used by the author;</li>
 * <li>the source: a company tracker system ({@code SYSTEM_KIND_INVALID}), the rules of ADR-0043
 * ({@code INVALID_TRIGGER}), or a schedule's cron and zone;</li>
 * <li>each row through {@code Accounts.bind(Trigger)}: {@code Fixed} stored, {@code ConnectRequired} stored (the gate
 * pauses the trigger {@code ACCOUNT_BROKEN} until it is connected again, §11), {@code NotEntitled}
 * {@code 400 ACCOUNT_NOT_ENTITLED}; the source row with an identity ({@code SOURCE_ACCOUNT_REQUIRED}), and not a
 * credential lent read-only while the results write there ({@code ACCOUNT_READ_ONLY}: I9, no write through a
 * trigger's {@code READ} lending; a lending narrowed after the save pauses nothing, the write-back drops its rows);</li>
 * <li>the payer: the author, or a service identity they may use ({@code MODEL_ACCESS_NOT_ENTITLED});
 * {@code ModelAccess.forRun}: {@code Refuse} {@code 409 MODEL_ACCESS_UNAVAILABLE} (or {@code CANNOT_RUN_ANYWHERE}),
 * {@code Wait} stored;</li>
 * <li>the hosts: {@code Nowhere} {@code 409 CANNOT_RUN_ANYWHERE};</li>
 * <li>the settings: package 3's checks for the trigger ({@code TriggerSettings.check});</li>
 * <li>the team: a group of the company;</li>
 * <li>the open events' acknowledgement ({@code OPEN_EVENTS_NOT_ACKNOWLEDGED}; a preview answers it instead): every
 * {@code ALLOW} write tool the open events would run, with the author's own account or a credential lent to a service
 * account alike (D2, the owner's decision Q3: the author acknowledges every open write, and the lender is told).</li>
 * </ol>
 * A channel trigger (package 5, §5.7) adds, after its source, for the author whoever edits: the system has a bot
 * ({@code 409 BOT_NOT_SET}); each channel is known, the bot is in it and it is neither archived nor shared
 * ({@code 400 CHANNEL_UNAVAILABLE}); the author is linked on the system and a member of each channel, as the platform
 * said before the save ({@code 400 CHANNEL_NOT_JOINED}); one trigger per agent per channel and one default
 * ({@code 409 CHANNEL_TAKEN}). It needs no row on its source, and is open to anyone in its channels: its acknowledgement
 * is asked as a tracker's.
 * The one choice ({@link #choices}) fills a table from "Use the accounts of: …".
 */
@Component
class TriggerEditor {

    static final int MAX_ROWS = 100;

    private final Agents agents;

    private final TriggerSettings triggerSettings;

    private final Accounts accounts;

    private final Principals principals;

    private final ModelAccess modelAccess;

    private final Hosts hosts;

    private final Systems systems;

    private final GroupMembership groups;

    private final TriggerStore store;

    private final ObjectProvider<ChannelSources> channelSources;

    TriggerEditor(Agents agents, TriggerSettings triggerSettings, Accounts accounts, Principals principals,
            ModelAccess modelAccess, Hosts hosts, Systems systems, GroupMembership groups, TriggerStore store,
            ObjectProvider<ChannelSources> channelSources) {
        this.store = store;
        this.channelSources = channelSources;
        this.agents = agents;
        this.triggerSettings = triggerSettings;
        this.accounts = accounts;
        this.principals = principals;
        this.modelAccess = modelAccess;
        this.hosts = hosts;
        this.systems = systems;
        this.groups = groups;
    }

    // ---- what is checked -------------------------------------------------------------------------------------------

    /**
     * A row as requested: the account named for the system, and whom it acts through (ADR-0072).
     *
     * @param holderPrincipalId the service account that borrows the credential named; null for the author's own
     *                          account, or for the one service account the author may use that borrows it
     */
    record RowInput(UUID systemId, UUID accountId, @Nullable UUID holderPrincipalId) {

        RowInput(UUID systemId, UUID accountId) {
            this(systemId, accountId, null);
        }
    }

    /**
     * A channel of a channel trigger, as requested and as checked.
     *
     * @param name the channel's name, once checked; null as requested
     */
    record ChannelInput(String channelId, boolean isDefault, @Nullable String name) {

        ChannelInput(String channelId, boolean isDefault) {
            this(channelId, isDefault, null);
        }
    }

    /** At most this many channels a trigger (§3.10). */
    static final int MAX_CHANNELS = 20;

    /** A schedule as requested. */
    record ScheduleInput(@Nullable String cron, @Nullable String zone, TriggerSchedule.Overlap overlap,
            TriggerSchedule.CatchUp catchUp) {
    }

    /**
     * A trigger as requested, a create's or a change's whole; absent lists are empty.
     *
     * @param agentVersion the agent's version the editor showed (a create); a change keeps the accepted one
     * @param settings     the settings to save with it; null: the stored ones (a change) or the defaults (a create)
     */
    record Draft(String name, TriggerSource source, @Nullable UUID systemId, List<TriggerEventSpec> events,
            @Nullable TriggerSubject subject, @Nullable String scope, List<ConditionGroup> conditions,
            @Nullable TriggerRepeat repeat, @Nullable ScheduleInput schedule, UUID agentId, int agentVersion,
            String instruction, List<RowInput> rows, UUID payerPrincipalId, @Nullable AgentSettingsRequest settings,
            @Nullable UUID teamGroupId, @Nullable TriggerResults results, @Nullable Integer maxFiringsPerHour,
            boolean acknowledgeOpenEvents, List<ChannelInput> channels, ChannelSources.@Nullable Joined joined,
            List<WebhookCondition> webhookConditions) {

        Draft {
            events = List.copyOf(events);
            conditions = List.copyOf(conditions);
            rows = List.copyOf(rows);
            channels = List.copyOf(channels);
            webhookConditions = List.copyOf(webhookConditions);
        }

        /** A tracker's or a schedule's draft, without channels. */
        Draft(String name, TriggerSource source, @Nullable UUID systemId, List<TriggerEventSpec> events,
                @Nullable TriggerSubject subject, @Nullable String scope, List<ConditionGroup> conditions,
                @Nullable TriggerRepeat repeat, @Nullable ScheduleInput schedule, UUID agentId, int agentVersion,
                String instruction, List<RowInput> rows, UUID payerPrincipalId,
                @Nullable AgentSettingsRequest settings, @Nullable UUID teamGroupId, @Nullable TriggerResults results,
                @Nullable Integer maxFiringsPerHour, boolean acknowledgeOpenEvents) {
            this(name, source, systemId, events, subject, scope, conditions, repeat, schedule, agentId, agentVersion,
                    instruction, rows, payerPrincipalId, settings, teamGroupId, results, maxFiringsPerHour,
                    acknowledgeOpenEvents, List.of(), null, List.of());
        }
    }

    /** The author a trigger is checked for: their principal and their user. */
    record Author(UUID principalId, UUID userId) {
    }

    /**
     * A row of the table after the check.
     *
     * @param holderPrincipalId whom it acts through: the author for their own account, or the service account that
     *                          borrows the credential
     */
    record Row(UUID systemId, @Nullable SystemInfo system, AccountInfo account, UUID holderPrincipalId,
            Status status) {

        /** The row as stored. */
        TriggerAccount stored(TriggerAccount.FilledFrom filledFrom, Instant at) {
            return new TriggerAccount(systemId, account.id(), holderPrincipalId, account.externalId(), filledFrom, at);
        }

        /** Whether it is the author's own account. */
        boolean own() {
            return !account.credential();
        }
    }

    /** How a row's account can be bound now. */
    enum Status {
        OK, RECONNECT_YOURS, RECONNECT_COMPANY
    }

    /** An {@code ALLOW} write tool the open events would run without approval (§5.8). */
    record OpenWrite(UUID systemId, String tool, UUID holderPrincipalId, boolean own) {
    }

    /**
     * The definition after the rules.
     *
     * @param schedule a schedule's parsed form; null for a tracker trigger
     * @param channels a channel trigger's channels, checked and named; none for another source
     * @param audience a channel trigger's askers in words: "any member of Acme in #support"; a webhook trigger's:
     *                 "anyone who can make Acme Desk call its webhook"
     * @param webhookConditions a webhook trigger's conditions on the raw body; none for another source
     */
    record Definition(String name, TriggerSource source, @Nullable SystemInfo system, List<TriggerEventSpec> events,
            TriggerSubject subject, @Nullable String scope, List<ConditionGroup> conditions, TriggerRepeat repeat,
            @Nullable Schedule schedule, @Nullable ScheduleInput scheduleInput, String instruction,
            TriggerResults results, List<ChannelInput> channels, @Nullable String audience,
            List<WebhookCondition> webhookConditions) {

        Definition {
            channels = List.copyOf(channels);
            webhookConditions = List.copyOf(webhookConditions);
        }

        /** A definition without webhook conditions. */
        Definition(String name, TriggerSource source, @Nullable SystemInfo system, List<TriggerEventSpec> events,
                TriggerSubject subject, @Nullable String scope, List<ConditionGroup> conditions, TriggerRepeat repeat,
                @Nullable Schedule schedule, @Nullable ScheduleInput scheduleInput, String instruction,
                TriggerResults results, List<ChannelInput> channels, @Nullable String audience) {
            this(name, source, system, events, subject, scope, conditions, repeat, schedule, scheduleInput,
                    instruction, results, channels, audience, List.of());
        }
    }

    /**
     * What a draft checks to: the definition, the table, the payer and what package 2 answers for it, the hosts, the
     * systems left unavailable and those that need no row (an MCP server that needs no account, ADR-0071), the open
     * events.
     *
     * @param unacknowledged a preview's: the save's refusal {@code OPEN_EVENTS_NOT_ACKNOWLEDGED} in words, when the
     *                       acknowledgement is needed and neither the draft nor the trigger gave it; null otherwise
     */
    record Checked(Definition definition, AgentInfo agent, EffectiveAgent effectiveAgent, List<Row> rows,
            PrincipalInfo payer, ModelAccessChoice modelAccess, RuntimeKind runtime, RunModelAccess payment,
            Hosts.Eligibility eligibility, List<SystemInfo> unavailable, List<SystemInfo> noAccount,
            List<OpenWrite> openWrites, boolean acknowledgementNeeded, @Nullable UUID teamGroupId,
            List<Instant> nextFirings, @Nullable String unacknowledged) {
    }

    // ---- the check -------------------------------------------------------------------------------------------------

    /**
     * Checks the draft whole for the author, as a save does; refusals are thrown as the API's problems.
     *
     * @param existing the trigger a change is of; null for a create
     * @param now      for a schedule's next firings
     */
    Checked check(UUID companyId, Author author, Draft draft, @Nullable TriggerInfo existing, Instant now) {
        return check(companyId, author, draft, existing, now, false);
    }

    /**
     * {@link #check(UUID, Author, Draft, TriggerInfo, Instant)}; a preview's ({@code preview}) answers a missing
     * acknowledgement of the open events in {@link Checked#unacknowledged} instead of refusing it, so that the editor
     * shows what it means before the person acknowledges it. Every other refusal is the save's.
     */
    Checked check(UUID companyId, Author author, Draft draft, @Nullable TriggerInfo existing, Instant now,
            boolean preview) {
        return check(companyId, author, draft, existing, now, preview, false);
    }

    /**
     * {@link #check(UUID, Author, Draft, TriggerInfo, Instant, boolean)}; {@code onBehalf} for a save an admin makes
     * for the author (an edit on their behalf, a reassignment's new author): its rows may name the author's own account,
     * and its settings add an own MCP server of theirs, only where the trigger had them already
     * ({@code 403 AUTHOR_ACCOUNT_NOT_ADMINS}, H2).
     */
    Checked check(UUID companyId, Author author, Draft draft, @Nullable TriggerInfo existing, Instant now,
            boolean preview, boolean onBehalf) {
        // 1. The agent.
        AgentInfo agent = agents.find(companyId, draft.agentId()).filter(AgentInfo::live)
            .orElseThrow(() -> ApiException.notFound("Agent not found"));
        AgentAccess access = agents.access(companyId, agent.id(), author.userId());
        if (!access.uses()) {
            throw ApiException.forbidden(ProblemCode.AGENT_NOT_USABLE, "The author may not use the agent "
                    + agent.name() + ": it is shared with groups they are not in");
        }
        int accepted = existing != null ? triggerSettings.acceptedVersion(companyId, existing.id())
                : draft.agentVersion();
        if (accepted < 1 || accepted > agent.version()) {
            throw ApiException.validation("agentVersion must be one of the agent's versions, 1 to " + agent.version());
        }
        EffectiveAgent effectiveAgent = agents.effective(companyId, agent.id(), accepted)
            .orElseThrow(() -> ApiException.notFound("Agent not found"));

        // 2. The source and the definition; a channel trigger's channels, for the author (package 5, §5.7).
        Definition definition = definition(companyId, draft, now);
        if (definition.source() == TriggerSource.CHANNEL) {
            definition = channels(companyId, author, definition, draft, agent, existing);
        }

        // 3. Each row; on the author's behalf, none of the author's own that the trigger did not have (H2).
        List<Row> rows = rows(companyId, author, draft.rows());
        if (onBehalf) {
            asTheAuthorChoseThem(companyId, rows, draft.settings(), existing);
        }
        if (definition.source() == TriggerSource.TRACKER) {
            UUID source = definition.system() != null ? definition.system().id() : null;
            boolean identified = rows.stream()
                .anyMatch(row -> row.systemId().equals(source) && row.account().externalId() != null);
            if (!identified) {
                throw ApiException.badRequest(ProblemCode.SOURCE_ACCOUNT_REQUIRED, "A trigger on "
                        + (definition.system() != null ? definition.system().name() : "its system")
                        + " needs an account there with an identity: it polls and writes back with it",
                        Map.of("details", Map.of("systemId", String.valueOf(source))));
            }
            requireWritable(companyId, definition, rows);
        }

        // 4. The payer.
        PrincipalInfo payer = payer(companyId, author, draft.payerPrincipalId());
        boolean ownRowsOnly = rows.stream().allMatch(Row::own);
        UUID triggerId = existing != null ? existing.id() : new UUID(0, 0);
        // A subscription pays only when the company allows it and the payer is the author (ADR-0085, decision 7).
        boolean subscriptionAllowed = store.subscriptionsOn(companyId) && payer.id().equals(author.principalId());
        TriggerSettings.Owner owner = new TriggerSettings.Owner(triggerId, author.principalId(), payer.id(),
                ownRowsOnly, subscriptionAllowed);

        // 6. The settings (before 4's model access, which reads their runtime and model access).
        AgentSettingsRequest settings = draft.settings();
        if (existing != null && settings == null) {
            if (!ownRowsOnly && triggerSettings.addsOwnServers(companyId, existing.id())) {
                throw ApiException.badRequest(ProblemCode.SETTINGS_WIDEN, "The author's own MCP servers act only "
                        + "while every account of the trigger is the author's own: take them out of the settings first");
            }
        }
        else {
            triggerSettings.check(companyId, agent.id(), owner, settings);
        }
        RuntimeKind runtime = runtime(agent, settings, existing);
        ModelAccessChoice choice = modelAccessOf(settings, existing, companyId, agent.id());

        // The workspace's git accounts: the code hosts of the starting repositories that have a row.
        Map<UUID, Row> bySystem = new LinkedHashMap<>();
        rows.forEach(row -> bySystem.put(row.systemId(), row));
        Set<UUID> holders = new LinkedHashSet<>();
        for (AgentDefinition.RepositoryEntry repository : effectiveAgent.definition().repositories()) {
            Row row = bySystem.get(repository.systemId());
            if (row != null) {
                holders.add(row.holderPrincipalId());
            }
        }
        RunModelContext context = new RunModelContext(companyId, payer.id(), choice, runtime, holders);
        RunModelAccess payment = modelAccess.forRun(context);
        if (payment instanceof RunModelAccess.Refuse refuse) {
            if (refuse.reason() == RunModelAccess.RefuseReason.CANNOT_RUN_ANYWHERE) {
                throw ApiException.conflict(ProblemCode.CANNOT_RUN_ANYWHERE, refuse.detail(),
                        Map.of("details", Map.of("reason", refuse.reason().name())));
            }
            throw ApiException.conflict(ProblemCode.MODEL_ACCESS_UNAVAILABLE, refuse.detail(),
                    Map.of("details", Map.of("reason", refuse.reason().name())));
        }

        // 5. The hosts.
        Hosts.Eligibility eligibility = hosts.eligible(context);
        if (eligibility instanceof Hosts.Eligibility.Nowhere nowhere) {
            throw ApiException.conflict(ProblemCode.CANNOT_RUN_ANYWHERE, nowhere.refusal().detail(),
                    Map.of("details", Map.of("reason", "CANNOT_RUN_ANYWHERE")));
        }

        // 7. The team.
        UUID team = draft.teamGroupId();
        if (team != null && groups.groupNames(companyId, List.of(team)).isEmpty()) {
            throw ApiException.validation("teamGroupId is not a group of the company");
        }

        // 8. The open events.
        List<OpenWrite> writes = definition.source() != TriggerSource.SCHEDULE
                ? openWrites(companyId, author, effectiveAgent, rows, settings, existing) : List.of();
        // D2, Q3: every open write needs the author's acknowledgement, with their own account or a lent credential.
        boolean needed = !writes.isEmpty();
        String unacknowledged = null;
        if (needed && !draft.acknowledgeOpenEvents()
                && (existing == null || existing.openEventsAcknowledgedAt() == null)) {
            boolean own = writes.stream().anyMatch(OpenWrite::own);
            boolean lent = writes.stream().anyMatch(write -> !write.own());
            String with = own && lent ? "with the author's own account and a credential lent to a service account"
                    : own ? "with the author's own account" : "with a credential lent to a service account";
            unacknowledged = drivers(definition) + " drives this trigger, and its agent then writes " + with
                    + " without approval: acknowledge it (acknowledgeOpenEvents), or set the write tools to ASK"
                    + (lent ? "; whoever lent the credential is told when the trigger is saved" : "");
            if (!preview) {
                throw ApiException.badRequest(ProblemCode.OPEN_EVENTS_NOT_ACKNOWLEDGED, unacknowledged);
            }
        }

        List<SystemInfo> unavailable = new ArrayList<>();
        List<SystemInfo> noAccount = new ArrayList<>();
        Map<UUID, SystemInfo> agentSystems = systems.findAll(companyId, effectiveAgent.definition().systems()
            .stream()
            .map(AgentDefinition.SystemEntry::systemId)
            .toList());
        for (AgentDefinition.SystemEntry system : effectiveAgent.definition().systems()) {
            SystemInfo info = agentSystems.get(system.systemId());
            if (!bySystem.containsKey(system.systemId()) && info != null) {
                // An MCP server that needs no account is the runs' without a row (ADR-0071).
                (info.accountsRequired() ? unavailable : noAccount).add(info);
            }
        }
        List<Instant> next = definition.schedule() != null ? definition.schedule().next(now, 3) : List.of();
        return new Checked(definition, agent, effectiveAgent, rows, payer, choice, runtime, payment, eligibility,
                unavailable, noAccount, writes, needed, team, next, unacknowledged);
    }

    /** The source and the rules of ADR-0043, or a schedule's cron and zone (§5.2 step 2). */
    Definition definition(UUID companyId, Draft draft, Instant now) {
        String name = TriggerRules.name(draft.name());
        String instruction = TriggerRules.instruction(draft.instruction());
        TriggerResults results = draft.results() != null ? draft.results() : TriggerResults.DEFAULT;
        if (draft.source() == TriggerSource.SCHEDULE) {
            if (draft.systemId() != null || !draft.events().isEmpty() || draft.scope() != null
                    || !draft.conditions().isEmpty()) {
                throw TriggerRules.invalid("a schedule has no system, events, scope or conditions");
            }
            if (!results.onSuccess().isEmpty() || !results.onFailure().isEmpty() || results.comment()) {
                results = new TriggerResults(false, results.review(), List.of(), List.of());
            }
            ScheduleInput input = draft.schedule();
            if (input == null) {
                throw TriggerRules.invalid("a schedule needs its cron and time zone");
            }
            Schedule schedule = TriggerRules.schedule(input.cron(), input.zone(), now);
            return new Definition(name, TriggerSource.SCHEDULE, null, List.of(), TriggerSubject.ANY, null, List.of(),
                    TriggerRepeat.EVERY_TIME, schedule, input, instruction, results, List.of(), null);
        }
        if (draft.schedule() != null) {
            throw TriggerRules.invalid("only a SCHEDULE trigger takes a schedule");
        }
        if (draft.source() != TriggerSource.WEBHOOK && !draft.webhookConditions().isEmpty()) {
            throw TriggerRules.invalid("only a WEBHOOK trigger takes webhookConditions");
        }
        if (draft.source() == TriggerSource.CHANNEL) {
            return channelDefinition(companyId, draft, name, instruction, results);
        }
        if (!draft.channels().isEmpty()) {
            throw TriggerRules.invalid("only a CHANNEL trigger takes channels");
        }
        if (draft.source() == TriggerSource.WEBHOOK) {
            return webhookDefinition(companyId, draft, name, instruction, results);
        }
        if (draft.systemId() == null) {
            throw ApiException.validation("A TRACKER trigger names its systemId");
        }
        SystemInfo system = systems.find(companyId, draft.systemId())
            .filter(found -> !found.personal())
            .orElseThrow(() -> ApiException.validation("systemId is not a company system"));
        if (!TriggerRules.TRACKERS.contains(system.kind())) {
            throw ApiException.badRequest(ProblemCode.SYSTEM_KIND_INVALID, "A trigger belongs to a tracker: a "
                    + "system of kind JIRA_DC, JIRA_CLOUD, GITHUB or GITLAB, not " + system.kind());
        }
        List<TriggerEventSpec> events = TriggerRules.events(system.kind(), draft.events());
        TriggerSubject subject = TriggerRules.subject(system.kind(), draft.subject(), events);
        String scope = TriggerRules.scope(system.kind(), draft.scope());
        List<ConditionGroup> conditions = TriggerRules.conditions(system.kind(), draft.conditions());
        TriggerRules.requireSatisfiable(events, conditions);
        TriggerResults checkedResults = new TriggerResults(results.comment(), results.review(),
                TriggerRules.actions(system.kind(), "onSuccess", results.onSuccess()),
                TriggerRules.actions(system.kind(), "onFailure", results.onFailure()));
        return new Definition(name, TriggerSource.TRACKER, system, events, subject, scope, conditions,
                draft.repeat() != null ? draft.repeat() : TriggerRepeat.EVERY_TIME, null, null, instruction,
                checkedResults, List.of(), null);
    }

    /** Each row through {@code Accounts.bind(Trigger)}, for the author (§5.2 step 3, T1). */
    List<Row> rows(UUID companyId, Author author, List<RowInput> input) {
        if (input.size() > MAX_ROWS) {
            throw ApiException.validation("A trigger has at most " + MAX_ROWS + " accounts");
        }
        Set<UUID> seen = new LinkedHashSet<>();
        List<Row> rows = new ArrayList<>();
        Map<UUID, SystemInfo> named = systems.findAll(companyId, input.stream().map(RowInput::systemId).toList());
        for (RowInput row : input) {
            if (!seen.add(row.systemId())) {
                throw ApiException.validation("The system " + row.systemId() + " is named twice: a trigger has one "
                        + "account per system");
            }
            UUID holder = holder(companyId, author, row);
            AccountBinding.Outcome outcome = accounts.bind(new AccountBinding.Trigger(companyId, author.principalId(),
                    holder, row.accountId()), row.systemId());
            Row checked = switch (outcome) {
                case AccountBinding.Fixed fixed -> new Row(row.systemId(), named.get(row.systemId()), fixed.account(),
                        fixed.holderPrincipalId(), Status.OK);
                case AccountBinding.ConnectRequired required -> {
                    AccountInfo account = accounts.find(companyId, row.accountId())
                        .orElseThrow(() -> notEntitled(row.systemId(), "ACCOUNT_NOT_USABLE"));
                    yield new Row(row.systemId(), named.get(row.systemId()), account, holder,
                            account.credential() ? Status.RECONNECT_COMPANY : Status.RECONNECT_YOURS);
                }
                case AccountBinding.NotEntitled refused when refused.reason() == AccountBinding.Refusal.BOT_ACCOUNT ->
                    throw ApiException.badRequest(ProblemCode.BOT_ACCOUNT, "A chat system's bot is never in a "
                            + "trigger's table: name a member's own account there for its tools, or a credential a "
                            + "service account borrows", Map.of("details", Map.of("systemId", row.systemId().toString())));
                case AccountBinding.NotEntitled refused -> throw notEntitled(row.systemId(), refused.reason().name());
                default -> throw notEntitled(row.systemId(), "ACCOUNT_NOT_USABLE");
            };
            if (!checked.account().systemId().equals(row.systemId())) {
                throw notEntitled(row.systemId(), "ACCOUNT_NOT_USABLE");
            }
            rows.add(checked);
        }
        return rows;
    }

    /**
     * H2 (the owner's decision Q2): an admin's save on the author's behalf names the author's own account only on a row
     * the trigger had with that account, and adds to the settings only the author's own MCP servers the trigger had;
     * else {@code 403 AUTHOR_ACCOUNT_NOT_ADMINS}, with the system. A reassignment's rows are the former author's, so its
     * new author's own accounts and servers are never the admin's to name: the new author adds them.
     */
    private void asTheAuthorChoseThem(UUID companyId, List<Row> rows, @Nullable AgentSettingsRequest settings,
            @Nullable TriggerInfo existing) {
        Set<UUID> had = new HashSet<>();
        Set<UUID> hadServers = new HashSet<>();
        if (existing != null) {
            for (TriggerAccount row : store.accounts(companyId, existing.id())) {
                if (row.accountId() != null) {
                    had.add(row.accountId());
                }
            }
            if (settings != null && settings.getOwnServers() != null && !settings.getOwnServers().isEmpty()) {
                triggerSettings.view(companyId, existing.agentId(), existing.id()).getOwnServers()
                    .forEach(server -> hadServers.add(server.getSystemId()));
            }
        }
        for (Row row : rows) {
            if (row.own() && !had.contains(row.account().id())) {
                throw authorsOwn(row.systemId(), "an account of the trigger's author");
            }
        }
        if (settings != null && settings.getOwnServers() != null) {
            for (AgentOwnServer server : settings.getOwnServers()) {
                if (!hadServers.contains(server.getSystemId())) {
                    throw authorsOwn(server.getSystemId(), "an MCP server of the trigger's author");
                }
            }
        }
    }

    private static ApiException authorsOwn(UUID systemId, String what) {
        return ApiException.forbidden(ProblemCode.AUTHOR_ACCOUNT_NOT_ADMINS, "The save names " + what + " on the "
                + "system " + systemId + ": only the author adds their own accounts and MCP servers to a trigger",
                Map.of("details", Map.of("systemId", systemId.toString())));
    }

    /**
     * I9: the results of a tracker trigger write to its source with the source row's account (the comment, the
     * actions), which a credential lent read-only to the service account cannot (ADR-0072, open point 1; the
     * write-back drops such a row): {@code 400 ACCOUNT_READ_ONLY} with the system, unless the results write nothing.
     * The lending's access as it is at this save; one narrowed afterwards pauses nothing.
     */
    private void requireWritable(UUID companyId, Definition definition, List<Row> rows) {
        TriggerResults results = definition.results();
        SystemInfo system = definition.system();
        if (system == null || !results.comment() && results.onSuccess().isEmpty() && results.onFailure().isEmpty()) {
            return;
        }
        for (Row row : rows) {
            if (!row.systemId().equals(system.id()) || accounts.fixedAccess(companyId, row.systemId(),
                    row.holderPrincipalId(), row.account().id()) != GrantInfo.Access.READ) {
                continue;
            }
            String holder = principals.find(companyId, row.holderPrincipalId()).map(PrincipalInfo::name)
                .orElse("its service account");
            throw ApiException.badRequest(ProblemCode.ACCOUNT_READ_ONLY, "The credential named for " + system.name()
                    + " is lent read-only to " + holder + ", and the trigger's results write there"
                    + (results.comment() ? " (the comment" + (results.onSuccess().isEmpty()
                            && results.onFailure().isEmpty() ? ")" : ", the actions)") : " (the actions)")
                    + ": name a credential lent with write access, or turn the comment and the actions off",
                    Map.of("details", Map.of("systemId", system.id().toString())));
        }
    }

    /**
     * Whom a requested row acts through: the service account named; else the author, for their own account; else the
     * one service account the author may use that borrows the credential named (ADR-0072), the author when there is
     * none or several, which the binding refuses then.
     */
    private UUID holder(UUID companyId, Author author, RowInput row) {
        if (row.holderPrincipalId() != null) {
            return row.holderPrincipalId();
        }
        AccountInfo account = accounts.find(companyId, row.accountId()).orElse(null);
        if (account == null || !account.credential()) {
            return author.principalId();
        }
        List<UUID> usable = accounts.borrowers(companyId, account.id()).stream()
            .filter(service -> principals.mayUse(companyId, author.userId(), service))
            .toList();
        return usable.size() == 1 ? usable.getFirst() : author.principalId();
    }

    private static ApiException notEntitled(UUID systemId, String reason) {
        return ApiException.badRequest(ProblemCode.ACCOUNT_NOT_ENTITLED, "The author is not entitled to the account "
                + "named for the system " + systemId + " (" + reason + "): name their own account there, or a "
                + "credential a service account they may use borrows", Map.of("details",
                        Map.of("systemId", systemId.toString(), "reason", reason)));
    }

    /** The payer: the author, or a service identity the author may use (§5.2 step 4, T1). */
    PrincipalInfo payer(UUID companyId, Author author, UUID payerPrincipalId) {
        PrincipalInfo payer = principals.find(companyId, payerPrincipalId)
            .orElseThrow(() -> ApiException.badRequest(ProblemCode.MODEL_ACCESS_NOT_ENTITLED, "The payer is not a "
                    + "principal of the company"));
        if (payer.id().equals(author.principalId())) {
            return payer;
        }
        if (payer.kind() != PrincipalKind.SERVICE || !principals.mayUse(companyId, author.userId(), payer.id())) {
            throw ApiException.badRequest(ProblemCode.MODEL_ACCESS_NOT_ENTITLED, "A trigger is paid by its author or "
                    + "by a service identity its author may use");
        }
        return payer;
    }

    private RuntimeKind runtime(AgentInfo agent, @Nullable AgentSettingsRequest settings,
            @Nullable TriggerInfo existing) {
        if (settings != null) {
            if (settings.getRuntime() != null) {
                RuntimeKind chosen = RuntimeKind.valueOf(settings.getRuntime().getValue());
                return agent.runtimes().contains(chosen) ? chosen : agent.defaultRuntime();
            }
            return agent.defaultRuntime();
        }
        return existing != null ? effectiveRuntime(agent, existing) : agent.defaultRuntime();
    }

    private RuntimeKind effectiveRuntime(AgentInfo agent, TriggerInfo existing) {
        var view = triggerSettings.view(existing.companyId(), agent.id(), existing.id());
        RuntimeKind runtime = RuntimeKind.valueOf(view.getRuntime().getValue());
        return agent.runtimes().contains(runtime) ? runtime : agent.defaultRuntime();
    }

    /**
     * The model access the settings name, or the stored ones: a subscription only as the settings' check allowed one
     * (ADR-0085, decision 7: the company's switch, the author paying), else the budget.
     */
    private ModelAccessChoice modelAccessOf(@Nullable AgentSettingsRequest settings, @Nullable TriggerInfo existing,
            UUID companyId, UUID agentId) {
        if (settings != null) {
            if (settings.getModelAccess() != null && settings.getModelAccess().getValue().equals("SUBSCRIPTION")
                    && settings.getSubscription() != null && settings.getSubscription().getRunnerHostId() != null) {
                return new ModelAccessChoice.Subscription(settings.getSubscription().getRunnerHostId(),
                        settings.getSubscription().getLabel());
            }
            return ModelAccessChoice.BUDGET;
        }
        if (existing == null) {
            return ModelAccessChoice.BUDGET;
        }
        var view = triggerSettings.view(companyId, agentId, existing.id());
        if (view.getModelAccess().getValue().equals("SUBSCRIPTION") && view.getSubscription() != null
                && view.getSubscription().getRunnerHostId() != null) {
            return new ModelAccessChoice.Subscription(view.getSubscription().getRunnerHostId(),
                    view.getSubscription().getLabel());
        }
        return ModelAccessChoice.BUDGET;
    }

    /**
     * The {@code ALLOW} write tools of the agent as the author accepted it, narrowed by the settings, on the systems the
     * table has a row for, each with whose account it would act as (§5.8, the editor's warning).
     */
    private List<OpenWrite> openWrites(UUID companyId, Author author, EffectiveAgent agent, List<Row> rows,
            @Nullable AgentSettingsRequest settings, @Nullable TriggerInfo existing) {
        Set<UUID> off = new LinkedHashSet<>();
        Map<String, String> modes = new HashMap<>();
        AgentSettingsRequest narrowing = settings;
        if (narrowing == null && existing != null) {
            var view = triggerSettings.view(companyId, agent.agent().id(), existing.id());
            off.addAll(view.getSystemsOff());
            view.getToolModes().forEach(mode -> modes.put(mode.getSystemId() + "/" + mode.getTool(),
                    mode.getMode().getValue()));
        }
        else if (narrowing != null) {
            if (narrowing.getSystemsOff() != null) {
                off.addAll(narrowing.getSystemsOff());
            }
            if (narrowing.getToolModes() != null) {
                for (AgentSettingsToolMode mode : narrowing.getToolModes()) {
                    modes.put(mode.getSystemId() + "/" + mode.getTool(), mode.getMode().getValue());
                }
            }
        }
        List<OpenWrite> writes = new ArrayList<>();
        for (Row row : rows) {
            // A credential lent READ writes nothing: its write tools are refused (ADR-0072).
            if (off.contains(row.systemId()) || accounts.fixedAccess(companyId, row.systemId(),
                    row.holderPrincipalId(), row.account().id()) == GrantInfo.Access.READ) {
                continue;
            }
            Optional<AgentDefinition.SystemEntry> system = agent.definition().system(row.systemId());
            if (system.isEmpty()) {
                continue;
            }
            Map<String, ToolEffect> effects = new HashMap<>();
            for (ToolInfo tool : systems.tools(companyId, row.systemId())) {
                effects.put(tool.name(), tool.effect());
            }
            for (AgentDefinition.ToolEntry tool : system.get().tools()) {
                if (modes.containsKey(row.systemId() + "/" + tool.name())) {
                    continue;
                }
                ToolEffect effect = effects.getOrDefault(tool.name(), ToolEffect.UNKNOWN);
                if (effect.effective() == ToolEffect.WRITE && tool.modeFor(effect) == ToolMode.ALLOW) {
                    writes.add(new OpenWrite(row.systemId(), tool.name(), row.holderPrincipalId(), row.own()));
                }
            }
        }
        return writes;
    }

    /**
     * Who drives the trigger, as a sentence's subject: "Anyone who may comment on or change issues in SHOP"; a channel
     * trigger's "Any member of Acme in #support" (package 5, §5.7).
     */
    static String drivers(Definition definition) {
        if (definition.audience() != null) {
            return Character.toUpperCase(definition.audience().charAt(0)) + definition.audience().substring(1);
        }
        return "Anyone who may comment on or change " + who(definition);
    }

    /** Who can drive a tracker trigger (§5.8): "anyone who may comment on issues in SHOP". */
    static String who(Definition definition) {
        if (definition.audience() != null) {
            return definition.audience();
        }
        String place = definition.scope() != null ? definition.scope()
                : definition.system() != null ? definition.system().name() : "the system";
        String subjects = switch (definition.subject()) {
            case ISSUE -> "issues";
            case CHANGE_REQUEST -> definition.system() != null && definition.system().kind() == SystemKind.GITLAB
                    ? "merge requests" : "pull requests";
            case ANY -> definition.system() != null && TriggerRules.jira(definition.system().kind()) ? "issues"
                    : "issues and change requests";
        };
        return subjects + " in " + place;
    }

    // ---- a channel trigger (package 5, §5.7) ------------------------------------------------------------------------

    /** A channel trigger's source: a company chat system; its one event, MENTIONED; its results, {@code review} only. */
    private Definition channelDefinition(UUID companyId, Draft draft, String name, String instruction,
            TriggerResults results) {
        if (draft.systemId() == null) {
            throw ApiException.validation("A CHANNEL trigger names its systemId, a chat system");
        }
        SystemInfo system = systems.find(companyId, draft.systemId())
            .filter(found -> !found.personal())
            .orElseThrow(() -> ApiException.validation("systemId is not a company system"));
        if (!system.kind().chat()) {
            throw ApiException.badRequest(ProblemCode.SYSTEM_KIND_INVALID, "A channel trigger belongs to a chat "
                    + "system: Slack or Microsoft Teams, not " + system.kind().label());
        }
        if (draft.scope() != null || !draft.conditions().isEmpty()
                || draft.events().stream().anyMatch(event -> event.event() != TriggerEvent.MENTIONED)) {
            throw TriggerRules.invalid("a channel trigger fires on mentions of the bot: no other event, scope or "
                    + "condition");
        }
        if (draft.channels().isEmpty() || draft.channels().size() > MAX_CHANNELS) {
            throw ApiException.validation("A channel trigger has 1 to " + MAX_CHANNELS + " channels");
        }
        Set<String> seen = new LinkedHashSet<>();
        for (ChannelInput channel : draft.channels()) {
            if (channel.channelId() == null || channel.channelId().isBlank() || channel.channelId().length() > 255
                    || !seen.add(channel.channelId())) {
                throw ApiException.validation("Each channel is named once, by its id at the platform");
            }
        }
        // A channel's results are posted in its thread: no comment or action of a tracker; its team may review them.
        TriggerResults checked = new TriggerResults(false, results.review(), List.of(), List.of());
        return new Definition(name, TriggerSource.CHANNEL, system, List.of(new TriggerEventSpec(TriggerEvent.MENTIONED,
                null)), TriggerSubject.ANY, null, List.of(), TriggerRepeat.EVERY_TIME, null, null, instruction, checked,
                draft.channels(), null);
    }

    // ---- a webhook trigger (package 5, §5.12) -------------------------------------------------------------------------

    /** A webhook trigger's events: at most this many mapped event names. */
    static final int MAX_WEBHOOK_EVENTS = 20;

    /**
     * A webhook trigger's source: a company {@code WEBHOOK} system; its events {@code DELIVERED}, 1 to 20 mapped event
     * names, or one without a value for any (none given: any); its conditions on the raw body (at most 5); its results,
     * {@code review} only. It needs no row on its source, and is open to anyone who can make the sender call it.
     */
    private Definition webhookDefinition(UUID companyId, Draft draft, String name, String instruction,
            TriggerResults results) {
        if (draft.systemId() == null) {
            throw ApiException.validation("A WEBHOOK trigger names its systemId, a webhook system");
        }
        SystemInfo system = systems.find(companyId, draft.systemId())
            .filter(found -> !found.personal())
            .orElseThrow(() -> ApiException.validation("systemId is not a company system"));
        if (system.kind() != SystemKind.WEBHOOK) {
            throw ApiException.badRequest(ProblemCode.SYSTEM_KIND_INVALID, "A webhook trigger belongs to a WEBHOOK "
                    + "system, not " + system.kind().label());
        }
        if (draft.scope() != null || !draft.conditions().isEmpty()) {
            throw TriggerRules.invalid("a webhook trigger has no scope, and its conditions are webhookConditions");
        }
        List<TriggerEventSpec> events = new ArrayList<>();
        Set<String> values = new LinkedHashSet<>();
        for (TriggerEventSpec event : draft.events()) {
            if (event.event() != TriggerEvent.DELIVERED) {
                throw TriggerRules.invalid("a webhook trigger fires on DELIVERED only");
            }
            String value = event.value() != null ? event.value().strip() : null;
            if (value == null || value.isEmpty()) {
                if (draft.events().size() > 1) {
                    throw TriggerRules.invalid("a webhook trigger's events each name a mapped event, or it has one "
                            + "DELIVERED without a value for any");
                }
                events.add(new TriggerEventSpec(TriggerEvent.DELIVERED, null));
                continue;
            }
            if (value.length() > WebhookMapping.EVENT || !values.add(value)) {
                throw TriggerRules.invalid("each mapped event is named once, in at most " + WebhookMapping.EVENT
                        + " characters");
            }
            events.add(new TriggerEventSpec(TriggerEvent.DELIVERED, value));
        }
        if (events.size() > MAX_WEBHOOK_EVENTS) {
            throw TriggerRules.invalid("a webhook trigger has at most " + MAX_WEBHOOK_EVENTS + " events");
        }
        if (events.isEmpty()) {
            events.add(new TriggerEventSpec(TriggerEvent.DELIVERED, null));
        }
        List<WebhookCondition> conditions = draft.webhookConditions();
        if (conditions.size() > WebhookCondition.MAX) {
            throw TriggerRules.invalid("a webhook trigger has at most " + WebhookCondition.MAX + " conditions");
        }
        for (WebhookCondition condition : conditions) {
            if (!pointer(condition.pointer())) {
                throw ApiException.badRequest(ProblemCode.MAPPING_INVALID, "A condition's pointer is not a JSON "
                        + "Pointer (RFC 6901): " + condition.pointer());
            }
            boolean valuesFit = switch (condition.op()) {
                case EQUALS -> condition.values().size() == 1;
                case IN -> !condition.values().isEmpty() && condition.values().size() <= 20;
                case PRESENT, ABSENT -> condition.values().isEmpty();
            };
            if (!valuesFit || condition.values().stream().anyMatch(value -> value.length() > 500)) {
                throw TriggerRules.invalid("a condition EQUALS one value, IN 1 to 20, and PRESENT or ABSENT none");
            }
        }
        TriggerResults checked = new TriggerResults(false, results.review(), List.of(), List.of());
        return new Definition(name, TriggerSource.WEBHOOK, system, List.copyOf(events), TriggerSubject.ANY, null,
                List.of(), TriggerRepeat.EVERY_TIME, null, null, instruction, checked, List.of(),
                "anyone who can make " + system.name() + " call its webhook", conditions);
    }

    /** Whether the text is a JSON Pointer (RFC 6901) of at most 500 characters. */
    static boolean pointer(String text) {
        if (text.length() > 500 || (!text.isEmpty() && text.charAt(0) != '/')) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '~' && (i + 1 >= text.length() || (text.charAt(i + 1) != '0'
                    && text.charAt(i + 1) != '1'))) {
                return false;
            }
        }
        return true;
    }

    /** §5.7's checks of the channels, for the author: the bot, each channel, the author in it, the claims. */
    private Definition channels(UUID companyId, Author author, Definition definition, Draft draft, AgentInfo agent,
            @Nullable TriggerInfo existing) {
        SystemInfo system = java.util.Objects.requireNonNull(definition.system());
        ChannelSources sources = channelSources.getIfAvailable();
        if (sources == null) {
            throw ApiException.badRequest(ProblemCode.SYSTEM_KIND_INVALID, "This server takes no chat systems");
        }
        if (sources.botWait(companyId, system.id()).filter(reason -> reason == WaitingReason.BOT_MISSING)
            .isPresent()) {
            throw ApiException.conflict(ProblemCode.BOT_NOT_SET, system.name() + " has no bot: set its bot first");
        }
        Map<String, ChannelSources.Channel> known = new HashMap<>();
        Set<String> ids = new LinkedHashSet<>();
        definition.channels().forEach(channel -> ids.add(channel.channelId()));
        sources.channels(companyId, system.id(), ids).forEach(channel -> known.put(channel.channelId(), channel));
        List<ChannelInput> named = new ArrayList<>();
        for (ChannelInput channel : definition.channels()) {
            ChannelSources.Channel found = known.get(channel.channelId());
            if (found == null || !found.available()) {
                throw ApiException.badRequest(ProblemCode.CHANNEL_UNAVAILABLE, "The channel " + (found != null
                        && found.name() != null ? "#" + found.name() : channel.channelId()) + " is not one the bot "
                        + "is in, or it is archived, deleted or shared with another organisation",
                        Map.of("details", Map.of("channelId", channel.channelId())));
            }
            named.add(new ChannelInput(channel.channelId(), channel.isDefault(), found.name()));
        }
        // The author's membership, as the platform said before the save: of every channel for a new author, else of
        // the channels this save adds (the saved ones were checked when they were added).
        Set<String> mustJoin = mustJoin(companyId, author, existing, definition);
        ChannelSources.Joined joined = draft.joined() != null ? draft.joined() : ChannelSources.Joined.NONE;
        if (!mustJoin.isEmpty() && !joined.linked()) {
            throw ApiException.badRequest(ProblemCode.CHANNEL_NOT_JOINED, "The trigger's author is not linked on "
                    + system.name() + ": they link their chat account first, by writing to the bot");
        }
        for (ChannelInput channel : named) {
            if (mustJoin.contains(channel.channelId()) && !joined.joined().contains(channel.channelId())) {
                throw ApiException.badRequest(ProblemCode.CHANNEL_NOT_JOINED, "The trigger's author is not a member "
                        + "of " + label(channel), Map.of("details", Map.of("channelId", channel.channelId())));
            }
        }
        Set<String> defaults = new LinkedHashSet<>();
        named.stream().filter(ChannelInput::isDefault).forEach(channel -> defaults.add(channel.channelId()));
        for (TriggerStore.ChannelClaim claim : store.channelClaims(companyId, system.id(), ids, agent.id(),
                existing != null ? existing.id() : null)) {
            if (claim.sameAgent()) {
                throw ApiException.conflict(ProblemCode.CHANNEL_TAKEN, agent.name() + " answers in the channel "
                        + claim.channelId() + " already, for the trigger " + claim.triggerName(),
                        Map.of("details", Map.of("channelId", claim.channelId(), "triggerId",
                                claim.triggerId().toString())));
            }
            if (claim.isDefault() && defaults.contains(claim.channelId())) {
                throw ApiException.conflict(ProblemCode.CHANNEL_TAKEN, "The channel " + claim.channelId() + " has a "
                        + "default trigger already: " + claim.triggerName(), Map.of("details", Map.of("channelId",
                                claim.channelId(), "triggerId", claim.triggerId().toString())));
            }
        }
        String workspace = sources.workspaceName(companyId, system.id()).orElse(system.name());
        String audience = "any member of " + workspace + " in " + String.join(", ", named.stream()
            .map(TriggerEditor::label)
            .toList());
        return new Definition(definition.name(), definition.source(), system, definition.events(),
                definition.subject(), null, List.of(), definition.repeat(), null, null, definition.instruction(),
                definition.results(), named, audience);
    }

    /**
     * The channels whose membership the save checks: all of them for a new trigger or another author than the saved
     * one (a reassignment), else those not saved yet.
     */
    Set<String> mustJoin(UUID companyId, Author author, @Nullable TriggerInfo existing, Definition definition) {
        Set<String> all = new LinkedHashSet<>();
        definition.channels().forEach(channel -> all.add(channel.channelId()));
        if (existing == null || existing.source() != TriggerSource.CHANNEL
                || !existing.authorPrincipalId().equals(author.principalId())) {
            return all;
        }
        store.channels(companyId, existing.id()).forEach(saved -> all.remove(saved.channelId()));
        return all;
    }

    private static String label(ChannelInput channel) {
        return channel.name() != null ? "#" + channel.name() : channel.channelId();
    }

    // ---- the one choice -------------------------------------------------------------------------------------------

    /**
     * A principal the editor's one choice offers, with its account on each system the trigger would need.
     *
     * @param noAccount the systems among them that need no account (ADR-0071): none is offered there, and none needed
     * @param models    the models its key may use ({@link ModelAccess#models}; empty: every model the gateway serves)
     */
    record Choice(PrincipalInfo principal, Map<UUID, Optional<AccountInfo>> accounts, Set<UUID> noAccount,
            RunModelAccess budget, List<String> models) {

        Choice {
            noAccount = Set.copyOf(noAccount);
            models = List.copyOf(models);
        }
    }

    /**
     * {@code TriggerEditor.fill(author, agentId, sourceSystemId, principalId)}'s choices (§5.2, the one choice): the
     * author and the service accounts they may use, each with its account per system the trigger needs (the source
     * and the agent's systems as the author would accept it now): the author's own, a service account's the credential
     * it borrows there (ADR-0072); its budget's state and its key's models. Service accounts first.
     */
    List<Choice> choices(UUID companyId, Author author, AgentInfo agent, @Nullable UUID sourceSystemId) {
        EffectiveAgent effective = agents.effective(companyId, agent.id(), agent.version()).orElseThrow();
        List<UUID> needed = new ArrayList<>();
        if (sourceSystemId != null) {
            needed.add(sourceSystemId);
        }
        for (AgentDefinition.SystemEntry system : effective.definition().systems()) {
            if (!needed.contains(system.systemId())) {
                needed.add(system.systemId());
            }
        }
        List<UUID> candidates = new ArrayList<>(principals.usableServiceIdentities(companyId, author.userId())
            .stream()
            .sorted()
            .toList());
        candidates.add(author.principalId());
        Map<UUID, PrincipalInfo> infos = principals.findAll(companyId, candidates);
        Set<UUID> noAccount = systems.findAll(companyId, needed)
            .values()
            .stream()
            .filter(system -> !system.accountsRequired())
            .map(SystemInfo::id)
            .collect(java.util.stream.Collectors.toSet());
        List<Choice> choices = new ArrayList<>();
        for (UUID candidate : candidates) {
            PrincipalInfo principal = infos.get(candidate);
            if (principal == null) {
                continue;
            }
            Map<UUID, Optional<AccountInfo>> perSystem = new LinkedHashMap<>();
            for (UUID systemId : needed) {
                Optional<AccountInfo> account = noAccount.contains(systemId) ? Optional.empty()
                        : principal.service()
                                ? accounts.lentTo(companyId, systemId, candidate).map(Accounts.Lent::credential)
                                : accounts.of(companyId, systemId, candidate);
                perSystem.put(systemId, account.filter(found -> tableMayHold(companyId, author, candidate, found)));
            }
            RunModelAccess budget = modelAccess.forRun(new RunModelContext(companyId, candidate,
                    ModelAccessChoice.BUDGET, agent.defaultRuntime(), Set.of()));
            choices.add(new Choice(principal, perSystem, noAccount, budget, modelAccess.models(companyId,
                    candidate)));
        }
        return choices;
    }

    /**
     * Whether the author's table may name the account, as {@link #rows} checks it on save: never a chat system's
     * {@code LINK} nor its bot (C9), so that a choice never fills a row the save refuses. One to sign in to again
     * stays, as {@code RECONNECT}.
     */
    private boolean tableMayHold(UUID companyId, Author author, UUID holderPrincipalId, AccountInfo account) {
        return !(accounts.bind(new AccountBinding.Trigger(companyId, author.principalId(), holderPrincipalId,
                account.id()), account.systemId()) instanceof AccountBinding.NotEntitled);
    }
}
