package com.flowtivo.tinfirm.diagnostics.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.shared.time.TimeSource;

/**
 * ADR-0070, §4 and §5: the operator's mode wins and is never read from the database; a wrong DSN, label or proxy stops
 * the server with the reason.
 */
class ErrorReportingSettingsTest {

    private static final String DSN = "https://0123456789abcdef0123456789abcdef@errors.example.com/1";

    @Test
    void theOperatorsModeIsTheModeWithoutAnyRead() {
        ErrorReportingSettings automatic = settings(ErrorReportingMode.AUTOMATIC, DSN, "acme-staging", null);
        assertThat(automatic.setByOperator()).isTrue();
        assertThat(automatic.mode()).isEqualTo(ErrorReportingMode.AUTOMATIC);
        assertThat(automatic.delivering()).isTrue();
        assertThat(automatic.enabled()).isTrue();
        assertThat(automatic.destination().host()).isEqualTo("errors.example.com");
        assertThat(automatic.label()).isEqualTo("acme-staging");

        ErrorReportingSettings withoutDestination = settings(ErrorReportingMode.AUTOMATIC, " ", null, null);
        assertThat(withoutDestination.delivering()).as("automatic without a DSN sends nothing").isFalse();
        assertThat(settings(ErrorReportingMode.LOCAL, DSN, null, null).delivering()).isFalse();
        assertThat(settings(ErrorReportingMode.OFF, DSN, null, null).enabled()).isFalse();
    }

    @Test
    void aProxyIsAnHttpHostAndPort() {
        assertThat(settings(ErrorReportingMode.LOCAL, null, null, "http://proxy.acme.internal:3128").proxy())
            .satisfies(proxy -> {
                assertThat(proxy.getHostString()).isEqualTo("proxy.acme.internal");
                assertThat(proxy.getPort()).isEqualTo(3128);
            });
        assertThat(settings(ErrorReportingMode.LOCAL, null, null, "").proxy()).isNull();
        assertThatThrownBy(() -> settings(ErrorReportingMode.LOCAL, null, null, "https://proxy:3128"))
            .hasMessageContaining("TINFIRM_ERROR_REPORTS_PROXY");
        assertThatThrownBy(() -> settings(ErrorReportingMode.LOCAL, null, null, "http://user:pw@proxy:3128"))
            .hasMessageContaining("TINFIRM_ERROR_REPORTS_PROXY");
    }

    @Test
    void aWrongDsnOrLabelStopsTheServer() {
        assertThatThrownBy(() -> settings(null, "https://errors.example.com/1", null, null))
            .hasMessageContaining("TINFIRM_ERROR_REPORTS_DSN");
        assertThatThrownBy(() -> settings(null, DSN, "Acme Production!", null))
            .hasMessageContaining("TINFIRM_ERROR_REPORTS_LABEL");
    }

    private static ErrorReportingSettings settings(@Nullable ErrorReportingMode mode, @Nullable String dsn,
            @Nullable String label, @Nullable String proxy) {
        ErrorReportProperties properties = new ErrorReportProperties(mode, dsn, label, proxy, 1000, 250,
                Duration.ofDays(90), 200, 20, 50, 100, Duration.ofMinutes(10), Duration.ofSeconds(20));
        // No store, transactions or audit: with the operator's mode nothing is read or written.
        return new ErrorReportingSettings(properties, null, null, null, new TimeSource(Clock.systemUTC()));
    }
}
