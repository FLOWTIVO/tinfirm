package com.flowtivo.tinfirm.principal.internal;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.flowtivo.tinfirm.audit.AuditAction;
import com.flowtivo.tinfirm.audit.AuditLog;
import com.flowtivo.tinfirm.audit.AuditRecord;
import com.flowtivo.tinfirm.company.GroupMembership;
import com.flowtivo.tinfirm.principal.PrincipalDependents;
import com.flowtivo.tinfirm.principal.PrincipalEvents;
import com.flowtivo.tinfirm.principal.PrincipalKind;
import com.flowtivo.tinfirm.principal.PrincipalStatus;
import com.flowtivo.tinfirm.principal.PrincipalSwitches;
import com.flowtivo.tinfirm.principal.Principals;
import com.flowtivo.tinfirm.shared.access.CompanyAccess;
import com.flowtivo.tinfirm.shared.access.CompanyAccessGuard;
import com.flowtivo.tinfirm.shared.access.CompanyPermission;
import com.flowtivo.tinfirm.shared.api.model.ServiceIdentity;
import com.flowtivo.tinfirm.shared.api.model.ServiceIdentityCreateRequest;
import com.flowtivo.tinfirm.shared.api.model.ServiceIdentityPage;
import com.flowtivo.tinfirm.shared.api.model.ServiceIdentityUpdateRequest;
import com.flowtivo.tinfirm.shared.api.model.ServiceIdentityUsersRequest;
import com.flowtivo.tinfirm.shared.id.Ids;
import com.flowtivo.tinfirm.shared.pagination.Page;
import com.flowtivo.tinfirm.shared.pagination.PageRequest;
import com.flowtivo.tinfirm.shared.security.Actor;
import com.flowtivo.tinfirm.shared.security.ActorType;
import com.flowtivo.tinfirm.shared.security.CurrentActor;
import com.flowtivo.tinfirm.shared.sql.Constraints;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.web.ApiException;
import com.flowtivo.tinfirm.shared.web.ProblemCode;

/**
 * Service identities (the package 1 contract, §3.1, §3.2 and §6): non-human principals that OWNER and ADMIN create,
 * rename, describe, suspend, resume and delete ({@code MANAGE_SERVICE_IDENTITIES}), and whose users (the groups whose
 * members may pick their accounts) they set. Every member lists them, with whether they may use each
 * ({@code VIEW_SERVICE_IDENTITIES}). A name is 1 to 100 characters, unique in the company ignoring case. Each change
 * is audited by ids and the name; a request that changes nothing is not. Creating, suspending, resuming and a change
 * of users are announced ({@link PrincipalEvents}).
 */
@Service
class ServiceIdentityService {

    static final int MAX_NAME = 100;

    static final int MAX_DESCRIPTION = 500;

    static final int MAX_USER_GROUPS = 100;

    /** The unique index of the service identities' names (V001). */
    private static final String NAME_INDEX = "principal_service_name_uq";

    private final PrincipalStore store;

    private final Principals principals;

    private final CompanyAccessGuard companyAccess;

    private final GroupMembership groups;

    private final List<PrincipalDependents> dependents;

    private final AuditLog auditLog;

    private final ApplicationEventPublisher events;

    private final ServiceIdentityMapper mapper;

    private final TimeSource time;

    private final Suspensions suspensions;

    ServiceIdentityService(PrincipalStore store, Principals principals, CompanyAccessGuard companyAccess,
            GroupMembership groups, List<PrincipalDependents> dependents, AuditLog auditLog,
            ApplicationEventPublisher events, ServiceIdentityMapper mapper, TimeSource time, Suspensions suspensions) {
        this.suspensions = suspensions;
        this.store = store;
        this.principals = principals;
        this.companyAccess = companyAccess;
        this.groups = groups;
        this.dependents = List.copyOf(dependents);
        this.auditLog = auditLog;
        this.events = events;
        this.mapper = mapper;
        this.time = time;
    }

