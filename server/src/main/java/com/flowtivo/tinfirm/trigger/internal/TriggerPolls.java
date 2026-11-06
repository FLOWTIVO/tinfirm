package com.flowtivo.tinfirm.trigger.internal;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.account.AccountInfo;
import com.flowtivo.tinfirm.account.Accounts;
import com.flowtivo.tinfirm.shared.config.TinfirmProperties;
import com.flowtivo.tinfirm.shared.security.SystemContext;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.tx.NoTransaction;
import com.flowtivo.tinfirm.shared.tx.SystemTransactions;
import com.flowtivo.tinfirm.system.CallCredential;
import com.flowtivo.tinfirm.system.CallResult;
import com.flowtivo.tinfirm.system.SystemInfo;
import com.flowtivo.tinfirm.system.SystemKind;
import com.flowtivo.tinfirm.system.SystemPolls;
import com.flowtivo.tinfirm.system.Systems;
import com.flowtivo.tinfirm.trigger.TriggerEvent;
import com.flowtivo.tinfirm.trigger.TriggerEventSpec;
import com.flowtivo.tinfirm.trigger.TriggerInfo;
import com.flowtivo.tinfirm.trigger.Triggers;
import com.flowtivo.tinfirm.trigger.WaitingReason;

/**
 * The polls ({@code TriggerPolls}, the package 4 contract, §5.4 with §11, points 8 and 9): on {@code worker} replicas
 * every {@code tinfirm.trigger.poll-tick}, the polls {@code trigger_poll_due} returns, one after the other. A pass of a
 * poll:
 * <ol>
 * <li><b>claims</b> it ({@code FOR UPDATE SKIP LOCKED}, a lease of {@code tinfirm.trigger.lease}: another replica does
 * not poll it meanwhile). Its system paused by an admin (the package 6 contract, §5.4): it calls nothing, waits
 * ({@code WAITING}, no failure, its window held) and goes on at the system's resume, whose switch moved its window to
 * the resume ({@link TriggerRunSwitches}). Then it finds its triggers: live, {@code ACTIVE}, whose source row names its
 * account and whose gate passes, each in a transaction of its own; while the company's runs are stopped the gate makes
 * them wait {@code COMPANY_STOPPED}, and they are polled all the same (§5.4: polls go on, the gate fires nothing and
 * records nothing). None: the poll is {@code IDLE} and its window moves to now;</li>
 * <li><b>reveals</b> the account's token once, outside any transaction ({@code trigger-poll:{pollId}}): a poll sees only
 * what its account sees (T8). An account that cannot be used makes the poll {@code WAITING}, its window held;</li>
 * <li><b>lists</b> the subjects changed since the window, over the union of its triggers' scopes, oldest change first,
 * {@link SystemPolls#PAGE_SIZE} a page, at most {@code tinfirm.trigger.max-per-pass} a pass; GitHub's listings
 * conditional on their ETags. A subject changed within the settle time is left for the next pass; one read again
 * unchanged is skipped;</li>
 * <li><b>handles each subject</b> that changed in a transaction of its own ({@link SubjectHandling}), with its newest
 * comments and a change request's head commit; a failure is named in {@code last_error};</li>
 * <li><b>moves the window</b> ({@link PollWindow}) and sets the next pass after the system's interval, doubled while
 * less than a tenth of the rate limit is left.</li>
 * </ol>
 * The system's answers: a {@code 401} runs {@code Accounts.refused}, the poll waits and the gate pauses its triggers
 * ({@code ACCOUNT_BROKEN}, §11 point 9); a scope that answers {@code 403} or {@code 404} makes its triggers wait
 * {@code SCOPE_UNREADABLE}, the others are read; a rate limit backs off until its reset, at most an hour; {@code 5xx},
 * a timeout or no connection back off 1, 2, 4… minutes up to 30, and after an hour of failures the triggers wait
 * {@code SYSTEM_UNREACHABLE}. The window holds in every one of them.
 */
@Component
class TriggerPolls {

