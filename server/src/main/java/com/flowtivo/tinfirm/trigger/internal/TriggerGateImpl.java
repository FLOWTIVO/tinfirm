package com.flowtivo.tinfirm.trigger.internal;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.flowtivo.tinfirm.account.AccountBinding;
import com.flowtivo.tinfirm.account.Accounts;
import com.flowtivo.tinfirm.agent.AgentDefinition;
import com.flowtivo.tinfirm.agent.AgentInfo;
import com.flowtivo.tinfirm.agent.Agents;
import com.flowtivo.tinfirm.agent.EffectiveSettings;
import com.flowtivo.tinfirm.agent.Settings;
import com.flowtivo.tinfirm.agent.SettingsOwner;
import com.flowtivo.tinfirm.company.CompanyDirectory;
import com.flowtivo.tinfirm.company.CompanySwitches;
import com.flowtivo.tinfirm.model.ModelAccess;
import com.flowtivo.tinfirm.model.ModelAccessChoice;
import com.flowtivo.tinfirm.model.RunModelAccess;
import com.flowtivo.tinfirm.model.RunModelContext;
import com.flowtivo.tinfirm.principal.PrincipalInfo;
import com.flowtivo.tinfirm.principal.PrincipalStatus;
import com.flowtivo.tinfirm.principal.Principals;
import com.flowtivo.tinfirm.shared.access.CompanyRole;
import com.flowtivo.tinfirm.system.SystemInfo;
import com.flowtivo.tinfirm.system.Systems;
import com.flowtivo.tinfirm.trigger.ChannelSources;
import com.flowtivo.tinfirm.trigger.PausedReason;
import com.flowtivo.tinfirm.trigger.TriggerAccount;
import com.flowtivo.tinfirm.trigger.TriggerGate;
import com.flowtivo.tinfirm.trigger.TriggerInfo;
import com.flowtivo.tinfirm.trigger.TriggerSource;
import com.flowtivo.tinfirm.trigger.WaitingReason;

/**
 * {@link TriggerGate} (the package 4 contract, §5.3 with §11): the trigger's author, its use of the agent, each row of
 * its table, its payer and the payer's model access, checked again at every use. Nothing re-binds and nothing switches
 * (T7). In this order:
 * <table>
 * <caption>The gate</caption>
 * <tr><th>Check</th><th>Fails as</th><th>The trigger</th></tr>
 * <tr><td>The author is an active member</td><td>left</td><td>pauses {@code AUTHOR_LEFT}</td></tr>
 * <tr><td></td><td>suspended</td><td>pauses {@code NOT_ENTITLED}</td></tr>
 * <tr><td>The author holds a seat: OWNER, ADMIN or MEMBER</td><td>a VIEWER, or no role (the security review of
 * 2026-10-27, M7)</td><td>pauses {@code NOT_ENTITLED}</td></tr>
 * <tr><td>The author uses the agent</td><td>no longer shared with them</td><td>pauses {@code NOT_ENTITLED}</td></tr>
 * <tr><td>Each row: {@code Accounts.check(Stored(…, author, no grant))}</td><td>{@code USE_RIGHT_GONE},
 * {@code PRINCIPAL_INACTIVE} of the author</td><td>pauses {@code NOT_ENTITLED}</td></tr>
 * <tr><td></td><td>no account, {@code IDENTITY_CHANGED}, the holder left</td><td>pauses
 * {@code ACCOUNT_REMOVED}</td></tr>
 * <tr><td></td><td>{@code SYSTEM_GONE}</td><td>the source's: deleted; another's: pauses {@code SYSTEM_REMOVED}, the row
 * removed</td></tr>
 * <tr><td></td><td>{@code RECONNECT_YOURS}, {@code RECONNECT_COMPANY}, the holder suspended</td><td>pauses
 * {@code ACCOUNT_BROKEN} (§11, open point 9)</td></tr>
 * <tr><td>The payer: the author, or {@code Principals.mayUse}</td><td>lost</td><td>pauses
 * {@code NOT_ENTITLED}</td></tr>
 * <tr><td>{@code ModelAccess.forRun}</td><td>{@code Refuse(PRINCIPAL_INACTIVE)},
 * {@code Refuse(GATEWAY_NOT_CONFIGURED)}</td><td>waits {@code SUSPENDED}, {@code GATEWAY_NOT_CONFIGURED}</td></tr>
 * <tr><td></td><td>{@code Refuse(SUBSCRIPTION_GONE)} and the others</td><td>pauses {@code NOT_ENTITLED}</td></tr>
 * <tr><td></td><td>{@code Wait}</td><td>fires; the turn waits</td></tr>
 * <tr><td>{@code CompanySwitches.runsStopped} (package 6, open point 7), once nothing above pauses it</td><td>the
 * company's runs are stopped</td><td>waits {@code COMPANY_STOPPED}; the lift arms it again</td></tr>
 * </table>
 * A row on a system an admin paused ({@code Accounts.check} answers {@code SYSTEM_PAUSED}, package 6, open point 6)
 * pauses the trigger {@code SYSTEM_PAUSED}; the system's resume resumes it through this gate. A channel or webhook
 * trigger's source system paused does too: it has no row there (package 5).
 * <p>
 * A channel trigger (package 5, §5.14, open point 21), once nothing above stops it: its chat system's bot that cannot be
 * used makes it wait ({@code BOT_MISSING}, {@code BOT_RECONNECT}, {@code BOT_SUSPENDED}), as every channel of it
 * unavailable does ({@code CHANNEL_UNAVAILABLE}); it goes on by itself when the gate passes again (a deliberate
 * exception to decision 9: the bot carries no authority).
 * A paused trigger stays paused until a person resumes it and the gate passes; a waiting one goes on by itself when the
 * gate passes again.
 */
