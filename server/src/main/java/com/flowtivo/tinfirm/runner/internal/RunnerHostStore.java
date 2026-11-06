package com.flowtivo.tinfirm.runner.internal;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.flowtivo.tinfirm.runner.CredentialKind;
import com.flowtivo.tinfirm.runner.CredentialState;
import com.flowtivo.tinfirm.runner.HostCredentialInfo;
import com.flowtivo.tinfirm.runner.HostScope;
import com.flowtivo.tinfirm.runner.RunnerHostInfo;
import com.flowtivo.tinfirm.runner.RunnerMessages;
import com.flowtivo.tinfirm.runner.RuntimeKind;
import com.flowtivo.tinfirm.shared.id.Ids;

import tools.jackson.databind.json.JsonMapper;

/**
 * SQL access to {@code runner_host}, {@code personal_runner_host} and {@code personal_runner_credential} (ADR-0092).
 * A company host is one row of {@code runner_host}. A member's personal host is a machine ({@code personal_runner_host},
 * an instance-level table of its owner's, without row-level security) with a presence in each company the owner is in
 * (a {@code runner_host} row of that company, which names the machine): every read of a host joins the machine's row,
 * so a presence shows the machine's slots, runtimes, version and facts, and every write of those goes to the machine.
 * Row-level security limits each statement on {@code runner_host} to the company of the tenant context, except the
 * token lookup and the lookups across companies, which call the narrow {@code SECURITY DEFINER} functions.
 */
@Repository
class RunnerHostStore {

    /** A host as the company sees it: the presence's identity, the machine's facts. */
    private static final String HOST_COLUMNS = """
            h.id, h.company_id, h.name, h.scope, h.owner_principal_id, h.personal_host_id,
            coalesce(m.max_concurrent_runs, h.max_concurrent_runs) AS max_concurrent_runs,
            coalesce(m.max_concurrent_runs_ceiling, h.max_concurrent_runs_ceiling) AS max_concurrent_runs_ceiling,
            coalesce(m.desired_max_concurrent_runs, h.desired_max_concurrent_runs) AS desired_max_concurrent_runs,
            coalesce(m.draining, h.draining) AS draining,
            coalesce(m.config_version, h.config_version) AS config_version,
            coalesce(m.applied_config_version, h.applied_config_version) AS applied_config_version,
            coalesce(m.runtimes, h.runtimes) AS runtimes,
            coalesce(m.runner_version, h.runner_version) AS runner_version,
            coalesce(m.hostname, h.hostname) AS hostname, coalesce(m.os, h.os) AS os, coalesce(m.arch, h.arch) AS arch,
            coalesce(m.last_seen_at, h.last_seen_at) AS last_seen_at, h.created_at""";

    private static final String HOSTS = " FROM runner_host h LEFT JOIN personal_runner_host m ON m.id = h.personal_host_id";

    private static final String CREDENTIAL_COLUMNS = """
            h.id AS runner_host_id, c.label, c.kind, c.runtimes, c.pool_size, c.status, c.cooldown_until, c.reported""";

    /** The host's {@code host.pool.status}, as {@code jsonb}. */
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final JdbcClient jdbc;

    RunnerHostStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * A runner host as a company sees it.
     *
     * @param ownerPrincipalId       the member's principal that owns a personal host; null for a company host
     * @param personalHostId         the machine a personal host is the presence of; null for a company host
     * @param maxConcurrentRuns      the effective slots as last confirmed (after hello: the expected value)
     * @param draining               as last reported; meaningful only while the host is connected
     * @param appliedConfigVersion   the highest {@code config_version} the host confirmed, or null
     */
    record HostRow(UUID id, UUID companyId, String name, HostScope scope, @Nullable UUID ownerPrincipalId,
            @Nullable UUID personalHostId, int maxConcurrentRuns, int maxConcurrentRunsCeiling,
            @Nullable Integer desiredMaxConcurrentRuns, boolean draining, long configVersion,
            @Nullable Long appliedConfigVersion, @Nullable String runnerVersion, @Nullable String hostname,
            @Nullable String os, @Nullable String arch, @Nullable Instant lastSeenAt, Instant createdAt) {

        /** The host has not confirmed the latest change of a desired value yet. */
        boolean configPending() {
            return configVersion > (appliedConfigVersion != null ? appliedConfigVersion : 0);
        }

        boolean personalOf(@Nullable UUID principalId) {
            return scope == HostScope.PERSONAL && principalId != null && principalId.equals(ownerPrincipalId);
        }

        /** The row the host's facts live in: the machine's for a personal host, its own for a company host. */
        Target target() {
            return personalHostId != null ? Target.machine(personalHostId) : Target.companyHost(id);
        }
    }

