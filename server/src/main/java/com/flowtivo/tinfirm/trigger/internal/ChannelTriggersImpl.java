package com.flowtivo.tinfirm.trigger.internal;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
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
import com.flowtivo.tinfirm.company.GroupMembership;
import com.flowtivo.tinfirm.principal.PrincipalInfo;
import com.flowtivo.tinfirm.principal.Principals;
import com.flowtivo.tinfirm.shared.id.Ids;
import com.flowtivo.tinfirm.shared.security.CurrentActor;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.trigger.ChannelTriggers;
import com.flowtivo.tinfirm.trigger.FiringOutcome;
import com.flowtivo.tinfirm.trigger.TriggerEvent;
import com.flowtivo.tinfirm.trigger.TriggerGate;
import com.flowtivo.tinfirm.trigger.TriggerInfo;
import com.flowtivo.tinfirm.trigger.TriggerSource;

/**
 * {@link ChannelTriggers} (the package 5 contract, §5.7, §5.14): a mention's firing, in the chat event's transaction.
 * In order, as package 4's firings (the package 4 contract, §5.6):
 * <ol>
 * <li><b>the gate</b> ({@link TriggerGate#check}, which locks the trigger's row): paused, or waiting so that it fires
 * nothing (its bot, its payer, the company's stop), fires nothing and records nothing (decision 10);</li>
 * <li><b>the agent</b> paused: nothing fires;</li>
 * <li><b>the rate</b>: past {@code max_firings_per_hour}, {@code SKIPPED_RATE}, recorded once an hour;</li>
 * <li><b>the conversation</b>: a firing {@code MENTIONED} with the chat user as its actor ({@code login} null, the
 * linked member as {@code actor_principal_id}), and a new conversation in the thread ({@code CHAT_THREAD}, kind
 * {@code CHANNEL}), its {@code FIRST} turn's request the mention, which is also its first {@code EXTERNAL}
 * message.</li>
 * </ol>
 * Audited as {@code trigger.fired} with the chat user's id, the member when linked and the message's id, never its
 * text (C16).
 */
@Component
class ChannelTriggersImpl implements ChannelTriggers {

    private static final Duration HOUR = Duration.ofHours(1);

    /** A conversation's title: {@code {trigger}: {first line}}, at most this long. */
    private static final int TITLE = 200;

    private final TriggerStore store;

    private final PollStore firings;

    private final TriggerGate gate;

    private final Agents agents;

    private final FiringConversations firingConversations;

    private final TriggerConversations conversations;

    private final AuditLog auditLog;

    private final TimeSource time;

    private final Principals principals;

    private final GroupMembership groups;

