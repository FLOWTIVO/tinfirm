package com.flowtivo.tinfirm.governance.internal;

import java.math.BigDecimal;
import java.util.List;

import org.jspecify.annotations.Nullable;

import com.flowtivo.tinfirm.shared.api.model.SpendAmounts;
import com.flowtivo.tinfirm.shared.api.model.SpendDimension;
import com.flowtivo.tinfirm.shared.api.model.SpendKey;
import com.flowtivo.tinfirm.shared.api.model.SpendReport;
import com.flowtivo.tinfirm.shared.api.model.SpendRow;

/**
 * A spend report as CSV (RFC 4180): per grouping its id and label, then the amounts; without a grouping, one row of
 * totals. A field that a spreadsheet would read as a formula ({@code =}, {@code +}, {@code -}, {@code @}, a tab or a
 * carriage return first) is prefixed with {@code '}.
 */
final class SpendCsv {

    private SpendCsv() {
    }

    static String of(SpendReport report) {
        StringBuilder csv = new StringBuilder();
        List<SpendDimension> groupBy = report.getGroupBy();
        for (SpendDimension dimension : groupBy) {
            csv.append(dimension.getValue()).append("_id,").append(dimension.getValue()).append("_label,");
        }
        csv.append("runs,requests,input_tokens,output_tokens,cached_input_tokens,cache_write_tokens,cost_usd,"
                + "relay_cost_usd,subscription_tokens\r\n");
        if (groupBy.isEmpty()) {
            amounts(csv, report.getTotals());
            return csv.toString();
        }
        for (SpendRow row : report.getRows()) {
            for (SpendKey key : row.getKeys()) {
                csv.append(field(key.getFolded() && key.getPersonPrincipalId() != null
                        ? key.getPersonPrincipalId().toString() : key.getId()))
                    .append(',')
                    .append(field(key.getLabel()))
                    .append(',');
            }
            amounts(csv, row.getAmounts());
        }
        return csv.toString();
    }

    private static void amounts(StringBuilder csv, SpendAmounts amounts) {
        csv.append(amounts.getRuns()).append(',').append(amounts.getRequests()).append(',')
            .append(amounts.getInputTokens()).append(',').append(amounts.getOutputTokens()).append(',')
            .append(amounts.getCachedInputTokens()).append(',').append(amounts.getCacheWriteTokens()).append(',')
            .append(plain(amounts.getCostUsd())).append(',').append(plain(amounts.getRelayCostUsd())).append(',')
            .append(amounts.getSubscriptionTokens()).append("\r\n");
    }

    private static String plain(BigDecimal value) {
        return value.toPlainString();
    }

    static String field(@Nullable String value) {
        if (value == null) {
            return "";
        }
        String text = value;
        if (!text.isEmpty() && "=+-@\t\r".indexOf(text.charAt(0)) >= 0) {
            text = "'" + text;
        }
        if (text.contains(",") || text.contains("\"") || text.contains("\n") || text.contains("\r")) {
            return "\"" + text.replace("\"", "\"\"") + "\"";
        }
        return text;
    }
}
