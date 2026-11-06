package com.flowtivo.tinfirm.governance.internal;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.flowtivo.tinfirm.agent.AgentInfo;
import com.flowtivo.tinfirm.agent.Agents;
import com.flowtivo.tinfirm.audit.AuditAction;
import com.flowtivo.tinfirm.audit.AuditLog;
import com.flowtivo.tinfirm.audit.AuditRecord;
import com.flowtivo.tinfirm.conversation.ConversationCosts;
import com.flowtivo.tinfirm.conversation.ConversationInfo;
import com.flowtivo.tinfirm.conversation.Conversations;
import com.flowtivo.tinfirm.governance.Spend.Query;
import com.flowtivo.tinfirm.principal.PrincipalInfo;
import com.flowtivo.tinfirm.principal.PrincipalStatus;
import com.flowtivo.tinfirm.principal.Principals;
import com.flowtivo.tinfirm.shared.access.CompanyAccess;
import com.flowtivo.tinfirm.shared.access.CompanyAccessGuard;
import com.flowtivo.tinfirm.shared.access.CompanyPermission;
import com.flowtivo.tinfirm.shared.access.CompanyRole;
import com.flowtivo.tinfirm.shared.api.model.ConversationCost;
import com.flowtivo.tinfirm.shared.api.model.ConversationTokens;
import com.flowtivo.tinfirm.shared.api.model.ConversationTurnCost;
import com.flowtivo.tinfirm.shared.api.model.SpendAmounts;
import com.flowtivo.tinfirm.shared.api.model.SpendDimension;
import com.flowtivo.tinfirm.shared.api.model.SpendKey;
import com.flowtivo.tinfirm.shared.api.model.SpendReport;
import com.flowtivo.tinfirm.shared.api.model.SpendRow;
import com.flowtivo.tinfirm.shared.api.model.SpendSource;
import com.flowtivo.tinfirm.shared.security.CurrentActor;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.web.ApiException;
import com.flowtivo.tinfirm.shared.web.ProblemCode;

/**
 * {@code Spend.query(caller, from, to, zone, groupBy, filters)} (the package 6 contract, §5.2; G1). The caller's scope
 * comes first, in the query's {@code WHERE}, before any filter or grouping, so that totals count only what the caller
 * sees and no filter reaches past it:
 * <ul>
 * <li>a member: the runs they paid, and the runs of the triggers whose author or team they are;</li>
 * <li>ADMIN, and an instance admin who is not a member: every run; another person's personal conversations are folded
 * into one row per person, without keys, and a {@code conversationId} of one filters to nothing;</li>
 * <li>OWNER: every run; another person's personal conversation by key only.</li>
 * </ul>
 * A title is shown only for the caller's own conversations and for triggers' conversations (which they see, or they
 * would not be counted); a spend view records no read of a conversation. An agent's owner sees nothing of the agent's
 * spend as such (open point 3). The CSV is audited as {@code spend.exported}; reading is not.
 */
@Service
class SpendQueryService {

    static final int MAX_DAYS = 366;

    static final int MAX_ROWS = 10_000;

    static final int MAX_CSV_ROWS = 100_000;

    private final TransactionTemplate transactions;

    private final CompanyAccessGuard companyAccess;

    private final JdbcClient jdbc;

    private final Principals principals;

    private final Agents agents;

    private final Conversations conversations;

    private final TriggerSpend triggers;

    private final AuditLog auditLog;

    private final TimeSource time;

    SpendQueryService(PlatformTransactionManager transactionManager, CompanyAccessGuard companyAccess, JdbcClient jdbc,
            Principals principals, Agents agents, Conversations conversations, TriggerSpend triggers,
            AuditLog auditLog, TimeSource time) {
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setReadOnly(false);
        this.companyAccess = companyAccess;
        this.jdbc = jdbc;
        this.principals = principals;
        this.agents = agents;
        this.conversations = conversations;
        this.triggers = triggers;
        this.auditLog = auditLog;
        this.time = time;
    }

    /** How the caller sees spend. */
    enum View {
        MEMBER, ADMIN, OWNER
    }

    /** The caller and their scope. */
    private record Caller(UUID companyId, View view, @Nullable UUID principalId, Set<UUID> triggers) {
    }

