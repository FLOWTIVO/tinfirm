package com.flowtivo.tinfirm.governance.internal;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.conversation.ConversationInfo;
import com.flowtivo.tinfirm.conversation.Conversations;
import com.flowtivo.tinfirm.model.BudgetAlerts;
import com.flowtivo.tinfirm.model.ModelAccessChoice;
import com.flowtivo.tinfirm.model.ModelEvents;
import com.flowtivo.tinfirm.model.ModelRelay;
import com.flowtivo.tinfirm.model.SpendLogs;
import com.flowtivo.tinfirm.run.RunEvents;
import com.flowtivo.tinfirm.run.RunInfo;
import com.flowtivo.tinfirm.run.RunReads;
import com.flowtivo.tinfirm.run.RunTokens;
import com.flowtivo.tinfirm.shared.config.TinfirmProperties;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.tx.NoTransaction;
import com.flowtivo.tinfirm.shared.tx.SystemTransactions;

/**
 * The spend sync (the package 6 contract, §5.1; G2, G3), the only writer of {@code run_spend}:
 * <ol>
 * <li>A run's end ({@link RunEvents.RunEnded}, heard in its transaction): a run paid by a budget that relayed a
 * request gets a {@code PENDING} settlement, due {@code settleDelay} later, since LiteLLM writes its logs in batches;
 * a subscription's run that started settles at once, a {@code SUBSCRIPTION} row under {@code run.model} (else
 * {@code default}) with the tokens its sandbox reported and no cost; any other run gets nothing. A request the relay
 * counts after the run ended ({@link ModelEvents.RelayRequestCounted}, heard outside any transaction: the answer was
 * still under way when the run ended) gives the finished run paid by a budget the same settlement when it has none
 * yet (its end saw no request), and makes a pending one due {@code settleDelay} later again, free of any worker's
 * lease ({@code pendingOrDue}); a settled one stays. The relay counts a request on the token row the run's end
 * revoked, so the count waits for the end's transaction, and sees the run finished, or came before the end, which
 * saw it.</li>
 * <li>On {@code worker} replicas every {@code syncInterval}: {@code spend_due} names the due runs; each company's are
 * claimed under a lease of five minutes ({@code FOR UPDATE SKIP LOCKED}), so that two workers never read the same
 * logs. A claimed run with an answer still under way ({@link ModelRelay#answersUnderWay}: admitted by the relay, not
 * ended; a run may end while its answers stream) is tried again later rather than settled without it, until
 * {@code relayAfter} passed since its end, when it is settled anyway (an answer a replica took down with it never
 * ends).</li>
 * <li>Outside any transaction, LiteLLM's spend logs of each key the relay forwarded the claimed runs with, over their
 * span (a minute before the first start to five after the last end), are read once per key
 * ({@link SpendLogs#of}), and each run keeps the entries tagged {@code run:{runId}}.</li>
 * <li>A run with at least as many entries as the relay counted answers settles from them: a {@code GATEWAY} row per
 * model, requests, tokens and LiteLLM's spend summed.</li>
 * <li>Otherwise it is tried again 2, 4, 8… minutes later; once {@code relayAfter} passed since its end, the relay's
 * own count settles it ({@link ModelRelay#usageByModel}), a {@code RELAY} row per model at LiteLLM's prices
 * ({@link SpendLogs#prices}; no cost for a model without one). With {@code tinfirm.spend.source} {@code RELAY} every
 * run settles from the relay at once, and so does every run of a company whose gateway keeps no spend logs Tinfirm
 * reads ({@link SpendLogs#recorded}: one whose keys its gateway team provides, ADR-0094).</li>
 * </ol>
 * Each settlement's rows are written in one transaction that locks it under the worker's lease again; a row is
 * written once ({@code ON CONFLICT DO NOTHING}). The sandbox's report never sets a cost. After a settlement with a
 * cost, the company's month is compared with its alert amount ({@code company_spend_alert}). Public for the tests,
 * which settle on demand; nothing else outside the module calls it.
 */
@Component
public class SpendSync {

    private static final Logger log = LoggerFactory.getLogger(SpendSync.class);

    static final int BATCH = 200;

    private static final Duration LEASE = Duration.ofMinutes(5);

    private static final Duration BEFORE_START = Duration.ofMinutes(1);

    private static final Duration AFTER_END = Duration.ofMinutes(5);

    private static final Duration MAX_BACKOFF = Duration.ofMinutes(32);

