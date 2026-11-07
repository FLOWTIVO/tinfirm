package com.flowtivo.tinfirm.model.internal;

import org.jspecify.annotations.Nullable;

import com.flowtivo.tinfirm.shared.litellm.LiteLlmRequests;

import tools.jackson.databind.json.JsonMapper;

/**
 * A relayed request's body as the gateway gets it (the package 2 contract, §6.1): without LiteLLM's own fields at its
 * top level ({@link LiteLlmRequests}), whatever the gateway's vendor, and for a LiteLLM with
 * {@code "drop_params": true} added last, so that LiteLLM drops a parameter the model's provider does not take (a
 * runtime's {@code reasoning_effort} for a model LiteLLM does not know to reason, say) instead of refusing the request
 * and failing the turn; v1.105.1 does so by itself only for Claude Code and Codex, by their {@code User-Agent}.
 * A run's own {@code drop_params} and {@code additional_drop_params} then do not count. Another vendor's gateway gets
 * no {@code drop_params} from Tinfirm: it is LiteLLM's own control, which a provider given the body as it came refuses.
 * The body must be one JSON object without a repeated top-level name (LiteLLM would take the last {@code model},
 * Tinfirm checks one); its {@code model} is read for the check. Refused, whatever the gateway's vendor, when it asks
 * the model's provider to run a tool itself: an MCP server, code execution, a web search, any tool the runtime does
 * not run (M1 of the security review of 2026-10-27; ADR-0108). No company setting lets one through.
 */
final class RelayBody {

    private RelayBody() {
    }

    /** The body to forward and the model it names (null when it names none). */
    record Result(byte[] bytes, @Nullable String model) {
    }

    /** Why a body cannot be relayed; the message is for the caller. */
    static final class Refused extends Exception {

        private static final long serialVersionUID = 1L;

        Refused(String message) {
            super(message, null, false, false);
        }
    }

    /** A JSON reader for bodies of up to {@code maxBytes}, whose strings may be as long as the body. */
    static JsonMapper mapper(long maxBytes) {
        return LiteLlmRequests.mapper(maxBytes);
    }

    /** Whether a top-level name is removed: one of LiteLLM's own fields, or the run's own {@code drop_params}. */
    static boolean removed(String name) {
        return LiteLlmRequests.liteLlmField(name) || LiteLlmRequests.dropControl(name);
    }

    /**
     * The body without LiteLLM's fields, with {@code "drop_params": true}; refused when it asks the provider to run a
     * tool itself.
     */
    static Result transform(JsonMapper mapper, byte[] body) throws Refused {
        return transform(mapper, body, true);
    }

    /**
     * The body without LiteLLM's fields, with {@code "drop_params": true} when {@code dropParams} (a LiteLLM), else
     * with the run's own as it sent them; refused when it asks the provider to run a tool itself.
     */
    static Result transform(JsonMapper mapper, byte[] body, boolean dropParams) throws Refused {
        try {
            LiteLlmRequests.Cleaned cleaned = LiteLlmRequests.clean(mapper, body, dropParams);
            return new Result(cleaned.bytes(), cleaned.model());
        }
        catch (LiteLlmRequests.Refused ex) {
            throw new Refused(ex.getMessage());
        }
    }
}
