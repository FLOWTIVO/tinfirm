package com.flowtivo.tinfirm.run.internal;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.run.RunInfo;
import com.flowtivo.tinfirm.run.RunReads;
import com.flowtivo.tinfirm.run.RunStatus;
import com.flowtivo.tinfirm.runner.ConnectedHost;
import com.flowtivo.tinfirm.runner.HostScope;
import com.flowtivo.tinfirm.runner.RunnerMessages;
import com.flowtivo.tinfirm.shared.licence.Feature;
import com.flowtivo.tinfirm.shared.licence.Licensing;
import com.flowtivo.tinfirm.shared.tx.SystemTransactions;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.vault.DataKeys;
import com.flowtivo.tinfirm.vault.ScanReport;
import com.flowtivo.tinfirm.vault.VaultService;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

/**
 * Keeping session bundles (ADR-0097; the Kubernetes runner analysis §6.5), part of elastic runners (ADR-0100,
 * {@code ELASTIC_RUNNERS}): the session folders of a conversation's agent, which a company runner host uploads at the
 * end of a run and the next run's host restores, so that a turn resumes its session on any company host.
 * <ul>
 * <li>Only while the licence in force holds {@code ELASTIC_RUNNERS}, for company hosts that list
 * {@code session.bundle}, and only while a store is configured ({@code tinfirm.session-bundles.store}); a personal
 * host keeps its homes on its owner's machine (ADR-0007). The host's endpoints answer {@code 403 EDITION_REQUIRED}
 * without the feature ({@link RunnerSessionBundleController}).</li>
 * <li>An upload is checked as untrusted, since the agent wrote it: at most
 * {@value RunnerMessages#MAX_SESSION_BUNDLE_BYTES} bytes, and scanned for the company's vault secrets and the known
 * credential formats as files are (ADR-0014); a bundle with a match, or one that cannot be scanned through, is
 * refused, and the conversation's earlier bundle goes with it.</li>
 * <li>An upload streams (ADR-0101): the scan reads it as it arrives, and what it reads is hashed, sealed and written to
 * the store as it passes; the object is committed only once the scan found it clean, else it is discarded. No more than
 * about a chunk of the bundle, its ciphertext and a part of the store is held at once. A host uploads one bundle at
 * a time, and a body must arrive within {@link #READ_DEADLINE}, so a slow host holds one of the
 * {@value #CONCURRENT_UPLOADS} uploads for at most that long (the security review of 2026-10-27, L24).</li>
 * <li>Each bundle gets a fresh data key, which the vault's master key wraps ({@link DataKeys}); the object, named by a
 * random UUID, holds only ciphertext ({@link BundleCipher}).</li>
 * <li>One bundle per conversation and agent, the newest; a run gets it only for its {@code resumeSessionId}, of its
 * runtime.</li>
 * </ul>
 * What goes of the bundles, in every edition, is the core's ({@link SessionBundleCleanup}).
 */
@Component
class SessionBundlesImpl implements SessionBundles {

    private static final Logger log = LoggerFactory.getLogger(SessionBundlesImpl.class);

    /** Uploads at once: each holds a chunk of its bundle and of its ciphertext, and a part of the store. */
    private static final int CONCURRENT_UPLOADS = 4;

    /** How long an upload may take to send its body (the security review of 2026-10-27, L24). */
    static final Duration READ_DEADLINE = Duration.ofSeconds(30);

    /**
     * How long an upload waits for its host's slot: the host's other runs that end at the same time upload one after
     * the other, each within {@link #READ_DEADLINE}; the host waits 300 seconds for an answer.
     */
    static final Duration SLOT_WAIT = Duration.ofSeconds(120);

    private final BundleObjects objects;

    private final SessionBundleRows rows;

    private final SessionBundleStore store;

    private final SessionBundleCleanup cleanup;

    private final RunReads runs;

    private final RunStore runStore;

    private final DataKeys dataKeys;

    private final VaultService vault;

    private final SystemTransactions transactions;

    private final TimeSource time;

    private final Licensing licensing;

    private final Semaphore uploads = new Semaphore(CONCURRENT_UPLOADS);

    /** One upload at a time per host (the machine): its slot, while it lasts. */
    private final Cache<UUID, Semaphore> hostSlots = Caffeine.newBuilder().weakValues().build();

    SessionBundlesImpl(BundleObjects objects, SessionBundleRows rows, SessionBundleStore store,
            SessionBundleCleanup cleanup, RunReads runs, RunStore runStore, DataKeys dataKeys, VaultService vault,
            SystemTransactions transactions, TimeSource time, Licensing licensing) {
        this.objects = objects;
        this.rows = rows;
        this.store = store;
        this.cleanup = cleanup;
        this.runs = runs;
        this.runStore = runStore;
        this.dataKeys = dataKeys;
        this.vault = vault;
        this.transactions = transactions;
        this.time = time;
        this.licensing = licensing;
    }

