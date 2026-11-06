package com.flowtivo.tinfirm.model.internal;

import java.io.Serial;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.audit.AuditAction;
import com.flowtivo.tinfirm.audit.AuditLog;
import com.flowtivo.tinfirm.audit.AuditRecord;
import com.flowtivo.tinfirm.principal.PrincipalInfo;
import com.flowtivo.tinfirm.principal.PrincipalStatus;
import com.flowtivo.tinfirm.principal.Principals;
import com.flowtivo.tinfirm.shared.security.CurrentActor;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.tx.SystemTransactions;
import com.flowtivo.tinfirm.vault.SecretWriter;

/**
 * Rotates LiteLLM keys (security review row 2 of the platform redesign; CONVENTIONS §15.4), principals' and the
 * company's own ({@link KeyHolder}, ADR-0065): every key {@code tinfirm.model.key-rotation-interval} after its creation
 * (30 days), and at once when an admin asks for one principal's, for the company's own, or for every key of the
 * company.
 * <p>
 * LiteLLM v1.105.1 regenerates a key only for enterprise licences, so a rotation creates the next key itself, with
 * the next alias (an alias is unique in LiteLLM), the company's team, the budget, period, models and metadata the
 * principal and the settings ask for, blocked for a suspended principal, and the spend the current key has: the budget
 * and the spend are the key's own in LiteLLM, and LiteLLM's reset time follows the period and the calendar, so the
 * next key goes on in the same period with the same spend. The rotation then:
 * <ol>
 * <li>records the next key in a new internal secret and makes it the principal's, in one transaction, which deletes
 * the previous key's secret: the relay reveals the next key for every request from then on
 * ({@code model_key.rotated});</li>
 * <li>leaves the previous key usable for the grace ({@code key-rotation-grace}, 10 minutes; none for a rotation an
 * admin asked for), so that relayed streams that hold it finish, then blocks it in LiteLLM
 * ({@code model_key.retired});</li>
 * <li>a minute later ({@code key-rotation-settle}), once LiteLLM wrote the spend of its last requests, moves what the
 * previous key spent after the next one was created over to the current key, deletes the previous key, and forgets
 * it.</li>
 * </ol>
 * Each step is recorded in {@code principal_model_key} before the next one runs, and each run reads the row again and
 * does what it says is due, under the row's lease. A run that dies between two steps leaves a state the next run
 * finishes (the reconciliation runs one for every principal): the principal always has a working key (the previous
 * one until the next is recorded, the next one after), a next key LiteLLM created but Tinfirm did not record is
 * deleted by its alias when the rotation asks for it again (or by the reconciliation's sweep), and a previous key is
 * blocked and deleted once its time came, whichever run gets there. A failure of LiteLLM ends the run with
 * {@code rotation_error} and changes nothing else; the current key keeps working. LiteLLM is called outside any
 * transaction; the actor of the run's records is SYSTEM.
 * <p>
 * Public for the tests, which simulate a crash after each step ({@link #crashAfter}); nothing else outside the module
 * calls it.
 */
@Component
public class KeyRotation {

    private static final Logger log = LoggerFactory.getLogger(KeyRotation.class);

    /** The steps one run may take: a previous key's two, a rotation, and the new previous key's block. */
    private static final int MAX_STEPS = 6;

    private static final double EPSILON = 0.000_001;

    /** The steps of a rotation that change something outside Tinfirm's database, or the switch itself. */
    public enum Step {
        /** LiteLLM created the next key; Tinfirm has not recorded it yet. */
        GENERATED,
        /** The next key is the principal's: the relay uses it, and the previous one is retiring. */
        SWITCHED,
        /** LiteLLM blocked the previous key; Tinfirm has not recorded it yet. */
        BLOCKED,
        /** What the previous key spent late went over to the current key; LiteLLM still has the previous key. */
        TRANSFERRED,
        /** LiteLLM deleted the previous key; Tinfirm still names it. */
        DELETED
    }

    /** What a rotation run did. */
    enum Result {
        /** The due steps ran, or nothing was due. */
        DONE,
        /** Another run holds the key's row; try again later. */
        BUSY,
        /** Nothing to rotate: the gateway did not pass its test, the principal is gone, or it has no row. */
        NOTHING,
        /** LiteLLM failed a step: {@code rotation_error}; the next run tries again. */
        FAILED
    }