    /** The company's service identities by name, with whether the caller may use each (any member). */
    @Transactional(readOnly = true)
    public ServiceIdentityPage list(UUID companyId, @Nullable Integer limit, @Nullable String cursor) {
        CompanyAccess access = companyAccess.require(companyId, CompanyPermission.VIEW_SERVICE_IDENTITIES);
        PageRequest request = PageRequest.of(limit, cursor);
        List<PrincipalStore.PrincipalRow> rows = request.firstPage()
                ? store.servicePage(companyId, null, null, request.fetchSize())
                : store.servicePage(companyId, request.key(0, 2), request.uuidKey(1, 2), request.fetchSize());
        Page<PrincipalStore.PrincipalRow> page = Page.of(rows, request,
                row -> List.of(Objects.requireNonNull(row.name()), row.id().toString()));
        return new ServiceIdentityPage(views(access, page.items())).nextCursor(page.nextCursor());
    }

    /** One service identity (any member). */
    @Transactional(readOnly = true)
    public ServiceIdentity get(UUID companyId, UUID principalId) {
        CompanyAccess access = companyAccess.require(companyId, CompanyPermission.VIEW_SERVICE_IDENTITIES);
        PrincipalStore.PrincipalRow row = store.findService(companyId, principalId)
            .orElseThrow(ServiceIdentityService::notFound);
        return view(access, row);
    }

    @Transactional
    public ServiceIdentity create(UUID companyId, ServiceIdentityCreateRequest input) {
        CompanyAccess access = companyAccess.require(companyId, CompanyPermission.MANAGE_SERVICE_IDENTITIES);
        String name = requireName(input.getName());
        String description = requireDescription(input.getDescription());
        if (store.serviceNameTaken(companyId, name, null)) {
            throw duplicateName();
        }
        Instant now = time.now();
        Actor actor = CurrentActor.actor();
        PrincipalStore.PrincipalRow row = new PrincipalStore.PrincipalRow(Ids.newId(), companyId,
                PrincipalKind.SERVICE, null, name, description, PrincipalStatus.ACTIVE, now, now,
                actor.type() == ActorType.USER ? actor.id() : null);
        try {
            store.insert(row);
        }
        catch (DataIntegrityViolationException ex) {
            throw duplicate(ex);
        }
        auditLog.record(AuditRecord.company(companyId, AuditAction.PRINCIPAL_CREATED, actor)
            .target(MemberPrincipalsImpl.TARGET, row.id())
            .detail("kind", PrincipalKind.SERVICE.name())
            .detail("name", name)
            .build());
        events.publishEvent(new PrincipalEvents.PrincipalCreated(companyId, row.id(), PrincipalKind.SERVICE));
        return view(access, row);
    }

    /** Renames or describes the service identity; absent fields stay, a blank description clears it. */
    @Transactional
    public ServiceIdentity update(UUID companyId, UUID principalId, ServiceIdentityUpdateRequest input) {
        CompanyAccess access = companyAccess.require(companyId, CompanyPermission.MANAGE_SERVICE_IDENTITIES);
        PrincipalStore.PrincipalRow current = store.lockService(companyId, principalId)
            .orElseThrow(ServiceIdentityService::notFound);
        String name = input.getName() != null ? requireName(input.getName()) : Objects.requireNonNull(current.name());
        String description = input.getDescription() != null ? requireDescription(input.getDescription())
                : current.description();
        List<String> changed = new ArrayList<>();
        if (!name.equals(current.name())) {
            changed.add("name");
        }
        if (!Objects.equals(description, current.description())) {
            changed.add("description");
        }
        if (changed.isEmpty()) {
            return view(access, current);
        }
        if (changed.contains("name") && store.serviceNameTaken(companyId, name, principalId)) {
            throw duplicateName();
        }
        Instant now = time.now();
        try {
            store.updateService(companyId, principalId, name, description, now);
        }
        catch (DataIntegrityViolationException ex) {
            throw duplicate(ex);
        }
        auditLog.record(AuditRecord.company(companyId, AuditAction.PRINCIPAL_CHANGED, CurrentActor.actor())
            .target(MemberPrincipalsImpl.TARGET, principalId)
            .detail("changed", changed)
            .detail("name", name)
            .build());
        return view(access, new PrincipalStore.PrincipalRow(principalId, companyId, PrincipalKind.SERVICE, null, name,
                description, current.status(), current.createdAt(), now, current.createdBy()));
    }

