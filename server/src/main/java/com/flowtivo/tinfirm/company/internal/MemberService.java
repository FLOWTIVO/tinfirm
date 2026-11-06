package com.flowtivo.tinfirm.company.internal;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.flowtivo.tinfirm.audit.AuditAction;
import com.flowtivo.tinfirm.audit.AuditLog;
import com.flowtivo.tinfirm.audit.AuditRecord;
import com.flowtivo.tinfirm.company.GroupEvents;
import com.flowtivo.tinfirm.company.MemberEvents;
import com.flowtivo.tinfirm.company.GroupMembership;
import com.flowtivo.tinfirm.company.MemberPrincipals;
import com.flowtivo.tinfirm.company.MemberStatuses;
import com.flowtivo.tinfirm.identity.BearerCredentials;
import com.flowtivo.tinfirm.identity.IdentityProviderNames;
import com.flowtivo.tinfirm.identity.UserDirectory;
import com.flowtivo.tinfirm.identity.UserSummary;
import com.flowtivo.tinfirm.shared.access.CompanyAccess;
import com.flowtivo.tinfirm.shared.access.CompanyAccessGuard;
import com.flowtivo.tinfirm.shared.access.CompanyPermission;
import com.flowtivo.tinfirm.shared.access.CompanyRole;
import com.flowtivo.tinfirm.shared.access.RoleSource;
import com.flowtivo.tinfirm.shared.api.model.Member;
import com.flowtivo.tinfirm.shared.api.model.MemberAddRequest;
import com.flowtivo.tinfirm.shared.api.model.MemberPage;
import com.flowtivo.tinfirm.shared.api.model.MemberUpdateRequest;
import com.flowtivo.tinfirm.shared.api.model.RoleManager;
import com.flowtivo.tinfirm.shared.licence.Licensing;
import com.flowtivo.tinfirm.shared.pagination.Page;
import com.flowtivo.tinfirm.shared.pagination.PageRequest;
import com.flowtivo.tinfirm.shared.security.CurrentActor;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.web.ApiException;
import com.flowtivo.tinfirm.shared.web.ProblemCode;

/**
 * Memberships. OWNER and ADMIN manage members, but only an OWNER (or an instance admin) may grant, change or revoke
 * the OWNER role, and the last OWNER can never be removed or demoted. A member's principal is created with the
 * membership and becomes {@code LEFT} when it ends ({@link MemberPrincipals}, the package 1 contract §3.1), in the
 * same transaction.
 */
@Service
class MemberService {

    private static final String TARGET = "user";

    private final MembershipRepository memberships;

    private final CompanyQueries queries;

    private final CompanyAccessGuard companyAccess;

    private final UserDirectory users;

    private final MemberPrincipals principals;

    private final AuditLog auditLog;

    private final CompanyMapper mapper;

    private final TimeSource time;

    private final ObjectProvider<MemberStatuses> statuses;

    private final GroupMembership groups;

    private final ApplicationEventPublisher events;

    private final Licensing licensing;

    private final IdentityProviderNames providerNames;

    private final BearerCredentials bearers;

    MemberService(MembershipRepository memberships, CompanyQueries queries, CompanyAccessGuard companyAccess,
            UserDirectory users, MemberPrincipals principals, AuditLog auditLog, CompanyMapper mapper,
            TimeSource time, ObjectProvider<MemberStatuses> statuses, GroupMembership groups,
            ApplicationEventPublisher events, Licensing licensing, IdentityProviderNames providerNames,
            BearerCredentials bearers) {
        this.bearers = bearers;
        this.licensing = licensing;
        this.providerNames = providerNames;
        this.statuses = statuses;
        this.groups = groups;
        this.events = events;
        this.memberships = memberships;
        this.queries = queries;
        this.companyAccess = companyAccess;
        this.users = users;
        this.principals = principals;
        this.auditLog = auditLog;
        this.mapper = mapper;
        this.time = time;
    }

    @Transactional(readOnly = true)
    public MemberPage list(UUID companyId, @Nullable Integer limit, @Nullable String cursor) {
        companyAccess.require(companyId, CompanyPermission.VIEW_MEMBERS);
        PageRequest request = PageRequest.of(limit, cursor);
        List<CompanyQueries.MemberRow> rows = request.firstPage()
                ? queries.members(companyId, null, null, request.fetchSize())
                : queries.members(companyId, request.instantKey(0, 2), request.uuidKey(1, 2), request.fetchSize());
        Page<CompanyQueries.MemberRow> page = Page.of(rows, request,
                row -> List.of(row.createdAt().toString(), row.userId().toString()));
        List<UUID> userIds = page.items().stream().map(CompanyQueries.MemberRow::userId).toList();
        Map<UUID, UserSummary> userById = users.findAllById(userIds);
        Set<UUID> suspended = suspendedUsers(companyId);
        Map<UUID, UUID> principalIds = principalIds(companyId, userIds);
        List<Member> items = page.items()
            .stream()
            .filter(row -> userById.containsKey(row.userId()))
            .map(row -> managedBy(mapper.toDto(row.with(suspended.contains(row.userId()),
                    principalIds.get(row.userId())), userById.get(row.userId())), row.roleSource()))
            .toList();
        return new MemberPage(items).nextCursor(page.nextCursor());
    }