    /**
     * What a run did, why it failed, and when something is next due (a previous key's grace or settling time), at
     * which a run should look again.
     */
    record Outcome(Result result, @Nullable String failure, @Nullable Instant wakeAt) {
    }

    /**
     * What a step is decided on: the row as read under the lease, with its settings, its principal (null for the
     * company's own key), its names for LiteLLM's admin, and gateway.
     */
    private record State(Gateway gateway, String connection, ModelSettingsStore.SettingsRow settings,
            ModelKeyStore.KeyRow row, @Nullable PrincipalInfo principal, KeySync.Names names) {
    }

    /** The step that is due. */
    enum Next {
        NONE, BLOCK_PREVIOUS, DELETE_PREVIOUS, ROTATE
    }

    private final SystemTransactions transactions;

    private final ModelSettingsStore settingsStore;

    private final ModelKeyStore keys;

    private final Principals principals;

    private final Gateways gateways;

    private final GatewayAdapter adapter;

    private final KeySync sync;

    private final SecretWriter secrets;

    private final AuditLog auditLog;

    private final ModelProperties properties;

    private final TimeSource time;

    private final Map<UUID, Step> crashes = new ConcurrentHashMap<>();

    KeyRotation(SystemTransactions transactions, ModelSettingsStore settingsStore, ModelKeyStore keys,
            Principals principals, Gateways gateways, GatewayAdapter adapter, KeySync sync, SecretWriter secrets,
            AuditLog auditLog, ModelProperties properties, TimeSource time) {
        this.transactions = transactions;
        this.settingsStore = settingsStore;
        this.keys = keys;
        this.principals = principals;
        this.gateways = gateways;
        this.adapter = adapter;
        this.sync = sync;
        this.secrets = secrets;
        this.auditLog = auditLog;
        this.properties = properties;
        this.time = time;
    }

    /** Runs what is due of the key's rotation now, on the caller's thread, which must hold no transaction. */
    Outcome run(KeyHolder holder) {
        UUID companyId = holder.companyId();
        Optional<ModelKeyStore.KeyRow> look = transactions.inCompany(companyId, () -> keys.find(holder));
        if (look.isEmpty() || look.get().provided() != null) {
            // A key its gateway team made is theirs to rotate (ADR-0094).
            return new Outcome(Result.NOTHING, null, null);
        }
        if (next(look.get(), time.now()) == Next.NONE) {
            return new Outcome(Result.DONE, null, wakeAt(look.get().rotation()));
        }
        State first = transactions.inCompany(companyId, () -> claim(holder));
        if (first == null) {
            return busyOrNothing(holder);
        }
        try {
            State state = first;
            for (int step = 0; step < MAX_STEPS && state != null; step++) {
                State current = state;
                Next next = next(current.row(), time.now());
                if (next == Next.NONE) {
                    break;
                }
                switch (next) {
                    case BLOCK_PREVIOUS -> blockPrevious(holder, current);
                    case DELETE_PREVIOUS -> deletePrevious(holder, current);
                    case ROTATE -> rotate(holder, current);
                    default -> throw new IllegalStateException("No step " + next);
                }
                state = transactions.inCompany(companyId, () -> reread(holder, first.connection()));
            }
            return transactions.inCompany(companyId, () -> {
                Optional<ModelKeyStore.KeyRow> row = keys.find(holder);
                row.ifPresent(ignored -> keys.release(holder));
                return new Outcome(Result.DONE, null, row.map(found -> wakeAt(found.rotation())).orElse(null));
            });
        }
        catch (LiteLlmException ex) {
            String failure = "The key's rotation failed: " + ex.getMessage();
            transactions.runInCompany(companyId, () -> keys.rotationFailed(holder, shorten(failure), time.now()));
            log.warn("The LiteLLM key of {} could not be rotated: {}", holder, ex.getMessage());
            return new Outcome(Result.FAILED, failure, null);
        }
        catch (RuntimeException ex) {
            try {
                transactions.runInCompany(companyId, () -> keys.release(holder));
            }
            catch (RuntimeException again) {
                ex.addSuppressed(again);
            }
            throw ex;
        }
    }

