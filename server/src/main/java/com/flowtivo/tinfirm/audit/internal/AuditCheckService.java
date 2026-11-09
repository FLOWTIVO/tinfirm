package com.flowtivo.tinfirm.audit.internal;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.flowtivo.tinfirm.audit.AuditChecks;
import com.flowtivo.tinfirm.shared.api.model.AuditCheck;
import com.flowtivo.tinfirm.shared.config.TinfirmProperties;
import com.flowtivo.tinfirm.shared.id.Ids;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.tx.SystemTransactions;

/**
 * The daily check of every audit chain (the package 6 contract, §3.6, §5.8), on replicas with the {@code worker}
 * role: each chain not checked for {@code check-interval} (a day) is checked from the head the previous check recorded,
 * the anchor (the latest check that passed, or whose failure an OWNER acknowledged; the chain's start before any):
 * <ol>
 * <li>{@code SHORTER}: the event at the anchor is gone, or the head row is behind it: the chain was cut, which leaves a
 * valid but shorter chain (ADR-0010) that a full verification does not notice;</li>
 * <li>{@code HEAD_CHANGED}: the event at the anchor has another hash: the chain was rewritten from there or before;</li>
 * <li>{@code BROKEN}: an event after the anchor does not verify (a changed event, a broken link, a gap, or a head row
 * that points past the last event or at another hash), {@code first_invalid_seq} the first;</li>
 * <li>{@code ANCHOR_MISMATCH} (ADR-0111): the chain verifies, but the event at the latest checkpoint's {@code seq}
 * outside the database is gone or has another hash: the chain was rewritten or cut, and recomputed, by someone who
 * could write the database. {@code first_invalid_seq} is the checkpoint's {@code seq}, {@code anchor_checkpoint} its
 * number. Compared only where the anchor is in force ({@link AuditAnchors}, under {@code ee/}).</li>
 * </ol>
 * A check that passed records the head it verified ({@code head_seq}, {@code head_hash}: the chain's head row as it
 * read it before the verification, which the verified events reach), and anchors the next check there; it never
 * records an event appended while it ran, which it did not verify (writers lock the head row, the check does not, so
 * the chain may grow under it; the next check verifies the growth). A check that failed records the chain's last
 * event as found, so that, acknowledged, it anchors the next check past the damage. A failure is logged at
 * {@code ERROR} at every check until one passes or it is acknowledged, and is an item of the inbox of OWNER and ADMIN
 * meanwhile ({@link AuditChecks}). A check of a chain locks it for the transaction (an advisory lock), so that two
 * workers check it once. The last {@value AuditCheckStore#KEPT} checks of a chain are kept, and always the anchor.
 * The check is not audited; its acknowledgement is.
 * <p>
 * The check runs in every edition; its results, the list of checks, their acknowledgement and the inbox's item, are
 * the audit's tools ({@link AuditTools}, ADR-0077, decision 4), under {@code ee/}.
 */
@Service
class AuditCheckService implements AuditChecks {

    private static final Logger log = LoggerFactory.getLogger(AuditCheckService.class);

    private static final int BATCH_SIZE = 500;

    private final AuditEventStore events;

    private final AuditCheckStore checks;

    private final SystemTransactions transactions;

    private final TimeSource time;

    private final boolean worker;

    private final Duration interval;

    private final ObjectProvider<AuditTools> tools;

    private final ObjectProvider<AuditAnchors> anchors;

    AuditCheckService(AuditEventStore events, AuditCheckStore checks, SystemTransactions transactions,
            TimeSource time, TinfirmProperties tinfirm,
            @Value("${tinfirm.audit.check-interval:P1D}") Duration interval, ObjectProvider<AuditTools> tools,
            ObjectProvider<AuditAnchors> anchors) {
        this.tools = tools;
        this.anchors = anchors;
        this.events = events;
        this.checks = checks;
        this.transactions = transactions;
        this.time = time;
        this.worker = tinfirm.hasRole(TinfirmProperties.WORKER_ROLE);
        this.interval = interval;
    }

    @Scheduled(fixedDelayString = "${tinfirm.audit.check-tick:PT1H}",
            initialDelayString = "${tinfirm.audit.check-initial-delay:PT10M}")
    void scheduledCheck() {
        if (worker) {
            try {
                checkDue();
            }
            catch (RuntimeException ex) {
                log.warn("The daily check of the audit chains failed to run", ex);
            }
        }
    }

    /**
     * Checks every chain due; safe to run on several workers at once.
     *
     * @return how many chains were checked
     */
    int checkDue() {
        Instant checkedBefore = time.now().minus(interval);
        int checked = 0;
        for (AuditCheckStore.Due due : transactions.unscoped(() -> checks.due(checkedBefore))) {
            try {
                if (check(due.companyId()).isPresent()) {
                    checked++;
                }
            }
            catch (RuntimeException ex) {
                log.warn("The daily check of the audit chain of {} failed to run",
                        due.companyId() == null ? "the instance" : due.companyId(), ex);
            }
        }
        return checked;
    }

