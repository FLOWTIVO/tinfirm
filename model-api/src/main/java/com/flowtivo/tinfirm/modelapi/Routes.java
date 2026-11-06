package com.flowtivo.tinfirm.modelapi;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;

/**
 * The routes of LiteLLM's model API the model port serves (ADR-0069), each by its exact path, and nothing else: no
 * key, team, user, model, spend or budget management, no pass-through route, no health, no UI. A request names one of
 * these paths as written, with no trailing slash, no encoding and no dot segment, and goes to LiteLLM at the path of
 * this table, never at the request's own; its query is dropped.
 */
final class Routes {

    /** A model route: its method and path, and whether it carries a JSON body. */
    record Route(String method, String path, boolean body) {
    }

    private static final List<Route> ALL = List.of(
            // OpenAI's chat, completions, responses and embeddings, at /v1 and at LiteLLM's root.
            post("/v1/chat/completions"), post("/chat/completions"),
            post("/v1/completions"), post("/completions"),
            post("/v1/responses"), post("/responses"),
            post("/v1/embeddings"), post("/embeddings"),
            // Anthropic's messages, as Claude Code sends them.
            post("/v1/messages"), post("/v1/messages/count_tokens"),
            // Rerankers (Cohere's format, which LiteLLM serves for every reranker).
            post("/v1/rerank"), post("/rerank"),
            // The models the caller's key may use.
            new Route("GET", "/v1/models", false), new Route("GET", "/models", false));

    private static final Map<String, Route> BY_PATH = ALL.stream()
        .collect(Collectors.toUnmodifiableMap(Route::path, Function.identity()));

    private Routes() {
    }

    private static Route post(String path) {
        return new Route("POST", path, true);
    }

    /** The route of a request's raw path, or null for any other path. */
    static @Nullable Route of(String rawPath) {
        return BY_PATH.get(rawPath);
    }

    static List<Route> all() {
        return ALL;
    }
}
