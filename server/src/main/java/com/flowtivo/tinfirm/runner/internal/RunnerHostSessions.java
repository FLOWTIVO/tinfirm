package com.flowtivo.tinfirm.runner.internal;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.runner.ConnectedHost;
import com.flowtivo.tinfirm.runner.RunnerMessages;
import com.flowtivo.tinfirm.runner.RuntimeKind;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.tx.SystemTransactions;

/**
 * Database work of the runner endpoint. The token lookup is the only step without a company: it calls the narrow
 * {@code runner_host_for_token} function, which names a company host, or a machine with its presences (ADR-0092).
 * A company host's facts live in its row, in its company's tenant context; a machine's live in its own row, which
 * belongs to no company, so they are written outside a tenant context, and a presence shows them in each company.
 * <p>
 * The credentials a host reports in {@code hello} (its subscriptions and keys, by label) are validated as the
 * protocol says. A machine's are stored ({@code personal_runner_credential}, the package 2 contract §2): they are its
 * owner's model access, and a {@code credential.status} updates them. A company host holds none (M5): the credentials
 * it reports are refused with {@code PERSONAL_HOST_CREDENTIAL_ONLY} and not stored, so that a {@code credential.status}
 * for one of them is {@code UNKNOWN_CREDENTIAL}.
 */
@Component
class RunnerHostSessions {

    private final RunnerHostStore store;

    private final SystemTransactions transactions;

    private final TimeSource time;

    /** Tinfirm's schedule of ready sandboxes, under {@code ee/} (ADR-0100): without it, a pool's count stays. */
    private final ObjectProvider<RunnerPools> pools;

    RunnerHostSessions(RunnerHostStore store, SystemTransactions transactions, TimeSource time,
            ObjectProvider<RunnerPools> pools) {
        this.store = store;
        this.transactions = transactions;
        this.time = time;
        this.pools = pools;
    }

    /** The host of the token: a company host, placed, or a machine with its presences, unplaced. */
    Optional<ConnectedHost> authenticate(String token) {
        String hash = RunnerTokens.hash(token);
        List<RunnerHostStore.TokenOwner> owners = transactions.unscoped(() -> store.hostForToken(hash));
        if (owners.isEmpty()) {
            return Optional.empty();
        }
        RunnerHostStore.TokenOwner first = owners.getFirst();
        if (first.personalHostId() == null) {
            return Optional.of(new ConnectedHost(first.runnerHostId(), first.companyId()));
        }
        Map<UUID, UUID> presences = new LinkedHashMap<>();
        for (RunnerHostStore.TokenOwner owner : owners) {
            if (owner.companyId() != null && owner.runnerHostId() != null) {
                presences.put(owner.companyId(), owner.runnerHostId());
            }
        }
        return Optional.of(ConnectedHost.machine(first.personalHostId(), presences));
    }

    /**
     * The host placed in the company of the run: a company host when the run is its company's, a machine's presence
     * there. Empty for an unknown run, or one of a company the host is not in. A machine whose owner joined a company
     * since the connection was authenticated has a presence there it does not know yet: its presences are read
     * again, and the placed host carries them ({@link ConnectedHost#presences()}).
     */
    Optional<ConnectedHost> placeForRun(ConnectedHost host, UUID runId) {
        Optional<UUID> company = transactions.unscoped(() -> store.runCompany(runId));
        if (company.isEmpty()) {
            return Optional.empty();
        }
        Optional<ConnectedHost> placed = host.in(company.get());
        if (placed.isPresent() || !host.personal()) {
            return placed;
        }
        return refreshed(host).in(company.get());
    }

    /** A machine with its presences as they are now. */
    private ConnectedHost refreshed(ConnectedHost host) {
        UUID machine = Objects.requireNonNull(host.personalHostId());
        Map<UUID, UUID> presences = new LinkedHashMap<>();
        transactions.unscoped(() -> store.presences(machine))
            .forEach(presence -> presences.put(presence.companyId(), presence.runnerHostId()));
        return host.withPresences(presences);
    }

