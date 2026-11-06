package com.flowtivo.tinfirm.runner.internal;

import java.time.Instant;
import java.util.Collection;
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
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.runner.ConnectedHost;
import com.flowtivo.tinfirm.runner.HostScope;
import com.flowtivo.tinfirm.runner.RunnerMessages;
import com.flowtivo.tinfirm.shared.api.model.RunnerHostPoolSettings;
import com.flowtivo.tinfirm.shared.api.model.RunnerHostPoolWindow;
import com.flowtivo.tinfirm.shared.licence.Feature;
import com.flowtivo.tinfirm.shared.licence.Licensing;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.tx.SystemTransactions;
import com.flowtivo.tinfirm.shared.web.ApiException;
import com.flowtivo.tinfirm.shared.web.ProblemCode;

/**
 * The sandbox pools of company runner hosts (ADR-0099; the Kubernetes runner analysis §6.4, D11), part of elastic
 * runners (ADR-0100, {@code ELASTIC_RUNNERS}): the server evaluates each pool's schedule with the JDK's zone rules and
 * sends the host numbers, the count now and its next change, in {@code host.config.pool}. The host needs no time zone
 * rules.
 * <ul>
 * <li>{@link #update} sets or clears a pool's settings while the licence in force holds the feature, else
 * {@code 403 EDITION_REQUIRED}. A host with the sandbox bridge, as every pool host has, connects only while it
 * does.</li>
 * <li>{@link #refresh} stores the count and its next change as the schedule gives them now, and moves
 * {@code config_version} on when they changed: at the host's {@code hello}, after a change of its settings, and every
 * minute ({@link #tick}).</li>
 * <li>The tick runs on every replica for the hosts connected to it, and sends a host whose count changed its
 * {@code host.config}. Between two ticks the host follows the next change by itself.</li>
 * </ul>
 */
@Component
class RunnerPoolsImpl implements RunnerPools {

    private static final Logger log = LoggerFactory.getLogger(RunnerPoolsImpl.class);

    private static final List<String> WEEK = List.of("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN");

    private final RunnerHostStore store;

    private final RunnerPoolStore pools;

    private final RunnerConnectionRegistry connections;

    private final SystemTransactions transactions;

    private final TimeSource time;

    private final Licensing licensing;

    RunnerPoolsImpl(RunnerHostStore store, RunnerPoolStore pools, RunnerConnectionRegistry connections,
            SystemTransactions transactions, TimeSource time, Licensing licensing) {
        this.store = store;
        this.pools = pools;
        this.connections = connections;
        this.transactions = transactions;
        this.time = time;
        this.licensing = licensing;
    }

    /** The schedule of the stored settings; none (no sandbox waits) without settings. */
    static PoolSchedule schedule(RunnerPoolStore.PoolRow pool) {
        if (pool.settings() == null) {
            return PoolSchedule.none();
        }
        try {
            // Checked against the pool size when stored; a smaller pool since then is the host's to cap.
            return PoolSchedule.of(pool.settings(), Integer.MAX_VALUE);
        }
        catch (PoolSchedule.InvalidSchedule ex) {
            // Only a zone that a later JDK no longer knows: none ready rather than a wrong count.
            log.warn("The pool settings of runner host {} are no longer a schedule ({}); no sandbox waits ready",
                    pool.hostId(), ex.getMessage());
            return PoolSchedule.none();
        }
    }

    @Override
    public boolean refresh(UUID hostId) {
        // As the tick: nothing while the licence in force does not hold the feature; a host keeps the count it has.
        if (!licensing.has(Feature.ELASTIC_RUNNERS)) {
            return false;
        }
        Optional<RunnerPoolStore.PoolRow> pool = pools.pool(hostId).filter(RunnerPoolStore.PoolRow::supported);
        if (pool.isEmpty()) {
            return false;
        }
        PoolSchedule.Evaluation evaluation = schedule(pool.get()).evaluate(time.now());
        PoolSchedule.Change next = evaluation.next();
        return pools.storePoolTarget(hostId, evaluation.readyTarget(), next != null ? next.readyTarget() : null,
                next != null ? next.at() : null);
    }

    /**
     * Every minute, on every replica: the pool hosts connected here get a new {@code host.config} when it changed.
     * Nothing while the licence in force does not hold the feature: a host keeps the count it has.
     */
    @Scheduled(fixedDelayString = "${tinfirm.runner.pool-tick:PT1M}", initialDelayString = "PT30S")
    void tick() {
        if (!licensing.has(Feature.ELASTIC_RUNNERS)) {
            return;
        }
        for (UUID hostId : List.copyOf(connections.connectedHostIds())) {
            if (!connections.supports(hostId, RunnerMessages.FEATURE_SANDBOX_POOL)) {
                continue;
            }
            Optional<ConnectedHost> host = connections.connectedHost(hostId);
            // A member's machine keeps no ready sandboxes for a company (ADR-0099): only company hosts.
            if (host.isEmpty() || host.get().personal() || !host.get().placed()) {
                continue;
            }
            try {
                Optional<RunnerMessages.HostConfig> config = transactions.inCompany(host.get().company(),
                        () -> refresh(hostId)
                                ? store.desiredConfig(RunnerHostStore.Target.companyHost(hostId))
                                    .map(RunnerHostSessions::hostConfig)
                                : Optional.<RunnerMessages.HostConfig>empty());
                config.ifPresent(changed -> connections.send(hostId, changed));
            }
            catch (RuntimeException ex) {
                log.warn("Evaluating the sandbox pool of runner host {} failed; the next tick tries again", hostId, ex);
            }
        }
    }

