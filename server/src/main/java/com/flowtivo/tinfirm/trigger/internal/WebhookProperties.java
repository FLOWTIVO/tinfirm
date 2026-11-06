package com.flowtivo.tinfirm.trigger.internal;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * The webhook's settings ({@code tinfirm.webhook.*}): the instance's deliveries a minute per webhook system, which seed
 * a company's until its admins set their own (the package 5 contract, §5.12, open point 11;
 * {@code TINFIRM_WEBHOOK_DELIVERIES_PER_MINUTE_DEFAULT}, {@code TINFIRM_WEBHOOK_DELIVERIES_PER_MINUTE_MAX}).
 *
 * @param deliveriesPerMinuteDefault a webhook system's deliveries a minute when it names none
 * @param deliveriesPerMinuteMax     the most a webhook system may name
 */
@Validated
@ConfigurationProperties("tinfirm.webhook")
record WebhookProperties(@Min(1) @Max(6_000) @DefaultValue("60") int deliveriesPerMinuteDefault,
        @Min(1) @Max(6_000) @DefaultValue("600") int deliveriesPerMinuteMax) {

    WebhookProperties {
        if (deliveriesPerMinuteDefault > deliveriesPerMinuteMax) {
            throw new IllegalArgumentException("tinfirm.webhook.deliveries-per-minute-default must be at most "
                    + "tinfirm.webhook.deliveries-per-minute-max");
        }
    }
}
