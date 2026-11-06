package com.flowtivo.tinfirm.runner.internal;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.flowtivo.tinfirm.audit.AuditAction;
import com.flowtivo.tinfirm.audit.AuditLog;
import com.flowtivo.tinfirm.audit.AuditRecord;
import com.flowtivo.tinfirm.principal.PrincipalInfo;
import com.flowtivo.tinfirm.principal.Principals;
import com.flowtivo.tinfirm.runner.HostCredentialInfo;
import com.flowtivo.tinfirm.runner.HostScope;
import com.flowtivo.tinfirm.runner.RunnerActivity;
import com.flowtivo.tinfirm.runner.RunnerMessages;
import com.flowtivo.tinfirm.runner.RunnerVersions;
import com.flowtivo.tinfirm.shared.access.CompanyAccess;
import com.flowtivo.tinfirm.shared.access.CompanyAccessGuard;
import com.flowtivo.tinfirm.shared.access.CompanyPermission;
import com.flowtivo.tinfirm.shared.api.model.RunnerHost;
import com.flowtivo.tinfirm.shared.api.model.RunnerHostCreateRequest;
import com.flowtivo.tinfirm.shared.api.model.RunnerHostPage;
import com.flowtivo.tinfirm.shared.api.model.RunnerHostPoolSettings;
import com.flowtivo.tinfirm.shared.api.model.RunnerHostRegistration;
import com.flowtivo.tinfirm.shared.api.model.RunnerHostUpdateRequest;
import com.flowtivo.tinfirm.shared.config.TinfirmProperties;
import com.flowtivo.tinfirm.shared.id.Ids;
import com.flowtivo.tinfirm.shared.licence.Feature;
import com.flowtivo.tinfirm.shared.licence.Licensing;
import com.flowtivo.tinfirm.shared.pagination.Page;
import com.flowtivo.tinfirm.shared.pagination.PageRequest;
import com.flowtivo.tinfirm.shared.security.AuthenticatedUser;
import com.flowtivo.tinfirm.shared.security.CurrentActor;
import com.flowtivo.tinfirm.shared.tenant.TenantScope;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.tx.AfterCommit;
import com.flowtivo.tinfirm.shared.web.ApiException;
import com.flowtivo.tinfirm.shared.web.ProblemCode;

/**
 * Registration, listing, capacity and removal of runner hosts (archived v1 CONVENTIONS §14.8; the package 2 contract,
 * §2 and §8). A host is a {@code COMPANY} host, which OWNER and ADMIN register, see, change and remove
 * ({@code MANAGE_RUNNER_HOSTS}), or a member's {@code PERSONAL} host, which any member registers for themselves
 * ({@code REGISTER_RUNNER_HOSTS}) and which only its owner sees, changes and removes: anyone else, an admin included,
 * gets 404 for it, as for a personal MCP server. The scope is chosen at registration and never changes.
 * <p>
 * A personal host is a machine of its owner's, present in every company they belong to (ADR-0092): registered in one
 * company, it appears in each of the owner's companies under the same name, with the same slots and credentials, and
 * each company's view, change and removal goes through its presence there. Removing it in any company removes the
 * machine everywhere; the owner's departure from a company removes only the presence there
 * ({@link RunnerHostDependents}).
 * <p>
 * A company host that keeps ready sandboxes takes its pool's settings here (ADR-0099), under {@code ee/}
 * ({@link RunnerPools}, ADR-0100).
 */
@Service
class RunnerHostService {

    static final String CONNECT_PATH = "/api/runner/v1/connect";

    static final String TARGET = "runner_host";

    private static final String DELETED_USER = "deleted user";

    private final RunnerHostStore store;

    private final CompanyAccessGuard companyAccess;

    private final Principals principals;

    private final ObjectProvider<RunnerActivity> activity;

    private final RunnerConnectionRegistry connections;

    private final AuditLog auditLog;

    private final RunnerMapper mapper;