    /** A machine: a member's personal runner host, in every company they belong to (ADR-0092). */
    record Machine(UUID id, UUID ownerUserId, String name, Instant createdAt) {
    }

    /** A machine's presence in a company: the {@code runner_host} row there. */
    record Presence(UUID runnerHostId, UUID companyId) {
    }

    /** A membership of a user: the company and their principal there. */
    record Membership(UUID companyId, UUID principalId) {
    }

    /** What a token names: a company host, or a machine with its presences. */
    record TokenOwner(@Nullable UUID runnerHostId, @Nullable UUID companyId, @Nullable UUID personalHostId) {
    }

    /**
     * The row that holds a host's facts, capacity and configuration: {@code runner_host} for a company host,
     * {@code personal_runner_host} for a machine.
     */
    record Target(String table, UUID id) {

        static Target companyHost(UUID runnerHostId) {
            return new Target("runner_host", runnerHostId);
        }

        /** Whether it names a company host's row, which alone keeps ready sandboxes (ADR-0099). */
        boolean companyHost() {
            return "runner_host".equals(table);
        }

        static Target machine(UUID personalHostId) {
            return new Target("personal_runner_host", personalHostId);
        }
    }

    /**
     * Host facts reported in {@code hello}; {@code maxConcurrentRunsCeiling} is its {@code maxConcurrentRuns},
     * {@code runtimes} the runtimes of its {@code runtimes} (those whose images it found), and {@code poolCeiling}
     * its {@code pool.maxReady} when it lists {@code sandbox.pool} (else null).
     */
    record HelloFacts(String runnerVersion, @Nullable String hostname, @Nullable String os, @Nullable String arch,
            int maxConcurrentRunsCeiling, Set<RuntimeKind> runtimes, @Nullable Integer poolCeiling) {
    }

    /**
     * The desired capacity setting of a host: the content of {@code host.config}. It names no credential pools: a
     * personal host's credentials are its owner's, at the pool the host reports. A host that keeps ready sandboxes
     * ({@code poolCeiling} not null) also gets the pool's count and its next change as last stored (ADR-0099).
     */
    record DesiredConfig(long version, @Nullable Integer desiredMaxConcurrentRuns, @Nullable Integer poolCeiling,
            int poolReadyTarget, @Nullable Integer poolNextTarget, @Nullable Instant poolNextAt) {
    }


    // ---- lookups across companies -----------------------------------------------------------------------------

    /** What the token names, across companies: nothing, a company host (one row), or a machine (a row per presence). */
    List<TokenOwner> hostForToken(String tokenHash) {
        return jdbc.sql("SELECT runner_host_id, company_id, personal_host_id FROM runner_host_for_token(?)")
            .param(tokenHash)
            .query((rs, row) -> new TokenOwner(rs.getObject("runner_host_id", UUID.class),
                    rs.getObject("company_id", UUID.class), rs.getObject("personal_host_id", UUID.class)))
            .list();
    }

    /** The company of a run, across companies; empty for an unknown run. */
    Optional<UUID> runCompany(UUID runId) {
        return jdbc.sql("SELECT run_company(?)").param(runId).query(UUID.class).optional();
    }

    /** The machine's presences, across companies. */
    List<Presence> presences(UUID personalHostId) {
        return jdbc.sql("SELECT runner_host_id, company_id FROM personal_runner_host_presences(?)")
            .param(personalHostId)
            .query((rs, row) -> new Presence(rs.getObject("runner_host_id", UUID.class),
                    rs.getObject("company_id", UUID.class)))
            .list();
    }

