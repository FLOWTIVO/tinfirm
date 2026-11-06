package com.flowtivo.tinfirm.trigger.internal;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.flowtivo.tinfirm.agent.AgentInfo;
import com.flowtivo.tinfirm.agent.AgentStatus;
import com.flowtivo.tinfirm.agent.Agents;
import com.flowtivo.tinfirm.audit.AuditAction;
import com.flowtivo.tinfirm.audit.AuditLog;
import com.flowtivo.tinfirm.audit.AuditRecord;
import com.flowtivo.tinfirm.conversation.ConversationInfo;
import com.flowtivo.tinfirm.conversation.TriggerConversations;
import com.flowtivo.tinfirm.shared.id.Ids;
import com.flowtivo.tinfirm.shared.security.CurrentActor;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.system.SystemInfo;
import com.flowtivo.tinfirm.system.Systems;
import com.flowtivo.tinfirm.system.WebhookSystems;
import com.flowtivo.tinfirm.trigger.FiringOutcome;
import com.flowtivo.tinfirm.trigger.PausedReason;
import com.flowtivo.tinfirm.trigger.TriggerEvent;
import com.flowtivo.tinfirm.trigger.TriggerGate;
import com.flowtivo.tinfirm.trigger.TriggerInfo;
import com.flowtivo.tinfirm.trigger.WaitingReason;

/**
 * A webhook delivery's firing of one trigger (the package 5 contract, §5.12), in the delivery's transaction, which
 * inserted the delivery and mapped it; the trigger matched it (its events and conditions). In order, as package 4's
 * firings (the package 4 contract, §5.6):
 * <ol>
 * <li><b>the gate</b> ({@link TriggerGate#check}, which locks the trigger's row): paused (by hand,
 * {@code ACCOUNT_BROKEN}, {@code AUTHOR_LEFT}, its system paused) or waiting so that it fires nothing, it fires nothing
 * and records nothing, then or later (decisions 9 and 10);</li>
 * <li><b>the agent</b> paused: nothing fires;</li>
 * <li><b>the rate</b>: past {@code max_firings_per_hour}, {@code SKIPPED_RATE}, recorded once an hour;</li>
 * <li><b>echoes</b> (row 10): a delivery about a subject key a conversation of the trigger wrote to within the settle
 * time ({@code tinfirm.trigger.settle}) fires one deeper than that conversation's deepest firing; beyond 3 it is
 * {@code SKIPPED_CHAIN}, and a {@code SYSTEM} message in the writing conversation says it started nothing;</li>
 * <li><b>the conversation</b> (open point 17): with a subject key, the trigger's open conversation on it takes the
 * delivery ({@code CONTINUED}: an {@code EVENT} turn now, or the next turn), else a new one starts; without one, a new
 * conversation per delivery, the trigger's previous idle one without a key ending {@code SUPERSEDED}.</li>
 * </ol>
 * The firing's event is {@code DELIVERED} with the mapped event as its value, its actor as the sender said
 * ({@code {id, login: null, name}}, never a member, C13), its depth. The delivery's text is also the conversation's
 * {@code EXTERNAL} message, without an author, which answers nothing. Audited as {@code trigger.fired} with the
 * delivery's id, never its body (C16).
 * <p>
 * The company's stop or the source system's pause committed after the inbox checked them (the gate answers
 * {@code COMPANY_STOPPED} or {@code SYSTEM_PAUSED}) throws {@link Held}: the delivery's transaction rolls back, nothing
 * is stored, and the inbox answers as if it had seen them ({@code 503} with {@code Retry-After}, or
 * {@code 200 {"paused": true}}), so that the delivery is not lost as {@code NO_TRIGGER}.
 */
@Component
class WebhookFirings {

    private static final Duration HOUR = Duration.ofHours(1);

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'")
        .withZone(ZoneOffset.UTC);

    /** A conversation's title: {@code {trigger}: {key} {title}}, at most this long. */
    private static final int TITLE = 200;

    private final PollStore firings;

    private final WebhookStore webhooks;

    private final TriggerGate gate;

    private final Agents agents;

    private final FiringConversations firingConversations;

    private final TriggerConversations conversations;

    private final PollProperties pollProperties;

    private final Systems systems;

    private final AuditLog auditLog;

    private final TimeSource time;

