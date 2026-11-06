package com.flowtivo.tinfirm.chat.internal;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.audit.AuditAction;
import com.flowtivo.tinfirm.audit.AuditLog;
import com.flowtivo.tinfirm.audit.AuditRecord;
import com.flowtivo.tinfirm.identity.CompanyNames;
import com.flowtivo.tinfirm.shared.config.TinfirmProperties;
import com.flowtivo.tinfirm.shared.id.Ids;
import com.flowtivo.tinfirm.shared.security.Actor;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.tx.SystemTransactions;
import com.flowtivo.tinfirm.system.ChatSystemInfo;
import com.flowtivo.tinfirm.system.ChatSystems;

/**
 * What arrives from the chats, acted on once and in order (the package 5 contract, §3.7, §5.2, C1 to C3, C6, C14,
 * C15).
 * <ul>
 * <li>{@link #store}: in the request, after the verification and the limits, one short transaction inserts the event
 * ({@code ON CONFLICT (system_id, event_id) DO NOTHING}), and the endpoint answers at once; nothing else runs
 * there.</li>
 * <li>{@link #tick}, on {@code worker} replicas every {@code tinfirm.chat.tick}: {@code chat_event_due} gives each
 * thread's oldest {@code RECEIVED} event, claimed {@code FOR UPDATE SKIP LOCKED}, so one replica handles a thread at a
 * time and in the platform's order. Platform calls run outside any transaction; each handler does its conversation's
 * work in one. A failure is tried again after 1, 2, 4 and 8 seconds, then {@code FAILED}, and the sender is told.</li>
 * <li>Before a handler, every message and press is checked: the system is not paused (else dropped unanswered,
 * {@code SYSTEM_PAUSED}: answering needs the system, the package 6 contract §5.4, G5); the bot is usable (else dropped
 * unanswered, {@code NO_BOT}, C14); the sender is a member of the workspace ({@code ChatAudience}, C6), else refused,
 * told once an hour and audited {@code chat.refused}; a sender linked to a suspended member is refused in the same way
 * ({@code SUSPENDED}, G6), never offered a sign-in link; a sender over the company's rate is told once a minute
 * (C15).</li>
 * <li>Held events (for a sign-in link, for the agent picker) are dropped when their hold runs out, and their sender
 * told; every event goes a day after it arrived.</li>
 * </ul>
 */
@Component
class ChatInbox {

    private static final Logger log = LoggerFactory.getLogger(ChatInbox.class);

    static final int MAX_ATTEMPTS = 5;

    static final Duration CLAIM = Duration.ofMinutes(2);

    static final Duration REFUSAL_TOLD = Duration.ofHours(1);

    /** What a handler did. */
    record Outcome(@Nullable String status, @Nullable String reason) {

        /** The handler recorded the event itself, in its own transaction (handled, held). */
        static final Outcome RECORDED = new Outcome(null, null);

        static Outcome handled() {
            return new Outcome("HANDLED", null);
        }

        static Outcome ignored(String reason) {
            return new Outcome("IGNORED", reason);
        }

        static Outcome refused(String reason) {
            return new Outcome("REFUSED", reason);
        }
    }

    /** A handler of one kind of event, outside any transaction. */
    interface Handler {

        Outcome handle(ChatSystemInfo system, ChatStore.EventRow event, ChatAudience.Who who);
    }

    private final ChatStore store;

    private final ChatSystems chatSystems;

    private final ChatBot bot;

    private final ChatAudience audience;

    private final ChatPoster poster;

    private final ChatLinks links;

    private final CompanyNames companies;

    private final SystemTransactions transactions;

    private final AuditLog auditLog;

    private final TimeSource time;

    private final boolean worker;

    private final Map<String, Handler> handlers = new ConcurrentHashMap<>();

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    /** The threads being handled on this replica: a thread's next event waits for its previous one. */
    private final Set<UUID> running = ConcurrentHashMap.newKeySet();

    private final Semaphore slots = new Semaphore(16);

