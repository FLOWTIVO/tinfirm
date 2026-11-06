package com.flowtivo.tinfirm.runner;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The runner versions that change what the server does with a host (the package 2 contract, §7 and open point 6):
 * 0.2.0, the always-on model proxy, for a gateway's runs; 0.3.0, the host's own token sent to the model relay.
 */
class RunnerVersionsTest {

    @ParameterizedTest
    @CsvSource(nullValues = "null", value = {
            "0.2.0, true",
            "0.2.1, true",
            "0.3.0, true",
            "1.0.0, true",
            "0.10.0, true",
            "0.2.0+build.7, true",
            " 0.2.0 , true",
            "0.1.9, false",
            "0.1.0-test, false",
            "0.2.0-rc.1, false",
            "0.2, false",
            "v0.2.0, false",
            "latest, false",
            "'', false",
            "null, false" })
    void gatewayRunsNeedTheAlwaysOnModelProxy(String version, boolean supported) {
        assertThat(RunnerVersions.supportsGatewayRuns(version)).as(version).isEqualTo(supported);
    }

    @ParameterizedTest
    @CsvSource(nullValues = "null", value = {
            "0.3.0, true",
            "0.3.1, true",
            "0.4.0, true",
            "1.0.0, true",
            "0.10.0, true",
            "0.3.0+build.7, true",
            "0.2.9, false",
            "0.2.0, false",
            "0.3.0-rc.1, false",
            "0.3, false",
            "'', false",
            "null, false" })
    void theHostTokenGoesToTheRelayFromThreeOn(String version, boolean sends) {
        assertThat(RunnerVersions.sendsHostTokenToRelay(version)).as(version).isEqualTo(sends);
    }
}