    /** Whether the server keeps bundles: a store is configured and the licence in force holds the feature. */
    boolean enabled() {
        return cleanup.enabled() && licensing.has(Feature.ELASTIC_RUNNERS);
    }

    // ---- the assignment -------------------------------------------------------------------------------------------

    @Override
    public RunnerMessages.@Nullable SessionBundle forAssignment(UUID conversationId, UUID agentId, String runtime,
            HostScope scope, boolean feature, @Nullable String resumeSessionId) {
        if (!feature || scope != HostScope.COMPANY || !enabled()) {
            return null;
        }
        return rows.find(conversationId, agentId)
            .filter(bundle -> bundle.runtime().equals(runtime))
            .filter(bundle -> bundle.sessionId().equals(resumeSessionId))
            .map(bundle -> new RunnerMessages.SessionBundle(bundle.sha256(), (long) bundle.sizeBytes()))
            .orElseGet(RunnerMessages.SessionBundle::uploadOnly);
    }

    // ---- the runner host's upload ---------------------------------------------------------------------------------

    /** The answer to an upload, for the runner host: {@code STORED} or {@code REFUSED}, and why. */
    record UploadAnswer(String status, String message, @Nullable String sha256) {

        static UploadAnswer refused(String message) {
            return new UploadAnswer("REFUSED", message, null);
        }
    }

    /**
     * Stores the bundle {@code body} of the run, which must be RUNNING on the company host with the epoch; empty when
     * it is not (the controller answers 404).
     */
    Optional<UploadAnswer> upload(ConnectedHost host, UUID runId, long epoch, String sessionId, InputStream body)
            throws IOException {
        Optional<RunInfo> found = transactions.inCompany(host.companyId(), () -> runs.find(host.companyId(), runId)
            .filter(run -> running(run, host, epoch)));
        if (found.isEmpty()) {
            return Optional.empty();
        }
        RunInfo run = found.get();
        if (!enabled()) {
            return Optional.of(UploadAnswer.refused("This server keeps no session bundles."));
        }
        if (sessionId.isBlank() || sessionId.length() > 255 || sessionId.chars().anyMatch(Character::isISOControl)) {
            return Optional.of(UploadAnswer.refused("The session id is not one."));
        }
        Semaphore slot = hostSlots.get(host.machineId(), id -> new Semaphore(1));
        if (!acquire(slot, SLOT_WAIT)) {
            return Optional.of(UploadAnswer.refused("Another session bundle of this host was being stored for "
                    + SLOT_WAIT.toSeconds() + " seconds."));
        }
        try {
            if (!acquire(uploads, Duration.ofSeconds(30))) {
                throw new IOException("Too many session bundles are being stored at once");
            }
            try {
                return Optional.of(store(run, host, epoch, sessionId, new Within(body, READ_DEADLINE,
                        System::nanoTime)));
            }
            catch (BodyTooSlow ex) {
                log.warn("Run {} of conversation {}: its session bundle was not received within {} seconds", run.id(),
                        run.conversationKey(), READ_DEADLINE.toSeconds());
                return Optional.of(UploadAnswer.refused("The bundle was not received within "
                        + READ_DEADLINE.toSeconds() + " seconds."));
            }
            finally {
                uploads.release();
            }
        }
        finally {
            slot.release();
        }
    }

    private static boolean acquire(Semaphore semaphore, Duration wait) throws IOException {
        try {
            return semaphore.tryAcquire(wait.toMillis(), TimeUnit.MILLISECONDS);
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting to store a session bundle", ex);
        }
    }

    /** A body that took longer than its deadline to arrive. */
    static final class BodyTooSlow extends IOException {

        private static final long serialVersionUID = 1L;

        BodyTooSlow() {
            super("The body was not received in time");
        }
    }

    /**
     * The body as the upload reads it, refusing one that is not complete within the deadline. The deadline is checked
     * before and after every read, so that bytes a read returned past it are refused too; a read that waits for bytes
     * that do not come ends with the connection's own read timeout. Past the deadline the upload ends with
     * {@link BodyTooSlow}, and nothing is committed.
     */
    static final class Within extends FilterInputStream {

        private final long end;

        /** The clock, {@link System#nanoTime} but in tests. */
        private final LongSupplier nanos;

        Within(InputStream body, Duration deadline, LongSupplier nanos) {
            super(body);
            this.nanos = nanos;
            this.end = nanos.getAsLong() + deadline.toNanos();
        }

