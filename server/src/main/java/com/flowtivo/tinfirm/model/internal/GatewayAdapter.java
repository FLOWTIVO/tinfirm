package com.flowtivo.tinfirm.model.internal;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.flowtivo.tinfirm.model.SpendLogs;

import tools.jackson.databind.JsonNode;

/**
 * What the model module asks of a company's gateway, whatever product it is (ADR-0094): the keys Tinfirm manages there
 * (one per principal and the company's own), the company's team, the models it serves, its spend logs and prices, and
 * a chat completion for Tinfirm's own requests. One implementation per {@link GatewayVendor}; {@link GatewayAdapters}
 * passes each call to the one of its {@link Gateway}. Every call is made outside a transaction, and fails with
 * {@link LiteLlmException}.
 */
interface GatewayAdapter {

    /** The vendor this adapter speaks to. */
    GatewayVendor vendor();

    /**
     * What a new key should be; {@code spend} what it starts with (null: nothing, a principal's first key).
     * {@code period} is Tinfirm's budget period, a number and {@code h}, {@code d}, {@code w} or {@code mo}
     * ({@code model_settings.budget_period}), which each adapter writes in its vendor's terms.
     */
    record KeySpec(String alias, String teamId, BigDecimal maxBudget, String period, List<String> models,
            Map<String, String> metadata, boolean blocked, @Nullable BigDecimal spend) {
    }

    /** A key the gateway created; {@link #toString()} never shows it. */
    record CreatedKey(String key, String keyId) {

        @Override
        public String toString() {
            return "CreatedKey[keyId=" + keyId + ", key=***]";
        }
    }

    /** A key as the gateway knows it, its budget's period in Tinfirm's terms (null when it has none Tinfirm reads). */
    record KeyState(String keyId, @Nullable String alias, boolean blocked, double spend, @Nullable Double maxBudget,
            @Nullable String period, @Nullable Instant budgetResetAt, List<String> models) {
    }

    /**
     * What a gateway's refusal of a relayed model request means for the key that paid (ADR-0094): its budget is spent,
     * the gateway refused the key itself, it does not serve the model, or anything else.
     */
    enum Refused {
        BUDGET_SPENT, KEY_REFUSED, MODEL_NOT_SERVED, OTHER
    }

    /** A change of a key; null fields stay as they are. */
    record KeyUpdate(@Nullable BigDecimal maxBudget, @Nullable String period, @Nullable List<String> models) {

        boolean empty() {
            return maxBudget == null && period == null && models == null;
        }
    }

    CreatedKey generate(Gateway gateway, KeySpec spec);

    void update(Gateway gateway, String keyId, KeyUpdate update);

    /** Sets the key's spend in its current period. */
    void setSpend(Gateway gateway, String keyId, BigDecimal spend);

    void block(Gateway gateway, String keyId);

    void unblock(Gateway gateway, String keyId);

    /** Deletes the key by its id; false when the gateway knows no such key. */
    boolean delete(Gateway gateway, String keyId);

    /** Deletes the key of the alias; false when the gateway knows none. */
    boolean deleteByAlias(Gateway gateway, String alias);

    /** The key as the gateway knows it; empty when it knows no such key, or deleted it. */
    Optional<KeyState> info(Gateway gateway, String keyId);

    /** Every key of the team. */
    List<KeyState> teamKeys(Gateway gateway, String teamId);

    /** Creates the company's team unless the gateway knows it, and gives it the company's name. */
    void ensureTeam(Gateway gateway, String teamId, UUID companyId, String companyName);

    /** The model names the gateway serves, sorted, without duplicates. */
    List<String> models(Gateway gateway);

    /** The spend logs of one key from {@code from}'s day to {@code to}'s day (UTC), one entry per request. */
    List<SpendLogs.Entry> spendLogs(Gateway gateway, String keyId, LocalDate from, LocalDate to);

    /** The gateway's model prices, by the names a request or a spend log may give a model. */
    Map<String, SpendLogs.Price> prices(Gateway gateway);

    /**
     * What the gateway's refusal of a relayed model request means: its status and its body, already
     * {@linkplain #redact redacted}.
     */
    Refused classify(Gateway gateway, int status, String body);

    /** A text of the gateway's without what it echoes of a key. */
    String redact(Gateway gateway, String text);

    /**
     * The headers that tag a request for the gateway's own records: {@code tags} is the run, its conversation and agent
     * ({@code run:…,conversation:…,agent:…}), or a platform request's purpose ({@code tinfirm-task:…}).
     */
    Map<String, String> tagHeaders(Gateway gateway, String tags);

    /**
     * One chat completion in OpenAI's format with a key, for the server's own requests on the company's own key
     * (ADR-0065), tagged with {@code tags} in the gateway's spend logs.
     */
    JsonNode chat(Gateway gateway, String key, String tags, JsonNode body, Duration chatTimeout);
}