@Component
class TriggerGateImpl implements TriggerGate {

    private final TriggerStore store;

    private final TriggerStates states;

    private final Principals principals;

    private final Agents agents;

    private final Accounts accounts;

    private final Settings settings;

    private final ModelAccess modelAccess;

    private final CompanySwitches companySwitches;

    private final Systems systems;

    private final ObjectProvider<ChannelSources> channelSources;

    private final CompanyDirectory companies;

    TriggerGateImpl(TriggerStore store, TriggerStates states, Principals principals, Agents agents,
            Accounts accounts, Settings settings, ModelAccess modelAccess, CompanySwitches companySwitches,
            Systems systems, ObjectProvider<ChannelSources> channelSources, CompanyDirectory companies) {
        this.companies = companies;
        this.companySwitches = companySwitches;
        this.systems = systems;
        this.channelSources = channelSources;
        this.store = store;
        this.states = states;
        this.principals = principals;
        this.agents = agents;
        this.accounts = accounts;
        this.settings = settings;
        this.modelAccess = modelAccess;
    }

    /** What the checks find, before anything is changed. */
    sealed interface Verdict {

        record Passes() implements Verdict {
        }

        /** The trigger pauses for the reason; {@code systemId} names a row to remove ({@code SYSTEM_REMOVED}). */
        record Pause(PausedReason reason, String detail, @Nullable UUID systemId) implements Verdict {
        }

        record Wait(WaitingReason reason, String detail) implements Verdict {
        }

        /** The source system is gone: the trigger is deleted ({@code system.deleted}). */
        record Delete(String cause) implements Verdict {
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Result check(UUID companyId, UUID triggerId) {
        TriggerInfo trigger = store.lock(companyId, triggerId).orElse(null);
        if (trigger == null || !trigger.live()) {
            return new Result.Gone();
        }
        if (trigger.paused()) {
            return new Result.Paused(Objects.requireNonNull(trigger.pausedReason()));
        }
        return apply(trigger, verdict(trigger));
    }

    /** Applies a verdict to the locked trigger. */
    Result apply(TriggerInfo trigger, Verdict verdict) {
        return switch (verdict) {
            case Verdict.Passes passes -> {
                states.repaired(trigger);
                yield new Result.Passes();
            }
            case Verdict.Wait wait -> {
                states.waiting(trigger, wait.reason());
                yield new Result.Waits(wait.reason());
            }
            case Verdict.Pause pause -> {
                if (pause.systemId() != null) {
                    store.deleteRow(trigger.companyId(), trigger.id(), pause.systemId());
                }
                states.pause(trigger, pause.reason(), null, pause.reason() == PausedReason.AUTHOR_LEFT);
                yield new Result.Paused(pause.reason());
            }
            case Verdict.Delete delete -> {
                states.delete(trigger, delete.cause(), null);
                yield new Result.Gone();
            }
        };
    }

    /**
     * §5.3's checks of the trigger as it is, changing nothing. A trigger that would fire, or wait, waits
     * {@code COMPANY_STOPPED} while the company's runs are stopped; what pauses it pauses it all the same.
     */
    Verdict verdict(TriggerInfo trigger) {
        Verdict verdict = entitled(trigger);
        if (!(verdict instanceof Verdict.Pause) && !(verdict instanceof Verdict.Delete)
                && companySwitches.runsStopped(trigger.companyId()).isPresent()) {
            return new Verdict.Wait(WaitingReason.COMPANY_STOPPED, "The company's runs are stopped by an admin");
        }
        return verdict;
    }

