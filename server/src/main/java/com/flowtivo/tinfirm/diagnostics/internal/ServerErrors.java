package com.flowtivo.tinfirm.diagnostics.internal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;

import org.jspecify.annotations.Nullable;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.HandlerMapping;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.StackTraceElementProxy;

import com.flowtivo.tinfirm.diagnostics.internal.CapturedError.CapturedException;
import com.flowtivo.tinfirm.diagnostics.internal.CapturedError.CapturedFrame;
import com.flowtivo.tinfirm.diagnostics.internal.CapturedError.Source;

/**
 * A log event of the server as a captured error (ADR-0070, §1, §2): the logger, the log line's template (its
 * {@code {}} placeholders unfilled, never its arguments) masked, each exception's type and masked message and its
 * frames, and the route's pattern of the request it was logged in.
 */
final class ServerErrors {

    static final String APP_PACKAGE = "com.flowtivo.tinfirm.";

    static final int MAX_EXCEPTIONS = 5;

    static final int MAX_FRAMES = 40;

    private static final String RUNTIME = "Java " + System.getProperty("java.version", "unknown");

    private ServerErrors() {
    }

    static CapturedError of(ILoggingEvent event) {
        String logger = Scrubber.name(event.getLoggerName(), 200);
        logger = logger == null ? "unknown" : logger;
        String template = Scrubber.mask(event.getMessage());
        List<CapturedException> exceptions = exceptions(event.getThrowableProxy());
        String title = exceptions.isEmpty() ? logger : exceptions.getFirst().type();
        String message = exceptions.isEmpty() ? template : exceptions.getFirst().message();
        return new CapturedError(Source.SERVER, CapturedError.serverFingerprint(logger, template, exceptions), title,
                message, logger, template, route(), RUNTIME, exceptions);
    }

    private static List<CapturedException> exceptions(@Nullable IThrowableProxy throwable) {
        List<CapturedException> exceptions = new ArrayList<>();
        Set<IThrowableProxy> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (IThrowableProxy current = throwable; current != null && exceptions.size() < MAX_EXCEPTIONS
                && seen.add(current); current = current.getCause()) {
            String type = Scrubber.name(current.getClassName(), 200);
            exceptions.add(new CapturedException(type == null ? "unknown" : type, Scrubber.mask(current.getMessage()),
                    frames(current.getStackTraceElementProxyArray())));
        }
        return exceptions;
    }

    private static List<CapturedFrame> frames(StackTraceElementProxy @Nullable [] proxies) {
        if (proxies == null) {
            return List.of();
        }
        List<CapturedFrame> frames = new ArrayList<>();
        for (StackTraceElementProxy proxy : proxies) {
            if (frames.size() == MAX_FRAMES) {
                break;
            }
            StackTraceElement element = proxy.getStackTraceElement();
            String module = Scrubber.name(element.getClassName(), 200);
            frames.add(new CapturedFrame(module, Scrubber.name(element.getMethodName(), 200),
                    Scrubber.name(element.getFileName(), 200), element.getLineNumber() >= 0 ? element.getLineNumber()
                            : null, null, module != null && module.startsWith(APP_PACKAGE)));
        }
        return frames;
    }

    /** {@code GET /api/companies/{companyId}/...}: the method and the handler's pattern, never the URL. */
    private static @Nullable String route() {
        try {
            RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
            if (attributes instanceof ServletRequestAttributes servlet) {
                HttpServletRequest request = servlet.getRequest();
                Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
                if (pattern != null) {
                    return Scrubber.name(request.getMethod() + " " + pattern, 300);
                }
            }
        }
        catch (RuntimeException ex) {
            // A request that already ended has no pattern to give.
        }
        return null;
    }
}
