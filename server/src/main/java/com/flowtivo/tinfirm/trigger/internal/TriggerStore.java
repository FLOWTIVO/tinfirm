package com.flowtivo.tinfirm.trigger.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.flowtivo.tinfirm.trigger.FiringOutcome;
import com.flowtivo.tinfirm.trigger.PausedReason;
import com.flowtivo.tinfirm.trigger.TriggerAccount;
import com.flowtivo.tinfirm.trigger.TriggerEvent;
import com.flowtivo.tinfirm.trigger.TriggerInfo;
import com.flowtivo.tinfirm.trigger.TriggerRepeat;
import com.flowtivo.tinfirm.trigger.TriggerSchedule;
import com.flowtivo.tinfirm.trigger.TriggerSource;
import com.flowtivo.tinfirm.trigger.TriggerStatus;
import com.flowtivo.tinfirm.trigger.TriggerSubject;
import com.flowtivo.tinfirm.trigger.WaitingReason;

/**
 * SQL access to {@code trigger}, {@code trigger_account}, {@code trigger_setting} and the reads of
 * {@code trigger_firing}, {@code trigger_poll} and {@code outbox} part a needs (V011 to V013), under row-level
 * security. A trigger's row is locked {@code FOR NO KEY UPDATE} (the lock order, {@link TriggerLocks}).
 */
@Repository
class TriggerStore {

    private static final String COLUMNS = "id, company_id, name, source, system_id, events::text AS events, subject,"
            + " scope, conditions::text AS conditions, repeat, schedule_cron, schedule_zone, overlap, catch_up,"
            + " next_run_at, last_run_at, last_outcome, agent_id, instruction, author_principal_id, team_group_id,"
            + " payer_principal_id, results::text AS results, max_firings_per_hour, status, paused_reason, paused_at,"
            + " waiting_reason, waiting_since, armed_at, missed_count, rate_skipped_count,"
            + " open_events_acknowledged_at, deleted_at, deleted_cause, created_at, updated_at";

    private static final String FIRING_COLUMNS = "id, trigger_id, subject_id, subject_key, event, value, changed_at,"
            + " due_at, manual_by, once, outcome, reason, chain_depth, conversation_id, run_id, created_at";

    private final JdbcClient jdbc;

    TriggerStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ---- triggers --------------------------------------------------------------------------------------------------

    void insert(TriggerInfo trigger, @Nullable UUID createdBy, @Nullable UUID acknowledgedBy) {
        TriggerSchedule schedule = trigger.schedule();
        jdbc.sql("""
                INSERT INTO trigger (id, company_id, name, source, system_id, events, subject, scope, conditions, repeat,
                                     schedule_cron, schedule_zone, overlap, catch_up, next_run_at, agent_id,
                                     instruction, author_principal_id, team_group_id, payer_principal_id, results,
                                     max_firings_per_hour, armed_at, open_events_acknowledged_at,
                                     open_events_acknowledged_by, created_at, updated_at, created_by)
                VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, CAST(? AS jsonb), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                        CAST(? AS jsonb), ?, ?, ?, ?, ?, ?, ?)""")
            .params(trigger.id(), trigger.companyId(), trigger.name(), trigger.source().name(), trigger.systemId(),
                    TriggerJson.events(trigger.events()), trigger.subject().name(), trigger.scope(),
                    TriggerJson.conditions(trigger.conditions()), trigger.repeat().name(),
                    schedule != null ? schedule.cron() : null, schedule != null ? schedule.zone() : null,
                    schedule != null ? schedule.overlap().name() : null,
                    schedule != null ? schedule.catchUp().name() : null,
                    schedule != null ? ts(schedule.nextRunAt()) : null, trigger.agentId(), trigger.instruction(),
                    trigger.authorPrincipalId(), trigger.teamGroupId(), trigger.payerPrincipalId(),
                    TriggerJson.results(trigger.results()), trigger.maxFiringsPerHour(), ts(trigger.armedAt()),
                    ts(trigger.openEventsAcknowledgedAt()), acknowledgedBy, ts(trigger.createdAt()),
                    ts(trigger.updatedAt()), createdBy)
            .update();
    }

    Optional<TriggerInfo> find(UUID companyId, UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM trigger WHERE company_id = ? AND id = ?")
            .params(companyId, id)
            .query(TriggerStore::row)
            .optional();
    }

    /** The trigger, locked {@code FOR NO KEY UPDATE}: first in the lock order, before its conversations. */
    Optional<TriggerInfo> lock(UUID companyId, UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM trigger WHERE company_id = ? AND id = ? FOR NO KEY UPDATE")
            .params(companyId, id)
            .query(TriggerStore::row)
            .optional();
    }

    /**
     * The definition's fields of an update (the rest stays). The open events' acknowledgement keeps who gave it unless
     * {@code acknowledgedBy} names someone new, and goes whole when its time is null: a change that makes an earlier
     * acknowledgement unnecessary clears both columns together ({@code trigger_acknowledged_ck}).
     */
    void updateDefinition(TriggerInfo trigger, @Nullable UUID acknowledgedBy) {
        TriggerSchedule schedule = trigger.schedule();
        jdbc.sql("""
                UPDATE trigger
                   SET name = ?, events = CAST(? AS jsonb), subject = ?, scope = ?, conditions = CAST(? AS jsonb),
                       repeat = ?, schedule_cron = ?, schedule_zone = ?, overlap = ?, catch_up = ?, next_run_at = ?,
                       instruction = ?, team_group_id = ?, results = CAST(? AS jsonb), max_firings_per_hour = ?,
                       open_events_acknowledged_at = ?,
                       open_events_acknowledged_by = CASE WHEN CAST(? AS timestamptz) IS NULL THEN NULL
                                                          ELSE coalesce(?, open_events_acknowledged_by) END,
                       updated_at = ?
                 WHERE company_id = ? AND id = ?""")
            .params(trigger.name(), TriggerJson.events(trigger.events()), trigger.subject().name(), trigger.scope(),
                    TriggerJson.conditions(trigger.conditions()), trigger.repeat().name(),
                    schedule != null ? schedule.cron() : null, schedule != null ? schedule.zone() : null,
                    schedule != null ? schedule.overlap().name() : null,
                    schedule != null ? schedule.catchUp().name() : null,
                    schedule != null ? ts(schedule.nextRunAt()) : null, trigger.instruction(), trigger.teamGroupId(),
                    TriggerJson.results(trigger.results()), trigger.maxFiringsPerHour(),
                    ts(trigger.openEventsAcknowledgedAt()), ts(trigger.openEventsAcknowledgedAt()), acknowledgedBy,
                    ts(trigger.updatedAt()),
                    trigger.companyId(), trigger.id())
            .update();
    }

    void setPayer(UUID companyId, UUID id, UUID payerPrincipalId, Instant at) {
        jdbc.sql("UPDATE trigger SET payer_principal_id = ?, updated_at = ? WHERE company_id = ? AND id = ?")
            .params(payerPrincipalId, ts(at), companyId, id)
            .update();
    }

    /** A reassignment: the new author and payer in one statement ({@code trigger_payer_holder_ck}). */
    void reassign(UUID companyId, UUID id, UUID authorPrincipalId, UUID payerPrincipalId, Instant at) {
        jdbc.sql("""
                UPDATE trigger SET author_principal_id = ?, payer_principal_id = ?, admin_changed_at = NULL,
                                   admin_changed_by = NULL, admin_changed_what = NULL, updated_at = ?
                 WHERE company_id = ? AND id = ?""")
            .params(authorPrincipalId, payerPrincipalId, ts(at), companyId, id)
            .update();
    }

    /** An admin changed the trigger on its author's behalf (§11, V024): the author's inbox says so. */
    void adminChanged(UUID companyId, UUID id, @Nullable UUID adminPrincipalId, String what, Instant at) {
        jdbc.sql("""
                UPDATE trigger SET admin_changed_at = ?, admin_changed_by = ?, admin_changed_what = ?
                 WHERE company_id = ? AND id = ?""")
            .params(ts(at), adminPrincipalId, what.length() > 500 ? what.substring(0, 500) : what, companyId, id)
            .update();
    }

