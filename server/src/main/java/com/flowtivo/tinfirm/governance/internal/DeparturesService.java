package com.flowtivo.tinfirm.governance.internal;

import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.flowtivo.tinfirm.account.AccountInfo;
import com.flowtivo.tinfirm.account.Accounts;
import com.flowtivo.tinfirm.agent.Agents;
import com.flowtivo.tinfirm.company.GroupMembership;
import com.flowtivo.tinfirm.conversation.InboxItems;
import com.flowtivo.tinfirm.conversation.PersonConversations;
import com.flowtivo.tinfirm.governance.TriggerSwitchReads;
import com.flowtivo.tinfirm.principal.PrincipalInfo;
import com.flowtivo.tinfirm.principal.PrincipalSwitches;
import com.flowtivo.tinfirm.principal.Principals;
import com.flowtivo.tinfirm.runner.RunnerDirectory;
import com.flowtivo.tinfirm.shared.access.CompanyAccessGuard;
import com.flowtivo.tinfirm.shared.access.CompanyPermission;
import com.flowtivo.tinfirm.shared.api.model.DepartureAgent;
import com.flowtivo.tinfirm.shared.api.model.DepartureChangeRequest;
import com.flowtivo.tinfirm.shared.api.model.DepartureCredential;
import com.flowtivo.tinfirm.shared.api.model.DeparturePreview;
import com.flowtivo.tinfirm.shared.api.model.DepartureSummary;
import com.flowtivo.tinfirm.shared.api.model.DepartureSummaryList;
import com.flowtivo.tinfirm.shared.api.model.DepartureTrigger;
import com.flowtivo.tinfirm.shared.api.model.GroupRef;
import com.flowtivo.tinfirm.shared.api.model.InboxDeparture;
import com.flowtivo.tinfirm.shared.api.model.InboxItem;
import com.flowtivo.tinfirm.shared.api.model.InboxItemKind;
import com.flowtivo.tinfirm.shared.web.ApiException;
import com.flowtivo.tinfirm.shared.web.ProblemCode;
import com.flowtivo.tinfirm.system.SystemInfo;
import com.flowtivo.tinfirm.system.Systems;

/**
 * A member who leaves (the package 6 contract, §5.5; open point 21): the preview of what removing them does, the
 * admins' checklist of what was theirs, computed from the state through each module's public API, its closing, and
 * the admins' {@code DEPARTURE} inbox item until it is closed. Items 1 to 3 (the triggers paused {@code AUTHOR_LEFT},
 * the groups left without a member, the system credentials they connected) keep it open; closing it while
 * one is open needs {@code force}.
 */
@Service
class DeparturesService implements InboxItems {

    /** The most open departures {@code GET /departures} lists. */
    static final int MAX_OPEN = 500;

    private final CompanyAccessGuard companyAccess;

    private final PrincipalSwitches principalSwitches;

    private final Principals principals;

    private final GroupMembership groups;

    private final Accounts accounts;

    private final Agents agents;

    private final PersonConversations conversations;

    private final RunnerDirectory runners;

    private final Systems systems;

    private final ObjectProvider<TriggerSwitchReads> triggers;

    DeparturesService(CompanyAccessGuard companyAccess, PrincipalSwitches principalSwitches, Principals principals,
            GroupMembership groups, Accounts accounts, Agents agents, PersonConversations conversations,
            RunnerDirectory runners, Systems systems, ObjectProvider<TriggerSwitchReads> triggers) {
        this.companyAccess = companyAccess;
        this.principalSwitches = principalSwitches;
        this.principals = principals;
        this.groups = groups;
        this.accounts = accounts;
        this.agents = agents;
        this.conversations = conversations;
        this.runners = runners;
        this.systems = systems;
        this.triggers = triggers;
    }

