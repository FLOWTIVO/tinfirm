package com.flowtivo.tinfirm.account.internal;

import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.flowtivo.tinfirm.account.AccountEvents;
import com.flowtivo.tinfirm.account.GrantInfo;
import com.flowtivo.tinfirm.audit.AuditAction;
import com.flowtivo.tinfirm.audit.AuditLog;
import com.flowtivo.tinfirm.audit.AuditRecord;
import com.flowtivo.tinfirm.company.GroupMembership;
import com.flowtivo.tinfirm.shared.access.CompanyAccessGuard;
import com.flowtivo.tinfirm.shared.access.CompanyPermission;
import com.flowtivo.tinfirm.shared.api.model.AccountGrant;
import com.flowtivo.tinfirm.shared.api.model.AccountGrantCreateRequest;
import com.flowtivo.tinfirm.shared.api.model.AccountGrantPage;
import com.flowtivo.tinfirm.shared.api.model.AccountGrantUpdateRequest;
import com.flowtivo.tinfirm.shared.id.Ids;
import com.flowtivo.tinfirm.shared.pagination.Page;
import com.flowtivo.tinfirm.shared.pagination.PageRequest;
import com.flowtivo.tinfirm.shared.security.Actor;
import com.flowtivo.tinfirm.shared.security.ActorType;
import com.flowtivo.tinfirm.shared.security.CurrentActor;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.web.ApiException;
import com.flowtivo.tinfirm.shared.web.ProblemCode;
import com.flowtivo.tinfirm.system.ChatSystems;
import com.flowtivo.tinfirm.system.SystemInfo;
import com.flowtivo.tinfirm.system.Systems;

/**
 * Grants (the package 1 contract, §3.7, I5; ADR-0072): OWNER and ADMIN lend a system's credential to a group,
 * {@code READ} or {@code WRITE}, change the access and withdraw it ({@code MANAGE_GRANTS}). Only a credential is lent,
 * never a member's own account (I5: {@code GRANT_NOT_SERVICE}, and the database's foreign key on the account's
 * principal kind). A grant goes with its credential and with its group, each audited with the cause. Every change is
 * announced ({@link AccountEvents}).
 */
@Service
class Grants {

    static final String TARGET = "grant";

    private final AccountStore store;

    private final CompanyAccessGuard guard;

    private final GroupMembership groups;

    private final Systems systems;

    private final AuditLog auditLog;

    private final ApplicationEventPublisher events;

    private final AccountMapper mapper;

    private final TimeSource time;

    private final ChatSystems chatSystems;

    Grants(AccountStore store, CompanyAccessGuard guard, GroupMembership groups,
            Systems systems, AuditLog auditLog, ApplicationEventPublisher events, AccountMapper mapper,
            TimeSource time, ChatSystems chatSystems) {
        this.store = store;
        this.guard = guard;
        this.groups = groups;
        this.systems = systems;
        this.auditLog = auditLog;
        this.events = events;
        this.mapper = mapper;
        this.time = time;
        this.chatSystems = chatSystems;
    }

    @Transactional(readOnly = true)
    public AccountGrantPage list(UUID companyId, @Nullable Integer limit, @Nullable String cursor) {
        guard.require(companyId, CompanyPermission.MANAGE_GRANTS);
        PageRequest request = PageRequest.of(limit, cursor);
        List<AccountStore.GrantRow> rows = request.firstPage()
                ? store.grantPage(companyId, null, null, request.fetchSize())
                : store.grantPage(companyId, request.instantKey(0, 2), request.uuidKey(1, 2), request.fetchSize());
        Page<AccountStore.GrantRow> page = Page.of(rows, request,
                row -> List.of(row.createdAt().toString(), row.info().id().toString()));
        return new AccountGrantPage(views(companyId, page.items())).nextCursor(page.nextCursor());
    }