    /**
     * The step that is due: a previous key past its grace is blocked, and deleted once it settled; a key whose time
     * came, or that an admin asked to rotate, is rotated once no previous key is left. A rotation that is due does not
     * wait for the previous key of the last one: that key is blocked and deleted at once.
     */
    Next next(ModelKeyStore.KeyRow row, Instant now) {
        ModelKeyStore.Rotation rotation = row.rotation();
        boolean rotate = rotationDue(row, now, properties.keyRotationInterval());
        if (rotation.retiringKeyId() != null) {
            if (rotation.retiringBlockedAt() == null) {
                return rotate || !now.isBefore(Objects.requireNonNull(rotation.retiringUntil()))
                        ? Next.BLOCK_PREVIOUS : Next.NONE;
            }
            return rotate || !now.isBefore(rotation.retiringBlockedAt().plus(properties.keyRotationSettle()))
                    ? Next.DELETE_PREVIOUS
                    : Next.NONE;
        }
        return rotate ? Next.ROTATE : Next.NONE;
    }

    /** Whether the key is to be rotated: an admin asked for it, or it is as old as the interval. */
    static boolean rotationDue(ModelKeyStore.KeyRow row, Instant now, Duration interval) {
        if (!row.hasKey()) {
            return false;
        }
        ModelKeyStore.Rotation rotation = row.rotation();
        return rotation.requestedAt() != null
                || rotation.keyCreatedAt() != null && !now.isBefore(rotation.keyCreatedAt().plus(interval));
    }

    /** When something of the rotation is next due without a request: a previous key's block or deletion. */
    @Nullable Instant wakeAt(ModelKeyStore.Rotation rotation) {
        if (rotation.retiringKeyId() == null) {
            return null;
        }
        return rotation.retiringBlockedAt() == null ? rotation.retiringUntil()
                : rotation.retiringBlockedAt().plus(properties.keyRotationSettle());
    }

    // ---- the lease -----------------------------------------------------------------------------------------------

    /** The state with the row's lease; null when there is nothing to do or another run holds the row. */
    private @Nullable State claim(KeyHolder holder) {
        Instant now = time.now();
        State state = state(holder);
        if (state == null || next(state.row(), now) == Next.NONE
                || !keys.claim(holder, now, now.plus(KeySync.LEASE))) {
            return null;
        }
        return state;
    }

    /** The state again, after a step, with the lease renewed; null when the gateway or the principal is gone. */
    private @Nullable State reread(KeyHolder holder, String connection) {
        State state = state(holder);
        if (state == null || !state.connection().equals(connection)) {
            keys.find(holder).ifPresent(ignored -> keys.release(holder));
            return null;
        }
        keys.renewLease(holder, time.now().plus(KeySync.LEASE));
        return state;
    }

    private @Nullable State state(KeyHolder holder) {
        UUID companyId = holder.companyId();
        Optional<ModelSettingsStore.SettingsRow> settings = settingsStore.find(companyId)
            .filter(ModelSettingsStore.SettingsRow::relayEnabled);
        PrincipalInfo principal = null;
        if (!holder.isCompany()) {
            principal = principals.find(companyId, holder.requirePrincipal())
                .filter(found -> found.status() != PrincipalStatus.LEFT)
                .orElse(null);
            if (principal == null) {
                return null;
            }
        }
        Optional<ModelKeyStore.KeyRow> row = keys.find(holder);
        if (settings.isEmpty() || row.isEmpty()) {
            return null;
        }
        PrincipalInfo found = principal;
        return gateways.of(settings.get())
            .map(gateway -> new State(gateway, KeySync.connection(settings.get()), settings.get(), row.get(), found,
                    sync.names(companyId, found)))
            .orElse(null);
    }

    /**
     * Whether the claim failed for a held lease or for nothing to do. Neither wakes a run later: the holder of the
     * lease wakes its own, and a step whose time has come while a dead run holds the lease (an overdue one, which a
     * wake would start at once and retry every few seconds) is the reconciliation's once the lease expired.
     */
    private Outcome busyOrNothing(KeyHolder holder) {
        return transactions.inCompany(holder.companyId(), () -> {
            Optional<ModelKeyStore.KeyRow> row = keys.find(holder);
            boolean leased = row.isPresent() && row.get().syncLeaseUntil() != null
                    && row.get().syncLeaseUntil().isAfter(time.now());
            return new Outcome(leased ? Result.BUSY : Result.NOTHING, null, null);
        });
    }

