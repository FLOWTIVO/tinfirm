package com.flowtivo.tinfirm.diagnostics.internal;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.flowtivo.tinfirm.diagnostics.internal.CapturedError.CapturedException;
import com.flowtivo.tinfirm.diagnostics.internal.CapturedError.CapturedFrame;
import com.flowtivo.tinfirm.diagnostics.internal.CapturedError.Source;
import com.flowtivo.tinfirm.diagnostics.internal.ErrorReportStore.ReportRow;

/**
 * A report as the Sentry event that delivery sends and the download holds (ADR-0070, §5, §6): exactly the report's
 * own fields, the installation's random id and label, and nothing else. The event's id follows from the report and its
 * count, so that a delivery that is sent again (its answer lost) is one event at the tracker, and the report's
 * fingerprint is the event's, so that the tracker groups as the installation does.
 */
final class SentryEvents {

    static final String SDK = "tinfirm.error-reports";

    private SentryEvents() {
    }

    /** The installation as its events name it. */
    record Installation(UUID instanceId, @Nullable String label, String version) {
    }

    static String eventId(ReportRow row) {
        return UUID.nameUUIDFromBytes((row.fingerprint() + ":" + row.occurrences()).getBytes(StandardCharsets.UTF_8))
            .toString()
            .replace("-", "");
    }

    static Map<String, Object> event(ReportRow row, Installation installation) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("event_id", eventId(row));
        event.put("timestamp", row.lastSeenAt().toString());
        event.put("platform", row.source() == Source.SERVER ? "java" : "javascript");
        event.put("level", "error");
        if (row.logger() != null) {
            event.put("logger", row.logger());
        }
        event.put("server_name", installation.label() != null ? installation.label()
                : installation.instanceId().toString());
        event.put("release", "tinfirm@" + row.lastVersion());
        if (row.route() != null) {
            event.put("transaction", row.route());
        }
        if (row.exceptions().isEmpty()) {
            event.put("message", Map.of("formatted", row.message() != null ? row.message() : row.title()));
        }
        else {
            List<Map<String, Object>> values = new ArrayList<>();
            // Sentry's order: the outermost exception last, and in each the innermost frame last.
            List<CapturedException> exceptions = new ArrayList<>(row.exceptions());
            Collections.reverse(exceptions);
            for (CapturedException exception : exceptions) {
                values.add(exception(exception, row.source()));
            }
            event.put("exception", Map.of("values", values));
        }
        event.put("fingerprint", List.of(row.fingerprint()));
        Map<String, Object> tags = new LinkedHashMap<>();
        tags.put("source", row.source().name().toLowerCase(Locale.ROOT));
        tags.put("instance", installation.instanceId().toString());
        if (installation.label() != null) {
            tags.put("label", installation.label());
        }
        if (row.lastCommit() != null) {
            tags.put("commit", row.lastCommit());
        }
        event.put("tags", tags);
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("occurrences", row.occurrences());
        extra.put("occurrences_since_last_report", row.unreported());
        extra.put("first_seen", row.firstSeenAt().toString());
        extra.put("first_version", row.firstVersion());
        if (row.logMessage() != null) {
            extra.put("log_message", row.logMessage());
        }
        event.put("extra", extra);
        Map<String, Object> contexts = runtime(row);
        if (!contexts.isEmpty()) {
            event.put("contexts", contexts);
        }
        event.put("sdk", Map.of("name", SDK, "version", installation.version()));
        return event;
    }

    private static Map<String, Object> exception(CapturedException exception, Source source) {
        Map<String, Object> value = new LinkedHashMap<>();
        int dot = exception.type().lastIndexOf('.');
        if (source == Source.SERVER && dot > 0) {
            value.put("type", exception.type().substring(dot + 1));
            value.put("module", exception.type().substring(0, dot));
        }
        else {
            value.put("type", exception.type());
        }
        if (exception.message() != null) {
            value.put("value", exception.message());
        }
        List<Map<String, Object>> frames = new ArrayList<>();
        List<CapturedFrame> innermostLast = new ArrayList<>(exception.frames());
        Collections.reverse(innermostLast);
        for (CapturedFrame frame : innermostLast) {
            Map<String, Object> out = new LinkedHashMap<>();
            if (frame.module() != null) {
                out.put("module", frame.module());
            }
            if (frame.function() != null) {
                out.put("function", frame.function());
            }
            if (frame.file() != null) {
                out.put("filename", frame.file());
                if (source == Source.WEB) {
                    out.put("abs_path", frame.file());
                }
            }
            if (frame.line() != null) {
                out.put("lineno", frame.line());
            }
            if (frame.column() != null) {
                out.put("colno", frame.column());
            }
            out.put("in_app", frame.inApp());
            frames.add(out);
        }
        if (!frames.isEmpty()) {
            value.put("stacktrace", Map.of("frames", frames));
        }
        return value;
    }

    /** {@code Java 25.0.4}, {@code Chrome 141}: the runtime's or the browser's context. */
    private static Map<String, Object> runtime(ReportRow row) {
        String runtime = row.runtime();
        int space = runtime == null ? -1 : runtime.lastIndexOf(' ');
        if (runtime == null || space <= 0) {
            return Map.of();
        }
        Map<String, Object> context = Map.of("name", runtime.substring(0, space), "version",
                runtime.substring(space + 1));
        return Map.of(row.source() == Source.SERVER ? "runtime" : "browser", context);
    }

    /** The envelope of one event: its header, the item's header and the event, one JSON a line. */
    static byte[] envelope(String eventId, byte[] event, Instant sentAt) {
        String header = "{\"event_id\":\"" + eventId + "\",\"sent_at\":\"" + sentAt + "\"}\n"
                + "{\"type\":\"event\",\"content_type\":\"application/json\",\"length\":" + event.length + "}\n";
        byte[] head = header.getBytes(StandardCharsets.UTF_8);
        byte[] envelope = new byte[head.length + event.length + 1];
        System.arraycopy(head, 0, envelope, 0, head.length);
        System.arraycopy(event, 0, envelope, head.length, event.length);
        envelope[envelope.length - 1] = '\n';
        return envelope;
    }
}