    /** A problem with the hello that the schema cannot express, or null. */
    static @Nullable String helloProblem(RunnerMessages.Hello hello) {
        Set<String> labels = new HashSet<>();
        for (RunnerMessages.HelloCredential credential : hello.credentials()) {
            if (!labels.add(credential.label())) {
                return "Duplicate credential label " + credential.label();
            }
        }
        return null;
    }

    /** The labels of the credentials the hello reports, which {@code credential.status} may name. */
    static Set<String> credentialLabels(RunnerMessages.Hello hello) {
        Set<String> labels = new HashSet<>();
        hello.credentials().forEach(credential -> labels.add(credential.label()));
        return Set.copyOf(labels);
    }

    /**
     * A problem with a {@code host.config.applied} that the schema cannot express, or null (archived v1 CONVENTIONS
     * §14.8): an effective value above its ceiling, or a label listed twice.
     */
    static @Nullable String appliedProblem(RunnerMessages.HostConfigApplied applied) {
        if (applied.maxConcurrentRuns() > applied.maxConcurrentRunsCeiling()) {
            return "maxConcurrentRuns exceeds maxConcurrentRunsCeiling";
        }
        if (applied.pool() != null && applied.pool().readyTarget() > applied.pool().maxReadyCeiling()) {
            return "pool.readyTarget exceeds pool.maxReadyCeiling";
        }
        Set<String> labels = new HashSet<>();
        for (RunnerMessages.AppliedCredential credential : applied.credentials()) {
            if (!labels.add(credential.label())) {
                return "Duplicate credential label " + credential.label();
            }
            if (credential.poolSize() > credential.poolSizeCeiling()) {
                return "The poolSize of " + credential.label() + " exceeds its poolSizeCeiling";
            }
        }
        return null;
    }

    /**
     * What a {@code hello} left: the host's {@code host.config}, and whether its credentials were refused because it
     * is a company host.
     */
    record HelloOutcome(RunnerMessages.HostConfig config, boolean credentialsRefused) {
    }

    /**
     * Records the host facts, its ceiling, the runtimes it can run and its version, and a machine's credentials.
     *
     * @return the host's {@code host.config} as of this transaction; empty when the host has been deleted since
     *         its token was checked
     */
    Optional<HelloOutcome> recordHello(ConnectedHost host, RunnerMessages.Hello hello) {
        return inHostContext(host, () -> {
            Instant now = time.now();
            Set<RuntimeKind> served = EnumSet.noneOf(RuntimeKind.class);
            hello.runtimes().forEach(runtime -> served.add(runtime.runtime()));
            // A personal host keeps no ready sandboxes for the company (ADR-0099): its pool is not stored.
            RunnerHostStore.HelloFacts facts = new RunnerHostStore.HelloFacts(hello.runnerVersion(),
                    hello.hostname(), hello.os(), hello.arch(), hello.maxConcurrentRuns(), served,
                    host.personal() ? null : hello.poolCeiling());
            RunnerHostStore.Target target = target(host);
            if (!store.recordHello(target, facts, now)) {
                return Optional.empty();
            }
            if (!host.personal()) {
                // The count as the schedule gives it now, which may have changed while the host was away.
                pools.ifAvailable(pooling -> pooling.refresh(host.runnerHostId()));
            }
            boolean refused = false;
            if (host.personal()) {
                store.recordCredentials(host.personalHostId(), hello.credentials(), now);
            }
            else {
                refused = !hello.credentials().isEmpty();
            }
            boolean credentialsRefused = refused;
            return store.desiredConfig(target).map(config -> new HelloOutcome(hostConfig(config), credentialsRefused));
        });
    }

    /** The labels a {@code credential.status} may name: a machine's, none of a company host's. */
    static Set<String> acceptedLabels(RunnerMessages.Hello hello, HelloOutcome outcome) {
        return outcome.credentialsRefused() ? Set.of() : credentialLabels(hello);
    }

    /** The host's current {@code host.config}; empty when the host no longer exists. */
    Optional<RunnerMessages.HostConfig> hostConfig(ConnectedHost host) {
        return inHostContext(host, () -> store.desiredConfig(target(host)).map(RunnerHostSessions::hostConfig));
    }

