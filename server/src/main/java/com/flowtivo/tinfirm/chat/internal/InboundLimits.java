package com.flowtivo.tinfirm.chat.internal;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.tx.SystemTransactions;
import com.flowtivo.tinfirm.system.ChatSystemInfo;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

/**
 * The inbound rates (the package 5 contract, §5.2, C15, open point 11): events a minute per chat system, messages a
 * minute and a day per chat user, counted after the verification (C1) and before an event is stored. The values are
 * the company's ({@code chat_setting}, read again every 30 seconds), seeded from the instance's
 * ({@code TINFIRM_CHAT_*}). In memory per replica, as the login throttle is: behind N replicas a sender gets up to N
 * times the rate. A chat user over their rate is told once a minute.
 * <p>
 * The order keeps one sender from starving the bot (the security review of 2026-10-27, L7): a message counts against
 * its sender first, and only one within that rate counts against the system; a guest's or another organisation's
 * event ({@link #outsider}) counts in a small bucket of its own, a tenth of the system's rate, never in the system's.
 */
@Component
class InboundLimits {

    static final Duration RATES_KEPT = Duration.ofSeconds(30);

    private record Window(long start, int count) {
    }

    private record KeptRates(ChatStore.Rates rates, Instant at) {
    }

    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    private final Map<UUID, KeptRates> rates = new ConcurrentHashMap<>();

    private final Map<String, Instant> told = new ConcurrentHashMap<>();

    /** Whether a chat user is a guest or of another organisation, as the worker's check stored it (chat_user). */
    private final Cache<String, Boolean> outsiders = Caffeine.newBuilder()
        .maximumSize(100_000)
        .expireAfterWrite(Duration.ofMinutes(10))
        .build();

    private final ChatStore store;

    private final ChatProperties properties;

    private final SystemTransactions transactions;

    private final TimeSource time;

    InboundLimits(ChatStore store, ChatProperties properties, SystemTransactions transactions, TimeSource time) {
        this.store = store;
        this.properties = properties;
        this.transactions = transactions;
        this.time = time;
    }

    /** The company's rates: its own, else the instance's. */
    ChatStore.Rates rates(UUID companyId) {
        KeptRates kept = rates.get(companyId);
        Instant now = time.now();
        if (kept != null && kept.at().plus(RATES_KEPT).isAfter(now)) {
            return kept.rates();
        }
        ChatStore.Rates current = transactions.inCompany(companyId, () -> store.rates(companyId)
            .orElse(defaults()));
        rates.put(companyId, new KeptRates(current, now));
        return current;
    }

    ChatStore.Rates defaults() {
        return new ChatStore.Rates(properties.messagesPerUserMinute(), properties.messagesPerUserDay(),
                properties.eventsPerSystemMinute(), 0);
    }

    /** An admin changed the company's rates on this replica: read them again at once. */
    void forget(UUID companyId) {
        rates.remove(companyId);
    }

    /** One event more of the system this minute: false over the company's rate. */
    boolean system(UUID companyId, UUID systemId) {
        return count("s:" + systemId, Duration.ofMinutes(1), rates(companyId).eventsPerSystemMinute());
    }

    /**
     * One event more of the system's guests and other organisations' users this minute: false over a tenth of the
     * system's rate (at least one).
     */
    boolean outsiders(UUID companyId, UUID systemId) {
        return count("o:" + systemId, Duration.ofMinutes(1), outsiderRate(rates(companyId)));
    }

    static int outsiderRate(ChatStore.Rates rates) {
        return Math.max(1, rates.eventsPerSystemMinute() / 10);
    }

    /**
     * Whether the sender is a guest or of another organisation: on Slack, a {@code user_team} that is not the
     * system's workspace (a shared channel); else what the worker's check of the chat user stored ({@code GUEST},
     * {@code EXTERNAL}), read at most every 10 minutes per user. A sender not checked yet counts as a member until the
     * worker has checked them.
     */
    boolean outsider(ChatSystemInfo system, String sender, @Nullable Object userTeam) {
        String workspace = system.workspace();
        if (system.slack() && userTeam instanceof String team && !team.isBlank() && workspace != null
                && !team.equalsIgnoreCase(workspace)) {
            return true;
        }
        return outsiders.get(system.id() + ":" + sender, key -> transactions.inCompany(system.companyId(),
                () -> store.user(system.id(), sender)
                    .map(user -> "GUEST".equals(user.audience()) || "EXTERNAL".equals(user.audience()))
                    .orElse(false)));
    }

    /** One message more of the chat user, this minute and this day: false over either rate. */
    boolean user(UUID companyId, UUID systemId, String user) {
        ChatStore.Rates current = rates(companyId);
        boolean minute = count("m:" + systemId + ":" + user, Duration.ofMinutes(1), current.messagesPerUserMinute());
        boolean day = count("d:" + systemId + ":" + user, Duration.ofDays(1), current.messagesPerUserDay());
        return minute && day;
    }

    /** Whether the chat user over their rate is told now: once a minute. */
    boolean tell(UUID systemId, String user) {
        Instant now = time.now();
        String key = systemId + ":" + user;
        Instant last = told.get(key);
        if (last != null && last.plus(Duration.ofMinutes(1)).isAfter(now)) {
            return false;
        }
        told.put(key, now);
        return true;
    }

    private boolean count(String key, Duration window, int limit) {
        long millis = time.now().toEpochMilli();
        long start = millis - millis % window.toMillis();
        Window counted = windows.compute(key, (k, current) -> current == null || current.start() != start
                ? new Window(start, 1) : new Window(start, current.count() + 1));
        if (windows.size() > 100_000) {
            windows.entrySet().removeIf(entry -> entry.getValue().start() < start - Duration.ofDays(1).toMillis());
        }
        return counted.count() <= limit;
    }
}
