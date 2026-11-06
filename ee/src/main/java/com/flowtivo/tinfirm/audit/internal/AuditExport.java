package com.flowtivo.tinfirm.audit.internal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import com.flowtivo.tinfirm.audit.AuditAction;
import com.flowtivo.tinfirm.audit.AuditLog;
import com.flowtivo.tinfirm.audit.AuditRecord;
import com.flowtivo.tinfirm.shared.licence.Feature;
import com.flowtivo.tinfirm.shared.licence.LicenceStatus;
import com.flowtivo.tinfirm.shared.licence.Licensing;
import com.flowtivo.tinfirm.shared.security.Actor;
import com.flowtivo.tinfirm.shared.security.CurrentActor;
import com.flowtivo.tinfirm.shared.tx.SystemTransactions;
import com.flowtivo.tinfirm.shared.web.ApiException;
import com.flowtivo.tinfirm.shared.web.ProblemCode;

/**
 * The export of a contiguous range of a company's chain as NDJSON (the package 6 contract, §5.8): a line that states
 * the range and the hash the first event chains from, a line per event in its canonical form with {@code prevHash}
 * and {@code hash}, oldest first, and a line with the chain's head at the export. {@link #plan} checks the licence and
 * resolves the range in the caller's short transaction, after its access check; {@link #response} streams on a thread
 * of its own, outside any, reading 1000 events a transaction, and audits {@code audit.exported} once the last line is
 * written. A range by time resolves to the
 * contiguous range of {@code seq} from the first event at or after {@code from} to the last before {@code to}: a
 * chain's order is its {@code seq}, which {@code occurred_at} across replicas need not follow.
 */
@Component
class AuditExport {

    private static final Logger log = LoggerFactory.getLogger(AuditExport.class);

    /** NDJSON, which the generated interface declares as a string. */
    private static final MediaType NDJSON = MediaType.parseMediaType("application/x-ndjson");

    /** The bytes sent at a time. */
    private static final int CHUNK = 64 * 1024;

    /** How long an export may stream: a chain of a million events takes minutes. */
    private static final Duration EXPORT_TIMEOUT = Duration.ofHours(1);

    private static final int BATCH_SIZE = 1000;

    private final AuditEventStore store;

    private final AuditToolsStore range;

    private final SystemTransactions transactions;

    private final AuditLog auditLog;

    /** The most events one export holds: 1 000 000 (the contract's). */
    private final long maxEvents;

    private final Licensing licensing;

    AuditExport(AuditEventStore store, AuditToolsStore range, SystemTransactions transactions, AuditLog auditLog,
            @Value("${tinfirm.audit.export-max-events:1000000}") long maxEvents, Licensing licensing) {
        this.licensing = licensing;
        this.store = store;
        this.range = range;
        this.transactions = transactions;
        this.auditLog = auditLog;
        this.maxEvents = maxEvents;
    }

    /**
     * An export, resolved: the range {@code [fromSeq, toSeq]} ({@code count} 0 for none), the hash its first event
     * chains from, and the chain's head at the start.
     */
    record Plan(UUID companyId, UUID userId, long fromSeq, long toSeq, long count, @Nullable String prevHash,
            long headSeq, String headHash, @Nullable Instant from, @Nullable Instant to) {
    }

    /** In the caller's transaction, after its access check ({@code VIEW_AUDIT}). */
    Plan plan(UUID companyId, @Nullable Long fromSeq, @Nullable Long toSeq, @Nullable Instant from,
            @Nullable Instant to) {
        requireExport();
        UUID userId = CurrentActor.requireUser().id();
        boolean bySeq = fromSeq != null || toSeq != null;
        boolean byTime = from != null || to != null;
        if (bySeq && byTime) {
            throw ApiException.validation("Export by sequence (fromSeq, toSeq) or by time (from, to), not both");
        }
        if (fromSeq != null && toSeq != null && fromSeq > toSeq) {
            throw ApiException.validation("fromSeq must not be after toSeq");
        }
        if (from != null && to != null && !from.isBefore(to)) {
            throw ApiException.validation("from must be before to");
        }
        ChainHead head = store.head(companyId).orElse(new ChainHead(0, AuditCanonicalForm.GENESIS_HASH));
        long first;
        long last;
        if (byTime) {
            first = from == null ? 1 : range.firstSeqFrom(companyId, from).orElse(Long.MAX_VALUE);
            last = to == null ? head.lastSeq() : range.lastSeqBefore(companyId, to).orElse(0L);
        }
        else {
            first = fromSeq != null ? fromSeq : 1;
            last = toSeq != null ? toSeq : head.lastSeq();
        }
        last = Math.min(last, head.lastSeq());
        long count = last >= first ? last - first + 1 : 0;
        if (count > maxEvents) {
            throw ApiException.badRequest(ProblemCode.AUDIT_EXPORT_TOO_LARGE, "An export holds at most " + maxEvents
                    + " events; this range holds " + count + ". Export it in parts by fromSeq and toSeq.");
        }
        String prevHash = null;
        if (count > 0) {
            prevHash = first == 1 ? AuditCanonicalForm.GENESIS_HASH
                    : store.at(companyId, first).map(StoredAuditEvent::prevHash).orElse(null);
        }
        else {
            first = 0;
            last = 0;
        }
        return new Plan(companyId, userId, first, last, count, prevHash, head.lastSeq(), head.lastHash(), from, to);
    }

