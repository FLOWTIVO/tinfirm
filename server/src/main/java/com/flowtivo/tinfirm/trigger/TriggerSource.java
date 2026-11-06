package com.flowtivo.tinfirm.trigger;

/**
 * Where a trigger's firings come from (the package 4 contract, §3.1, open point 5): a company tracker system
 * ({@code JIRA_DC}, {@code JIRA_CLOUD}, {@code GITHUB}, {@code GITLAB}), polled with the source account; or a schedule,
 * without a system. Package 5 adds a chat system's channels ({@code CHANNEL}: mentions of its bot) and a webhook's
 * deliveries ({@code WEBHOOK}), neither with a row on its source.
 */
public enum TriggerSource {
    TRACKER, SCHEDULE, CHANNEL, WEBHOOK
}