    private static final Duration KEEP_SETTLED = Duration.ofDays(30);

    private static final Duration KEEP_ALERTS = Duration.ofDays(400);

    /** The model of a subscription's run whose settings named none: the runtime's default. */
    static final String DEFAULT_MODEL = "default";

    private final SystemTransactions transactions;

    private final SpendStore store;

    private final RunReads runs;

    private final Conversations conversations;

    private final ModelRelay relay;

    private final SpendLogs logs;

    private final BudgetAlerts alerts;

    private final TriggerSpend triggers;

    private final SpendProperties properties;

    private final TinfirmProperties tinfirm;

    private final TimeSource time;

    SpendSync(SystemTransactions transactions, SpendStore store, RunReads runs, Conversations conversations,
            ModelRelay relay, SpendLogs logs, BudgetAlerts alerts, TriggerSpend triggers, SpendProperties properties,
            TinfirmProperties tinfirm, TimeSource time) {
        this.transactions = transactions;
        this.store = store;
        this.runs = runs;
        this.conversations = conversations;
        this.relay = relay;
        this.logs = logs;
        this.alerts = alerts;
        this.triggers = triggers;
        this.properties = properties;
        this.tinfirm = tinfirm;
        this.time = time;
    }

    // ---- 1. a run's end ---------------------------------------------------------------------------------------------

    /** In the transaction that ended the run, in its company's tenant context. */
    @EventListener
    void runEnded(RunEvents.RunEnded ended) {
        RunInfo run = runs.find(ended.companyId(), ended.runId()).orElse(null);
        if (run == null || run.finishedAt() == null) {
            return;
        }
        Instant[] span = span(run);
        Instant started = span[0];
        Instant end = span[1];
        if (run.modelAccess() instanceof ModelAccessChoice.Budget) {
            if (relay.usage(run.companyId(), run.id()).requests() == 0) {
                return;
            }
            store.pending(run.companyId(), run.id(), run.payerPrincipalId(), started, end,
                    time.now().plus(properties.settleDelay()));
            return;
        }
        // A subscription's run (a removed subscription host leaves modelAccess null): not metered, settled now.
        if (run.startedAt() == null) {
            return;
        }
        RunTokens tokens = runs.tokens(run.companyId(), run.id()).orElse(null);
        Instant now = time.now();
        if (tokens == null || !store.settledAtOnce(run.companyId(), run.id(), run.payerPrincipalId(), started, end,
                now)) {
            return;
        }
        String model = tokens.model() != null ? tokens.model() : DEFAULT_MODEL;
        store.insert(row(run, model, end, 0, tokens.inputTokens(), tokens.outputTokens(), tokens.cachedInputTokens(),
                tokens.cacheWriteTokens(), null, "SUBSCRIPTION", now));
    }

    /**
     * Outside any transaction, after the relay counted a request of the run: a run that is finished and paid by a
     * budget gets its {@code PENDING} settlement when it has none yet (its end saw no request: the answer was still
     * under way), and a pending one is due {@code settleDelay} later again, the answer's log still to be written, and
     * free of a worker's lease, so that a settlement under way from the count before the answer is given up and tried
     * again; a run still running gets nothing, its end will; a settled one stays as it is.
     */
    @EventListener
    void relayRequestCounted(ModelEvents.RelayRequestCounted counted) {
        NoTransaction.require("A relayed request's count");
        try {
            transactions.runInCompany(counted.companyId(), () -> {
                RunInfo run = runs.find(counted.companyId(), counted.runId()).orElse(null);
                if (run == null || run.finishedAt() == null
                        || !(run.modelAccess() instanceof ModelAccessChoice.Budget)) {
                    return;
                }
                Instant[] span = span(run);
                store.pendingOrDue(run.companyId(), run.id(), run.payerPrincipalId(), span[0], span[1],
                        time.now().plus(properties.settleDelay()));
            });
        }
        catch (RuntimeException ex) {
            log.warn("The settlement of run {} after a late relayed request was not recorded", counted.runId(), ex);
        }
    }

    /** The run's span as its settlement keeps it: its start (else its creation) to its end, never before the start. */
    private static Instant[] span(RunInfo run) {
        Instant started = run.startedAt() != null ? run.startedAt() : run.createdAt();
        Instant finished = Objects.requireNonNull(run.finishedAt());
        return new Instant[] {started, finished.isBefore(started) ? started : finished};
    }

