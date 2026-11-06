package com.flowtivo.tinfirm.model.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/**
 * The bundled gateway's variables (ADR-0094): {@code TINFIRM_MODEL_GATEWAY_*}, and the {@code TINFIRM_LITELLM_*} names
 * of a deployment from before them while the new ones are unset.
 */
class GatewayVariablesTest {

    private static StandardEnvironment environment(Map<String, Object> variables) throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().addFirst(new MapPropertySource("variables", variables));
        new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yaml"))
            .forEach(environment.getPropertySources()::addLast);
        return environment;
    }

    @Test
    void theNewNamesAreRead() throws IOException {
        StandardEnvironment environment = environment(Map.of("TINFIRM_MODEL_GATEWAY_ADMIN_URL", "http://gateway:4000",
                "TINFIRM_MODEL_GATEWAY_ADMIN_KEY", "sk-TEST-ONLY-new", "TINFIRM_MODEL_GATEWAY_MODEL_URL",
                "http://model-api:4001", "TINFIRM_LITELLM_ADMIN_URL", "http://old:4000", "TINFIRM_LITELLM_MASTER_KEY",
                "sk-TEST-ONLY-old"));

        assertThat(environment.getProperty("tinfirm.model.gateway-admin-url")).isEqualTo("http://gateway:4000");
        assertThat(environment.getProperty("tinfirm.model.gateway-admin-key")).isEqualTo("sk-TEST-ONLY-new");
        assertThat(environment.getProperty("tinfirm.model.gateway-model-url")).isEqualTo("http://model-api:4001");
        assertThat(environment.getProperty("tinfirm.model.gateway-vendor")).isEqualTo("LITELLM");
    }

    @Test
    void theLiteLlmNamesStillWork() throws IOException {
        StandardEnvironment environment = environment(Map.of("TINFIRM_LITELLM_ADMIN_URL", "http://litellm:4000",
                "TINFIRM_LITELLM_MASTER_KEY", "sk-TEST-ONLY-old", "TINFIRM_LITELLM_MODEL_URL",
                "http://model-api:4001"));

        assertThat(environment.getProperty("tinfirm.model.gateway-admin-url")).isEqualTo("http://litellm:4000");
        assertThat(environment.getProperty("tinfirm.model.gateway-admin-key")).isEqualTo("sk-TEST-ONLY-old");
        assertThat(environment.getProperty("tinfirm.model.gateway-model-url")).isEqualTo("http://model-api:4001");
    }

    @Test
    void neitherBundlesNone() throws IOException {
        StandardEnvironment environment = environment(Map.of());

        assertThat(environment.getProperty("tinfirm.model.gateway-admin-url")).isEmpty();
        assertThat(environment.getProperty("tinfirm.model.gateway-admin-key")).isEmpty();
    }
}