    /** The query, checked. */
    private record Checked(LocalDate from, LocalDate to, ZoneId zone, List<SpendDimension> groupBy,
            SpendReport.BucketEnum bucket) {
    }

    SpendReport report(UUID companyId, Query query) {
        return Objects.requireNonNull(transactions.execute(status -> {
            Caller caller = caller(companyId);
            Checked checked = check(query);
            return build(caller, query, checked, MAX_ROWS);
        }));
    }

    /** The same rows as CSV, audited as {@code spend.exported}. */
    String csv(UUID companyId, Query query) {
        return Objects.requireNonNull(transactions.execute(status -> {
            Caller caller = caller(companyId);
            Checked checked = check(query);
            SpendReport report = build(caller, query, checked, MAX_CSV_ROWS);
            Map<String, Object> filters = new LinkedHashMap<>();
            putIf(filters, "payerId", query.payerId());
            putIf(filters, "actorId", query.actorId());
            putIf(filters, "agentId", query.agentId());
            putIf(filters, "triggerId", query.triggerId());
            putIf(filters, "conversationId", query.conversationId());
            putIf(filters, "model", query.model());
            putIf(filters, "source", query.source() != null ? query.source().getValue() : null);
            auditLog.record(AuditRecord.company(companyId, AuditAction.SPEND_EXPORTED, CurrentActor.actor())
                .detail("filters", filters)
                .detail("groupBy", checked.groupBy().stream().map(SpendDimension::getValue).toList())
                .detail("bucket", checked.bucket().getValue())
                .detail("from", checked.from().toString())
                .detail("to", checked.to().toString())
                .detail("zone", checked.zone().getId())
                .detail("rows", report.getRows().size())
                .build());
            return SpendCsv.of(report);
        }));
    }

    private static void putIf(Map<String, Object> map, String key, @Nullable Object value) {
        if (value != null) {
            map.put(key, value.toString());
        }
    }

    // ---- a conversation's cost ---------------------------------------------------------------------------------------

    /**
     * What the conversation's settled turns cost, each turn once, oldest first: {@code getSpend} with its
     * {@code conversationId} over all time, in the caller's scope (G1). In the caller's transaction (the conversation's
     * read); not audited, as the spend views are not.
     */
    ConversationCost conversationCost(UUID companyId, UUID conversationId) {
        Caller caller = caller(companyId);
        Map<String, Object> params = new HashMap<>();
        StringBuilder where = new StringBuilder("company_id = :company AND conversation_id = :conversationId");
        params.put("company", companyId);
        params.put("conversationId", conversationId);
        scope(caller, where, params);
        notOthersPersonal(caller, where);
        List<TurnRow> rows = jdbc.sql("""
                SELECT run_id, sum(cost_usd) FILTER (WHERE source <> 'SUBSCRIPTION') AS cost_usd,
                       coalesce(sum(input_tokens + output_tokens + cached_input_tokens + cache_write_tokens)
                                FILTER (WHERE source = 'SUBSCRIPTION'), 0) AS subscription_tokens,
                       sum(input_tokens) AS input_tokens, sum(cache_write_tokens) AS cache_write_tokens,
                       sum(cached_input_tokens) AS cached_input_tokens, sum(output_tokens) AS output_tokens,
                       min(source) AS source
                  FROM run_spend
                 WHERE\s""" + where + " GROUP BY run_id ORDER BY min(ended_at), run_id")
            .params(params)
            .query((rs, row) -> new TurnRow(rs.getObject("run_id", UUID.class), rs.getBigDecimal("cost_usd"),
                    rs.getLong("subscription_tokens"), tokens(rs), rs.getString("source")))
            .list();
        BigDecimal cost = BigDecimal.ZERO;
        long subscriptionTokens = 0;
        ConversationTokens tokens = new ConversationTokens(0L, 0L, 0L, 0L);
        List<ConversationTurnCost> turns = new ArrayList<>();
        for (TurnRow row : rows) {
            if (row.costUsd() != null) {
                cost = cost.add(row.costUsd());
            }
            subscriptionTokens += row.subscriptionTokens();
            tokens.inputTokens(tokens.getInputTokens() + row.tokens().getInputTokens())
                .cacheWriteTokens(tokens.getCacheWriteTokens() + row.tokens().getCacheWriteTokens())
                .cachedInputTokens(tokens.getCachedInputTokens() + row.tokens().getCachedInputTokens())
                .outputTokens(tokens.getOutputTokens() + row.tokens().getOutputTokens());
            turns.add(new ConversationTurnCost(row.runId(), SpendSource.fromValue(row.source()))
                .costUsd(row.costUsd() != null ? scale(row.costUsd()) : null));
        }
        return new ConversationCost(scale(cost), tokens, subscriptionTokens, turns);
    }

