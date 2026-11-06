package com.flowtivo.tinfirm.model.internal;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.audit.AuditAction;
import com.flowtivo.tinfirm.audit.AuditLog;
import com.flowtivo.tinfirm.audit.AuditRecord;
import com.flowtivo.tinfirm.company.CompanyDirectory;
import com.flowtivo.tinfirm.model.ModelEvents;
import com.flowtivo.tinfirm.principal.PrincipalInfo;
import com.flowtivo.tinfirm.principal.PrincipalStatus;
import com.flowtivo.tinfirm.principal.Principals;
import com.flowtivo.tinfirm.shared.security.CurrentActor;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.tx.SystemTransactions;
import com.flowtivo.tinfirm.vault.SecretWriter;

/**
 * Brings one LiteLLM key to the state its holder and the company's settings ask for (the package 2 contract, §6; M1,
 * M6): a principal's key, or the company's own (ADR-0065, {@link KeyHolder}). It creates the key when it has none (or
 * LiteLLM lost it), changes its budget, period and models when they differ, blocks a principal's key while the
 * principal is suspended and unblocks it when it is resumed (the company's own key is never blocked), and reads its
 * spend and reset time. It is idempotent and reads the state when it starts, so it may run any number of times, in any
 * order: after a principal's change, after the settings changed, and in the reconciliation.
 * <p>
 * No transaction is held while LiteLLM is called: a first transaction plans (and takes the row's lease, so that no
 * other replica talks to LiteLLM about this key meanwhile), LiteLLM is called, and a second records what happened, as
 * actor SYSTEM, with its audit: {@code model_key.created}, {@code model_key.blocked}, {@code model_key.unblocked},
 * {@code model_key.budget_spent}. A failure leaves the key {@code ERROR} with LiteLLM's reason until the next sync. A
 * key LiteLLM created but Tinfirm could not record (its principal gone, the company's gateway changed meanwhile) is
 * deleted again.
 * <p>
 * A status recorded since the sync planned is newer than what it read of LiteLLM, and stays: but for a change of the
 * gateway, whose sync drops what it learned anyway, only the relay records one outside a sync's lease (a spent budget
 * or a refused key, from LiteLLM's answer to one of the key's requests), and it asks for a sync of its own, which reads
 * LiteLLM after it. Recording the older read would make a spent budget's key usable again until that sync, and the
 * payer's waiting runs would start on it.
 */
@Component
class KeySync {

    private static final Logger log = LoggerFactory.getLogger(KeySync.class);

    /** How long a sync may hold a key's row; a replica that died lets go after it. */
    static final Duration LEASE = Duration.ofMinutes(2);

    /**
     * How long a provided key whose budget the gateway reported spent, without saying when it resets, waits before the
     * relay tries it again (ADR-0094): its next refusal makes it spent again.
     */
    static final Duration PROVIDED_RETRY = Duration.ofMinutes(15);

    /** The kind the metadata of the company's own key names. */
    static final String COMPANY_KIND = "COMPANY";

    private static final double EPSILON = 0.000_001;

    private final SystemTransactions transactions;

    private final ModelSettingsStore settingsStore;

    private final ModelKeyStore keys;

    private final Principals principals;

    private final CompanyDirectory companies;

    private final Gateways gateways;

    private final GatewayAdapter adapter;

    private final SecretWriter secrets;

    private final AuditLog auditLog;

    private final ApplicationEventPublisher events;

    private final TimeSource time;

    private final BudgetAlertStore alerts;

    KeySync(SystemTransactions transactions, ModelSettingsStore settingsStore, ModelKeyStore keys,
            Principals principals, CompanyDirectory companies, Gateways gateways, GatewayAdapter adapter,
            SecretWriter secrets, AuditLog auditLog, ApplicationEventPublisher events, TimeSource time,
            BudgetAlertStore alerts) {
        this.alerts = alerts;
        this.transactions = transactions;
        this.settingsStore = settingsStore;
        this.keys = keys;
        this.principals = principals;
        this.companies = companies;
        this.gateways = gateways;
        this.adapter = adapter;
        this.secrets = secrets;
        this.auditLog = auditLog;
        this.events = events;
        this.time = time;
    }

    /** What a sync did. */
    enum Result {
        /** The key is as asked, or its failure recorded. */
        DONE,
        /** Another sync holds the key's row; try again later. */
        BUSY,
        /** Nothing to do: the company's gateway did not pass its test, or the key's principal is gone. */
        NOTHING
    }

