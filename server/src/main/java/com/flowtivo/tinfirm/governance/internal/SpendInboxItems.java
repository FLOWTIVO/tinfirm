package com.flowtivo.tinfirm.governance.internal;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.conversation.InboxItems;
import com.flowtivo.tinfirm.model.BudgetAlerts;
import com.flowtivo.tinfirm.principal.PrincipalInfo;
import com.flowtivo.tinfirm.principal.Principals;
import com.flowtivo.tinfirm.run.QueueReasonCode;
import com.flowtivo.tinfirm.run.RunReads;
import com.flowtivo.tinfirm.shared.api.model.InboxBudget;
import com.flowtivo.tinfirm.shared.api.model.InboxCompanySpend;
import com.flowtivo.tinfirm.shared.api.model.InboxItem;
import com.flowtivo.tinfirm.shared.api.model.InboxItemKind;
import com.flowtivo.tinfirm.shared.api.model.InboxSpendSync;
import com.flowtivo.tinfirm.shared.time.TimeSource;

/**
 * The inbox items of spend (the package 6 contract, §5.3, §5.11), computed from the state on each read, none stored as
 * a notification:
 * <ul>
 * <li>{@code BUDGET}, for each alert that stands ({@link BudgetAlerts#open}): a member's own, to the member at each
 * threshold; a service identity's, to admins and the authors of the live triggers it pays, at each threshold; and to
 * admins every principal's at 100 %, with the counts of what waits for it and nothing of it. Each recipient sees the
 * highest threshold crossed, once per principal.</li>
 * <li>{@code COMPANY_SPEND}, to admins, while the calendar month (UTC) whose spend crossed the alert amount lasts.</li>
 * <li>{@code SPEND_SYNC}, to admins, while more than 10 of today's runs (UTC) were settled from the relay's count, or
 * LiteLLM's logs could not be read for an hour.</li>
 * </ul>
 * Each only informs, so a member dismisses it for themselves (CONVENTIONS §15.7): a budget's key is its alert (the
 * principal, the threshold, the crossing in its period), the company's spend's its month, the sync's since when.
 */
@Component
class SpendInboxItems implements InboxItems {

    static final int RELAY_RUNS_PER_DAY = 10;

    private static final Duration FAILING = Duration.ofHours(1);

    private final BudgetAlerts alerts;

    private final Principals principals;

    private final RunReads runs;

    private final TriggerSpend triggers;

    private final SpendStore store;

    private final TimeSource time;

    SpendInboxItems(BudgetAlerts alerts, Principals principals, RunReads runs, TriggerSpend triggers,
            SpendStore store, TimeSource time) {
        this.alerts = alerts;
        this.principals = principals;
        this.runs = runs;
        this.triggers = triggers;
        this.store = store;
        this.time = time;
    }

    @Override
    public List<Item> itemsFor(Caller caller, int limit) {
        List<Item> items = new ArrayList<>(budgets(caller, limit));
        if (caller.admin()) {
            companySpend(caller.companyId()).ifPresent(items::add);
            spendSync(caller.companyId()).ifPresent(items::add);
        }
        return items;
    }

