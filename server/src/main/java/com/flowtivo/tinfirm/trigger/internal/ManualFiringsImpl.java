package com.flowtivo.tinfirm.trigger.internal;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.account.AccountBinding;
import com.flowtivo.tinfirm.account.AccountInfo;
import com.flowtivo.tinfirm.account.Accounts;
import com.flowtivo.tinfirm.audit.AuditAction;
import com.flowtivo.tinfirm.audit.AuditLog;
import com.flowtivo.tinfirm.audit.AuditRecord;
import com.flowtivo.tinfirm.principal.PrincipalInfo;
import com.flowtivo.tinfirm.principal.Principals;
import com.flowtivo.tinfirm.shared.security.CurrentActor;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.tx.NoTransaction;
import com.flowtivo.tinfirm.shared.tx.SystemTransactions;
import com.flowtivo.tinfirm.shared.web.ApiException;
import com.flowtivo.tinfirm.shared.web.ProblemCode;
import com.flowtivo.tinfirm.system.CallCredential;
import com.flowtivo.tinfirm.system.CallResult;
import com.flowtivo.tinfirm.system.SystemPolls;
import com.flowtivo.tinfirm.trigger.PausedReason;
import com.flowtivo.tinfirm.trigger.TriggerAccount;
import com.flowtivo.tinfirm.trigger.TriggerEventSpec;
import com.flowtivo.tinfirm.trigger.TriggerGate;
import com.flowtivo.tinfirm.trigger.TriggerInfo;
import com.flowtivo.tinfirm.trigger.TriggerSource;

/**
 * Run now for both sources (the package 4 contract, §5.6, {@code POST /triggers/{triggerId}/run}) and the subjects of
 * the editor's "run now on an issue" ({@code GET /triggers/{triggerId}/subjects}), after part a's API checked the
 * caller, outside any transaction.
 * <ul>
 * <li>A tracker trigger: the gate first, so that a paused trigger, one whose system an admin paused, and one waiting
 * while the company's runs are stopped read nothing ({@code 409 TRIGGER_PAUSED} with the reason); then the subject is
 * read with the source account ({@code trigger-run:{triggerId}}; one it cannot read is
 * {@code 409 SUBJECT_NOT_VISIBLE}); scope, subject kind and conditions are checked on that read
 * ({@code 409 TRIGGER_CONDITIONS_FAIL}, naming the failing clauses); it fires as its first event, {@code manual_by}
 * set, never {@code once}, depth 0, the gate and the rate applied ({@code 409 TRIGGER_PAUSED} when the gate or the
 * agent stops it).</li>
 * <li>A schedule fires once now, with its overlap and without its catch-up; its next firing does not move.</li>
 * </ul>
 * Audited as {@code trigger.run} by the user, and as {@code trigger.fired}.
 */
@Component
class ManualFiringsImpl implements ManualFirings, SubjectSearch {

    /** The subjects the editor's search answers, at most. */
    static final int SEARCH_LIMIT = 20;

    private final TriggerStore triggers;

    private final Firings firings;

    private final TriggerGate gate;

    private final ScheduleFirings schedules;

    private final Accounts accounts;

    private final SystemPolls systemPolls;

    private final Principals principals;

    private final SystemTransactions transactions;

    private final AuditLog auditLog;

    private final TimeSource time;

    ManualFiringsImpl(TriggerStore triggers, Firings firings, TriggerGate gate, ScheduleFirings schedules,
            Accounts accounts, SystemPolls systemPolls, Principals principals, SystemTransactions transactions,
            AuditLog auditLog, TimeSource time) {
        this.triggers = triggers;
        this.firings = firings;
        this.gate = gate;
        this.schedules = schedules;
        this.accounts = accounts;
        this.systemPolls = systemPolls;
        this.principals = principals;
        this.transactions = transactions;
        this.auditLog = auditLog;
        this.time = time;
    }