    /**
     * What each conversation's settled turns cost ({@code GATEWAY} and {@code RELAY}) and their tokens, in the caller's
     * scope (G1), as {@link #conversationCost} counts them; a conversation with nothing the caller sees is absent.
     */
    Map<UUID, ConversationCosts.Total> conversationTotals(UUID companyId, Collection<UUID> conversationIds) {
        if (conversationIds.isEmpty()) {
            return Map.of();
        }
        Caller caller = caller(companyId);
        Map<String, Object> params = new HashMap<>();
        StringBuilder where = new StringBuilder("company_id = :company AND conversation_id IN (:conversationIds)");
        params.put("company", companyId);
        params.put("conversationIds", Set.copyOf(conversationIds));
        scope(caller, where, params);
        notOthersPersonal(caller, where);
        Map<UUID, ConversationCosts.Total> totals = new HashMap<>();
        jdbc.sql("""
                SELECT conversation_id, coalesce(sum(cost_usd) FILTER (WHERE source <> 'SUBSCRIPTION'), 0) AS cost_usd,
                       sum(input_tokens) AS input_tokens, sum(cache_write_tokens) AS cache_write_tokens,
                       sum(cached_input_tokens) AS cached_input_tokens, sum(output_tokens) AS output_tokens
                  FROM run_spend
                 WHERE\s""" + where + " GROUP BY conversation_id")
            .params(params)
            .query(rs -> {
                totals.put(rs.getObject("conversation_id", UUID.class),
                        new ConversationCosts.Total(scale(rs.getBigDecimal("cost_usd")), tokens(rs)));
            });
        return totals;
    }

    /** A row's sums of the four token counts. */
    private static ConversationTokens tokens(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ConversationTokens(rs.getLong("input_tokens"), rs.getLong("cache_write_tokens"),
                rs.getLong("cached_input_tokens"), rs.getLong("output_tokens"));
    }

    /** One settled turn: its cost (null when none of its rows has one), its subscription's tokens and all its tokens. */
    private record TurnRow(UUID runId, @Nullable BigDecimal costUsd, long subscriptionTokens, ConversationTokens tokens,
            String source) {
    }

    // ---- the caller ------------------------------------------------------------------------------------------------

    private Caller caller(UUID companyId) {
        CompanyAccess access = companyAccess.require(companyId, CompanyPermission.VIEW_MODEL_ACCESS);
        UUID userId = CurrentActor.requireUser().id();
        UUID principalId = principals.ofMember(companyId, userId).map(PrincipalInfo::id).orElse(null);
        View view;
        if (access.role() == CompanyRole.OWNER) {
            view = View.OWNER;
        }
        else if (access.role() == CompanyRole.ADMIN || access.role() == null && access.viaInstanceAdmin()) {
            view = View.ADMIN;
        }
        else {
            view = View.MEMBER;
        }
        Set<UUID> audience = view == View.MEMBER && principalId != null
                ? Set.copyOf(triggers.audienceOf(companyId, principalId)) : Set.of();
        return new Caller(companyId, view, principalId, audience);
    }