    private final TinfirmProperties tinfirm;

    private final TenantScope tenantScope;

    private final TimeSource time;

    /** Tinfirm's schedule of ready sandboxes, under {@code ee/} (ADR-0100). */
    private final ObjectProvider<RunnerPools> pools;

    RunnerHostService(RunnerHostStore store, CompanyAccessGuard companyAccess, Principals principals,
            ObjectProvider<RunnerActivity> activity, RunnerConnectionRegistry connections, AuditLog auditLog,
            RunnerMapper mapper, TinfirmProperties tinfirm, TenantScope tenantScope, TimeSource time,
            ObjectProvider<RunnerPools> pools) {
        this.store = store;
        this.companyAccess = companyAccess;
        this.principals = principals;
        // The run module implements the port and depends on this one: resolved when used.
        this.activity = activity;
        this.connections = connections;
        this.auditLog = auditLog;
        this.mapper = mapper;
        this.tinfirm = tinfirm;
        this.tenantScope = tenantScope;
        this.time = time;
        this.pools = pools;
    }

    /** The company hosts (for OWNER and ADMIN) and the caller's own personal hosts, by name. */
    @Transactional(readOnly = true)
    public RunnerHostPage list(UUID companyId, @Nullable Integer limit, @Nullable String cursor) {
        CompanyAccess access = companyAccess.require(companyId, CompanyPermission.VIEW_RUNNER_HOSTS);
        UUID me = callerPrincipal(companyId).orElse(null);
        boolean companyHosts = access.covers(CompanyPermission.MANAGE_RUNNER_HOSTS);
        PageRequest request = PageRequest.of(limit, cursor);
        List<RunnerHostStore.HostRow> rows = request.firstPage()
                ? store.hosts(companyId, companyHosts, me, null, null, request.fetchSize())
                : store.hosts(companyId, companyHosts, me, request.key(0, 2), request.uuidKey(1, 2),
                        request.fetchSize());
        Page<RunnerHostStore.HostRow> page = Page.of(rows, request, row -> List.of(row.name(), row.id().toString()));
        return new RunnerHostPage(details(companyId, page.items()).stream().map(mapper::toDto).toList())
            .nextCursor(page.nextCursor());
    }

    /**
     * Registers a company host (OWNER and ADMIN) or the caller's own personal host (any member, who must have a
     * principal in the company). Audited as {@code runner_host.registered} with its scope and owner.
     * <p>
     * A personal host is a machine of the caller's (ADR-0092): one row of its own with the token, and a presence in
     * each company the caller is a member of, written in that company's tenant context and audited in its chain, with
     * {@code personalHostId}. Its name is unique among the caller's machines, in every company at once; the answer
     * shows the presence in the company the request named.
     */
    @Transactional
    public RunnerHostRegistration register(UUID companyId, RunnerHostCreateRequest request) {
        HostScope scope = request.getScope() != null ? HostScope.valueOf(request.getScope().getValue())
                : HostScope.PERSONAL;
        CompanyAccess access = companyAccess.require(companyId, CompanyPermission.REGISTER_RUNNER_HOSTS);
        String name = request.getName();
        String token = RunnerTokens.generate();
        if (scope == HostScope.COMPANY) {
            if (!access.covers(CompanyPermission.MANAGE_RUNNER_HOSTS)) {
                throw ApiException.forbidden("Only an owner or admin registers a company runner host");
            }
            if (store.companyHostNameTaken(companyId, name)) {
                throw duplicateName();
            }
            RunnerHostStore.HostRow host = new RunnerHostStore.HostRow(Ids.newId(), companyId, name, scope, null,
                    null, 1, 1, null, false, 0, null, null, null, null, null, null, time.now());
            try {
                store.insertHost(host, RunnerTokens.hash(token));
            }
            catch (DataIntegrityViolationException ex) {
                throw duplicateName();
            }
            auditLog.record(registered(companyId, host, null).build());
            return new RunnerHostRegistration(mapper.toDto(details(companyId, List.of(host)).getFirst()), token,
                    connectUrl());
        }
        callerPrincipal(companyId)
            .orElseThrow(() -> ApiException.forbidden("Only a member of the company registers a personal runner "
                    + "host, which runs their own work"));
        UUID userId = CurrentActor.requireUser().id();
        if (store.machineNameTaken(userId, name)) {
            throw duplicateName();
        }
        Instant now = time.now();
        RunnerHostStore.Machine machine = new RunnerHostStore.Machine(Ids.newId(), userId, name, now);
        try {
            store.insertMachine(machine, RunnerTokens.hash(token));
        }
        catch (DataIntegrityViolationException ex) {
            throw duplicateName();
        }
        UUID ownPresence = null;
        for (RunnerHostStore.Membership membership : store.memberships(userId)) {
            UUID presence = addPresence(membership.companyId(), membership.principalId(), machine, null, now);
            if (membership.companyId().equals(companyId)) {
                ownPresence = presence;
            }
        }
        tenantScope.enterCompany(companyId);
        RunnerHostStore.HostRow host = store.host(companyId, Objects.requireNonNull(ownPresence)).orElseThrow();
        return new RunnerHostRegistration(mapper.toDto(details(companyId, List.of(host)).getFirst()), token,
                connectUrl());
    }

