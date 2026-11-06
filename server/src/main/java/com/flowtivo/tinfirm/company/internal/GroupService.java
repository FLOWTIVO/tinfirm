package com.flowtivo.tinfirm.company.internal;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.flowtivo.tinfirm.audit.AuditAction;
import com.flowtivo.tinfirm.audit.AuditLog;
import com.flowtivo.tinfirm.audit.AuditRecord;
import com.flowtivo.tinfirm.company.GroupDependents;
import com.flowtivo.tinfirm.company.GroupEvents;
import com.flowtivo.tinfirm.company.GroupUses;
import com.flowtivo.tinfirm.identity.UserDirectory;
import com.flowtivo.tinfirm.identity.UserSummary;
import com.flowtivo.tinfirm.shared.access.CompanyAccess;
import com.flowtivo.tinfirm.shared.access.CompanyAccessGuard;
import com.flowtivo.tinfirm.shared.access.CompanyPermission;
import com.flowtivo.tinfirm.shared.api.model.Group;
import com.flowtivo.tinfirm.shared.api.model.GroupInput;
import com.flowtivo.tinfirm.shared.api.model.AccountGrant;
import com.flowtivo.tinfirm.shared.api.model.GroupPage;
import com.flowtivo.tinfirm.shared.api.model.GroupUsage;
import com.flowtivo.tinfirm.shared.api.model.GroupUsageItem;
import com.flowtivo.tinfirm.shared.id.Ids;
import com.flowtivo.tinfirm.shared.pagination.Page;
import com.flowtivo.tinfirm.shared.pagination.PageRequest;
import com.flowtivo.tinfirm.shared.security.CurrentActor;
import com.flowtivo.tinfirm.shared.sql.Constraints;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.web.ApiException;
import com.flowtivo.tinfirm.shared.web.ProblemCode;

/**
 * Groups of members (ADR-0049 §2): a named set of members of the company. OWNER and ADMIN create, rename, fill and
 * delete groups and see their members ({@code MANAGE_GROUPS}); every member lists their names ({@code VIEW_GROUPS}). A
 * group is replaced as a whole: its name (1 to 100 characters, unique in the company ignoring case), its description
 * and its members, each a member of the company and counted once; or one member is added or taken out, which touches
 * no other member, so that two admins who change the same group's members at the same moment both keep their change.
 * Leaving the company leaves every group (the membership's foreign key cascades). A change of members is audited as
 * {@code group.updated} and announced ({@link GroupEvents.GroupMembersChanged}) the same way whichever request made it;
 * a deletion first lets the modules that give the group rights remove them ({@link GroupDependents}), and what its
 * members may use through it is told by the same modules ({@link GroupUses}).
 */
@Service
class GroupService {

    static final int MAX_NAME = 100;

    static final int MAX_MEMBERS = 500;

    private static final String TARGET = "group";

    private static final String DELETED_USER = "deleted user";

    /** The unique index of the groups' names (V001). */
    private static final String NAME_INDEX = "user_group_company_name_uq";

    /** A group member's foreign key to the company's memberships (V001). */
    private static final String MEMBERSHIP_FK = "user_group_member_membership_fk";

    private static final List<String> MEMBERS_CHANGED = List.of("members");

    private final GroupStore store;

    private final CompanyAccessGuard companyAccess;

    private final UserDirectory users;

    private final AuditLog auditLog;

    private final GroupMapper mapper;

    private final TimeSource time;

    private final List<GroupDependents> dependents;

    private final List<GroupUses> uses;

    private final ApplicationEventPublisher events;

    GroupService(GroupStore store, CompanyAccessGuard companyAccess, UserDirectory users, AuditLog auditLog,
            GroupMapper mapper, TimeSource time, List<GroupDependents> dependents, List<GroupUses> uses,
            ApplicationEventPublisher events) {
        this.store = store;
        this.companyAccess = companyAccess;
        this.users = users;
        this.auditLog = auditLog;
        this.mapper = mapper;
        this.time = time;
        this.dependents = List.copyOf(dependents);
        this.uses = List.copyOf(uses);
        this.events = events;
    }

