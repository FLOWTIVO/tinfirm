package com.flowtivo.tinfirm.diagnostics.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;

import com.flowtivo.tinfirm.diagnostics.internal.CapturedError.CapturedException;

/** ADR-0070, §1 to §3: a server's log event keeps its template, types, masked messages and frames. */
class ServerErrorsTest {

    private final Logger logger = ((LoggerContext) LoggerFactory.getILoggerFactory()).getLogger("test.ServerErrors");

    @Test
    void keepsTheTemplateNotItsArgumentsAndMasksTheExceptionsMessages() {
        IllegalStateException cause = new IllegalStateException("token ghp_A1b2C3d4E5f6G7h8I9j0 refused");
        RuntimeException outer = new RuntimeException("Sync of 'Payroll' for alice@acme.example failed", cause);
        CapturedError error = ServerErrors.of(event("Sync of system {} failed for {}", outer, "Jira", "alice"));

        assertThat(error.source()).isEqualTo(CapturedError.Source.SERVER);
        assertThat(error.logger()).isEqualTo("test.ServerErrors");
        assertThat(error.logMessage()).isEqualTo("Sync of system {} failed for {}");
        assertThat(error.title()).isEqualTo("java.lang.RuntimeException");
        assertThat(error.message()).isEqualTo("Sync of <str> for <email> failed");
        assertThat(error.runtime()).startsWith("Java ");
        assertThat(error.exceptions()).extracting(CapturedException::type)
            .containsExactly("java.lang.RuntimeException", "java.lang.IllegalStateException");
        assertThat(error.exceptions().get(1).message()).isEqualTo("token <token> refused");
        assertThat(error.exceptions().getFirst().frames().getFirst().module()).isEqualTo(getClass().getName());
        assertThat(error.exceptions().getFirst().frames().getFirst().inApp()).isTrue();
        assertThat(error.exceptions().getFirst().frames()).hasSizeLessThanOrEqualTo(ServerErrors.MAX_FRAMES);
        assertThat(error.toString()).doesNotContain("alice@", "Payroll", "ghp_", "Jira");
    }

    @Test
    void aLogLineWithoutAnExceptionIsTitledByItsLogger() {
        CapturedError error = ServerErrors.of(event("The daily check of {} failed: {}", null, "x", "y"));

        assertThat(error.title()).isEqualTo("test.ServerErrors");
        assertThat(error.message()).isEqualTo("The daily check of {} failed: {}");
        assertThat(error.exceptions()).isEmpty();
    }

    @Test
    void groupsTheSameErrorAcrossMessagesAndLines() {
        String first = ServerErrors.of(event("Unhandled exception", failure("for 1"))).fingerprint();
        String second = ServerErrors.of(event("Unhandled exception", failure("for 2"))).fingerprint();
        String other = ServerErrors.of(event("Unhandled exception", new IllegalArgumentException("x"))).fingerprint();

        assertThat(first).isEqualTo(second).matches("[0-9a-f]{64}");
        assertThat(other).isNotEqualTo(first);
    }

    private static IllegalStateException failure(String message) {
        return new IllegalStateException(message);
    }

    private LoggingEvent event(String template, Throwable throwable, Object... arguments) {
        return new LoggingEvent(Logger.class.getName(), logger, Level.ERROR, template, throwable, arguments);
    }
}