    WebhookFirings(PollStore firings, WebhookStore webhooks, TriggerGate gate, Agents agents,
            FiringConversations firingConversations, TriggerConversations conversations, PollProperties pollProperties,
            Systems systems, AuditLog auditLog, TimeSource time) {
        this.firings = firings;
        this.webhooks = webhooks;
        this.gate = gate;
        this.agents = agents;
        this.firingConversations = firingConversations;
        this.conversations = conversations;
        this.pollProperties = pollProperties;
        this.systems = systems;
        this.auditLog = auditLog;
        this.time = time;
    }

    /**
     * A verified delivery, stored and mapped.
     *
     * @param rowId      its {@code webhook_delivery} row
     * @param deliveryId its id: {@code webhook-id}, the mapped one, or the SHA-256 of the body
     * @param body       the raw body as received, for the prompt (cut to 16 KiB)
     */
    record Delivery(UUID rowId, String deliveryId, WebhookSystems.WebhookSystem system, Instant receivedAt,
            WebhookMapping.Mapped mapped, String body) {
    }

    /** What the delivery did for the trigger: a firing's outcome, or why nothing fired. */
    record Fired(@Nullable FiringOutcome outcome, @Nullable UUID conversationId, @Nullable String stopped) {

        /** Whether it started or continued a conversation. */
        boolean took() {
            return outcome == FiringOutcome.STARTED || outcome == FiringOutcome.CONTINUED;
        }
    }

    /** The company's stop or the source system's pause, seen by the gate after the inbox's check: nothing is stored. */
    static final class Held extends RuntimeException {

        private static final long serialVersionUID = 1L;

        /** Whether it is the company's stop ({@code 503}); else the system's pause ({@code 200 {"paused": true}}). */
        private final boolean stopped;

        Held(boolean stopped) {
            super(stopped ? "The company's runs are stopped" : "The webhook system is paused", null, false, false);
            this.stopped = stopped;
        }