    /**
     * Deletes the service identity after its accounts went ({@link PrincipalDependents#principalDeleting}); who may use
     * it goes with it. Audited as {@code principal.deleted} with its name.
     */
    @Transactional
    public void delete(UUID companyId, UUID principalId) {
        companyAccess.require(companyId, CompanyPermission.MANAGE_SERVICE_IDENTITIES);
        PrincipalStore.PrincipalRow current = store.lockServiceForDeletion(companyId, principalId)
            .orElseThrow(ServiceIdentityService::notFound);
        for (PrincipalDependents dependent : dependents) {
            dependent.principalDeleting(companyId, principalId);
        }
        store.deleteService(companyId, principalId);
        auditLog.record(AuditRecord.company(companyId, AuditAction.PRINCIPAL_DELETED, CurrentActor.actor())
            .target(MemberPrincipalsImpl.TARGET, principalId)
            .detail("name", Objects.requireNonNull(current.name()))
            .build());
    }

    /** Replaces the groups that may use the service identity as a whole. */
    @Transactional
    public ServiceIdentity replaceUsers(UUID companyId, UUID principalId, ServiceIdentityUsersRequest input) {
        CompanyAccess access = companyAccess.require(companyId, CompanyPermission.MANAGE_SERVICE_IDENTITIES);
        PrincipalStore.PrincipalRow current = store.lockService(companyId, principalId)
            .orElseThrow(ServiceIdentityService::notFound);
        Set<UUID> requested = new LinkedHashSet<>(input.getGroupIds());
        if (requested.size() > MAX_USER_GROUPS) {
            throw ApiException.validation("A service identity has at most " + MAX_USER_GROUPS + " groups of users");
        }
        Map<UUID, String> known = groups.groupNames(companyId, requested);
        for (UUID groupId : requested) {
            if (!known.containsKey(groupId)) {
                throw ApiException.validation("The group " + groupId + " is not a group of this company");
            }
        }
        Set<UUID> previous = store.users(companyId, principalId);
        List<UUID> added = requested.stream().filter(id -> !previous.contains(id)).sorted().toList();
        List<UUID> removed = previous.stream().filter(id -> !requested.contains(id)).sorted().toList();
        if (added.isEmpty() && removed.isEmpty()) {
            return view(access, current);
        }
        store.removeUsers(companyId, principalId, removed);
        store.addUsers(companyId, principalId, added);
        usersChanged(companyId, current, requested, added, removed, null);
        return view(access, current);
    }

    /** Suspends the service identity, with an optional reason that stays on its row (the package 6 contract, §5.4). */
    @Transactional
    public ServiceIdentity suspend(UUID companyId, UUID principalId, @Nullable String reason) {
        return changeStatus(companyId, principalId, PrincipalStatus.SUSPENDED,
                reason == null || reason.isBlank() ? null : Suspensions.requireReason(reason));
    }

    @Transactional
    public ServiceIdentity resume(UUID companyId, UUID principalId) {
        return changeStatus(companyId, principalId, PrincipalStatus.ACTIVE, null);
    }

    /**
     * The group is about to be deleted: it may use none of the service identities any longer, each audited and
     * announced as a change of users caused by the group's deletion. Runs in the deleting transaction.
     */
    void groupDeleting(UUID companyId, UUID groupId) {
        for (PrincipalStore.PrincipalRow principal : store.lockServicesUsableBy(companyId, groupId)) {
            store.removeUsers(companyId, principal.id(), List.of(groupId));
            Set<UUID> remaining = store.users(companyId, principal.id());
            usersChanged(companyId, principal, remaining, List.of(), List.of(groupId), "group.deleted");
        }
    }

    // ---- Rules -----------------------------------------------------------------------------------------------------

    private ServiceIdentity changeStatus(UUID companyId, UUID principalId, PrincipalStatus next,
            @Nullable String reason) {
        CompanyAccess access = companyAccess.require(companyId, CompanyPermission.MANAGE_SERVICE_IDENTITIES);
        PrincipalStore.PrincipalRow current = store.lockService(companyId, principalId)
            .orElseThrow(ServiceIdentityService::notFound);
        if (current.status() == next) {
            return view(access, current);
        }
        if (next == PrincipalStatus.SUSPENDED) {
            suspensions.suspend(companyId, principalId, reason, PrincipalSwitches.Cause.ADMIN);
        }
        else {
            suspensions.resume(companyId, principalId);
        }
        return view(access, store.find(companyId, principalId).orElseThrow());
    }