    /** What the key should be. */
    record Desired(BigDecimal budgetUsd, String period, List<String> models, boolean blocked) {
    }

    /**
     * What LiteLLM's admin is to show of a key's holder, beside the ids that identify it: the company's name (the
     * team's alias, {@code tinfirm_company_name}) and, for a principal's key, the principal's display name
     * ({@code tinfirm_principal_name}; null when it has none, as a deleted user's member). The names as they were
     * when the key was made: a key's metadata is never changed ({@link GatewayAdapter}), a rotation's next key carries
     * the current ones.
     */
    record Names(String company, @Nullable String principal) {
    }

    /**
     * What a sync works on: the gateway and the key's alias, its id and secret as recorded, what it should be, its
     * holder's kind ({@code MEMBER}, {@code SERVICE}, or {@code COMPANY} for the company's own key) and names, and
     * the status it had.
     */
    private record Plan(Gateway gateway, String connection, String teamId, String alias, @Nullable String keyId,
            @Nullable UUID secretId, Desired desired, String kind, Names names, ModelKeyStore.KeyStatus previous) {
    }

    /** Syncs the holder's key now, on the caller's thread, which must hold no transaction. */
    Result sync(KeyHolder holder) {
        UUID companyId = holder.companyId();
        Plan plan = transactions.inCompany(companyId, () -> plan(holder));
        if (plan == null) {
            return busyOrNothing(holder);
        }
        GatewayAdapter.CreatedKey created = null;
        GatewayAdapter.KeyState state;
        try {
            ensureTeam(plan.gateway(), plan.teamId(), companyId, plan.names().company());
            state = plan.keyId() != null ? adapter.info(plan.gateway(), plan.keyId()).orElse(null) : null;
            if (state == null) {
                created = create(plan, holder);
                state = adapter.info(plan.gateway(), created.keyId()).orElse(null);
                if (state == null) {
                    throw new LiteLlmException(404, "LiteLLM does not know the key it just created");
                }
            }
            else {
                state = converge(plan, state);
            }
        }
        catch (LiteLlmException ex) {
            GatewayAdapter.CreatedKey made = created;
            if (made != null) {
                // The key exists in LiteLLM: keep it, with the error, so that the next sync does not create another.
                if (!recordCreated(holder, plan, made, null, ex.getMessage())) {
                    deleteQuietly(plan.gateway(), made.keyId());
                }
            }
            else {
                recordFailure(holder, plan, ex.getMessage());
            }
            return Result.DONE;
        }
        GatewayAdapter.KeyState confirmed = state;
        try {
            if (created != null) {
                if (!recordCreated(holder, plan, created, confirmed, null)) {
                    // The principal left or was deleted meanwhile, or the gateway changed: the key goes again.
                    deleteQuietly(plan.gateway(), created.keyId());
                }
            }
            else {
                transactions.runInCompany(companyId, () -> recordState(holder, plan, confirmed, null));
            }
        }
        catch (RuntimeException ex) {
            if (created != null) {
                // Tinfirm could not keep the key: it must not live on in LiteLLM without an owner.
                deleteQuietly(plan.gateway(), created.keyId());
            }
            throw ex;
        }
        return Result.DONE;
    }

    /** The plan of a sync, with the row's lease; null when there is nothing to do or another sync holds the row. */
    private @Nullable Plan plan(KeyHolder holder) {
        UUID companyId = holder.companyId();
        Optional<ModelSettingsStore.SettingsRow> settings = settingsStore.find(companyId);
        if (settings.isEmpty() || !settings.get().relayEnabled()) {
            return null;
        }
        // The principal's row before the key's (CONVENTIONS §10): inserting the key's row, or writing it twice, checks
        // the principal's row through its foreign key. The company's own key has no principal.
        Optional<PrincipalInfo> principal = Optional.empty();
        if (!holder.isCompany()) {
            principal = principals.lockShared(companyId, holder.requirePrincipal());
            if (principal.isEmpty() || principal.get().status() == PrincipalStatus.LEFT) {
                return null;
            }
        }
        Instant now = time.now();
        keys.insertPending(holder, now);
        if (settings.get().gateway() == ModelSettingsStore.GatewayKind.PROVIDED) {
            provided(holder, principal.orElse(null), now);
            return null;
        }
        if (!keys.claim(holder, now, now.plus(LEASE))) {
            return null;
        }
        ModelKeyStore.KeyRow row = keys.find(holder).orElseThrow();
        Optional<Gateway> gateway = gateways.of(settings.get());
        if (gateway.isEmpty()) {
            keys.recordState(holder, ModelKeyStore.KeyStatus.ERROR,
                    "The company's gateway cannot be reached: its settings are incomplete", null, null, null, now);
            return null;
        }
        Desired desired = desired(settings.get(), row, principal.orElse(null));
        // A key made again (LiteLLM lost it) keeps the alias of its generation: the alias of a later one may still be
        // the previous key's, which serves the grace of a rotation (KeyRotation).
        return new Plan(gateway.get(), connection(settings.get()), Gateway.teamId(companyId),
                holder.alias(row.rotation().generation()), row.hasKey() ? row.gatewayKeyId() : null, row.secretId(),
                desired, kind(principal.orElse(null)), names(companyId, principal.orElse(null)), row.status());
    }

