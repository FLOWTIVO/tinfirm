package com.flowtivo.tinfirm.audit.internal;

import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * TEST FIXTURE ONLY: key pairs made for each test run, the anchor's settings around them, and chains of audit events
 * built in memory, as the server's chain and its export hold them.
 */
final class AnchorFixtures {

    private AnchorFixtures() {
    }

    /** A key of the configuration that may sign. */
    static AuditAnchorProperties.Key signing(KeyPair pair) {
        return new AuditAnchorProperties.Key(AnchorKeys.pem("PRIVATE KEY", pair.getPrivate().getEncoded()), null);
    }

    /** A retired key of the configuration, which only verifies. */
    static AuditAnchorProperties.Key retired(KeyPair pair) {
        return new AuditAnchorProperties.Key(null, AnchorKeys.publicPem(pair.getPublic()));
    }

    static AuditAnchorProperties properties(AuditAnchorProperties.Sink sink, @Nullable Path dir, String activeKey,
            Map<String, AuditAnchorProperties.Key> keys) {
        return new AuditAnchorProperties(sink, Duration.ofHours(1), keys, activeKey,
                new AuditAnchorProperties.S3(null, "us-east-1", null, false, "audit-checkpoints/", null, null,
                        Duration.ofDays(400), true),
                new AuditAnchorProperties.File(dir), new AuditAnchorProperties.Syslog(null, 6514, "tls"),
                Duration.ofMinutes(1), Duration.ofMinutes(1), Duration.ofMinutes(5));
    }

    static AnchorKeys keys(String activeKey, Map<String, AuditAnchorProperties.Key> keys) {
        return AnchorKeys.of(properties(AuditAnchorProperties.Sink.NONE, null, activeKey, keys));
    }

    static Map<String, AuditAnchorProperties.Key> keyMap(Object... idsAndKeys) {
        Map<String, AuditAnchorProperties.Key> keys = new LinkedHashMap<>();
        for (int i = 0; i < idsAndKeys.length; i += 2) {
            keys.put((String) idsAndKeys[i], (AuditAnchorProperties.Key) idsAndKeys[i + 1]);
        }
        return keys;
    }

    /** A chain of {@code length} events, each linked to the one before, as the server stores them. */
    static List<StoredAuditEvent> chain(@Nullable UUID companyId, int length) {
        List<StoredAuditEvent> events = new ArrayList<>();
        String previous = AuditCanonicalForm.GENESIS_HASH;
        Instant at = Instant.parse("2026-11-01T08:00:00.123456Z");
        for (int seq = 1; seq <= length; seq++) {
            StoredAuditEvent event = event(companyId, seq, at.plusSeconds(seq), "{\"n\":" + seq + "}", previous);
            events.add(event);
            previous = event.hash();
        }
        return events;
    }

    /** An event with its hash computed from its content and the hash it chains from. */
    static StoredAuditEvent event(@Nullable UUID companyId, long seq, Instant at, String details, String prevHash) {
        AuditCanonicalForm.Content content = new AuditCanonicalForm.Content(companyId, seq, at, "SYSTEM", null,
                "company.updated", "company", companyId != null ? companyId.toString() : null,
                AuditCanonicalForm.parseDetails(details));
        String hash = AuditCanonicalForm.hash(prevHash, AuditCanonicalForm.canonical(content));
        return new StoredAuditEvent(UUID.randomUUID(), companyId, seq, at, "SYSTEM", null, "company.updated",
                "company", companyId != null ? companyId.toString() : null, details, prevHash, hash);
    }

    /** The lines of an export of the whole chain, as {@code GET …/audit-events/export} writes them. */
    static List<String> export(UUID companyId, List<StoredAuditEvent> events) {
        List<String> lines = new ArrayList<>();
        Map<String, @Nullable Object> header = new LinkedHashMap<>();
        header.put("kind", "export");
        header.put("companyId", companyId.toString());
        header.put("fromSeq", events.getFirst().seq());
        header.put("toSeq", events.getLast().seq());
        header.put("count", events.size());
        header.put("prevHash", events.getFirst().prevHash());
        header.put("from", null);
        header.put("to", null);
        header.put("checkpoint", null);
        lines.add(AuditCanonicalForm.exportLine(header));
        for (StoredAuditEvent event : events) {
            lines.add(AuditCanonicalForm.exportLine(event));
        }
        Map<String, @Nullable Object> head = new LinkedHashMap<>();
        head.put("kind", "head");
        head.put("seq", events.getLast().seq());
        head.put("hash", events.getLast().hash());
        lines.add(AuditCanonicalForm.exportLine(head));
        return lines;
    }

    static Checkpoint.Head head(@Nullable UUID companyId, StoredAuditEvent event) {
        return new Checkpoint.Head(companyId, event.seq(), event.hash());
    }
}
