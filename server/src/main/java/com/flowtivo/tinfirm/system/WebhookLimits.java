package com.flowtivo.tinfirm.system;

import java.util.UUID;

/**
 * The company's webhook deliveries a minute per system (the package 5 contract, §5.12, open point 11): a default and a
 * maximum that admins change with the company's settings, seeded from the instance's configuration. Implemented by the
 * {@code trigger} module, which keeps the company's settings; looked up when used. In the caller's transaction and
 * tenant context.
 */
public interface WebhookLimits {

    /** A webhook system's deliveries a minute when it names none. */
    int defaultPerMinute(UUID companyId);

    /** The most a webhook system may name ({@code 400 WEBHOOK_LIMIT_TOO_HIGH} above it). */
    int maxPerMinute(UUID companyId);
}
