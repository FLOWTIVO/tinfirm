package com.flowtivo.tinfirm.company.internal;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.flowtivo.tinfirm.company.SeatFallbacks;

@Component
class SeatFallbacksImpl implements SeatFallbacks {

    private final CompanyQueries queries;

    SeatFallbacksImpl(CompanyQueries queries) {
        this.queries = queries;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public List<Fallback> since(UUID companyId, Instant since, int limit) {
        return queries.seatFallbacks(companyId, since, limit);
    }
}
