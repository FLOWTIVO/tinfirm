package com.flowtivo.tinfirm.conversation.internal;

import java.util.Locale;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.flowtivo.tinfirm.agent.AgentInfo;
import com.flowtivo.tinfirm.agent.Agents;
import com.flowtivo.tinfirm.agent.EffectiveSettings;
import com.flowtivo.tinfirm.agent.Settings;
import com.flowtivo.tinfirm.conversation.ConversationInfo;
import com.flowtivo.tinfirm.system.FixedTools;
import com.flowtivo.tinfirm.system.SystemInfo;
import com.flowtivo.tinfirm.system.ToolEffect;

/**
 * What a push of the conversation's branch gets in a repository where the conversation has no change request that is
 * open, nor closed (the package 3 contract, §5.4 and open point 5; CONVENTIONS §15.7): the mode of
 * {@code open_change_request} on the agent for the person decides ({@code EffectiveSettings.modeOf}). {@code ALLOW}
 * opens one after the commit ({@link ChangeRequestOpener}); {@code ASK} raises its approval through part 2
 * ({@link ChangeRequestApprovals}), or, while no part implements that seam, posts a {@code SYSTEM} message that approving
 * it is not available; without the tool, or with it switched off, the branch stays pushed, and a {@code SYSTEM} message
 * says why when the push brought new commits. Called inside a transaction in the conversation's company: at a turn's end
 * ({@link OutputTurnEnd}), and when the watch reads that the conversation's change request was merged without the
 * branch's latest push ({@link ChangeRequestWatch}).
 */
@Component
class ChangeRequestPolicy {

    private static final Logger log = LoggerFactory.getLogger(ChangeRequestPolicy.class);

    private final Settings settings;

    private final ChangeRequestOpener opener;

    private final ObjectProvider<ChangeRequestApprovals> approvals;

    private final ConversationMessages messages;

    private final Agents agents;

    ChangeRequestPolicy(Settings settings, ChangeRequestOpener opener, ObjectProvider<ChangeRequestApprovals> approvals,
            ConversationMessages messages, Agents agents) {
        this.settings = settings;
        this.opener = opener;
        this.approvals = approvals;
        this.messages = messages;
        this.agents = agents;
    }

    /**
     * A push of the conversation's branch to a repository of a GitHub or GitLab system.
     *
     * @param runId the turn that pushed it, whose summary a change request opened for it carries
     * @param fresh whether it brought commits the branch's previous push did not have: only then is a push left
     *              without a change request said
     */
    record Push(UUID runId, UUID repositoryId, String repositoryName, String branch, SystemInfo system,
            boolean fresh) {
    }

    /** The push is due a change request: opens it, asks for it, or says why the branch stays without one. */
    @Transactional(propagation = Propagation.MANDATORY)
    void due(ConversationInfo conversation, UUID agentId, Push push) {
        EffectiveSettings effective = settings.effective(conversation.companyId(), agentId,
                conversation.settingsOwner());
        EffectiveSettings.ToolDecision mode = effective.modeOf(push.system().id(), FixedTools.OPEN_CHANGE_REQUEST,
                ToolEffect.WRITE);
        switch (mode) {
            case ALLOW -> opener.openAfterCommit(conversation.companyId(), conversation.id(), push.runId(),
                    push.repositoryId());
            case ASK -> ask(conversation, push);
            case NOT_ON_AGENT -> skipped(conversation, push, agentName(conversation.companyId(), agentId)
                    + " does not have `" + FixedTools.OPEN_CHANGE_REQUEST + "` on " + push.system().name()
                    + ", so the branch stays pushed without one.");
            case SWITCHED_OFF -> skipped(conversation, push, "`" + FixedTools.OPEN_CHANGE_REQUEST + "` on "
                    + push.system().name() + " is switched off in the settings of " + agentName(
                            conversation.companyId(), agentId) + ", so the branch stays pushed without one.");
        }
    }

    /** The push went to the conversation's change request in the repository, which is closed: says so. */
    @Transactional(propagation = Propagation.MANDATORY)
    void closed(ConversationInfo conversation, Push push, int number) {
        String sign = ChangeRequestOpener.numberSign(push.system().kind());
        skipped(conversation, push, "this conversation's " + ChangeRequestOpener.changeRequestName(push.system()
            .kind()) + " " + sign + number + " there is closed, so the push is on none that is open. Reopen "
                + sign + number + " to have it reviewed.");
    }

    private void skipped(ConversationInfo conversation, Push push, String why) {
        log.debug("Conversation {} pushed {} to {} without a change request: {}", conversation.id(), push.branch(),
                push.repositoryName(), why);
        if (push.fresh()) {
            messages.post(conversation.companyId(), conversation.id(), ConversationMessages.Kind.SYSTEM, null,
                    "Tinfirm did not open a " + ChangeRequestOpener.changeRequestName(push.system().kind())
                            + " for `" + push.branch() + "` in `" + push.repositoryName() + "`: " + why,
                    push.runId(), null);
        }
    }

    private void ask(ConversationInfo conversation, Push push) {
        String name = ChangeRequestOpener.changeRequestName(push.system().kind());
        ChangeRequestApprovals raise = approvals.getIfUnique();
        if (raise == null) {
            messages.post(conversation.companyId(), conversation.id(), ConversationMessages.Kind.SYSTEM, null,
                    "The turn pushed `" + push.branch() + "` to `" + push.repositoryName() + "`. Opening a " + name
                            + " needs your approval, which is not available on this server, so the branch stays "
                            + "pushed without one.", push.runId(), null);
            return;
        }
        raise.raise(conversation.companyId(), conversation.id(), push.runId(), push.repositoryId(), "Open a " + name
                + " of `" + push.branch() + "` in " + push.repositoryName() + " on " + push.system().name());
    }

    private String agentName(UUID companyId, UUID agentId) {
        return agents.find(companyId, agentId).map(AgentInfo::name).orElse("the agent");
    }

    /** Whether two commit names name the same commit: equal, or one the other's abbreviation of 7 or more digits. */
    static boolean sameCommit(String one, String other) {
        String a = one.strip().toLowerCase(Locale.ROOT);
        String b = other.strip().toLowerCase(Locale.ROOT);
        return a.equals(b) || a.length() >= 7 && b.length() >= 7 && (a.startsWith(b) || b.startsWith(a));
    }
}