    /**
     * The response that streams the export on a thread of its own, outside any transaction: the generated interface
     * declares the body a string, and Spring MVC streams a {@link ResponseBodyEmitter} found in a
     * {@code ResponseEntity} at runtime, as {@code EventStreamSupport} does an emitter of events. The lines go out in
     * chunks of {@value #CHUNK} bytes.
     */
    @SuppressWarnings("unchecked")
    ResponseEntity<String> response(Plan plan) {
        ResponseBodyEmitter emitter = new ResponseBodyEmitter(EXPORT_TIMEOUT.toMillis());
        Thread.ofVirtual().name("audit-export-" + plan.companyId()).start(() -> {
            try (OutputStream out = new EmitterStream(emitter)) {
                write(plan, out);
            }
            catch (IOException | RuntimeException ex) {
                log.warn("The export of the audit chain of company {} failed", plan.companyId(), ex);
                emitter.completeWithError(ex);
                return;
            }
            emitter.complete();
        });
        ResponseEntity<?> response = ResponseEntity.ok()
            .contentType(NDJSON)
            .cacheControl(CacheControl.noStore())
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                .filename("audit-" + plan.companyId() + "-" + plan.fromSeq() + "-" + plan.toSeq() + ".ndjson")
                .build()
                .toString())
            .header("X-Accel-Buffering", "no")
            .body(emitter);
        return (ResponseEntity<String>) response;
    }

    /**
     * {@code 403 EDITION_REQUIRED} unless the export is in force (ADR-0077, decisions 4 and 7.3): with the audit's
     * tools, and for 90 days after a licence expired.
     */
    private void requireExport() {
        LicenceStatus status = licensing.status();
        LocalDate until = status.auditExportUntil();
        boolean afterExpiry = status.expired() && until != null
                && !LocalDate.now(ZoneOffset.UTC).isAfter(until);
        if (!status.has(Feature.AUDIT_TOOLS) && !afterExpiry) {
            throw Licensing.editionRequired(Feature.AUDIT_TOOLS);
        }
    }

    /** Streams the export, outside any transaction, and audits it once the last line is written. */
    void write(Plan plan, OutputStream out) throws IOException {
        Map<String, @Nullable Object> header = new LinkedHashMap<>();
        header.put("kind", "export");
        header.put("companyId", plan.companyId().toString());
        header.put("fromSeq", plan.fromSeq());
        header.put("toSeq", plan.toSeq());
        header.put("count", plan.count());
        header.put("prevHash", plan.prevHash());
        header.put("from", plan.from() != null ? plan.from().toString() : null);
        header.put("to", plan.to() != null ? plan.to().toString() : null);
        line(out, AuditCanonicalForm.exportLine(header));
        long written = 0;
        if (plan.count() > 0) {
            long after = plan.fromSeq() - 1;
            List<StoredAuditEvent> batch;
            do {
                long from = after;
                batch = transactions.inCompany(plan.companyId(),
                        () -> store.chainSlice(plan.companyId(), from, plan.toSeq(), BATCH_SIZE));
                for (StoredAuditEvent event : batch) {
                    line(out, AuditCanonicalForm.exportLine(event));
                    written++;
                    after = event.seq();
                }
            }
            while (batch.size() == BATCH_SIZE);
        }
        Map<String, @Nullable Object> trailer = new LinkedHashMap<>();
        trailer.put("kind", "head");
        trailer.put("seq", plan.headSeq());
        trailer.put("hash", plan.headHash());
        line(out, AuditCanonicalForm.exportLine(trailer));
        out.flush();

        long count = written;
        transactions.runInCompany(plan.companyId(), () -> {
            AuditRecord.Builder record = AuditRecord
                .company(plan.companyId(), AuditAction.AUDIT_EXPORTED, Actor.user(plan.userId()))
                .target("company", plan.companyId())
                .detail("fromSeq", plan.fromSeq())
                .detail("toSeq", plan.toSeq())
                .detail("count", count);
            if (plan.from() != null || plan.to() != null) {
                record.detail("from", plan.from() != null ? plan.from().toString() : null)
                    .detail("to", plan.to() != null ? plan.to().toString() : null);
            }
            auditLog.record(record.build());
        });
    }

    private static void line(OutputStream out, String json) throws IOException {
        out.write(json.getBytes(StandardCharsets.UTF_8));
        out.write('\n');
    }

    /** The bytes written to it, sent through the emitter in chunks. */
    private static final class EmitterStream extends OutputStream {

        private final ResponseBodyEmitter emitter;

        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream(CHUNK);

        EmitterStream(ResponseBodyEmitter emitter) {
            this.emitter = emitter;
        }

        @Override
        public void write(int b) throws IOException {
            buffer.write(b);
            if (buffer.size() >= CHUNK) {
                flush();
            }
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            buffer.write(bytes, offset, length);
            if (buffer.size() >= CHUNK) {
                flush();
            }
        }

        @Override
        public void flush() throws IOException {
            if (buffer.size() > 0) {
                emitter.send(buffer.toByteArray(), NDJSON);
                buffer.reset();
            }
        }

        @Override
        public void close() throws IOException {
            flush();
        }
    }
}