    /** The companies the user is a member of, who has not left, with their principal there. */
    List<Membership> memberships(UUID userId) {
        return jdbc.sql("SELECT company_id, principal_id FROM member_principals(?)")
            .param(userId)
            .query((rs, row) -> new Membership(rs.getObject("company_id", UUID.class),
                    rs.getObject("principal_id", UUID.class)))
            .list();
    }

    // ---- machines ---------------------------------------------------------------------------------------------

    void insertMachine(Machine machine, String tokenHash) {
        jdbc.sql("""
                INSERT INTO personal_runner_host (id, owner_user_id, name, token_hash, created_at)
                VALUES (?, ?, ?, ?, ?)""")
            .params(machine.id(), machine.ownerUserId(), machine.name(), tokenHash, ts(machine.createdAt()))
            .update();
    }

    boolean machineNameTaken(UUID ownerUserId, String name) {
        return jdbc.sql("SELECT count(*) FROM personal_runner_host WHERE owner_user_id = ? AND name = ?")
            .params(ownerUserId, name)
            .query(Long.class)
            .single() > 0;
    }

    /** The user's machines, by name. */
    List<Machine> machinesOf(UUID ownerUserId) {
        return jdbc.sql("SELECT id, owner_user_id, name, created_at FROM personal_runner_host WHERE owner_user_id = ?"
                + " ORDER BY name, id")
            .param(ownerUserId)
            .query(RunnerHostStore::machine)
            .list();
    }

    /** The machine, locked. */
    Optional<Machine> lockMachine(UUID personalHostId) {
        return jdbc.sql("SELECT id, owner_user_id, name, created_at FROM personal_runner_host WHERE id = ? FOR UPDATE")
            .param(personalHostId)
            .query(RunnerHostStore::machine)
            .optional();
    }

    /** Deletes the machine; its presences and credentials go with it. */
    void deleteMachine(UUID personalHostId) {
        jdbc.sql("DELETE FROM personal_runner_host WHERE id = ?").param(personalHostId).update();
    }

    // ---- hosts of a company: company hosts and presences ------------------------------------------------------

    void insertHost(HostRow host, String tokenHash) {
        jdbc.sql("""
                INSERT INTO runner_host (id, company_id, name, scope, owner_principal_id, token_hash, created_at)
                VALUES (?, ?, ?, 'COMPANY', NULL, ?, ?)""")
            .params(host.id(), host.companyId(), host.name(), tokenHash, ts(host.createdAt()))
            .update();
    }

    /**
     * A company host that the deployment declares (ADR-0101, decision 8): {@code declared}, which no host registered
     * in the app is.
     */
    void insertDeclaredHost(UUID id, UUID companyId, String name, String tokenHash, Instant now) {
        jdbc.sql("""
                INSERT INTO runner_host (id, company_id, name, scope, owner_principal_id, token_hash, declared,
                                         created_at)
                VALUES (?, ?, ?, 'COMPANY', NULL, ?, true, ?)""")
            .params(id, companyId, name, tokenHash, ts(now))
            .update();
    }

    /** A company host as a declaration sees it: whether a declaration registered it, and its token's hash. */
    record DeclaredHost(UUID id, String name, boolean declared, String tokenHash) {
    }

    /** The company host of this name, locked, if any. */
    Optional<DeclaredHost> lockCompanyHostNamed(UUID companyId, String name) {
        return jdbc.sql("""
                SELECT id, name, declared, token_hash FROM runner_host
                 WHERE company_id = ? AND scope = 'COMPANY' AND name = ? FOR UPDATE""")
            .params(companyId, name)
            .query((rs, row) -> new DeclaredHost(rs.getObject("id", UUID.class), rs.getString("name"),
                    rs.getBoolean("declared"), rs.getString("token_hash")))
            .optional();
    }

    /** The names of the company's declared hosts. */
    List<String> declaredHostNames(UUID companyId) {
        return jdbc.sql("SELECT name FROM runner_host WHERE company_id = ? AND declared ORDER BY name")
            .param(companyId)
            .query(String.class)
            .list();
    }

    /** Replaces a declared company host's token: the earlier one stops working. */
    void setDeclaredTokenHash(UUID companyId, UUID hostId, String tokenHash) {
        jdbc.sql("UPDATE runner_host SET token_hash = ? WHERE company_id = ? AND id = ? AND declared")
            .params(tokenHash, companyId, hostId)
            .update();
    }