    @Override
    public FiringView run(UUID companyId, UUID triggerId, @Nullable String subjectKey, UUID userId) {
        NoTransaction.require("Running a trigger now");
        TriggerInfo trigger = transactions.inCompany(companyId, () -> triggers.find(companyId, triggerId))
            .filter(TriggerInfo::live)
            .orElseThrow(TriggerService::notFound);
        if (trigger.source() == TriggerSource.SCHEDULE) {
            return transactions.inCompany(companyId, () -> runSchedule(companyId, triggerId, userId));
        }
        if (trigger.source() == TriggerSource.CHANNEL || trigger.source() == TriggerSource.WEBHOOK) {
            // A mention or a delivery is what fires it (the package 5 contract, §5.7, §5.12): nothing to run now.
            throw ApiException.validation("A " + trigger.source() + " trigger fires on "
                    + (trigger.source() == TriggerSource.CHANNEL ? "a mention of the bot" : "its webhook's deliveries")
                    + ": it cannot run now");
        }
        if (subjectKey == null || subjectKey.isBlank()) {
            throw ApiException.validation("subjectKey is required to run a tracker trigger now");
        }
        TriggerGate.Result gated = transactions.inCompany(companyId, () -> gate.check(companyId, triggerId));
        switch (gated) {
            case TriggerGate.Result.Passes passes -> {
            }
            case TriggerGate.Result.Gone gone -> throw TriggerService.notFound();
            case TriggerGate.Result.Paused paused -> throw ApiException.conflict(ProblemCode.TRIGGER_PAUSED,
                    "The trigger is paused: resume it first", Map.of("details", Map.of("reason",
                            paused.reason().name())));
            case TriggerGate.Result.Waits waits -> throw ApiException.conflict(ProblemCode.TRIGGER_PAUSED,
                    "The trigger cannot fire now: " + Firings.gateWords(waits), Map.of("details", Map.of("reason",
                            waits.reason().name())));
        }
        Source source = transactions.inCompany(companyId, () -> source(companyId, trigger));
        Optional<Accounts.RevealedCredential> revealed = accounts.reveal(companyId, source.account().id(),
                source.row().holderPrincipalId(), "trigger-run:" + triggerId);
        if (revealed.isEmpty()) {
            throw ApiException.conflict(ProblemCode.SUBJECT_NOT_VISIBLE, "The trigger's account on its system cannot "
                    + "be used now: it needs signing in again, or its holder is suspended");
        }
        CallCredential credential = revealed.get().callCredential();
        CallResult<SystemPolls.Session> opened = systemPolls.open(companyId, source.row().systemId(), credential);
        CallResult<SystemPolls.Subject> read = opened.value() != null ? opened.value().read(subjectKey.strip())
                : opened.failedAs();
        if (read.value() == null) {
            CallResult.Failure failure = Objects.requireNonNull(read.failure());
            if (failure.kind() == CallResult.Kind.UNAUTHORIZED) {
                transactions.runInCompany(companyId, () -> accounts.refused(companyId, source.account().id(),
                        failure.message()));
            }
            throw ApiException.conflict(ProblemCode.SUBJECT_NOT_VISIBLE, "The trigger's account cannot read "
                    + subjectKey.strip() + ": " + failure.message());
        }
        SystemPolls.Subject subject = read.value();
        String userName = transactions.inCompany(companyId, () -> principals.ofMember(companyId, userId)
            .map(PrincipalInfo::name).orElse(null));
        return transactions.inCompany(companyId, () -> {
            TriggerInfo locked = triggers.lock(companyId, triggerId).filter(TriggerInfo::live)
                .orElseThrow(TriggerService::notFound);
            if (locked.paused()) {
                throw ApiException.conflict(ProblemCode.TRIGGER_PAUSED, "The trigger is paused: resume it first",
                        Map.of("details", Map.of("reason", String.valueOf(locked.pausedReason()))));
            }
            TriggerEvaluator.Facts facts = TriggerEvaluator.Facts.of(subject, false);
            List<String> failing = TriggerEvaluator.failing(locked, subject, facts);
            if (!failing.isEmpty()) {
                throw ApiException.conflict(ProblemCode.TRIGGER_CONDITIONS_FAIL, "The trigger would not fire on "
                        + subject.key() + ": " + String.join("; ", failing),
                        Map.of("details", Map.of("failing", failing)));
            }
            TriggerEventSpec first = locked.events().getFirst();
            Instant now = time.now();
            EventDetector.Event event = new EventDetector.Event(first.event(), first.value(), now, null, null);
            Firings.Fired fired = firings.fire(new Firings.SubjectEvent(locked, source.row().systemId(), subject,
                    event, 0, login(source.account()), userId, userName != null ? userName : "a member"));
            if (fired.firingId() == null) {
                throw ApiException.conflict(ProblemCode.TRIGGER_PAUSED, "The trigger did not fire: "
                        + Objects.requireNonNullElse(fired.stopped(), "it was skipped"),
                        Map.of("details", Map.of("reason", Objects.requireNonNullElse(fired.stopped(), "SKIPPED"))));
            }
            FiringView view = triggers.firing(companyId, fired.firingId()).orElseThrow();
            auditRun(locked, view);
            return view;
        });
    }

