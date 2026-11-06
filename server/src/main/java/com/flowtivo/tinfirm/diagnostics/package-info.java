/**
 * Diagnostics (ADR-0070): the error reports of the installation. Every log event at {@code ERROR} of the server's
 * process and every error the web app's {@code ErrorHandler} posts is scrubbed from an allowlist when captured,
 * grouped by its fingerprint into one report per kind of error, and kept at most 90 days; instance admins list,
 * download and delete the reports, and choose whether a {@code worker} replica delivers them in Sentry's protocol to
 * the vendor's error tracker, unless the operator set the mode ({@code TINFIRM_ERROR_REPORTS}).
 * <p>
 * API: {@link com.flowtivo.tinfirm.diagnostics.RemoteText}, the scrubber for a remote system's text that a module keeps
 * for good (the audit's refusal reasons). It stands on {@code shared} and {@code audit}, implements
 * {@code shared.config.TelemetryStatus}
 * (what {@code SystemInfo.telemetryEnabled} says) and serves {@code /api/client-errors},
 * {@code /api/admin/error-reports/**} and {@code /api/admin/error-reporting}.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Diagnostics")
package com.flowtivo.tinfirm.diagnostics;
