package com.flowtivo.tinfirm.audit.internal;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.shared.api.model.AuditAnchorKey;
import com.flowtivo.tinfirm.shared.api.model.AuditAnchorStatus;
import com.flowtivo.tinfirm.shared.api.model.AuditCheckpointWritten;
import com.flowtivo.tinfirm.shared.config.TinfirmProperties;
import com.flowtivo.tinfirm.shared.id.Ids;
import com.flowtivo.tinfirm.shared.licence.Feature;
import com.flowtivo.tinfirm.shared.licence.Licensing;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.tx.SystemTransactions;

/**
 * The audit chains' heads anchored outside the database (ADR-0111, {@code AUDIT_ANCHOR}, Pro and Enterprise).
 * <p>
 * <b>The writer</b> ({@link #writeIfDue}), on {@code worker} replicas: while the licence holds the anchor and a sink
 * is configured, a look every {@code tick} writes a checkpoint once {@code interval} has passed since the last one
 * and some chain's head moved: the heads that moved since the latest checkpoint (every chain's in the first), signed
 * with the active key, chained to the checkpoint before it, written to the sink, then recorded. One replica writes at
 * a time (an advisory lock). A failed write never stops an audit event: it is logged at {@code ERROR} at every
 * failure, counted in the status, and tried again after {@code retry}; a checkpoint's number is taken only once its
 * write succeeded.
 * <p>
 * <b>The comparison</b> ({@link #compare}), in the daily check of every chain: every head the checkpoints name for
 * it must agree with the chain (the event at its {@code seq} exists and has its hash), and no checkpoint may
 * contradict an earlier one for it. Where the sink can be read, what was anchored, the writer's next number and its
 * {@code prev} all come from the sink's verified checkpoints ({@link SinkIndex}), never from the database's record,
 * which whoever the anchor defends against can write; only syslog, which cannot be read back, uses the record.
 */
