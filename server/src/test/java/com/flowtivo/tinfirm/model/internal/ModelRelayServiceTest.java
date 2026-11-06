package com.flowtivo.tinfirm.model.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import com.flowtivo.tinfirm.shared.net.InflightBytes;

/**
 * Parts of the model relay without a server: what a request's plan prints, the settings that bound the bytes of
 * request bodies a replica holds ({@code InflightBytes}, tested in {@code libs/net}), LiteLLM's own fields and those of
 * its guardrails removed from a body and its {@code drop_params} put on; the settings' defaults (the host's own token
 * required, the body deadline, the beta values passed on), a body's share taken as its bytes arrive whatever its
 * declared length, a body past its deadline 408, and a provider's tool refused unless the company allows it.
 */
class ModelRelayServiceTest {

    private static final String KEY = "sk-TEST-ONLY-principal-key-5d1e";

    @Test
    void aPlanNeverShowsTheKeyNorTheTokensHash() {
        String hash = RelayTokenStore.hash("tfm_" + "A".repeat(43));
        UUID runId = UUID.randomUUID();
        RelayTokenStore.TokenRow token = new RelayTokenStore.TokenRow(hash, UUID.randomUUID(), runId, 3,
                UUID.randomUUID(), UUID.randomUUID(), RelayTokenStore.RelayFormat.ANTHROPIC, null, null, null);
        ModelRelayService.Plan plan = new ModelRelayService.Plan(token,
                new Gateway("http://litellm:4000", "sk-TEST-ONLY-admin-key", true), KEY, UUID.randomUUID(),
                List.of("claude-*"), 16);
        assertThat(plan.toString()).contains(runId.toString())
            .contains("key=***")
            .contains("claude-*")
            .doesNotContain(KEY)
            .doesNotContain(hash)
            .doesNotContain("sk-TEST-ONLY-admin-key");
    }

    @Test
    void theCapHoldsTheLargestBodyTwice() {
        ModelProperties defaults = properties(64L * 1024 * 1024, 256L * 1024 * 1024);
        assertThat(defaults.relayMaxInflightBytes()).isEqualTo(268435456L);
        properties(1024, 2048);
        assertThatThrownBy(() -> properties(1024, 2047)).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("relay-max-inflight-bytes");
    }

    @Test
    void liteLlmsGuardrailFieldsAreRemoved() throws RelayBody.Refused {
        assertThat(RelayBody.removed("guardrail_config")).isTrue();
        assertThat(RelayBody.removed("Guardrail_Config")).isTrue();
        assertThat(RelayBody.removed("guardrails")).isTrue();
        assertThat(RelayBody.removed("policies")).isTrue();
        assertThat(RelayBody.removed("include_guardrail_response")).isTrue();
        RelayBody.Result result = RelayBody.transform(RelayBody.mapper(1024),
                ("{\"model\":\"m\",\"guardrail_config\":{\"pii\":{\"mode\":\"off\"}},\"max_tokens\":1,"
                        + "\"policies\":[\"p\"],\"include_guardrail_response\":true}").getBytes());
        assertThat(new String(result.bytes())).isEqualTo("{\"model\":\"m\",\"max_tokens\":1,\"drop_params\":true}");
    }

    /**
     * LiteLLM drops what the model's provider does not take, whatever the runtime: pi's {@code reasoning_effort} for
     * a model LiteLLM does not know to reason (Kimi K3 through OpenRouter) was a refusal that failed the turn. A run's
     * own {@code drop_params} does not count, in any case.
     */
    @Test
    void liteLlmDropsWhatTheModelDoesNotTake() throws RelayBody.Refused {
        RelayBody.Result result = RelayBody.transform(RelayBody.mapper(1024),
                "{\"Drop_Params\":false,\"model\":\"moonshotai/kimi-k3\",\"reasoning_effort\":\"high\"}".getBytes());
        assertThat(new String(result.bytes()))
            .isEqualTo("{\"model\":\"moonshotai/kimi-k3\",\"reasoning_effort\":\"high\",\"drop_params\":true}");
        assertThat(result.model()).isEqualTo("moonshotai/kimi-k3");
    }

