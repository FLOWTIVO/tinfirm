package com.flowtivo.tinfirm.trigger.internal;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * The trigger module's settings ({@code tinfirm.trigger.*}): the instance's firing limits, which seed a company's until
 * its admins set their own (the package 4 contract, §11, open point 15; {@code TINFIRM_TRIGGER_FIRINGS_PER_HOUR_DEFAULT},
 * {@code TINFIRM_TRIGGER_FIRINGS_PER_HOUR_MAX}).
 *
 * @param firingsPerHourDefault a new trigger's {@code max_firings_per_hour}
 * @param firingsPerHourMax     the most a trigger may set
 */
@Validated
@ConfigurationProperties("tinfirm.trigger")
record TriggerProperties(@Min(1) @Max(10_000) @DefaultValue("30") int firingsPerHourDefault,
        @Min(1) @Max(10_000) @DefaultValue("300") int firingsPerHourMax) {

    TriggerProperties {
        if (firingsPerHourDefault > firingsPerHourMax) {
            throw new IllegalArgumentException("tinfirm.trigger.firings-per-hour-default must be at most "
                    + "tinfirm.trigger.firings-per-hour-max");
        }
    }
}