    private static final Logger log = LoggerFactory.getLogger(TriggerPolls.class);

    /** Polls a tick takes at most. */
    static final int BATCH = 50;

    /** The longest backoff after failures. */
    static final Duration MAX_BACKOFF = Duration.ofMinutes(30);

    /** The longest wait for a rate limit's reset. */
    static final Duration MAX_RATE_WAIT = Duration.ofHours(1);

    /** How long a snapshot and a record of a write are kept unread. */
    static final Duration KEPT = Duration.ofDays(90);

    private final PollStore store;

    private final Triggers triggers;

    private final TriggerStore triggerStore;

    private final TriggerGateImpl gate;

    private final TriggerStates states;

    private final SubjectHandling handling;

    private final Accounts accounts;

    private final Systems systems;

    private final SystemPolls systemPolls;

    private final SystemTransactions transactions;

    private final JdbcClient jdbc;

    private final PollProperties properties;

    private final boolean worker;

    private final TimeSource time;

    TriggerPolls(PollStore store, Triggers triggers, TriggerStore triggerStore, TriggerGateImpl gate,
            TriggerStates states, SubjectHandling handling, Accounts accounts, Systems systems,
            SystemPolls systemPolls, SystemTransactions transactions, JdbcClient jdbc, PollProperties properties,
            TinfirmProperties tinfirm, TimeSource time) {
        this.store = store;
        this.triggers = triggers;
        this.triggerStore = triggerStore;
        this.gate = gate;
        this.states = states;
        this.handling = handling;
        this.accounts = accounts;
        this.systems = systems;
        this.systemPolls = systemPolls;
        this.transactions = transactions;
        this.jdbc = jdbc;
        this.properties = properties;
        this.worker = tinfirm.hasRole(TinfirmProperties.WORKER_ROLE);
        this.time = time;
    }

    /**
     * What a pass did.
     *
     * @param status   the poll's status after it
     * @param subjects the subjects it read
     * @param handled  the subjects that changed and were handled
     * @param fired    the firings recorded
     * @param error    the pass's error, if any
     */
    record Outcome(String status, int subjects, int handled, int fired, @Nullable String error) {
    }

    @Scheduled(fixedDelayString = "${tinfirm.trigger.poll-tick:PT15S}",
            initialDelayString = "${tinfirm.trigger.poll-tick:PT15S}")
    void tick() {
        if (worker) {
            try {
                pollDue();
            }
            catch (RuntimeException ex) {
                log.warn("The poll tick failed", ex);
            }
        }
    }

    /** Polls every poll that is due, one after the other; safe on several replicas at once. */
    int pollDue() {
        Instant now = time.now();
        List<UUID[]> due = transactions.unscoped(() -> jdbc.sql(
                "SELECT poll_id, company_id FROM trigger_poll_due(?, ?)")
            .params(TriggerStore.ts(now), BATCH)
            .query((rs, n) -> new UUID[] {rs.getObject("poll_id", UUID.class), rs.getObject("company_id", UUID.class)})
            .list());
        int polled = 0;
        for (UUID[] ref : due) {
            try {
                if (SystemContext.callInCompany(ref[1], () -> poll(ref[1], ref[0], false)) != null) {
                    polled++;
                }
            }
            catch (RuntimeException ex) {
                log.warn("The pass of poll {} failed", ref[0], ex);
            }
        }
        return polled;
    }

    /** The poll of the trigger's source account, if it has one. */
    Optional<UUID> pollOf(UUID companyId, UUID triggerId) {
        return transactions.inCompany(companyId, () -> triggerStore.find(companyId, triggerId)
            .filter(trigger -> trigger.systemId() != null)
            .flatMap(trigger -> triggerStore.accounts(companyId, triggerId).stream()
                .filter(row -> row.systemId().equals(trigger.systemId()) && row.accountId() != null)
                .findFirst()
                .flatMap(row -> store.pollOf(companyId, trigger.systemId(), Objects.requireNonNull(row.accountId()))))
            .map(PollStore.PollRow::id));
    }