    ChatInbox(ChatStore store, ChatSystems chatSystems, ChatBot bot, ChatAudience audience, ChatPoster poster,
            ChatLinks links, CompanyNames companies, SystemTransactions transactions, AuditLog auditLog,
            TimeSource time, TinfirmProperties tinfirm) {
        this.store = store;
        this.chatSystems = chatSystems;
        this.bot = bot;
        this.audience = audience;
        this.poster = poster;
        this.links = links;
        this.companies = companies;
        this.transactions = transactions;
        this.auditLog = auditLog;
        this.time = time;
        this.worker = tinfirm.hasRole(TinfirmProperties.WORKER_ROLE);
    }

    /** The handlers register themselves by kind ({@code MESSAGE}, {@code ACTION}, {@code MENTION}, {@code LIFECYCLE}). */
    void register(String kind, Handler handler) {
        handlers.put(kind, handler);
    }

    // ---- storing (in the request) ----------------------------------------------------------------------------------

    enum Stored {
        STORED, DUPLICATE, FAILED
    }

    /** Stores a verified event once (C3), in one short transaction; {@code FAILED} when the database failed. */
    Stored store(ChatStore.SystemRef system, Inbound inbound) {
        try {
            boolean inserted = transactions.inCompany(system.companyId(), () -> store.insert(new ChatStore.NewEvent(
                    Ids.newId(), system.companyId(), system.systemId(), inbound.eventId(), inbound.kind(),
                    inbound.sender(), inbound.threadKey(), inbound.platformTs(), inbound.retryNum(),
                    inbound.payload(), inbound.status(), inbound.reason(), time.now())));
            return inserted ? Stored.STORED : Stored.DUPLICATE;
        }
        catch (DataAccessException ex) {
            log.warn("An event of the chat system {} was not stored ({})", system.systemId(),
                    ex.getClass().getSimpleName());
            return Stored.FAILED;
        }
    }

    // ---- handling (worker) -----------------------------------------------------------------------------------------

    @Scheduled(fixedDelayString = "${tinfirm.chat.tick:PT1S}", initialDelayString = "${tinfirm.chat.tick:PT1S}")
    void tick() {
        if (worker) {
            handleDue();
            expire();
        }
    }

    /** One pass: every due event, each thread on a virtual thread of its own. */
    void handleDue() {
        Instant now = time.now();
        List<UUID[]> due = transactions.unscoped(() -> store.due(now, 50));
        for (UUID[] candidate : due) {
            UUID eventId = candidate[0];
            UUID companyId = candidate[1];
            if (!running.add(eventId)) {
                continue;
            }
            if (!slots.tryAcquire()) {
                running.remove(eventId);
                break;
            }
            executor.submit(() -> {
                try {
                    handle(companyId, eventId);
                }
                catch (RuntimeException ex) {
                    log.warn("A chat event was not handled ({})", ex.getClass().getSimpleName(), ex);
                }
                finally {
                    running.remove(eventId);
                    slots.release();
                }
            });
        }
    }

    /** Claims one event and hands it to its handler. */
    void handle(UUID companyId, UUID eventId) {
        Instant now = time.now();
        Claimed claimed = transactions.inCompany(companyId, () -> store.claim(eventId, now, now.plus(CLAIM))
            .map(event -> new Claimed(event, chatSystems.find(companyId, event.systemId()).orElse(null)))
            .orElse(null));
        if (claimed == null) {
            return;
        }
        ChatStore.EventRow event = claimed.event();
        ChatSystemInfo system = claimed.system();
        if (system == null) {
            transactions.runInCompany(companyId, () -> store.delete(eventId));
            return;
        }
        Outcome outcome;
        try {
            outcome = dispatch(system, event);
        }
        catch (RuntimeException ex) {
            log.warn("The chat event {} failed (attempt {}): {}", eventId, event.attempts(),
                    ex.getClass().getSimpleName(), ex);
            if (event.attempts() >= MAX_ATTEMPTS) {
                finish(companyId, eventId, "FAILED", "FAILED");
                tellFailure(system, event);
            }
            else {
                Instant next = time.now().plusSeconds(1L << (event.attempts() - 1));
                transactions.runInCompany(companyId, () -> store.retry(eventId, next));
            }
            return;
        }
        if (outcome.status() != null) {
            finish(companyId, eventId, outcome.status(), outcome.reason());
        }
    }