    /**
     * The sync of a key its gateway team provides (ADR-0094), in the company's transaction, without a call to the
     * gateway: Tinfirm holds no admin key there. A holder without a key waits for one; a suspended principal's key is
     * {@code BLOCKED} here, since the relay is the only way Tinfirm's runs reach the gateway, and its team is asked to
     * revoke it there ({@code model_key.revoke_requested}); a resumed one's is {@code OK} again; a spent budget whose
     * reset the gateway did not tell is tried again after {@link #PROVIDED_RETRY}, one past its reset at once.
     */
    private void provided(KeyHolder holder, @Nullable PrincipalInfo principal, Instant now) {
        ModelKeyStore.KeyRow row = keys.lock(holder).orElseThrow();
        ModelKeyStore.KeyStatus status = row.status();
        String error = row.lastError();
        if (!row.hasKey()) {
            status = ModelKeyStore.KeyStatus.ERROR;
            error = ModelKeyStore.NOT_PROVIDED;
        }
        else if (principal != null && principal.status() == PrincipalStatus.SUSPENDED) {
            if (status != ModelKeyStore.KeyStatus.BLOCKED) {
                auditLog.record(holder
                    .audit(AuditRecord.company(holder.companyId(), AuditAction.MODEL_KEY_REVOKE_REQUESTED,
                            CurrentActor.actor()))
                    .detail("cause", "principal.suspended")
                    .detail("hint", row.provided() != null ? row.provided().hint() : null)
                    .build());
            }
            status = ModelKeyStore.KeyStatus.BLOCKED;
            error = null;
        }
        else if (status == ModelKeyStore.KeyStatus.BLOCKED) {
            status = ModelKeyStore.KeyStatus.OK;
        }
        else if (status == ModelKeyStore.KeyStatus.BUDGET_SPENT) {
            Instant resetsAt = row.budgetResetsAt();
            boolean due = resetsAt != null ? !resetsAt.isAfter(now)
                    : !row.updatedAt().plus(PROVIDED_RETRY).isAfter(now);
            if (due) {
                status = ModelKeyStore.KeyStatus.OK;
            }
        }
        if (status != row.status() || !Objects.equals(error, row.lastError())) {
            keys.recordStatus(holder, status, error, null, now);
            statusChanged(holder, row.status(), status);
        }
    }

    /** What the key's metadata names as its holder's kind. */
    static String kind(@Nullable PrincipalInfo principal) {
        return principal != null ? principal.kind().name() : COMPANY_KIND;
    }

    /**
     * The names LiteLLM's admin is to show of the holder ({@link Names}), as they are now; runs in a transaction of
     * the company. A company that is gone keeps its team's id for a name.
     */
    Names names(UUID companyId, @Nullable PrincipalInfo principal) {
        String company = companies.find(companyId)
            .map(CompanyDirectory.CompanySummary::name)
            .orElseGet(() -> Gateway.teamId(companyId));
        String name = principal != null ? principals.displayNames(companyId, List.of(principal.id()))
            .getOrDefault(principal.id(), principal.name()) : null;
        return new Names(company, name);
    }

    /**
     * Which gateway the settings name: its kind and URL. A key made at one gateway means nothing at another, so what a
     * sync learned is dropped when the settings moved to another gateway while it talked to the old one (the settings
     * forgot the keys and turned the relay off meanwhile).
     */
    static String connection(ModelSettingsStore.SettingsRow settings) {
        return settings.gateway().name() + "|" + Objects.toString(settings.adminUrl(), "");
    }