    /** What a pass works with, read when it claimed the poll. */
    private record Claim(PollStore.PollRow poll, List<TriggerInfo> triggers, AccountInfo account, Instant startedAt) {
    }

    /**
     * One pass of the poll, outside any transaction, in the company's tenant context.
     *
     * @param force poll although the poll is not due (another pass holding it still wins)
     * @return what it did, or null when it did not run
     */
    @Nullable Outcome poll(UUID companyId, UUID pollId, boolean force) {
        NoTransaction.require("A poll's pass");
        Instant startedAt = time.now();
        PollStore.PollRow poll = transactions.inCompany(companyId, () -> store.claim(companyId, pollId, startedAt,
                startedAt.plus(properties.lease()), force).orElse(null));
        if (poll == null) {
            return null;
        }
        try {
            return pass(companyId, poll, startedAt);
        }
        catch (RuntimeException ex) {
            log.warn("The pass of poll {} failed", pollId, ex);
            transactions.runInCompany(companyId, () -> failed(poll, startedAt, "The poll failed ("
                    + ex.getClass().getSimpleName() + ")", null));
            return new Outcome("ACTIVE", 0, 0, 0, ex.getClass().getSimpleName());
        }
    }

    private Outcome pass(UUID companyId, PollStore.PollRow poll, Instant startedAt) {
        // 0. Its system paused: it calls nothing.
        if (systemPaused(companyId, poll)) {
            return paused(companyId, poll, startedAt);
        }
        // 1. Its triggers, each through the gate in a transaction of its own.
        List<TriggerInfo> candidates = transactions.inCompany(companyId, () -> triggers.onSourceAccount(companyId,
                poll.systemId(), poll.accountId())).stream().filter(TriggerInfo::active).toList();
        List<TriggerInfo> serving = new ArrayList<>();
        for (TriggerInfo candidate : candidates) {
            TriggerInfo passed = transactions.inCompany(companyId, () -> gated(companyId, candidate.id()));
            if (passed != null) {
                serving.add(passed);
            }
        }
        AccountInfo account = transactions.inCompany(companyId, () -> accounts.find(companyId, poll.accountId())
            .orElse(null));
        if (serving.isEmpty() || account == null) {
            transactions.runInCompany(companyId, () -> store.idle(poll.id(), startedAt.minus(properties.overlap()),
                    startedAt.plus(interval(companyId, poll)), time.now()));
            return new Outcome("IDLE", 0, 0, 0, null);
        }
        // 2. The token, once.
        Optional<Accounts.RevealedCredential> revealed = accounts.reveal(companyId, poll.accountId(),
                poll.holderPrincipalId(), "trigger-poll:" + poll.id());
        if (revealed.isEmpty()) {
            transactions.runInCompany(companyId, () -> store.status(poll.id(), "WAITING", "The account cannot be used "
                    + "now: it needs signing in again, or its holder is suspended", startedAt, time.now()));
            return new Outcome("WAITING", 0, 0, 0, "The account cannot be used");
        }
        CallCredential credential = revealed.get().callCredential();
        CallResult<SystemPolls.Session> opened = systemPolls.open(companyId, poll.systemId(), credential);
        if (opened.value() == null) {
            CallResult.Failure failure = Objects.requireNonNull(opened.failure());
            if (failure.kind() == CallResult.Kind.UNAUTHORIZED) {
                return refused(companyId, poll, startedAt, failure.message());
            }
            if (systemPaused(companyId, poll)) {
                return paused(companyId, poll, startedAt);
            }
            transactions.runInCompany(companyId, () -> failed(poll, startedAt, failure.message(), serving));
            return new Outcome("ACTIVE", 0, 0, 0, failure.message());
        }
        return list(companyId, new Claim(poll, serving, account, startedAt), opened.value());
    }