    private Verdict entitled(TriggerInfo trigger) {
        UUID companyId = trigger.companyId();
        PrincipalInfo author = principals.find(companyId, trigger.authorPrincipalId()).orElse(null);
        if (author == null || author.status() == PrincipalStatus.LEFT || author.userId() == null) {
            return new Verdict.Pause(PausedReason.AUTHOR_LEFT, "The author left the company", null);
        }
        if (!author.active()) {
            return new Verdict.Pause(PausedReason.NOT_ENTITLED, "The author is suspended", null);
        }
        // M7: a trigger's author holds a seat (ADR-0077, decision 5); a VIEWER authors none, whoever demoted them.
        if (!holdsSeat(companies.roleOf(companyId, author.userId()).orElse(null))) {
            return new Verdict.Pause(PausedReason.NOT_ENTITLED, "The author is a Viewer, who authors no trigger",
                    null);
        }
        AgentInfo agent = agents.find(companyId, trigger.agentId()).filter(AgentInfo::live).orElse(null);
        if (agent == null) {
            return new Verdict.Delete("agent.deleted");
        }
        if (!agents.access(companyId, agent.id(), author.userId()).uses()) {
            return new Verdict.Pause(PausedReason.NOT_ENTITLED, "The author may no longer use " + agent.name(), null);
        }
        List<TriggerAccount> rows = store.accounts(companyId, trigger.id());
        boolean sourceWithoutRow = trigger.source() == TriggerSource.CHANNEL
                || trigger.source() == TriggerSource.WEBHOOK;
        if (sourceWithoutRow && trigger.systemId() != null) {
            SystemInfo source = systems.find(companyId, trigger.systemId()).orElse(null);
            if (source == null) {
                return new Verdict.Delete("system.deleted");
            }
            if (source.paused()) {
                return new Verdict.Pause(PausedReason.SYSTEM_PAUSED, "Its system is paused", null);
            }
        }
        if (trigger.source() == TriggerSource.TRACKER && rows.stream()
            .noneMatch(row -> row.systemId().equals(trigger.systemId()))) {
            return new Verdict.Pause(PausedReason.ACCOUNT_REMOVED, "The source system has no account", null);
        }
        for (TriggerAccount row : rows) {
            Optional<Verdict> refused = row(trigger, author, row);
            if (refused.isPresent()) {
                return refused.get();
            }
        }
        UUID payer = trigger.payerPrincipalId();
        if (payer == null || !payer.equals(author.id()) && !principals.mayUse(companyId, author.userId(), payer)) {
            return new Verdict.Pause(PausedReason.NOT_ENTITLED, "The author may no longer use the payer's budget",
                    null);
        }
        EffectiveSettings effective;
        try {
            effective = settings.effective(companyId, agent.id(), new SettingsOwner.Trigger(trigger.id()));
        }
        catch (IllegalArgumentException ex) {
            return new Verdict.Pause(PausedReason.NOT_ENTITLED, "The trigger's settings cannot be read", null);
        }
        Optional<ModelAccessChoice> choice = effective.modelAccess().choice();
        if (choice.isEmpty()) {
            return new Verdict.Pause(PausedReason.NOT_ENTITLED, "The subscription that paid is gone", null);
        }
        // The company's switch (ADR-0085, decision 7): a trigger set while it was on pauses once it is off.
        if (choice.get() instanceof ModelAccessChoice.Subscription && !store.subscriptionsOn(companyId)) {
            return new Verdict.Pause(PausedReason.NOT_ENTITLED, "The company lets a trigger pay only with a budget: "
                    + "choose a model under the budget in the trigger's settings", null);
        }
        RunModelContext context = new RunModelContext(companyId, payer, choice.get(), effective.runtime(),
                holders(effective, rows));
        RunModelAccess payment = modelAccess.forRun(context);
        if (payment instanceof RunModelAccess.Budget budget) {
            // No model, or one the key no longer allows: every firing would fail the same way.
            RunModelAccess.Refuse refuse = budget.refusal(effective.model());
            if (refuse != null) {
                return new Verdict.Pause(PausedReason.NOT_ENTITLED, refuse.detail(), null);
            }
        }
        if (payment instanceof RunModelAccess.Refuse refuse) {
            return switch (refuse.reason()) {
                case PRINCIPAL_INACTIVE -> new Verdict.Wait(WaitingReason.SUSPENDED, refuse.detail());
                case GATEWAY_NOT_CONFIGURED -> new Verdict.Wait(WaitingReason.GATEWAY_NOT_CONFIGURED,
                        refuse.detail());
                default -> new Verdict.Pause(PausedReason.NOT_ENTITLED, refuse.detail(), null);
            };
        }
        if (trigger.source() == TriggerSource.CHANNEL && trigger.systemId() != null) {
            return channels(trigger);
        }
        return new Verdict.Passes();
    }

