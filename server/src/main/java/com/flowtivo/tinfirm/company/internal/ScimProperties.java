package com.flowtivo.tinfirm.company.internal;

import jakarta.validation.constraints.Positive;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * The SCIM endpoint's limits (ADR-0087, decision 1), in memory per replica, as the login throttle's.
 *
 * @param requestsPerMinute {@code tinfirm.scim.requests-per-minute}: a company's requests a minute
 * @param refusalsPerMinute {@code tinfirm.scim.refusals-per-minute}: refused tokens a minute from one client address
 */
@Validated
@ConfigurationProperties("tinfirm.scim")
record ScimProperties(@DefaultValue("600") @Positive int requestsPerMinute,
        @DefaultValue("20") @Positive int refusalsPerMinute) {
}