    private List<Item> budgets(Caller caller, int limit) {
        List<BudgetAlerts.Alert> open = alerts.open(caller.companyId());
        if (open.isEmpty()) {
            return List.of();
        }
        Map<UUID, PrincipalInfo> payers = principals.findAll(caller.companyId(),
                open.stream().map(BudgetAlerts.Alert::principalId).distinct().toList());
        // The highest threshold each recipient may see, per principal: open() is newest first, so keep the highest.
        Map<UUID, BudgetAlerts.Alert> shown = new HashMap<>();
        Map<UUID, Boolean> counts = new HashMap<>();
        for (BudgetAlerts.Alert alert : open) {
            PrincipalInfo payer = payers.get(alert.principalId());
            if (payer == null) {
                continue;
            }
            boolean own = payer.id().equals(caller.principalId());
            boolean full = alert.threshold() >= 100;
            boolean sees;
            boolean withCounts;
            if (payer.member()) {
                sees = own || caller.admin() && full;
                withCounts = !own && caller.admin() && full;
            }
            else {
                Set<UUID> authors = triggers.authorsPaidBy(caller.companyId(), payer.id());
                boolean author = caller.principalId() != null && authors.contains(caller.principalId());
                sees = caller.admin() || author;
                withCounts = sees && full;
            }
            if (!sees) {
                continue;
            }
            BudgetAlerts.Alert known = shown.get(alert.principalId());
            if (known == null || known.threshold() < alert.threshold()) {
                shown.put(alert.principalId(), alert);
                counts.put(alert.principalId(), withCounts);
            }
        }
        Map<UUID, String> names = principals.displayNames(caller.companyId(), shown.keySet());
        List<Item> items = new ArrayList<>();
        for (BudgetAlerts.Alert alert : shown.values()) {
            if (items.size() >= limit) {
                break;
            }
            PrincipalInfo payer = payers.get(alert.principalId());
            InboxBudget budget = new InboxBudget(alert.principalId(),
                    payer.member() ? InboxBudget.PrincipalKindEnum.MEMBER : InboxBudget.PrincipalKindEnum.SERVICE,
                    payer.id().equals(caller.principalId()), alert.threshold(), alert.spentUsd(), alert.budgetUsd())
                .principalName(names.get(alert.principalId()))
                .resetsAt(alert.resetsAt() != null ? alert.resetsAt().atOffset(ZoneOffset.UTC) : null);
            if (Boolean.TRUE.equals(counts.get(alert.principalId()))) {
                budget.waitingRuns(runs.countQueued(caller.companyId(), alert.principalId(),
                        QueueReasonCode.BUDGET_SPENT))
                    .waitingTriggers(triggers.livePaidBy(caller.companyId(), alert.principalId()));
            }
            // One alert of a principal: its threshold in its period. The next threshold or period is another key.
            items.add(new Item(alert.crossedAt(), new InboxItem(InboxItemKind.BUDGET,
                    alert.crossedAt().atOffset(ZoneOffset.UTC)).budget(budget)
                .noticeKey(InboxItems.key(InboxItemKind.BUDGET, alert.principalId(), alert.threshold(),
                        alert.crossedAt()))
                .dismissible(true)));
        }
        return items;
    }

    private Optional<Item> companySpend(UUID companyId) {
        LocalDate month = time.now().atOffset(ZoneOffset.UTC).toLocalDate().withDayOfMonth(1);
        return store.companyAlert(companyId, month).map(alert -> new Item(alert.crossedAt(),
                new InboxItem(InboxItemKind.COMPANY_SPEND, alert.crossedAt().atOffset(ZoneOffset.UTC))
                    .companySpend(new InboxCompanySpend(alert.month().toString().substring(0, 7),
                            alert.totalUsd(), alert.alertUsd()))
                    // The month's: the next month's crossing is another key.
                    .noticeKey(InboxItems.key(InboxItemKind.COMPANY_SPEND, YearMonth.from(alert.month())))
                    .dismissible(true)));
    }

    private Optional<Item> spendSync(UUID companyId) {
        Instant now = time.now();
        LocalDate today = now.atOffset(ZoneOffset.UTC).toLocalDate();
        int relayRuns = store.relayRuns(companyId, today);
        Optional<SpendStore.Settlement> failing = store.failingSince(companyId, now.minus(FAILING));
        if (relayRuns <= RELAY_RUNS_PER_DAY && failing.isEmpty()) {
            return Optional.empty();
        }
        Instant since = failing.map(SpendStore.Settlement::failingSince)
            .orElse(today.atStartOfDay(ZoneOffset.UTC).toInstant());
        InboxSpendSync sync = new InboxSpendSync(relayRuns)
            .failingSince(failing.map(row -> row.failingSince().atOffset(ZoneOffset.UTC)).orElse(null))
            .lastError(failing.map(SpendStore.Settlement::lastError).orElse(null));
        // Since the logs failed, else since today began: a new failure, or the next day's count, is another key.
        return Optional.of(new Item(since, new InboxItem(InboxItemKind.SPEND_SYNC, since.atOffset(ZoneOffset.UTC))
            .spendSync(sync)
            .noticeKey(InboxItems.key(InboxItemKind.SPEND_SYNC, since))
            .dismissible(true)));
    }
}