    ChannelTriggersImpl(TriggerStore store, PollStore firings, TriggerGate gate, Agents agents,
            FiringConversations firingConversations, TriggerConversations conversations, AuditLog auditLog,
            TimeSource time, Principals principals, GroupMembership groups) {
        this.principals = principals;
        this.groups = groups;
        this.store = store;
        this.firings = firings;
        this.gate = gate;
        this.agents = agents;
        this.firingConversations = firingConversations;
        this.conversations = conversations;
        this.auditLog = auditLog;
        this.time = time;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<ChannelTrigger> ofChannel(UUID companyId, UUID systemId, String channelId) {
        return store.ofChannel(companyId, systemId, channelId).stream()
            .map(row -> new ChannelTrigger(row.triggerId(), row.triggerName(), row.agentId(), row.isDefault(),
                    row.available()))
            .toList();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<OnChannel> onChannels(UUID companyId, UUID systemId, Collection<String> channelIds,
            @Nullable UUID viewerPrincipalId, boolean admin) {
        java.util.Set<UUID> groups = viewerPrincipalId == null || admin ? java.util.Set.of()
                : principals.find(companyId, viewerPrincipalId)
                    .map(PrincipalInfo::userId)
                    .filter(java.util.Objects::nonNull)
                    .map(userId -> this.groups.groupsOf(companyId, userId))
                    .orElse(java.util.Set.of());
        return store.onChannels(companyId, systemId, channelIds).stream()
            .map(row -> new OnChannel(row.channelId(), row.triggerId(), row.triggerName(), row.agentId(),
                    row.isDefault(), admin || row.authorPrincipalId().equals(viewerPrincipalId)
                            || row.teamGroupId() != null && groups.contains(row.teamGroupId())))
            .toList();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Fired fire(UUID companyId, Mention mention) {
        Optional<Fired.Stopped> stopped = stopped(companyId, mention.triggerId());
        if (stopped.isPresent()) {
            return stopped.get();
        }
        TriggerInfo trigger = store.find(companyId, mention.triggerId()).filter(TriggerInfo::live).orElse(null);
        if (trigger == null || trigger.source() != TriggerSource.CHANNEL) {
            return new Fired.Stopped("NO_TRIGGER", "No agent answers here now");
        }
        String agentName = agents.find(companyId, trigger.agentId()).map(AgentInfo::name).orElse("The agent");
        Instant now = time.now();
        String subjectId = mention.channelId() + ":" + mention.threadId();
        String subjectKey = mention.channelName() != null ? "#" + mention.channelName() : mention.channelId();
        Map<String, @Nullable String> actor = new LinkedHashMap<>();
        actor.put("id", mention.askerId());
        actor.put("name", mention.askerName());
        String request = request(mention, subjectKey);
        if (firings.firedSince(trigger.id(), now.minus(HOUR)) >= trigger.maxFiringsPerHour()) {
            firings.countRateSkipped(trigger.id());
            if (!firings.recordedSince(trigger.id(), FiringOutcome.SKIPPED_RATE, now.minus(HOUR))) {
                UUID skipped = Ids.newId();
                firings.insertFiring(new PollStore.Firing(skipped, companyId, trigger.id(), subjectId, subjectKey,
                        TriggerEvent.MENTIONED, null, actor, mention.messageId(), now, null, null, false,
                        FiringOutcome.SKIPPED_RATE, "More than " + trigger.maxFiringsPerHour()
                                + " firings within an hour", 0, request, null, null, now,
                        mention.askerPrincipalId()));
                audit(trigger, skipped, subjectKey, FiringOutcome.SKIPPED_RATE, null, mention);
            }
            return new Fired.Stopped("RATE_LIMITED", "Too many requests to " + agentName + " this hour");
        }
        UUID firingId = Ids.newId();
        firings.insertFiring(new PollStore.Firing(firingId, companyId, trigger.id(), subjectId, subjectKey,
                TriggerEvent.MENTIONED, null, actor, mention.messageId(), now, null, null, false,
                FiringOutcome.STARTED, null, 0, request, null, null, now, mention.askerPrincipalId()));
        Map<String, String> placeRef = new LinkedHashMap<>();
        placeRef.put("systemId", mention.systemId().toString());
        placeRef.put("kind", "CHANNEL");
        if (mention.teamId() != null) {
            placeRef.put("teamId", mention.teamId());
        }
        placeRef.put("channelId", mention.channelId());
        placeRef.put("threadId", mention.threadId());
        if (mention.channelName() != null) {
            placeRef.put("channelName", mention.channelName());
        }
        if (mention.serviceUrl() != null) {
            placeRef.put("serviceUrl", mention.serviceUrl());
        }
        // Who asked, for the prompt's "started" line (§5.7): the chat name, and the member when linked.
        placeRef.put("askerName", mention.askerName());
        if (mention.askerPrincipalId() != null) {
            placeRef.put("askerPrincipalId", mention.askerPrincipalId().toString());
        }
        TriggerConversations.Started started = firingConversations.start(companyId, trigger.id(),
                new FiringConversations.Place.Held(ConversationInfo.Place.CHAT_THREAD,
                        new TriggerConversations.HeldPlace(placeRef, title(trigger, mention.text()))),
                0, firingId, null);
        UUID conversationId = started.conversation().id();
        // The mention is also the conversation's first message of the place: what the next turns read (§5.7).
        Map<String, String> author = new LinkedHashMap<>();
        author.put("id", mention.askerId());
        author.put("name", mention.askerName());
        author.put("via", mention.via());
        conversations.external(companyId, conversationId, new TriggerConversations.ExternalComment(
                mention.messageId(), author, mention.askerPrincipalId(), mention.text().isBlank() ? "(a mention)"
                        : mention.text(), now, false));
        audit(trigger, firingId, subjectKey, FiringOutcome.STARTED, conversationId, mention);
        return new Fired.Started(firingId, conversationId);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Fired.Stopped> stopped(UUID companyId, UUID triggerId) {
        TriggerGate.Result gated = gate.check(companyId, triggerId);
        TriggerInfo trigger = store.find(companyId, triggerId).orElse(null);
        String agentName = trigger != null ? agents.find(companyId, trigger.agentId()).map(AgentInfo::name)
            .orElse("The agent") : "The agent";
        return switch (gated) {
            case TriggerGate.Result.Gone gone -> Optional.of(new Fired.Stopped("NO_TRIGGER",
                    "No agent answers here now"));
            case TriggerGate.Result.Paused paused -> Optional.of(new Fired.Stopped("TRIGGER_PAUSED",
                    agentName + " is paused here"));
            case TriggerGate.Result.Waits waits -> Optional.of(new Fired.Stopped("TRIGGER_PAUSED",
                    agentName + " cannot answer now"));
            case TriggerGate.Result.Passes passes -> {
                boolean paused = trigger == null || agents.find(companyId, trigger.agentId())
                    .filter(AgentInfo::live)
                    .map(agent -> agent.status() == AgentStatus.PAUSED)
                    .orElse(true);
                yield paused ? Optional.of(new Fired.Stopped("AGENT_PAUSED", agentName + " is paused"))
                        : Optional.empty();
            }
        };
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void channelState(UUID companyId, UUID systemId, String channelId, boolean available) {
        for (UUID triggerId : store.channelState(companyId, systemId, channelId, available)) {
            // Its waits again: CHANNEL_UNAVAILABLE once every channel is, gone once one is back.
            gate.check(companyId, triggerId);
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void botChanged(UUID companyId, UUID systemId) {
        for (TriggerInfo trigger : store.liveOnSystem(companyId, systemId)) {
            if (trigger.source() == TriggerSource.CHANNEL) {
                gate.check(companyId, trigger.id());
            }
        }
    }

    /** The first turn's request: who asked, where, and what, as other people's words. */
    static String request(Mention mention, String channel) {
        StringBuilder text = new StringBuilder(mention.askerName());
        text.append(" (").append(mention.via().equals("TEAMS") ? "Teams" : "Slack");
        if (mention.askerPrincipalId() == null) {
            text.append(", not a Tinfirm member");
        }
        text.append(") asked in ").append(channel).append(":\n\n");
        for (String line : mention.text().strip().split("\n", -1)) {
            text.append("> ").append(line).append('\n');
        }
        return FiringTexts.cut(text.toString().stripTrailing(), 20_000);
    }

    /** {@code {trigger}: {first line}} (§5.7). */
    static String title(TriggerInfo trigger, String text) {
        String first = text.strip().lines().findFirst().orElse("").strip();
        String title = trigger.name() + (first.isEmpty() ? "" : ": " + first);
        return title.length() <= TITLE ? title : title.substring(0, TITLE - 1) + "…";
    }

    private void audit(TriggerInfo trigger, UUID firingId, String subjectKey, FiringOutcome outcome,
            @Nullable UUID conversationId, Mention mention) {
        auditLog.record(AuditRecord.company(trigger.companyId(), AuditAction.TRIGGER_FIRED, CurrentActor.actor())
            .target(TriggerStates.TARGET, trigger.id())
            .detail("firingId", firingId.toString())
            .detail("subjectKey", subjectKey)
            .detail("event", TriggerEvent.MENTIONED.name())
            .detail("outcome", outcome.name())
            .detail("conversationId", conversationId != null ? conversationId.toString() : null)
            .detail("chainDepth", 0)
            .detail("manual", false)
            .detail("systemId", mention.systemId().toString())
            .detail("channelId", mention.channelId())
            .detail("threadId", mention.threadId())
            .detail("messageId", mention.messageId())
            .detail("chatUserId", mention.askerId())
            .detail("memberPrincipalId", mention.askerPrincipalId() != null ? mention.askerPrincipalId().toString()
                    : null)
            .build());
    }
}