    private Checked check(Query query) {
        ZoneId zone;
        try {
            zone = query.zone() == null || query.zone().isBlank() ? ZoneId.of("UTC") : ZoneId.of(query.zone());
        }
        catch (DateTimeException ex) {
            throw invalid("Unknown time zone");
        }
        LocalDate today = time.now().atZone(zone).toLocalDate();
        LocalDate from = query.from() != null ? query.from()
                : query.to() != null ? query.to().minusMonths(1) : today.withDayOfMonth(1);
        LocalDate to = query.to() != null ? query.to()
                : query.from() != null ? query.from().plusMonths(1) : today.withDayOfMonth(1).plusMonths(1);
        if (!to.isAfter(from)) {
            throw invalid("`to` must be after `from`");
        }
        if (ChronoUnit.DAYS.between(from, to) > MAX_DAYS) {
            throw invalid("A spend query covers at most " + MAX_DAYS + " days");
        }
        List<SpendDimension> groupBy = query.groupBy() != null ? List.copyOf(query.groupBy()) : List.of();
        if (groupBy.size() > 2) {
            throw invalid("A spend query groups by at most two dimensions");
        }
        if (groupBy.size() == 2 && groupBy.get(0) == groupBy.get(1)) {
            throw invalid("A spend query groups by two different dimensions");
        }
        SpendReport.BucketEnum bucket;
        try {
            bucket = query.bucket() == null ? SpendReport.BucketEnum.DAY : SpendReport.BucketEnum.fromValue(query.bucket());
        }
        catch (IllegalArgumentException ex) {
            throw ApiException.validation("bucket: DAY, WEEK or MONTH");
        }
        return new Checked(from, to, zone, groupBy, bucket);
    }

    private static ApiException invalid(String detail) {
        return ApiException.badRequest(ProblemCode.SPEND_RANGE_INVALID, detail);
    }

    // ---- the query ---------------------------------------------------------------------------------------------------

    private SpendReport build(Caller caller, Query query, Checked checked, int limit) {
        Map<String, Object> params = new HashMap<>();
        StringBuilder where = new StringBuilder("company_id = :company AND ended_at >= :from AND ended_at < :to");
        params.put("company", caller.companyId());
        params.put("from", SpendStore.ts(checked.from().atStartOfDay(checked.zone()).toInstant()));
        params.put("to", SpendStore.ts(checked.to().atStartOfDay(checked.zone()).toInstant()));
        params.put("zone", checked.zone().getId());
        params.put("bucket", checked.bucket().getValue().toLowerCase(java.util.Locale.ROOT));
        // G1: the scope, before every filter and grouping.
        scope(caller, where, params);
        filter(where, params, "payer_principal_id", "payerId", query.payerId());
        filter(where, params, "acting_principal_id", "actorId", query.actorId());
        filter(where, params, "agent_id", "agentId", query.agentId());
        filter(where, params, "trigger_id", "triggerId", query.triggerId());
        filter(where, params, "model", "model", query.model());
        filter(where, params, "source", "source", query.source() != null ? query.source().getValue() : null);
        if (query.conversationId() != null) {
            where.append(" AND conversation_id = :conversationId");
            params.put("conversationId", query.conversationId());
            notOthersPersonal(caller, where);
        }

        List<String> select = new ArrayList<>();
        List<String> group = new ArrayList<>();
        for (int i = 0; i < checked.groupBy().size(); i++) {
            SpendDimension dimension = checked.groupBy().get(i);
            select.add(expression(dimension, caller) + " AS g" + i);
            group.add("g" + i);
            if (dimension == SpendDimension.CONVERSATION && caller.view() == View.ADMIN) {
                select.add("CASE WHEN personal AND acting_principal_id IS DISTINCT FROM CAST(:me AS uuid) "
                        + "THEN acting_principal_id END AS fold" + i);
                group.add("fold" + i);
            }
        }
        String amounts = """
                count(DISTINCT run_id) AS runs, coalesce(sum(requests), 0) AS requests,
                coalesce(sum(input_tokens), 0) AS input_tokens, coalesce(sum(output_tokens), 0) AS output_tokens,
                coalesce(sum(cached_input_tokens), 0) AS cached_input_tokens,
                coalesce(sum(cache_write_tokens), 0) AS cache_write_tokens,
                coalesce(sum(cost_usd) FILTER (WHERE source <> 'SUBSCRIPTION'), 0) AS cost_usd,
                coalesce(sum(cost_usd) FILTER (WHERE source = 'RELAY'), 0) AS relay_cost_usd,
                coalesce(sum(input_tokens + output_tokens + cached_input_tokens + cache_write_tokens)
                         FILTER (WHERE source = 'SUBSCRIPTION'), 0) AS subscription_tokens""";
        SpendAmounts totals = jdbc.sql("SELECT " + amounts + " FROM run_spend WHERE " + where)
            .params(params)
            .query((rs, row) -> amounts(rs))
            .single();

        List<Raw> raws = List.of();
        if (!checked.groupBy().isEmpty()) {
            int period = checked.groupBy().indexOf(SpendDimension.PERIOD);
            String order = (period >= 0 ? "g" + period + ", " : "") + "cost_usd DESC, runs DESC, "
                    + String.join(", ", group);
            int dimensions = checked.groupBy().size();
            raws = jdbc.sql("SELECT " + String.join(", ", select) + ", " + amounts + " FROM run_spend WHERE " + where
                    + " GROUP BY " + String.join(", ", group) + " ORDER BY " + order + " LIMIT " + limit)
                .params(params)
                .query((rs, row) -> {
                    String[] keys = new String[dimensions];
                    UUID[] folds = new UUID[dimensions];
                    for (int i = 0; i < dimensions; i++) {
                        keys[i] = rs.getString("g" + i);
                        if (checked.groupBy().get(i) == SpendDimension.CONVERSATION && caller.view() == View.ADMIN) {
                            folds[i] = rs.getObject("fold" + i, UUID.class);
                        }
                    }
                    return new Raw(keys, folds, amounts(rs));
                })
                .list();
        }
        Labels labels = new Labels(caller, checked.groupBy(), raws);
        List<SpendRow> rows = new ArrayList<>();
        for (Raw raw : raws) {
            List<SpendKey> keys = new ArrayList<>();
            for (int i = 0; i < checked.groupBy().size(); i++) {
                keys.add(labels.key(checked.groupBy().get(i), raw.keys()[i], raw.folds()[i]));
            }
            rows.add(new SpendRow(keys, raw.amounts()));
        }
        return new SpendReport(checked.from(), checked.to(), checked.zone().getId(), new ArrayList<>(checked.groupBy()),
                checked.bucket(), rows, totals);
    }

