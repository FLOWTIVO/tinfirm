package com.flowtivo.tinfirm.chat.internal;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.shared.config.TinfirmProperties;
import com.flowtivo.tinfirm.shared.id.Ids;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.tx.SystemTransactions;
import com.flowtivo.tinfirm.system.ChatSystemInfo;
import com.flowtivo.tinfirm.system.ChatSystems;

/**
 * A Slack event subscription that Slack disabled (the package 5 contract, §5.2): when the database fails,
 * the Events API is answered {@code 503}; Slack retries such deliveries, and when they keep failing it disables the
 * app's event subscriptions and sends nothing more, and nothing tells Tinfirm. So each replica remembers the systems
 * it answered {@code 503}; such a system that stored no event for {@code tinfirm.chat.silence-after} since then turns
 * {@code ERROR}, with an item in the admins' inbox naming the re-enable step. Its next stored event clears both (the
 * watch of {@code worker} replicas). Socket Mode has no such failure.
 */
@Component
class ChatSilence {

    private static final Logger log = LoggerFactory.getLogger(ChatSilence.class);

    static final String KIND = "SUBSCRIPTION_SILENT";

    static final String ERROR = "Slack may have disabled this app's event subscriptions after deliveries failed: "
            + "re-enable them on the app's Event Subscriptions page";

    private record Failing(UUID companyId, Instant since) {
    }

    private final Map<UUID, Failing> failing = new ConcurrentHashMap<>();

    private final ChatStore store;

    private final ChatSystems chatSystems;

    private final ChatProperties properties;

    private final SystemTransactions transactions;

    private final TimeSource time;

    private final boolean worker;

    ChatSilence(ChatStore store, ChatSystems chatSystems, ChatProperties properties,
            SystemTransactions transactions, TimeSource time, TinfirmProperties tinfirm) {
        this.store = store;
        this.chatSystems = chatSystems;
        this.properties = properties;
        this.transactions = transactions;
        this.time = time;
        this.worker = tinfirm.hasRole(TinfirmProperties.WORKER_ROLE);
    }

    /** This replica answered the system's delivery {@code 503}. */
    void failed(UUID companyId, UUID systemId) {
        failing.putIfAbsent(systemId, new Failing(companyId, time.now()));
    }

    @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT1M")
    void tick() {
        watch();
        if (worker) {
            clear();
        }
    }

    /** The systems this replica answered {@code 503} and that stayed silent since turn {@code ERROR}. */
    void watch() {
        Instant now = time.now();
        for (Map.Entry<UUID, Failing> entry : failing.entrySet()) {
            UUID systemId = entry.getKey();
            Failing since = entry.getValue();
            boolean heard = transactions.inCompany(since.companyId(), () -> store.receivedSince(systemId,
                    since.since()));
            if (heard) {
                failing.remove(systemId, since);
                continue;
            }
            if (since.since().plus(properties.silenceAfter()).isAfter(now)) {
                continue;
            }
            transactions.runInCompany(since.companyId(), () -> chatSystems.find(since.companyId(), systemId)
                .filter(system -> !system.socketMode())
                .ifPresent(system -> {
                    chatSystems.status(since.companyId(), systemId, "ERROR", ERROR);
                    store.notice(Ids.newId(), since.companyId(), systemId, null, KIND, Map.of("since",
                            since.since().toString()), now, null);
                }));
            log.warn("The Slack system {} answered 503 and stayed silent: it may have been disabled", systemId);
            failing.remove(systemId, since);
        }
    }

    /** A silent system that stored an event since its item was raised: the item goes, the system is {@code OK}. */
    void clear() {
        for (ChatStore.SystemRef ref : transactions.unscoped(store::systemsWithBot)) {
            if (!"SLACK".equals(ref.kind())) {
                continue;
            }
            transactions.runInCompany(ref.companyId(), () -> {
                for (ChatStore.Notice notice : store.notices(ref.companyId(), null, true, time.now())) {
                    if (KIND.equals(notice.kind()) && notice.systemId().equals(ref.systemId())
                            && store.receivedSince(ref.systemId(), notice.createdAt())) {
                        store.clearNotices(ref.systemId(), KIND);
                        ChatSystemInfo system = chatSystems.find(ref.companyId(), ref.systemId()).orElse(null);
                        if (system != null && "ERROR".equals(system.status())) {
                            chatSystems.status(ref.companyId(), ref.systemId(), "OK", null);
                        }
                    }
                }
            });
        }
    }
}