    /** A machine's presence in the company of the tenant context. */
    void insertPresence(UUID id, UUID companyId, String name, UUID ownerPrincipalId, UUID personalHostId,
            Instant now) {
        jdbc.sql("""
                INSERT INTO runner_host (id, company_id, name, scope, owner_principal_id, personal_host_id, created_at)
                VALUES (?, ?, ?, 'PERSONAL', ?, ?, ?)""")
            .params(id, companyId, name, ownerPrincipalId, personalHostId, ts(now))
            .update();
    }

    /** Whether the company has a company host of this name. */
    boolean companyHostNameTaken(UUID companyId, String name) {
        return jdbc.sql("SELECT count(*) FROM runner_host WHERE company_id = ? AND scope = 'COMPANY' AND name = ?")
            .params(companyId, name)
            .query(Long.class)
            .single() > 0;
    }

    Optional<HostRow> host(UUID companyId, UUID hostId) {
        return jdbc.sql("SELECT " + HOST_COLUMNS + HOSTS + " WHERE h.company_id = ? AND h.id = ?")
            .params(companyId, hostId)
            .query(RunnerHostStore::host)
            .optional();
    }

    Optional<HostRow> lockHost(UUID companyId, UUID hostId) {
        return jdbc.sql("SELECT " + HOST_COLUMNS + HOSTS + " WHERE h.company_id = ? AND h.id = ? FOR UPDATE OF h")
            .params(companyId, hostId)
            .query(RunnerHostStore::host)
            .optional();
    }

    /**
     * The hosts the caller sees, ordered by (name, id), after the given position: the company hosts when
     * {@code companyHosts}, and the personal hosts of {@code ownerPrincipalId} (none when null).
     */
    List<HostRow> hosts(UUID companyId, boolean companyHosts, @Nullable UUID ownerPrincipalId,
            @Nullable String afterName, @Nullable UUID afterId, int limit) {
        List<Object> params = new ArrayList<>(List.of(companyId, companyHosts));
        StringBuilder sql = new StringBuilder("SELECT " + HOST_COLUMNS + HOSTS + " WHERE h.company_id = ?"
                + " AND ((h.scope = 'COMPANY' AND ?)");
        if (ownerPrincipalId != null) {
            sql.append(" OR h.owner_principal_id = ?");
            params.add(ownerPrincipalId);
        }
        sql.append(')');
        if (afterName != null && afterId != null) {
            sql.append(" AND (h.name, h.id) > (?, ?)");
            params.add(afterName);
            params.add(afterId);
        }
        sql.append(" ORDER BY h.name, h.id LIMIT ?");
        params.add(limit);
        return jdbc.sql(sql.toString()).params(params).query(RunnerHostStore::host).list();
    }

    /** The personal hosts of the principal, locked. */
    List<HostRow> lockPersonalHosts(UUID companyId, UUID ownerPrincipalId) {
        return jdbc.sql("SELECT " + HOST_COLUMNS + HOSTS + " WHERE h.company_id = ? AND h.owner_principal_id = ?"
                + " ORDER BY h.id FOR UPDATE OF h")
            .params(companyId, ownerPrincipalId)
            .query(RunnerHostStore::host)
            .list();
    }

    /** Every host of the company, by name. */
    List<RunnerHostInfo> hostInfos(UUID companyId) {
        return jdbc.sql("SELECT " + HOST_COLUMNS + HOSTS + " WHERE h.company_id = ? ORDER BY h.name, h.id")
            .param(companyId)
            .query(RunnerHostStore::hostInfo)
            .list();
    }

    Optional<RunnerHostInfo> hostInfo(UUID companyId, UUID hostId) {
        return jdbc.sql("SELECT " + HOST_COLUMNS + HOSTS + " WHERE h.company_id = ? AND h.id = ?")
            .params(companyId, hostId)
            .query(RunnerHostStore::hostInfo)
            .optional();
    }

