package com.flowtivo.tinfirm.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;

import com.flowtivo.tinfirm.ee.licence.EnterpriseLicence;
import com.flowtivo.tinfirm.shared.security.Actor;
import com.flowtivo.tinfirm.shared.tx.SystemTransactions;
import com.flowtivo.tinfirm.support.ApiClient;
import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.IntegrationTest;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The export of the audit chain by sequence and by time (the package 6 contract, §5.8, §9 {@code AuditExportTest}),
 * verified offline by ADR-0010's rule from its first {@code prevHash} to the head, with a verifier of the test's own.
 */
@ExtendWith(EnterpriseLicence.class)
class AuditExportTest extends IntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private AuditLog auditLog;

    @Autowired
    private SystemTransactions transactions;

    @Test
    void theWholeChainIsExportedAndVerifiesOfflineAndTheExportIsAudited() {
        TestCompany company = companyWithRoles();
        ApiClient admin = loginAs(company.email("ADMIN"));
        List<JsonNode> events = auditEvents(admin, company);
        long head = events.getFirst().get("seq").asLong();

        ApiResponse export = admin.get(company.path("/audit-events/export"));

        assertThat(export.status()).as("%s", export).isEqualTo(200);
        assertThat(export.contentType()).startsWith("application/x-ndjson");
        List<JsonNode> lines = lines(export.body());
        JsonNode first = lines.getFirst();
        assertThat(first.get("kind").asString()).isEqualTo("export");
        assertThat(first.get("fromSeq").asLong()).isEqualTo(1);
        assertThat(first.get("toSeq").asLong()).isEqualTo(head);
        assertThat(first.get("count").asLong()).isEqualTo(head);
        assertThat(first.get("prevHash").asString()).isEqualTo("0".repeat(64));
        JsonNode last = lines.getLast();
        assertThat(last.get("kind").asString()).isEqualTo("head");
        assertThat(last.get("seq").asLong()).isEqualTo(head);
        assertThat(lines).hasSize((int) head + 2);
        assertThat(verify(lines)).isTrue();
        // Personal data stays out: the export holds what the chain holds.
        assertThat(export.body()).doesNotContain(company.email("OWNER"));

        JsonNode audited = auditEvents(admin, company, "audit.exported").getFirst();
        assertThat(audited.get("actor").get("id").asString()).isEqualTo(company.userId("ADMIN").toString());
        assertThat(audited.get("details").get("fromSeq").asLong()).isEqualTo(1);
        assertThat(audited.get("details").get("toSeq").asLong()).isEqualTo(head);
        assertThat(audited.get("details").get("count").asLong()).isEqualTo(head);

        // A changed line no longer verifies.
        List<JsonNode> tampered = new ArrayList<>(lines);
        tampered.set(2, JSON.readTree(lines.get(2).toString().replace("\"action\":\"", "\"action\":\"x")));
        assertThat(verify(tampered)).isFalse();
    }

    @Test
    void aRangeBySequenceChainsFromItsFirstPrevHash() {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        List<JsonNode> events = auditEvents(owner, company);
        JsonNode third = events.get(events.size() - 3);
        JsonNode fifth = events.get(events.size() - 5);

        List<JsonNode> lines = lines(owner.get(company.path("/audit-events/export?fromSeq=3&toSeq=5")).body());

        assertThat(lines).hasSize(5);
        assertThat(lines.getFirst().get("prevHash").asString()).isEqualTo(third.get("prevHash").asString());
        assertThat(lines.get(1).get("seq").asLong()).isEqualTo(3);
        assertThat(lines.get(3).get("hash").asString()).isEqualTo(fifth.get("hash").asString());
        assertThat(verify(lines)).isTrue();
        // Beyond the head is the head.
        List<JsonNode> tail = lines(owner.get(company.path("/audit-events/export?fromSeq=2&toSeq=99999")).body());
        assertThat(tail.getFirst().get("toSeq").asLong()).isEqualTo(tail.getLast().get("seq").asLong());
        assertThat(verify(tail)).isTrue();
    }

    @Test
    void aRangeByTimeIsTheContiguousRangeOfItsEvents() {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        List<JsonNode> events = auditEvents(owner, company).reversed();
        String from = events.get(2).get("occurredAt").asString();
        String to = events.get(events.size() - 1).get("occurredAt").asString();

        List<JsonNode> lines = lines(owner.get(company.path("/audit-events/export?from=" + from + "&to=" + to)).body());

        JsonNode first = lines.getFirst();
        // The contiguous range by seq from the first event at or after from to the last one before to, among the
        // events up to the head the export saw: a transaction's events are appended at its commit with the times of
        // their calls, so a time may go back from one seq to the next, and an event appended after the listing above
        // may still fall in the range (CONVENTIONS §8).
        long headSeq = lines.getLast().get("seq").asLong();
        List<JsonNode> chain = auditEvents(owner, company).stream()
            .filter(event -> event.get("seq").asLong() <= headSeq)
            .toList();
        long fromSeq = chain.stream()
            .filter(event -> !occurredAt(event).isBefore(java.time.OffsetDateTime.parse(from)))
            .mapToLong(event -> event.get("seq").asLong())
            .min()
            .orElseThrow();
        long toSeq = chain.stream()
            .filter(event -> occurredAt(event).isBefore(java.time.OffsetDateTime.parse(to)))
            .mapToLong(event -> event.get("seq").asLong())
            .max()
            .orElseThrow();
        assertThat(first.get("fromSeq").asLong()).isEqualTo(fromSeq);
        assertThat(first.get("toSeq").asLong()).isEqualTo(toSeq);
        assertThat(first.get("from").asString()).isNotNull();
        assertThat(lines.get(lines.size() - 2).get("seq").asLong()).isEqualTo(first.get("toSeq").asLong());
        assertThat(verify(lines)).isTrue();
        JsonNode audited = auditEvents(owner, company, "audit.exported").getFirst();
        assertThat(audited.get("details").get("from").asString()).isNotNull();

        // A time without events exports none, with the head.
        List<JsonNode> none = lines(owner.get(company.path("/audit-events/export?to=2000-01-01T00:00:00Z")).body());
        assertThat(none).hasSize(2);
        assertThat(none.getFirst().get("count").asLong()).isZero();
        assertThat(none.getLast().get("kind").asString()).isEqualTo("head");
    }

    private static java.time.OffsetDateTime occurredAt(JsonNode event) {
        return java.time.OffsetDateTime.parse(event.get("occurredAt").asString());
    }

    @Test
    void anExportOverTheLimitAndAMixedOrInvertedRangeAreRefused() {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        // The test profile's limit is 200 events.
        transactions.runInCompany(company.id(), () -> {
            for (int i = 0; i < 200; i++) {
                auditLog.record(AuditRecord.company(company.id(), AuditAction.CONVERSATION_READ,
                        Actor.user(company.userId("OWNER")))
                    .target("conversation", UUID.randomUUID())
                    .detail("kind", "DETAIL")
                    .build());
            }
        });

        ApiResponse tooLarge = owner.get(company.path("/audit-events/export"));
        assertThat(tooLarge.status()).isEqualTo(400);
        assertThat(tooLarge.problemCode()).isEqualTo("AUDIT_EXPORT_TOO_LARGE");
        List<JsonNode> part = lines(owner.get(company.path("/audit-events/export?fromSeq=1&toSeq=150")).body());
        assertThat(part).hasSize(152);
        assertThat(verify(part)).isTrue();

        assertThat(owner.get(company.path("/audit-events/export?fromSeq=1&from=2020-01-01T00:00:00Z")).problemCode())
            .isEqualTo("VALIDATION_FAILED");
        assertThat(owner.get(company.path("/audit-events/export?fromSeq=5&toSeq=2")).problemCode())
            .isEqualTo("VALIDATION_FAILED");
        assertThat(loginAs(company.email("MEMBER")).get(company.path("/audit-events/export")).status())
            .isEqualTo(403);
    }

    /** ADR-0010's rule, independently of the server: each event chains from the one before, the first from line 1. */
    static boolean verify(List<JsonNode> lines) {
        String previous = lines.getFirst().get("prevHash").asString();
        for (JsonNode line : lines.subList(1, lines.size() - 1)) {
            Map<String, Object> event = sorted(line);
            String prevHash = (String) event.remove("prevHash");
            String hash = (String) event.remove("hash");
            if (!prevHash.equals(previous) || !hash.equals(sha256(prevHash + "\n" + JSON.writeValueAsString(event)))) {
                return false;
            }
            previous = hash;
        }
        JsonNode head = lines.getLast();
        return lines.size() == 2 || head.get("seq").asLong() != lines.get(lines.size() - 2).get("seq").asLong()
                || head.get("hash").asString().equals(previous);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> sorted(JsonNode node) {
        Map<String, Object> map = JSON.convertValue(node, Map.class);
        return (Map<String, Object>) sortedValue(map);
    }

    @SuppressWarnings("unchecked")
    private static Object sortedValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            TreeMap<String, Object> sorted = new TreeMap<>();
            map.forEach((key, child) -> sorted.put((String) key, sortedValue(child)));
            return sorted;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(AuditExportTest::sortedValue).toList();
        }
        return value;
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.UTF_8)));
        }
        catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    static List<JsonNode> lines(String body) {
        List<JsonNode> lines = new ArrayList<>();
        for (String line : body.split("\n")) {
            if (!line.isBlank()) {
                lines.add(JSON.readTree(line));
            }
        }
        return lines;
    }
}