    /**
     * The trigger, locked, when its gate passes; the poll's own waits are kept for the pass to repair. A trigger that
     * waits {@code COMPANY_STOPPED} is polled too: its firings stop at the gate ({@link Firings}).
     */
    private @Nullable TriggerInfo gated(UUID companyId, UUID triggerId) {
        TriggerInfo trigger = triggerStore.lock(companyId, triggerId).filter(TriggerInfo::active).orElse(null);
        if (trigger == null) {
            return null;
        }
        TriggerGateImpl.Verdict verdict = gate.verdict(trigger);
        if (verdict instanceof TriggerGateImpl.Verdict.Passes) {
            if (trigger.waitingReason() == null || pollsOwn(trigger.waitingReason())) {
                return trigger;
            }
            gate.apply(trigger, verdict);
            return triggerStore.find(companyId, triggerId).orElse(null);
        }
        gate.apply(trigger, verdict);
        if (verdict instanceof TriggerGateImpl.Verdict.Wait wait && wait.reason() == WaitingReason.COMPANY_STOPPED) {
            return triggerStore.find(companyId, triggerId).orElse(null);
        }
        return null;
    }

    /** Whether an admin paused the poll's system (the package 6 contract, §5.4). */
    private boolean systemPaused(UUID companyId, PollStore.PollRow poll) {
        return transactions.inCompany(companyId, () -> systems.find(companyId, poll.systemId())
            .map(SystemInfo::paused)
            .orElse(false));
    }

    /** The poll's system is paused: it calls nothing and waits, its window and its failures as they were. */
    private Outcome paused(UUID companyId, PollStore.PollRow poll, Instant startedAt) {
        String why = "The system is paused by an admin";
        transactions.runInCompany(companyId, () -> store.status(poll.id(), "WAITING", why, startedAt, time.now()));
        return new Outcome("WAITING", 0, 0, 0, why);
    }

    private static boolean pollsOwn(WaitingReason reason) {
        return reason == WaitingReason.SYSTEM_UNREACHABLE || reason == WaitingReason.SCOPE_UNREADABLE;
    }

    /** What one listing of the pass read. */
    private static final class Listed {

        final SystemPolls.Listing listing;

        @Nullable Instant newest;

        @Nullable Instant last;

        boolean cut;

        boolean failed;

        final List<String> keys = new ArrayList<>();

        @Nullable String cursor;

        Listed(SystemPolls.Listing listing) {
            this.listing = listing;
        }

        PollWindow.Seen seen() {
            return failed && last == null ? PollWindow.Seen.failure()
                    : new PollWindow.Seen(newest, cut || failed ? last : null, false, keys, cursor);
        }
    }

