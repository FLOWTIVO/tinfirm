package com.flowtivo.tinfirm.model.internal;

import org.jspecify.annotations.Nullable;

import com.flowtivo.tinfirm.shared.litellm.LiteLlmRequests;

import tools.jackson.databind.json.JsonMapper;

/**
 * A relayed request's body as LiteLLM gets it (the package 2 contract, §6.1): without LiteLLM's own fields at its
 * top level ({@link LiteLlmRequests}, shared with the gateway's model port), and with {@code "drop_params": true}
 * added last, so that LiteLLM drops a parameter the model's provider does not take (a runtime's
 * {@code reasoning_effort} for a model LiteLLM does not know to reason, say) instead of refusing the request and
 * failing the turn; v1.105.1 does so by itself only for Claude Code and Codex, by their {@code User-Agent}. A run's
 * own {@code drop_params} and {@code additional_drop_params} do not count. The body must be one JSON object without a
 * repeated top-level name (LiteLLM would take the last {@code model}, Tinfirm checks one); its {@code model} is read
 * for the check. Without {@code mcp_servers} and {@code container}, and refused when it names a tool the provider would
 * run that the company's {@code providerTools} does not allow (M1 of the security review of 2026-10-27).
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

    /**
     * Whether a top-level name is removed: one of LiteLLM's own fields, {@code mcp_servers} or {@code container}, or
     * the run's own {@code drop_params}.
     */
    static boolean removed(String name) {
        return LiteLlmRequests.liteLlmField(name) || LiteLlmRequests.providerAction(name)
                || LiteLlmRequests.dropControl(name);
    }

    /** The body without LiteLLM's fields, with {@code "drop_params": true}; no tool of the provider's allowed. */
    static Result transform(JsonMapper mapper, byte[] body) throws Refused {
        return transform(mapper, body, LiteLlmRequests.ProviderTools.OFF);
    }

    /** The body without LiteLLM's fields, with {@code "drop_params": true}; the provider's tools as chosen. */
    static Result transform(JsonMapper mapper, byte[] body, LiteLlmRequests.ProviderTools providerTools)
            throws Refused {
        try {
            LiteLlmRequests.Cleaned cleaned = LiteLlmRequests.clean(mapper, body, true, providerTools);
            return new Result(cleaned.bytes(), cleaned.model());
        }
        catch (LiteLlmRequests.Refused ex) {
            throw new Refused(ex.getMessage());
        }
    }
}
