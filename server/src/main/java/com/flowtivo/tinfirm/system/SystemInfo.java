package com.flowtivo.tinfirm.system;

import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * A system as other modules see it (the package 1 contract, §3.3).
 *
 * @param baseUrl          the API root; null for {@code GIT} and {@code WEBHOOK}
 * @param ownerPrincipalId the member's principal of a personal MCP server; null for a company system
 * @param authHeader       an MCP server's header for a stored token (null: {@code Authorization: Bearer})
 * @param oauthApp         whether the system has an app, so accounts on it may sign in with OAuth
 * @param paused           whether an admin paused it (the package 6 contract, §5.4): its accounts are used for
 *                         nothing, its tools are not listed, and a turn needing its repositories waits
 * @param accountsRequired whether a conversation needs an account on it (ADR-0071): false only for an MCP server its
 *                         admin set so, which runs call without a credential and nobody connects an account to
 */
public record SystemInfo(UUID id, UUID companyId, String name, String handle, SystemKind kind,
        @Nullable String baseUrl, @Nullable UUID ownerPrincipalId, @Nullable String authHeader, boolean oauthApp,
        boolean paused, boolean accountsRequired) {

    /** A system that needs accounts. */
    public SystemInfo(UUID id, UUID companyId, String name, String handle, SystemKind kind, @Nullable String baseUrl,
            @Nullable UUID ownerPrincipalId, @Nullable String authHeader, boolean oauthApp, boolean paused) {
        this(id, companyId, name, handle, kind, baseUrl, ownerPrincipalId, authHeader, oauthApp, paused, true);
    }

    /** A system that is not paused and needs accounts. */
    public SystemInfo(UUID id, UUID companyId, String name, String handle, SystemKind kind, @Nullable String baseUrl,
            @Nullable UUID ownerPrincipalId, @Nullable String authHeader, boolean oauthApp) {
        this(id, companyId, name, handle, kind, baseUrl, ownerPrincipalId, authHeader, oauthApp, false, true);
    }

    /** A personal MCP server (I6). */
    public boolean personal() {
        return ownerPrincipalId != null;
    }

    public boolean ownedBy(UUID principalId) {
        return principalId.equals(ownerPrincipalId);
    }
}