    @Transactional
    public Member add(UUID companyId, MemberAddRequest request) {
        CompanyAccess access = companyAccess.require(companyId, CompanyPermission.MANAGE_MEMBERS);
        CompanyRole role = mapper.toDomain(request.getRole());
        if (role == CompanyRole.OWNER) {
            requireOwnerManagement(access);
        }
        UserSummary user = users.findByEmail(request.getEmail())
            .orElseThrow(() -> ApiException.notFound(ProblemCode.USER_NOT_FOUND, "No user with this email exists"));
        if (memberships.existsByCompanyIdAndUserId(companyId, user.id())) {
            throw duplicateMember();
        }
        licensing.requireSeat(user.id(), role);
        MembershipEntity membership = new MembershipEntity(companyId, user.id(), role, time.now());
        try {
            memberships.saveAndFlush(membership);
        }
        catch (DataIntegrityViolationException ex) {
            throw duplicateMember();
        }
        auditLog.record(AuditRecord.company(companyId, AuditAction.MEMBER_ADDED, CurrentActor.actor())
            .target(TARGET, user.id())
            .detail("userId", user.id().toString())
            .detail("role", role.name())
            .build());
        principals.joined(companyId, user.id());
        return toDto(membership, user);
    }

    @Transactional
    public Member changeRole(UUID companyId, UUID userId, MemberUpdateRequest request) {
        CompanyAccess access = companyAccess.require(companyId, CompanyPermission.MANAGE_MEMBERS);
        queries.lockCompany(companyId);
        MembershipEntity membership = memberships.findByCompanyIdAndUserId(companyId, userId)
            .orElseThrow(MemberService::memberNotFound);
        CompanyRole previous = membership.getRole();
        CompanyRole next = mapper.toDomain(request.getRole());
        if (previous == CompanyRole.OWNER || next == CompanyRole.OWNER) {
            requireOwnerManagement(access);
        }
        if (previous == CompanyRole.OWNER && previous != next) {
            requireNotBreakGlass(access);
        }
        if (previous != next && membership.getRoleSource().managed() && next != CompanyRole.OWNER) {
            // ADR-0088 decision 3: the provider's or SCIM's role, whoever asks; OWNER is never theirs.
            throw roleManaged(membership.getRoleSource());
        }
        if (previous != next) {
            if (previous == CompanyRole.OWNER && isLastOwner(companyId, userId)) {
                throw lastOwner();
            }
            licensing.requireSeat(userId, next);
            // The app's from now on: an OWNER is never a provider's (ADR-0088 decision 4).
            membership.changeRole(next, time.now());
            memberships.flush();
            auditLog.record(AuditRecord.company(companyId, AuditAction.MEMBER_ROLE_CHANGED, CurrentActor.actor())
                .target(TARGET, userId)
                .detail("userId", userId.toString())
                .detail("previousRole", previous.name())
                .detail("role", next.name())
                .build());
            events.publishEvent(new MemberEvents.MemberRoleChanged(companyId, userId, previous, next));
        }
        return toDto(membership, requireUser(userId));
    }

    @Transactional
    public void remove(UUID companyId, UUID userId) {
        CompanyAccess access = companyAccess.require(companyId, CompanyPermission.MANAGE_MEMBERS);
        queries.lockCompany(companyId);
        MembershipEntity membership = memberships.findByCompanyIdAndUserId(companyId, userId)
            .orElseThrow(MemberService::memberNotFound);
        if (membership.getRole() == CompanyRole.OWNER) {
            requireOwnerManagement(access);
            requireNotBreakGlass(access);
            if (isLastOwner(companyId, userId)) {
                throw lastOwner();
            }
        }
        leave(membership);
    }

    /**
     * Ends a membership the caller authorised and checked (the last OWNER), as a member who leaves (package 6, §5.5):
     * the principal leaves first, then the membership goes, audited {@code member.removed}. The company's SCIM removes
     * a member the same way (ADR-0087, decision 7).
     */
    void leave(MembershipEntity membership) {
        leave(membership, null);
    }

