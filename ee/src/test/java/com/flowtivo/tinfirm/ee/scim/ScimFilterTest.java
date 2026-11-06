package com.flowtivo.tinfirm.ee.scim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.flowtivo.tinfirm.company.scim.ScimException;

/**
 * The filters the SCIM endpoint serves (ADR-0087, decision 1), read by a scanner rather than a pattern with a nested
 * quantifier, which recurses once per character of a long value (the security review of 2026-10-27, L10).
 */
class ScimFilterTest {

    @Test
    void theServedFiltersInAnyCase() {
        assertThat(ScimFilter.parse("userName eq \"ada@example.com\""))
            .isEqualTo(new ScimFilter("userName", "ada@example.com"));
        assertThat(ScimFilter.parse("  USERNAME   EQ  \"ada@example.com\"  "))
            .isEqualTo(new ScimFilter("userName", "ada@example.com"));
        assertThat(ScimFilter.parse("externalId eq \"00u1\"")).isEqualTo(new ScimFilter("externalId", "00u1"));
        assertThat(ScimFilter.parse("id eq \"2819c223\"")).isEqualTo(new ScimFilter("id", "2819c223"));
        assertThat(ScimFilter.parse("urn:ietf:params:scim:schemas:core:2.0:User:userName eq \"a@b.c\""))
            .isEqualTo(new ScimFilter("userName", "a@b.c"));
        assertThat(ScimFilter.parse("userName eq \"\"")).isEqualTo(new ScimFilter("userName", ""));
    }

    @Test
    void aValuesEscapesAreJsons() {
        assertThat(ScimFilter.parse("userName eq \"a\\\"b\\\\c\\u0041\\/\"").value()).isEqualTo("a\"b\\cA/");
        assertThat(ScimFilter.parse("userName eq \"tab\\there\"").value()).isEqualTo("tab\there");
    }

    @ParameterizedTest
    @ValueSource(strings = { "", "userName", "userName eq", "userName eq ada", "userName co \"a\"",
            "displayName eq \"a\"", "userNameeq \"a\"", "userName eq\"a\"", "userName eq \"a\" and id eq \"b\"",
            "userName eq \"a\"b\"", "userName eq \"a\\\"", "userName eq \"a", "userName eq \"", "id eq \"a\\\nb\"",
            "urn:ietf:params:scim:schemas:core:2.0:Group:id eq \"a\"" })
    void anythingElseIsAnInvalidFilter(String filter) {
        assertThatThrownBy(() -> ScimFilter.parse(filter)).as(filter)
            .isInstanceOf(ScimException.class)
            .hasMessageContaining("Only userName eq, externalId eq and id eq are served");
    }

    @Test
    void aLongAdversarialValueIsReadInLinearTimeWithoutRecursion() {
        // 100 KiB of escapes and plain characters, the shape that makes such a pattern recurse per character, then a
        // quote that closes nothing: refused, and in a few milliseconds.
        String adversarial = "userName eq \"" + "\\\\a".repeat(100 * 1024 / 3) + "\"x";
        long started = System.nanoTime();
        assertThatThrownBy(() -> ScimFilter.parse(adversarial)).isInstanceOf(ScimException.class);
        String valid = "userName eq \"" + "a\\\"".repeat(100 * 1024 / 3) + "\"";
        assertThat(ScimFilter.parse(valid).value()).hasSize(2 * (100 * 1024 / 3));
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
    }
}
