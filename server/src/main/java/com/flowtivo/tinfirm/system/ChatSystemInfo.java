package com.flowtivo.tinfirm.system;

import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * A chat system as the {@code chat} module sees it (the package 5 contract, §3.1, §3.2): the system, its kind's
 * settings, its bot's account, and which of its own secrets are kept (never their values).
 *
 * @param transport       Slack's {@code EVENTS_API} or {@code SOCKET_MODE}; null on Teams
 * @param teamId          Slack's workspace, fixed once the bot named it; null until then and on Teams
 * @param tenantId        Teams' tenant; null on Slack
 * @param appId           Teams' Microsoft App ID (the Azure Bot); null on Slack
 * @param botUserId       Slack's bot user, kept while the system has a bot; null otherwise and on Teams
 * @param botAccountId    the bot's account, a service identity's on this system (I9); null without a bot
 * @param signingSecretId Slack's signing secret (Events API); null when none is kept
 * @param appTokenId      Slack's app-level token (Socket Mode); null when none is kept
 * @param status          {@code NEW}, {@code OK} or {@code ERROR}
 */
public record ChatSystemInfo(SystemInfo system, @Nullable String transport, @Nullable String teamId,
        @Nullable String teamName, @Nullable String tenantId, @Nullable String appId, @Nullable String botUserId,
        @Nullable UUID botAccountId, @Nullable UUID signingSecretId, @Nullable UUID appTokenId, String status) {

    public static final String EVENTS_API = "EVENTS_API";

    public static final String SOCKET_MODE = "SOCKET_MODE";

    public UUID id() {
        return system.id();
    }

    public UUID companyId() {
        return system.companyId();
    }

    public SystemKind kind() {
        return system.kind();
    }

    public String name() {
        return system.name();
    }

    public boolean slack() {
        return kind() == SystemKind.SLACK;
    }

    public boolean teams() {
        return kind() == SystemKind.TEAMS;
    }

    public boolean socketMode() {
        return slack() && SOCKET_MODE.equals(transport);
    }

    public boolean hasBot() {
        return botAccountId != null;
    }

    /** The workspace or tenant the platform's events must name (C2): Slack's {@code team_id}, Teams' tenant. */
    public @Nullable String workspace() {
        return slack() ? teamId : tenantId;
    }
}