        @Override
        public int read() throws IOException {
            check();
            int value = in.read();
            check();
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            check();
            int count = in.read(buffer, offset, length);
            check();
            return count;
        }

        private void check() throws BodyTooSlow {
            if (nanos.getAsLong() - end > 0) {
                throw new BodyTooSlow();
            }
        }

        @Override
        public boolean markSupported() {
            return false;
        }
    }

    private UploadAnswer store(RunInfo run, ConnectedHost host, long epoch, String sessionId, InputStream body)
            throws IOException {
        UUID objectName = UUID.randomUUID();
        Passing passing;
        ScanReport scan;
        DataKeys.WrappedKey wrapped;
        // The scan's values are read in the company's transaction; the scan itself waits on the host outside one.
        try (DataKeys.DataKey key = dataKeys.create(context(run.companyId(), run.conversationId(), run.agentId(),
                objectName));
                BundleObjects.Upload upload = objects.upload(objectName);
                BundleCipher.Sealing sealing = BundleCipher.sealing(key.secretKey(), objectName, upload.stream());
                VaultService.ContentScanner scanner = transactions.inCompany(run.companyId(),
                        () -> vault.scanner(run.companyId()))) {
            passing = new Passing(body, sealing);
            // Written in the sandbox, where the agent may be root: scanned as a file is, as it arrives.
            scan = scanner.scan(passing);
            passing.transferTo(OutputStream.nullOutputStream());
            if (passing.tooLarge || passing.count == 0 || !scan.clean()) {
                // Not committed: the upload leaves no object.
                wrapped = null;
            }
            else {
                sealing.finish();
                upload.commit();
                wrapped = key.wrapped();
            }
        }
        if (passing.tooLarge) {
            dropRefused(run, "it is larger than the server's " + RunnerMessages.MAX_SESSION_BUNDLE_BYTES + " bytes");
            return UploadAnswer.refused("The bundle is larger than the server's "
                    + RunnerMessages.MAX_SESSION_BUNDLE_BYTES + " bytes.");
        }
        if (passing.count == 0) {
            return UploadAnswer.refused("The bundle is empty.");
        }
        if (!scan.clean() || wrapped == null) {
            String why = !scan.vaultSecrets().isEmpty() ? "it holds the value of the vault secret(s) "
                    + String.join(", ", scan.vaultSecrets())
                    : !scan.formats().isEmpty() ? "it holds what looks like a credential (" + String.join(", ",
                            scan.formats()) + ")" : "it cannot be scanned through (" + scan.unscannable() + ")";
            dropRefused(run, why);
            return UploadAnswer.refused("The bundle was not stored: " + why + ".");
        }
        String sha256 = HexFormat.of().formatHex(passing.digest.digest());
        int size = (int) passing.count;
        Instant now = time.now();
        SessionBundleRows.BundleRow row = new SessionBundleRows.BundleRow(run.companyId(), run.conversationId(),
                run.agentId(), run.runtime().name(), sessionId, objectName, size, sha256, wrapped.keyId(),
                wrapped.wrapped(), wrapped.nonce(), run.id(), now);
        boolean recorded;
        try {
            recorded = Boolean.TRUE.equals(transactions.inCompany(run.companyId(), () -> {
                // The run is still this one on this host: else the bundle is not the session's.
                if (runs.find(run.companyId(), run.id()).filter(again -> running(again, host, epoch)).isEmpty()) {
                    return false;
                }
                rows.replace(row).ifPresent(cleanup::deleteAfterCommit);
                return true;
            }));
        }
        catch (RuntimeException ex) {
            cleanup.deleteQuietly(objectName);
            throw ex;
        }
        if (!recorded) {
            cleanup.deleteQuietly(objectName);
            return UploadAnswer.refused("The run is no longer running on this host.");
        }
        log.info("Run {} of conversation {} stored its session bundle of session {} ({} bytes) as object {}",
                run.id(), run.conversationKey(), sessionId, size, objectName);
        return new UploadAnswer("STORED", "Stored the session bundle.", sha256);
    }

    /**
     * The host's upload as the scan reads it: counted, hashed and sealed into the object as it passes. Past
     * {@value RunnerMessages#MAX_SESSION_BUNDLE_BYTES} bytes it ends, and says the bundle was too large.
     */
    private static final class Passing extends FilterInputStream {

        private final OutputStream sealing;

        private final MessageDigest digest = sha256Digest();

        private long count;

        private boolean tooLarge;

        Passing(InputStream body, OutputStream sealing) {
            super(body);
            this.sealing = sealing;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n < 0 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (tooLarge) {
                return -1;
            }
            int n = in.read(buffer, offset, length);
            if (n <= 0) {
                return n;
            }
            if (count + n > RunnerMessages.MAX_SESSION_BUNDLE_BYTES) {
                tooLarge = true;
                return -1;
            }
            count += n;
            digest.update(buffer, offset, n);
            sealing.write(buffer, offset, n);
            return n;
        }