    private record Claimed(ChatStore.EventRow event, @Nullable ChatSystemInfo system) {
    }

    private Outcome dispatch(ChatSystemInfo system, ChatStore.EventRow event) {
        if (Inbound.LIFECYCLE.equals(event.kind())) {
            Handler lifecycle = handlers.get(Inbound.LIFECYCLE);
            return lifecycle != null ? lifecycle.handle(system, event, new ChatAudience.Who(
                    ChatAudience.Audience.MEMBER, null, null)) : Outcome.ignored("NOT_ADDRESSED");
        }
        ChatBot.State state = transactions.inCompany(system.companyId(), () -> bot.state(system));
        if (state == ChatBot.State.PAUSED) {
            // G5: a paused system's events are verified and dropped, never answered later.
            return Outcome.ignored("SYSTEM_PAUSED");
        }
        if (state != ChatBot.State.USABLE) {
            // C14: verified, then dropped unanswered, and never answered later.
            return Outcome.ignored("NO_BOT");
        }
        String sender = event.sender();
        if (sender == null) {
            return Outcome.ignored("NOT_ADDRESSED");
        }
        Map<String, Object> payload = event.payload();
        ChatAudience.Who who = audience.of(system, sender, text(payload, "channel"), text(payload, "serviceUrl"));
        if (!who.member()) {
            refuse(system, event, who.audience().name(), "Tinfirm serves members of this workspace only.");
            return Outcome.refused(who.audience().name());
        }
        String aadObjectId = who.aadObjectId() != null ? who.aadObjectId() : text(payload, "aadObjectId");
        if (transactions.inCompany(system.companyId(), () -> links.suspended(system, sender, aadObjectId))) {
            // G6: a suspended member acts for nothing, here as in the app; refused as a non-member is.
            String company = transactions.inCompany(system.companyId(),
                    () -> companies.nameOf(system.companyId()).orElse("this company"));
            refuse(system, event, "SUSPENDED", "Your Tinfirm account is suspended in " + company + ".");
            return Outcome.refused("SUSPENDED");
        }
        if ("RATE_LIMITED".equals(payload.get("refusal"))) {
            tell(system, event, "You sent more messages than this workspace allows for now. Try again later.");
            return Outcome.refused("RATE_LIMITED");
        }
        Handler handler = handlers.get(event.kind());
        return handler != null ? handler.handle(system, event, who) : Outcome.ignored("NOT_ADDRESSED");
    }

    /**
     * C6: the refusal of a guest, another organisation's user, a bot or a deleted user, and G6's of a suspended member,
     * told once an hour per chat user (an ephemeral message on Slack; in a direct message on Teams) and audited
     * {@code chat.refused} then.
     */
    private void refuse(ChatSystemInfo system, ChatStore.EventRow event, String reason, String told) {
        String sender = java.util.Objects.requireNonNull(event.sender());
        Instant now = time.now();
        boolean tell = transactions.inCompany(system.companyId(), () -> {
            store.seen(system.companyId(), system.id(), sender, null, null, null, now);
            boolean due = store.tellRefusal(system.id(), sender, now, now.minus(REFUSAL_TOLD));
            if (due) {
                auditLog.record(AuditRecord.company(system.companyId(), AuditAction.CHAT_REFUSED, Actor.SYSTEM)
                    .target("system", system.id())
                    .detail("systemId", system.id().toString())
                    .detail("chatUserId", sender)
                    .detail("channelId", text(event.payload(), "channel"))
                    .detail("reason", reason)
                    .build());
            }
            return due;
        });
        boolean personal = "im".equals(event.payload().get("channelType"))
                || "personal".equals(event.payload().get("channelType"));
        if (tell && (system.slack() || personal)) {
            tell(system, event, told);
        }
    }