    /**
     * The grants to the group, as {@link #list} shows them, by system name and then credential name, for the
     * group's usage: one query for the grants and one each for the names, whatever the group holds. The caller holds
     * {@code MANAGE_GRANTS} already.
     */
    List<AccountGrant> ofGroup(UUID companyId, UUID groupId) {
        return views(companyId, store.grantsToGroup(companyId, groupId)).stream()
            .sorted(Comparator.comparing(AccountGrant::getSystemName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(AccountGrant::getCredentialName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(AccountGrant::getId))
            .toList();
    }

    @Transactional
    public AccountGrant create(UUID companyId, AccountGrantCreateRequest input) {
        guard.require(companyId, CompanyPermission.MANAGE_GRANTS);
        AccountStore.AccountRow account = store.lock(companyId, input.getCredentialId())
            .orElseThrow(() -> ApiException.notFound("Credential not found"));
        if (!account.credential()) {
            throw ApiException.badRequest(ProblemCode.GRANT_NOT_SERVICE,
                    "Only a system's credential is lent to a group; a member's own account stays theirs");
        }
        if (chatSystems.botOf(companyId, account.id()).isPresent()) {
            // C9 (the package 5 contract, §3.2): a chat system's bot carries messages and nothing else.
            throw ApiException.badRequest(ProblemCode.BOT_ACCOUNT, "A chat system's bot is never lent to a group");
        }
        UUID groupId = input.getGroupId();
        if (!groups.groupNames(companyId, List.of(groupId)).containsKey(groupId)) {
            throw ApiException.validation("The group " + groupId + " is not a group of this company");
        }
        if (store.grantExists(companyId, account.id(), groupId)) {
            throw duplicate();
        }
        GrantInfo grant = new GrantInfo(Ids.newId(), companyId, account.id(), account.systemId(), groupId,
                GrantInfo.Access.valueOf(input.getAccess().name()));
        Instant now = time.now();
        Actor actor = CurrentActor.actor();
        try {
            store.insertGrant(grant, now, actor.type() == ActorType.USER ? actor.id() : null);
        }
        catch (DuplicateKeyException ex) {
            throw duplicate();
        }
        auditLog.record(audit(AuditAction.GRANT_CREATED, grant, null).build());
        events.publishEvent(new AccountEvents.GrantCreated(companyId, grant.id(), grant.accountId(), grant.systemId(),
                groupId, grant.access()));
        return views(companyId, List.of(new AccountStore.GrantRow(grant, now, now))).getFirst();
    }

    @Transactional
    public AccountGrant update(UUID companyId, UUID grantId, AccountGrantUpdateRequest input) {
        guard.require(companyId, CompanyPermission.MANAGE_GRANTS);
        AccountStore.GrantRow current = store.grant(companyId, grantId, true).orElseThrow(Grants::notFound);
        GrantInfo.Access access = GrantInfo.Access.valueOf(input.getAccess().name());
        if (access == current.info().access()) {
            return views(companyId, List.of(current)).getFirst();
        }
        Instant now = time.now();
        store.updateGrant(companyId, grantId, access, now);
        GrantInfo changed = new GrantInfo(grantId, companyId, current.info().accountId(), current.info().systemId(),
                current.info().groupId(), access);
        auditLog.record(audit(AuditAction.GRANT_CHANGED, changed, null)
            .detail("previousAccess", current.info().access().name())
            .build());
        events.publishEvent(new AccountEvents.GrantChanged(companyId, grantId, changed.accountId(), changed.systemId(),
                changed.groupId(), access));
        return views(companyId, List.of(new AccountStore.GrantRow(changed, current.createdAt(), now))).getFirst();
    }

    @Transactional
    public void delete(UUID companyId, UUID grantId) {
        guard.require(companyId, CompanyPermission.MANAGE_GRANTS);
        AccountStore.GrantRow grant = store.grant(companyId, grantId, true).orElseThrow(Grants::notFound);
        remove(grant.info(), null);
    }

    /** The credential goes: every grant of it first, each audited with the cause. */
    void removeAllOf(AccountStore.AccountRow account, String cause) {
        for (AccountStore.GrantRow grant : store.lockGrantsOf(account.companyId(), account.id())) {
            remove(grant.info(), cause);
        }
    }

    /** The group goes: every grant to it first, each audited with the cause {@code group.deleted}. */
    void removeAllTo(UUID companyId, UUID groupId) {
        for (AccountStore.GrantRow grant : store.lockGrantsTo(companyId, groupId)) {
            remove(grant.info(), "group.deleted");
        }
    }

    private void remove(GrantInfo grant, @Nullable String cause) {
        store.deleteGrant(grant.companyId(), grant.id());
        AuditRecord.Builder audit = audit(AuditAction.GRANT_DELETED, grant, cause);
        auditLog.record(audit.build());
        events.publishEvent(new AccountEvents.GrantDeleted(grant.companyId(), grant.id(), grant.accountId(),
                grant.systemId(), grant.groupId()));
    }

    private static AuditRecord.Builder audit(AuditAction action, GrantInfo grant, @Nullable String cause) {
        AuditRecord.Builder audit = AuditRecord.company(grant.companyId(), action, CurrentActor.actor())
            .target(TARGET, grant.id())
            .detail("accountId", grant.accountId().toString())
            .detail("systemId", grant.systemId().toString())
            .detail("groupId", grant.groupId().toString())
            .detail("access", grant.access().name());
        if (cause != null) {
            audit.detail("cause", cause);
        }
        return audit;
    }

    // ---- Views -----------------------------------------------------------------------------------------------------

    private List<AccountGrant> views(UUID companyId, Collection<AccountStore.GrantRow> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<UUID, String> groupNames = groups.groupNames(companyId,
                rows.stream().map(row -> row.info().groupId()).toList());
        Map<UUID, String> credentials = new HashMap<>();
        for (AccountStore.GrantRow row : rows) {
            credentials.computeIfAbsent(row.info().accountId(), id -> store.find(companyId, id)
                .map(AccountStore.AccountRow::name)
                .orElse(""));
        }
        Map<UUID, SystemInfo> systemsById = new HashMap<>(systems.findAll(companyId,
                rows.stream().map(row -> row.info().systemId()).toList()));
        return rows.stream().map(row -> {
            GrantInfo grant = row.info();
            SystemInfo system = systemsById.get(grant.systemId());
            return mapper.toDto(new AccountMapper.GrantDetails(grant.id(), grant.accountId(), grant.systemId(),
                    system != null ? system.name() : "",
                    Objects.requireNonNullElse(credentials.get(grant.accountId()), ""), grant.groupId(),
                    Objects.requireNonNullElse(groupNames.get(grant.groupId()), ""), grant.access(), row.createdAt(),
                    row.updatedAt()));
        }).toList();
    }

    private static ApiException notFound() {
        return ApiException.notFound("Grant not found");
    }

    private static ApiException duplicate() {
        return ApiException.conflict(ProblemCode.DUPLICATE_GRANT, "The credential is lent to this group already");
    }
}