        boolean stopped() {
            return stopped;
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    Fired fire(TriggerInfo trigger, Delivery delivery) {
        UUID companyId = trigger.companyId();
        TriggerGate.Result gated = gate.check(companyId, trigger.id());
        if (gated instanceof TriggerGate.Result.Waits waits && waits.reason() == WaitingReason.COMPANY_STOPPED) {
            throw new Held(true);
        }
        if (gated instanceof TriggerGate.Result.Paused paused && paused.reason() == PausedReason.SYSTEM_PAUSED
                && systems.find(companyId, delivery.system().id()).map(SystemInfo::paused).orElse(false)) {
            throw new Held(false);
        }
        if (!gated.fires()) {
            return new Fired(null, null, Firings.gateWords(gated));
        }
        Optional<AgentInfo> agent = agents.find(companyId, trigger.agentId()).filter(AgentInfo::live);
        if (agent.isEmpty() || agent.get().status() == AgentStatus.PAUSED) {
            return new Fired(null, null, "its agent is paused");
        }
        Instant now = time.now();
        WebhookMapping.Mapped mapped = delivery.mapped();
        String key = mapped.subjectKey();
        String request = request(delivery);
        if (firings.firedSince(trigger.id(), now.minus(HOUR)) >= trigger.maxFiringsPerHour()) {
            firings.countRateSkipped(trigger.id());
            if (!firings.recordedSince(trigger.id(), FiringOutcome.SKIPPED_RATE, now.minus(HOUR))) {
                record(trigger, delivery, FiringOutcome.SKIPPED_RATE, "More than " + trigger.maxFiringsPerHour()
                        + " firings within an hour", 0, request, null, null, now);
            }
            return new Fired(FiringOutcome.SKIPPED_RATE, null, "it fired " + trigger.maxFiringsPerHour()
                    + " times within the last hour");
        }
        int depth = 0;
        if (key != null) {
            Optional<WebhookStore.Echo> echo = webhooks.echo(companyId, trigger.id(), key,
                    delivery.receivedAt().minus(pollProperties.settle()));
            if (echo.isPresent()) {
                depth = echo.get().depth() + 1;
                if (depth > Firings.MAX_CHAIN) {
                    record(trigger, delivery, FiringOutcome.SKIPPED_CHAIN, key + " came back from a write at the end of"
                            + " a chain of " + Firings.MAX_CHAIN + " triggered conversations", Firings.MAX_CHAIN + 1,
                            request, null, null, now);
                    conversations.post(companyId, echo.get().conversationId(), "A delivery of "
                            + delivery.system().name() + " about " + key + " started nothing: it came back from this "
                            + "conversation's write, at the end of a chain of " + Firings.MAX_CHAIN
                            + " triggered conversations.");
                    return new Fired(FiringOutcome.SKIPPED_CHAIN, null, "the chain of triggered conversations is "
                            + Firings.MAX_CHAIN + " deep");
                }
            }
        }
        if (key != null) {
            List<ConversationInfo> open = conversations.openOnWebhook(companyId, trigger.id(), key);
            if (!open.isEmpty()) {
                UUID conversationId = open.getFirst().id();
                record(trigger, delivery, FiringOutcome.CONTINUED, null, depth, request, null, conversationId, now);
                conversations.external(companyId, conversationId, external(delivery));
                firingConversations.continueWith(companyId, conversationId);
                return new Fired(FiringOutcome.CONTINUED, conversationId, null);
            }
        }
        else {
            // A conversation per delivery: the previous idle one ends, as a schedule's does (package 4, §5.10).
            for (ConversationInfo previous : conversations.openOnWebhook(companyId, trigger.id(), null)) {
                if (!conversations.busy(companyId, previous.id())) {
                    conversations.end(companyId, previous.id(), ConversationInfo.EndedReason.SUPERSEDED, null);
                }
            }
        }
        UUID firingId = record(trigger, delivery, FiringOutcome.STARTED, null, depth, request, summary(delivery), null,
                now);
        Map<String, String> placeRef = new LinkedHashMap<>();
        placeRef.put("systemId", delivery.system().id().toString());
        placeRef.put("deliveryId", delivery.deliveryId());
        if (key != null) {
            placeRef.put("subjectKey", key);
            // The subject's title and link as the mapping read them, for the conversation's subject; a link only over
            // http(s), since the page shows it as one.
            if (mapped.subjectTitle() != null && !mapped.subjectTitle().isBlank()) {
                placeRef.put("subjectTitle", FiringTexts.cut(mapped.subjectTitle().strip(), 500));
            }
            String url = mapped.subjectUrl() != null ? mapped.subjectUrl().strip() : null;
            if (url != null && url.length() <= 2000 && (url.startsWith("https://") || url.startsWith("http://"))) {
                placeRef.put("subjectUrl", url);
            }
        }
        TriggerConversations.Started started = firingConversations.start(companyId, trigger.id(),
                new FiringConversations.Place.Held(ConversationInfo.Place.WEBHOOK,
                        new TriggerConversations.HeldPlace(placeRef, title(trigger, mapped))),
                Math.min(depth, Firings.MAX_CHAIN), firingId, null);
        conversations.external(companyId, started.conversation().id(), external(delivery));
        return new Fired(FiringOutcome.STARTED, started.conversation().id(), null);
    }

    /** Inserts and audits the firing. */
    private UUID record(TriggerInfo trigger, Delivery delivery, FiringOutcome outcome, @Nullable String reason,
            int depth, String request, @Nullable String summary, @Nullable UUID conversationId, Instant now) {
        WebhookMapping.Mapped mapped = delivery.mapped();
        String subject = mapped.subjectKey() != null ? mapped.subjectKey() : delivery.deliveryId();
        UUID id = Ids.newId();
        firings.insertFiring(new PollStore.Firing(id, trigger.companyId(), trigger.id(), FiringTexts.cut(subject, 255),
                FiringTexts.cut(subject, 255), TriggerEvent.DELIVERED, mapped.event(), mapped.actor(),
                FiringTexts.cut(delivery.deliveryId(), 255), delivery.receivedAt(), null, null, false, outcome, reason,
                Math.min(depth, Firings.MAX_CHAIN + 1), request, summary, conversationId, now, null));
        auditLog.record(AuditRecord.company(trigger.companyId(), AuditAction.TRIGGER_FIRED, CurrentActor.actor())
            .target(TriggerStates.TARGET, trigger.id())
            .detail("firingId", id.toString())
            .detail("subjectKey", mapped.subjectKey())
            .detail("event", TriggerEvent.DELIVERED.name())
            .detail("outcome", outcome.name())
            .detail("conversationId", conversationId != null ? conversationId.toString() : null)
            .detail("chainDepth", depth)
            .detail("manual", false)
            .detail("systemId", delivery.system().id().toString())
            .detail("deliveryId", delivery.deliveryId())
            .build());
        return id;
    }

    /** The delivery's text as the conversation keeps it: the sender's words, without a member (C13). */
    private static TriggerConversations.ExternalComment external(Delivery delivery) {
        WebhookMapping.Mapped mapped = delivery.mapped();
        Map<String, String> author = new LinkedHashMap<>();
        if (mapped.actorId() != null) {
            author.put("id", mapped.actorId());
        }
        author.put("name", mapped.actorName() != null ? mapped.actorName()
                : mapped.actorId() != null ? mapped.actorId() : delivery.system().name());
        author.put("via", "WEBHOOK");
        author.put("verified", "false");
        String body = mapped.text() != null ? mapped.text()
                : "(a delivery" + (mapped.event() != null ? " of " + mapped.event() : "")
                        + (mapped.subjectKey() != null ? " about " + mapped.subjectKey() : "") + ")";
        return new TriggerConversations.ExternalComment(delivery.deliveryId(), author, null, body,
                delivery.receivedAt(), false);
    }

    /** The first turn's request, or the next turn's: what the sender sent, as its words. */
    static String request(Delivery delivery) {
        WebhookMapping.Mapped mapped = delivery.mapped();
        StringBuilder text = new StringBuilder(delivery.system().name()).append(" sent ")
            .append(mapped.event() != null ? mapped.event() : "a delivery");
        if (mapped.subjectKey() != null) {
            text.append(" about ").append(mapped.subjectKey());
            if (mapped.subjectTitle() != null) {
                text.append(": ").append(mapped.subjectTitle());
            }
        }
        if (mapped.actorName() != null || mapped.actorId() != null) {
            text.append(", by ").append(mapped.actorName() != null ? mapped.actorName() : mapped.actorId())
                .append(" (as the sender says, unverified)");
        }
        text.append(", at ").append(CLOCK.format(delivery.receivedAt())).append('.');
        if (mapped.text() != null) {
            text.append("\n\n");
            for (String line : mapped.text().strip().split("\n", -1)) {
                text.append("> ").append(line).append('\n');
            }
        }
        return FiringTexts.cut(text.toString().stripTrailing(), 20_000);
    }

    /** A started firing's {@code # Event}: the sender's data, other people's words, cut to 16 KiB. */
    static String summary(Delivery delivery) {
        WebhookMapping.Mapped mapped = delivery.mapped();
        StringBuilder text = new StringBuilder();
        text.append("System: ").append(delivery.system().name()).append('\n');
        text.append("Received: ").append(CLOCK.format(delivery.receivedAt())).append('\n');
        text.append("Delivery: ").append(delivery.deliveryId()).append('\n');
        if (mapped.event() != null) {
            text.append("Event: ").append(mapped.event()).append('\n');
        }
        if (mapped.subjectKey() != null || mapped.subjectTitle() != null || mapped.subjectUrl() != null) {
            text.append("Subject: ").append(String.join(" ", java.util.stream.Stream.of(mapped.subjectKey(),
                    mapped.subjectTitle(), mapped.subjectUrl()).filter(java.util.Objects::nonNull).toList()))
                .append('\n');
        }
        if (mapped.actorName() != null || mapped.actorId() != null) {
            text.append("Actor: ").append(mapped.actorName() != null ? mapped.actorName() : mapped.actorId());
            if (mapped.actorName() != null && mapped.actorId() != null) {
                text.append(" (").append(mapped.actorId()).append(')');
            }
            text.append(", as the sender says, unverified\n");
        }
        if (mapped.text() != null) {
            text.append("Text:\n").append(mapped.text().strip()).append('\n');
        }
        text.append("Body:\n```json\n").append(WebhookMapping.cutBody(delivery.body()
            .getBytes(java.nio.charset.StandardCharsets.UTF_8))).append("\n```");
        return FiringTexts.cut(text.toString(), 16_384);
    }

    /** {@code {trigger}: {key} {title}}, or the event, or the delivery (§5.12). */
    static String title(TriggerInfo trigger, WebhookMapping.Mapped mapped) {
        String about = mapped.subjectKey() != null
                ? mapped.subjectKey() + (mapped.subjectTitle() != null ? " " + mapped.subjectTitle() : "")
                : mapped.subjectTitle() != null ? mapped.subjectTitle()
                        : mapped.event() != null ? mapped.event() : "a delivery";
        String title = trigger.name() + ": " + about.strip().lines().findFirst().orElse("").strip();
        return title.length() <= TITLE ? title : title.substring(0, TITLE - 1) + "…";
    }
}