    /**
     * Tells the sender alone: in a direct message, there; in a Slack channel, an ephemeral message in its thread; in a
     * Teams channel, which has none, in the sender's direct message with the bot when the Bot Framework opens one,
     * else nothing (the package 5 contract, §5.8).
     */
    void tell(ChatSystemInfo system, ChatStore.EventRow event, String text) {
        Map<String, Object> payload = event.payload();
        String channel = text(payload, "channel");
        if (channel == null || event.sender() == null) {
            return;
        }
        ChatPoster.Target target = new ChatPoster.Target(channel, threadOf(payload), text(payload, "serviceUrl"));
        boolean personal = "im".equals(payload.get("channelType")) || "personal".equals(payload.get("channelType"));
        if (personal) {
            poster.post(system.companyId(), system.id(), target, ChatCards.Message.of(text), null);
        }
        else if (system.slack()) {
            poster.tell(system.companyId(), system.id(), target, event.sender(), text);
        }
        else if (target.serviceUrl() != null) {
            String direct = poster.openDirect(system.companyId(), system.id(), event.sender(), target.serviceUrl())
                .orElse(null);
            if (direct != null) {
                poster.post(system.companyId(), system.id(), new ChatPoster.Target(direct, null, target.serviceUrl()),
                        ChatCards.Message.of(text), null);
            }
        }
    }

    private void tellFailure(ChatSystemInfo system, ChatStore.EventRow event) {
        if (Inbound.MESSAGE.equals(event.kind()) || Inbound.ACTION.equals(event.kind())) {
            tell(system, event, "Something went wrong while Tinfirm took this. Please send it again.");
        }
    }

    void finish(UUID companyId, UUID eventId, String status, @Nullable String reason) {
        transactions.runInCompany(companyId, () -> store.finish(eventId, status, reason, time.now()));
    }

    // ---- the holds' and the rows' ends -----------------------------------------------------------------------------

    /** Held events whose hold ran out are dropped and their sender told; events go a day after they arrived. */
    void expire() {
        Instant now = time.now();
        for (UUID[] candidate : transactions.unscoped(() -> store.expired(now, 200))) {
            UUID eventId = candidate[0];
            UUID companyId = candidate[1];
            Expired expired = transactions.inCompany(companyId, () -> store.lock(eventId).map(event -> {
                boolean held = "AWAITING_LINK".equals(event.status()) || "AWAITING_AGENT".equals(event.status());
                if (held) {
                    store.finish(eventId, "IGNORED", "AWAITING_LINK".equals(event.status()) ? "LINK_EXPIRED"
                            : "NO_AGENT_CHOSEN", now);
                    return new Expired(event, chatSystems.find(companyId, event.systemId()).orElse(null));
                }
                store.delete(eventId);
                return null;
            }).orElse(null));
            if (expired != null && expired.system() != null) {
                // Told on a thread of its own: a slow platform never holds the scheduler's thread.
                executor.submit(() -> tell(expired.system(), expired.event(),
                        "AWAITING_LINK".equals(expired.event().status())
                                ? "Your message was not answered: the sign-in link expired. Send it again to get a new"
                                        + " link."
                                : "Your message was not answered: no agent was chosen within an hour. Send it again."));
            }
        }
    }

    private record Expired(ChatStore.EventRow event, @Nullable ChatSystemInfo system) {
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    static @Nullable String text(Map<String, Object> payload, String field) {
        Object value = payload.get(field);
        return value instanceof String string && !string.isEmpty() ? string : null;
    }

    /** The thread a reply goes to: Slack's thread or the message itself; none on Teams' personal chats. */
    static @Nullable String threadOf(Map<String, Object> payload) {
        if ("TEAMS".equals(payload.get("platform"))) {
            return null;
        }
        String thread = text(payload, "threadTs");
        return thread != null ? thread : text(payload, "ts") != null ? text(payload, "ts")
                : text(payload, "messageTs");
    }

    Optional<ChatStore.EventRow> find(UUID companyId, UUID eventId) {
        return transactions.inCompany(companyId, () -> store.find(eventId));
    }
}
