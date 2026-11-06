package com.flowtivo.tinfirm.diagnostics;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * A remote system's text is scrubbed before it enters a record kept for good (the security review of 2026-10-27, I2).
 */
class RemoteTextTest {

    @Test
    void masksAnEchoedTokenAndDropsControlCharacters() {
        String scrubbed = RemoteText
            .scrubbed("Jira answered 401\u0000\n for ATATT3xFfGF0TestEchoedToken " + "x".repeat(600));

        assertThat(scrubbed).startsWith("Jira answered 401 for <token>")
            .doesNotContain("ATATT3xFfGF0")
            .doesNotContain("\u0000")
            .doesNotContain("\n")
            .hasSize(300);
        assertThat(RemoteText.scrubbed(null)).isEmpty();
        assertThat(RemoteText.scrubbed("invalid_grant")).isEqualTo("invalid_grant");
    }
}
