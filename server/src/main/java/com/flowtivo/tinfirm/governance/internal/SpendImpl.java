package com.flowtivo.tinfirm.governance.internal;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.flowtivo.tinfirm.governance.Spend;
import com.flowtivo.tinfirm.shared.api.model.SpendReport;

@Component
class SpendImpl implements Spend {

    private final SpendStore store;

    private final SpendQueryService queries;

    SpendImpl(SpendStore store, SpendQueryService queries) {
        this.store = store;
        this.queries = queries;
    }

    @Override
    @Transactional(readOnly = true)
    public Totals paidBy(UUID companyId, UUID principalId, Instant from, Instant to) {
        SpendStore.Totals totals = store.paidBy(companyId, principalId, from, to);
        return new Totals(totals.runs(), totals.requests(), totals.inputTokens(), totals.outputTokens(),
                totals.costUsd(), totals.subscriptionTokens());
    }

    @Override
    public SpendReport report(UUID companyId, Query query) {
        return queries.report(companyId, query);
    }
}