    /** The company's groups by name, with their member counts (any member). */
    @Transactional(readOnly = true)
    public GroupPage list(UUID companyId, @Nullable Integer limit, @Nullable String cursor) {
        companyAccess.require(companyId, CompanyPermission.VIEW_GROUPS);
        PageRequest request = PageRequest.of(limit, cursor);
        List<GroupStore.GroupCount> rows = request.firstPage()
                ? store.page(companyId, null, null, request.fetchSize())
                : store.page(companyId, request.key(0, 2), request.uuidKey(1, 2), request.fetchSize());
        Page<GroupStore.GroupCount> page = Page.of(rows, request,
                row -> List.of(row.group().name(), row.group().id().toString()));
        return new GroupPage(page.items()
            .stream()
            .map(row -> mapper.toDto(new GroupMapper.SummaryDetails(row.group().id(), row.group().name(),
                    row.group().description(), row.memberCount())))
            .toList()).nextCursor(page.nextCursor());
    }

    /** One group with its members (OWNER, ADMIN). */
    @Transactional(readOnly = true)
    public Group get(UUID companyId, UUID groupId) {
        companyAccess.require(companyId, CompanyPermission.MANAGE_GROUPS);
        GroupStore.GroupRow group = store.find(companyId, groupId).orElseThrow(GroupService::notFound);
        return toDto(group, store.members(groupId));
    }

    /**
     * What the group's members may use through it (OWNER, ADMIN): each module that gives groups something answers its
     * part ({@link GroupUses}), as the caller sees it in that module's own list.
     */
    @Transactional(readOnly = true)
    public GroupUsage usage(UUID companyId, UUID groupId) {
        CompanyAccess access = companyAccess.require(companyId, CompanyPermission.MANAGE_GROUPS);
        store.find(companyId, groupId).orElseThrow(GroupService::notFound);
        List<GroupUsageItem> agents = new ArrayList<>();
        List<AccountGrant> grants = new ArrayList<>();
        List<GroupUsageItem> serviceIdentities = new ArrayList<>();
        for (GroupUses module : uses) {
            GroupUses.Uses part = module.of(access, groupId);
            agents.addAll(part.agents());
            grants.addAll(part.grants());
            serviceIdentities.addAll(part.serviceIdentities());
        }
        return new GroupUsage(agents, grants, serviceIdentities);
    }

    @Transactional
    public Group create(UUID companyId, GroupInput input) {
        companyAccess.require(companyId, CompanyPermission.MANAGE_GROUPS);
        String name = requireName(input.getName());
        String description = blankToNull(input.getDescription());
        List<UUID> members = requireMembers(companyId, input.getMemberIds());
        if (store.nameTaken(companyId, name, null)) {
            throw duplicateName();
        }
        Instant now = time.now();
        GroupStore.GroupRow group = new GroupStore.GroupRow(Ids.newId(), companyId, name, description, now, now);
        try {
            store.insert(group);
        }
        catch (DataIntegrityViolationException ex) {
            throw duplicate(ex);
        }
        store.replaceMembers(companyId, group.id(), members);
        auditLog.record(AuditRecord.company(companyId, AuditAction.GROUP_CREATED, CurrentActor.actor())
            .target(TARGET, group.id())
            .detail("name", name)
            .detail("memberIds", ids(members.stream().sorted().toList()))
            .build());
        return toDto(group, members);
    }