    /** Whether the company's settings still name the plan's gateway; runs in a transaction of the company. */
    private boolean sameGateway(UUID companyId, Plan plan) {
        return settingsStore.find(companyId).map(KeySync::connection).filter(plan.connection()::equals).isPresent();
    }

    /** Whether the plan was refused for a held lease, or because there is nothing to do. */
    private Result busyOrNothing(KeyHolder holder) {
        return transactions.inCompany(holder.companyId(), () -> {
            Optional<ModelKeyStore.KeyRow> row = keys.find(holder);
            boolean leased = row.isPresent() && row.get().syncLeaseUntil() != null
                    && row.get().syncLeaseUntil().isAfter(time.now());
            return leased ? Result.BUSY : Result.NOTHING;
        });
    }

    /**
     * What the key should be: its own overrides or the company's defaults, blocked while its principal is suspended
     * (the company's own key, without a principal, never is).
     */
    static Desired desired(ModelSettingsStore.SettingsRow settings, ModelKeyStore.KeyRow row,
            @Nullable PrincipalInfo principal) {
        BigDecimal budget = row.budgetUsd() != null ? row.budgetUsd() : settings.defaultBudgetUsd();
        List<String> models = row.models() != null ? row.models() : settings.defaultModels();
        return new Desired(budget, settings.budgetPeriod(), models,
                principal != null && principal.status() == PrincipalStatus.SUSPENDED);
    }

    /**
     * Makes sure of the company's team at the gateway, named after the company, once per process (and once more
     * after a rename: every sync passes here, so a new name reaches LiteLLM within the reconciliation's interval).
     */
    private void ensureTeam(Gateway gateway, String teamId, UUID companyId, String companyName) {
        gateways.ensureTeam(gateway, teamId, companyName,
                () -> adapter.ensureTeam(gateway, teamId, companyId, companyName));
    }


    private GatewayAdapter.CreatedKey create(Plan plan, KeyHolder holder) {
        return generate(plan.gateway(),
                keySpec(plan.alias(), plan.teamId(), holder, plan.kind(), plan.names(), plan.desired(), null),
                holder.companyId(), plan.names().company());
    }

    /**
     * What a key is created with: the alias, the company's team, the budget, period and models its holder and the
     * settings ask for, Tinfirm's metadata (the company and its name, the principal of a principal's key and its
     * name, the holder's kind), blocked for a suspended principal, and the spend it starts with (a rotation's next
     * key; null for none).
     */
    static GatewayAdapter.KeySpec keySpec(String alias, String teamId, KeyHolder holder, String kind, Names names,
            Desired desired, @Nullable BigDecimal spend) {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("tinfirm_company", holder.companyId().toString());
        metadata.put("tinfirm_company_name", names.company());
        if (!holder.isCompany()) {
            metadata.put("tinfirm_principal", holder.requirePrincipal().toString());
            if (names.principal() != null) {
                metadata.put("tinfirm_principal_name", names.principal());
            }
        }
        metadata.put("tinfirm_kind", kind);
        return new GatewayAdapter.KeySpec(alias, teamId, desired.budgetUsd(), desired.period(), desired.models(),
                metadata, desired.blocked(), spend);
    }

    /**
     * Creates the key in LiteLLM. A 400 is a key of the alias left over from a sync or rotation that could not record
     * it (the alias is unique in LiteLLM), or a team LiteLLM lost: both are cleared and the key is asked for once more.
     * The alias is never the one of a key a row holds.
     */
    GatewayAdapter.CreatedKey generate(Gateway gateway, GatewayAdapter.KeySpec spec, UUID companyId,
            String companyName) {
        ensureTeam(gateway, spec.teamId(), companyId, companyName);
        try {
            return adapter.generate(gateway, spec);
        }
        catch (LiteLlmException ex) {
            if (ex.status() == 400) {
                deleteAliasQuietly(gateway, spec.alias());
                gateways.teamUnknown(gateway, spec.teamId());
                ensureTeam(gateway, spec.teamId(), companyId, companyName);
                return adapter.generate(gateway, spec);
            }
            throw ex;
        }
    }