        @Override
        public long skip(long n) throws IOException {
            // Every byte passes through read, so that the object holds the whole bundle.
            byte[] skipped = new byte[(int) Math.min(n, 8192)];
            int read = read(skipped, 0, skipped.length);
            return Math.max(read, 0);
        }

        @Override
        public boolean markSupported() {
            return false;
        }

        @Override
        public void close() {
            // The request's body is the container's to close.
        }
    }

    /** A refused bundle: the conversation's earlier one no longer holds the session, and goes. */
    private void dropRefused(RunInfo run, String why) {
        log.warn("Run {} of conversation {}: its session bundle was refused, {}", run.id(), run.conversationKey(),
                why);
        transactions.runInCompany(run.companyId(), () -> store.delete(run.conversationId(), run.agentId())
            .ifPresent(cleanup::deleteAfterCommit));
    }

    // ---- the runner host's download -------------------------------------------------------------------------------

    /** A bundle as its host downloads it: its size, its SHA-256 and its content, decrypted as it is read. */
    record Download(long sizeBytes, String sha256, InputStream content) {
    }

    /**
     * The bundle of the run's conversation and agent, of its runtime and of the session the run resumes (the one
     * {@code run.assign} named), for its company host with the epoch while the run is active (assigned and awaiting
     * {@code run.accepted}, or running); empty otherwise.
     */
    Optional<Download> download(ConnectedHost host, UUID runId, long epoch) throws IOException {
        if (!enabled()) {
            return Optional.empty();
        }
        Optional<SessionBundleRows.BundleRow> found = transactions.inCompany(host.companyId(), () -> runs
            .find(host.companyId(), runId)
            .filter(run -> assigned(run, host, epoch))
            .flatMap(run -> rows.find(run.conversationId(), run.agentId())
                .filter(bundle -> bundle.runtime().equals(run.runtime().name()))
                .filter(bundle -> resumes(run).filter(bundle.sessionId()::equals).isPresent())));
        if (found.isEmpty()) {
            return Optional.empty();
        }
        SessionBundleRows.BundleRow bundle = found.get();
        Optional<InputStream> sealed = objects.open(bundle.objectName());
        if (sealed.isEmpty()) {
            log.warn("The session bundle object {} of conversation {} is missing", bundle.objectName(),
                    bundle.conversationId());
            return Optional.empty();
        }
        try (DataKeys.DataKey key = dataKeys.unwrap(context(bundle.companyId(), bundle.conversationId(),
                bundle.agentId(), bundle.objectName()), new DataKeys.WrappedKey(bundle.keyId(), bundle.wrappedKey(),
                        bundle.keyNonce()))) {
            return Optional.of(new Download(bundle.sizeBytes(), bundle.sha256(),
                    BundleCipher.decrypt(key.secretKey(), bundle.objectName(), sealed.get())));
        }
        catch (RuntimeException | IOException ex) {
            sealed.get().close();
            log.warn("The session bundle object {} of conversation {} cannot be opened", bundle.objectName(),
                    bundle.conversationId(), ex);
            return Optional.empty();
        }
    }

    // ---- helpers --------------------------------------------------------------------------------------------------

    /** The AAD of a bundle's data key, the core's, which the vault's rewrap uses too ({@link SessionBundleKeys}). */
    private static String context(UUID companyId, UUID conversationId, UUID agentId, UUID objectName) {
        return SessionBundleKeys.context(companyId, conversationId, agentId, objectName);
    }

    /** The session the run resumes, as its assignment named it: the conversation's latest earlier one. */
    private Optional<String> resumes(RunInfo run) {
        return runStore.previousWithSession(run.conversationId(), run.id(), run.createdAt())
            .map(RunStore.RunRow::sessionId);
    }

    /** The run is RUNNING on the company host with the epoch. */
    private static boolean running(RunInfo run, ConnectedHost host, long epoch) {
        return run.status() == RunStatus.RUNNING && assigned(run, host, epoch);
    }

    /** The run is active on the company host with the epoch: assigned and awaiting acceptance, or running. */
    private static boolean assigned(RunInfo run, ConnectedHost host, long epoch) {
        return (run.status() == RunStatus.QUEUED || run.status() == RunStatus.RUNNING) && run.epoch() == epoch
                && host.runnerHostId().equals(run.runnerHostId()) && run.runnerHostKind() == HostScope.COMPANY;
    }

    static String sha256(byte[] content) {
        return HexFormat.of().formatHex(sha256Digest().digest(content));
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        }
        catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