    /** Replaces the group's name, description and members as a whole; a request that changes nothing is not audited. */
    @Transactional
    public Group replace(UUID companyId, UUID groupId, GroupInput input) {
        companyAccess.require(companyId, CompanyPermission.MANAGE_GROUPS);
        GroupStore.GroupRow current = store.lock(companyId, groupId).orElseThrow(GroupService::notFound);
        String name = requireName(input.getName());
        String description = blankToNull(input.getDescription());
        List<UUID> members = requireMembers(companyId, input.getMemberIds());
        List<UUID> previous = store.members(groupId);
        List<UUID> added = members.stream().filter(id -> !previous.contains(id)).sorted().toList();
        List<UUID> removed = previous.stream().filter(id -> !members.contains(id)).sorted().toList();
        List<String> changed = new ArrayList<>();
        if (!name.equals(current.name())) {
            changed.add("name");
        }
        if (!Objects.equals(description, current.description())) {
            changed.add("description");
        }
        if (!added.isEmpty() || !removed.isEmpty()) {
            changed.add("members");
        }
        if (changed.isEmpty()) {
            return toDto(current, previous);
        }
        if (changed.contains("name") && store.nameTaken(companyId, name, groupId)) {
            throw duplicateName();
        }
        GroupStore.GroupRow updated = new GroupStore.GroupRow(groupId, companyId, name, description,
                current.createdAt(), time.now());
        try {
            store.update(updated);
        }
        catch (DataIntegrityViolationException ex) {
            throw duplicate(ex);
        }
        if (changed.contains("members")) {
            store.replaceMembers(companyId, groupId, members);
        }
        recordUpdate(updated, changed, added, removed);
        return toDto(updated, members);
    }

    /**
     * Adds one member, as a replace that adds them does (the same rules, audit and announcement), without writing the
     * other members: a change another admin makes to the group at the same moment stays. A member already in the
     * group changes nothing and is not audited. Answers the group with every member.
     */
    @Transactional
    public Group addMember(UUID companyId, UUID groupId, UUID userId) {
        companyAccess.require(companyId, CompanyPermission.MANAGE_GROUPS);
        GroupStore.GroupRow current = store.lock(companyId, groupId).orElseThrow(GroupService::notFound);
        List<UUID> members = store.members(groupId);
        if (members.contains(userId)) {
            return toDto(current, members);
        }
        requireMembers(companyId, List.of(userId));
        if (members.size() >= MAX_MEMBERS) {
            throw tooManyMembers();
        }
        boolean added;
        try {
            added = store.addMember(companyId, groupId, userId);
        }
        catch (DataIntegrityViolationException ex) {
            // The user left the company after the check, before the insert.
            throw Constraints.violated(ex, MEMBERSHIP_FK) ? notAMember(userId) : ex;
        }
        if (!added) {
            return toDto(current, store.members(groupId));
        }
        GroupStore.GroupRow updated = touched(current);
        recordUpdate(updated, MEMBERS_CHANGED, List.of(userId), List.of());
        return toDto(updated, store.members(groupId));
    }

    /**
     * Takes one member out, as a replace that leaves them out does, without writing the other members. A user who is
     * not in the group, one who left the company included, changes nothing and is not audited. Answers the group with
     * every member.
     */
    @Transactional
    public Group removeMember(UUID companyId, UUID groupId, UUID userId) {
        companyAccess.require(companyId, CompanyPermission.MANAGE_GROUPS);
        GroupStore.GroupRow current = store.lock(companyId, groupId).orElseThrow(GroupService::notFound);
        if (!store.removeMember(companyId, groupId, userId)) {
            return toDto(current, store.members(groupId));
        }
        GroupStore.GroupRow updated = touched(current);
        recordUpdate(updated, MEMBERS_CHANGED, List.of(), List.of(userId));
        return toDto(updated, store.members(groupId));
    }

    /**
     * Deletes the group with its members, after the modules that give it rights removed them; audited as
     * {@code group.deleted} with its name.
     */
    @Transactional
    public void delete(UUID companyId, UUID groupId) {
        companyAccess.require(companyId, CompanyPermission.MANAGE_GROUPS);
        GroupStore.GroupRow group = store.lock(companyId, groupId).orElseThrow(GroupService::notFound);
        for (GroupDependents dependent : dependents) {
            dependent.groupDeleting(companyId, groupId);
        }
        store.delete(companyId, groupId);
        auditLog.record(AuditRecord.company(companyId, AuditAction.GROUP_DELETED, CurrentActor.actor())
            .target(TARGET, groupId)
            .detail("name", group.name())
            .build());
    }

