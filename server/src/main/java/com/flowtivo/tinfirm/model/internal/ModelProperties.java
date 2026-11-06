package com.flowtivo.tinfirm.model.internal;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Settings of model access (CONVENTIONS §4; the package 2 contract, §6, §6.1).
 *
 * @param gatewayAdminUrl              the model gateway bundled with the deployment, inside its network, for its admin
 *                                     API ({@code TINFIRM_MODEL_GATEWAY_ADMIN_URL}, or the older
 *                                     {@code TINFIRM_LITELLM_ADMIN_URL}); blank: none is bundled, and companies
 *                                     configure their own ({@code EXTERNAL})
 * @param gatewayAdminKey              its admin key, LiteLLM's master key ({@code TINFIRM_MODEL_GATEWAY_ADMIN_KEY}, or
 *                                     the older {@code TINFIRM_LITELLM_MASTER_KEY}); never logged
 * @param adminTimeout                 connect and request timeout of one call to LiteLLM's admin API
 * @param reconcileInterval            how often a {@code worker} replica compares every company's keys with LiteLLM's
 *                                     and repairs what drifted
 * @param relayIdleTimeout             the model relay's longest wait for the next part of LiteLLM's answer (and for its
 *                                     headers); a streaming answer has no total limit
 * @param relayMaxRequestBytes         the largest request body the relay passes (64 MiB)
 * @param relayRunConcurrency          relayed requests of one run at a time; more are 429
 * @param relayHostConcurrencyPerSlot  relayed requests of one host at a time, per slot of its ceiling; more are 429
 * @param keyRotationInterval          how long after its creation a principal's key is rotated
 *                                     ({@code TINFIRM_MODEL_KEY_ROTATION_INTERVAL}); at least a day
 * @param keyRotationGrace             how long a rotated-out key keeps serving the requests that already hold it
 *                                     before it is blocked ({@code TINFIRM_MODEL_KEY_ROTATION_GRACE}); at most an
 *                                     hour, and none for a rotation an admin asks for
 * @param keyRotationSettle            how long after LiteLLM blocked a rotated-out key it is deleted and its late
 *                                     spend moved: LiteLLM writes the spend of requests every 10 to 15 seconds
 *                                     ({@code proxy_batch_write_at}), and a blocked key still takes the spend of the
 *                                     requests that were under way; at most an hour
 * @param relayMaxInflightBytes        the most bytes of request bodies the relay holds at once on a replica, each body
 *                                     counted twice while it is read and written anew, and of plain JSON answers it
 *                                     keeps to read their usage (at most 4 MiB each, {@code RelayUsageMeter})
 *                                     ({@code TINFIRM_MODEL_RELAY_MAX_INFLIGHT_BYTES}, 256 MiB); a body beyond it is
 *                                     429, an answer beyond it is passed on unmetered. At least twice
 *                                     {@code relayMaxRequestBytes}, so that the largest body fits
 * @param relayRequireHostToken        whether the relay refuses a run's token without its host's own token
 *                                     ({@code X-Tinfirm-Runner-Token}) from every host, the older ones too
 *                                     ({@code TINFIRM_MODEL_RELAY_REQUIRE_HOST_TOKEN}, on by default: runner
 *                                     {@code 0.3.0} is the minimum); off, a host before runner {@code 0.3.0} may leave
 *                                     it out (the package 2 contract, open point 6), for an upgrade only
 * @param gatewayModelUrl              the bundled gateway's model port, which serves the model API alone
 *                                     ({@code TINFIRM_MODEL_GATEWAY_MODEL_URL}, or the older
 *                                     {@code TINFIRM_LITELLM_MODEL_URL}; ADR-0069): the model requests the server
 *                                     relays and its own go there, its admin API to {@code gatewayAdminUrl}; blank,
 *                                     both go to {@code gatewayAdminUrl}. A company's own gateway has one URL for
 *                                     both
 * @param relayBodyTimeout             the longest the relay waits for a request's whole body
 *                                     ({@code TINFIRM_MODEL_RELAY_BODY_TIMEOUT}, 30 s); a body still arriving then is
 *                                     408. One read that waits for the next bytes is bounded by Tomcat's
 *                                     {@code server.tomcat.connection-timeout} besides
 * @param relayAnthropicBetas          the {@code anthropic-beta} values the relay passes on
 *                                     ({@code TINFIRM_MODEL_RELAY_ANTHROPIC_BETAS}, comma-separated; unset,
 *                                     {@link #ANTHROPIC_BETAS}); any other value is dropped from the forwarded request,
 *                                     never refused
 * @param relayOpenaiBetas             the {@code OpenAI-Beta} values the relay passes on
 *                                     ({@code TINFIRM_MODEL_RELAY_OPENAI_BETAS}; unset, {@link #OPENAI_BETAS}), as
 *                                     {@code relayAnthropicBetas}
 * @param gatewayVendor                the product the bundled gateway is ({@code TINFIRM_MODEL_GATEWAY_VENDOR},
 *                                     ADR-0094): {@code LITELLM}, the only product the deployment bundles
 */
@Validated
@ConfigurationProperties("tinfirm.model")
record ModelProperties(@Nullable String gatewayAdminUrl, @Nullable String gatewayAdminKey,
        @NotNull @DefaultValue("PT10S") Duration adminTimeout, @NotNull @DefaultValue("PT5M") Duration reconcileInterval,
        @NotNull @DefaultValue("PT10M") Duration relayIdleTimeout,
        @Positive @DefaultValue("67108864") long relayMaxRequestBytes,
        @Positive @DefaultValue("16") int relayRunConcurrency,
        @Positive @DefaultValue("16") int relayHostConcurrencyPerSlot,
        @NotNull @DefaultValue("P30D") Duration keyRotationInterval,
        @NotNull @DefaultValue("PT10M") Duration keyRotationGrace,
        @NotNull @DefaultValue("PT1M") Duration keyRotationSettle,
        @Positive @DefaultValue("268435456") long relayMaxInflightBytes,
        @DefaultValue("true") boolean relayRequireHostToken, @Nullable String gatewayModelUrl,
        @NotNull @DefaultValue("PT30S") Duration relayBodyTimeout, @Nullable List<String> relayAnthropicBetas,
        @Nullable List<String> relayOpenaiBetas, @NotNull @DefaultValue("LITELLM") GatewayVendor gatewayVendor) {

    /**
     * The {@code anthropic-beta} values the relay passes on by default (M1 of the security review of 2026-10-27): the
     * betas of the pinned runtimes that shape a Messages request or its answer (thinking, caching, context, effort,
     * output, mid-conversation changes, tool streaming, the provider's own model fallback), and none that lets the
     * provider act for the request beyond the model: no {@code mcp-client-*} or {@code mcp-servers-*} (the MCP
     * connector), {@code code-execution-*}, {@code web-fetch-*}, {@code web-search-*} (web search needs no beta),
     * {@code advanced-tool-use-*} (programmatic tool calls in a code container), {@code advisor-tool-*},
     * {@code skills-*}, {@code files-api-*}, {@code message-batches-*}, {@code oauth-*} or
     * {@code oidc-federation-*}, nor the betas of Anthropic's other APIs (managed agents, environments, memory,
     * tunnels). Each list item is checked where it was found: Claude Code 2.1.287 (claude-agent-acp 0.87.0), pi 1.0.4
     * (pi-ai), and the Anthropic SDK of OpenCode 2.0.29.
     */
    static final List<String> ANTHROPIC_BETAS = List.of(
            // Claude Code: its own, and the features it asks for.
            "claude-code-20250219", "interleaved-thinking-2025-05-14", "context-1m-2025-08-07",
            "context-management-2025-06-27", "structured-outputs-2025-12-15", "effort-2025-11-24",
            "task-budgets-2026-03-13", "prompt-caching-scope-2026-01-05", "prompt-caching-evict-2026-05-12",
            "cache-keepalive-2026-09-03", "extended-cache-ttl-2025-04-11", "fast-mode-2026-02-01",
            "redact-thinking-2026-02-12", "thinking-resumption-2026-07-17", "thinking-token-count-2026-05-13",
            "thinking-display-updates-2026-08-18", "thinking-binding-controls-2026-08-01", "cache-diagnosis-2026-04-07",
            "context-hint-2026-04-09", "mid-conversation-system-2026-04-07",
            "mid-conversation-system-clear-at-2026-08-21", "mid-conversation-tool-changes-2026-07-01",
            "per-turn-control-2026-07-01", "timing-2026-09-09", "server-side-fallback-2026-06-01",
            "server-side-fallback-2026-07-01", "fallback-credit-2026-06-01", "afk-mode-2026-01-31",
            "auto-mode-classifier-2026-07-16", "dangerous-tool-use-2026-09-03", "token-counting-2024-11-01",
            // Its tool search over the request's own deferred tools, which it turns off behind a gateway: the tool
            // types pass (LiteLlmRequests), so their beta does.
            "tool-search-tool-2025-10-19",
            // pi: tool streaming, output settings and tool definitions inside the conversation (their types are
            // checked as the request's tools are, LiteLlmRequests).
            "fine-grained-tool-streaming-2025-05-14", "mid-conversation-output-config-2026-07-01",
            "inline-tools-2026-09-15",
            // The SDKs' older ones (OpenCode's and pi's providers): tool and output size settings, prompt caching.
            "token-efficient-tools-2025-02-19", "output-128k-2025-02-19", "prompt-caching-2024-07-31");

    /**
     * The {@code OpenAI-Beta} values the relay passes on by default: Codex 0.156.1 sends the header only over the
     * Responses WebSocket, which the relay does not serve; older versions sent {@code responses=experimental}.
     */
    static final List<String> OPENAI_BETAS = List.of("responses=experimental");

    /** The shortest rotation interval. */
    static final Duration MIN_ROTATION_INTERVAL = Duration.ofDays(1);

    /** The longest grace of a rotated-out key, and the longest wait before it is deleted. */
    static final Duration MAX_ROTATION_GRACE = Duration.ofHours(1);

    ModelProperties {
        relayAnthropicBetas = relayAnthropicBetas != null ? List.copyOf(relayAnthropicBetas) : ANTHROPIC_BETAS;
        relayOpenaiBetas = relayOpenaiBetas != null ? List.copyOf(relayOpenaiBetas) : OPENAI_BETAS;
        if (relayBodyTimeout != null && (relayBodyTimeout.isNegative() || relayBodyTimeout.isZero())) {
            throw new IllegalArgumentException("tinfirm.model.relay-body-timeout must be positive");
        }
        if (keyRotationInterval != null && keyRotationInterval.compareTo(MIN_ROTATION_INTERVAL) < 0) {
            throw new IllegalArgumentException("tinfirm.model.key-rotation-interval must be at least P1D (a day)");
        }
        if (keyRotationGrace != null
                && (keyRotationGrace.isNegative() || keyRotationGrace.compareTo(MAX_ROTATION_GRACE) > 0)) {
            throw new IllegalArgumentException("tinfirm.model.key-rotation-grace must be between PT0S and PT1H");
        }
        if (keyRotationSettle != null
                && (keyRotationSettle.isNegative() || keyRotationSettle.compareTo(MAX_ROTATION_GRACE) > 0)) {
            throw new IllegalArgumentException("tinfirm.model.key-rotation-settle must be between PT0S and PT1H");
        }
        if (relayMaxRequestBytes > 0 && relayMaxInflightBytes / 2 < relayMaxRequestBytes) {
            throw new IllegalArgumentException("tinfirm.model.relay-max-inflight-bytes (" + relayMaxInflightBytes
                    + ") must be at least twice tinfirm.model.relay-max-request-bytes (" + relayMaxRequestBytes + ")");
        }
        if (gatewayModelUrl != null && !gatewayModelUrl.isBlank() && !modelUrl(gatewayModelUrl.trim())) {
            throw new IllegalArgumentException("TINFIRM_MODEL_GATEWAY_MODEL_URL must be an http(s) URL without "
                    + "credentials, a query or a fragment, e.g. http://model-api:4001");
        }
        if (gatewayVendor == GatewayVendor.GENERIC) {
            throw new IllegalArgumentException("TINFIRM_MODEL_GATEWAY_VENDOR must be LITELLM: Tinfirm manages the "
                    + "bundled gateway's keys, and GENERIC is for a company's gateway whose keys its team provides");
        }
    }

    /** An address the model requests, with their keys, may go to: http(s), a host, no credentials, query or fragment. */
    private static boolean modelUrl(String url) {
        try {
            URI uri = new URI(url);
            String scheme = uri.getScheme() != null ? uri.getScheme().toLowerCase(Locale.ROOT) : "";
            return ("http".equals(scheme) || "https".equals(scheme)) && uri.getHost() != null
                    && uri.getRawUserInfo() == null && uri.getRawQuery() == null && uri.getRawFragment() == null;
        }
        catch (URISyntaxException ex) {
            return false;
        }
    }

    /** Where the server sends model requests to the bundled gateway: its model port, or else its admin URL. */
    @Nullable String bundledModelUrl() {
        return gatewayModelUrl != null && !gatewayModelUrl.isBlank() ? gatewayModelUrl.trim() : gatewayAdminUrl;
    }

    /** Whether the deployment bundles a gateway: both its URL and its admin key are set. */
    boolean bundled() {
        return gatewayAdminUrl != null && !gatewayAdminUrl.isBlank() && gatewayAdminKey != null
                && !gatewayAdminKey.isBlank();
    }

    @Override
    public String toString() {
        return "ModelProperties[gatewayVendor=" + gatewayVendor + ", gatewayAdminUrl=" + gatewayAdminUrl
                + ", gatewayAdminKey=" + (gatewayAdminKey == null || gatewayAdminKey.isBlank() ? "unset" : "***")
                + ", adminTimeout=" + adminTimeout + ", reconcileInterval=" + reconcileInterval + ", relayIdleTimeout="
                + relayIdleTimeout
                + ", relayMaxRequestBytes=" + relayMaxRequestBytes + ", relayRunConcurrency=" + relayRunConcurrency
                + ", relayHostConcurrencyPerSlot=" + relayHostConcurrencyPerSlot + ", keyRotationInterval="
                + keyRotationInterval + ", keyRotationGrace=" + keyRotationGrace + ", keyRotationSettle="
                + keyRotationSettle + ", relayMaxInflightBytes=" + relayMaxInflightBytes + ", relayRequireHostToken="
                + relayRequireHostToken + ", gatewayModelUrl=" + gatewayModelUrl + ", relayBodyTimeout="
                + relayBodyTimeout + ", relayAnthropicBetas=" + relayAnthropicBetas + ", relayOpenaiBetas="
                + relayOpenaiBetas + "]";
    }
}
