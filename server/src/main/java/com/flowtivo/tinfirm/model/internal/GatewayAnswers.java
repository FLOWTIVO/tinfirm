package com.flowtivo.tinfirm.model.internal;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

import com.flowtivo.tinfirm.shared.net.BoundedBodies;
import com.flowtivo.tinfirm.shared.net.GuardedHosts;
import com.flowtivo.tinfirm.shared.tx.NoTransaction;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * What every gateway adapter reads the same way (ADR-0094): an answer within its limit, its JSON, its reason without
 * what it echoes of a key, and the two calls of the model API that any gateway serves in OpenAI's format: one chat
 * completion with a key, for Tinfirm's own requests on the company's key (ADR-0065), and the model list a key may use
 * ({@code GET /v1/models}), which checks a key a company's gateway team provided. Their answers are read within
 * {@link #MAX_MODEL_ANSWER_BYTES} and within the call's timeout, body included ({@link BoundedBodies}): a body that has
 * not ended by then fails the call as one whose headers never came. {@code who} names the gateway in the messages,
 * which are for people and never hold a key.
 */
final class GatewayAnswers {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final int MAX_REASON = 300;

    /**
     * The most bytes of a chat completion's or a model list's answer the server reads: 1 MiB. The platform's requests
     * ask for a few thousand tokens at most (a memory's compaction 4000, some 16 KiB of text), and a gateway's answer
     * of that many, with its envelope and a reasoning model's thinking, stays under 100 KiB.
     */
    static final int MAX_MODEL_ANSWER_BYTES = 1024 * 1024;

    private static final Pattern KEY = Pattern.compile("sk-[^\\s,;\"'`)\\]}]*");

    private static final Pattern HEX_64 = Pattern.compile("(?i)\\b[0-9a-f]{64}\\b");

    private GatewayAnswers() {
    }

    /**
     * A gateway's text without what it echoes of a key: a key of the {@code sk-} form (LiteLLM's 401 names the key it
     * refused, abbreviated) and a key's SHA-256.
     */
    static String redact(String text) {
        return HEX_64.matcher(KEY.matcher(text).replaceAll("sk-***")).replaceAll("***");
    }

    /**
     * A gateway's text without the key Tinfirm sent it, as it came and as a JSON string writes it: a key a company's
     * gateway team provided may have any form (ADR-0094), which {@link #redact} does not know. A JSON text is written
     * anew first, since its writer may have escaped the key's characters its own way ({@code \/}, {@code \u002b}).
     */
    static String withoutKey(String text, String key) {
        if (key.isEmpty()) {
            return text;
        }
        JsonNode parsed = text.isBlank() ? null : parse(text);
        String normalized = parsed != null ? JSON.writeValueAsString(parsed) : text;
        String json = JSON.writeValueAsString(key);
        return normalized.replace(key, "***").replace(json.substring(1, json.length() - 1), "***");
    }

    /** The answer's body, read within its limit; a larger one fails the call with its status. */
    static String within(HttpResponse<@Nullable String> response, int maxBytes, String who) {
        String body = response.body();
        if (body == null) {
            throw new LiteLlmException(response.statusCode(), who + "'s answer (" + response.statusCode()
                    + ") is larger than the " + maxBytes / (1024 * 1024) + " MiB Tinfirm reads");
        }
        return body;
    }

    /** The body as JSON: an empty object for an empty body, null for one that is not JSON. */
    static @Nullable JsonNode parse(@Nullable String body) {
        if (body == null || body.isBlank()) {
            return JSON.createObjectNode();
        }
        try {
            return JSON.readTree(body);
        }
        catch (JacksonException ex) {
            return null;
        }
    }

    /** A gateway's reason for an error: {@code error.message}, {@code detail.error} or {@code detail}, cut short. */
    static String reason(@Nullable JsonNode answer) {
        if (answer == null) {
            return "no reason given";
        }
        JsonNode error = answer.get("error");
        String reason = null;
        if (error != null && error.isObject()) {
            reason = text(error, "message");
        }
        else if (error != null && error.isString()) {
            reason = error.stringValue();
        }
        JsonNode detail = answer.get("detail");
        if (reason == null && detail != null) {
            reason = detail.isString() ? detail.stringValue()
                    : detail.isObject() && text(detail, "error") != null ? text(detail, "error")
                            : detail.isObject() && text(detail, "message") != null ? text(detail, "message") : null;
        }
        if (reason == null) {
            return "no reason given";
        }
        String oneLine = redact(reason).replaceAll("\\s+", " ").strip();
        return oneLine.length() <= MAX_REASON ? oneLine : oneLine.substring(0, MAX_REASON - 1) + "…";
    }

    /**
     * One chat completion in OpenAI's format at the gateway's model URL with a key and the adapter's tag headers;
     * never inside a transaction. The answer's errors are redacted; an answer larger than
     * {@value #MAX_MODEL_ANSWER_BYTES} bytes fails.
     */
    static JsonNode chat(GatewayHttp http, Gateway gateway, String key, Map<String, String> headers, JsonNode body,
            Duration timeout, String who) {
        NoTransaction.require("A model request to the gateway");
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(gateway.modelUrl() + "/v1/chat/completions"))
            .timeout(timeout)
            .header("Authorization", "Bearer " + key)
            .header("Accept", "application/json")
            .header("User-Agent", "Tinfirm")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        headers.forEach(request::header);
        return send(http, gateway, request.build(), key, timeout, who);
    }

    /**
     * The models a key may use at the gateway, sorted, without duplicates ({@code GET /v1/models} at its model URL, in
     * OpenAI's format, which every gateway Tinfirm speaks to serves); never inside a transaction. A key the gateway
     * refuses fails with its status (401, 403).
     */
    static List<String> modelsOfKey(GatewayHttp http, Gateway gateway, String key, Duration timeout, String who) {
        NoTransaction.require("A model list of the gateway");
        HttpRequest request = HttpRequest.newBuilder(URI.create(gateway.modelUrl() + "/v1/models"))
            .timeout(timeout)
            .header("Authorization", "Bearer " + key)
            .header("Accept", "application/json")
            .header("User-Agent", "Tinfirm")
            .GET()
            .build();
        JsonNode data = send(http, gateway, request, key, timeout, who).get("data");
        LinkedHashSet<String> names = new LinkedHashSet<>();
        if (data != null && data.isArray()) {
            for (JsonNode model : data) {
                String id = text(model, "id");
                if (id != null && !id.isBlank()) {
                    names.add(id);
                }
            }
        }
        return names.stream().sorted().toList();
    }

    /** The answer as JSON; a failed one fails with its reason, without the key sent ({@link #withoutKey}). */
    private static JsonNode send(GatewayHttp http, Gateway gateway, HttpRequest request, String key, Duration timeout,
            String who) {
        HttpResponse<@Nullable String> response;
        try (GuardedHosts.Registration guard = http.guard(gateway)) {
            response = http.client().send(request, BoundedBodies.ofString(MAX_MODEL_ANSWER_BYTES, timeout));
        }
        catch (HttpTimeoutException ex) {
            throw LiteLlmException.unreachable(who + " did not answer in time");
        }
        catch (IOException ex) {
            throw LiteLlmException.unreachable(who + " cannot be reached");
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw LiteLlmException.unreachable("Interrupted while calling " + who);
        }
        String body = within(response, MAX_MODEL_ANSWER_BYTES, who);
        boolean failed = response.statusCode() < 200 || response.statusCode() > 299;
        JsonNode answer = parse(failed ? withoutKey(body, key) : body);
        if (failed) {
            throw new LiteLlmException(response.statusCode(),
                    redact(who + " answered " + response.statusCode() + ": " + reason(answer)));
        }
        if (answer == null) {
            throw new LiteLlmException(response.statusCode(), who + "'s answer is not JSON");
        }
        return answer;
    }

    static @Nullable String text(@Nullable JsonNode node, String field) {
        JsonNode value = node != null ? node.get(field) : null;
        return value != null && value.isString() ? value.stringValue() : null;
    }
}