    /** Checks one chain now, in its own transaction; empty when another worker checks it at the moment. */
    Optional<AuditCheckStore.CheckRow> check(@Nullable UUID companyId) {
        Optional<AuditCheckStore.CheckRow> result = companyId != null
                ? transactions.inCompany(companyId, () -> checkLocked(companyId))
                : transactions.instanceWide(() -> checkLocked(null));
        result.filter(row -> !row.ok()).ifPresent(row -> log.error(
                "The daily check of the audit chain of {} failed: {} (from seq {}, first invalid seq {}, {} events"
                        + " checked{}); an OWNER acknowledges it on the audit page once it is understood",
                companyId == null ? "the instance" : "company " + companyId, row.failure(), row.fromSeq(),
                row.firstInvalidSeq(), row.eventsChecked(),
                row.anchorCheckpoint() != null ? ", checkpoint " + row.anchorCheckpoint() : ""));
        return result;
    }

    private Optional<AuditCheckStore.CheckRow> checkLocked(@Nullable UUID companyId) {
        if (!checks.tryLock(companyId)) {
            return Optional.empty();
        }
        Optional<AuditCheckStore.CheckRow> anchor = checks.anchor(companyId);
        long fromSeq = anchor.map(AuditCheckStore.CheckRow::headSeq).orElse(0L);
        String fromHash = anchor.map(AuditCheckStore.CheckRow::headHash).orElse(AuditCanonicalForm.GENESIS_HASH);
        ChainHead head = events.head(companyId).orElse(new ChainHead(0, AuditCanonicalForm.GENESIS_HASH));

        String failure = null;
        Long firstInvalid = null;
        long checked = 0;
        if (fromSeq > 0) {
            Optional<StoredAuditEvent> atAnchor = events.at(companyId, fromSeq);
            if (atAnchor.isEmpty() || head.lastSeq() < fromSeq) {
                failure = "SHORTER";
            }
            else if (!atAnchor.get().hash().equals(fromHash)) {
                failure = "HEAD_CHANGED";
            }
        }
        if (failure == null) {
            String previousHash = fromHash;
            long expectedSeq = fromSeq + 1;
            List<StoredAuditEvent> batch;
            do {
                batch = events.chainSlice(companyId, expectedSeq - 1, head.lastSeq(), BATCH_SIZE);
                for (StoredAuditEvent event : batch) {
                    checked++;
                    if (firstInvalid == null) {
                        if (event.seq() != expectedSeq) {
                            firstInvalid = expectedSeq;
                        }
                        else if (!event.prevHash().equals(previousHash) || !event.hash().equals(
                                AuditCanonicalForm.hash(event.prevHash(), AuditCanonicalForm.canonical(event.content())))) {
                            firstInvalid = event.seq();
                        }
                    }
                    previousHash = event.hash();
                    expectedSeq = event.seq() + 1;
                }
            }
            while (batch.size() == BATCH_SIZE);
            long lastSeq = expectedSeq - 1;
            if (firstInvalid == null) {
                if (lastSeq != head.lastSeq()) {
                    firstInvalid = lastSeq + 1;
                }
                else if (!previousHash.equals(head.lastHash())) {
                    firstInvalid = Math.max(lastSeq, 1);
                }
            }
            if (firstInvalid != null) {
                failure = "BROKEN";
            }
        }
        Long anchorCheckpoint = null;
        if (failure == null) {
            AuditAnchors found = anchors.getIfAvailable();
            Optional<AuditAnchors.Mismatch> mismatch = found != null ? found.compare(companyId) : Optional.empty();
            if (mismatch.isPresent()) {
                failure = "ANCHOR_MISMATCH";
                firstInvalid = mismatch.get().seq();
                anchorCheckpoint = mismatch.get().checkpoint();
            }
        }

        // The head the next check starts from once this one is accepted. Passed: the head this check verified (the
        // head row as read above, which the verified events reached), never an event appended since, which it did
        // not verify. Failed: the chain's last event as stored, so that an acknowledged failure anchors past the
        // damage (SHORTER, BROKEN).
        long headSeq;
        String headHash;
        if (failure == null) {
            headSeq = head.lastSeq();
            headHash = head.lastHash();
        }
        else {
            Optional<StoredAuditEvent> last = events.last(companyId);
            headSeq = last.map(StoredAuditEvent::seq).orElse(0L);
            headHash = last.map(StoredAuditEvent::hash).orElse(AuditCanonicalForm.GENESIS_HASH);
        }
        AuditCheckStore.CheckRow row = new AuditCheckStore.CheckRow(Ids.newId(), companyId, time.now(),
                failure == null, fromSeq, failure, firstInvalid, headSeq, headHash, checked, null, null, null,
                anchorCheckpoint);
        checks.insert(row);
        checks.prune(companyId);
        return Optional.of(row);
    }

    /** The results are the audit's tools (ADR-0077, decision 4), under {@code ee/}: none without them. */
    @Override
    @Transactional(readOnly = true)
    public Optional<AuditCheck> openFailure(UUID companyId) {
        AuditTools found = tools.getIfAvailable();
        return found != null ? found.openFailure(companyId) : Optional.empty();
    }
}
