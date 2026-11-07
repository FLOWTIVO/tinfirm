package com.flowtivo.tinfirm.model.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;

import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.shared.time.TimeSource;

/**
 * The bundled gateway's addresses: the admin API at {@code TINFIRM_MODEL_GATEWAY_ADMIN_URL}, and the model requests
 * there too, unless the optional {@code TINFIRM_MODEL_GATEWAY_MODEL_URL} names another; a company's own gateway has
 * one URL for both. Neither shows the admin key. A model URL the keys could not safely go to stops the server.
 */
class GatewayModelUrlTest {

    private static ModelProperties properties(String adminUrl, String modelUrl) {
        return new ModelProperties(adminUrl, "sk-TEST-ONLY-master-key", Duration.ofSeconds(10), Duration.ofMinutes(5),
                Duration.ofMinutes(10), 67_108_864L, 16, 16, Duration.ofDays(30), Duration.ofMinutes(10),
                Duration.ofMinutes(1), 268_435_456L, modelUrl, Duration.ofSeconds(30), null, null,
                GatewayVendor.LITELLM);
    }

    private static Gateway bundled(ModelProperties properties) {
        return new Gateways(properties, null, new TimeSource(Clock.systemUTC())).bundled().orElseThrow();
    }

    @Test
    void byDefaultTheModelRequestsGoToTheAdminUrl() {
        Gateway gateway = bundled(properties("http://litellm:4000/", null));

        assertThat(gateway.baseUrl()).isEqualTo("http://litellm:4000");
        assertThat(gateway.modelUrl()).isEqualTo("http://litellm:4000");
        assertThat(gateway.bundled()).isTrue();
        assertThat(bundled(properties("http://litellm:4000", "  ")).modelUrl()).isEqualTo("http://litellm:4000");
    }

    @Test
    void aModelUrlSetOverridesTheAdminUrlForTheModelRequestsOnly() {
        Gateway gateway = bundled(properties("http://litellm:4000", " http://litellm-models:4000/ "));

        assertThat(gateway.baseUrl()).isEqualTo("http://litellm:4000");
        assertThat(gateway.modelUrl()).isEqualTo("http://litellm-models:4000");
        assertThat(gateway.toString()).contains("http://litellm-models:4000")
            .doesNotContain("sk-TEST-ONLY-master-key");
    }

    @Test
    void aModelUrlWithCredentialsAQueryOrAnotherSchemeStopsTheServer() {
        for (String url : new String[] { "http://user:pass@litellm:4000", "http://litellm:4000?x=1",
                "http://litellm:4000#f", "ftp://litellm:4000", "litellm:4000", "http://", "http://a b" }) {
            assertThatThrownBy(() -> properties("http://litellm:4000", url)).as(url)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("TINFIRM_MODEL_GATEWAY_MODEL_URL");
        }
        assertThat(properties("http://litellm:4000", "https://gateway.example.com/models").gatewayModelUrl())
            .isEqualTo("https://gateway.example.com/models");
    }

    @Test
    void aCompanysOwnGatewayHasOneUrlForBoth() {
        Gateway own = new Gateway("https://litellm.example.com/", "sk-TEST-ONLY-admin-key", false);

        assertThat(own.modelUrl()).isEqualTo(own.baseUrl()).isEqualTo("https://litellm.example.com");
    }
}
