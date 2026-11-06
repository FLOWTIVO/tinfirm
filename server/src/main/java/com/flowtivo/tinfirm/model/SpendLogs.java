package com.flowtivo.tinfirm.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * LiteLLM's records of what was spent (the package 6 contract, §2.2, §5.1), read with the company's admin key,
 * outside any transaction (CONVENTIONS §10): its spend logs of a key, and its model prices. Each call refuses to start
 * inside a transaction.
 */
public interface SpendLogs {

    /**
     * The spend logs of the keys over the span, as LiteLLM's {@code /spend/logs} answers them, without their
     * prompts: the caller keeps those whose request tags hold its run.
     *
     * @param keyIds LiteLLM's ids of the keys (their SHA-256)
     * @throws Unavailable when the company's gateway has not passed its test, or LiteLLM cannot be read
     */
    List<Entry> of(UUID companyId, Collection<String> keyIds, Instant from, Instant to);

    /**
     * The gateway's model prices per token, by every name a model is known by (its name in the gateway and its
     * provider's); read from LiteLLM's {@code /model/info} at most once an hour per company. Empty when they cannot be
     * read.
     */
    Map<String, Price> prices(UUID companyId);

    /**
     * Whether the company's gateway keeps spend logs Tinfirm reads: false for one whose keys its team provides
     * (ADR-0094), whose runs are settled from the relay's own count at once.
     */
    boolean recorded(UUID companyId);

    /**
     * One request LiteLLM logged.
     *
     * @param model             the model as the request named it (LiteLLM's model group), else as LiteLLM logged it
     * @param inputTokens       the input neither read from nor written to the provider's cache
     * @param tags              the request's tags ({@code run:…}, {@code conversation:…}, {@code agent:…})
     */
    record Entry(String requestId, String keyId, String model, List<String> tags, @Nullable Instant startTime,
            BigDecimal spendUsd, long inputTokens, long outputTokens, long cachedInputTokens, long cacheWriteTokens) {

        public Entry {
            tags = List.copyOf(tags);
        }
    }

    /** A model's price in USD per token; a missing cache price is the input's. */
    record Price(BigDecimal input, BigDecimal output, @Nullable BigDecimal cacheRead, @Nullable BigDecimal cacheWrite) {

        public BigDecimal cost(long inputTokens, long outputTokens, long cachedInputTokens, long cacheWriteTokens) {
            return input.multiply(BigDecimal.valueOf(inputTokens))
                .add(output.multiply(BigDecimal.valueOf(outputTokens)))
                .add((cacheRead != null ? cacheRead : input).multiply(BigDecimal.valueOf(cachedInputTokens)))
                .add((cacheWrite != null ? cacheWrite : input).multiply(BigDecimal.valueOf(cacheWriteTokens)));
        }
    }

    /** LiteLLM's records cannot be read now: no gateway that passed its test, or LiteLLM failed. */
    final class Unavailable extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public Unavailable(String reason) {
            super(reason);
        }
    }
}
