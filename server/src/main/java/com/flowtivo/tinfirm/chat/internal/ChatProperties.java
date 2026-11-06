package com.flowtivo.tinfirm.chat.internal;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * The chat module's settings (the package 5 contract, §5.2, §5.4, §5.6, open point 11).
 *
 * @param tick                   how often a {@code worker} replica handles the due events ({@code tinfirm.chat.tick})
 * @param silenceAfter           a Slack system answered {@code 503} and silent this long turns {@code ERROR}
 * @param maxBody                the largest inbound request read (the operator's; {@code 413} beyond)
 * @param messagesPerUserMinute  the instance's seed of a company's rate ({@code TINFIRM_CHAT_MESSAGES_PER_USER_MINUTE})
 * @param messagesPerUserDay     ({@code TINFIRM_CHAT_MESSAGES_PER_USER_DAY})
 * @param eventsPerSystemMinute  ({@code TINFIRM_CHAT_EVENTS_PER_SYSTEM_MINUTE})
 * @param linkLifetime           a sign-in link's lifetime (10 minutes)
 * @param agentHold              how long a direct message waits for the agent picker (an hour)
 * @param socketTick             how often a {@code worker} replica looks after its Socket Mode connections
 * @param socketsPerSystem       Socket Mode connections per Slack system in all (Slack allows 10 per app)
 * @param socketLease            how long a replica holds a Socket Mode slot without renewing it
 */
@ConfigurationProperties("tinfirm.chat")
public record ChatProperties(@DefaultValue("PT1S") Duration tick, @DefaultValue("PT30M") Duration silenceAfter,
        @DefaultValue("1MB") DataSize maxBody, @DefaultValue("20") int messagesPerUserMinute,
        @DefaultValue("200") int messagesPerUserDay, @DefaultValue("600") int eventsPerSystemMinute,
        @DefaultValue("PT10M") Duration linkLifetime, @DefaultValue("PT1H") Duration agentHold,
        @DefaultValue("PT5S") Duration socketTick, @DefaultValue("2") int socketsPerSystem,
        @DefaultValue("PT30S") Duration socketLease) {
}