    /**
     * A presence of the machine in the company, in its tenant context, audited {@code runner_host.registered} in its
     * chain; the thread stays in that context. After the commit the machine's connection, if any, serves there too.
     *
     * @param cause why nobody asked for it ({@code principal.joined}), or null at registration
     */
    UUID addPresence(UUID companyId, UUID principalId, RunnerHostStore.Machine machine, @Nullable String cause,
            Instant now) {
        tenantScope.enterCompany(companyId);
        UUID id = Ids.newId();
        store.insertPresence(id, companyId, machine.name(), principalId, machine.id(), now);
        RunnerHostStore.HostRow host = new RunnerHostStore.HostRow(id, companyId, machine.name(), HostScope.PERSONAL,
                principalId, machine.id(), 1, 1, null, false, 0, null, null, null, null, null, null, now);
        auditLog.record(registered(companyId, host, cause).build());
        UUID machineId = machine.id();
        AfterCommit.run(() -> connections.addPresence(machineId, companyId, id));
        return id;
    }

    private static AuditRecord.Builder registered(UUID companyId, RunnerHostStore.HostRow host,
            @Nullable String cause) {
        AuditRecord.Builder audit = AuditRecord
            .company(companyId, AuditAction.RUNNER_HOST_REGISTERED, CurrentActor.actor())
            .target(TARGET, host.id())
            .detail("name", host.name())
            .detail("scope", host.scope().name());
        if (host.ownerPrincipalId() != null) {
            audit.detail("ownerPrincipalId", host.ownerPrincipalId().toString());
        }
        if (host.personalHostId() != null) {
            audit.detail("personalHostId", host.personalHostId().toString());
        }
        if (cause != null) {
            audit.detail("cause", cause);
        }
        return audit;
    }

