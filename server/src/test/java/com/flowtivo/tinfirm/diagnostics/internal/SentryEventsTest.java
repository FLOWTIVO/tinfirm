package com.flowtivo.tinfirm.diagnostics.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.diagnostics.internal.CapturedError.CapturedException;
import com.flowtivo.tinfirm.diagnostics.internal.CapturedError.CapturedFrame;
import com.flowtivo.tinfirm.diagnostics.internal.CapturedError.Source;
import com.flowtivo.tinfirm.diagnostics.internal.ErrorReportStore.ReportRow;
import com.flowtivo.tinfirm.diagnostics.internal.SentryEvents.Installation;

/** ADR-0070, §5 and §6: a report as a Sentry event holds the report's fields and the installation's id, no more. */
class SentryEventsTest {

    private static final UUID INSTANCE = UUID.fromString("0192f1e2-3b4c-7d5e-8f90-a1b2c3d4e5f6");

    private static final String FINGERPRINT = "a".repeat(64);

    @Test
    void anExceptionIsInSentrysOrderWithTheReportsFingerprint() {
        ReportRow row = row(List.of(
                new CapturedException("java.lang.RuntimeException", "outer", List.of(
                        new CapturedFrame("com.flowtivo.tinfirm.run.Turns", "start", "Turns.java", 10, null, true),
                        new CapturedFrame("org.springframework.Proxy", "invoke", "Proxy.java", 99, null, false))),
                new CapturedException("java.io.IOException", "inner", List.of())));

        Map<String, Object> event = SentryEvents.event(row, new Installation(INSTANCE, "acme-prod", "0.6.0"));

        assertThat(event).containsEntry("event_id", SentryEvents.eventId(row))
            .containsEntry("platform", "java")
            .containsEntry("level", "error")
            .containsEntry("server_name", "acme-prod")
            .containsEntry("release", "tinfirm@0.6.0")
            .containsEntry("transaction", "POST /api/companies/{companyId}/conversations")
            .containsEntry("fingerprint", List.of(FINGERPRINT));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> values = (List<Map<String, Object>>) ((Map<String, Object>) event.get("exception"))
            .get("values");
        assertThat(values).extracting(value -> value.get("type")).containsExactly("IOException", "RuntimeException");
        assertThat(values.get(1)).containsEntry("module", "java.lang").containsEntry("value", "outer");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> frames = (List<Map<String, Object>>) ((Map<String, Object>) values.get(1)
            .get("stacktrace")).get("frames");
        assertThat(frames).extracting(frame -> frame.get("function")).containsExactly("invoke", "start");
        assertThat(frames.get(1)).containsEntry("in_app", true).containsEntry("lineno", 10);
        assertThat(event.get("tags")).isEqualTo(Map.of("source", "server", "instance", INSTANCE.toString(), "label",
                "acme-prod", "commit", "abc1234"));
        assertThat(event.get("contexts")).isEqualTo(Map.of("runtime", Map.of("name", "Java", "version", "25.0.4")));
        assertThat(event.toString()).doesNotContain("@acme", "user", "company:");
    }

    @Test
    void aLogLineWithoutExceptionIsAMessageAndTheIdFollowsTheCount() {
        ReportRow row = row(List.of());
        Map<String, Object> event = SentryEvents.event(row, new Installation(INSTANCE, null, "0.6.0"));

        assertThat(event).containsEntry("message", Map.of("formatted", "Unhandled exception"))
            .containsEntry("server_name", INSTANCE.toString())
            .doesNotContainKey("exception");
        assertThat(SentryEvents.eventId(row)).matches("[0-9a-f]{32}")
            .isNotEqualTo(SentryEvents.eventId(new ReportRow(row.id(), row.fingerprint(), row.source(), row.title(),
                    row.message(), row.logger(), row.logMessage(), row.route(), row.runtime(), row.exceptions(), 6,
                    0, row.firstSeenAt(), row.lastSeenAt(), row.firstVersion(), row.lastVersion(), row.lastCommit(),
                    null)));
    }

    @Test
    void anEnvelopeIsItsHeaderTheItemsHeaderAndTheEvent() {
        byte[] event = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);
        String envelope = new String(SentryEvents.envelope("e1", event, Instant.parse("2026-10-16T12:00:00Z")),
                StandardCharsets.UTF_8);

        assertThat(envelope).isEqualTo("{\"event_id\":\"e1\",\"sent_at\":\"2026-10-16T12:00:00Z\"}\n"
                + "{\"type\":\"event\",\"content_type\":\"application/json\",\"length\":7}\n{\"a\":1}\n");
    }

    private static ReportRow row(List<CapturedException> exceptions) {
        return new ReportRow(UUID.randomUUID(), FINGERPRINT, Source.SERVER,
                exceptions.isEmpty() ? "com.flowtivo.tinfirm.shared.web.ApiExceptionHandler" : exceptions.getFirst()
                    .type(), "Unhandled exception", "com.flowtivo.tinfirm.shared.web.ApiExceptionHandler",
                "Unhandled exception", "POST /api/companies/{companyId}/conversations", "Java 25.0.4", exceptions, 5,
                2, Instant.parse("2026-10-15T08:00:00Z"), Instant.parse("2026-10-16T09:30:00Z"), "0.5.0", "0.6.0",
                "abc1234", null);
    }
}