    @Override
    public @Nullable Change update(RunnerHostStore.HostRow host, @Nullable RunnerHostPoolSettings request,
            int poolSize) {
        licensing.require(Feature.ELASTIC_RUNNERS);
        RunnerPoolStore.PoolRow pool = pools.pool(host.id())
            .filter(row -> host.scope() == HostScope.COMPANY)
            .filter(RunnerPoolStore.PoolRow::supported)
            .orElseThrow(() -> ApiException.conflict(ProblemCode.RUNNER_HOST_POOL_UNSUPPORTED,
                    host.scope() == HostScope.COMPANY
                            ? "This runner host keeps no ready sandboxes: it has not reported the feature "
                                    + "sandbox.pool (a host with a VM per run, ADR-0098)"
                            : "Only a company runner host keeps ready sandboxes"));
        PoolSchedule.Settings settings = null;
        if (request != null) {
            settings = settings(request);
            try {
                PoolSchedule.of(settings, poolSize);
            }
            catch (PoolSchedule.InvalidSchedule ex) {
                throw ApiException.validation(ex.getMessage());
            }
        }
        if (Objects.equals(settings, pool.settings())) {
            return null;
        }
        pools.setPoolSettings(host.id(), settings);
        refresh(host.id());
        return new Change(auditPool(pool.settings()), auditPool(settings));
    }

    @Override
    public Map<UUID, RunnerViews.PoolDetails> details(UUID companyId, Collection<UUID> hostIds) {
        Map<UUID, RunnerViews.PoolDetails> details = new HashMap<>();
        for (RunnerPoolStore.PoolRow pool : pools.pools(companyId, hostIds)) {
            RunnerViews.PoolDetails shown = details(pool);
            if (shown != null) {
                details.put(pool.hostId(), shown);
            }
        }
        return details;
    }

    /** The pool as the host's page shows it, evaluated now; null for a host that keeps no ready sandboxes. */
    RunnerViews.@Nullable PoolDetails details(RunnerPoolStore.@Nullable PoolRow pool) {
        if (pool == null || !pool.supported()) {
            return null;
        }
        Instant now = time.now();
        PoolSchedule.Evaluation evaluation = schedule(pool).evaluate(now);
        PoolSchedule.Change next = evaluation.next();
        RunnerMessages.HostPoolStatus status = pool.status();
        return new RunnerViews.PoolDetails(settings(pool.settings()), pool.maxReadyCeiling(),
                evaluation.readyTarget(), next != null ? change(next) : null,
                evaluation.week().stream().map(RunnerPoolsImpl::change).toList(),
                status != null && pool.statusAt() != null
                        ? new RunnerViews.PoolStatusView(status.ready(), status.starting(), status.busy(),
                                status.coldStartP90Seconds(), status.servedReady(), status.servedCold(),
                                pool.statusAt())
                        : null);
    }

    static RunnerViews.@Nullable PoolSettingsView settings(PoolSchedule.@Nullable Settings settings) {
        if (settings == null) {
            return null;
        }
        return new RunnerViews.PoolSettingsView(settings.zone(), settings.readyDefault(), settings.windows()
            .stream()
            .map(window -> new RunnerViews.PoolWindowView(window.days(), window.from(), window.to(), window.ready(),
                    window.zone()))
            .toList());
    }

    private static RunnerViews.PoolChangeView change(PoolSchedule.Change change) {
        return new RunnerViews.PoolChangeView(change.at(), change.readyTarget());
    }

    /** The pool's settings of the request, its zones stripped. */
    private static PoolSchedule.Settings settings(RunnerHostPoolSettings request) {
        List<PoolSchedule.WindowSettings> windows = request.getWindows()
            .stream()
            .map(window -> new PoolSchedule.WindowSettings(
                    window.getDays().stream().map(RunnerHostPoolWindow.DaysEnum::getValue).sorted(RunnerPoolsImpl::byDay)
                        .toList(),
                    window.getFrom(), window.getTo(), window.getReady(), strip(window.getZone())))
            .toList();
        return new PoolSchedule.Settings(strip(request.getZone()), request.getReadyDefault(), windows);
    }

    private static int byDay(String a, String b) {
        return Integer.compare(WEEK.indexOf(a), WEEK.indexOf(b));
    }

    private static @Nullable String strip(@Nullable String zone) {
        return zone == null || zone.isBlank() ? null : zone.strip();
    }

    /** A pool's settings in the audit: their values, never a person's. */
    private static @Nullable Map<String, Object> auditPool(PoolSchedule.@Nullable Settings settings) {
        if (settings == null) {
            return null;
        }
        Map<String, Object> pool = new LinkedHashMap<>();
        pool.put("zone", settings.zone());
        pool.put("readyDefault", settings.readyDefault());
        pool.put("windows", settings.windows()
            .stream()
            .map(window -> {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("days", window.days());
                entry.put("from", window.from());
                entry.put("to", window.to());
                entry.put("ready", window.ready());
                entry.put("zone", window.zone());
                return entry;
            })
            .toList());
        return pool;
    }
}
