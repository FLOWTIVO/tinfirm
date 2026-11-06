package com.flowtivo.tinfirm.audit.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class AuditCanonicalFormTest {

    private static final UUID COMPANY = UUID.fromString("01929d3a-0000-7000-8000-000000000001");

    private static final UUID ACTOR = UUID.fromString("01929d3a-0000-7000-8000-000000000002");

    @Test
    void canonicalJsonSortsKeysRecursivelyWithoutWhitespace() throws Exception {
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("z", 1);
        nested.put("a", Arrays.asList(true, null, "x"));
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("role", "OWNER");
        details.put("b", nested);

        String canonical = AuditCanonicalForm.canonical(new AuditCanonicalForm.Content(COMPANY, 7,
                Instant.parse("2026-01-02T03:04:05.123456Z"), "USER", ACTOR, "member.added", "user", "abc",
                AuditCanonicalForm.detailsTree(details)));

        assertThat(canonical).isEqualTo("{\"action\":\"member.added\",\"actor\":{\"id\":\"" + ACTOR
                + "\",\"type\":\"USER\"},\"companyId\":\"" + COMPANY
                + "\",\"details\":{\"b\":{\"a\":[true,null,\"x\"],\"z\":1},\"role\":\"OWNER\"},"
                + "\"occurredAt\":\"2026-01-02T03:04:05.123456Z\",\"seq\":7,\"targetId\":\"abc\",\"targetType\":\"user\"}");

        String prevHash = "0".repeat(64);
        byte[] expected = MessageDigest.getInstance("SHA-256")
            .digest((prevHash + "\n" + canonical).getBytes(StandardCharsets.UTF_8));
        assertThat(AuditCanonicalForm.hash(prevHash, canonical)).isEqualTo(HexFormat.of().formatHex(expected))
            .hasSize(64);
    }

    @Test
    void instanceEventsHaveNullCompanyAndTimestampsAlwaysHaveMicroseconds() {
        String canonical = AuditCanonicalForm.canonical(new AuditCanonicalForm.Content(null, 1,
                Instant.parse("2026-01-02T03:04:05Z"), "SYSTEM", null, "user.created", null, null,
                AuditCanonicalForm.detailsTree(Map.of())));

        assertThat(canonical).isEqualTo("{\"action\":\"user.created\",\"actor\":{\"id\":null,\"type\":\"SYSTEM\"},"
                + "\"companyId\":null,\"details\":{},\"occurredAt\":\"2026-01-02T03:04:05.000000Z\",\"seq\":1,"
                + "\"targetId\":null,\"targetType\":null}");
    }

    @Test
    void detailsSurviveAJsonbStyleRoundTrip() {
        Map<String, Object> details = Map.of("name", "Ünïcødé \"quoted\"", "version", 3, "flag", false);
        String stored = AuditCanonicalForm.canonicalJson(AuditCanonicalForm.detailsTree(details));
        // PostgreSQL returns jsonb with its own key order and spacing.
        String fromDatabase = "{\"flag\": false, \"name\": \"Ünïcødé \\\"quoted\\\"\", \"version\": 3}";

        assertThat(AuditCanonicalForm.canonicalJson(AuditCanonicalForm.parseDetails(fromDatabase))).isEqualTo(stored);
    }

    @Test
    void rejectsFractionalNumbers() {
        assertThatThrownBy(() -> AuditCanonicalForm.detailsTree(Map.of("ratio", 0.5)))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
