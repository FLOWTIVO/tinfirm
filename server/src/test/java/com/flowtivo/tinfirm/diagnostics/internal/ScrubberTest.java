package com.flowtivo.tinfirm.diagnostics.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** ADR-0070, §2: what may be the company's data is masked before anything is stored. */
class ScrubberTest {

    @Test
    void masksUrlsEmailsIdsAndQuotedValues() {
        assertThat(Scrubber.mask(
                "GET https://jira.acme.example/rest/api/2/issue/PAY-12?x=1 failed for alice@acme.example"))
            .isEqualTo("GET <url> failed for <email>");
        assertThat(Scrubber.mask("Conversation 0192f1e2-3b4c-7d5e-8f90-a1b2c3d4e5f6 not found"))
            .isEqualTo("Conversation <uuid> not found");
        assertThat(Scrubber.mask("Issue 'Payroll for Bob Smith' could not be read; field \"salary\" is unknown"))
            .isEqualTo("Issue <str> could not be read; field <str> is unknown");
        assertThat(Scrubber.mask("Issue 'Bob's salary' is locked")).isEqualTo("Issue <str> is locked");
    }

    @Test
    void masksAddressesPathsHexTokensAndLongNumbers() {
        assertThat(Scrubber.mask("Connection to 10.1.2.3:5432 refused")).isEqualTo("Connection to <ip> refused");
        assertThat(Scrubber.mask("No such file /home/alice/secrets/plan.txt")).isEqualTo("No such file <path>");
        assertThat(Scrubber.mask("Object at 0x7f3a2b and hash deadbeefcafebabe1234"))
            .isEqualTo("Object at <hex> and hash <hex>");
        assertThat(Scrubber.mask("Bearer ghp_A1b2C3d4E5f6G7h8I9j0 refused")).isEqualTo("Bearer <token> refused");
        assertThat(Scrubber.mask("Key xKqPzWmRtYvBnLsDfGhJ7 refused")).as("one digit is enough")
            .isEqualTo("Key <token> refused");
        assertThat(Scrubber.mask("Key sk-abcdefghijklmnopqrstuvwxyz refused")).as("letters alone behind a key's prefix")
            .isEqualTo("Key <token> refused");
        assertThat(Scrubber.mask("Key sk-proj-AbCdEfGhIjKlMnOpQrStUv refused")).isEqualTo("Key <token> refused");
        assertThat(Scrubber.mask("Invalid apiKey=sk-abcdefghijklmnopqrstuvwxyz")).as("after an assignment")
            .isEqualTo("Invalid <token>");
        assertThat(Scrubber.mask("Token github_pat_abcdefghijklmnopqrstuvwxyzABCDEF expired"))
            .isEqualTo("Token <token> expired");
        assertThat(Scrubber.mask("Secret whsec_abcdefghijklmnopqrstuvwxyz== refused"))
            .isEqualTo("Secret <token> refused");
        assertThat(Scrubber.mask("Invoice 20261016 of 125 EUR")).isEqualTo("Invoice <n> of 125 EUR");
    }

    @Test
    void keepsWhatIsCode() {
        assertThat(Scrubber.mask("Request failed with status 503 in DataIntegrityViolationException"))
            .isEqualTo("Request failed with status 503 in DataIntegrityViolationException");
        assertThat(Scrubber.mask("Unhandled exception")).isEqualTo("Unhandled exception");
        assertThat(Scrubber.mask("Run {} of {} failed")).isEqualTo("Run {} of {} failed");
        assertThat(Scrubber.mask("violates unique constraint error_report_fingerprint_uq"))
            .isEqualTo("violates unique constraint error_report_fingerprint_uq");
        assertThat(Scrubber.mask("No spring-boot-starter-actuator on the class path"))
            .isEqualTo("No spring-boot-starter-actuator on the class path");
        assertThat(Scrubber.mask("Unknown column principal_model_key.sync_lease_until"))
            .isEqualTo("Unknown column principal_model_key.sync_lease_until");
        assertThat(Scrubber.mask("Bad property spring.profiles.active=production-cluster-east"))
            .isEqualTo("Bad property spring.profiles.active=production-cluster-east");
    }

    @Test
    void foldsWhitespaceDropsControlCharactersAndCuts() {
        assertThat(Scrubber.mask("  one\n\ttwo\u0007  three ")).isEqualTo("one two three");
        assertThat(Scrubber.mask("x".repeat(500))).hasSize(Scrubber.MAX_MESSAGE);
        assertThat(Scrubber.mask("   ")).isNull();
        assertThat(Scrubber.mask(null)).isNull();
    }

    @Test
    void neverSplitsASurrogatePair() {
        String text = "a".repeat(Scrubber.MAX_MESSAGE - 1) + "😀";
        assertThat(Scrubber.cut(text, Scrubber.MAX_MESSAGE)).hasSize(Scrubber.MAX_MESSAGE - 1);
    }
}