    /** The author changed the trigger themselves: the note of an admin's change goes. */
    void authorChanged(UUID companyId, UUID id) {
        jdbc.sql("""
                UPDATE trigger SET admin_changed_at = NULL, admin_changed_by = NULL, admin_changed_what = NULL
                 WHERE company_id = ? AND id = ? AND admin_changed_at IS NOT NULL""")
            .params(companyId, id)
            .update();
    }

    /**
     * A reassignment's reset of the open events' acknowledgement (H2): the new author gives their own when they accept
     * the trigger.
     */
    void clearAcknowledgement(UUID companyId, UUID id, Instant at) {
        jdbc.sql("""
                UPDATE trigger SET open_events_acknowledged_at = NULL, open_events_acknowledged_by = NULL, updated_at = ?
                 WHERE company_id = ? AND id = ?""")
            .params(ts(at), companyId, id)
            .update();
    }

    // ---- credentials exposed to open sources (V077; D2, Q3) ---------------------------------------------------------

    /**
     * Records that the trigger acts with the credential through the service account, once per trigger and credential:
     * true the first time, when whoever lent it is to be told.
     */
    boolean recordExposure(UUID companyId, UUID triggerId, UUID accountId, UUID servicePrincipalId,
            @Nullable UUID lentBy, Instant at) {
        return jdbc.sql("""
                INSERT INTO trigger_credential_exposure (company_id, trigger_id, account_id, service_principal_id,
                                                         lent_by, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (trigger_id, account_id) DO NOTHING""")
            .params(companyId, triggerId, accountId, servicePrincipalId, lentBy, ts(at))
            .update() == 1;
    }

    /** A credential a live trigger open to anyone exposed, for the inbox of whoever lent it. */
    record Exposure(UUID triggerId, String triggerName, TriggerSource source, @Nullable UUID systemId,
            UUID accountId, UUID servicePrincipalId, @Nullable UUID lentBy, Instant at) {
    }

    /** The exposures of the company's live triggers recorded since {@code since}, oldest first, at most {@code limit}. */
    /**
     * The exposures since {@code since} that are {@code userId}'s to see: the ones they lent, and for an admin
     * ({@code admin}) also the ones whose lender was not recorded or is no member any more. The audience is in the
     * query, so that {@code limit} counts only what the caller sees.
     */
    List<Exposure> exposuresSince(UUID companyId, UUID userId, boolean admin, Instant since, int limit) {
        return jdbc.sql("""
                SELECT e.trigger_id, t.name, t.source, t.system_id, e.account_id, e.service_principal_id, e.lent_by,
                       e.created_at
                  FROM trigger_credential_exposure e JOIN trigger t ON t.id = e.trigger_id
                 WHERE e.company_id = ? AND e.created_at >= ? AND t.deleted_at IS NULL
                   AND (e.lent_by = ?
                        OR (? AND (e.lent_by IS NULL OR NOT EXISTS (
                                SELECT 1 FROM principal p
                                 WHERE p.company_id = e.company_id AND p.user_id = e.lent_by
                                   AND p.kind = 'MEMBER' AND p.status <> 'LEFT'))))
                 ORDER BY e.created_at, e.trigger_id, e.account_id
                 LIMIT ?""")
            .params(companyId, ts(since), userId, admin, limit)
            .query((rs, n) -> new Exposure(rs.getObject("trigger_id", UUID.class), rs.getString("name"),
                    TriggerSource.valueOf(rs.getString("source")), rs.getObject("system_id", UUID.class),
                    rs.getObject("account_id", UUID.class), rs.getObject("service_principal_id", UUID.class),
                    rs.getObject("lent_by", UUID.class), instant(rs, "created_at")))
            .list();
    }

    /**
     * The live triggers open to anyone (every source but a schedule) whose rows act with each of the credentials
     * through a service account, by credential, by name (D2, Q3).
     */
    Map<UUID, List<com.flowtivo.tinfirm.account.CredentialUses.OpenTrigger>> liveOpenByCredential(UUID companyId,
            Collection<UUID> credentialIds) {
        Map<UUID, List<com.flowtivo.tinfirm.account.CredentialUses.OpenTrigger>> open = new LinkedHashMap<>();
        if (credentialIds.isEmpty()) {
            return open;
        }
        jdbc.sql("""
                SELECT a.account_id, a.holder_principal_id, t.id, t.name, t.source
                  FROM trigger t JOIN trigger_account a ON a.trigger_id = t.id
                 WHERE t.company_id = ? AND t.deleted_at IS NULL AND t.source <> 'SCHEDULE'
                   AND a.account_id = ANY (?) AND a.holder_principal_id <> t.author_principal_id
                 ORDER BY t.name, t.id""")
            .params(companyId, credentialIds.toArray(UUID[]::new))
            .query((rs, n) -> open.computeIfAbsent(rs.getObject("account_id", UUID.class), id -> new ArrayList<>())
                .add(new com.flowtivo.tinfirm.account.CredentialUses.OpenTrigger(rs.getObject("id", UUID.class),
                        rs.getString("name"), rs.getString("source"),
                        rs.getObject("holder_principal_id", UUID.class))))
            .list();
        return open;
    }

    /** An admin's latest change of a live trigger of the author since {@code since}, for the author's inbox. */
    record AdminChange(UUID triggerId, String name, Instant at, @Nullable UUID by, String what) {
    }

    List<AdminChange> adminChanges(UUID companyId, UUID authorPrincipalId, Instant since) {
        return jdbc.sql("""
                SELECT id, name, admin_changed_at, admin_changed_by, admin_changed_what FROM trigger
                 WHERE company_id = ? AND author_principal_id = ? AND deleted_at IS NULL
                   AND admin_changed_at >= ? ORDER BY admin_changed_at, id""")
            .params(companyId, authorPrincipalId, ts(since))
            .query((rs, n) -> new AdminChange(rs.getObject("id", UUID.class), rs.getString("name"),
                    instant(rs, "admin_changed_at"), rs.getObject("admin_changed_by", UUID.class),
                    rs.getString("admin_changed_what")))
            .list();
    }

    /** Live triggers with a row on the system whose account went (an account disconnected), by id. */
    List<UUID> liveWithoutAccountOn(UUID companyId, UUID systemId) {
        return jdbc.sql("""
                SELECT DISTINCT t.id FROM trigger t JOIN trigger_account a ON a.trigger_id = t.id
                 WHERE t.company_id = ? AND a.system_id = ? AND a.account_id IS NULL AND t.deleted_at IS NULL
                 ORDER BY t.id""")
            .params(companyId, systemId)
            .query(UUID.class)
            .list();
    }

