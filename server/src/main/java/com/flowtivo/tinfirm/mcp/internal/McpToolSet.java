package com.flowtivo.tinfirm.mcp.internal;

/**
 * Marks a bean whose {@code @McpTool} methods are tools of the MCP endpoint. Spring AI's annotation scanner turns the
 * methods into tool specifications; {@link McpToolCatalogue} reads their {@link ToolScope}. A tool set must not be
 * proxied (no {@code @Transactional} on it): the scanner reads the annotations from the bean's own class. The
 * services it calls carry the transactions.
 */
interface McpToolSet {
}