    private Outcome list(UUID companyId, Claim claim, SystemPolls.Session session) {
        PollStore.PollRow poll = claim.poll();
        Instant startedAt = claim.startedAt();
        Instant cutoff = startedAt.minus(properties.settle());
        Instant armed = claim.triggers().stream().map(TriggerInfo::armedAt).min(Instant::compareTo)
            .orElse(startedAt);
        PollWindow window = poll.since() != null ? new PollWindow(poll.since(), poll.offsetKeys(), poll.cursor())
                : PollWindow.first(armed, properties.overlap());
        boolean unscoped = claim.triggers().stream().anyMatch(trigger -> trigger.scope() == null);
        Set<String> scopes = new LinkedHashSet<>();
        if (!unscoped) {
            claim.triggers().forEach(trigger -> scopes.add(Objects.requireNonNull(trigger.scope())));
        }
        boolean assignedOnly = unscoped && claim.triggers().stream().allMatch(trigger -> trigger.scope() == null
                && trigger.events().stream().map(TriggerEventSpec::event).allMatch(TriggerEvent.ASSIGNED::equals));
        List<SystemPolls.Listing> listings = new ArrayList<>(SystemPolls.listings(session.kind(), scopes,
                assignedOnly));
        EventDetector.Identity identity = new EventDetector.Identity(claim.account().externalId(),
                claim.account().externalLogin());
        String accountName = claim.account().externalLogin() != null ? claim.account().externalLogin()
                : claim.account().externalId();
        SubjectHandling.Pass pass = new SubjectHandling.Pass(companyId, poll.id(), poll.systemId(), claim.triggers(),
                identity, accountName, window.since(), startedAt, startedAt, properties.catchUp(),
                properties.overlap());
        Map<String, String> etags = new LinkedHashMap<>(poll.etags());
        List<Listed> read = new ArrayList<>();
        Set<String> unreadable = new LinkedHashSet<>();
        Set<String> unchanged = new LinkedHashSet<>();
        List<String> problems = new ArrayList<>();
        int remaining = properties.maxPerPass();
        int subjects = 0;
        int handled = 0;
        int fired = 0;
        for (int i = 0; i < listings.size(); i++) {
            SystemPolls.Listing listing = listings.get(i);
            boolean single = listings.size() == 1;
            int budget = Math.max(1, (remaining + listings.size() - i - 1) / (listings.size() - i));
            Listed listed = new Listed(listing);
            SystemPolls.Position position = single ? new SystemPolls.Position(window.offset(), window.cursor())
                    : SystemPolls.Position.START;
            int readHere = 0;
            pages:
            while (true) {
                int limit = Math.min(SystemPolls.PAGE_SIZE, budget - readHere);
                String etagKey = listing.id();
                String etag = position.offset() == 0 ? etags.get(etagKey) : null;
                Instant readAt = time.now();
                CallResult<SystemPolls.Page> page = session.list(listing, window.since(), position, limit, etag);
                if (page.value() == null) {
                    CallResult.Failure failure = Objects.requireNonNull(page.failure());
                    switch (failure.kind()) {
                        case UNAUTHORIZED -> {
                            return refused(companyId, poll, startedAt, failure.message());
                        }
                        case FORBIDDEN, NOT_FOUND -> {
                            if (splitJira(listings, i, listing)) {
                                i--;
                                listed = null;
                                break pages;
                            }
                            listed.failed = true;
                            if (!listing.scopes().isEmpty()) {
                                unreadable.add(listing.scopes().getFirst().toLowerCase(Locale.ROOT));
                            }
                            problems.add(failure.message());
                            break pages;
                        }
                        case INVALID -> {
                            if (splitJira(listings, i, listing)) {
                                i--;
                                listed = null;
                                break pages;
                            }
                            if (listing.kind() == SystemPolls.Listing.Kind.JIRA && listing.scopes().size() == 1) {
                                listed.failed = true;
                                unreadable.add(listing.scopes().getFirst().toLowerCase(Locale.ROOT));
                                problems.add(failure.message());
                                break pages;
                            }
                            return stopped(companyId, poll, startedAt, failure, claim.triggers(), read, window,
                                    session);
                        }
                        default -> {
                            return stopped(companyId, poll, startedAt, failure, claim.triggers(), read, window,
                                    session);
                        }
                    }
                }
                SystemPolls.Page got = page.value();
                if (got.notModified()) {
                    break;
                }
                if (got.etag() != null) {
                    etags.put(etagKey, got.etag());
                }
                Map<String, SubjectSnapshot> known = transactions.inCompany(companyId, () -> store.snapshots(poll.id(),
                        got.subjects().stream().map(SystemPolls.Subject::id).toList()));
                boolean settled = false;
                for (SystemPolls.Subject subject : got.subjects()) {
                    if (subject.updated().isAfter(cutoff)) {
                        settled = true;
                        break;
                    }
                    subjects++;
                    listed.keys.add(subject.key());
                    listed.last = subject.updated();
                    listed.newest = listed.newest == null || subject.updated().isAfter(listed.newest)
                            ? subject.updated() : listed.newest;
                    SubjectSnapshot snapshot = known.get(subject.id());
                    if (snapshot != null && snapshot.sourceUpdatedAt() != null
                            && !subject.updated().isAfter(snapshot.sourceUpdatedAt())) {
                        unchanged.add(subject.id());
                        continue;
                    }
                    CallResult<SystemPolls.Subject> complete = session.complete(subject, snapshot != null
                            ? snapshot.commentsFrom() : window.since());
                    if (complete.value() == null) {
                        CallResult.Failure failure = Objects.requireNonNull(complete.failure());
                        if (failure.kind() == CallResult.Kind.UNAUTHORIZED) {
                            return refused(companyId, poll, startedAt, failure.message());
                        }
                        if (failure.kind() == CallResult.Kind.RATE_LIMITED) {
                            listed.failed = true;
                            read.add(listed);
                            return stopped(companyId, poll, startedAt, failure, claim.triggers(), read, window,
                                    session);
                        }
                        problems.add(subject.key() + ": " + failure.message());
                        continue;
                    }
                    SubjectHandling.Pass at = new SubjectHandling.Pass(pass.companyId(), pass.pollId(),
                            pass.systemId(), pass.triggers(), pass.identity(), pass.account(), pass.bound(), readAt,
                            time.now(), pass.catchUp(), pass.overlap());
                    try {
                        SubjectHandling.Handled done = transactions.inCompany(companyId,
                                () -> handling.handle(at, Objects.requireNonNull(complete.value())));
                        if (done.changed()) {
                            handled++;
                        }
                        fired += done.fired();
                    }
                    catch (RuntimeException ex) {
                        log.warn("Poll {}: handling {} failed", poll.id(), subject.key(), ex);
                        problems.add(subject.key() + ": " + ex.getClass().getSimpleName());
                    }
                }
                readHere += got.count();
                position = got.next();
                listed.cursor = got.next().cursor();
                if (settled || !got.more() || got.count() == 0) {
                    break;
                }
                if (readHere >= budget) {
                    listed.cut = true;
                    break;
                }
            }
            if (listed != null) {
                remaining = Math.max(0, remaining - readHere);
                read.add(listed);
            }
        }
        PollWindow next = PollWindow.next(window, read.stream().map(Listed::seen).toList(), cutoff,
                properties.overlap(), session::precision);
        String error = problems.isEmpty() ? null : String.join("; ", problems);
        int handledCount = handled;
        int firedCount = fired;
        int subjectCount = subjects;
        transactions.runInCompany(companyId, () -> {
            Instant now = time.now();
            store.touch(poll.id(), unchanged, startedAt);
            Duration interval = interval(companyId, poll);
            SystemPolls.RateLimit rate = session.rate();
            Instant nextPoll = now.plus(interval);
            if (rate != null && rate.low() && rate.resetsAt() != null && rate.resetsAt().isAfter(now)) {
                Instant doubled = now.plus(interval.multipliedBy(2));
                nextPoll = doubled.isBefore(rate.resetsAt()) ? doubled : rate.resetsAt();
            }
            store.finish(poll.id(), next, etags, startedAt, nextPoll, 0, null, null, error, rate, now);
            store.sweep(companyId, poll.id(), now.minus(KEPT));
            for (TriggerInfo trigger : claim.triggers()) {
                boolean blind = trigger.scope() != null && unreadable.contains(trigger.scope().toLowerCase(Locale.ROOT));
                waitOrRepair(companyId, trigger.id(), blind ? WaitingReason.SCOPE_UNREADABLE : null);
            }
        });
        return new Outcome("ACTIVE", subjectCount, handledCount, firedCount, error);
    }

