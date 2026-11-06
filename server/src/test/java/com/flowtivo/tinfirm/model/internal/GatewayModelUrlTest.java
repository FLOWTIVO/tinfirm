package com.flowtivo.tinfirm.model.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;

import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.shared.time.TimeSource;

/**
 * The bundled gateway's two addresses (ADR-0069): the admin API at {@code TINFIRM_LITELLM_ADMIN_URL}, the model
 * requests at the model port {@code TINFIRM_LITELLM_MODEL_URL}, or at the admin URL without one; a company's own gateway
 * has one URL for both. Neither shows the admin key. A model URL the keys could not safely go to stops the server.
 */
class GatewayModelUrlTest {

    private static ModelProperties properties(String adminUrl, String modelUrl) {
        return new ModelProperties(adminUrl, "sk-TEST-ONLY-master-key", Duration.ofSeconds(10), Duration.ofMinutes(5),
                Duration.ofMinutes(10), 67_108_864L, 16, 16, Duration.ofDays(30), Duration.ofMinutes(10),
                Duration.ofMinutes(1), 268_435_456L, false, modelUrl, Duration.ofSeconds(30), null, null);
    }

    private static Gateway bundled(ModelProperties properties) {
        return new Gateways(properties, null, new TimeSource(Clock.systemUTC())).bundled().orElseThrow();
    }

    @Test
    void theBundledGatewaysModelRequestsGoToItsModelPort() {
        Gateway gateway = bundled(properties("http://litellm:4000", " http://litellm:4001/ "));

        assertThat(gateway.baseUrl()).isEqualTo("http://litellm:4000");
        assertThat(gateway.modelUrl()).isEqualTo("http://litellm:4001");
        assertThat(gateway.bundled()).isTrue();
        assertThat(gateway.toString()).contains("http://litellm:4001").doesNotContain("sk-TEST-ONLY-master-key");
    }

    @Test
    void withoutAModelPortEverythingGoesToTheAdminUrl() {
        assertThat(bundled(properties("http://litellm:4000/", null)).modelUrl()).isEqualTo("http://litellm:4000");
        assertThat(bundled(properties("http://litellm:4000", "  ")).modelUrl()).isEqualTo("http://litellm:4000");
    }

    @Test
    void aModelUrlWithCredentialsAQueryOrAnotherSchemeStopsTheServer() {
        for (String url : new String[] { "http://user:pass@model-api:4001", "http://model-api:4001?x=1",
                "http://model-api:4001#f", "ftp://model-api:4001", "model-api:4001", "http://", "http://a b" }) {
            assertThatThrownBy(() -> properties("http://litellm:4000", url)).as(url)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("TINFIRM_LITELLM_MODEL_URL");
        }
        assertThat(properties("http://litellm:4000", "https://gateway.example.com/models").litellmModelUrl())
            .isEqualTo("https://gateway.example.com/models");
    }

    @Test
    void aCompanysOwnGatewayHasOneUrlForBoth() {
        Gateway own = new Gateway("https://litellm.example.com/", "sk-TEST-ONLY-admin-key", false);

        assertThat(own.modelUrl()).isEqualTo(own.baseUrl()).isEqualTo("https://litellm.example.com");
    }
}