    // ---- the steps -----------------------------------------------------------------------------------------------

    /**
     * Creates the next key with the current one's spend, and makes it the holder's: the current key retires, with the
     * grace (none when an admin asked for the rotation, also while this step ran).
     */
    private void rotate(KeyHolder holder, State state) {
        UUID companyId = holder.companyId();
        ModelKeyStore.KeyRow row = state.row();
        String currentId = Objects.requireNonNull(row.gatewayKeyId());
        Gateway gateway = state.gateway();
        GatewayAdapter.KeyState current = adapter.info(gateway, currentId)
            .orElseThrow(() -> new LiteLlmException(404,
                    "LiteLLM does not know the current key; the sync makes it again"));
        int generation = row.rotation().generation() + 1;
        BigDecimal carried = BigDecimal.valueOf(current.spend());
        KeySync.Desired desired = KeySync.desired(state.settings(), row, state.principal());
        GatewayAdapter.CreatedKey created = sync.generate(gateway, KeySync.keySpec(holder.alias(generation),
                Gateway.teamId(companyId), holder, KeySync.kind(state.principal()), state.names(), desired, carried),
                companyId, state.names().company());

        checkpoint(holder, Step.GENERATED);
        boolean switched;
        try {
            switched = transactions.inCompany(companyId, () -> switchTo(holder, state, currentId, created, generation,
                    carried, current.budgetResetAt()));
        }
        catch (RuntimeException ex) {
            // Tinfirm could not keep the key: it must not live on in LiteLLM without an owner.
            deleteQuietly(gateway, created.keyId());
            throw ex;
        }
        if (!switched) {
            deleteQuietly(gateway, created.keyId());
            return;
        }
        checkpoint(holder, Step.SWITCHED);
    }

    /** The switch, in a transaction of the company; false when the row moved on meanwhile. */
    private boolean switchTo(KeyHolder holder, State state, String currentId, GatewayAdapter.CreatedKey created,
            int generation, BigDecimal carried, @Nullable Instant resetsAt) {
        UUID companyId = holder.companyId();
        Optional<ModelKeyStore.KeyRow> row = keys.lock(holder);
        if (row.isEmpty() || !currentId.equals(row.get().gatewayKeyId()) || row.get().rotation().retiringKeyId() != null
                || !settingsStore.find(companyId).map(KeySync::connection).filter(state.connection()::equals)
                    .isPresent()) {
            return false;
        }
        Instant now = time.now();
        boolean requested = row.get().rotation().requestedAt() != null;
        Duration grace = requested ? Duration.ZERO : properties.keyRotationGrace();
        UUID secretId = secrets.createInternal(companyId, holder.secretName(), holder.secretDescription(),
                created.key());
        keys.switchKey(holder, secretId, created.keyId(), generation, currentId, now.plus(grace), carried, resetsAt,
                now);
        if (row.get().secretId() != null) {
            secrets.deleteInternal(companyId, row.get().secretId());
        }
        auditLog.record(holder
            .audit(AuditRecord.company(companyId, AuditAction.MODEL_KEY_ROTATED, CurrentActor.actor()))
            .detail("cause", requested ? "request" : "schedule")
            .detail("graceSeconds", grace.toSeconds())
            .build());
        return true;
    }