    /** The defaults an operator gets without a variable (M1, M3 and I8 of the security review of 2026-10-27). */
    @Test
    void theDefaultsRequireTheHostsTokenBoundTheBodyAndPassOnlyKnownBetas() {
        ModelProperties defaults = new Binder(new MapConfigurationPropertySource(Map.of()))
            .bindOrCreate("tinfirm.model", Bindable.of(ModelProperties.class));
        assertThat(defaults.relayRequireHostToken()).isTrue();
        assertThat(defaults.relayBodyTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(defaults.relayAnthropicBetas()).isEqualTo(ModelProperties.ANTHROPIC_BETAS)
            .contains("claude-code-20250219", "interleaved-thinking-2025-05-14",
                    "fine-grained-tool-streaming-2025-05-14", "context-management-2025-06-27",
                    "inline-tools-2026-09-15")
            .noneMatch(beta -> beta.startsWith("mcp-") || beta.startsWith("code-execution")
                    || beta.startsWith("web-fetch") || beta.startsWith("web-search") || beta.startsWith("skills")
                    || beta.startsWith("files-api") || beta.startsWith("advanced-tool-use")
                    || beta.startsWith("advisor-tool") || beta.startsWith("oauth") || beta.startsWith("computer-use"));
        assertThat(defaults.relayOpenaiBetas()).containsExactly("responses=experimental");
        // An operator's list replaces the default.
        ModelProperties narrowed = new Binder(new MapConfigurationPropertySource(
                Map.of("tinfirm.model.relay-anthropic-betas", "interleaved-thinking-2025-05-14")))
            .bindOrCreate("tinfirm.model", Bindable.of(ModelProperties.class));
        assertThat(narrowed.relayAnthropicBetas()).containsExactly("interleaved-thinking-2025-05-14");
    }

    /**
     * A declared length reserves nothing ahead (M3): a body takes its share as its bytes arrive, so that one that
     * stalls after a few bytes holds only those, and a read that times out is 408.
     */
    @Test
    void aBodyTakesItsShareAsItArrivesAndAStalledOneIs408() throws IOException {
        InflightBytes inflight = new InflightBytes(1024);
        InputStream stalls = new InputStream() {

            private int sent;

            @Override
            public int read() throws IOException {
                throw new UnsupportedOperationException();
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                if (sent >= 10) {
                    throw new SocketTimeoutException("Read timed out");
                }
                sent += 10;
                return 10;
            }
        };
        try (InflightBytes.Share share = inflight.share()) {
            assertThatThrownBy(() -> ModelRelayService.read(stalls, 64L * 1024 * 1024, share, Duration.ofSeconds(30)))
                .hasMessageContaining("did not arrive within 30 seconds");
            assertThat(inflight.held()).isEqualTo(20);
        }
        assertThat(inflight.held()).isZero();
        // A body that keeps arriving past the deadline is 408 as well, at its next part.
        InputStream slow = new InputStream() {

            @Override
            public int read() throws IOException {
                throw new UnsupportedOperationException();
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                try {
                    Thread.sleep(30);
                }
                catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IOException(ex);
                }
                b[off] = ' ';
                return 1;
            }
        };
        try (InflightBytes.Share share = inflight.share()) {
            assertThatThrownBy(() -> ModelRelayService.read(slow, 1024, share, Duration.ofMillis(100)))
                .hasMessageContaining("did not arrive within");
        }
        // One that arrives in time is read whole.
        try (InflightBytes.Share share = inflight.share()) {
            byte[] body = "{\"model\":\"m\"}".getBytes(StandardCharsets.UTF_8);
            assertThat(ModelRelayService.read(new ByteArrayInputStream(body), 1024, share, Duration.ofSeconds(30)))
                .isEqualTo(body);
            assertThat(inflight.held()).isEqualTo(2L * body.length);
        }
    }

    /** A tool the provider would run, web search among them, and {@code mcp_servers} are always refused. */
    @Test
    void theRelayAlwaysRefusesAProvidersTool() {
        byte[] search = "{\"model\":\"m\",\"tools\":[{\"type\":\"web_search_20250305\",\"name\":\"web_search\"}]}"
            .getBytes(StandardCharsets.UTF_8);
        byte[] connector = "{\"model\":\"m\",\"mcp_servers\":[{\"type\":\"url\",\"url\":\"https://evil.example/mcp\"}]}"
            .getBytes(StandardCharsets.UTF_8);
        assertThat(RelayBody.removed("mcp_servers")).isFalse();
        assertThatThrownBy(() -> RelayBody.transform(RelayBody.mapper(1024), search))
            .isInstanceOf(RelayBody.Refused.class)
            .hasMessageContaining("`web_search_20250305`");
        assertThatThrownBy(() -> RelayBody.transform(RelayBody.mapper(1024), connector))
            .isInstanceOf(RelayBody.Refused.class)
            .hasMessageContaining("`mcp_servers`");
    }

    private static ModelProperties properties(long maxRequestBytes, long maxInflightBytes) {
        return new ModelProperties(null, null, Duration.ofSeconds(10), Duration.ofMinutes(5), Duration.ofMinutes(10),
                maxRequestBytes, 16, 16, Duration.ofDays(30), Duration.ofMinutes(10), Duration.ofMinutes(1),
                maxInflightBytes, false, null, Duration.ofSeconds(30), null, null);
    }
}
