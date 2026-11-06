package com.flowtivo.tinfirm.mcp.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Min;

/**
 * The MCP endpoint's settings (ADR-0045; the package 6 contract, §5.7).
 *
 * @param rateLimitPerMinute requests one credential may send to the endpoint per minute, as a token bucket whose
 *                           burst is the same number ({@code TINFIRM_MCP_RATE_LIMIT_PER_MINUTE}); 0 turns the limit
 *                           off
 * @param startsPerHour      conversations one member may start through AI clients in an hour, in each company,
 *                           counted from the database so that the limit holds across replicas
 *                           ({@code TINFIRM_MCP_STARTS_PER_HOUR}, open point 14); 0 turns the limit off
 */
@Validated
@ConfigurationProperties("tinfirm.mcp")
record McpProperties(@Min(0) @DefaultValue("120") int rateLimitPerMinute,
        @Min(0) @DefaultValue("30") int startsPerHour) {
}