    /** Live triggers paused for the reason, by id (the inbox of admins: {@code AUTHOR_LEFT}). */
    List<TriggerInfo> livePaused(UUID companyId, PausedReason reason) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM trigger WHERE company_id = ? AND deleted_at IS NULL"
                + " AND status = 'PAUSED' AND paused_reason = ? ORDER BY paused_at, id")
            .params(companyId, reason.name())
            .query(TriggerStore::row)
            .list();
    }

    /** Live paused triggers, the newest pause first (the kill switches' page, package 6). */
    List<TriggerInfo> livePaused(UUID companyId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM trigger WHERE company_id = ? AND deleted_at IS NULL"
                + " AND status = 'PAUSED' ORDER BY paused_at DESC, id")
            .params(companyId)
            .query(TriggerStore::row)
            .list();
    }

    /** Live triggers whose source is the system or whose table has a row on it, by id (a system's pause). */
    List<TriggerInfo> liveOnSystem(UUID companyId, UUID systemId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM trigger t WHERE company_id = ? AND deleted_at IS NULL"
                + " AND (system_id = ? OR EXISTS (SELECT 1 FROM trigger_account a WHERE a.trigger_id = t.id"
                + " AND a.system_id = ?)) ORDER BY id")
            .params(companyId, systemId, systemId)
            .query(TriggerStore::row)
            .list();
    }

    /** Live active triggers of the company, by id (the company's stop and its lift, package 6). */
    List<TriggerInfo> liveActive(UUID companyId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM trigger WHERE company_id = ? AND deleted_at IS NULL"
                + " AND status = 'ACTIVE' ORDER BY id")
            .params(companyId)
            .query(TriggerStore::row)
            .list();
    }

    /** Live triggers of the author, in full. */
    List<TriggerInfo> liveOfAuthorInfo(UUID companyId, UUID authorPrincipalId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM trigger WHERE company_id = ? AND author_principal_id = ?"
                + " AND deleted_at IS NULL ORDER BY id")
            .params(companyId, authorPrincipalId)
            .query(TriggerStore::row)
            .list();
    }

    /** Live triggers whose team is one of the groups, in full. */
    List<TriggerInfo> liveOfTeams(UUID companyId, Collection<UUID> groupIds) {
        if (groupIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT " + COLUMNS + " FROM trigger WHERE company_id = :companyId AND deleted_at IS NULL"
                + " AND team_group_id IN (:groupIds) ORDER BY id")
            .param("companyId", companyId)
            .param("groupIds", Set.copyOf(groupIds))
            .query(TriggerStore::row)
            .list();
    }

    /** Polls waiting on an account or a principal, for admins' inbox (part b keeps them). */
    record WaitingPoll(UUID id, UUID systemId, UUID accountId, Instant since) {
    }

    List<WaitingPoll> waitingPolls(UUID companyId, int limit) {
        return jdbc.sql("""
                SELECT id, system_id, account_id, coalesce(failing_since, updated_at) AS since FROM trigger_poll
                 WHERE company_id = ? AND status = 'WAITING' ORDER BY since, id LIMIT ?""")
            .params(companyId, limit)
            .query((rs, n) -> new WaitingPoll(rs.getObject("id", UUID.class), rs.getObject("system_id", UUID.class),
                    rs.getObject("account_id", UUID.class), instant(rs, "since")))
            .list();
    }

    /** A firing that part a records itself: a schedule's run now (§5.6). */
    void insertFiring(UUID id, UUID companyId, UUID triggerId, Instant dueAt, @Nullable UUID manualBy,
            FiringOutcome outcome, @Nullable String reason, Instant at) {
        jdbc.sql("""
                INSERT INTO trigger_firing (id, company_id, trigger_id, event, due_at, manual_by, outcome, reason,
                                            created_at)
                VALUES (?, ?, ?, 'SCHEDULE', ?, ?, ?, ?, ?)""")
            .params(id, companyId, triggerId, ts(dueAt), manualBy, outcome.name(), reason, ts(at))
            .update();
    }

    void setTeam(UUID companyId, UUID id, @Nullable UUID teamGroupId, Instant at) {
        jdbc.sql("UPDATE trigger SET team_group_id = ?, updated_at = ? WHERE company_id = ? AND id = ?")
            .params(teamGroupId, ts(at), companyId, id)
            .update();
    }

    void setMaxFirings(UUID companyId, UUID id, int max, Instant at) {
        jdbc.sql("UPDATE trigger SET max_firings_per_hour = ?, updated_at = ? WHERE company_id = ? AND id = ?")
            .params(max, ts(at), companyId, id)
            .update();
    }

    void pause(UUID companyId, UUID id, PausedReason reason, @Nullable UUID by, Instant at) {
        jdbc.sql("""
                UPDATE trigger SET status = 'PAUSED', paused_reason = ?, paused_at = ?, paused_by = ?,
                                   waiting_reason = NULL, waiting_since = NULL, next_run_at = NULL, updated_at = ?
                 WHERE company_id = ? AND id = ?""")
            .params(reason.name(), ts(at), by, ts(at), companyId, id)
            .update();
    }

    /** Active again, armed now (T16); the counts since the last resume start over. */
    void resume(UUID companyId, UUID id, @Nullable Instant nextRunAt, Instant at) {
        jdbc.sql("""
                UPDATE trigger SET status = 'ACTIVE', paused_reason = NULL, paused_at = NULL, paused_by = NULL,
                                   armed_at = ?, missed_count = 0, rate_skipped_count = 0, next_run_at = ?,
                                   updated_at = ?
                 WHERE company_id = ? AND id = ?""")
            .params(ts(at), ts(nextRunAt), ts(at), companyId, id)
            .update();
    }

    /** Re-arms a trigger (a reassignment, T16): a change before now fires nothing. */
    void arm(UUID companyId, UUID id, Instant at) {
        jdbc.sql("UPDATE trigger SET armed_at = ?, updated_at = ? WHERE company_id = ? AND id = ?")
            .params(ts(at), ts(at), companyId, id)
            .update();
    }

    void waiting(UUID companyId, UUID id, @Nullable WaitingReason reason, Instant at) {
        jdbc.sql("""
                UPDATE trigger SET waiting_reason = ?,
                                   waiting_since = CASE WHEN ?::text IS NULL THEN NULL
                                                        WHEN waiting_reason IS NOT DISTINCT FROM ?::text
                                                        THEN waiting_since ELSE ? END,
                                   updated_at = ?
                 WHERE company_id = ? AND id = ?""")
            .params(reason != null ? reason.name() : null, reason != null ? reason.name() : null,
                    reason != null ? reason.name() : null, ts(at), ts(at), companyId, id)
            .update();
    }

    void delete(UUID companyId, UUID id, String cause, @Nullable UUID by, Instant at) {
        // A channel trigger's channels go with it (V030): the indexes of one trigger per agent and one default per
        // channel hold among the live triggers; its conversations keep their channel in their place.
        jdbc.sql("DELETE FROM trigger_channel WHERE company_id = ? AND trigger_id = ?")
            .params(companyId, id)
            .update();
        jdbc.sql("""
                UPDATE trigger SET deleted_at = ?, deleted_cause = ?, deleted_by = ?, next_run_at = NULL,
                                   updated_at = ?
                 WHERE company_id = ? AND id = ? AND deleted_at IS NULL""")
            .params(ts(at), cause, by, ts(at), companyId, id)
            .update();
    }

    /**
     * The filters and the caller's audience of {@code GET /triggers}.
     *
     * @param accountHolderPrincipalId only the triggers whose table holds an account of this principal
     */
    record Filter(@Nullable UUID systemId, @Nullable UUID agentId, @Nullable UUID authorPrincipalId,
            @Nullable UUID teamGroupId, @Nullable TriggerStatus status, boolean all, @Nullable UUID principalId,
            Set<UUID> groupIds, @Nullable UUID accountHolderPrincipalId) {
    }

    /** Live triggers by (name, id) after the position, that the filter's audience sees. */
    List<TriggerInfo> page(UUID companyId, Filter filter, @Nullable String afterName, @Nullable UUID afterId,
            int limit) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("companyId", companyId);
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS
                + " FROM trigger WHERE company_id = :companyId AND deleted_at IS NULL");
        if (!filter.all()) {
            sql.append(" AND (author_principal_id = :principalId");
            params.put("principalId", filter.principalId() != null ? filter.principalId() : new UUID(0, 0));
            if (!filter.groupIds().isEmpty()) {
                sql.append(" OR team_group_id IN (:groupIds)");
                params.put("groupIds", filter.groupIds());
            }
            sql.append(')');
        }
        if (filter.systemId() != null) {
            sql.append(" AND system_id = :systemId");
            params.put("systemId", filter.systemId());
        }
        if (filter.agentId() != null) {
            sql.append(" AND agent_id = :agentId");
            params.put("agentId", filter.agentId());
        }
        if (filter.authorPrincipalId() != null) {
            sql.append(" AND author_principal_id = :authorId");
            params.put("authorId", filter.authorPrincipalId());
        }
        if (filter.teamGroupId() != null) {
            sql.append(" AND team_group_id = :teamId");
            params.put("teamId", filter.teamGroupId());
        }
        if (filter.status() != null) {
            sql.append(" AND status = :status");
            params.put("status", filter.status().name());
        }
        if (filter.accountHolderPrincipalId() != null) {
            sql.append(" AND EXISTS (SELECT 1 FROM trigger_account a WHERE a.trigger_id = trigger.id"
                    + " AND a.company_id = :companyId AND a.holder_principal_id = :holderId)");
            params.put("holderId", filter.accountHolderPrincipalId());
        }
        if (afterName != null && afterId != null) {
            sql.append(" AND (name, id) > (:afterName, :afterId)");
            params.put("afterName", afterName);
            params.put("afterId", afterId);
        }
        sql.append(" ORDER BY name, id LIMIT :limit");
        params.put("limit", limit);
        return jdbc.sql(sql.toString()).params(params).query(TriggerStore::row).list();
    }

    /** The ids of the live triggers the principal authored or whose team is one of the groups. */
    Set<UUID> seenBy(UUID companyId, @Nullable UUID principalId, Collection<UUID> groupIds) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("companyId", companyId);
        params.put("principalId", principalId != null ? principalId : new UUID(0, 0));
        String teams = "";
        if (!groupIds.isEmpty()) {
            teams = " OR team_group_id IN (:groupIds)";
            params.put("groupIds", Set.copyOf(groupIds));
        }
        return Set.copyOf(jdbc.sql("SELECT id FROM trigger WHERE company_id = :companyId"
                + " AND (author_principal_id = :principalId" + teams + ")")
            .params(params)
            .query(UUID.class)
            .list());
    }

    /** Live triggers of the agent, by id (the lock order). */
    List<TriggerInfo> liveOfAgent(UUID companyId, UUID agentId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM trigger WHERE company_id = ? AND agent_id = ? AND deleted_at IS NULL"
                + " ORDER BY id")
            .params(companyId, agentId)
            .query(TriggerStore::row)
            .list();
    }

    /** Live triggers whose source is the system, by id. */
    List<TriggerInfo> liveOnSource(UUID companyId, UUID systemId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM trigger WHERE company_id = ? AND system_id = ?"
                + " AND deleted_at IS NULL ORDER BY id")
            .params(companyId, systemId)
            .query(TriggerStore::row)
            .list();
    }

    /** Live triggers with a row on the system that is not their source, by id. */
    List<UUID> liveWithRowOn(UUID companyId, UUID systemId) {
        return jdbc.sql("""
                SELECT t.id FROM trigger t JOIN trigger_account a ON a.trigger_id = t.id
                 WHERE t.company_id = ? AND a.system_id = ? AND t.deleted_at IS NULL
                   AND t.system_id IS DISTINCT FROM a.system_id
                 ORDER BY t.id""")
            .params(companyId, systemId)
            .query(UUID.class)
            .list();
    }

    /** Live triggers with a row naming the account, by id. */
    List<UUID> liveWithAccount(UUID companyId, UUID accountId) {
        return jdbc.sql("""
                SELECT DISTINCT t.id FROM trigger t JOIN trigger_account a ON a.trigger_id = t.id
                 WHERE t.company_id = ? AND a.account_id = ? AND t.deleted_at IS NULL
                 ORDER BY t.id""")
            .params(companyId, accountId)
            .query(UUID.class)
            .list();
    }

    /** The live triggers whose row on the system names the service account and the credential (ADR-0072), by id. */
    List<UUID> liveWithLending(UUID companyId, UUID systemId, UUID servicePrincipalId, UUID accountId) {
        return jdbc.sql("""
                SELECT t.id FROM trigger t JOIN trigger_account a ON a.trigger_id = t.id
                 WHERE t.company_id = ? AND t.deleted_at IS NULL AND a.system_id = ? AND a.holder_principal_id = ?
                   AND a.account_id = ?
                 ORDER BY t.id""")
            .params(companyId, systemId, servicePrincipalId, accountId)
            .query(UUID.class)
            .list();
    }

    /**
     * The row on the system that still names the service account and the credential it replaced names the one it
     * borrows now, with its identity (ADR-0072): the database checks that it does ({@code trigger_account_holder_ck}).
     * False when the row names something else by now (its author saved another before the trigger's lock).
     */
    boolean followLending(UUID companyId, UUID triggerId, UUID systemId, UUID servicePrincipalId,
            UUID replacedAccountId, UUID accountId, @Nullable String externalId, @Nullable UUID savedBy, Instant at) {
        return jdbc.sql("""
                UPDATE trigger_account SET account_id = ?, external_id = ?, saved_at = ?, saved_by = ?
                 WHERE company_id = ? AND trigger_id = ? AND system_id = ? AND holder_principal_id = ?
                   AND account_id = ?""")
            .params(accountId, externalId, ts(at), savedBy, companyId, triggerId, systemId, servicePrincipalId,
                    replacedAccountId)
            .update() == 1;
    }

    /** How many live triggers' rows name each of the credentials through each service account (ADR-0072). */
    java.util.Map<com.flowtivo.tinfirm.account.CredentialUses.Lending, Integer> liveTriggersByLending(UUID companyId,
            java.util.Collection<UUID> credentialIds) {
        java.util.Map<com.flowtivo.tinfirm.account.CredentialUses.Lending, Integer> counts = new java.util.HashMap<>();
        if (credentialIds.isEmpty()) {
            return counts;
        }
        jdbc.sql("""
                SELECT a.account_id, a.holder_principal_id, count(DISTINCT t.id) AS triggers
                  FROM trigger t JOIN trigger_account a ON a.trigger_id = t.id
                 WHERE t.company_id = ? AND t.deleted_at IS NULL AND a.account_id = ANY (?)
                 GROUP BY a.account_id, a.holder_principal_id""")
            .params(companyId, credentialIds.toArray(UUID[]::new))
            .query((rs, row) -> counts.put(new com.flowtivo.tinfirm.account.CredentialUses.Lending(
                    rs.getObject("account_id", UUID.class), rs.getObject("holder_principal_id", UUID.class)),
                    rs.getInt("triggers")))
            .list();
        return counts;
    }

    /** Live triggers whose row or payer names the principal, or whose author it is, by id. */
    List<UUID> liveNaming(UUID companyId, UUID principalId) {
        return jdbc.sql("""
                SELECT t.id FROM trigger t
                 WHERE t.company_id = ? AND t.deleted_at IS NULL
                   AND (t.payer_principal_id = ? OR t.author_principal_id = ?
                        OR EXISTS (SELECT 1 FROM trigger_account a
                                    WHERE a.trigger_id = t.id AND a.holder_principal_id = ?))
                 ORDER BY t.id""")
            .params(companyId, principalId, principalId, principalId)
            .query(UUID.class)
            .list();
    }

    /** The names of the live triggers whose row or payer names the service identity. */
    List<String> liveNamesUsing(UUID companyId, UUID principalId) {
        return jdbc.sql("""
                SELECT t.name FROM trigger t
                 WHERE t.company_id = ? AND t.deleted_at IS NULL
                   AND (t.payer_principal_id = ?
                        OR EXISTS (SELECT 1 FROM trigger_account a
                                    WHERE a.trigger_id = t.id AND a.holder_principal_id = ?))
                 ORDER BY t.name""")
            .params(companyId, principalId, principalId)
            .query(String.class)
            .list();
    }

    /** Live triggers of the author, by id. */
    List<UUID> liveOfAuthor(UUID companyId, UUID authorPrincipalId) {
        return jdbc.sql("SELECT id FROM trigger WHERE company_id = ? AND author_principal_id = ? AND deleted_at IS NULL"
                + " ORDER BY id")
            .params(companyId, authorPrincipalId)
            .query(UUID.class)
            .list();
    }

    /** Live triggers whose team is the group, by id. */
    List<UUID> liveOfTeam(UUID companyId, UUID groupId) {
        return jdbc.sql("SELECT id FROM trigger WHERE company_id = ? AND team_group_id = ? AND deleted_at IS NULL"
                + " ORDER BY id")
            .params(companyId, groupId)
            .query(UUID.class)
            .list();
    }

    /** Every live trigger of the company, by id (the gate after a change of the model settings). */
    List<UUID> liveIds(UUID companyId) {
        return jdbc.sql("SELECT id FROM trigger WHERE company_id = ? AND deleted_at IS NULL ORDER BY id")
            .params(companyId)
            .query(UUID.class)
            .list();
    }

    /** Live triggers above the firing limit, by id. */
    List<UUID> liveAbove(UUID companyId, int max) {
        return jdbc.sql("SELECT id FROM trigger WHERE company_id = ? AND deleted_at IS NULL"
                + " AND max_firings_per_hour > ? ORDER BY id")
            .params(companyId, max)
            .query(UUID.class)
            .list();
    }

    /**
     * Serialises the creates under one cap until the transaction ends, on every replica: a transaction-scoped advisory
     * lock on the source system's live triggers ({@code trigger_cap:system:{systemId}}), taken before the count and
     * before any row lock of the create (CONVENTIONS §10), so that two creates at the cap never both count one short
     * of it.
     */
    void lockSystemCap(UUID systemId) {
        advisoryLock("trigger_cap:system:" + systemId);
    }

    /** As {@link #lockSystemCap}, for the company's schedules ({@code trigger_cap:schedules:{companyId}}). */
    void lockScheduleCap(UUID companyId) {
        advisoryLock("trigger_cap:schedules:" + companyId);
    }

    private void advisoryLock(String key) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")
            .params(key)
            .query((rs, row) -> 1)
            .list();
    }

    int countLiveOnSystem(UUID companyId, UUID systemId) {
        return jdbc.sql("SELECT count(*) FROM trigger WHERE company_id = ? AND system_id = ? AND deleted_at IS NULL")
            .params(companyId, systemId)
            .query(Integer.class)
            .single();
    }

    int countLiveSchedules(UUID companyId) {
        return jdbc.sql("SELECT count(*) FROM trigger WHERE company_id = ? AND source = 'SCHEDULE'"
                + " AND deleted_at IS NULL")
            .params(companyId)
            .query(Integer.class)
            .single();
    }

    boolean nameTaken(UUID companyId, String name, @Nullable UUID except) {
        return jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM trigger
                                WHERE company_id = ? AND lower(name) = lower(?) AND deleted_at IS NULL
                                  AND id IS DISTINCT FROM ?)""")
            .params(companyId, name, except)
            .query(Boolean.class)
            .single();
    }

    // ---- the account table -----------------------------------------------------------------------------------------

    List<TriggerAccount> accounts(UUID companyId, UUID triggerId) {
        return jdbc.sql("""
                SELECT system_id, account_id, holder_principal_id, external_id, filled_from, saved_at
                  FROM trigger_account WHERE company_id = ? AND trigger_id = ? ORDER BY system_id""")
            .params(companyId, triggerId)
            .query((rs, n) -> new TriggerAccount(rs.getObject("system_id", UUID.class),
                    rs.getObject("account_id", UUID.class), rs.getObject("holder_principal_id", UUID.class),
                    rs.getString("external_id"), TriggerAccount.FilledFrom.valueOf(rs.getString("filled_from")),
                    instant(rs, "saved_at")))
            .list();
    }

    /** The table replaced as a whole (the database checks T1, T2 and T3 on each row, T1 and T2 again at the commit). */
    void replaceAccounts(UUID companyId, UUID triggerId, List<TriggerAccount> rows, @Nullable UUID savedBy) {
        jdbc.sql("DELETE FROM trigger_account WHERE company_id = ? AND trigger_id = ?")
            .params(companyId, triggerId)
            .update();
        for (TriggerAccount row : rows) {
            jdbc.sql("""
                    INSERT INTO trigger_account (company_id, trigger_id, system_id, account_id, holder_principal_id,
                                                 external_id, filled_from, saved_at, saved_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                .params(companyId, triggerId, row.systemId(), row.accountId(), row.holderPrincipalId(),
                        row.externalId(), row.filledFrom().name(), ts(row.savedAt()), savedBy)
                .update();
        }
    }

    // ---- a channel trigger's channels (package 5, V030) ------------------------------------------------------------

    /** A channel of a channel trigger as saved. */
    record ChannelRow(String channelId, boolean isDefault, boolean available, Instant addedAt) {
    }

    /** A live channel trigger's row of a channel, with the trigger. */
    record ChannelOf(UUID triggerId, String triggerName, UUID agentId, boolean isDefault, boolean available) {
    }

    /** Another live trigger's claim on a channel: the same agent, or the channel's default. */
    record ChannelClaim(String channelId, UUID triggerId, String triggerName, boolean sameAgent, boolean isDefault) {
    }

    List<ChannelRow> channels(UUID companyId, UUID triggerId) {
        return jdbc.sql("""
                SELECT channel_id, is_default, state, added_at FROM trigger_channel
                 WHERE company_id = ? AND trigger_id = ? ORDER BY added_at, channel_id""")
            .params(companyId, triggerId)
            .query((rs, n) -> new ChannelRow(rs.getString("channel_id"), rs.getBoolean("is_default"),
                    "ACTIVE".equals(rs.getString("state")), instant(rs, "added_at")))
            .list();
    }

    /**
     * The channels replaced as a whole: those left out go, the others keep their state, the new ones are
     * {@code ACTIVE} unless the chat says now they are not.
     */
    void replaceChannels(UUID companyId, UUID triggerId, UUID systemId, UUID agentId,
            List<TriggerEditor.ChannelInput> channels, Set<String> unavailable, @Nullable UUID addedBy, Instant at) {
        List<String> ids = channels.stream().map(TriggerEditor.ChannelInput::channelId).toList();
        if (ids.isEmpty()) {
            jdbc.sql("DELETE FROM trigger_channel WHERE company_id = ? AND trigger_id = ?")
                .params(companyId, triggerId)
                .update();
            return;
        }
        jdbc.sql("""
                DELETE FROM trigger_channel
                 WHERE company_id = :companyId AND trigger_id = :triggerId AND channel_id NOT IN (:ids)""")
            .param("companyId", companyId)
            .param("triggerId", triggerId)
            .param("ids", ids)
            .update();
        // Defaults given up first, so that a default moving between two triggers' rows never meets itself.
        for (TriggerEditor.ChannelInput channel : channels) {
            if (!channel.isDefault()) {
                jdbc.sql("""
                        UPDATE trigger_channel SET is_default = false
                         WHERE company_id = ? AND trigger_id = ? AND channel_id = ? AND is_default""")
                    .params(companyId, triggerId, channel.channelId())
                    .update();
            }
        }
        for (TriggerEditor.ChannelInput channel : channels) {
            jdbc.sql("""
                    INSERT INTO trigger_channel (company_id, trigger_id, system_id, channel_id, agent_id, is_default,
                                                 state, added_at, added_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (trigger_id, channel_id) DO UPDATE SET is_default = excluded.is_default""")
                .params(companyId, triggerId, systemId, channel.channelId(), agentId, channel.isDefault(),
                        unavailable.contains(channel.channelId()) ? "UNAVAILABLE" : "ACTIVE", ts(at), addedBy)
                .update();
        }
    }

    /**
     * Other live triggers' claims on these channels of the system ({@code CHANNEL_TAKEN}): a row of the same agent,
     * or the channel's default when this one wants it.
     */
    List<ChannelClaim> channelClaims(UUID companyId, UUID systemId, Collection<String> channelIds, UUID agentId,
            @Nullable UUID except) {
        if (channelIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                SELECT c.channel_id, c.trigger_id, t.name, c.agent_id = :agentId AS same_agent, c.is_default
                  FROM trigger_channel c JOIN trigger t ON t.id = c.trigger_id
                 WHERE c.company_id = :companyId AND c.system_id = :systemId AND c.channel_id IN (:ids)
                   AND t.deleted_at IS NULL AND (CAST(:except AS uuid) IS NULL OR c.trigger_id <> :except)
                   AND (c.agent_id = :agentId OR c.is_default)""")
            .param("companyId", companyId)
            .param("systemId", systemId)
            .param("ids", Set.copyOf(channelIds))
            .param("agentId", agentId)
            .param("except", except)
            .query((rs, n) -> new ChannelClaim(rs.getString("channel_id"), rs.getObject("trigger_id", UUID.class),
                    rs.getString("name"), rs.getBoolean("same_agent"), rs.getBoolean("is_default")))
            .list();
    }

    /** The live channel triggers of a channel, the default first. */
    List<ChannelOf> ofChannel(UUID companyId, UUID systemId, String channelId) {
        return jdbc.sql("""
                SELECT c.trigger_id, t.name, c.agent_id, c.is_default, c.state
                  FROM trigger_channel c JOIN trigger t ON t.id = c.trigger_id
                 WHERE c.company_id = ? AND c.system_id = ? AND c.channel_id = ? AND t.deleted_at IS NULL
                 ORDER BY c.is_default DESC, t.name, c.trigger_id""")
            .params(companyId, systemId, channelId)
            .query((rs, n) -> new ChannelOf(rs.getObject("trigger_id", UUID.class), rs.getString("name"),
                    rs.getObject("agent_id", UUID.class), rs.getBoolean("is_default"),
                    "ACTIVE".equals(rs.getString("state"))))
            .list();
    }

    /** A live channel trigger on a channel, with whom it is shown to (its author and team). */
    record OnChannel(String channelId, UUID triggerId, String triggerName, UUID agentId, boolean isDefault,
            UUID authorPrincipalId, @Nullable UUID teamGroupId) {
    }

    /** The live channel triggers on these channels of the system, by channel, the default first. */
    List<OnChannel> onChannels(UUID companyId, UUID systemId, Collection<String> channelIds) {
        if (channelIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                SELECT c.channel_id, c.trigger_id, t.name, c.agent_id, c.is_default, t.author_principal_id,
                       t.team_group_id
                  FROM trigger_channel c JOIN trigger t ON t.id = c.trigger_id
                 WHERE c.company_id = :companyId AND c.system_id = :systemId AND c.channel_id IN (:ids)
                   AND t.deleted_at IS NULL
                 ORDER BY c.channel_id, c.is_default DESC, t.name, c.trigger_id""")
            .param("companyId", companyId)
            .param("systemId", systemId)
            .param("ids", Set.copyOf(channelIds))
            .query((rs, n) -> new OnChannel(rs.getString("channel_id"), rs.getObject("trigger_id", UUID.class),
                    rs.getString("name"), rs.getObject("agent_id", UUID.class), rs.getBoolean("is_default"),
                    rs.getObject("author_principal_id", UUID.class), rs.getObject("team_group_id", UUID.class)))
            .list();
    }

    /**
     * A channel's rows turn {@code ACTIVE} or {@code UNAVAILABLE}: the live triggers whose rows changed. The lock order
     * (CONVENTIONS §10): the channel's triggers are locked first, in the order of their ids, then their rows, as the
     * editor does when it replaces a trigger's channels.
     */
    List<UUID> channelState(UUID companyId, UUID systemId, String channelId, boolean available) {
        List<UUID> triggerIds = jdbc.sql("""
                SELECT DISTINCT trigger_id FROM trigger_channel
                 WHERE company_id = ? AND system_id = ? AND channel_id = ? AND state <> ?
                 ORDER BY trigger_id""")
            .params(companyId, systemId, channelId, available ? "ACTIVE" : "UNAVAILABLE")
            .query(UUID.class)
            .list();
        if (triggerIds.isEmpty()) {
            return List.of();
        }
        for (UUID triggerId : triggerIds) {
            lock(companyId, triggerId);
        }
        // Only the locked triggers' rows: a row added since is its editor's, which sets its state itself.
        return jdbc.sql("""
                UPDATE trigger_channel SET state = :state
                 WHERE company_id = :companyId AND system_id = :systemId AND channel_id = :channelId
                   AND state <> :state AND trigger_id IN (:triggerIds)
                RETURNING trigger_id""")
            .param("state", available ? "ACTIVE" : "UNAVAILABLE")
            .param("companyId", companyId)
            .param("systemId", systemId)
            .param("channelId", channelId)
            .param("triggerIds", triggerIds)
            .query(UUID.class)
            .list();
    }

    void deleteRow(UUID companyId, UUID triggerId, UUID systemId) {
        jdbc.sql("DELETE FROM trigger_account WHERE company_id = ? AND trigger_id = ? AND system_id = ?")
            .params(companyId, triggerId, systemId)
            .update();
    }

    // ---- a webhook trigger's conditions (package 5, V019) -----------------------------------------------------------

    List<WebhookCondition> webhookConditions(UUID companyId, UUID triggerId) {
        return jdbc.sql("SELECT webhook_conditions::text FROM trigger WHERE company_id = ? AND id = ?")
            .params(companyId, triggerId)
            .query(String.class)
            .optional()
            .map(TriggerJson::webhookConditions)
            .orElse(List.of());
    }

    void saveWebhookConditions(UUID companyId, UUID triggerId, List<WebhookCondition> conditions) {
        jdbc.sql("UPDATE trigger SET webhook_conditions = CAST(? AS jsonb) WHERE company_id = ? AND id = ?")
            .params(TriggerJson.webhookConditions(conditions), companyId, triggerId)
            .update();
    }

    // ---- the company's webhook deliveries a minute (package 5, V019) --------------------------------------------

    /** The stored webhook limits, {@code [default, max]}; empty: the instance's configuration. */
    Optional<int[]> webhookLimits(UUID companyId) {
        return jdbc.sql("SELECT webhook_per_minute_default, webhook_per_minute_max FROM trigger_setting"
                + " WHERE company_id = ? AND webhook_per_minute_default IS NOT NULL")
            .params(companyId)
            .query((rs, n) -> new int[] {rs.getInt(1), rs.getInt(2)})
            .optional();
    }

    // ---- the company's switch for subscriptions (ADR-0085, decision 7) -----------------------------------------------

    /** Whether a trigger may pay with its author's subscription; false without a row. */
    boolean subscriptionsOn(UUID companyId) {
        return jdbc.sql("SELECT subscriptions FROM trigger_setting WHERE company_id = ?")
            .params(companyId)
            .query(Boolean.class)
            .optional()
            .orElse(false);
    }

    // ---- the company's firing limits -------------------------------------------------------------------------------

    /** The stored limits, {@code [default, max]}; empty: the instance's configuration. */
    Optional<int[]> limits(UUID companyId) {
        return jdbc.sql("SELECT firings_per_hour_default, firings_per_hour_max FROM trigger_setting WHERE company_id = ?")
            .params(companyId)
            .query((rs, n) -> new int[] {rs.getInt(1), rs.getInt(2)})
            .optional();
    }

    // ---- the company's settings as a whole, under optimistic locking (V060) -----------------------------------------

    /**
     * The company's stored settings, as one row: the values and the version are each other's, which separate reads
     * would not make sure of under READ COMMITTED.
     *
     * @param webhook the webhook limits, {@code [default, max]}, or null for none set (the instance's hold)
     * @param version the count of the settings' changes, 1 or more
     */
    record Settings(int firingsDefault, int firingsMax, int @Nullable [] webhook, boolean subscriptions, int version) {
    }

    /** The stored settings; empty without a row, which is version 0 and the instance's values. */
    Optional<Settings> settings(UUID companyId) {
        return jdbc.sql("SELECT firings_per_hour_default, firings_per_hour_max, webhook_per_minute_default,"
                + " webhook_per_minute_max, subscriptions, version FROM trigger_setting WHERE company_id = ?")
            .params(companyId)
            .query((rs, n) -> {
                Integer webhookDefault = rs.getObject(3, Integer.class);
                Integer webhookMax = rs.getObject(4, Integer.class);
                int[] webhook = webhookDefault != null && webhookMax != null
                        ? new int[] {webhookDefault, webhookMax} : null;
                return new Settings(rs.getInt(1), rs.getInt(2), webhook, rs.getBoolean(5), rs.getInt(6));
            })
            .optional();
    }

    /**
     * Stores the company's settings as a whole, when they are still at {@code version} (0: no row yet): a new row
     * takes version 1, a changed one the next. Nothing is written and false answered when they moved meanwhile.
     *
     * @param webhook the webhook limits, {@code [default, max]}, or null for none set (the instance's hold)
     * @param by      the user who saved them
     */
    boolean saveSettings(UUID companyId, int version, int firingsDefault, int firingsMax, int @Nullable [] webhook,
            boolean subscriptions, @Nullable UUID by, Instant at) {
        Integer webhookDefault = webhook != null ? webhook[0] : null;
        Integer webhookMax = webhook != null ? webhook[1] : null;
        if (version == 0) {
            // A row that appeared meanwhile is at version 1 or more: DO NOTHING, and the change is refused.
            return jdbc.sql("""
                    INSERT INTO trigger_setting (company_id, firings_per_hour_default, firings_per_hour_max,
                                                 webhook_per_minute_default, webhook_per_minute_max, subscriptions,
                                                 version, updated_at, updated_by)
                    VALUES (?, ?, ?, ?, ?, ?, 1, ?, ?)
                    ON CONFLICT (company_id) DO NOTHING""")
                .params(companyId, firingsDefault, firingsMax, webhookDefault, webhookMax, subscriptions, ts(at), by)
                .update() == 1;
        }
        return jdbc.sql("""
                UPDATE trigger_setting
                   SET firings_per_hour_default = ?, firings_per_hour_max = ?, webhook_per_minute_default = ?,
                       webhook_per_minute_max = ?, subscriptions = ?, version = version + 1, updated_at = ?,
                       updated_by = ?
                 WHERE company_id = ? AND version = ?""")
            .params(firingsDefault, firingsMax, webhookDefault, webhookMax, subscriptions, ts(at), by, companyId,
                    version)
            .update() == 1;
    }

    // ---- firings (part b writes them) --------------------------------------------------------------------------------

    List<FiringView> firings(UUID companyId, UUID triggerId, @Nullable Instant afterAt, @Nullable UUID afterId,
            int limit) {
        if (afterAt == null || afterId == null) {
            return jdbc.sql("SELECT " + FIRING_COLUMNS + " FROM trigger_firing WHERE company_id = ? AND trigger_id = ?"
                    + " ORDER BY created_at DESC, id DESC LIMIT ?")
                .params(companyId, triggerId, limit)
                .query(TriggerStore::firing)
                .list();
        }
        return jdbc.sql("SELECT " + FIRING_COLUMNS + " FROM trigger_firing WHERE company_id = ? AND trigger_id = ?"
                + " AND (created_at, id) < (?, ?) ORDER BY created_at DESC, id DESC LIMIT ?")
            .params(companyId, triggerId, ts(afterAt), afterId, limit)
            .query(TriggerStore::firing)
            .list();
    }

    /** The newest firing of each trigger. */
    Map<UUID, FiringView> lastFirings(UUID companyId, Collection<UUID> triggerIds) {
        Map<UUID, FiringView> last = new LinkedHashMap<>();
        if (triggerIds.isEmpty()) {
            return last;
        }
        jdbc.sql("SELECT DISTINCT ON (trigger_id) " + FIRING_COLUMNS + " FROM trigger_firing"
                + " WHERE company_id = :companyId AND trigger_id IN (:ids) ORDER BY trigger_id, created_at DESC, id DESC")
            .param("companyId", companyId)
            .param("ids", Set.copyOf(triggerIds))
            .query(TriggerStore::firing)
            .list()
            .forEach(firing -> last.put(firing.triggerId(), firing));
        return last;
    }

    Optional<FiringView> firing(UUID companyId, UUID firingId) {
        return jdbc.sql("SELECT " + FIRING_COLUMNS + " FROM trigger_firing WHERE company_id = ? AND id = ?")
            .params(companyId, firingId)
            .query(TriggerStore::firing)
            .optional();
    }

    /** The firing's conversation and the turn that took it (part a's {@code FiringConversations}). */
    void firingTaken(UUID companyId, UUID firingId, UUID conversationId, @Nullable UUID runId) {
        jdbc.sql("UPDATE trigger_firing SET conversation_id = ?, run_id = coalesce(run_id, ?) WHERE company_id = ?"
                + " AND id = ?")
            .params(conversationId, runId, companyId, firingId)
            .update();
    }

    /** Whether firings of the conversation wait for a turn ({@code TriggerTurns.waiting}). */
    boolean firingsWaiting(UUID companyId, UUID conversationId) {
        return jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM trigger_firing
                                WHERE company_id = ? AND conversation_id = ? AND run_id IS NULL
                                  AND outcome IN ('STARTED', 'CONTINUED'))""")
            .params(companyId, conversationId)
            .query(Boolean.class)
            .single();
    }

    /** The turn takes the firings that wait ({@code TriggerTurns.taken}). */
    void takeFirings(UUID companyId, UUID conversationId, UUID runId) {
        jdbc.sql("""
                UPDATE trigger_firing SET run_id = ?
                 WHERE company_id = ? AND conversation_id = ? AND run_id IS NULL AND outcome IN ('STARTED', 'CONTINUED')""")
            .params(runId, companyId, conversationId)
            .update();
    }

    /** The requests and the subject's summary of the firings a turn took, oldest first. */
    record Taken(@Nullable String request, @Nullable String subjectSummary, TriggerEvent event, @Nullable String value,
            @Nullable String subjectKey, @Nullable Instant changedAt, @Nullable Instant dueAt,
            @Nullable UUID manualBy, FiringOutcome outcome) {
    }

    List<Taken> taken(UUID companyId, UUID runId) {
        return jdbc.sql("""
                SELECT request, subject_summary, event, value, subject_key, changed_at, due_at, manual_by, outcome
                  FROM trigger_firing WHERE company_id = ? AND run_id = ? ORDER BY created_at, id""")
            .params(companyId, runId)
            .query((rs, n) -> new Taken(rs.getString("request"), rs.getString("subject_summary"),
                    TriggerEvent.valueOf(rs.getString("event")), rs.getString("value"), rs.getString("subject_key"),
                    nullableInstant(rs, "changed_at"), nullableInstant(rs, "due_at"),
                    rs.getObject("manual_by", UUID.class), FiringOutcome.valueOf(rs.getString("outcome"))))
            .list();
    }

    /** How many happened since a time, and the latest of them (null when none did). */
    record Recent(int count, @Nullable Instant latest) {
    }

    /** The firings of the trigger skipped by the chain or the rate since {@code since}. */
    Recent skippedSince(UUID companyId, UUID triggerId, Instant since) {
        return jdbc.sql("""
                SELECT count(*) AS n, max(created_at) AS latest FROM trigger_firing
                 WHERE company_id = ? AND trigger_id = ? AND outcome IN ('SKIPPED_CHAIN', 'SKIPPED_RATE')
                   AND created_at >= ?""")
            .params(companyId, triggerId, ts(since))
            .query((rs, n) -> new Recent(rs.getInt("n"), nullableInstant(rs, "latest")))
            .single();
    }

    // ---- the outbox (part c delivers it) -----------------------------------------------------------------------------

    /** The rows of the conversations that are still to be written: held while their trigger is paused (§5.11). */
    int holdOutbox(UUID companyId, Collection<UUID> conversationIds, Instant at) {
        return outbox("UPDATE outbox SET status = 'HELD', updated_at = :at WHERE company_id = :companyId"
                + " AND conversation_id IN (:ids) AND status = 'PENDING'", companyId, conversationIds, at, null);
    }

    int releaseOutbox(UUID companyId, Collection<UUID> conversationIds, Instant at) {
        return outbox("UPDATE outbox SET status = 'PENDING', updated_at = :at WHERE company_id = :companyId"
                + " AND conversation_id IN (:ids) AND status = 'HELD'", companyId, conversationIds, at, null);
    }

    int dropOutbox(UUID companyId, Collection<UUID> conversationIds, String reason, Instant at) {
        return outbox("UPDATE outbox SET status = 'DROPPED', dropped_reason = :reason, updated_at = :at"
                + " WHERE company_id = :companyId AND conversation_id IN (:ids) AND status IN ('PENDING', 'HELD')",
                companyId, conversationIds, at, reason);
    }

    /** Write-backs of the conversations dropped since {@code since}. */
    Recent droppedSince(UUID companyId, Collection<UUID> conversationIds, Instant since) {
        if (conversationIds.isEmpty()) {
            return new Recent(0, null);
        }
        return jdbc.sql("SELECT count(*) AS n, max(updated_at) AS latest FROM outbox WHERE company_id = :companyId"
                + " AND conversation_id IN (:ids) AND status = 'DROPPED' AND updated_at >= :since")
            .param("companyId", companyId)
            .param("ids", Set.copyOf(conversationIds))
            .param("since", ts(since))
            .query((rs, n) -> new Recent(rs.getInt("n"), nullableInstant(rs, "latest")))
            .single();
    }

    private int outbox(String sql, UUID companyId, Collection<UUID> conversationIds, Instant at,
            @Nullable String reason) {
        if (conversationIds.isEmpty()) {
            return 0;
        }
        JdbcClient.StatementSpec spec = jdbc.sql(sql)
            .param("companyId", companyId)
            .param("ids", Set.copyOf(conversationIds))
            .param("at", ts(at));
        if (reason != null) {
            spec = spec.param("reason", reason);
        }
        return spec.update();
    }

    // ---- polls (part b keeps them) --------------------------------------------------------------------------------

    /** A poll of a system, for {@code GET /systems/{systemId}/polls}. */
    record PollRow(UUID id, UUID accountId, @Nullable UUID holderPrincipalId, String status, @Nullable Instant lastPolledAt,
            Instant nextPollAt, int failures, @Nullable String lastError, @Nullable Instant backoffUntil,
            @Nullable Instant failingSince, @Nullable Integer rateLimit, @Nullable Integer rateRemaining,
            @Nullable Instant rateResetsAt, List<UUID> triggerIds) {
    }

    List<PollRow> polls(UUID companyId, UUID systemId) {
        return jdbc.sql("""
                SELECT p.id, p.account_id, p.holder_principal_id, p.status, p.last_polled_at, p.next_poll_at,
                       p.failures, p.last_error, p.backoff_until, p.failing_since, p.rate_limit, p.rate_remaining,
                       p.rate_resets_at,
                       coalesce((SELECT array_agg(t.id ORDER BY t.name)
                                   FROM trigger t JOIN trigger_account a ON a.trigger_id = t.id
                                  WHERE t.system_id = p.system_id AND a.system_id = p.system_id
                                    AND a.account_id = p.account_id AND t.deleted_at IS NULL), '{}') AS triggers
                  FROM trigger_poll p WHERE p.company_id = ? AND p.system_id = ? ORDER BY p.created_at, p.id""")
            .params(companyId, systemId)
            .query((rs, n) -> {
                List<UUID> triggers = new ArrayList<>();
                for (Object id : (Object[]) rs.getArray("triggers").getArray()) {
                    triggers.add(id instanceof UUID uuid ? uuid : UUID.fromString(id.toString()));
                }
                return new PollRow(rs.getObject("id", UUID.class), rs.getObject("account_id", UUID.class),
                        rs.getObject("holder_principal_id", UUID.class), rs.getString("status"),
                        nullableInstant(rs, "last_polled_at"), instant(rs, "next_poll_at"), rs.getInt("failures"),
                        rs.getString("last_error"), nullableInstant(rs, "backoff_until"),
                        nullableInstant(rs, "failing_since"), (Integer) rs.getObject("rate_limit"),
                        (Integer) rs.getObject("rate_remaining"), nullableInstant(rs, "rate_resets_at"), triggers);
            })
            .list();
    }

    // ---- mapping ---------------------------------------------------------------------------------------------------

    private static TriggerInfo row(ResultSet rs, int rowNum) throws SQLException {
        String cron = rs.getString("schedule_cron");
        String lastOutcome = rs.getString("last_outcome");
        TriggerSchedule schedule = cron == null ? null
                : new TriggerSchedule(cron, rs.getString("schedule_zone"),
                        TriggerSchedule.Overlap.valueOf(rs.getString("overlap")),
                        TriggerSchedule.CatchUp.valueOf(rs.getString("catch_up")), nullableInstant(rs, "next_run_at"),
                        nullableInstant(rs, "last_run_at"),
                        lastOutcome != null ? FiringOutcome.valueOf(lastOutcome) : null);
        String paused = rs.getString("paused_reason");
        String waiting = rs.getString("waiting_reason");
        return new TriggerInfo(rs.getObject("id", UUID.class), rs.getObject("company_id", UUID.class),
                rs.getString("name"), TriggerSource.valueOf(rs.getString("source")),
                rs.getObject("system_id", UUID.class), TriggerJson.events(rs.getString("events")),
                TriggerSubject.valueOf(rs.getString("subject")), rs.getString("scope"),
                TriggerJson.conditions(rs.getString("conditions")), TriggerRepeat.valueOf(rs.getString("repeat")),
                schedule, rs.getObject("agent_id", UUID.class), rs.getString("instruction"),
                rs.getObject("author_principal_id", UUID.class), rs.getObject("team_group_id", UUID.class),
                rs.getObject("payer_principal_id", UUID.class), TriggerJson.results(rs.getString("results")),
                rs.getInt("max_firings_per_hour"), TriggerStatus.valueOf(rs.getString("status")),
                paused != null ? PausedReason.valueOf(paused) : null, nullableInstant(rs, "paused_at"),
                waiting != null ? WaitingReason.valueOf(waiting) : null, nullableInstant(rs, "waiting_since"),
                instant(rs, "armed_at"), rs.getInt("missed_count"), rs.getInt("rate_skipped_count"),
                nullableInstant(rs, "open_events_acknowledged_at"), nullableInstant(rs, "deleted_at"),
                rs.getString("deleted_cause"), instant(rs, "created_at"), instant(rs, "updated_at"));
    }

    private static FiringView firing(ResultSet rs, int rowNum) throws SQLException {
        return new FiringView(rs.getObject("id", UUID.class), rs.getObject("trigger_id", UUID.class),
                rs.getString("subject_id"), rs.getString("subject_key"), TriggerEvent.valueOf(rs.getString("event")),
                rs.getString("value"), nullableInstant(rs, "changed_at"), nullableInstant(rs, "due_at"),
                rs.getObject("manual_by", UUID.class), rs.getBoolean("once"),
                FiringOutcome.valueOf(rs.getString("outcome")), rs.getString("reason"), rs.getInt("chain_depth"),
                rs.getObject("conversation_id", UUID.class), rs.getObject("run_id", UUID.class),
                instant(rs, "created_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, OffsetDateTime.class).toInstant();
    }

    private static @Nullable Instant nullableInstant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value != null ? value.toInstant() : null;
    }

    static @Nullable OffsetDateTime ts(@Nullable Instant instant) {
        return instant != null ? instant.atOffset(ZoneOffset.UTC) : null;
    }
}
