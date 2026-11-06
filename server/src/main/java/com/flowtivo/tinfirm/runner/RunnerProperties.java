package com.flowtivo.tinfirm.runner;

import java.time.Duration;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

/**
 * Runner protocol settings (docs/archive/v1/CONVENTIONS.md §14.6, §15.1).
 *
 * @param leaseTtl          lease of a running run, renewed by heartbeats and the other frames of its host
 *                          ({@code TINFIRM_RUNNER_LEASE_TTL})
 * @param heartbeatInterval heartbeat interval announced in {@code welcome}; also the keep-alive ping interval
 *                          ({@code TINFIRM_RUNNER_HEARTBEAT_INTERVAL})
 * @param maxMessageBytes   largest inbound text frame; larger frames close the connection (1009)
 * @param reconnectGrace    when the server starts, and when a host's connection closes, the runs that hold a slot
 *                          keep a lease of at least this long, so a restart or a network interruption does not lose
 *                          them ({@code TINFIRM_RUNNER_RECONNECT_GRACE})
 * @param lostResumeWindow  a run lost because its lease expired is resumed when its host reports it again within this
 *                          time; zero turns resuming off ({@code TINFIRM_RUNNER_LOST_RESUME_WINDOW})
 * @param toolCallsInFlight the {@code TOOL} requests of one run that may wait for their answer at once; one more is
 *                          answered {@code REJECTED TOO_MANY_REQUESTS} ({@code TINFIRM_RUNNER_TOOL_CALLS_IN_FLIGHT})
 * @param eventsMaxBytes    the event payloads one run may store; past them its {@code LOG} events are dropped and
 *                          its other events refused ({@code TINFIRM_RUNNER_EVENTS_MAX_BYTES})
 * @param requestsPerSecond the {@code run.request} frames one host may send per second, and the
 *                          {@value #BURST_SECONDS} seconds' worth it may send at once; 0 turns the limit off
 *                          ({@code TINFIRM_RUNNER_REQUESTS_PER_SECOND})
 * @param companyRequestsPerSecond the same for all the hosts of a company together
 *                          ({@code TINFIRM_RUNNER_COMPANY_REQUESTS_PER_SECOND})
 * @param eventsPerSecond   the {@code run.event} frames of one run per second, with the same burst
 *                          ({@code TINFIRM_RUNNER_EVENTS_PER_SECOND})
 * @param hostEventsPerSecond the {@code run.event} frames of one host per second, all its runs together
 *                          ({@code TINFIRM_RUNNER_HOST_EVENTS_PER_SECOND})
 * @param companyEventsPerSecond the {@code run.event} frames of all the hosts of a company per second
 *                          ({@code TINFIRM_RUNNER_COMPANY_EVENTS_PER_SECOND})
 */
@Validated
@ConfigurationProperties("tinfirm.runner")
public record RunnerProperties(@NotNull @DefaultValue("PT30S") Duration leaseTtl,
        @NotNull @DefaultValue("PT10S") Duration heartbeatInterval,
        @Positive @DefaultValue("1048576") int maxMessageBytes,
        @NotNull @DefaultValue("PT2M") Duration reconnectGrace,
        @NotNull @DefaultValue("PT10M") Duration lostResumeWindow,
        @Positive @DefaultValue("4") int toolCallsInFlight,
        @NotNull @DefaultValue("64MB") DataSize eventsMaxBytes,
        @Min(0) @DefaultValue("50") int requestsPerSecond,
        @Min(0) @DefaultValue("200") int companyRequestsPerSecond,
        @Min(0) @DefaultValue("20") int eventsPerSecond,
        @Min(0) @DefaultValue("200") int hostEventsPerSecond,
        @Min(0) @DefaultValue("500") int companyEventsPerSecond) {

    /** A rate limit's bucket holds this many seconds' worth of frames: a host may send them at once. */
    public static final int BURST_SECONDS = 10;

    public RunnerProperties {
        if (leaseTtl.toSeconds() < 1) {
            throw new IllegalArgumentException("tinfirm.runner.lease-ttl must be at least 1 second");
        }
        if (reconnectGrace.isNegative() || lostResumeWindow.isNegative()) {
            throw new IllegalArgumentException(
                    "tinfirm.runner.reconnect-grace and tinfirm.runner.lost-resume-window must not be negative");
        }
        if (eventsMaxBytes.toBytes() < DataSize.ofMegabytes(1).toBytes()) {
            throw new IllegalArgumentException("tinfirm.runner.events-max-bytes must be at least 1MB");
        }
    }

    /**
     * How often the frames of a connected host renew the leases of all its running runs: a sixth of the lease, at
     * least 1 s. A host that sends anything at least every five sixths of the lease never loses a run.
     */
    public Duration leaseRenewalInterval() {
        Duration interval = leaseTtl.dividedBy(6);
        return interval.compareTo(Duration.ofSeconds(1)) < 0 ? Duration.ofSeconds(1) : interval;
    }
}