    /** {@code GET /members/{userId}/departure}: what removing the member will do. */
    @Transactional(readOnly = true)
    public DeparturePreview preview(UUID companyId, UUID userId) {
        companyAccess.require(companyId, CompanyPermission.MANAGE_MEMBERS);
        PrincipalInfo member = principals.ofMember(companyId, userId)
            .orElseThrow(() -> ApiException.notFound("Member not found"));
        TriggerSwitchReads reads = triggers.getIfAvailable();
        int hosts = (int) runners.hosts(companyId).stream().filter(host -> host.personalOf(member.id())).count();
        return new DeparturePreview(userId, member.id(), reads != null ? reads.authoredBy(companyId, member.id()) : 0,
                conversations.openOf(companyId, member.id()), agents.ownedBy(companyId, member.id()), hosts,
                accounts.ofPrincipal(companyId, member.id()).size(), groups.groupsOf(companyId, userId).size());
    }

    /** {@code GET /departures}: the members who left and whose checklist is not closed, newest first. */
    @Transactional(readOnly = true)
    public DepartureSummaryList open(UUID companyId) {
        companyAccess.require(companyId, CompanyPermission.MANAGE_MEMBERS);
        List<PrincipalSwitches.Departure> open = principalSwitches.openDepartures(companyId, MAX_OPEN);
        Map<UUID, String> names = principals.displayNames(companyId,
                open.stream().map(PrincipalSwitches.Departure::principalId).toList());
        return new DepartureSummaryList(open.stream()
            .map(departure -> new DepartureSummary(departure.principalId(), departure.userId(),
                    names.getOrDefault(departure.principalId(), "deleted user"),
                    departure.leftAt().atOffset(ZoneOffset.UTC)))
            .toList());
    }

    /** {@code GET /principals/{principalId}/departure}: the checklist. */
    @Transactional(readOnly = true)
    public com.flowtivo.tinfirm.shared.api.model.Departure checklist(UUID companyId, UUID principalId) {
        companyAccess.require(companyId, CompanyPermission.MANAGE_MEMBERS);
        return view(companyId, items(companyId, departure(companyId, principalId)));
    }

    /** {@code POST /principals/{principalId}/departure/close}. */
    @Transactional
    public com.flowtivo.tinfirm.shared.api.model.Departure close(UUID companyId, UUID principalId, boolean force) {
        companyAccess.require(companyId, CompanyPermission.MANAGE_MEMBERS);
        Items items = items(companyId, departure(companyId, principalId));
        if (items.departure().closed()) {
            return view(companyId, items);
        }
        Map<String, Integer> open = items.openCounts();
        if (!force && open.values().stream().anyMatch(count -> count > 0)) {
            throw ApiException.conflict(ProblemCode.DEPARTURE_OPEN, "The checklist has open items: its paused "
                    + "triggers, the groups left without a member and the accounts they connected need someone, or "
                    + "close it with force", Map.<String, Object>of("openItems", open));
        }
        PrincipalSwitches.Departure closed = principalSwitches.closeDeparture(companyId, principalId, force, open);
        return view(companyId, new Items(closed, items.triggers(), items.emptyGroups(), items.accounts()));
    }

    @Override
    @Transactional(readOnly = true)
    public List<Item> itemsFor(Caller caller, int limit) {
        if (!caller.admin()) {
            return List.of();
        }
        List<PrincipalSwitches.Departure> open = principalSwitches.openDepartures(caller.companyId(), limit);
        Map<UUID, String> names = principals.displayNames(caller.companyId(),
                open.stream().map(PrincipalSwitches.Departure::principalId).toList());
        List<Item> items = new ArrayList<>();
        for (PrincipalSwitches.Departure departure : open) {
            // The admins act on it (its checklist), so it is not dismissed: it leaves once the checklist is closed.
            items.add(new Item(departure.leftAt(), new InboxItem(InboxItemKind.DEPARTURE,
                    departure.leftAt().atOffset(ZoneOffset.UTC)).departure(new InboxDeparture(
                            departure.principalId(), departure.userId(),
                            names.getOrDefault(departure.principalId(), "deleted user")))
                .noticeKey(InboxItems.key(InboxItemKind.DEPARTURE, departure.principalId(), departure.leftAt()))
                .dismissible(false)));
        }
        return items;
    }

