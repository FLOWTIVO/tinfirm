package com.flowtivo.tinfirm.governance.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The CSV's fields (RFC 4180) and its guard against formulas; which {@code Accept} asks for CSV. */
class SpendCsvTest {

    @Test
    void fieldsAreQuotedAndFormulasDefused() {
        assertThat(SpendCsv.field(null)).isEmpty();
        assertThat(SpendCsv.field("plain")).isEqualTo("plain");
        assertThat(SpendCsv.field("a,b")).isEqualTo("\"a,b\"");
        assertThat(SpendCsv.field("say \"hi\"")).isEqualTo("\"say \"\"hi\"\"\"");
        assertThat(SpendCsv.field("=HYPERLINK(\"x\")")).isEqualTo("\"'=HYPERLINK(\"\"x\"\")\"");
        assertThat(SpendCsv.field("@SUM")).isEqualTo("'@SUM");
        assertThat(SpendCsv.field("-1")).isEqualTo("'-1");
    }

    @Test
    void csvOnlyWhenAskedBeforeJson() {
        assertThat(SpendController.wantsCsv(null)).isFalse();
        assertThat(SpendController.wantsCsv("application/json")).isFalse();
        assertThat(SpendController.wantsCsv("text/csv")).isTrue();
        assertThat(SpendController.wantsCsv("application/json, text/csv")).isFalse();
        assertThat(SpendController.wantsCsv("application/json;q=0.5, text/csv")).isTrue();
        assertThat(SpendController.wantsCsv("*/*")).isFalse();
        assertThat(SpendController.wantsCsv("not a type")).isFalse();
    }
}