    /**
     * As {@link #leave(MembershipEntity)}, for a provider that decides the company's membership and whose claim names
     * the person no more (ADR-0089): {@code member.removed} carries the {@code provider} and {@code roleSource: IDP}.
     */
    void leave(MembershipEntity membership, @Nullable String provider) {
        UUID companyId = membership.getCompanyId();
        UUID userId = membership.getUserId();
        // The membership's cascade takes the member out of every group; each group is announced (package 6, §5.5).
        Set<UUID> groupIds = groups.groupsOf(companyId, userId);
        principals.leaving(companyId, userId);
        memberships.delete(membership);
        memberships.flush();
        AuditRecord.Builder removed = AuditRecord.company(companyId, AuditAction.MEMBER_REMOVED, CurrentActor.actor())
            .target(TARGET, userId)
            .detail("userId", userId.toString())
            .detail("role", membership.getRole().name());
        if (provider != null) {
            removed.detail("roleSource", "IDP").detail("provider", provider);
        }
        auditLog.record(removed.build());
        // The person's AI clients lose the company with them: the tokens that name it, the grants for it (CONVENTIONS
        // §5, the owner's decision Q6 of 2026-10-27).
        bearers.companyLeft(userId, companyId);
        groupIds.stream().sorted().forEach(groupId -> events.publishEvent(
                new GroupEvents.GroupMembersChanged(companyId, groupId, Set.of(), Set.of(userId))));
    }

    /**
     * An instance admin who is not a member appoints an OWNER and nothing more of the OWNER role (the package 6
     * contract, §5.10): demoting or removing one stays the company's own owners'.
     */
    private static void requireNotBreakGlass(CompanyAccess access) {
        if (access.viaInstanceAdmin()) {
            throw ApiException.forbidden("An instance admin who is not a member may appoint an owner, not demote or "
                    + "remove one");
        }
    }

    private Set<UUID> suspendedUsers(UUID companyId) {
        MemberStatuses status = statuses.getIfAvailable();
        return status != null ? status.suspendedUsers(companyId) : Set.of();
    }

    private Map<UUID, UUID> principalIds(UUID companyId, List<UUID> userIds) {
        MemberStatuses status = statuses.getIfAvailable();
        return status != null && !userIds.isEmpty() ? status.principalIds(companyId, userIds) : Map.of();
    }

    /**
     * Only an OWNER may grant, change or revoke OWNER. An ADMIN gets 403; an instance admin passes through the guard
     * again, which audits the use of admin rights.
     */
    private void requireOwnerManagement(CompanyAccess access) {
        if (!access.covers(CompanyPermission.MANAGE_OWNERS)) {
            companyAccess.require(access.companyId(), CompanyPermission.MANAGE_OWNERS);
        }
    }

    /**
     * Whether the owner {@code userId} is the company's last active OWNER: a suspended owner counts for nothing (the
     * package 6 contract, §5.4), so the company keeps an owner who can act.
     */
    private boolean isLastOwner(UUID companyId, UUID userId) {
        Set<UUID> suspended = suspendedUsers(companyId);
        return queries.owners(companyId).stream().noneMatch(owner -> !owner.equals(userId) && !suspended.contains(owner));
    }

    private Member toDto(MembershipEntity membership, UserSummary user) {
        UUID companyId = membership.getCompanyId();
        UUID userId = membership.getUserId();
        return managedBy(mapper.toDto(new CompanyQueries.MemberRow(userId, membership.getRole(),
                membership.getRoleSource(), membership.getCreatedAt(),
                suspendedUsers(companyId).contains(userId), principalIds(companyId, List.of(userId)).get(userId)),
                user), membership.getRoleSource());
    }

    /** Who decides the member's role when the app does not (ADR-0088): a provider by its name, or SCIM. */
    private Member managedBy(Member member, RoleSource source) {
        String provider = source.provider();
        if (provider != null) {
            return member.roleManagedBy(new RoleManager(RoleManager.SourceEnum.IDP, providerNames.nameOf(provider))
                .provider(provider));
        }
        if (source.equals(RoleSource.SCIM)) {
            return member.roleManagedBy(new RoleManager(RoleManager.SourceEnum.SCIM, "SCIM"));
        }
        return member;
    }

    private UserSummary requireUser(UUID userId) {
        return users.findById(userId).orElseThrow(MemberService::memberNotFound);
    }

    private static ApiException memberNotFound() {
        return ApiException.notFound("Member not found");
    }

    private static ApiException duplicateMember() {
        return ApiException.conflict(ProblemCode.DUPLICATE_MEMBER, "This user is already a member");
    }

    private static ApiException roleManaged(RoleSource source) {
        String manager = source.manager();
        return ApiException.conflict(ProblemCode.ROLE_MANAGED,
                "This member's role is managed by " + ("scim".equals(manager) ? "SCIM" : "the identity provider")
                        + ": change it there, or make them an owner",
                Map.of("details", Map.of("provider", manager != null ? manager : source.value())));
    }

    private static ApiException lastOwner() {
        return ApiException.conflict(ProblemCode.LAST_OWNER, "The last owner cannot be removed or demoted");
    }
}
