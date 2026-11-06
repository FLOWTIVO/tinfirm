package com.flowtivo.tinfirm.conversation.internal;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.shared.config.TinfirmProperties;
import com.flowtivo.tinfirm.shared.security.SystemContext;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.tx.SystemTransactions;

/**
 * The call journal's sweep (package R, §3.2), on {@code worker} replicas every
 * {@code tinfirm.conversation.tool-journal.sweep-interval} (a minute): in every company (V026's lookup
 * {@code tool_call_due}, ids only), each in its company's tenant context as SYSTEM,
 * <ul>
 * <li>a call claimed longer ago than the tool timeout and {@value ToolJournal#RUNNING_MARGIN_SECONDS} seconds and still
 * {@code RUNNING}, whose host never sent its request again, is settled: an {@code UNSAFE} one {@code INTERRUPTED} and
 * audited ({@code tool.interrupted}, cause {@code RESTART}), a {@code SAFE} one {@code INTERRUPTED} without an audit,
 * claimed again if its request comes back ({@link ToolJournal#settleLost});</li>
 * <li>the rows of runs that ended longer ago than {@code TINFIRM_TOOL_JOURNAL_RETENTION} (seven days) are deleted,
 * and rows claimed longer ago than that whose run's end never reached them (a claim that raced the end), at most
 * {@value #DELETE_BATCH} rows a transaction.</li>
 * </ul>
 * Safe on several replicas at once: each row is settled again under its locks.
 */
@Component
class ToolJournalSweep {

    private static final Logger log = LoggerFactory.getLogger(ToolJournalSweep.class);

    /** How many of each kind one pass looks at. */
    private static final int BATCH = 200;

    /** How many rows one transaction deletes. */
    static final int DELETE_BATCH = 1000;

    private final JdbcClient jdbc;

    private final ToolJournal journal;

    private final ToolCallStore store;

    private final SystemTransactions transactions;

    private final Duration retention;

    private final boolean worker;

    private final TimeSource time;

    ToolJournalSweep(JdbcClient jdbc, ToolJournal journal, ToolCallStore store, SystemTransactions transactions,
            @Value("${tinfirm.conversation.tool-journal.retention:P7D}") Duration retention, TinfirmProperties tinfirm,
            TimeSource time) {
        if (retention.isNegative() || retention.isZero()) {
            throw new IllegalArgumentException("tinfirm.conversation.tool-journal.retention must be positive, not "
                    + retention);
        }
        this.jdbc = jdbc;
        this.journal = journal;
        this.store = store;
        this.transactions = transactions;
        this.retention = retention;
        this.worker = tinfirm.hasRole(TinfirmProperties.WORKER_ROLE);
        this.time = time;
    }

    /** What the lookup found: a lost call, or a company with expired rows. */
    private record Due(String kind, UUID companyId, @Nullable UUID runId, @Nullable UUID requestId) {
    }

    /** What a pass did. */
    record Swept(int settled, int deleted) {
    }

    @Scheduled(fixedDelayString = "${tinfirm.conversation.tool-journal.sweep-interval:PT1M}",
            initialDelayString = "${tinfirm.conversation.tool-journal.sweep-interval:PT1M}")
    void scheduledSweep() {
        if (worker) {
            try {
                sweep(time.now());
            }
            catch (RuntimeException ex) {
                log.warn("The pass over the call journal failed", ex);
            }
        }
    }

    /** One pass as of {@code now}; safe to run on several replicas at once. */
    Swept sweep(Instant now) {
        Instant runningBefore = now.minus(journal.toolTimeout()).minus(ToolJournal.RUNNING_MARGIN);
        Instant endedBefore = now.minus(retention);
        List<Due> due = transactions.unscoped(() -> jdbc.sql("""
                SELECT kind, company_id, run_id, request_id FROM tool_call_due(?, ?, ?)""")
            .params(ts(runningBefore), ts(endedBefore), BATCH)
            .query((rs, row) -> new Due(rs.getString("kind"), rs.getObject("company_id", UUID.class),
                    rs.getObject("run_id", UUID.class), rs.getObject("request_id", UUID.class)))
            .list());
        int settled = 0;
        int deleted = 0;
        for (Due one : due) {
            try {
                if ("RUNNING".equals(one.kind()) && one.runId() != null && one.requestId() != null) {
                    UUID runId = one.runId();
                    UUID requestId = one.requestId();
                    if (SystemContext.callInCompany(one.companyId(),
                            () -> journal.settleLost(one.companyId(), runId, requestId, runningBefore))) {
                        settled++;
                    }
                }
                else if ("EXPIRED".equals(one.kind())) {
                    deleted += SystemContext.callInCompany(one.companyId(), () -> deleteExpired(one.companyId(),
                            endedBefore));
                }
            }
            catch (RuntimeException ex) {
                log.warn("The call journal of company {} could not be swept", one.companyId(), ex);
            }
        }
        return new Swept(settled, deleted);
    }

    /** The company's rows past their retention, {@value #DELETE_BATCH} a transaction. */
    private int deleteExpired(UUID companyId, Instant cutoff) {
        int deleted = 0;
        while (true) {
            int batch = transactions.inCompany(companyId, () -> store.deleteExpired(cutoff, DELETE_BATCH));
            deleted += batch;
            if (batch < DELETE_BATCH) {
                return deleted;
            }
        }
    }

    private static OffsetDateTime ts(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