    /** A channel trigger's waits (package 5, open point 21): on its chat system's bot, on its channels. */
    private Verdict channels(TriggerInfo trigger) {
        ChannelSources sources = channelSources.getIfAvailable();
        if (sources != null) {
            Optional<WaitingReason> bot = sources.botWait(trigger.companyId(), Objects.requireNonNull(
                    trigger.systemId()));
            if (bot.isPresent()) {
                return new Verdict.Wait(bot.get(), "Its chat system's bot cannot be used");
            }
        }
        List<TriggerStore.ChannelRow> channels = store.channels(trigger.companyId(), trigger.id());
        if (!channels.isEmpty() && channels.stream().noneMatch(TriggerStore.ChannelRow::available)) {
            return new Verdict.Wait(WaitingReason.CHANNEL_UNAVAILABLE, "Every channel of it is unavailable");
        }
        return new Verdict.Passes();
    }

    /** One row through {@code Accounts.check}, with the author acting and no grant (T1). */
    private Optional<Verdict> row(TriggerInfo trigger, PrincipalInfo author, TriggerAccount row) {
        boolean source = row.systemId().equals(trigger.systemId());
        if (row.accountId() == null) {
            return Optional.of(new Verdict.Pause(PausedReason.ACCOUNT_REMOVED, "An account of the trigger was removed",
                    null));
        }
        AccountBinding.Check check = accounts.check(new AccountBinding.Stored(trigger.companyId(), row.systemId(),
                author.id(), row.holderPrincipalId(), row.accountId(), null, row.externalId()));
        if (check instanceof AccountBinding.Usable) {
            return Optional.empty();
        }
        AccountBinding.StopReason reason = ((AccountBinding.Stopped) check).reason();
        return Optional.of(switch (reason) {
            // USE_RIGHT_GONE: the author may no longer use the service account, or it no longer borrows the
            // credential the row names (ADR-0072: withdrawn; a replaced one changed the row in its transaction).
            case USE_RIGHT_GONE, GRANT_GONE, NOT_IN_GROUP, PERSONAL_SYSTEMS_OFF -> new Verdict.Pause(
                    PausedReason.NOT_ENTITLED, "The author may no longer use an account of the trigger", null);
            case IDENTITY_CHANGED -> new Verdict.Pause(PausedReason.ACCOUNT_REMOVED,
                    "An account of the trigger is another identity now", null);
            case SYSTEM_GONE -> source ? new Verdict.Delete("system.deleted")
                    : new Verdict.Pause(PausedReason.SYSTEM_REMOVED, "A system of the trigger was removed",
                            row.systemId());
            // ACCOUNT_REQUIRED stops a binding without an account only (Accounts.open), never a row's account.
            case RECONNECT_YOURS, ACCOUNT_REQUIRED -> new Verdict.Pause(PausedReason.ACCOUNT_BROKEN,
                    "An account of the trigger needs signing in again", null);
            case RECONNECT_COMPANY -> new Verdict.Pause(PausedReason.ACCOUNT_BROKEN,
                    "A credential of the trigger needs an admin: to sign it in again, or to enable it", null);
            case PRINCIPAL_INACTIVE -> holderGone(trigger, author, row);
            case SYSTEM_PAUSED -> new Verdict.Pause(PausedReason.SYSTEM_PAUSED, "A system of the trigger is paused",
                    null);
        });
    }

    /** {@code PRINCIPAL_INACTIVE}: the author (not entitled), a holder who left (removed) or is suspended (broken). */
    private Verdict holderGone(TriggerInfo trigger, PrincipalInfo author, TriggerAccount row) {
        if (row.holderPrincipalId().equals(author.id())) {
            return new Verdict.Pause(PausedReason.NOT_ENTITLED, "The author is not active", null);
        }
        PrincipalInfo holder = principals.find(trigger.companyId(), row.holderPrincipalId()).orElse(null);
        if (holder == null || holder.status() == PrincipalStatus.LEFT) {
            return new Verdict.Pause(PausedReason.ACCOUNT_REMOVED, "An account's holder is gone", null);
        }
        return new Verdict.Pause(PausedReason.ACCOUNT_BROKEN, "An account's holder is suspended", null);
    }

    /** Whether the role holds a seat, as a trigger's author must: OWNER, ADMIN or MEMBER, never VIEWER (M7). */
    static boolean holdsSeat(@Nullable CompanyRole role) {
        return role != null && role != CompanyRole.VIEWER;
    }

    /** The holders of the git accounts the workspace would carry: the rows on the code hosts of its repositories. */
    static Set<UUID> holders(EffectiveSettings effective, List<TriggerAccount> rows) {
        Set<UUID> systems = new LinkedHashSet<>();
        for (AgentDefinition.RepositoryEntry repository : effective.repositories()) {
            systems.add(repository.systemId());
        }
        Set<UUID> holders = new LinkedHashSet<>();
        for (TriggerAccount row : rows) {
            if (systems.contains(row.systemId())) {
                holders.add(row.holderPrincipalId());
            }
        }
        return holders;
    }
}