    // ---- 2. to 5. settling ------------------------------------------------------------------------------------------

    @Scheduled(fixedDelayString = "${tinfirm.spend.sync-interval:PT1M}",
            initialDelayString = "${tinfirm.spend.sync-initial-delay:PT1M}")
    void syncScheduled() {
        if (!tinfirm.hasRole(TinfirmProperties.WORKER_ROLE)) {
            return;
        }
        try {
            syncDue();
        }
        catch (RuntimeException ex) {
            log.warn("The spend sync failed", ex);
        }
    }

    /** Settles the due runs of every company, at most {@value #BATCH}; never inside a transaction. */
    public int syncDue() {
        NoTransaction.require("The spend sync");
        Instant now = time.now();
        List<SpendStore.Due> due = transactions.unscoped(() -> store.due(now, BATCH));
        Map<UUID, List<UUID>> byCompany = new LinkedHashMap<>();
        due.forEach(row -> byCompany.computeIfAbsent(row.companyId(), id -> new ArrayList<>()).add(row.runId()));
        int settled = 0;
        for (Map.Entry<UUID, List<UUID>> company : byCompany.entrySet()) {
            try {
                settled += syncCompany(company.getKey(), company.getValue());
            }
            catch (RuntimeException ex) {
                log.warn("Settling the spend of company {} failed", company.getKey(), ex);
            }
        }
        return settled;
    }

    private int syncCompany(UUID companyId, List<UUID> runIds) {
        Instant now = time.now();
        Instant lease = now.plus(LEASE).truncatedTo(ChronoUnit.MICROS);
        List<SpendStore.Settlement> claimed = transactions.inCompany(companyId,
                () -> store.claim(companyId, runIds, now, lease));
        if (claimed.isEmpty()) {
            return 0;
        }
        Map<UUID, List<ModelRelay.ModelUsage>> counted = new HashMap<>();
        List<SpendStore.Settlement> ready = new ArrayList<>();
        List<SpendStore.Settlement> underWay = new ArrayList<>();
        for (SpendStore.Settlement settlement : claimed) {
            // The answers under way are read before the count, so that a count read complete is complete.
            boolean late = !now.isBefore(settlement.endedAt().plus(properties.relayAfter()));
            long streaming = late ? 0 : transactions.inCompany(companyId,
                    () -> relay.answersUnderWay(companyId, settlement.runId()));
            if (streaming > 0) {
                underWay.add(settlement);
                continue;
            }
            counted.put(settlement.runId(), transactions.inCompany(companyId,
                    () -> relay.usageByModel(companyId, settlement.runId())));
            ready.add(settlement);
        }
        for (SpendStore.Settlement settlement : underWay) {
            try {
                log.debug("Run {} has an answer under way: its spend is settled later", settlement.runId());
                retry(companyId, settlement, lease, null);
            }
            catch (RuntimeException ex) {
                log.warn("Settling the spend of run {} failed; it is tried again", settlement.runId(), ex);
            }
        }
        // A gateway whose keys its team provides keeps no records Tinfirm reads (ADR-0094): the relay's count settles.
        boolean relayOnly = properties.source() == SpendProperties.Source.RELAY
                || !ready.isEmpty() && !logs.recorded(companyId);
        Logs read = relayOnly ? Logs.NONE : readLogs(companyId, ready, counted);
        int settled = 0;
        for (SpendStore.Settlement settlement : ready) {
            List<ModelRelay.ModelUsage> usage = counted.get(settlement.runId());
            List<SpendLogs.Entry> entries = read.of(settlement);
            long answers = usage.stream().mapToLong(ModelRelay.ModelUsage::requests).sum();
            boolean fromLogs = properties.source() == SpendProperties.Source.LITELLM && !entries.isEmpty()
                    && entries.size() >= answers;
            boolean late = !now.isBefore(settlement.endedAt().plus(properties.relayAfter()));
            try {
                if (fromLogs) {
                    settled += settleFromLogs(companyId, settlement, lease, entries) ? 1 : 0;
                }
                else if (relayOnly || late) {
                    settled += settleFromRelay(companyId, settlement, lease, usage) ? 1 : 0;
                }
                else {
                    retry(companyId, settlement, lease, read.error(settlement));
                }
            }
            catch (RuntimeException ex) {
                log.warn("Settling the spend of run {} failed; it is tried again", settlement.runId(), ex);
            }
        }
        transactions.runInCompany(companyId, () -> store.sweep(companyId, now.minus(KEEP_SETTLED),
                now.minus(KEEP_ALERTS)));
        return settled;
    }