    private FiringView runSchedule(UUID companyId, UUID triggerId, UUID userId) {
        TriggerInfo locked = triggers.lock(companyId, triggerId).filter(TriggerInfo::live)
            .orElseThrow(TriggerService::notFound);
        if (locked.paused()) {
            throw ApiException.conflict(ProblemCode.TRIGGER_PAUSED, "The trigger is paused: resume it first",
                    Map.of("details", Map.of("reason", String.valueOf(locked.pausedReason()))));
        }
        ScheduleFirings.Outcome outcome = schedules.fire(locked, time.now(), userId, false);
        if (outcome.firingId() == null) {
            throw ApiException.conflict(ProblemCode.TRIGGER_PAUSED, "The trigger cannot fire now: "
                    + outcome.stopped(), Map.of("details", Map.of("reason", String.valueOf(outcome.stopped()))));
        }
        FiringView view = triggers.firing(companyId, outcome.firingId()).orElseThrow();
        auditRun(locked, view);
        return view;
    }

    private void auditRun(TriggerInfo trigger, FiringView firing) {
        auditLog.record(AuditRecord.company(trigger.companyId(), AuditAction.TRIGGER_RUN, CurrentActor.actor())
            .target(TriggerStates.TARGET, trigger.id())
            .detail("subjectKey", firing.subjectKey())
            .detail("outcome", firing.outcome().name())
            .detail("firingId", firing.id().toString())
            .build());
    }

    // ---- the editor's search -----------------------------------------------------------------------------------------

    @Override
    public List<SubjectHit> search(UUID companyId, UUID triggerId, @Nullable String query) {
        NoTransaction.require("Searching a trigger's subjects");
        TriggerInfo trigger = transactions.inCompany(companyId, () -> triggers.find(companyId, triggerId))
            .filter(TriggerInfo::live)
            .orElse(null);
        if (trigger == null || trigger.source() != TriggerSource.TRACKER
                || trigger.pausedReason() == PausedReason.SYSTEM_PAUSED) {
            // A paused system's accounts are used for nothing (the package 6 contract, §5.4).
            return List.of();
        }
        Source source;
        try {
            source = transactions.inCompany(companyId, () -> source(companyId, trigger));
        }
        catch (ApiException ex) {
            return List.of();
        }
        // Read with the row's account only while the trigger may use it: its holder active, a service account still
        // borrowing the credential (I7, ADR-0072).
        TriggerAccount row = source.row();
        boolean usable = transactions.inCompany(companyId, () -> accounts.check(new AccountBinding.Stored(companyId,
                row.systemId(), trigger.authorPrincipalId(), row.holderPrincipalId(), source.account().id(), null,
                row.externalId()))) instanceof AccountBinding.Usable;
        if (!usable) {
            return List.of();
        }
        Optional<Accounts.RevealedCredential> revealed = accounts.reveal(companyId, source.account().id(),
                row.holderPrincipalId(), "trigger-run:" + triggerId);
        if (revealed.isEmpty()) {
            return List.of();
        }
        CallResult<SystemPolls.Session> opened = systemPolls.open(companyId, source.row().systemId(),
                revealed.get().callCredential());
        if (opened.value() == null) {
            return List.of();
        }
        CallResult<List<SystemPolls.Subject>> found = opened.value().search(trigger.scope(), query, SEARCH_LIMIT);
        if (found.value() == null) {
            if (Objects.requireNonNull(found.failure()).kind() == CallResult.Kind.UNAUTHORIZED) {
                transactions.runInCompany(companyId, () -> accounts.refused(companyId, source.account().id(),
                        found.failureMessage()));
            }
            return List.of();
        }
        return found.value().stream()
            .filter(subject -> SystemPolls.inScope(subject, trigger.scope()))
            .limit(SEARCH_LIMIT)
            .map(subject -> new SubjectHit(subject.key(), subject.kind().name(), subject.title(),
                    subject.status() != null ? subject.status() : subject.open() ? "open" : "closed", subject.url()))
            .toList();
    }

    // ---- the source account ----------------------------------------------------------------------------------------

    private record Source(TriggerAccount row, AccountInfo account) {
    }

    private Source source(UUID companyId, TriggerInfo trigger) {
        TriggerAccount row = triggers.accounts(companyId, trigger.id()).stream()
            .filter(account -> account.systemId().equals(trigger.systemId()) && account.accountId() != null)
            .findFirst()
            .orElseThrow(() -> ApiException.conflict(ProblemCode.SUBJECT_NOT_VISIBLE, "The trigger has no account "
                    + "on its system"));
        AccountInfo account = accounts.find(companyId, Objects.requireNonNull(row.accountId()))
            .orElseThrow(() -> ApiException.conflict(ProblemCode.SUBJECT_NOT_VISIBLE, "The trigger's account on its "
                    + "system is gone"));
        return new Source(row, account);
    }

    private static @Nullable String login(AccountInfo account) {
        return account.externalLogin() != null ? account.externalLogin() : account.externalId();
    }
}
