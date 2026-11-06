package com.flowtivo.tinfirm.identity.internal;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import com.flowtivo.tinfirm.shared.security.ApiScope;

/** The OAuth scopes of Tinfirm's authorization server (ADR-0045): {@code tinfirm:read}, {@code :write}, {@code :decide}. */
final class OAuthScopes {

    /** The three, lowest first. */
    static final List<String> ALL = Arrays.stream(ApiScope.values()).map(ApiScope::oauthScope).toList();

    /** What the 401 of the MCP endpoint and its resource metadata advertise: reading only. */
    static final String ADVERTISED = ApiScope.READ.oauthScope();

    private OAuthScopes() {
    }

    /** The scope of a known name, e.g. {@code tinfirm:write}. */
    static Optional<ApiScope> of(String name) {
        return Arrays.stream(ApiScope.values()).filter(scope -> scope.oauthScope().equals(name)).findFirst();
    }

    /** The highest of the {@code tinfirm:*} scopes among the names; unknown names are ignored. */
    static Optional<ApiScope> highest(Collection<String> names) {
        return names.stream().map(OAuthScopes::of).flatMap(Optional::stream).max(Enum::compareTo);
    }

    /** The known scopes among the names, lowest first. */
    static List<String> known(Collection<String> names) {
        Set<String> requested = Set.copyOf(names);
        return ALL.stream().filter(requested::contains).toList();
    }

    /** A one-line explanation of a scope for the consent page and "Connected apps". */
    static String explanation(ApiScope scope) {
        return switch (scope) {
            case READ -> "Read your conversations, your decisions and your inbox, and the agents you may use.";
            case WRITE -> "Everything Read allows, and start your conversations, send messages in them, choose "
                    + "accounts, cancel, retry and end. Never decide.";
            case DECIDE -> "Everything Read and Write allow, and answer questions, approve or deny your own "
                    + "requests, as you.";
        };
    }

    static String title(ApiScope scope) {
        return switch (scope) {
            case READ -> "Read";
            case WRITE -> "Write";
            case DECIDE -> "Decide";
        };
    }

    static String joined(Collection<String> scopes) {
        return known(scopes).stream().collect(Collectors.joining(" "));
    }
}
