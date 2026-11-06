package com.flowtivo.tinfirm.mcp.internal;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.flowtivo.tinfirm.shared.security.ApiScope;

/**
 * The scope a tool needs (ADR-0045): {@code tinfirm:read} for a read, {@code tinfirm:write} for a write,
 * {@code tinfirm:decide} for {@code decide}. Every {@code @McpTool} method of a {@link McpToolSet} carries it; the
 * {@link McpToolCatalogue} refuses to start without it, so a new tool cannot be added without naming its scope.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@interface ToolScope {

    ApiScope value();
}