    private record Raw(String[] keys, @Nullable UUID[] folds, SpendAmounts amounts) {
    }

    /** G1: the runs the caller sees, as the first condition after the company's. */
    private static void scope(Caller caller, StringBuilder where, Map<String, Object> params) {
        params.put("me", caller.principalId());
        if (caller.view() == View.MEMBER) {
            if (caller.principalId() == null) {
                where.append(" AND false");
            }
            else if (caller.triggers().isEmpty()) {
                where.append(" AND payer_principal_id = :me");
            }
            else {
                where.append(" AND (payer_principal_id = :me OR trigger_id IN (:audience))");
                params.put("audience", caller.triggers());
            }
        }
    }

    /** Another person's personal conversation is not ADMIN's: a filter on one finds nothing, as an unknown id. */
    private static void notOthersPersonal(Caller caller, StringBuilder where) {
        if (caller.view() == View.ADMIN) {
            where.append(" AND NOT (personal AND acting_principal_id IS DISTINCT FROM CAST(:me AS uuid))");
        }
    }

    private static void filter(StringBuilder where, Map<String, Object> params, String column, String name,
            @Nullable Object value) {
        if (value != null) {
            where.append(" AND ").append(column).append(" = :").append(name);
            params.put(name, value);
        }
    }

    private static String expression(SpendDimension dimension, Caller caller) {
        return switch (dimension) {
            case PAYER -> "payer_principal_id::text";
            case ACTOR -> "acting_principal_id::text";
            case AGENT -> "agent_id::text";
            case TRIGGER -> "trigger_id::text";
            case MODEL -> "model";
            case CONVERSATION -> caller.view() == View.ADMIN
                    ? "CASE WHEN personal AND acting_principal_id IS DISTINCT FROM CAST(:me AS uuid) THEN NULL "
                            + "ELSE conversation_id::text END"
                    : "conversation_id::text";
            case PERIOD -> "to_char(date_trunc(:bucket, ended_at AT TIME ZONE :zone), 'YYYY-MM-DD')";
        };
    }