    /** Blocks the previous key in LiteLLM: from now on it is refused. */
    private void blockPrevious(KeyHolder holder, State state) {
        String previous = Objects.requireNonNull(state.row().rotation().retiringKeyId());
        try {
            adapter.block(state.gateway(), previous);
        }
        catch (LiteLlmException ex) {
            if (!ex.notFound()) {
                throw ex;
            }
            // LiteLLM has it no longer: nothing refuses it better.
        }
        checkpoint(holder, Step.BLOCKED);
        transactions.runInCompany(holder.companyId(), () -> {
            Optional<ModelKeyStore.KeyRow> row = keys.lock(holder);
            if (row.isEmpty() || !previous.equals(row.get().rotation().retiringKeyId())) {
                return;
            }
            keys.retiringBlocked(holder, time.now());
            AuditRecord.Builder audit = holder
                .audit(AuditRecord.company(holder.companyId(), AuditAction.MODEL_KEY_RETIRED, CurrentActor.actor()));
            if (row.get().rotation().keyRotatedAt() != null) {
                audit.detail("rotatedAt", row.get().rotation().keyRotatedAt().toString());
            }
            auditLog.record(audit.build());
        });
    }

    /**
     * Moves what the blocked previous key spent after the current one was created with its spend over to the current
     * key, deletes the previous key in LiteLLM, and forgets it.
     */
    private void deletePrevious(KeyHolder holder, State state) {
        ModelKeyStore.KeyRow row = state.row();
        String previous = Objects.requireNonNull(row.rotation().retiringKeyId());
        Optional<GatewayAdapter.KeyState> old = adapter.info(state.gateway(), previous);
        BigDecimal spent = null;
        if (old.isPresent()) {
            double late = lateSpend(old.get(), row.rotation());
            if (late > EPSILON && row.hasKey()) {
                String currentId = Objects.requireNonNull(row.gatewayKeyId());
                Optional<GatewayAdapter.KeyState> current = adapter.info(state.gateway(), currentId);
                if (current.isPresent()) {
                    spent = BigDecimal.valueOf(current.get().spend() + late);
                    adapter.setSpend(state.gateway(), currentId, spent);
                }
            }
            checkpoint(holder, Step.TRANSFERRED);
            adapter.delete(state.gateway(), previous);
        }
        checkpoint(holder, Step.DELETED);
        BigDecimal spentUsd = spent;
        transactions.runInCompany(holder.companyId(), () -> {
            Optional<ModelKeyStore.KeyRow> locked = keys.lock(holder);
            if (locked.isPresent() && previous.equals(locked.get().rotation().retiringKeyId())) {
                keys.retired(holder, spentUsd, time.now());
            }
        });
    }

    /**
     * What the previous key spent after the current one was created with its spend: its spend since, or all of its
     * spend when its period restarted meanwhile (the current key's restarted too).
     */
    static double lateSpend(GatewayAdapter.KeyState previous, ModelKeyStore.Rotation rotation) {
        boolean restarted = previous.budgetResetAt() != null && rotation.retiringResetsAt() != null
                && previous.budgetResetAt().isAfter(rotation.retiringResetsAt());
        double carried = rotation.retiringSpendUsd() != null ? rotation.retiringSpendUsd().doubleValue()
                : previous.spend();
        return Math.max(0, restarted ? previous.spend() : previous.spend() - carried);
    }

    private void deleteQuietly(Gateway gateway, String keyId) {
        try {
            adapter.delete(gateway, keyId);
        }
        catch (LiteLlmException ex) {
            log.warn("A rotation's key Tinfirm could not keep was not deleted ({}); the reconciliation deletes it",
                    ex.getMessage());
        }
    }

    private static String shorten(String text) {
        return text.length() <= 500 ? text : text.substring(0, 499) + "…";
    }

    // ---- a crash, for the tests ----------------------------------------------------------------------------------

    /**
     * TEST ONLY: the next rotation run of the principal's key (or, with the company's id, of the company's own key)
     * stops right after the step, as if the server died there: no catch runs, nothing is cleaned up, and the row's
     * lease stays held until it expires. Nothing in production calls it.
     */
    public void crashAfter(UUID holderId, Step step) {
        crashes.put(holderId, step);
    }

    private void checkpoint(KeyHolder holder, Step step) {
        if (!crashes.isEmpty() && crashes.remove(holder.workId(), step)) {
            throw new SimulatedCrash(step);
        }
    }

    /** A crash's stand-in (TEST ONLY): an {@link Error}, so that none of the rotation's catches runs. */
    public static final class SimulatedCrash extends Error {

        @Serial
        private static final long serialVersionUID = 1L;

        SimulatedCrash(Step step) {
            super("Simulated crash after " + step, null, false, false);
        }
    }
}