    /**
     * Sets or clears the host's desired slots (archived v1 CONVENTIONS §14.8) and a company host's pool settings
     * (ADR-0099). A change locks the host row, stores the values, moves {@code config_version} on and is audited, all
     * in this transaction; after the commit a connected host gets the full {@code host.config}. A request that changes
     * nothing does none of this.
     */
    @Transactional
    public RunnerHost updateHost(UUID companyId, UUID hostId, RunnerHostUpdateRequest request) {
        RunnerHostStore.HostRow host = requireManageable(companyId, hostId,
                "Only an owner or admin changes a company runner host");
        Integer desired = request.getDesiredMaxConcurrentRuns();
        boolean clear = Boolean.TRUE.equals(request.getClearDesiredMaxConcurrentRuns());
        if (desired != null && clear) {
            throw ApiException.validation("Set desiredMaxConcurrentRuns or clear it, not both");
        }
        RunnerHostPoolSettings poolRequest = request.getPool();
        boolean clearPool = Boolean.TRUE.equals(request.getClearPool());
        if (poolRequest != null && clearPool) {
            throw ApiException.validation("Set pool or clear it, not both");
        }
        Integer target = clear ? null : desired != null ? desired : host.desiredMaxConcurrentRuns();
        boolean slotsChanged = !Objects.equals(target, host.desiredMaxConcurrentRuns());
        // Checked against the slots as they will be; stored first, in this transaction, when they change.
        RunnerPools.Change pool = poolRequest != null || clearPool
                ? pooling().update(host, poolRequest, target != null ? target : host.maxConcurrentRunsCeiling())
                : null;
        if (!slotsChanged && pool == null) {
            return mapper.toDto(details(companyId, List.of(host)).getFirst());
        }
        // A machine's slots are one across its presences (ADR-0092): the change is audited where it was made.
        long version = 0;
        if (slotsChanged) {
            version = store.setDesiredMaxConcurrentRuns(host.target(), target);
        }
        AuditRecord.Builder audit = AuditRecord
            .company(companyId, AuditAction.RUNNER_HOST_UPDATED, CurrentActor.actor())
            .target(TARGET, hostId)
            .detail("name", host.name());
        if (slotsChanged) {
            audit.detail("oldDesiredMaxConcurrentRuns", host.desiredMaxConcurrentRuns())
                .detail("newDesiredMaxConcurrentRuns", target);
        }
        if (pool != null) {
            audit.detail("changed", "pool").detail("oldPool", pool.oldPool()).detail("newPool", pool.newPool());
        }
        if (host.personalHostId() != null) {
            audit.detail("personalHostId", host.personalHostId().toString());
        }
        auditLog.record(audit.detail("configVersion", store.desiredConfig(host.target())
            .map(RunnerHostStore.DesiredConfig::version)
            .orElse(version)).build());
        pushConfigAfterCommit(host);
        RunnerHostStore.HostRow updated = store.host(companyId, hostId).orElseThrow();
        return mapper.toDto(details(companyId, List.of(updated)).getFirst());
    }

    /**
     * Removes the host (an admin a company host, the owner a personal one); its token stops working at once. A
     * personal host is removed as a machine, from every company it is present in (ADR-0092), so a run of its in any
     * of them keeps it.
     */
    @Transactional
    public void delete(UUID companyId, UUID hostId) {
        RunnerHostStore.HostRow host = requireManageable(companyId, hostId,
                "Only an owner or admin removes a company runner host");
        UUID machineId = host.personalHostId();
        if (machineId == null) {
            // Under the host's lock, which a dispatcher takes before it assigns a run to the host.
            if (activity.getObject().hasActiveRuns(hostId)) {
                throw activeRuns();
            }
            remove(companyId, host, null);
            return;
        }
        // The machine is locked (requireManageable), in the dispatcher's order (CONVENTIONS §10): the machine
        // first, then its presences by id, each in its company's tenant context. Under the machine's lock no
        // dispatcher of any company assigns a run to a presence, and no new presence is added (its foreign key to the
        // machine waits), so the presences read now are all of them, and the check for runs under the locks is final.
        List<RunnerHostStore.Presence> presences = store.presences(machineId)
            .stream()
            .sorted(java.util.Comparator.comparing(RunnerHostStore.Presence::runnerHostId))
            .toList();
        List<RunnerHostStore.HostRow> rows = new ArrayList<>();
        for (RunnerHostStore.Presence presence : presences) {
            tenantScope.enterCompany(presence.companyId());
            store.lockHost(presence.companyId(), presence.runnerHostId()).ifPresent(rows::add);
        }
        tenantScope.enterCompany(companyId);
        if (activity.getObject().hasActiveRunsOnMachine(machineId)) {
            throw activeRuns();
        }
        // Each presence's removal is audited in its own chain.
        for (RunnerHostStore.HostRow row : rows) {
            tenantScope.enterCompany(row.companyId());
            auditLog.record(removed(row.companyId(), row, null).build());
        }
        tenantScope.enterCompany(companyId);
        // Its presences and credentials go with it; the token stops working at once, and the connection is closed.
        store.deleteMachine(machineId);
        AfterCommit.run(() -> connections.disconnect(machineId, RunnerConnectionRegistry.INVALID_TOKEN));
    }

