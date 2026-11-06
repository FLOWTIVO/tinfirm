package com.flowtivo.tinfirm.system;

/**
 * What a system is (the package 1 contract, §3.3); what it offers follows from it. Never changes for a system.
 * {@code SLACK} and {@code TEAMS} are registered from package 5 (its contract, §5.1), each with its bot (I9), and
 * {@code WEBHOOK} from package 5b (§5.12); {@code BUZZ} is parked ({@link #registrable}).
 */
public enum SystemKind {
    JIRA_DC, JIRA_CLOUD, GITHUB, GITLAB, GIT, MCP, SLACK, TEAMS, BUZZ, WEBHOOK;

    /**
     * Whether systems of this kind are registered: the trackers, the code hosts and MCP servers (package 1), Slack,
     * Teams and the generic webhook (package 5). Buzz is parked.
     */
    public boolean registrable() {
        return switch (this) {
            case JIRA_DC, JIRA_CLOUD, GITHUB, GITLAB, GIT, MCP, SLACK, TEAMS, WEBHOOK -> true;
            case BUZZ -> false;
        };
    }

    /** Whether it has a base URL: every kind but {@code GIT} (repositories by URL) and {@code WEBHOOK} (Tinfirm is it). */
    public boolean hasBaseUrl() {
        return this != GIT && this != WEBHOOK;
    }

    /** Whether it holds git repositories (contract §3.4). */
    public boolean hasRepositories() {
        return this == GITHUB || this == GITLAB || this == GIT;
    }

    /**
     * Whether its tools are Tinfirm's fixed catalogue ({@link FixedTools}): the trackers', and Slack's {@code history}
     * (the package 5 contract, open point 13). Teams and the webhook have none.
     */
    public boolean hasFixedTools() {
        return this == JIRA_DC || this == JIRA_CLOUD || this == GITHUB || this == GITLAB || this == SLACK;
    }

    /** Whether it is a tracker or a code host whose fixed tools work on issues: every fixed catalogue but Slack's. */
    public boolean tracker() {
        return this == JIRA_DC || this == JIRA_CLOUD || this == GITHUB || this == GITLAB;
    }

    /**
     * Whether an agent may call tools on it, and so send it what it passes as their arguments: every kind with tools,
     * fixed ({@link #hasFixedTools}: the trackers, the code hosts but {@code GIT}, Slack's {@code history}) or the
     * server's own ({@link #hasCatalogue}: MCP). What a conversation read elsewhere may reach such a system, as the
     * arguments of a READ tool too (the security review of 2026-10-27, D3, an accepted risk with a cap of
     * {@link SystemTools#MAX_ARGUMENT_BYTES} per call); its page says so. {@code GIT}, {@code TEAMS} and
     * {@code WEBHOOK} have no tool.
     */
    public boolean receivesData() {
        return hasFixedTools() || hasCatalogue();
    }

    /** Whether its tools are the server's own catalogue, read and confirmed (contract §3.5). */
    public boolean hasCatalogue() {
        return this == MCP;
    }

    /**
     * Whether accounts on it may sign in with OAuth through the system's app: on Slack the app's user scopes, for
     * {@code history}'s accounts (the package 5 contract, open point 13).
     */
    public boolean oauth() {
        return this == JIRA_DC || this == JIRA_CLOUD || this == GITHUB || this == GITLAB || this == MCP
                || this == SLACK;
    }

    /**
     * Whether its credentials may be an OAuth client's own ({@code client_credentials}, {@link ClientCredentials}):
     * Jira Cloud, for an Atlassian service account's OAuth 2.0 credential.
     */
    public boolean clientCredentials() {
        return this == JIRA_CLOUD;
    }

    /** A chat system, which may have a bot (I9). */
    public boolean chat() {
        return this == SLACK || this == TEAMS || this == BUZZ;
    }

    /** The kind as people name it, for the words of a problem or a refusal: never the enum's name. */
    public String label() {
        return switch (this) {
            case JIRA_DC -> "Jira Data Center";
            case JIRA_CLOUD -> "Jira Cloud";
            case GITHUB -> "GitHub";
            case GITLAB -> "GitLab";
            case GIT -> "Git";
            case MCP -> "MCP server";
            case SLACK -> "Slack";
            case TEAMS -> "Microsoft Teams";
            case BUZZ -> "Buzz";
            case WEBHOOK -> "Webhook";
        };
    }
}
