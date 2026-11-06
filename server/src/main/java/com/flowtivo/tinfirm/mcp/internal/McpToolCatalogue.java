package com.flowtivo.tinfirm.mcp.internal;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import org.jspecify.annotations.Nullable;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;

import com.flowtivo.tinfirm.shared.security.ApiScope;

/**
 * The tools of the MCP endpoint and the scope each needs (ADR-0045), read from the {@code @McpTool} methods of the
 * {@link McpToolSet} beans and their {@link ToolScope}. The HTTP filter ({@link McpRequestFilter}) and the tool
 * invoker look a tool's scope up here. A module adds tools by adding methods (or tool sets) with their
 * {@code @ToolScope}; nothing here changes.
 */
@Component
class McpToolCatalogue {

    private final Map<String, ApiScope> scopes;

    /** From the types of the tool set beans, without creating them (they depend on the invoker, which needs this). */
    @Autowired
    McpToolCatalogue(ListableBeanFactory beans) {
        this(scopes(Arrays.stream(beans.getBeanNamesForType(McpToolSet.class, false, false))
            .<Class<?>>map(name -> unproxied(name, beans.getType(name)))
            .toList()));
    }

    private McpToolCatalogue(Map<String, ApiScope> scopes) {
        this.scopes = scopes;
    }

    /** For tests: the catalogue of the given tool set classes. */
    static McpToolCatalogue of(Collection<Class<?>> toolSetClasses) {
        return new McpToolCatalogue(scopes(toolSetClasses));
    }

    private static Class<?> unproxied(String name, @Nullable Class<?> type) {
        if (type == null) {
            throw new IllegalStateException("The type of the MCP tool set " + name + " is unknown");
        }
        if (ClassUtils.getUserClass(type) != type) {
            throw new IllegalStateException("An MCP tool set must not be proxied: " + name);
        }
        return type;
    }

    private static Map<String, ApiScope> scopes(Collection<Class<?>> toolSetClasses) {
        Map<String, ApiScope> found = new TreeMap<>();
        for (Class<?> type : toolSetClasses) {
            for (Method method : type.getDeclaredMethods()) {
                McpTool tool = method.getAnnotation(McpTool.class);
                if (tool == null) {
                    continue;
                }
                String name = tool.name().isEmpty() ? method.getName() : tool.name();
                ToolScope scope = method.getAnnotation(ToolScope.class);
                if (scope == null) {
                    throw new IllegalStateException("The MCP tool " + name + " (" + type.getSimpleName() + "."
                            + method.getName() + ") has no @ToolScope");
                }
                if (found.put(name, scope.value()) != null) {
                    throw new IllegalStateException("Two MCP tools are named " + name);
                }
            }
        }
        return Collections.unmodifiableMap(found);
    }

    /** The scope the tool needs; empty for a name that is no tool (the server answers that itself). */
    Optional<ApiScope> requiredScope(String tool) {
        return Optional.ofNullable(scopes.get(tool));
    }

    /** Every tool and its scope, by name. */
    Map<String, ApiScope> tools() {
        return scopes;
    }
}
