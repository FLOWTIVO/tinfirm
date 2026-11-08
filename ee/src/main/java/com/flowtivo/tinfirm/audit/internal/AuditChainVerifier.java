package com.flowtivo.tinfirm.audit.internal;

import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.shared.api.model.AuditCheckpointHead;
import com.flowtivo.tinfirm.shared.api.model.AuditVerification;

/**
 * Recomputes a chain from its first event and reports the first position that does not verify: a changed event
 * (hash mismatch), a broken link (prevHash mismatch), a missing event (gap in seq), or missing events at the end
 * (the head points past the last event).
 * <p>
 * The head is read first and only events up to it are checked, so events appended during verification do not
 * cause false alarms. The caller must be able to read the chain (company context or instance admin). Where the anchor
 * is in force (ADR-0111), the chain must also agree with the latest checkpoint outside the database that names it.
 */
@Component
class AuditChainVerifier {

    private static final int BATCH_SIZE = 500;

    private final AuditEventStore store;

    private final AuditAnchors anchors;

    AuditChainVerifier(AuditEventStore store, AuditAnchors anchors) {
        this.store = store;
        this.anchors = anchors;
    }

    AuditVerification verify(@Nullable UUID companyId) {
        ChainHead head = store.head(companyId).orElse(new ChainHead(0, AuditCanonicalForm.GENESIS_HASH));

        String previousHash = AuditCanonicalForm.GENESIS_HASH;
        long expectedSeq = 1;
        long checked = 0;
        Long firstInvalid = null;

        List<StoredAuditEvent> batch;
        do {
            batch = store.chainSlice(companyId, expectedSeq - 1, head.lastSeq(), BATCH_SIZE);
            for (StoredAuditEvent event : batch) {
                checked++;
                if (firstInvalid == null) {
                    if (event.seq() != expectedSeq) {
                        firstInvalid = expectedSeq;
                    }
                    else if (!event.prevHash().equals(previousHash) || !event.hash()
                        .equals(AuditCanonicalForm.hash(event.prevHash(), AuditCanonicalForm.canonical(event.content())))) {
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
                firstInvalid = Math.min(lastSeq, head.lastSeq()) + 1;
            }
            else if (!previousHash.equals(head.lastHash())) {
                firstInvalid = Math.max(lastSeq, 1);
            }
        }

        // ADR-0111: the chain must also agree with the latest checkpoint outside the database that names it, which a
        // chain recomputed by whoever could write the database does not.
        Optional<AuditAnchors.Mismatch> mismatch = anchors.compare(companyId);
        if (mismatch.isPresent()) {
            firstInvalid = firstInvalid == null ? mismatch.get().seq() : Math.min(firstInvalid, mismatch.get().seq());
        }
        AuditVerification verification = new AuditVerification(firstInvalid == null, checked)
            .firstInvalidSeq(firstInvalid);
        anchors.anchored(companyId, null).ifPresent(anchored -> verification.checkpoint(new AuditCheckpointHead(
                anchored.checkpoint(), anchored.issuedAt().atOffset(ZoneOffset.UTC), anchored.keyId(), anchored.seq(),
                anchored.hash())));
        return verification;
    }
}
