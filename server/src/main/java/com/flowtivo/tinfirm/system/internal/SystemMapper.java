package com.flowtivo.tinfirm.system.internal;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.flowtivo.tinfirm.shared.api.model.ChatSystemDetails;
import com.flowtivo.tinfirm.shared.api.model.ExternalSystem;
import com.flowtivo.tinfirm.shared.api.model.SystemApp;
import com.flowtivo.tinfirm.shared.api.model.SystemRepository;
import com.flowtivo.tinfirm.shared.api.model.SystemTool;
import com.flowtivo.tinfirm.shared.mapping.ApiMapperConfig;
import com.flowtivo.tinfirm.system.RepositoryInfo;
import com.flowtivo.tinfirm.system.SystemKind;
import com.flowtivo.tinfirm.system.ToolEffect;

/** The DTOs of the Systems API (the package 1 contract, §6). */
@Mapper(config = ApiMapperConfig.class)
interface SystemMapper {

    /**
     * A system as the caller sees it.
     *
     * @param personal the caller's own personal server (nobody else sees one)
     * @param app              the app, for whoever manages the system; null otherwise, and without one
     * @param oauthCallbackUrl the redirect URI to register for its app, for whoever manages a system of a kind that
     *                         signs in with OAuth, with an app or without; null otherwise
     * @param chat             a chat system's settings and bot (package 5); null for another kind
     * @param accountsRequired false only for an MCP server that needs no account (ADR-0071)
     * @param receivesData     whether an agent may call tools on it, so that it receives what they pass
     *                         ({@link SystemKind#receivesData}, the security review of 2026-10-27, D3)
     * @param internalHost     whether its base URL's host is one of the operator's internal hosts (ADR-0066); never
     *                         for a personal server
     */
    record SystemDetails(UUID id, String name, String handle, SystemKind kind, @Nullable String baseUrl,
            boolean personal, String status, @Nullable String lastError, boolean paused, boolean oauthAvailable,
            @Nullable AppDetails app, @Nullable String oauthCallbackUrl, @Nullable String authHeader,
            @Nullable ChatSystemDetails chat, Instant createdAt, Instant updatedAt, boolean accountsRequired,
            boolean receivesData, boolean internalHost) {
    }

    record AppDetails(String clientId, boolean clientSecretSet, @Nullable String scopes, String callbackUrl,
            @Nullable String authorizationEndpoint) {
    }

    /** A tool as the Systems API shows it. */
    record ToolDetails(String name, @Nullable String title, @Nullable String description,
            Map<String, Object> inputSchema, ToolEffect effect, @Nullable ToolEffect suggestedEffect,
            @Nullable Boolean hintReadOnly, @Nullable Boolean hintDestructive, boolean enabled, boolean fixed) {
    }

    /**
     * A webhook's details are set by the caller after the mapping, with its secret only on its creation; a tracker's
     * poll interval and the last test too.
     */
    @Mapping(target = "webhook", ignore = true)
    @Mapping(target = "pollIntervalSeconds", ignore = true)
    @Mapping(target = "lastTest", ignore = true)
    ExternalSystem toDto(SystemDetails system);

    SystemApp toDto(AppDetails app);

    SystemTool toDto(ToolDetails tool);

    SystemRepository toDto(RepositoryInfo repository);
}
