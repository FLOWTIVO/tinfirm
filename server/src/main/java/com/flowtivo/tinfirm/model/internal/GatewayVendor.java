package com.flowtivo.tinfirm.model.internal;

/**
 * The product a company's model gateway is (ADR-0094): which {@link GatewayAdapter} speaks to it. {@code LITELLM} for
 * every bundled gateway and every company's own that Tinfirm manages; {@code GENERIC} for a gateway whose keys the
 * company provides and whose product Tinfirm does not know.
 */
enum GatewayVendor {

    LITELLM, GENERIC
}