    /** LiteLLM's logs of the claimed runs, read once per key, outside any transaction. */
    private Logs readLogs(UUID companyId, List<SpendStore.Settlement> claimed,
            Map<UUID, List<ModelRelay.ModelUsage>> counted) {
        Map<String, Instant[]> spans = new LinkedHashMap<>();
        for (SpendStore.Settlement settlement : claimed) {
            for (ModelRelay.ModelUsage usage : counted.get(settlement.runId())) {
                spans.merge(usage.keyId(), new Instant[] {settlement.startedAt(), settlement.endedAt()},
                        (a, b) -> new Instant[] {min(a[0], b[0]), max(a[1], b[1])});
            }
        }
        Map<String, List<SpendLogs.Entry>> byKey = new HashMap<>();
        Map<String, String> errors = new HashMap<>();
        for (Map.Entry<String, Instant[]> span : spans.entrySet()) {
            try {
                byKey.put(span.getKey(), logs.of(companyId, List.of(span.getKey()),
                        span.getValue()[0].minus(BEFORE_START), span.getValue()[1].plus(AFTER_END)));
            }
            catch (SpendLogs.Unavailable ex) {
                errors.put(span.getKey(), ex.getMessage() != null ? ex.getMessage() : "LiteLLM cannot be read");
            }
        }
        return new Logs(byKey, errors, counted);
    }

    /** The logs read, and why a key's could not be. */
    private record Logs(Map<String, List<SpendLogs.Entry>> byKey, Map<String, String> errors,
            Map<UUID, List<ModelRelay.ModelUsage>> counted) {

        static final Logs NONE = new Logs(Map.of(), Map.of(), Map.of());

        /** The entries tagged with the run, within its span, each once. */
        List<SpendLogs.Entry> of(SpendStore.Settlement settlement) {
            String tag = "run:" + settlement.runId();
            Instant from = settlement.startedAt().minus(BEFORE_START);
            Instant to = settlement.endedAt().plus(AFTER_END);
            Map<String, SpendLogs.Entry> entries = new LinkedHashMap<>();
            for (ModelRelay.ModelUsage usage : counted.getOrDefault(settlement.runId(), List.of())) {
                for (SpendLogs.Entry entry : byKey.getOrDefault(usage.keyId(), List.of())) {
                    boolean inSpan = entry.startTime() == null
                            || !entry.startTime().isBefore(from) && entry.startTime().isBefore(to);
                    if (inSpan && entry.tags().contains(tag)) {
                        entries.putIfAbsent(entry.requestId(), entry);
                    }
                }
            }
            return List.copyOf(entries.values());
        }

        @Nullable String error(SpendStore.Settlement settlement) {
            for (ModelRelay.ModelUsage usage : counted.getOrDefault(settlement.runId(), List.of())) {
                String error = errors.get(usage.keyId());
                if (error != null) {
                    return error;
                }
            }
            return null;
        }
    }

    private boolean settleFromLogs(UUID companyId, SpendStore.Settlement settlement, Instant lease,
            List<SpendLogs.Entry> entries) {
        Map<String, long[]> tokens = new LinkedHashMap<>();
        Map<String, BigDecimal> costs = new HashMap<>();
        for (SpendLogs.Entry entry : entries) {
            String model = model(entry.model());
            long[] sums = tokens.computeIfAbsent(model, ignored -> new long[5]);
            sums[0]++;
            sums[1] += entry.inputTokens();
            sums[2] += entry.outputTokens();
            sums[3] += entry.cachedInputTokens();
            sums[4] += entry.cacheWriteTokens();
            costs.merge(model, entry.spendUsd().max(BigDecimal.ZERO), BigDecimal::add);
        }
        return write(companyId, settlement, lease, tokens, costs, "GATEWAY");
    }

    private boolean settleFromRelay(UUID companyId, SpendStore.Settlement settlement, Instant lease,
            List<ModelRelay.ModelUsage> usage) {
        Map<String, SpendLogs.Price> prices = usage.isEmpty() ? Map.of() : logs.prices(companyId);
        Map<String, long[]> tokens = new LinkedHashMap<>();
        for (ModelRelay.ModelUsage counted : usage) {
            long[] sums = tokens.computeIfAbsent(model(counted.model()), ignored -> new long[5]);
            sums[0] += counted.requests();
            sums[1] += counted.inputTokens();
            sums[2] += counted.outputTokens();
            sums[3] += counted.cachedInputTokens();
            sums[4] += counted.cacheWriteTokens();
        }
        Map<String, BigDecimal> costs = new HashMap<>();
        tokens.forEach((model, sums) -> {
            SpendLogs.Price price = prices.get(model);
            if (price != null) {
                costs.put(model, price.cost(sums[1], sums[2], sums[3], sums[4]));
            }
        });
        return write(companyId, settlement, lease, tokens, costs, "RELAY");
    }