    /** The group, changed now: its {@code updated_at} moves, its name and description stay. */
    private GroupStore.GroupRow touched(GroupStore.GroupRow group) {
        GroupStore.GroupRow updated = new GroupStore.GroupRow(group.id(), group.companyId(), group.name(),
                group.description(), group.createdAt(), time.now());
        store.update(updated);
        return updated;
    }

    /**
     * What every change of a group records, a replace's and one member's alike: {@code group.updated} with what
     * changed and the members added and removed by id, and, when its members changed, {@link
     * GroupEvents.GroupMembersChanged}, so that teams, grants and bindings are looked at again.
     */
    private void recordUpdate(GroupStore.GroupRow group, List<String> changed, List<UUID> added,
            List<UUID> removed) {
        auditLog.record(AuditRecord.company(group.companyId(), AuditAction.GROUP_UPDATED, CurrentActor.actor())
            .target(TARGET, group.id())
            .detail("changed", changed)
            .detail("name", group.name())
            .detail("added", ids(added))
            .detail("removed", ids(removed))
            .build());
        if (!added.isEmpty() || !removed.isEmpty()) {
            events.publishEvent(new GroupEvents.GroupMembersChanged(group.companyId(), group.id(), Set.copyOf(added),
                    Set.copyOf(removed)));
        }
    }

    // ---- Rules -----------------------------------------------------------------------------------------------------

    private static String requireName(String requested) {
        String name = requested.strip();
        if (name.isEmpty() || name.length() > MAX_NAME) {
            throw ApiException.validation("name must be 1 to " + MAX_NAME + " characters");
        }
        return name;
    }

    /** The members of a request, each once, in the order named; each must be a member of the company. */
    private List<UUID> requireMembers(UUID companyId, List<UUID> requested) {
        Set<UUID> members = new LinkedHashSet<>(requested);
        if (members.size() > MAX_MEMBERS) {
            throw tooManyMembers();
        }
        Set<UUID> known = store.membersOfCompany(companyId, members);
        for (UUID userId : members) {
            if (!known.contains(userId)) {
                throw notAMember(userId);
            }
        }
        return List.copyOf(members);
    }

    private static ApiException tooManyMembers() {
        return ApiException.validation("A group has at most " + MAX_MEMBERS + " members");
    }

    private static ApiException notAMember(UUID userId) {
        return ApiException.validation("The user " + userId + " is not a member of this company");
    }

    // ---- Views -----------------------------------------------------------------------------------------------------

    private Group toDto(GroupStore.GroupRow group, List<UUID> memberIds) {
        Map<UUID, UserSummary> byId = memberIds.isEmpty() ? Map.of() : users.findAllById(memberIds);
        List<GroupMapper.MemberDetails> members = memberIds.stream()
            .map(id -> {
                UserSummary user = byId.get(id);
                return user != null ? new GroupMapper.MemberDetails(id, user.email(), user.displayName())
                        : new GroupMapper.MemberDetails(id, "", DELETED_USER);
            })
            .sorted(Comparator.comparing(GroupMapper.MemberDetails::displayName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(GroupMapper.MemberDetails::userId))
            .toList();
        return mapper.toDto(new GroupMapper.GroupDetails(group.id(), group.name(), group.description(), members,
                group.createdAt(), group.updatedAt()));
    }

    private static List<String> ids(List<UUID> ids) {
        return ids.stream().map(UUID::toString).toList();
    }

    private static @Nullable String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static ApiException notFound() {
        return ApiException.notFound("Group not found");
    }

    private static ApiException duplicateName() {
        return ApiException.conflict(ProblemCode.DUPLICATE_GROUP_NAME, "A group with this name already exists");
    }

    /** A unique index refused the row: another group took the name at the same moment. */
    private static RuntimeException duplicate(DataIntegrityViolationException ex) {
        return Constraints.violated(ex, NAME_INDEX) ? duplicateName() : ex;
    }
}
