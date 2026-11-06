package com.flowtivo.tinfirm.chat.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.system.ChatSystemInfo;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * What every verified event goes through before it is acknowledged (the package 5 contract, §5.2, C1, C2, C3, C15),
 * whether it arrived over HTTP or over a Socket Mode connection: the workspace or tenant it names must be the
 * system's (else stored nowhere); then the company's rates; then one short transaction stores it. Nothing else runs
 * before the acknowledgement.
 */
@Component
class ChatReceiver {

    private static final Logger log = LoggerFactory.getLogger(ChatReceiver.class);

    static final String REFUSED_METRIC = "tinfirm.chat.inbound.refused";

    /** What became of an event. */
    enum Received {
        /** Stored, or stored before (a retry): acknowledge it. */
        STORED,
        /** Of another workspace or tenant (C2): acknowledged, stored nowhere, never retried. */
        OTHER_WORKSPACE,
        /** The database failed: {@code 503}, or no acknowledgement on Socket Mode, so the platform delivers again. */
        FAILED
    }

    private final ChatInbox inbox;

    private final InboundLimits limits;

    private final ChatSilence silence;

    private final MeterRegistry meters;

    ChatReceiver(ChatInbox inbox, InboundLimits limits, ChatSilence silence, MeterRegistry meters) {
        this.inbox = inbox;
        this.limits = limits;
        this.silence = silence;
        this.meters = meters;
    }

    /**
     * Receives a verified event of the system.
     *
     * @param http whether it arrived over HTTP (a Slack {@code 503} is remembered for a silent subscription)
     */
    Received receive(ChatSystemInfo system, Inbound inbound, boolean http) {
        String workspace = system.workspace();
        if (workspace == null || inbound.workspace() == null || !workspace.equalsIgnoreCase(inbound.workspace())) {
            refused(system, "OTHER_WORKSPACE");
            log.info("An event of the chat system {} named another workspace: dropped", system.id());
            return Received.OTHER_WORKSPACE;
        }
        Inbound limited = limit(system, inbound);
        ChatInbox.Stored stored = inbox.store(new ChatStore.SystemRef(system.id(), system.companyId(),
                system.kind().name()), limited);
        if (stored == ChatInbox.Stored.FAILED) {
            if (http && system.slack()) {
                silence.failed(system.companyId(), system.id());
            }
            return Received.FAILED;
        }
        return Received.STORED;
    }

    /**
     * C15, in the order of the security review of 2026-10-27 (L7): a message counts against its sender's rate first,
     * and an event refused there counts nowhere else, so that one chat user cannot spend the system's rate; a guest's
     * or another organisation's event then counts in their small bucket, never in the system's; every other event
     * against the system's rate. The excess is stored {@code REFUSED RATE_LIMITED}, acknowledged so that the platform
     * does not retry it; the sender over their own rate is told once a minute (the worker tells, from the event's
     * {@code refusal}).
     */
    private Inbound limit(ChatSystemInfo system, Inbound inbound) {
        if (!inbound.received()) {
            // Never handled (a bot's message, an edit): counted, never refused.
            limits.system(system.companyId(), system.id());
            return inbound;
        }
        String sender = inbound.sender();
        boolean message = Inbound.MESSAGE.equals(inbound.kind()) || Inbound.MENTION.equals(inbound.kind());
        if (message && sender != null && !limits.user(system.companyId(), system.id(), sender)) {
            refused(system, "RATE_LIMITED");
            log.info("A chat user of the system {} is over the messages' rate: a message was refused", system.id());
            return limits.tell(system.id(), sender) ? inbound.with("refusal", "RATE_LIMITED")
                    : inbound.refused("RATE_LIMITED");
        }
        if (sender != null && limits.outsider(system, sender, inbound.payload().get("userTeam"))) {
            if (!limits.outsiders(system.companyId(), system.id())) {
                refused(system, "RATE_LIMITED");
                log.info("The guests and other organisations' users of the chat system {} are over their rate: an "
                        + "event was refused", system.id());
                return inbound.refused("RATE_LIMITED");
            }
            return inbound;
        }
        if (!limits.system(system.companyId(), system.id())) {
            refused(system, "RATE_LIMITED");
            log.info("The chat system {} is over its events' rate: an event was refused", system.id());
            return inbound.refused("RATE_LIMITED");
        }
        return inbound;
    }

    /** Counts a refusal per system (a signature, a token, a workspace, a rate). */
    void refused(ChatSystemInfo system, String why) {
        Counter.builder(REFUSED_METRIC)
            .tag("kind", system.kind().name())
            .tag("system", system.id().toString())
            .tag("reason", why)
            .register(meters)
            .increment();
    }
}
