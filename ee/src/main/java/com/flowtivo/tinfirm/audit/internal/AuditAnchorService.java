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
import java.util.concurrent.ConcurrentHashMap;

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
 * <b>The comparison</b> ({@link #compare}), in the daily check of every chain: the latest checkpoint that names it,
 * read from the sink and its signature verified where the sink can be read (the database's record otherwise), must
 * agree with the chain: the event at its {@code seq} exists and has its hash.
 */
@Component
@EnableConfigurationProperties(AuditAnchorProperties.class)
class AuditAnchorService implements AuditAnchors, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(AuditAnchorService.class);

    /** How long the sink's latest checkpoint is kept in memory for the daily checks of many chains. */
    private static final Duration LATEST_KEPT = Duration.ofMinutes(1);

    private static final int CACHED_CHECKPOINTS = 64;

    private final AuditAnchorProperties properties;

    private final AnchorKeys keys;

    private final @Nullable AnchorSink sink;

    private final AuditCheckpointStore store;

    private final AuditEventStore events;

    private final SystemTransactions transactions;

    private final Licensing licensing;

    private final TimeSource time;

    private final boolean worker;

    private final Map<Long, Checkpoint.Signed> verified = new ConcurrentHashMap<>();

    private volatile @Nullable Latest latest;

    private record Latest(Instant readAt, Optional<Checkpoint.Signed> checkpoint) {
    }

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
        Map<String, Checkpoint.Head> anchored = new HashMap<>();
        for (Checkpoint.Head head : store.lastAnchored()) {
            anchored.put(chainKey(head.companyId()), head);
        }
        Previous previous = previous(target, instance);
        List<Checkpoint.Head> moved = new ArrayList<>();
        for (Checkpoint.Head head : heads) {
            Checkpoint.Head last = anchored.get(chainKey(head.companyId()));
            if (previous.number() == 0 || last == null || last.seq() != head.seq()
                    || !last.hash().equals(head.hash())) {
                moved.add(head);
            }
        }
        if (moved.isEmpty()) {
            store.recordNothingToWrite(issuedAt);
            return Outcome.UNCHANGED;
        }
        String keyId = keys.activeId().orElseThrow();
        Checkpoint checkpoint = new Checkpoint(instance, previous.number() + 1, issuedAt, previous.digest(), keyId,
                moved);
        String document = checkpoint.signedDocument(keys);
        String location;
        try {
            location = target.write(instance, checkpoint.number(), document);
        }
        catch (IOException ex) {
            throw new IllegalStateException(ex.getMessage(), ex);
        }
        store.insert(checkpoint, checkpoint.digest(), location, document, time.now());
        store.recordSuccess(checkpoint.number(), time.now());
        verified.put(checkpoint.number(), new Checkpoint.Signed(checkpoint, true, null));
        latest = null;
        log.info("Audit checkpoint {} written to {}: {} chains", checkpoint.number(), location,
                checkpoint.chains().size());
        return Outcome.WRITTEN;
    }

    /** The checkpoint the next one follows: its number and digest, 0 and no digest before the first. */
    private record Previous(long number, String digest) {
    }

    /**
     * From the sink where it can be read, its signature verified; the database's record only for a sink that cannot
     * be read (syslog), and as a warning where the two disagree.
     */
    private Previous previous(AnchorSink target, UUID instance) {
        Optional<AuditCheckpointStore.Written> recorded = store.latest();
        if (!target.readable()) {
            return recorded.map(written -> new Previous(written.number(), written.digest()))
                .orElse(new Previous(0, Checkpoint.NO_PREVIOUS));
        }
        long after = recorded.map(written -> Math.max(0, written.number() - 1)).orElse(0L);
        List<Long> numbers;
        try {
            numbers = target.numbers(instance, after);
            if (numbers.isEmpty() && after > 0) {
                numbers = target.numbers(instance, 0);
            }
        }
        catch (IOException ex) {
            throw new IllegalStateException(ex.getMessage(), ex);
        }
        if (numbers.isEmpty()) {
            if (recorded.isPresent()) {
                log.error("The audit anchor's sink {} holds no checkpoint of instance {}, while the database records"
                        + " {}: the sink was changed or emptied; the next checkpoint follows the database's"
                        + " (ADR-0111)", target.location(), instance, recorded.get().number());
                return new Previous(recorded.get().number(), recorded.get().digest());
            }
            return new Previous(0, Checkpoint.NO_PREVIOUS);
        }
        long number = numbers.getLast();
        Checkpoint.Signed last = readVerified(target, instance, number)
            .orElseThrow(() -> new IllegalStateException("The checkpoint " + number + " vanished from the sink"));
        if (!last.verified()) {
            throw new IllegalStateException("The latest checkpoint in the sink, " + number + ", does not verify ("
                    + last.reason() + "): no checkpoint follows it until it is understood");
        }
        if (recorded.isPresent() && recorded.get().number() != number) {
            log.warn("The audit anchor's sink holds checkpoint {} as its latest, the database records {}; the next"
                    + " checkpoint follows the sink's", number, recorded.get().number());
        }
        return new Previous(number, last.checkpoint().digest());
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

    @Override
    public Optional<Mismatch> compare(@Nullable UUID companyId) {
        if (!inForce()) {
            return Optional.empty();
        }
        Optional<Anchored> candidate = anchoredFromSink(companyId);
        if (candidate.isEmpty()) {
            return Optional.empty();
        }
        Anchored anchor = candidate.get();
        if (anchor.seq() == 0) {
            return Optional.empty();
        }
        Optional<StoredAuditEvent> event = events.at(companyId, anchor.seq());
        if (event.isEmpty() || !event.get().hash().equals(anchor.hash())) {
            log.error("The audit chain of {} disagrees with checkpoint {}: the event at seq {} is {} (ADR-0111)",
                    companyId == null ? "the instance" : "company " + companyId, anchor.checkpoint(), anchor.seq(),
                    event.isEmpty() ? "gone" : "another");
            return Optional.of(new Mismatch(anchor.checkpoint(), anchor.seq()));
        }
        return Optional.empty();
    }

    /**
     * The latest checkpoint that names the chain, its head taken from the sink's signed document where the sink can
     * be read: the database's record says which checkpoint to read, and the sink's latest is read as well, so that a
     * record removed from the database does not hide the newest checkpoint. A recorded checkpoint the sink no longer
     * holds is skipped with a warning; one that does not verify is a mismatch at its recorded head.
     */
    private Optional<Anchored> anchoredFromSink(@Nullable UUID companyId) {
        Optional<Anchored> recorded = store.anchored(companyId, null);
        AnchorSink target = sink;
        if (target == null || !target.readable()) {
            return recorded;
        }
        // In the chain's own context: the state is readable from any (V084), the checkpoints' table is not.
        Optional<AuditCheckpointStore.State> state = store.state();
        if (state.isEmpty()) {
            return recorded;
        }
        UUID instance = state.get().instanceId();
        Optional<Anchored> fromLatest = latestInSink(target, instance, state.get().lastNumber())
            .filter(Checkpoint.Signed::verified)
            .flatMap(signed -> signed.checkpoint().chain(companyId).map(head -> anchoredOf(signed.checkpoint(), head)));
        if (recorded.isEmpty()
                || fromLatest.isPresent() && fromLatest.get().checkpoint() >= recorded.get().checkpoint()) {
            return fromLatest;
        }
        long number = recorded.get().checkpoint();
        Optional<Checkpoint.Signed> read;
        try {
            read = readVerified(target, instance, number);
        }
        catch (RuntimeException ex) {
            log.warn("The checkpoint {} cannot be read from {} for the daily check: {}", number, target.location(),
                    reason(ex));
            return Optional.empty();
        }
        if (read.isEmpty()) {
            log.warn("The database records checkpoint {} for the chain of {}, which the sink {} no longer holds",
                    number, companyId == null ? "the instance" : "company " + companyId, target.location());
            return Optional.empty();
        }
        if (!read.get().verified()) {
            log.error("The checkpoint {} in {} does not verify: {}", number, target.location(), read.get().reason());
            return recorded;
        }
        return read.get().checkpoint().chain(companyId).map(head -> anchoredOf(read.get().checkpoint(), head));
    }

    private Optional<Checkpoint.Signed> latestInSink(AnchorSink target, UUID instance, @Nullable Long lastNumber) {
        Latest known = latest;
        Instant now = time.now();
        if (known != null && known.readAt().plus(LATEST_KEPT).isAfter(now)) {
            return known.checkpoint();
        }
        Optional<Checkpoint.Signed> found;
        try {
            long after = lastNumber != null ? Math.max(0, lastNumber - 1) : 0;
            List<Long> numbers = target.numbers(instance, after);
            if (numbers.isEmpty() && after > 0) {
                numbers = target.numbers(instance, 0);
            }
            found = numbers.isEmpty() ? Optional.empty() : readVerified(target, instance, numbers.getLast());
        }
        catch (IOException | RuntimeException ex) {
            log.warn("The latest checkpoint cannot be read from {}: {}", target.location(), reason(ex));
            found = Optional.empty();
        }
        latest = new Latest(now, found);
        return found;
    }

    private Optional<Checkpoint.Signed> readVerified(AnchorSink target, UUID instance, long number) {
        Checkpoint.Signed cached = verified.get(number);
        if (cached != null) {
            return Optional.of(cached);
        }
        Optional<AnchorSink.Stored> stored;
        try {
            stored = target.read(instance, number);
        }
        catch (IOException ex) {
            throw new IllegalStateException(ex.getMessage(), ex);
        }
        if (stored.isEmpty()) {
            return Optional.empty();
        }
        Checkpoint.Signed signed;
        try {
            signed = Checkpoint.parse(stored.get().document(), keys);
        }
        catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
        if (signed.checkpoint().number() != number || !signed.checkpoint().instance().equals(instance)) {
            signed = new Checkpoint.Signed(signed.checkpoint(), false,
                    "it names another number or instance than its place in the sink");
        }
        if (signed.verified()) {
            if (verified.size() >= CACHED_CHECKPOINTS) {
                verified.clear();
            }
            verified.put(number, signed);
        }
        return Optional.of(signed);
    }

    private static Anchored anchoredOf(Checkpoint checkpoint, Checkpoint.Head head) {
        return new Anchored(checkpoint.number(), checkpoint.issuedAt(), checkpoint.keyId(), head.seq(), head.hash());
    }

    @Override
    public Optional<Anchored> anchored(@Nullable UUID companyId, @Nullable Long atOrBeforeSeq) {
        if (!inForce()) {
            return Optional.empty();
        }
        return store.anchored(companyId, atOrBeforeSeq);
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
        store.latest().ifPresent(written -> status.lastCheckpoint(new AuditCheckpointWritten(written.number(),
                written.issuedAt().atOffset(ZoneOffset.UTC), written.keyId(), written.location(),
                written.chains())));
        return status;
    }
}
