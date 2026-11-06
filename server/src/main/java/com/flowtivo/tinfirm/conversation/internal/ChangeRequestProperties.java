package com.flowtivo.tinfirm.conversation.internal;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotNull;

/**
 * The watch of the change requests the server opened (ADR-0022; the package 3 contract, §5.4).
 *
 * @param stateInterval the state of a watched change request (not MERGED) is read again this long after the last read
 *                      ({@code TINFIRM_CHANGE_REQUEST_STATE_INTERVAL}); at least 30 seconds
 * @param stateTick     how often a worker replica looks for due states; read by the schedule only
 */
@Validated
@ConfigurationProperties("tinfirm.conversation.change-requests")
record ChangeRequestProperties(@NotNull @DefaultValue("PT5M") Duration stateInterval,
        @NotNull @DefaultValue("PT15S") Duration stateTick) {

    ChangeRequestProperties {
        if (stateInterval.toSeconds() < 30) {
            throw new IllegalArgumentException(
                    "tinfirm.conversation.change-requests.state-interval must be at least 30 seconds");
        }
    }
}