    /**
     * Deletes a company host and audits {@code runner_host.removed} ({@code cause} when nobody asked for it); after
     * the commit its live connection is closed. Runs in the caller's transaction.
     */
    void remove(UUID companyId, RunnerHostStore.HostRow host, @Nullable String cause) {
        store.deleteHost(companyId, host.id());
        auditLog.record(removed(companyId, host, cause).build());
        // The token stops working at once: its hash is gone, and the live connection is closed.
        UUID hostId = host.id();
        AfterCommit.run(() -> connections.disconnect(hostId, RunnerConnectionRegistry.INVALID_TOKEN));
    }

    /**
     * Deletes a machine's presence in the company, audited {@code runner_host.removed} with the cause; the machine,
     * its token and its connection stay, for the owner's other companies. Runs in the caller's transaction.
     */
    void removePresence(UUID companyId, RunnerHostStore.HostRow host, String cause) {
        store.deleteHost(companyId, host.id());
        auditLog.record(removed(companyId, host, cause).build());
        UUID hostId = host.id();
        AfterCommit.run(() -> connections.removePresence(hostId));
    }

    private static AuditRecord.Builder removed(UUID companyId, RunnerHostStore.HostRow host, @Nullable String cause) {
        AuditRecord.Builder audit = AuditRecord
            .company(companyId, AuditAction.RUNNER_HOST_REMOVED, CurrentActor.actor())
            .target(TARGET, host.id())
            .detail("name", host.name())
            .detail("scope", host.scope().name());
        if (host.personalHostId() != null) {
            audit.detail("personalHostId", host.personalHostId().toString());
        }
        if (cause != null) {
            audit.detail("cause", cause);
        }
        return audit;
    }

    /**
     * The host, locked, when the caller may change it: a company host for OWNER and ADMIN (403 for a member), the
     * caller's own personal host. Another member's personal host is 404, as an unknown id is. A personal host's
     * machine is locked first ({@code FOR UPDATE}), as the dispatcher takes the machine before the host (CONVENTIONS
     * §10); a host's machine never changes, so the unlocked read that finds it is safe.
     */
    private RunnerHostStore.HostRow requireManageable(UUID companyId, UUID hostId, String forbidden) {
        CompanyAccess access = companyAccess.require(companyId, CompanyPermission.REGISTER_RUNNER_HOSTS);
        UUID machineId = store.host(companyId, hostId).orElseThrow(RunnerHostService::notFound).personalHostId();
        if (machineId != null) {
            store.lockMachine(machineId).orElseThrow(RunnerHostService::notFound);
        }
        RunnerHostStore.HostRow host = store.lockHost(companyId, hostId).orElseThrow(RunnerHostService::notFound);
        if (host.scope() == HostScope.PERSONAL) {
            if (!host.personalOf(callerPrincipal(companyId).orElse(null))) {
                throw notFound();
            }
            return host;
        }
        if (!access.covers(CompanyPermission.MANAGE_RUNNER_HOSTS)) {
            throw ApiException.forbidden(forbidden);
        }
        return host;
    }

    /** Tinfirm's schedule of ready sandboxes; without {@code ee/} the server answers as Community. */
    private RunnerPools pooling() {
        RunnerPools found = pools.getIfAvailable();
        if (found == null) {
            throw Licensing.editionRequired(Feature.ELASTIC_RUNNERS);
        }
        return found;
    }