    /**
     * Locks, {@code FOR NO KEY UPDATE} and in the order of their ids, first the machines of the personal hosts among
     * the given ones and then the hosts themselves (CONVENTIONS §10): it serialises dispatchers, of every company a
     * machine is in, without blocking foreign-key checks of new runs, and the removal and the capacity change of a
     * machine take its lock first too, so none of them can cycle. A host's machine never changes, so the unlocked read
     * that finds the machines is safe.
     */
    List<RunnerHostInfo> lockHostInfos(Collection<UUID> hostIds) {
        if (hostIds.isEmpty()) {
            return List.of();
        }
        List<UUID> machines = jdbc.sql("SELECT DISTINCT h.personal_host_id FROM runner_host h"
                + " WHERE h.id IN (:ids) AND h.personal_host_id IS NOT NULL ORDER BY 1")
            .param("ids", hostIds)
            .query(UUID.class)
            .list();
        if (!machines.isEmpty()) {
            jdbc.sql("SELECT id FROM personal_runner_host WHERE id IN (:ids) ORDER BY id FOR NO KEY UPDATE")
                .param("ids", machines)
                .query(UUID.class)
                .list();
        }
        return jdbc.sql("SELECT " + HOST_COLUMNS + HOSTS + " WHERE h.id IN (:ids) ORDER BY h.id FOR NO KEY UPDATE OF h")
            .param("ids", hostIds)
            .query(RunnerHostStore::hostInfo)
            .list();
    }

    /** Deletes a company host, or a machine's presence in the company. */
    void deleteHost(UUID companyId, UUID hostId) {
        jdbc.sql("DELETE FROM runner_host WHERE company_id = ? AND id = ?").params(companyId, hostId).update();
    }

    // ---- the facts, capacity and configuration of a host ------------------------------------------------------

    /**
     * Records {@code hello}: the ceiling, and as effective value the one the server expects, {@code min(desired,
     * ceiling)}, until the host confirms it; a new connection does not drain. False when the host no longer exists
     * (deleted after the token check).
     */
    boolean recordHello(Target target, HelloFacts facts, Instant now) {
        boolean found = jdbc.sql("UPDATE " + target.table() + """
                   SET runner_version = ?, hostname = ?, os = ?, arch = ?, max_concurrent_runs_ceiling = ?,
                       max_concurrent_runs = least(coalesce(desired_max_concurrent_runs, ?), ?), draining = false,
                       runtimes = CAST(? AS varchar(16)[]), last_seen_at = ?
                 WHERE id = ?""")
            .params(facts.runnerVersion(), facts.hostname(), facts.os(), facts.arch(),
                    facts.maxConcurrentRunsCeiling(), facts.maxConcurrentRunsCeiling(),
                    facts.maxConcurrentRunsCeiling(), names(facts.runtimes()), ts(now), target.id())
            .update() == 1;
        if (found && target.companyHost()) {
            // Only a company host keeps ready sandboxes (ADR-0099): its ceiling, or none without the feature.
            jdbc.sql("UPDATE runner_host SET pool_max_ready_ceiling = CAST(? AS integer) WHERE id = ?")
                .params(facts.poolCeiling(), target.id())
                .update();
        }
        return found;
    }

    /**
     * Stores the credentials a machine's {@code hello} reports (the package 2 contract, §2): new labels are added,
     * known ones take the reported kind, runtimes and pool and start {@code AVAILABLE} again, and those the hello no
     * longer lists are kept as no longer reported.
     */
    void recordCredentials(UUID personalHostId, List<RunnerMessages.HelloCredential> credentials, Instant now) {
        for (RunnerMessages.HelloCredential credential : credentials) {
            jdbc.sql("""
                    INSERT INTO personal_runner_credential (id, personal_host_id, label, kind, runtimes, pool_size,
                                                            status, reported, created_at, updated_at)
                    VALUES (?, ?, ?, ?, CAST(? AS varchar(16)[]), ?, 'AVAILABLE', true, ?, ?)
                    ON CONFLICT (personal_host_id, label) DO UPDATE
                       SET kind = EXCLUDED.kind, runtimes = EXCLUDED.runtimes, pool_size = EXCLUDED.pool_size,
                           status = 'AVAILABLE', cooldown_until = NULL, detail = NULL, reported = true,
                           updated_at = EXCLUDED.updated_at""")
                .params(Ids.newId(), personalHostId, credential.label(), credential.kind().name(),
                        names(Set.copyOf(credential.runtimes())), credential.poolSize(), ts(now), ts(now))
                .update();
        }
        String[] labels = credentials.stream().map(RunnerMessages.HelloCredential::label).toArray(String[]::new);
        jdbc.sql("""
                UPDATE personal_runner_credential SET reported = false, updated_at = ?
                 WHERE personal_host_id = ? AND reported AND label <> ALL (CAST(? AS varchar(63)[]))""")
            .params(ts(now), personalHostId, labels)
            .update();
    }