    /** Changes what differs from the plan in LiteLLM; the key's state as LiteLLM then knows it. */
    private GatewayAdapter.KeyState converge(Plan plan, GatewayAdapter.KeyState state) {
        Desired desired = plan.desired();
        String keyId = Objects.requireNonNull(plan.keyId());
        BigDecimal budget = state.maxBudget() == null
                || Math.abs(state.maxBudget() - desired.budgetUsd().doubleValue()) > EPSILON ? desired.budgetUsd()
                        : null;
        // A new period restarts it in LiteLLM, so it is sent only when it changed.
        String period = desired.period().equals(state.period()) ? null : desired.period();
        List<String> models = new HashSet<>(desired.models()).equals(new HashSet<>(state.models())) ? null
                : desired.models();
        GatewayAdapter.KeyUpdate update = new GatewayAdapter.KeyUpdate(budget, period, models);
        boolean changed = !update.empty();
        adapter.update(plan.gateway(), keyId, update);
        if (desired.blocked() && !state.blocked()) {
            adapter.block(plan.gateway(), keyId);
            changed = true;
        }
        else if (!desired.blocked() && state.blocked()) {
            adapter.unblock(plan.gateway(), keyId);
            changed = true;
        }
        return changed ? adapter.info(plan.gateway(), keyId)
            .orElseThrow(() -> new LiteLlmException(404, "LiteLLM lost the key while it was changed")) : state;
    }

    /**
     * Keeps the key LiteLLM created in an internal secret, with its state; false when the key's row is gone (its
     * principal left or was deleted meanwhile), so that the caller deletes the key again, outside the transaction.
     */
    private boolean recordCreated(KeyHolder holder, Plan plan, GatewayAdapter.CreatedKey created,
            GatewayAdapter.@Nullable KeyState state, @Nullable String error) {
        UUID companyId = holder.companyId();
        return transactions.inCompany(companyId, () -> {
            Optional<ModelKeyStore.KeyRow> row = lock(holder);
            if (row.isEmpty()) {
                return false;
            }
            if (!sameGateway(companyId, plan)) {
                keys.release(holder);
                return false;
            }
            UUID secretId = secrets.createInternal(companyId, holder.secretName(), holder.secretDescription(),
                    created.key());
            keys.recordKey(holder, secretId, created.keyId(), time.now());
            if (row.get().secretId() != null) {
                secrets.deleteInternal(companyId, row.get().secretId());
            }
            auditLog.record(holder
                .audit(AuditRecord.company(companyId, AuditAction.MODEL_KEY_CREATED, CurrentActor.actor()))
                .detail("blocked", plan.desired().blocked())
                .build());
            if (state != null) {
                recordState(holder, plan, state, null);
            }
            else {
                keys.recordState(holder, ModelKeyStore.KeyStatus.ERROR, error, null, null, null, time.now());
                statusChanged(holder, plan.previous(), ModelKeyStore.KeyStatus.ERROR);
            }
            return true;
        });
    }

    /**
     * The key's row, locked after its principal's (CONVENTIONS §10: the principal's row, then its key's, then the audit
     * chain head); empty when either is gone. A sync writes the key's row twice when it makes the key, and records a
     * budget's crossed threshold, and each checks the principal's row again through a foreign key: under the key's row,
     * the first even after this transaction's audit records. A service identity's deletion (its row FOR UPDATE, then its
     * key) waited on such a sync while the sync waited on it, and so did a change of its users or status and a member's
     * leaving while they locked the row FOR UPDATE. Locked first, the principal's row makes the sync wait for a deletion
     * at its start instead. The company's own key has no principal: its row is locked alone.
     */
    private Optional<ModelKeyStore.KeyRow> lock(KeyHolder holder) {
        if (!holder.isCompany() && principals.lockShared(holder.companyId(), holder.requirePrincipal()).isEmpty()) {
            return Optional.empty();
        }
        return keys.lock(holder);
    }

