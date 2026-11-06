package com.flowtivo.tinfirm.diagnostics.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * One occurrence of an error, already scrubbed (ADR-0070, §2): what a report keeps of it, and its fingerprint (§3).
 *
 * @param title      the outermost exception's type; a log line's logger without one
 * @param message    the outermost exception's masked message; a log line's masked template without one
 * @param logger     the server's logger
 * @param logMessage the server's log line's masked template
 * @param route      the route's pattern
 * @param runtime    the Java version, or the browser's name and major version
 * @param exceptions the outermost first, then its causes
 */
record CapturedError(Source source, String fingerprint, String title, @Nullable String message,
        @Nullable String logger, @Nullable String logMessage, @Nullable String route, @Nullable String runtime,
        List<CapturedException> exceptions) {

    /** Where an error happened. */
    enum Source {
        SERVER, WEB
    }

    /** An exception of an occurrence: its type, masked message and frames, the innermost first. */
    record CapturedException(String type, @Nullable String message, List<CapturedFrame> frames) {
    }

    /** A stack frame; {@code module} is the Java class. */
    record CapturedFrame(@Nullable String module, @Nullable String function, @Nullable String file,
            @Nullable Integer line, @Nullable Integer column, boolean inApp) {
    }

    /** The frames per exception that group an error. */
    private static final int GROUPING_FRAMES = 5;

    /** A lambda's or a generated class's number changes with the code around it. */
    private static final Pattern GENERATED = Pattern.compile("\\$\\$.*$|\\$\\d+|/0x[0-9a-f]+");

    /**
     * The fingerprint of a server error: its logger and template, and each exception's type with its first five frames
     * of Tinfirm's code (of any code when it has none), without line numbers, which every change moves.
     */
    static String serverFingerprint(String logger, @Nullable String template, List<CapturedException> exceptions) {
        StringBuilder key = new StringBuilder("SERVER\n").append(logger).append('\n')
            .append(template == null ? "" : template).append('\n');
        for (CapturedException exception : exceptions) {
            key.append(exception.type()).append('\n');
            List<CapturedFrame> frames = exception.frames().stream().filter(CapturedFrame::inApp).toList();
            if (frames.isEmpty()) {
                frames = exception.frames();
            }
            frames.stream().limit(GROUPING_FRAMES).forEach(frame -> key
                .append(GENERATED.matcher(String.valueOf(frame.module())).replaceAll(""))
                .append('.')
                .append(GENERATED.matcher(String.valueOf(frame.function())).replaceAll(""))
                .append('\n'));
        }
        return sha256(key.toString());
    }

    /** The fingerprint of a web app's error: its type, masked message and route, since minified frames change. */
    static String webFingerprint(String kind, @Nullable String message, @Nullable String route) {
        return sha256("WEB\n" + kind + "\n" + (message == null ? "" : message) + "\n" + (route == null ? "" : route));
    }

    private static String sha256(String key) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(key.getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
