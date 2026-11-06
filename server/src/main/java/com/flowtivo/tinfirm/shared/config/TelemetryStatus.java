package com.flowtivo.tinfirm.shared.config;

/**
 * Whether the installation sends anything to the vendor, as {@code SystemInfo.telemetryEnabled} says. Implemented by
 * the diagnostics module: true exactly while its error reports are delivered automatically to a destination
 * (ADR-0070). Without an implementation, nothing is sent.
 */
public interface TelemetryStatus {

    boolean enabled();
}