    /** Stores a {@code credential.status} of a stored credential; false when the machine holds no such credential. */
    boolean recordCredentialStatus(UUID personalHostId, String label, CredentialState state,
            @Nullable Instant cooldownUntil, @Nullable String detail, Instant now) {
        return jdbc.sql("""
                UPDATE personal_runner_credential SET status = ?, cooldown_until = ?, detail = ?, updated_at = ?
                 WHERE personal_host_id = ? AND label = ?""")
            .params(state.name(), cooldownUntil != null ? ts(cooldownUntil) : null, detail, ts(now), personalHostId,
                    label)
            .update() == 1;
    }

    /** The credentials of the company's hosts, by host and label: a presence's are its machine's. */
    List<HostCredentialInfo> credentials(UUID companyId, Collection<UUID> hostIds) {
        if (hostIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT " + CREDENTIAL_COLUMNS + " FROM runner_host h"
                + " JOIN personal_runner_credential c ON c.personal_host_id = h.personal_host_id"
                + " WHERE h.company_id = :companyId AND h.id IN (:ids) ORDER BY h.id, c.label")
            .param("companyId", companyId)
            .param("ids", hostIds)
            .query(RunnerHostStore::credential)
            .list();
    }

    /** The host's current {@code config_version} and desired values; empty when the host no longer exists. */
    Optional<DesiredConfig> desiredConfig(Target target) {
        if (!target.companyHost()) {
            return jdbc.sql("SELECT config_version, desired_max_concurrent_runs FROM " + target.table() + " WHERE id = ?")
                .param(target.id())
                .query((rs, row) -> new DesiredConfig(rs.getLong("config_version"),
                        rs.getObject("desired_max_concurrent_runs", Integer.class), null, 0, null, null))
                .optional();
        }
        return jdbc.sql("""
                SELECT config_version, desired_max_concurrent_runs, pool_max_ready_ceiling, pool_ready_target,
                       pool_next_target, pool_next_at
                  FROM runner_host WHERE id = ?""")
            .param(target.id())
            .query((rs, row) -> new DesiredConfig(rs.getLong("config_version"),
                    rs.getObject("desired_max_concurrent_runs", Integer.class),
                    rs.getObject("pool_max_ready_ceiling", Integer.class), rs.getInt("pool_ready_target"),
                    rs.getObject("pool_next_target", Integer.class), nullableInstant(rs, "pool_next_at")))
            .optional();
    }

    /** Stores a company host's latest {@code host.pool.status} (ADR-0099), which its page shows. */
    void recordPoolStatus(UUID hostId, RunnerMessages.HostPoolStatus status, Instant at) {
        jdbc.sql("UPDATE runner_host SET pool_status = CAST(? AS jsonb), pool_status_at = ? WHERE id = ?")
            .params(JSON.writeValueAsString(status), ts(at), hostId)
            .update();
    }

    /**
     * Stores what {@code host.config.applied} reports for the host itself, and the pool's ceiling when it reports a
     * pool. The confirmed version only grows, and is never above a version the server has issued. False when the host
     * no longer exists.
     */
    boolean recordApplied(Target target, long version, boolean draining, int maxConcurrentRuns, int ceiling,
            @Nullable Integer poolCeiling) {
        boolean found = jdbc.sql("UPDATE " + target.table() + """
                   SET max_concurrent_runs_ceiling = ?, max_concurrent_runs = ?, draining = ?,
                       applied_config_version = greatest(applied_config_version, least(?, config_version))
                 WHERE id = ?""")
            .params(ceiling, maxConcurrentRuns, draining, version, target.id())
            .update() == 1;
        if (found && poolCeiling != null && target.companyHost()) {
            jdbc.sql("UPDATE runner_host SET pool_max_ready_ceiling = ? WHERE id = ?")
                .params(poolCeiling, target.id())
                .update();
        }
        return found;
    }

