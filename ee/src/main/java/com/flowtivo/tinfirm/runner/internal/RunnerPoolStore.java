package com.flowtivo.tinfirm.runner.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.flowtivo.tinfirm.runner.RunnerMessages;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The SQL on {@code runner_host}'s pool (V067) that only Tinfirm's schedule of ready sandboxes runs (ADR-0099,
 * ADR-0100), under row-level security: a host's pool read with its settings, its settings stored, and the count and
 * next change that {@code host.config.pool} carries. What the host reports, and the configuration it is sent, are the
 * core's ({@link RunnerHostStore}).
 */
@Repository
class RunnerPoolStore {

    private static final String POOL_COLUMNS = """
            id, pool_max_ready_ceiling, pool_ready_default, pool_zone, pool_windows, pool_ready_target,
            pool_next_target, pool_next_at, pool_status, pool_status_at""";

    /** The pool's windows and the host's status, as {@code jsonb}. */
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final TypeReference<List<PoolSchedule.WindowSettings>> WINDOWS = new TypeReference<>() {
    };

    private final JdbcClient jdbc;

    RunnerPoolStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * A host's sandbox pool (ADR-0099): its ceiling from {@code hello} (null: the host keeps no ready sandboxes), the
     * settings set in Tinfirm (null: none), the count and next change last sent, and the host's last
     * {@code host.pool.status}.
     */
    record PoolRow(UUID hostId, @Nullable Integer maxReadyCeiling, PoolSchedule.@Nullable Settings settings,
            int readyTarget, @Nullable Integer nextTarget, @Nullable Instant nextAt,
            RunnerMessages.@Nullable HostPoolStatus status, @Nullable Instant statusAt) {

        boolean supported() {
            return maxReadyCeiling != null;
        }
    }

    /**
     * The host's pool, its row locked to the end of the transaction: the minute's tick and a change of the settings
     * evaluate and store the count one after the other, never from settings the other just replaced.
     */
    Optional<PoolRow> pool(UUID hostId) {
        return jdbc.sql("SELECT " + POOL_COLUMNS + " FROM runner_host WHERE id = ? FOR UPDATE")
            .param(hostId)
            .query(RunnerPoolStore::pool)
            .optional();
    }

    /** The pools of these hosts of the company. */
    List<PoolRow> pools(UUID companyId, Collection<UUID> hostIds) {
        if (hostIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT " + POOL_COLUMNS + " FROM runner_host WHERE company_id = :companyId AND id IN (:ids)")
            .param("companyId", companyId)
            .param("ids", hostIds)
            .query(RunnerPoolStore::pool)
            .list();
    }

    /** Sets or clears the pool's settings; returns the new {@code config_version}. */
    long setPoolSettings(UUID hostId, PoolSchedule.@Nullable Settings settings) {
        return jdbc.sql("""
                UPDATE runner_host
                   SET pool_ready_default = CAST(? AS integer), pool_zone = CAST(? AS varchar), pool_windows = CAST(? AS jsonb),
                       config_version = config_version + 1
                 WHERE id = ?
                RETURNING config_version""")
            .params(settings != null ? settings.readyDefault() : null, settings != null ? settings.zone() : null,
                    settings != null ? JSON.writeValueAsString(settings.windows()) : null, hostId)
            .query(Long.class)
            .single();
    }

    /**
     * Stores the count and its next change that {@code host.config.pool} carries, and moves {@code config_version} on
     * when they changed. True when they did.
     */
    boolean storePoolTarget(UUID hostId, int readyTarget, @Nullable Integer nextTarget, @Nullable Instant nextAt) {
        return jdbc.sql("""
                UPDATE runner_host
                   SET pool_ready_target = ?, pool_next_target = CAST(? AS integer), pool_next_at = CAST(? AS timestamptz),
                       config_version = config_version + 1
                 WHERE id = ?
                   AND (pool_ready_target, pool_next_target, pool_next_at)
                       IS DISTINCT FROM (CAST(? AS integer), CAST(? AS integer), CAST(? AS timestamptz))""")
            .params(readyTarget, nextTarget, nextAt != null ? RunnerHostStore.ts(nextAt) : null, hostId, readyTarget,
                    nextTarget, nextAt != null ? RunnerHostStore.ts(nextAt) : null)
            .update() == 1;
    }

    private static PoolRow pool(ResultSet rs, int row) throws SQLException {
        Integer readyDefault = rs.getObject("pool_ready_default", Integer.class);
        String windows = rs.getString("pool_windows");
        PoolSchedule.Settings settings = readyDefault != null && windows != null
                ? new PoolSchedule.Settings(rs.getString("pool_zone"), readyDefault, JSON.readValue(windows, WINDOWS))
                : null;
        String status = rs.getString("pool_status");
        return new PoolRow(rs.getObject("id", UUID.class), rs.getObject("pool_max_ready_ceiling", Integer.class),
                settings, rs.getInt("pool_ready_target"), rs.getObject("pool_next_target", Integer.class),
                RunnerHostStore.nullableInstant(rs, "pool_next_at"),
                status != null ? JSON.readValue(status, RunnerMessages.HostPoolStatus.class) : null,
                RunnerHostStore.nullableInstant(rs, "pool_status_at"));
    }
}