    /**
     * A Jira search over several projects that the system refused: it is read again as one search per project, so
     * that only the projects the account cannot read wait.
     */
    private static boolean splitJira(List<SystemPolls.Listing> listings, int index, SystemPolls.Listing listing) {
        if (listing.kind() != SystemPolls.Listing.Kind.JIRA || listing.scopes().size() < 2) {
            return false;
        }
        listings.remove(index);
        List<SystemPolls.Listing> each = listing.scopes().stream()
            .map(scope -> new SystemPolls.Listing(SystemPolls.Listing.Kind.JIRA, List.of(scope), false, false))
            .toList();
        listings.addAll(index, each);
        return true;
    }

    /** The system refused the token (a {@code 401}): the account needs a reconnect, the poll waits (§5.4). */
    private Outcome refused(UUID companyId, PollStore.PollRow poll, Instant startedAt, String message) {
        transactions.runInCompany(companyId, () -> {
            store.status(poll.id(), "WAITING", "The system refused the account's credential: " + message, startedAt,
                    time.now());
            accounts.refused(companyId, poll.accountId(), message);
        });
        return new Outcome("WAITING", 0, 0, 0, message);
    }

    /** A rate limit or a failure stopped the pass: the window holds; it backs off. */
    private Outcome stopped(UUID companyId, PollStore.PollRow poll, Instant startedAt, CallResult.Failure failure,
            List<TriggerInfo> serving, List<Listed> read, PollWindow window, SystemPolls.Session session) {
        transactions.runInCompany(companyId, () -> {
            Instant now = time.now();
            if (failure.kind() == CallResult.Kind.RATE_LIMITED) {
                Duration wait = failure.retryAfter() != null ? failure.retryAfter() : Duration.ofMinutes(1);
                wait = wait.compareTo(MAX_RATE_WAIT) > 0 ? MAX_RATE_WAIT : wait;
                store.finish(poll.id(), window, poll.etags(), startedAt, now.plus(wait), poll.failures(),
                        now.plus(wait), poll.failingSince(), failure.message(), session.rate(), now);
            }
            else {
                failed(poll, startedAt, failure.message(), serving);
            }
        });
        return new Outcome("ACTIVE", 0, 0, 0, failure.message());
    }

