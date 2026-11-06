package com.flowtivo.tinfirm.model.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.flowtivo.tinfirm.model.internal.ModelRelayService.HostCheck;

/**
 * The model relay's runner host check as a table (the package 2 contract, open point 6): the host's own token, when
 * sent, must be the run token's host's, whatever its version and the flag; without it, a host of runner 0.3.0 or later
 * is refused, and every host while {@code tinfirm.model.relay-require-host-token} is on; an older host is accepted.
 */
class ModelRelayHostCheckTest {

    private static final UUID HOST = UUID.fromString("00000000-0000-0000-0000-00000000000a");

    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    @ParameterizedTest
    @CsvSource(nullValues = "null", value = {
            // The header sent: its host decides.
            "own, 0.3.0, false, BOUND",
            "own, 0.2.0, false, BOUND",
            "own, 0.2.0, true, BOUND",
            "own, null, true, BOUND",
            "other, 0.3.0, false, REFUSED",
            "other, 0.2.0, false, REFUSED",
            "other, null, false, REFUSED",
            // Not sent: refused from 0.3.0 on, or from every host with the flag; else accepted, as before.
            "null, 0.3.0, false, REFUSED",
            "null, 0.3.1, false, REFUSED",
            "null, 1.0.0, false, REFUSED",
            "null, 0.3.0-rc.1, false, LEGACY",
            "null, 0.2.0, false, LEGACY",
            "null, 0.1.0-test, false, LEGACY",
            "null, null, false, LEGACY",
            "null, 0.2.0, true, REFUSED",
            "null, null, true, REFUSED" })
    void theHeaderTheVersionAndTheFlag(String presented, String version, boolean required, HostCheck expected) {
        UUID presentedHost = presented == null ? null : "own".equals(presented) ? HOST : OTHER;
        assertThat(ModelRelayService.hostCheck(HOST, presentedHost, version, required)).isEqualTo(expected);
    }
}