@Component
@EnableConfigurationProperties(AuditAnchorProperties.class)
class AuditAnchorService implements AuditAnchors, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(AuditAnchorService.class);

    private final AuditAnchorProperties properties;

    private final AnchorKeys keys;

    private final @Nullable AnchorSink sink;

    private final AuditCheckpointStore store;

    private final AuditEventStore events;

    private final SystemTransactions transactions;

    private final Licensing licensing;

    private final TimeSource time;

    private final boolean worker;

    /** The sink's verified checkpoints, where the sink can be read; made at the first use. */
    private volatile @Nullable SinkIndex index;

    @Autowired
    AuditAnchorService(AuditAnchorProperties properties, AuditCheckpointStore store, AuditEventStore events,
            SystemTransactions transactions, Licensing licensing, TimeSource time, TinfirmProperties tinfirm,
            Clock clock) {
        this(properties, sinkOf(properties, clock), store, events, transactions, licensing, time, tinfirm);
    }

    /** With a sink of the caller's: the tests' failing and counting sinks. */
    AuditAnchorService(AuditAnchorProperties properties, @Nullable AnchorSink sink, AuditCheckpointStore store,
            AuditEventStore events, SystemTransactions transactions, Licensing licensing, TimeSource time,
            TinfirmProperties tinfirm) {
        this.properties = properties;
        this.keys = AnchorKeys.of(properties);
        this.sink = sink;
        this.store = store;
        this.events = events;
        this.transactions = transactions;
        this.licensing = licensing;
        this.time = time;
        this.worker = tinfirm.hasRole(TinfirmProperties.WORKER_ROLE);
        if (sink != null) {
            sink.checkAtStart();
            log.info("The audit chains are anchored to {} ({}), signed with the key {}; the keys that verify: {}"
                    + " (ADR-0111)", sink.location(), properties.sink().name().toLowerCase(Locale.ROOT),
                    keys.activeId().orElse("-"), publicKeys());
        }
        else {
            log.warn("The audit chains are not anchored outside the database: TINFIRM_AUDIT_ANCHOR_SINK is none"
                    + " (ADR-0111, threat model 2.8)");
        }
    }

    private static @Nullable AnchorSink sinkOf(AuditAnchorProperties properties, Clock clock) {
        return switch (properties.sink()) {
            case NONE -> null;
            case FILE -> new FileAnchorSink(properties.file().dir());
            case S3 -> new S3AnchorSink(properties.s3(), properties.s3().retention(), properties.s3().requireLock(),
                    clock);
            case SYSLOG -> new SyslogAnchorSink(properties.syslog(), clock);
        };
    }

    private String publicKeys() {
        List<String> listed = new ArrayList<>();
        keys.verifying().forEach((id, key) -> listed.add(id + "="
                + java.util.Base64.getEncoder().encodeToString(key.getEncoded())));
        return String.join(", ", listed);
    }

    @Override
    public void destroy() {
        if (sink != null) {
            sink.close();
        }
    }

    /** Whether checkpoints are written: a sink, and the licence holds the anchor. */
    boolean inForce() {
        return sink != null && licensing.has(Feature.AUDIT_ANCHOR);
    }

    // ---- the writer ------------------------------------------------------------------------------------------------
    @Scheduled(fixedDelayString = "${tinfirm.audit.anchor.tick:PT1M}",
            initialDelayString = "${tinfirm.audit.anchor.initial-delay:PT1M}")
    void scheduledWrite() {
        if (worker && inForce()) {
            try {
                writeIfDue();
            }
            catch (RuntimeException ex) {
                log.warn("The audit anchor's writer failed to run", ex);
            }
        }
    }

    /** What a look found. */
    enum Outcome {
        /** Not in force, not due, or another replica writes. */
        SKIPPED,
        /** No chain's head moved since the latest checkpoint. */
        UNCHANGED,
        WRITTEN,
        FAILED
    }

    /** Writes a checkpoint when one is due; safe on several replicas at once. */
    Outcome writeIfDue() {
        if (!inForce()) {
            return Outcome.SKIPPED;
        }
        Instant now = time.now();
        Optional<AuditCheckpointStore.State> state = transactions.instanceWide(store::state);
        if (state.isPresent() && state.get().lastAttemptAt() != null) {
            AuditCheckpointStore.State current = state.get();
            boolean failedLast = current.consecutiveFailures() > 0;
            Duration wait = failedLast ? properties.retry() : properties.interval();
            if (current.lastAttemptAt().plus(wait).isAfter(now)) {
                return Outcome.SKIPPED;
            }
        }
        return write();
    }

    /** Writes a checkpoint now if any chain's head moved. */
    Outcome write() {
        if (!inForce()) {
            return Outcome.SKIPPED;
        }
        AnchorSink target = sink;
        try {
            return transactions.instanceWide(() -> {
                if (!store.tryLock()) {
                    return Outcome.SKIPPED;
                }
                return writeLocked(target);
            });
        }
        catch (RuntimeException ex) {
            String reason = reason(ex);
            log.error("The audit anchor could not write a checkpoint to {}: {}; the next try is in {} (ADR-0111)",
                    target.location(), reason, properties.retry(), ex);
            try {
                transactions.instanceWide(() -> {
                    store.stateOrCreate(Ids.newId());
                    store.recordFailure(time.now(), reason);
                    return null;
                });
            }
            catch (RuntimeException recording) {
                log.warn("The audit anchor's failure could not be recorded", recording);
            }
            return Outcome.FAILED;
        }
    }

    private Outcome writeLocked(AnchorSink target) {
        AuditCheckpointStore.State state = store.stateOrCreate(Ids.newId());
        UUID instance = state.instanceId();
        Instant issuedAt = time.now();
        List<Checkpoint.Head> heads = store.heads();
        // What was anchored last, and what the next checkpoint follows: from the sink's verified checkpoints where
        // the sink can be read, never from the database, which whoever the anchor defends against can write.
        Map<String, Checkpoint.Head> anchored = new HashMap<>();
        long previousNumber;
        String previousDigest;
        SinkIndex index = null;
        if (target.readable()) {
            index = refreshedIndex(target, instance);
            if (index.broken() != null) {
                throw new IllegalStateException("The sink's checkpoints stop verifying (" + index.broken()
                        + "): no checkpoint follows until they are put right");
            }
            Optional<AuditCheckpointStore.Written> recorded = store.latest();
            if (index.lastNumber() == 0 && recorded.isPresent()) {
                throw new IllegalStateException("The sink " + target.location() + " holds no checkpoint of instance "
                        + instance + ", while checkpoints up to " + recorded.get().number() + " were written: restore"
                        + " them to the sink (copy every checkpoint of the instance there) before the anchor"
                        + " continues");
            }
            if (recorded.isPresent() && recorded.get().number() > index.lastNumber()) {
                log.warn("The database records checkpoint {}, the sink's latest is {}: the next checkpoint follows"
                        + " the sink", recorded.get().number(), index.lastNumber());
            }
            previousNumber = index.lastNumber();
            previousDigest = index.lastDigest();
            index.latestOfEach().forEach((companyId, anchor) -> anchored.put(chainKey(companyId),
                    new Checkpoint.Head(companyId, anchor.seq(), anchor.hash())));
        }
        else {
            // A sink that cannot be read back (syslog): the database's record is all there is (ADR-0111, decision 3).
            Optional<AuditCheckpointStore.Written> recorded = Optional.ofNullable(state.lastNumber())
                .flatMap(store::written);
            previousNumber = recorded.map(AuditCheckpointStore.Written::number).orElse(0L);
            previousDigest = recorded.map(AuditCheckpointStore.Written::digest).orElse(Checkpoint.NO_PREVIOUS);
            for (Checkpoint.Head head : store.lastAnchored()) {
                anchored.put(chainKey(head.companyId()), head);
            }
        }
        List<Checkpoint.Head> moved = new ArrayList<>();
        for (Checkpoint.Head head : heads) {
            Checkpoint.Head last = anchored.get(chainKey(head.companyId()));
            if (previousNumber == 0 || last == null || last.seq() != head.seq()
                    || !last.hash().equals(head.hash())) {
                moved.add(head);
            }
        }
        if (moved.isEmpty()) {
            store.recordNothingToWrite(issuedAt);
            return Outcome.UNCHANGED;
        }
        String keyId = keys.activeId().orElseThrow();
        Checkpoint checkpoint = new Checkpoint(instance, previousNumber + 1, issuedAt, previousDigest, keyId, moved);
        String document = checkpoint.signedDocument(keys);
        String location;
        try {
            location = target.write(instance, checkpoint.number(), document);
        }
        catch (IOException ex) {
            throw new IllegalStateException(ex.getMessage(), ex);
        }
        if (index != null) {
            index.written(checkpoint);
        }
        store.insert(checkpoint, checkpoint.digest(), location, document, time.now());
        store.recordSuccess(checkpoint.number(), time.now());
        log.info("Audit checkpoint {} written to {}: {} chains", checkpoint.number(), location,
                checkpoint.chains().size());
        return Outcome.WRITTEN;
    }

    /** The index of the sink's verified checkpoints, read on from where it stopped. */
    private SinkIndex refreshedIndex(AnchorSink target, UUID instance) {
        SinkIndex current = index;
        if (current == null || !current.instance().equals(instance)) {
            current = new SinkIndex(instance);
            index = current;
        }
        try {
            current.refresh(target, keys);
        }
        catch (IOException ex) {
            throw new IllegalStateException(ex.getMessage(), ex);
        }
        return current;
    }

    private static String chainKey(@Nullable UUID companyId) {
        return companyId == null ? "" : companyId.toString();
    }

    private static String reason(Throwable ex) {
        Throwable cause = ex;
        while (cause.getCause() != null && cause.getMessage() == null) {
            cause = cause.getCause();
        }
        return cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
    }

    // ---- the comparison --------------------------------------------------------------------------------------------

    /**
     * Compares the chain with every head the checkpoints name for it: where the sink can be read, the sink's
     * verified checkpoints (a later one that contradicts an earlier one for the chain is a mismatch at its own head);
     * for syslog, the database's record. The first head whose event is gone or has another hash is the mismatch.
     */
    @Override
    public Optional<Mismatch> compare(@Nullable UUID companyId) {
        if (!inForce()) {
            return Optional.empty();
        }
        List<SinkIndex.Anchor> anchors;
        AnchorSink target = sink;
        if (target.readable()) {
            // In the chain's own context: the state is readable from any (V084), the checkpoints' table is not.
            Optional<AuditCheckpointStore.State> state = store.state();
            if (state.isEmpty()) {
                return Optional.empty();
            }
            SinkIndex current;
            try {
                current = refreshedIndex(target, state.get().instanceId());
            }
            catch (RuntimeException ex) {
                log.warn("The checkpoints cannot be read from {} for the daily check: {}", target.location(),
                        reason(ex));
                return Optional.empty();
            }
            if (current.broken() != null) {
                log.error("The checkpoints in {} stop verifying: {}; the chains are compared with those before",
                        target.location(), current.broken());
            }
            Optional<SinkIndex.Conflict> conflict = current.conflict(companyId);
            if (conflict.isPresent()) {
                log.error("A checkpoint contradicts an earlier one for the audit chain of {}: checkpoint {} {}"
                        + " (ADR-0111)", chainName(companyId), conflict.get().checkpoint(), conflict.get().reason());
                return Optional.of(new Mismatch(conflict.get().checkpoint(), conflict.get().seq()));
            }
            anchors = current.anchors(companyId);
        }
        else {
            anchors = store.anchorsOf(companyId);
        }
        Map<Long, String> stored = store.hashesAt(companyId, anchors.stream()
            .map(SinkIndex.Anchor::seq)
            .filter(seq -> seq > 0)
            .distinct()
            .toList());
        for (SinkIndex.Anchor anchor : anchors) {
            if (anchor.seq() == 0) {
                continue;
            }
            String hash = stored.get(anchor.seq());
            if (!anchor.hash().equals(hash)) {
                log.error("The audit chain of {} disagrees with checkpoint {}: the event at seq {} is {} (ADR-0111)",
                        chainName(companyId), anchor.checkpoint(), anchor.seq(), hash == null ? "gone" : "another");
                return Optional.of(new Mismatch(anchor.checkpoint(), anchor.seq()));
            }
        }
        return Optional.empty();
    }

    private static String chainName(@Nullable UUID companyId) {
        return companyId == null ? "the instance" : "company " + companyId;
    }

    @Override
    public Optional<Anchored> anchored(@Nullable UUID companyId, @Nullable Long atOrBeforeSeq) {
        if (!inForce()) {
            return Optional.empty();
        }
        AnchorSink target = sink;
        if (!target.readable()) {
            return store.anchored(companyId, atOrBeforeSeq);
        }
        Optional<AuditCheckpointStore.State> state = store.state();
        if (state.isEmpty()) {
            return Optional.empty();
        }
        try {
            return refreshedIndex(target, state.get().instanceId()).latest(companyId, atOrBeforeSeq)
                .map(anchor -> new Anchored(anchor.checkpoint(), anchor.issuedAt(), anchor.keyId(), anchor.seq(),
                        anchor.hash()));
        }
        catch (RuntimeException ex) {
            log.warn("The checkpoints cannot be read from {}: {}", target.location(), reason(ex));
            return Optional.empty();
        }
    }

    // ---- the status ------------------------------------------------------------------------------------------------

    @Override
    public AuditAnchorStatus status() {
        licensing.require(Feature.AUDIT_ANCHOR);
        List<AuditAnchorKey> publicKeys = new ArrayList<>();
        Map<String, java.security.PublicKey> sorted = new LinkedHashMap<>(keys.verifying());
        sorted.forEach((id, key) -> publicKeys.add(new AuditAnchorKey(id, AnchorKeys.publicPem(key),
                id.equals(keys.activeId().orElse(null)))));
        AuditAnchorStatus status = new AuditAnchorStatus(inForce(),
                AuditAnchorStatus.SinkEnum.fromValue(properties.sink().name()), publicKeys, 0)
            .location(sink != null ? sink.location() : null)
            .activeKeyId(keys.activeId().orElse(null));
        store.state().ifPresent(state -> status.instanceId(state.instanceId())
            .lastSuccessAt(state.lastSuccessAt() != null ? state.lastSuccessAt().atOffset(ZoneOffset.UTC) : null)
            .lastFailureAt(state.lastFailureAt() != null ? state.lastFailureAt().atOffset(ZoneOffset.UTC) : null)
            .lastFailure(state.lastFailure())
            .consecutiveFailures(state.consecutiveFailures()));
        // The writer's own latest write, not the newest row: a row is only a record (decision 2).
        store.state()
            .map(AuditCheckpointStore.State::lastNumber)
            .flatMap(store::written)
            .ifPresent(written -> status.lastCheckpoint(new AuditCheckpointWritten(written.number(),
                    written.issuedAt().atOffset(ZoneOffset.UTC), written.keyId(), written.location(),
                    written.chains())));
        return status;
    }
}