    /**
     * A pass that failed: backs off 1, 2, 4… minutes up to 30; after an hour of failures the triggers wait
     * {@code SYSTEM_UNREACHABLE}. The window holds.
     */
    private void failed(PollStore.PollRow poll, Instant startedAt, String message,
            @Nullable List<TriggerInfo> serving) {
        Instant now = time.now();
        int failures = poll.failures() + 1;
        Duration backoff = Duration.ofMinutes(1L << Math.min(failures - 1, 5));
        backoff = backoff.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : backoff;
        Instant failingSince = poll.failingSince() != null ? poll.failingSince() : startedAt;
        PollWindow window = poll.since() != null ? new PollWindow(poll.since(), poll.offsetKeys(), poll.cursor())
                : PollWindow.first(startedAt, properties.overlap());
        store.finish(poll.id(), window, poll.etags(), startedAt, now.plus(backoff), failures, now.plus(backoff),
                failingSince, message, null, now);
        if (serving != null && !failingSince.isAfter(now.minus(properties.unreachableAfter()))) {
            for (TriggerInfo trigger : serving) {
                waitOrRepair(poll.companyId(), trigger.id(), WaitingReason.SYSTEM_UNREACHABLE);
            }
        }
    }

    /**
     * The poll's own wait of the trigger: set it, or repair it when the poll read the trigger's scope again. The
     * company's stop is the wait a trigger shows while it lasts; its lift clears it.
     */
    private void waitOrRepair(UUID companyId, UUID triggerId, @Nullable WaitingReason reason) {
        TriggerInfo trigger = triggerStore.lock(companyId, triggerId).filter(TriggerInfo::active).orElse(null);
        if (trigger == null || trigger.waitingReason() == WaitingReason.COMPANY_STOPPED) {
            return;
        }
        if (reason != null) {
            states.waiting(trigger, reason);
        }
        else if (trigger.waitingReason() != null && pollsOwn(trigger.waitingReason())) {
            states.repaired(trigger);
        }
    }

    private Duration interval(UUID companyId, PollStore.PollRow poll) {
        return systemPolls.interval(companyId, poll.systemId());
    }

    /** For a trigger's system kind: whether it is polled. */
    static boolean polled(@Nullable SystemKind kind) {
        return kind == SystemKind.JIRA_DC || kind == SystemKind.JIRA_CLOUD || kind == SystemKind.GITHUB
                || kind == SystemKind.GITLAB;
    }
}