    /** The caller's own principal in the company; empty for an instance admin who is not a member. */
    private Optional<UUID> callerPrincipal(UUID companyId) {
        AuthenticatedUser user = CurrentActor.requireUser();
        return principals.ofMember(companyId, user.id()).map(PrincipalInfo::id);
    }

    private List<RunnerViews.HostDetails> details(UUID companyId, List<RunnerHostStore.HostRow> hosts) {
        if (hosts.isEmpty()) {
            return List.of();
        }
        List<UUID> hostIds = hosts.stream().map(RunnerHostStore.HostRow::id).toList();
        Map<UUID, Integer> activeRuns = activity.getObject().activeRunsByHost(hostIds);
        Map<UUID, String> owners = principals.displayNames(companyId, hosts.stream()
            .map(RunnerHostStore.HostRow::ownerPrincipalId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet()));
        Map<UUID, List<HostCredentialInfo>> credentials = store.credentials(companyId, hostIds)
            .stream()
            .collect(Collectors.groupingBy(HostCredentialInfo::runnerHostId));
        RunnerPools pooling = pools.getIfAvailable();
        Map<UUID, RunnerViews.PoolDetails> poolDetails = pooling != null ? pooling.details(companyId, hostIds)
                : Map.of();
        return hosts.stream().map(host -> {
            boolean online = connections.isConnected(host.id());
            UUID owner = host.ownerPrincipalId();
            List<RunnerViews.CredentialDetails> hostCredentials = credentials.getOrDefault(host.id(), List.of())
                .stream()
                .map(credential -> new RunnerViews.CredentialDetails(credential.label(), credential.kind().name(),
                        credential.runtimes().stream().map(Enum::name).sorted().toList(),
                        online ? credential.state().name() : "OFFLINE", credential.cooldownUntil(),
                        credential.reported()))
                .toList();
            return new RunnerViews.HostDetails(host.id(), host.name(), host.scope().name(), owner,
                    owner != null ? owners.getOrDefault(owner, DELETED_USER) : null, online,
                    online && host.draining(), host.lastSeenAt(), host.runnerVersion(),
                    RunnerVersions.supportsGatewayRuns(host.runnerVersion()), host.hostname(), host.os(), host.arch(),
                    host.maxConcurrentRuns(), host.maxConcurrentRunsCeiling(), host.desiredMaxConcurrentRuns(),
                    host.configPending(), activeRuns.getOrDefault(host.id(), 0), hostCredentials, host.createdAt(),
                    host.scope() == HostScope.COMPANY ? poolDetails.get(host.id()) : null);
        }).toList();
    }

    /**
     * After the commit, a connected host gets its full {@code host.config} as this transaction left it. Two changes
     * can overtake each other on the way; the host ignores the older version (docs/archive/v1/CONVENTIONS.md §15.7).
     */
    private void pushConfigAfterCommit(RunnerHostStore.HostRow host) {
        RunnerMessages.HostConfig config = store.desiredConfig(host.target())
            .map(RunnerHostSessions::hostConfig)
            .orElseThrow();
        UUID hostId = host.id();
        AfterCommit.run(() -> connections.send(hostId, config));
    }

    private String connectUrl() {
        URI publicUrl = tinfirm.publicUrl();
        String scheme = "https".equalsIgnoreCase(publicUrl.getScheme()) ? "wss" : "ws";
        return scheme + tinfirm.publicBaseUrl().substring(Objects.requireNonNull(publicUrl.getScheme()).length())
                + CONNECT_PATH;
    }

    private static ApiException notFound() {
        return ApiException.notFound("Runner host not found");
    }

    private static ApiException activeRuns() {
        return ApiException.conflict(ProblemCode.RUNNER_HOST_HAS_ACTIVE_RUNS,
                "Runs are queued for or running on this runner host; cancel them first");
    }

    private static ApiException duplicateName() {
        return ApiException.conflict(ProblemCode.DUPLICATE_RUNNER_HOST_NAME,
                "A runner host with this name already exists");
    }
}