    /** Sets or clears the host's desired slots; returns the new {@code config_version}. */
    long setDesiredMaxConcurrentRuns(Target target, @Nullable Integer desired) {
        return jdbc.sql("UPDATE " + target.table() + """
                   SET desired_max_concurrent_runs = ?, config_version = config_version + 1
                 WHERE id = ?
                RETURNING config_version""")
            .params(desired, target.id())
            .query(Long.class)
            .single();
    }

    void touch(Target target, Instant now) {
        jdbc.sql("UPDATE " + target.table() + " SET last_seen_at = ? WHERE id = ?")
            .params(ts(now), target.id())
            .update();
    }

    private static HostRow host(ResultSet rs, int row) throws SQLException {
        return new HostRow(rs.getObject("id", UUID.class), rs.getObject("company_id", UUID.class),
                rs.getString("name"), HostScope.valueOf(rs.getString("scope")),
                rs.getObject("owner_principal_id", UUID.class), rs.getObject("personal_host_id", UUID.class),
                rs.getInt("max_concurrent_runs"), rs.getInt("max_concurrent_runs_ceiling"),
                rs.getObject("desired_max_concurrent_runs", Integer.class), rs.getBoolean("draining"),
                rs.getLong("config_version"), rs.getObject("applied_config_version", Long.class),
                rs.getString("runner_version"), rs.getString("hostname"), rs.getString("os"), rs.getString("arch"),
                nullableInstant(rs, "last_seen_at"), rs.getObject("created_at", OffsetDateTime.class).toInstant());
    }

    private static RunnerHostInfo hostInfo(ResultSet rs, int row) throws SQLException {
        return new RunnerHostInfo(rs.getObject("id", UUID.class), rs.getObject("company_id", UUID.class),
                rs.getString("name"), HostScope.valueOf(rs.getString("scope")),
                rs.getObject("owner_principal_id", UUID.class), rs.getObject("personal_host_id", UUID.class),
                rs.getInt("max_concurrent_runs"), rs.getInt("max_concurrent_runs_ceiling"),
                rs.getObject("desired_max_concurrent_runs", Integer.class), rs.getBoolean("draining"),
                runtimes(rs.getArray("runtimes")), rs.getString("runner_version"), nullableInstant(rs, "last_seen_at"));
    }

    private static Machine machine(ResultSet rs, int row) throws SQLException {
        return new Machine(rs.getObject("id", UUID.class), rs.getObject("owner_user_id", UUID.class),
                rs.getString("name"), rs.getObject("created_at", OffsetDateTime.class).toInstant());
    }

    private static HostCredentialInfo credential(ResultSet rs, int row) throws SQLException {
        Set<RuntimeKind> runtimes = runtimes(rs.getArray("runtimes"));
        return new HostCredentialInfo(rs.getObject("runner_host_id", UUID.class), rs.getString("label"),
                CredentialKind.valueOf(rs.getString("kind")), runtimes != null ? runtimes : Set.of(),
                rs.getInt("pool_size"), CredentialState.valueOf(rs.getString("status")),
                nullableInstant(rs, "cooldown_until"), rs.getBoolean("reported"));
    }

    private static String[] names(Set<RuntimeKind> runtimes) {
        return runtimes.stream().map(Enum::name).sorted().toArray(String[]::new);
    }

    /** The runtimes of a {@code varchar(16)[]} column; null for a NULL column. */
    private static @Nullable Set<RuntimeKind> runtimes(@Nullable Array array) throws SQLException {
        if (array == null) {
            return null;
        }
        Set<RuntimeKind> runtimes = EnumSet.noneOf(RuntimeKind.class);
        for (Object runtime : (Object[]) array.getArray()) {
            runtimes.add(RuntimeKind.valueOf((String) runtime));
        }
        return runtimes;
    }

    static @Nullable Instant nullableInstant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value != null ? value.toInstant() : null;
    }

    static OffsetDateTime ts(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
