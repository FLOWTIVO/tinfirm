package com.flowtivo.tinfirm.conversation.internal;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

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
 * The calls a restart left behind (CONVENTIONS §15.7, Work after the commit). An approved tool call and the parked
 * calls of a binding just bound run after the commit that set them going, on a thread of the server
 * ({@link ConversationWorkers}); a restart before their record lost them: the decision stayed {@code APPROVED} without
 * a result, telling the agent forever that the call is running, and the parked calls stayed {@code ALLOWED} or
 * {@code RUNNING}, holding their binding's slots; neither turn that should tell them started. Shortly after the server
 * starts and then every {@code call-recovery.interval}, on replicas with the {@code worker} role, a pass finds them in
 * every company (V015's lookup) and handles each in its company, as SYSTEM:
 * <ul>
 * <li>an approved call nobody took to run, approved longer than the tool timeout ago, and a parked call
 * {@code ALLOWED} that long ago, run now, as after their commit; so does one whose token could not be got earlier;</li>
 * <li>an approved or parked call taken to run longer ago than the tool timeout and a margin (any call ends by then)
 * was running when the server stopped: it may have written, so it is never run again, and is settled as of unknown
 * outcome ("Tinfirm restarted during the call");</li>
 * <li>a parked call left {@code PARKED} on a bound binding passes the tools' rule again, as at the bind.</li>
 * </ul>
 * Then the turn that tells them is requested, as their own settling requests it. Each is claimed under its row's lock
 * (the decision's {@code call_started_at}, the parked call's status), so passes on several replicas at once, and a
 * pass beside the thread the commit set going, run a call once.
 */
@Component
class CallRecovery {

    private static final Logger log = LoggerFactory.getLogger(CallRecovery.class);

    /** How many of each kind one pass looks at. */
    private static final int BATCH = 100;

    /**
     * How much longer than the tool timeout a call may take from its claim to its record: the token's refresh and the
     * transactions around the call. A call claimed longer ago than both was lost.
     */
    static final Duration IN_FLIGHT_MARGIN = Duration.ofMinutes(2);

    private final JdbcClient jdbc;

    private final ApprovedCalls approved;

    private final ParkedCalls parked;

    private final SystemTransactions transactions;

    private final Duration toolTimeout;

    private final boolean worker;

    private final TimeSource time;

    CallRecovery(JdbcClient jdbc, ApprovedCalls approved, ParkedCalls parked, SystemTransactions transactions,
            @Value("${tinfirm.run.tool-timeout:PT60S}") Duration toolTimeout, TinfirmProperties tinfirm,
            TimeSource time) {
        this.jdbc = jdbc;
        this.approved = approved;
        this.parked = parked;
        this.transactions = transactions;
        this.toolTimeout = toolTimeout;
        this.worker = tinfirm.hasRole(TinfirmProperties.WORKER_ROLE);
        this.time = time;
    }

    /** What the lookup found: an approved call's decision, or a binding with calls. */
    private record Due(String kind, UUID id, UUID companyId) {
    }

    @Scheduled(fixedDelayString = "${tinfirm.conversation.call-recovery.interval:PT2M}",
            initialDelayString = "${tinfirm.conversation.call-recovery.initial-delay:PT20S}")
    void scheduledRecover() {
        if (worker) {
            try {
                recover();
            }
            catch (RuntimeException ex) {
                log.warn("The pass over the calls a restart left behind failed", ex);
            }
        }
    }

    /**
     * One pass: up to one batch of approved calls and of bindings with calls, each run or settled; safe to run on
     * several replicas at once.
     *
     * @return how many were looked at
     */
    int recover() {
        Instant now = time.now();
        Instant startedBefore = now.minus(toolTimeout);
        Instant runningBefore = startedBefore.minus(IN_FLIGHT_MARGIN);
        List<Due> due = transactions.unscoped(() -> jdbc.sql("""
                SELECT kind, id, company_id FROM conversation_calls_due(?, ?, ?)""")
            .params(ts(startedBefore), ts(runningBefore), BATCH)
            .query((rs, row) -> new Due(rs.getString("kind"), rs.getObject("id", UUID.class),
                    rs.getObject("company_id", UUID.class)))
            .list());
        for (Due one : due) {
            try {
                SystemContext.runInCompany(one.companyId(), () -> {
                    if ("DECISION".equals(one.kind())) {
                        approved.recover(one.companyId(), one.id(), runningBefore);
                    }
                    else {
                        parked.recover(one.companyId(), one.id(), startedBefore, runningBefore);
                    }
                });
            }
            catch (RuntimeException ex) {
                log.warn("The calls of {} {} could not be recovered", one.kind().toLowerCase(java.util.Locale.ROOT),
                        one.id(), ex);
            }
        }
        return due.size();
    }

    private static OffsetDateTime ts(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
