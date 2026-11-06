package com.flowtivo.tinfirm.governance.internal;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import com.flowtivo.tinfirm.governance.Spend;
import com.flowtivo.tinfirm.shared.api.SpendApi;
import com.flowtivo.tinfirm.shared.api.model.SpendDimension;
import com.flowtivo.tinfirm.shared.api.model.SpendReport;
import com.flowtivo.tinfirm.shared.api.model.SpendSource;

import jakarta.servlet.http.HttpServletRequest;

/**
 * {@code GET /spend}: JSON, or CSV when the caller asks for {@code text/csv} before JSON (the package 6 contract,
 * §5.2). The generated interface types the answer as the JSON report; the CSV is the same rows as text.
 */
@RestController
class SpendController implements SpendApi {

    static final MediaType CSV = new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8);

    private final SpendQueryService spend;

    private final HttpServletRequest request;

    SpendController(SpendQueryService spend, HttpServletRequest request) {
        this.spend = spend;
        this.request = request;
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public ResponseEntity<SpendReport> getSpend(UUID companyId, @Nullable LocalDate from, @Nullable LocalDate to,
            @Nullable String zone, @Nullable List<SpendDimension> groupBy, String bucket, @Nullable UUID payerId,
            @Nullable UUID actorId, @Nullable UUID agentId, @Nullable UUID triggerId, @Nullable UUID conversationId,
            @Nullable String model, @Nullable SpendSource source) {
        Spend.Query query = new Spend.Query(from, to, zone, groupBy, bucket, payerId, actorId,
                agentId, triggerId, conversationId, model, source);
        if (wantsCsv(request.getHeader(HttpHeaders.ACCEPT))) {
            ResponseEntity csv = ResponseEntity.ok()
                .contentType(CSV)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"spend.csv\"")
                .body(spend.csv(companyId, query));
            return (ResponseEntity<SpendReport>) csv;
        }
        return ResponseEntity.ok(spend.report(companyId, query));
    }

    /** Whether the request's {@code Accept} prefers CSV to JSON (by quality, then order). */
    static boolean wantsCsv(@Nullable String accept) {
        if (accept == null || accept.isBlank()) {
            return false;
        }
        List<MediaType> types;
        try {
            types = MediaType.parseMediaTypes(accept);
        }
        catch (IllegalArgumentException ex) {
            return false;
        }
        types = new java.util.ArrayList<>(types);
        types.sort(java.util.Comparator.comparingDouble(MediaType::getQualityValue).reversed());
        for (MediaType type : types) {
            if (type.isCompatibleWith(MediaType.APPLICATION_JSON) && !type.isWildcardType()) {
                return false;
            }
            if (type.getType().equals("text") && type.getSubtype().equals("csv")) {
                return true;
            }
        }
        return false;
    }
}
