package com.flowtivo.tinfirm.modelapi;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;

import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import com.flowtivo.tinfirm.shared.litellm.LiteLlmRequests;

/**
 * The model port's settings (ADR-0069; the variables are in model-api/README.md), checked at startup.
 *
 * @param upstream        LiteLLM's own port, which serves every route ({@code MODEL_API_UPSTREAM}): beside it in its
 *                        pod, {@code http://127.0.0.1:4000}
 * @param maxRequestBytes the largest request body passed on ({@code MODEL_API_MAX_REQUEST_BYTES}, 64 MiB, as the
 *                        server's relay); a larger one is {@code 413}
 * @param maxInflightBytes the most bytes of request bodies the port holds at once, each body counted twice while it
 *                        is read and written anew, once while its answer streams ({@code MODEL_API_MAX_INFLIGHT_BYTES},
 *                        128 MiB, within the container's memory), counted as a body's bytes arrive; a request that would pass it is
 *                        {@code 429}.
 *                        At least twice {@code maxRequestBytes}, so that the largest body fits
 * @param connectTimeout  how long a connection to LiteLLM may take
 * @param idleTimeout     the longest wait for LiteLLM's answer to start, and between two of its parts
 *                        ({@code MODEL_API_IDLE_TIMEOUT}); a streaming answer has no total limit
 * @param providerTools   which tools the model's provider may run itself for a request
 *                        ({@code MODEL_API_PROVIDER_TOOLS}): {@code OFF} (the default), none, so that a request that
 *                        names one (web search or fetch, code execution, MCP) is {@code 400}; {@code WEB_SEARCH}, the
 *                        providers' web search. The server's relay applies each company's own choice before the port,
 *                        so that the stricter of the two holds for a run (M1 of the security review of 2026-10-27)
 */
@Validated
@ConfigurationProperties("model-api")
public record ModelApiProperties(@NotNull URI upstream, @DefaultValue("67108864") long maxRequestBytes,
        @DefaultValue("134217728") long maxInflightBytes, @DefaultValue("PT10S") Duration connectTimeout,
        @DefaultValue("PT10M") Duration idleTimeout,
        @DefaultValue("OFF") LiteLlmRequests.ProviderTools providerTools) {

    public ModelApiProperties {
        String scheme = upstream.getScheme() != null ? upstream.getScheme().toLowerCase(Locale.ROOT) : "";
        String path = upstream.getRawPath() != null ? upstream.getRawPath() : "";
        if (upstream.getHost() == null || !("http".equals(scheme) || "https".equals(scheme))
                || upstream.getRawUserInfo() != null || upstream.getRawQuery() != null
                || !(path.isEmpty() || "/".equals(path))) {
            throw new IllegalArgumentException("MODEL_API_UPSTREAM must be LiteLLM's root URL without a path, query "
                    + "or credentials, e.g. http://127.0.0.1:4000");
        }
        if (maxRequestBytes < 1024 || maxRequestBytes > 1024L * 1024 * 1024) {
            throw new IllegalArgumentException("MODEL_API_MAX_REQUEST_BYTES must be between 1024 and 1073741824");
        }
        if (maxInflightBytes / 2 < maxRequestBytes) {
            throw new IllegalArgumentException("MODEL_API_MAX_INFLIGHT_BYTES (" + maxInflightBytes + ") must be at "
                    + "least twice MODEL_API_MAX_REQUEST_BYTES (" + maxRequestBytes + "), so that the largest body fits");
        }
        if (connectTimeout.isNegative() || connectTimeout.isZero() || idleTimeout.isNegative()
                || idleTimeout.isZero()) {
            throw new IllegalArgumentException("The model port's timeouts must be positive");
        }
    }

    /** LiteLLM's root, without a trailing slash. */
    public String upstreamRoot() {
        String root = upstream.toString();
        return root.endsWith("/") ? root.substring(0, root.length() - 1) : root;
    }
}
