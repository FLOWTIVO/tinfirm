package com.flowtivo.tinfirm.model;

import java.util.UUID;

/**
 * Tinfirm's own model requests (ADR-0065): paid by the company's own LiteLLM key, whose budget and models OWNER and
 * ADMIN set in Model access, never by a person's or a trigger's payer. Each use names its model, which its own setting
 * chooses among those the key allows (a memory's compaction: the company's memory settings, ADR-0063). Called outside
 * any transaction: it reads Tinfirm's rows in short transactions of its own and makes one request to the company's
 * LiteLLM.
 */
public interface PlatformModel {

    /**
     * One request on the company's own key: a system and a user message to the model, at most {@code maxTokens} out,
     * tagged with {@code purpose} in LiteLLM ({@code tinfirm-task:{purpose}}).
     */
    Completion complete(UUID companyId, String purpose, String model, String system, String user, int maxTokens);

    /**
     * Whether the company's own key allows the model: its own models, else the company's default ones; none allows
     * every model the gateway serves. Read in the caller's transaction.
     */
    boolean allows(UUID companyId, String model);

    /** What a request answered. */
    sealed interface Completion {

        /** The model's text and what it counted. */
        record Answer(String text, String model, long inputTokens, long outputTokens) implements Completion {
        }

        /**
         * No answer: {@code GATEWAY_NOT_CONFIGURED}, {@code KEY_ERROR} (the key is not created yet, or LiteLLM refuses
         * it), {@code BUDGET_SPENT}, {@code MODEL_NOT_ALLOWED} (the key does not allow the model), {@code FAILED}
         * (LiteLLM refused or did not answer, with its redacted reason).
         */
        record Unavailable(String reason, String detail) implements Completion {
        }
    }
}
