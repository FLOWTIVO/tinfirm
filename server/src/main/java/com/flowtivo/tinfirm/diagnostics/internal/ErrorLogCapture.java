package com.flowtivo.tinfirm.diagnostics.internal;

import org.jspecify.annotations.Nullable;
import org.slf4j.ILoggerFactory;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.UnsynchronizedAppenderBase;

/**
 * Attaches an appender to the root logger while the application runs (ADR-0070, §1): every log event at {@code ERROR}
 * of the process, but the diagnostics module's own, becomes a captured error. The appender never throws, never logs,
 * and does not capture an error raised while it captures, so that a failure of its own cannot loop. Nothing is
 * attached while the mode is {@code OFF}. Spring Boot resets Logback when another application context starts in the
 * same process (the tests' contexts), which drops the appender: {@link #ensureAttached()} attaches it again, and each
 * flush asks it to.
 */
@Component
class ErrorLogCapture implements SmartLifecycle {

    static final String APPENDER_NAME = "tinfirm-error-reports";

    private static final String OWN_PACKAGE = "com.flowtivo.tinfirm.diagnostics.";

    private final ErrorCapture capture;

    private @Nullable Appender appender;

    ErrorLogCapture(ErrorCapture capture) {
        this.capture = capture;
    }

    @Override
    public synchronized void start() {
        if (appender == null) {
            attach();
        }
    }

    /** Attaches the appender again if a reset of Logback dropped it, while running. */
    synchronized void ensureAttached() {
        Appender attached = appender;
        if (attached != null && (!attached.isStarted() || !(attached.getContext() instanceof LoggerContext context)
                || !context.getLogger(Logger.ROOT_LOGGER_NAME).isAttached(attached))) {
            attach();
        }
    }

    private void attach() {
        ILoggerFactory factory = LoggerFactory.getILoggerFactory();
        if (!capture.on() || !(factory instanceof LoggerContext context)) {
            return;
        }
        Appender attached = new Appender(capture);
        attached.setContext(context);
        attached.setName(APPENDER_NAME);
        attached.start();
        context.getLogger(Logger.ROOT_LOGGER_NAME).addAppender(attached);
        appender = attached;
    }

    @Override
    public synchronized void stop() {
        Appender attached = appender;
        if (attached != null && attached.getContext() instanceof LoggerContext context) {
            context.getLogger(Logger.ROOT_LOGGER_NAME).detachAppender(attached);
            attached.stop();
        }
        appender = null;
    }

    @Override
    public synchronized boolean isRunning() {
        return appender != null;
    }

    /** Started first and stopped last, so that the errors of the other beans' start and stop are captured too. */
    @Override
    public int getPhase() {
        return Integer.MIN_VALUE + 1000;
    }

    private static final class Appender extends UnsynchronizedAppenderBase<ILoggingEvent> {

        private static final ThreadLocal<Boolean> CAPTURING = new ThreadLocal<>();

        private final ErrorCapture capture;

        Appender(ErrorCapture capture) {
            this.capture = capture;
        }

        @Override
        protected void append(ILoggingEvent event) {
            if (!event.getLevel().isGreaterOrEqual(Level.ERROR) || CAPTURING.get() != null) {
                return;
            }
            String logger = event.getLoggerName();
            if (logger != null && logger.startsWith(OWN_PACKAGE)) {
                return;
            }
            CAPTURING.set(Boolean.TRUE);
            try {
                capture.add(ServerErrors.of(event));
            }
            catch (RuntimeException | LinkageError ex) {
                // Never into the log: an error of the capture is not one of the application.
            }
            finally {
                CAPTURING.remove();
            }
        }
    }
}