    /**
     * Records the key's state; runs in a transaction of the company. A status recorded since the plan (the relay's)
     * stays, and the sync records nothing else: the sync the relay asked for reads the key again.
     */
    private void recordState(KeyHolder holder, Plan plan, GatewayAdapter.KeyState state, @Nullable String error) {
        Optional<ModelKeyStore.KeyRow> current = lock(holder);
        if (current.isEmpty()) {
            return;
        }
        if (!sameGateway(holder.companyId(), plan) || recordedMeanwhile(current.get(), plan)) {
            keys.release(holder);
            return;
        }
        Instant now = time.now();
        ModelKeyStore.KeyStatus status = status(plan.desired(), state);
        BigDecimal spent = BigDecimal.valueOf(state.spend());
        keys.recordState(holder, status, error, spent, state.budgetResetAt(), now, now);
        if (!holder.isCompany()) {
            // Package 6 (§5.3): a threshold the period's spend crossed is recorded once per period.
            alerts.check(holder.companyId(), holder.requirePrincipal(), spent, plan.desired().budgetUsd(),
                    state.budgetResetAt());
        }
        ModelKeyStore.KeyStatus previous = plan.previous();
        boolean hadKey = plan.keyId() != null;
        if (hadKey && previous != ModelKeyStore.KeyStatus.BLOCKED && status == ModelKeyStore.KeyStatus.BLOCKED) {
            audit(holder, AuditAction.MODEL_KEY_BLOCKED, null);
        }
        if (hadKey && previous == ModelKeyStore.KeyStatus.BLOCKED && status != ModelKeyStore.KeyStatus.BLOCKED) {
            audit(holder, AuditAction.MODEL_KEY_UNBLOCKED, null);
        }
        if (previous != ModelKeyStore.KeyStatus.BUDGET_SPENT && status == ModelKeyStore.KeyStatus.BUDGET_SPENT) {
            audit(holder, AuditAction.MODEL_KEY_BUDGET_SPENT, state.budgetResetAt());
        }
        statusChanged(holder, previous, status);
    }

    /**
     * The status that follows from LiteLLM's state: {@code BLOCKED} while the principal is suspended,
     * {@code BUDGET_SPENT} when the period's spend reached the budget, else {@code OK}.
     */
    static ModelKeyStore.KeyStatus status(Desired desired, GatewayAdapter.KeyState state) {
        if (desired.blocked()) {
            return ModelKeyStore.KeyStatus.BLOCKED;
        }
        if (state.maxBudget() != null && state.spend() + EPSILON >= state.maxBudget()) {
            return ModelKeyStore.KeyStatus.BUDGET_SPENT;
        }
        return ModelKeyStore.KeyStatus.OK;
    }

    private void recordFailure(KeyHolder holder, Plan plan, @Nullable String error) {
        transactions.runInCompany(holder.companyId(), () -> {
            Optional<ModelKeyStore.KeyRow> row = lock(holder);
            if (row.isEmpty()) {
                return;
            }
            if (!sameGateway(holder.companyId(), plan) || recordedMeanwhile(row.get(), plan)) {
                keys.release(holder);
                return;
            }
            keys.recordState(holder, ModelKeyStore.KeyStatus.ERROR, error, null, null, null, time.now());
            statusChanged(holder, row.get().status(), ModelKeyStore.KeyStatus.ERROR);
        });
        log.warn("The LiteLLM key of {} could not be synced: {}", holder, error);
    }

    /**
     * Whether a status was recorded since the plan: by the relay, from LiteLLM's answer to one of the key's requests,
     * the only writer of a status outside a sync's lease. It is newer than what this sync read of LiteLLM.
     */
    private static boolean recordedMeanwhile(ModelKeyStore.KeyRow row, Plan plan) {
        if (row.status() == plan.previous()) {
            return false;
        }
        log.debug("The LiteLLM key of {} became {} while a sync that read it as {} talked to LiteLLM; it stays {}",
                row.holder(), row.status(), plan.previous(), row.status());
        return true;
    }

    private void audit(KeyHolder holder, AuditAction action, @Nullable Instant resetsAt) {
        AuditRecord.Builder audit = holder
            .audit(AuditRecord.company(holder.companyId(), action, CurrentActor.actor()));
        if (resetsAt != null) {
            audit.detail("resetsAt", resetsAt.toString());
        }
        auditLog.record(audit.build());
    }

    /**
     * A principal's key whose status changed wakes the runs and triggers that wait on it; nothing waits on the
     * company's own key.
     */
    private void statusChanged(KeyHolder holder, ModelKeyStore.KeyStatus previous, ModelKeyStore.KeyStatus status) {
        if (previous != status && !holder.isCompany()) {
            events.publishEvent(new ModelEvents.ModelKeyStatusChanged(holder.companyId(), holder.requirePrincipal(),
                    status.name()));
        }
    }

    private void deleteQuietly(Gateway gateway, String keyId) {
        try {
            adapter.delete(gateway, keyId);
        }
        catch (LiteLlmException ex) {
            log.warn("A LiteLLM key Tinfirm could not keep was not deleted ({}); the reconciliation deletes it",
                    ex.getMessage());
        }
    }

    private void deleteAliasQuietly(Gateway gateway, String alias) {
        try {
            adapter.deleteByAlias(gateway, alias);
        }
        catch (LiteLlmException ex) {
            log.debug("No key of the alias was deleted: {}", ex.getMessage());
        }
    }
}