    /**
     * Stores a valid {@code host.config.applied} ({@link #appliedProblem} is null): the effective value, the ceiling
     * and {@code draining} of the host, and the confirmed version. The credential pools it lists are not stored; its
     * ceiling of ready sandboxes is, for a host whose {@code hello} listed {@code sandbox.pool} ({@code poolFeature}).
     *
     * @return false when the host has been deleted
     */
    boolean recordApplied(ConnectedHost host, RunnerMessages.HostConfigApplied applied, boolean poolFeature) {
        Integer poolCeiling = poolFeature && !host.personal() && applied.pool() != null
                ? applied.pool().maxReadyCeiling() : null;
        return inHostContext(host, () -> store.recordApplied(target(host), applied.version(), applied.draining(),
                applied.maxConcurrentRuns(), applied.maxConcurrentRunsCeiling(), poolCeiling));
    }

    /** Stores a company host's latest {@code host.pool.status} (ADR-0099), for its page. */
    void recordPoolStatus(ConnectedHost host, RunnerMessages.HostPoolStatus status) {
        transactions.runInCompany(host.company(),
                () -> store.recordPoolStatus(host.runnerHostId(), status, time.now()));
    }

    /**
     * A {@code credential.status}: an error frame when the label was not reported in the connection's hello (or the
     * host is a company host, whose credentials are not stored); else the machine's credential takes the state.
     */
    Optional<RunnerMessages.ProtocolError> credentialStatus(ConnectedHost host, Set<String> reportedLabels,
            RunnerMessages.CredentialStatus status) {
        if (!reportedLabels.contains(status.label()) || host.personalHostId() == null) {
            return Optional.of(RunnerMessages.ProtocolError.of(RunnerMessages.UNKNOWN_CREDENTIAL,
                    "No credential with this label was reported in hello"));
        }
        Instant cooldownUntil = cooldownUntil(status.cooldownUntil());
        UUID machine = host.personalHostId();
        transactions.unscoped(() -> store.recordCredentialStatus(machine, status.label(), status.status(),
                cooldownUntil, status.detail(), time.now()));
        return Optional.empty();
    }

    /** A cooldown's end as the host reported it (RFC 3339), or null when absent or unreadable. */
    private static @Nullable Instant cooldownUntil(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        }
        catch (DateTimeParseException ex) {
            return null;
        }
    }

    void touch(ConnectedHost host) {
        inHostContext(host, () -> {
            store.touch(target(host), time.now());
            return null;
        });
    }

    /**
     * The {@code host.config} of the stored desired values; it lists no credential pools. A host that keeps ready
     * sandboxes gets the pool's count and its next change as last stored ({@link RunnerPools#refresh}).
     */
    static RunnerMessages.HostConfig hostConfig(RunnerHostStore.DesiredConfig config) {
        RunnerMessages.HostConfigPool pool = null;
        if (config.poolCeiling() != null) {
            RunnerMessages.PoolChange next = config.poolNextTarget() != null && config.poolNextAt() != null
                    ? new RunnerMessages.PoolChange(config.poolNextTarget(), config.poolNextAt().toString())
                    : null;
            pool = new RunnerMessages.HostConfigPool(config.poolReadyTarget(), next);
        }
        return new RunnerMessages.HostConfig(config.version(), config.desiredMaxConcurrentRuns(), List.of(), pool);
    }

    /** The row that holds the host's facts: the machine's, or the company host's own. */
    private static RunnerHostStore.Target target(ConnectedHost host) {
        return host.personalHostId() != null ? RunnerHostStore.Target.machine(host.personalHostId())
                : RunnerHostStore.Target.companyHost(host.runnerHostId());
    }

    /**
     * A company host's row is its company's, so its writes run in that tenant context; a machine's row belongs to no
     * company, so its writes run without one.
     */
    private <T> T inHostContext(ConnectedHost host, java.util.function.Supplier<T> work) {
        return host.personal() ? transactions.unscoped(work) : transactions.inCompany(host.company(), work);
    }
}