    // ---- The checklist ---------------------------------------------------------------------------------------------

    private PrincipalSwitches.Departure departure(UUID companyId, UUID principalId) {
        return principalSwitches.departure(companyId, principalId)
            .orElseThrow(() -> ApiException.notFound("No member who left with this id"));
    }

    /** Items 1 to 3, which keep the checklist open. */
    private record Items(PrincipalSwitches.Departure departure, List<TriggerSwitchReads.PausedTrigger> triggers,
            Map<UUID, String> emptyGroups, List<AccountInfo> accounts) {

        Map<String, Integer> openCounts() {
            Map<String, Integer> counts = new LinkedHashMap<>();
            counts.put("triggers", triggers.size());
            counts.put("emptyGroups", emptyGroups.size());
            counts.put("connectedAccounts", accounts.size());
            return counts;
        }
    }

    private Items items(UUID companyId, PrincipalSwitches.Departure departure) {
        TriggerSwitchReads reads = triggers.getIfAvailable();
        List<TriggerSwitchReads.PausedTrigger> paused = reads != null
                ? reads.pausedAuthorLeft(companyId, departure.principalId()) : List.of();
        Map<UUID, String> empty = new LinkedHashMap<>();
        Map<UUID, String> names = groups.groupNames(companyId, departure.groupIds());
        for (Map.Entry<UUID, String> group : names.entrySet()) {
            if (groupEmpty(companyId, group.getKey())) {
                empty.put(group.getKey(), group.getValue());
            }
        }
        return new Items(departure, paused, empty,
                accounts.credentialsConnectedBy(companyId, departure.userId()));
    }

    private boolean groupEmpty(UUID companyId, UUID groupId) {
        return groups.membersOf(companyId, groupId).isEmpty();
    }

    private com.flowtivo.tinfirm.shared.api.model.Departure view(UUID companyId, Items items) {
        PrincipalSwitches.Departure departure = items.departure();
        String name = principals.displayNames(companyId, List.of(departure.principalId()))
            .getOrDefault(departure.principalId(), "deleted user");
        Map<UUID, SystemInfo> systemsById = systems.findAll(companyId,
                items.accounts().stream().map(AccountInfo::systemId).toList());
        List<DepartureCredential> connected = items.accounts()
            .stream()
            .map(account -> new DepartureCredential(account.id(), account.systemId(),
                    systemsById.containsKey(account.systemId()) ? systemsById.get(account.systemId()).name() : "",
                    java.util.Objects.requireNonNullElse(account.name(), ""),
                    DepartureCredential.KindEnum.fromValue(account.kind().name())))
            .toList();
        boolean open = items.openCounts().values().stream().anyMatch(count -> count > 0);
        return new com.flowtivo.tinfirm.shared.api.model.Departure(departure.principalId(), departure.userId(), name,
                open,
                items.triggers().stream().map(trigger -> new DepartureTrigger(trigger.id(), trigger.name())
                    .waitingConversations(trigger.waitingConversations())).toList(),
                refs(items.emptyGroups()), connected,
                agents.passedFrom(companyId, departure.principalId())
                    .entrySet()
                    .stream()
                    .map(agent -> new DepartureAgent(agent.getKey(), agent.getValue()))
                    .toList(),
                conversations.openChangeRequestsOf(companyId, departure.principalId())
                    .stream()
                    .map(request -> new DepartureChangeRequest(request.conversationId(), request.url(),
                            DepartureChangeRequest.StateEnum.fromValue(request.state())))
                    .toList(),
                conversations.endedOf(companyId, departure.principalId()))
            .leftAt(departure.leftAt().atOffset(ZoneOffset.UTC))
            .closedAt(departure.closedAt() != null ? departure.closedAt().atOffset(ZoneOffset.UTC) : null)
            .closedByUserId(departure.closedByUserId());
    }

    private static List<GroupRef> refs(Map<UUID, String> groups) {
        return groups.entrySet().stream().map(group -> new GroupRef(group.getKey(), group.getValue())).toList();
    }
}