    /** Writes the run's rows and settles it, under the worker's lease; false when the lease was lost. */
    private boolean write(UUID companyId, SpendStore.Settlement claimed, Instant lease, Map<String, long[]> tokens,
            Map<String, BigDecimal> costs, String source) {
        return Boolean.TRUE.equals(transactions.inCompany(companyId, () -> {
            Optional<SpendStore.Settlement> locked = store.lockClaimed(claimed.runId(), lease);
            if (locked.isEmpty()) {
                return false;
            }
            Instant now = time.now();
            RunInfo run = runs.find(companyId, claimed.runId()).orElse(null);
            if (run != null) {
                for (Map.Entry<String, long[]> model : tokens.entrySet()) {
                    long[] sums = model.getValue();
                    BigDecimal cost = costs.get(model.getKey());
                    store.insert(row(run, model.getKey(), locked.get().endedAt(), sums[0], sums[1], sums[2], sums[3],
                            sums[4], cost != null ? cost.setScale(6, java.math.RoundingMode.HALF_UP) : null, source,
                            now));
                }
            }
            store.settle(claimed.runId(), now);
            if (!costs.isEmpty()) {
                checkMonth(companyId, locked.get().endedAt(), now);
            }
            return true;
        }));
    }

    private void retry(UUID companyId, SpendStore.Settlement settlement, Instant lease, @Nullable String error) {
        transactions.runInCompany(companyId, () -> {
            if (store.lockClaimed(settlement.runId(), lease).isEmpty()) {
                return;
            }
            Instant now = time.now();
            int attempts = settlement.attempts() + 1;
            Duration backoff = Duration.ofMinutes(1L << Math.min(attempts, 5));
            if (backoff.compareTo(MAX_BACKOFF) > 0) {
                backoff = MAX_BACKOFF;
            }
            Instant next = now.plus(backoff);
            Instant relayAt = settlement.endedAt().plus(properties.relayAfter());
            store.retry(settlement.runId(), attempts, next.isAfter(relayAt) && now.isBefore(relayAt) ? relayAt : next,
                    error, now);
        });
    }

    /** The company's month crossed its alert amount: recorded once (§5.3, open point 18). */
    private void checkMonth(UUID companyId, Instant endedAt, Instant now) {
        Optional<BigDecimal> alertUsd = alerts.monthlyAlertUsd(companyId);
        if (alertUsd.isEmpty()) {
            return;
        }
        LocalDate month = endedAt.atOffset(ZoneOffset.UTC).toLocalDate().withDayOfMonth(1);
        BigDecimal total = store.cost(companyId, month, month.plusMonths(1));
        if (total.compareTo(alertUsd.get()) >= 0) {
            store.companyAlert(companyId, month, total, alertUsd.get(), now);
        }
    }

    // ---- the row ----------------------------------------------------------------------------------------------------

    private SpendStore.SpendRow row(RunInfo run, String model, Instant endedAt, long requests, long input, long output,
            long cached, long cacheWrite, @Nullable BigDecimal cost, String source, Instant now) {
        ConversationInfo conversation = conversations.find(run.companyId(), run.conversationId()).orElse(null);
        boolean personal = conversation == null || conversation.origin() != ConversationInfo.Origin.TRIGGER;
        return new SpendStore.SpendRow(run.companyId(), run.id(), model(model), run.conversationId(), run.agentId(),
                triggers.triggerOf(run.companyId(), run.id()), run.payerPrincipalId(), run.actingPrincipalId(),
                personal, endedAt, requests, input, output, cached, cacheWrite, cost, source, now);
    }

    private static String model(@Nullable String model) {
        if (model == null || model.isBlank()) {
            return "unknown";
        }
        return model.length() > 200 ? model.substring(0, 200) : model;
    }

    private static Instant min(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }

    private static Instant max(Instant a, Instant b) {
        return a.isAfter(b) ? a : b;
    }
}