    private void usersChanged(UUID companyId, PrincipalStore.PrincipalRow principal, Collection<UUID> after,
            List<UUID> added, List<UUID> removed, @Nullable String cause) {
        AuditRecord.Builder audit = AuditRecord
            .company(companyId, AuditAction.SERVICE_IDENTITY_USERS_CHANGED, CurrentActor.actor())
            .target(MemberPrincipalsImpl.TARGET, principal.id())
            .detail("name", Objects.requireNonNull(principal.name()))
            .detail("groupIds", ids(after.stream().sorted().toList()))
            .detail("added", ids(added))
            .detail("removed", ids(removed));
        if (cause != null) {
            audit.detail("cause", cause);
        }
        auditLog.record(audit.build());
        events.publishEvent(new PrincipalEvents.ServiceIdentityUsersChanged(companyId, principal.id(), Set.copyOf(added),
                Set.copyOf(removed)));
    }

    private static String requireName(String requested) {
        String name = requested.strip();
        if (name.isEmpty() || name.length() > MAX_NAME) {
            throw ApiException.validation("name must be 1 to " + MAX_NAME + " characters");
        }
        return name;
    }

    private static @Nullable String requireDescription(@Nullable String requested) {
        if (requested == null || requested.isBlank()) {
            return null;
        }
        String description = requested.strip();
        if (description.length() > MAX_DESCRIPTION) {
            throw ApiException.validation("description must be at most " + MAX_DESCRIPTION + " characters");
        }
        return description;
    }

    // ---- Views -----------------------------------------------------------------------------------------------------

    private ServiceIdentity view(CompanyAccess access, PrincipalStore.PrincipalRow row) {
        return views(access, List.of(row)).getFirst();
    }

    /**
     * The service identities as the caller sees them: whether they may use each; the groups that may, for those who
     * manage service identities.
     */
    private List<ServiceIdentity> views(CompanyAccess access, List<PrincipalStore.PrincipalRow> rows) {
        UUID companyId = access.companyId();
        UUID callerId = CurrentActor.requireUser().id();
        Set<UUID> usable = rows.isEmpty() ? Set.of() : principals.usableServiceIdentities(companyId, callerId);
        boolean manager = access.covers(CompanyPermission.MANAGE_SERVICE_IDENTITIES);
        Map<UUID, Set<UUID>> users = manager ? store.users(companyId, rows.stream().map(PrincipalStore.PrincipalRow::id)
            .toList()) : Map.of();
        Map<UUID, String> groupNames = manager
                ? groups.groupNames(companyId, users.values().stream().flatMap(Set::stream).toList()) : Map.of();
        // A suspension's time, and for those who manage service identities its reason (as on the kill switches).
        Map<UUID, PrincipalStore.SuspensionRow> suspensions = rows.stream()
            .anyMatch(row -> row.status() == PrincipalStatus.SUSPENDED)
                    ? store.suspended(companyId)
                        .stream()
                        .collect(Collectors.toMap(PrincipalStore.SuspensionRow::id, Function.identity()))
                    : Map.of();
        return rows.stream().map(row -> {
            List<ServiceIdentityMapper.GroupDetails> userGroups = manager ? users.getOrDefault(row.id(), Set.of())
                .stream()
                .filter(groupNames::containsKey)
                .map(id -> new ServiceIdentityMapper.GroupDetails(id, groupNames.get(id)))
                .sorted(Comparator.comparing(ServiceIdentityMapper.GroupDetails::name, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(ServiceIdentityMapper.GroupDetails::id))
                .toList() : null;
            PrincipalStore.SuspensionRow suspension = row.status() == PrincipalStatus.SUSPENDED
                    ? suspensions.get(row.id()) : null;
            return mapper.toDto(new ServiceIdentityMapper.ServiceIdentityDetails(row.id(),
                    Objects.requireNonNull(row.name()), row.description(), row.status(), usable.contains(row.id()),
                    userGroups, suspension != null ? suspension.since() : null,
                    suspension != null && manager ? suspension.reason() : null, row.createdAt(), row.updatedAt()));
        }).toList();
    }

    private static List<String> ids(List<UUID> ids) {
        return ids.stream().map(UUID::toString).toList();
    }

    private static ApiException notFound() {
        return ApiException.notFound("Service identity not found");
    }

    private static ApiException duplicateName() {
        return ApiException.conflict(ProblemCode.DUPLICATE_SERVICE_IDENTITY_NAME,
                "A service identity with this name already exists");
    }

    /** A unique index refused the row: another service identity took the name at the same moment. */
    private static RuntimeException duplicate(DataIntegrityViolationException ex) {
        return Constraints.violated(ex, NAME_INDEX) ? duplicateName() : ex;
    }
}