    private static SpendAmounts amounts(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new SpendAmounts(rs.getLong("runs"), rs.getLong("requests"), rs.getLong("input_tokens"),
                rs.getLong("output_tokens"), rs.getLong("cached_input_tokens"), rs.getLong("cache_write_tokens"),
                scale(rs.getBigDecimal("cost_usd")), scale(rs.getBigDecimal("relay_cost_usd")),
                rs.getLong("subscription_tokens"));
    }

    private static BigDecimal scale(BigDecimal value) {
        return value.setScale(6, java.math.RoundingMode.HALF_UP).stripTrailingZeros();
    }

    // ---- labels ------------------------------------------------------------------------------------------------------

    /** The labels of the rows' keys, each read once, by the caller's scope. */
    private final class Labels {

        private final Caller caller;

        private final Map<UUID, PrincipalInfo> principalInfo = new HashMap<>();

        private final Map<UUID, String> principalNames = new HashMap<>();

        private final Map<UUID, @Nullable AgentInfo> agentInfo = new HashMap<>();

        private final Map<UUID, String> triggerNames;

        private final Map<UUID, @Nullable ConversationInfo> conversationInfo = new HashMap<>();

        Labels(Caller caller, List<SpendDimension> groupBy, List<Raw> raws) {
            this.caller = caller;
            Set<UUID> people = new HashSet<>();
            Set<UUID> triggerIds = new HashSet<>();
            EnumSet<SpendDimension> principalsDims = EnumSet.of(SpendDimension.PAYER, SpendDimension.ACTOR);
            for (Raw raw : raws) {
                for (int i = 0; i < groupBy.size(); i++) {
                    if (principalsDims.contains(groupBy.get(i)) && raw.keys()[i] != null) {
                        people.add(UUID.fromString(raw.keys()[i]));
                    }
                    if (groupBy.get(i) == SpendDimension.TRIGGER && raw.keys()[i] != null) {
                        triggerIds.add(UUID.fromString(raw.keys()[i]));
                    }
                    if (raw.folds()[i] != null) {
                        people.add(raw.folds()[i]);
                    }
                }
            }
            principalInfo.putAll(principals.findAll(caller.companyId(), people));
            principalNames.putAll(principals.displayNames(caller.companyId(), people));
            triggerNames = triggerIds.isEmpty() ? Map.of() : triggers.names(caller.companyId(), triggerIds);
        }

        SpendKey key(SpendDimension dimension, @Nullable String id, @Nullable UUID fold) {
            SpendKey key = new SpendKey(dimension, false).id(id);
            if (fold != null) {
                return key.folded(true).personPrincipalId(fold)
                    .label("Personal conversations of " + principal(fold));
            }
            if (id == null) {
                return key;
            }
            return switch (dimension) {
                case PAYER, ACTOR -> key.label(principal(UUID.fromString(id)));
                case AGENT -> {
                    AgentInfo agent = agentInfo.computeIfAbsent(UUID.fromString(id),
                            agentId -> agents.find(caller.companyId(), agentId).orElse(null));
                    yield key.label(agent != null ? agent.name() : null);
                }
                case TRIGGER -> key.label(triggerNames.get(UUID.fromString(id)));
                case CONVERSATION -> conversation(key, UUID.fromString(id));
                case MODEL, PERIOD -> key.label(id);
            };
        }

        private String principal(UUID principalId) {
            String name = principalNames.getOrDefault(principalId, "unknown");
            PrincipalInfo info = principalInfo.get(principalId);
            return info != null && info.status() == PrincipalStatus.LEFT ? name + " (left)" : name;
        }

        /**
         * A conversation by its key; its title only when it is the caller's own or a trigger's (G1): another person's
         * personal conversation never shows its title, to OWNER either.
         */
        private SpendKey conversation(SpendKey key, UUID conversationId) {
            ConversationInfo conversation = conversationInfo.computeIfAbsent(conversationId,
                    id -> conversations.find(caller.companyId(), id).orElse(null));
            if (conversation == null) {
                return key;
            }
            key.key(conversation.key()).label(conversation.key());
            boolean personal = conversation.origin() != ConversationInfo.Origin.TRIGGER;
            boolean own = caller.principalId() != null
                    && caller.principalId().equals(conversation.personPrincipalId());
            if (!personal || own) {
                key.title(conversation.title()).label(conversation.key() + " " + conversation.title());
            }
            return key;
        }
    }
}
