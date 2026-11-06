package com.flowtivo.tinfirm.conversation.internal;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.company.GroupEvents;
import com.flowtivo.tinfirm.company.GroupMembership;
import com.flowtivo.tinfirm.company.MemberEvents;
import com.flowtivo.tinfirm.conversation.ConversationInfo;
import com.flowtivo.tinfirm.principal.PrincipalInfo;
import com.flowtivo.tinfirm.principal.Principals;
import com.flowtivo.tinfirm.shared.access.CompanyRole;

/**
 * Who decides an open decision follows the roles and the groups (ADR-0077, decision 5): when a member becomes a Viewer
 * or stops being one, or a group's members change, the open decisions they take part in go to who decides them now
 * ({@link DecisionStore#conversationsWithOpenFor}), never the whole company's. A request
 * whose only candidates became Viewers goes to the Owners and Admins, and back once a candidate holds a seat again.
 * Inside the transaction of the change, so that it is never seen half applied.
 */
@Component
class DecisionRouting {

    private final DecisionStore decisions;

    private final ConversationStore conversations;

    private final DecisionOps ops;

    private final Principals principals;

    private final GroupMembership groups;

    DecisionRouting(DecisionStore decisions, ConversationStore conversations, DecisionOps ops, Principals principals,
            GroupMembership groups) {
        this.decisions = decisions;
        this.conversations = conversations;
        this.ops = ops;
        this.principals = principals;
        this.groups = groups;
    }

    @EventListener
    void roleChanged(MemberEvents.MemberRoleChanged event) {
        if (event.previous() == CompanyRole.VIEWER || event.role() == CompanyRole.VIEWER) {
            UUID companyId = event.companyId();
            reroute(companyId, decisions.conversationsWithOpenFor(
                    principals.ofMember(companyId, event.userId()).map(PrincipalInfo::id).stream().toList(),
                    groups.groupsOf(companyId, event.userId())));
        }
    }

    @EventListener
    void groupMembersChanged(GroupEvents.GroupMembersChanged event) {
        reroute(event.companyId(), decisions.conversationsWithOpenFor(List.of(), List.of(event.groupId())));
    }

    /** The open decisions of the conversations, as the roles and groups are now; conversations locked first, by id. */
    private void reroute(UUID companyId, List<UUID> ids) {
        if (ids.isEmpty()) {
            return;
        }
        Map<UUID, ConversationInfo> open = ids.stream()
            .map(id -> conversations.lock(companyId, id))
            .flatMap(Optional::stream)
            .map(ConversationStore.ConversationRow::info)
            .collect(Collectors.toMap(ConversationInfo::id, Function.identity()));
        for (DecisionStore.DecisionRow decision : decisions.lockOpenOf(open.keySet())) {
            ops.reroute(decision, ops.approverOf(open.get(decision.conversationId()), decision.kind()));
        }
    }
}
