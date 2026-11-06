package com.flowtivo.tinfirm.governance.internal;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotNull;

/**
 * The spend sync's tuning (the package 6 contract, §5.1).
 *
 * @param syncInterval how often a {@code worker} replica settles due runs ({@code PT1M})
 * @param source       {@code LITELLM}: LiteLLM's spend logs, else the relay's count after {@code relayAfter};
 *                     {@code RELAY}: the relay's count at once, should LiteLLM not keep the tags (open point 1)
 * @param settleDelay  how long after a run's end its logs are first read, since LiteLLM writes them in batches
 * @param relayAfter   how long after a run's end its logs are waited for before the relay's count settles it
 */
@Validated
@ConfigurationProperties("tinfirm.spend")
record SpendProperties(@NotNull @DefaultValue("PT1M") Duration syncInterval,
        @NotNull @DefaultValue("LITELLM") Source source, @NotNull @DefaultValue("PT2M") Duration settleDelay,
        @NotNull @DefaultValue("PT1H") Duration relayAfter) {

    enum Source {
        LITELLM, RELAY
    }
}
