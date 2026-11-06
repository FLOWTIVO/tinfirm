package com.flowtivo.tinfirm.model.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.shared.net.InflightBytes;

/**
 * The relay's reading of an answer's usage (the package 6 contract, §3.1): Anthropic's stream, OpenAI's Responses
 * stream and a plain JSON answer, whatever the parts' boundaries; an answer without usage; no meter for the
 * endpoints that cost nothing; and what the meter keeps in memory (CONVENTIONS §15.4): a plain answer within its cap
 * and the replica's budget of bytes in flight, and one line of a stream, in a buffer reserved from the same budget as
 * it grows, the old array too while it is copied, both given back when the request's share closes; a budget that is
 * full drops the line, or the answer.
 */
class RelayUsageMeterTest {

    /** A budget that counts what the meter holds of it, and the most it ever held, up to {@code allowed}. */
    private static final class Counted implements RelayUsageMeter.Budget {

        private final long allowed;

        private final AtomicLong held = new AtomicLong();

        private long peak;

        Counted(long allowed) {
            this.allowed = allowed;
        }

        @Override
        public boolean reserve(long bytes) {
            assertThat(bytes).isPositive();
            if (held.get() + bytes > allowed) {
                return false;
            }
            peak = Math.max(peak, held.addAndGet(bytes));
            return true;
        }

        @Override
        public void release(long bytes) {
            assertThat(bytes).isNotNegative().isLessThanOrEqualTo(held.get());
            held.addAndGet(-bytes);
        }

        long held() {
            return held.get();
        }

        long peak() {
            return peak;
        }
    }

    private static Counted unlimited() {
        return new Counted(Long.MAX_VALUE);
    }

    private static RelayTokenStore.TokenRow token(RelayTokenStore.RelayFormat format) {
        return new RelayTokenStore.TokenRow("hash", UUID.randomUUID(), UUID.randomUUID(), 1, UUID.randomUUID(),
                UUID.randomUUID(), format, null, null, null);
    }

    /** Feeds the answer in parts of {@code size} bytes, as a stream arrives. */
    private static RelayUsageMeter.Answered meter(RelayTokenStore.RelayFormat format, String endpoint, String answer,
            int size, String requestModel) {
        RelayUsageMeter meter = RelayUsageMeter.of(endpoint, token(format), "sk-test-key", unlimited());
        assertThat(meter).isNotNull();
        return feed(meter, answer, size, requestModel);
    }

    private static RelayUsageMeter.Answered feed(RelayUsageMeter meter, String answer, int size, String requestModel) {
        meter.answered();
        byte[] bytes = answer.getBytes(StandardCharsets.UTF_8);
        for (int offset = 0; offset < bytes.length; offset += size) {
            meter.accept(bytes, offset, Math.min(size, bytes.length - offset));
        }
        return meter.result(requestModel);
    }

    /** A plain JSON answer of about {@code bytes} bytes with this usage: a long text before it. */
    private static String plainAnswer(int bytes, long input, long output) {
        return "{\"type\":\"message\",\"content\":\"" + "x".repeat(bytes) + "\",\"model\":\"claude-x\",\"usage\":{"
                + "\"input_tokens\":" + input + ",\"output_tokens\":" + output + "}}";
    }

    @Test
    void anOpenAiResponsesStreamReportsItsUsageAtCompletion() {
        String stream = """
                event: response.created
                data: {"type":"response.created","response":{"model":"gpt-5.4","usage":null}}

                event: response.completed
                data: {"type":"response.completed","response":{"model":"gpt-5.4","usage":{"input_tokens":900,\
                "input_tokens_details":{"cached_tokens":600},"output_tokens":70}}}

                """;
        for (int size : new int[] {1, 7, 4096}) {
            RelayUsageMeter.Answered answered = meter(RelayTokenStore.RelayFormat.OPENAI, "OPENAI_RESPONSES", stream,
                    size, null);
            assertThat(answered.model()).isEqualTo("gpt-5.4");
            assertThat(answered.inputTokens()).isEqualTo(300);
            assertThat(answered.cachedInputTokens()).isEqualTo(600);
            assertThat(answered.outputTokens()).isEqualTo(70);
            assertThat(answered.keyId()).isEqualTo(RelayUsageMeter.keyId("sk-test-key")).hasSize(64);
        }
    }

    /**
     * Bifrost's stream (v2.3.2) ends with a {@code message_delta} whose usage reports {@code input_tokens: 0} beside
     * the output: the running totals keep the input that {@code message_start} reported.
     */
    @Test
    void anAnthropicStreamKeepsItsRunningTotalsWhenALaterEventReportsZero() {
        String stream = """
                event: message_start
                data: {"type":"message_start","message":{"model":"claude-sonnet-4-5","usage":{"input_tokens":1000,\
                "cache_creation_input_tokens":0,"cache_read_input_tokens":200,"output_tokens":1}}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"hello"}}

                event: message_delta
                data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"input_tokens":0,\
                "cache_creation_input_tokens":0,"cache_read_input_tokens":0,"output_tokens":500}}

                """;
        for (int size : new int[] {1, 13, 4096}) {
            RelayUsageMeter.Answered answered = meter(RelayTokenStore.RelayFormat.ANTHROPIC, "ANTHROPIC_MESSAGES",
                    stream, size, null);
            assertThat(answered.model()).isEqualTo("claude-sonnet-4-5");
            assertThat(answered.inputTokens()).isEqualTo(1000);
            assertThat(answered.cachedInputTokens()).isEqualTo(200);
            assertThat(answered.outputTokens()).isEqualTo(500);
        }
    }

    @Test
    void aPlainAnthropicAnswerAndOneWithoutUsage() {
        RelayUsageMeter.Answered plain = meter(RelayTokenStore.RelayFormat.ANTHROPIC, "ANTHROPIC_MESSAGES", """
                {"type":"message","model":"claude-x","usage":{"input_tokens":5,"cache_creation_input_tokens":2,
                 "cache_read_input_tokens":3,"output_tokens":9}}""", 3, "claude-sonnet-4-5");
        assertThat(plain.model()).isEqualTo("claude-sonnet-4-5");
        assertThat(plain.inputTokens()).isEqualTo(5);
        assertThat(plain.cacheWriteTokens()).isEqualTo(2);
        assertThat(plain.cachedInputTokens()).isEqualTo(3);
        assertThat(plain.outputTokens()).isEqualTo(9);

        RelayUsageMeter.Answered bare = meter(RelayTokenStore.RelayFormat.ANTHROPIC, "ANTHROPIC_MESSAGES",
                "{\"type\":\"message\"}", 100, null);
        assertThat(bare.model()).isEqualTo("unknown");
        assertThat(bare.inputTokens() + bare.outputTokens()).isZero();
    }

    @Test
    void aPlainAnswerBeyondTheCapCountsARequestWithoutTokensAndKeepsNoMoreThanTheCap() {
        Counted budget = unlimited();
        RelayUsageMeter meter = RelayUsageMeter.of("ANTHROPIC_MESSAGES", token(RelayTokenStore.RelayFormat.ANTHROPIC),
                "k", budget);
        AtomicLong peak = new AtomicLong();
        meter.answered();
        byte[] bytes = plainAnswer(RelayUsageMeter.MAX_JSON + 1024, 5, 9).getBytes(StandardCharsets.UTF_8);
        for (int offset = 0; offset < bytes.length; offset += 16 * 1024) {
            meter.accept(bytes, offset, Math.min(16 * 1024, bytes.length - offset));
            peak.set(Math.max(peak.get(), meter.retained()));
            // The budget holds the buffer's whole capacity, never less than what it keeps.
            assertThat(budget.held()).isEqualTo(meter.capacity()).isGreaterThanOrEqualTo(meter.retained());
        }
        RelayUsageMeter.Answered answered = meter.result("claude-sonnet-4-5");
        assertThat(answered).isNotNull();
        assertThat(answered.model()).isEqualTo("claude-sonnet-4-5");
        assertThat(answered.inputTokens() + answered.outputTokens()).isZero();
        assertThat(peak.get()).isLessThanOrEqualTo(RelayUsageMeter.MAX_JSON);
        // The last growth held the old array beside the new one while it was copied.
        assertThat(budget.peak()).isEqualTo(RelayUsageMeter.MAX_JSON / 2 + RelayUsageMeter.MAX_JSON);
        assertThat(budget.held()).as("an answer beyond the cap gives its buffer back at once").isZero();
        assertThat(meter.retained()).isZero();

        // Within the cap, the answer is read whole.
        RelayUsageMeter.Answered within = meter(RelayTokenStore.RelayFormat.ANTHROPIC, "ANTHROPIC_MESSAGES",
                plainAnswer(1024 * 1024, 5, 9), 16 * 1024, null);
        assertThat(within.inputTokens()).isEqualTo(5);
        assertThat(within.outputTokens()).isEqualTo(9);
    }

    @Test
    void aBudgetThatRefusesLeavesTheAnswerUnmeteredAndKeepsNothing() {
        long allowed = 100;
        Counted budget = new Counted(allowed);
        RelayUsageMeter meter = RelayUsageMeter.of("OPENAI_CHAT_COMPLETIONS", token(RelayTokenStore.RelayFormat.OPENAI),
                "k", budget);
        meter.answered();
        byte[] bytes = ("{\"model\":\"gpt-5.4\",\"content\":\"" + "y".repeat(300) + "\",\"usage\":{\"prompt_tokens\":7,"
                + "\"completion_tokens\":3}}").getBytes(StandardCharsets.UTF_8);
        for (int offset = 0; offset < bytes.length; offset += 64) {
            meter.accept(bytes, offset, Math.min(64, bytes.length - offset));
            assertThat(meter.retained()).isLessThanOrEqualTo((int) allowed);
        }
        assertThat(meter.retained()).isZero();
        RelayUsageMeter.Answered answered = meter.result(null);
        assertThat(answered).isNotNull();
        assertThat(answered.model()).isEqualTo("unknown");
        assertThat(answered.inputTokens() + answered.outputTokens()).isZero();
        assertThat(budget.held()).isZero();
    }

    @Test
    void whatAPlainAnswerTakesOfTheBudgetGoesBackWhenTheShareCloses() {
        InflightBytes inflight = new InflightBytes(1024 * 1024);
        String answer = plainAnswer(4096, 11, 13);
        try (InflightBytes.Share share = inflight.share()) {
            RelayUsageMeter meter = RelayUsageMeter.of("ANTHROPIC_MESSAGES",
                    token(RelayTokenStore.RelayFormat.ANTHROPIC), "k", RelayUsageMeter.Budget.of(share));
            RelayUsageMeter.Answered answered = feed(meter, answer, 1000, null);
            assertThat(answered.inputTokens()).isEqualTo(11);
            assertThat(answered.outputTokens()).isEqualTo(13);
            // A small answer: the first step of the buffer, held until the share closes.
            assertThat(inflight.held()).isEqualTo(RelayUsageMeter.LINE_STEP);
        }
        assertThat(inflight.held()).isZero();

        // A budget too small for the answer: nothing kept, nothing left held.
        InflightBytes small = new InflightBytes(1000);
        try (InflightBytes.Share share = small.share()) {
            RelayUsageMeter meter = RelayUsageMeter.of("ANTHROPIC_MESSAGES",
                    token(RelayTokenStore.RelayFormat.ANTHROPIC), "k", RelayUsageMeter.Budget.of(share));
            RelayUsageMeter.Answered answered = feed(meter, answer, 700, null);
            assertThat(answered.inputTokens() + answered.outputTokens()).isZero();
            assertThat(meter.retained()).isZero();
            assertThat(small.held()).isLessThanOrEqualTo(1000);
        }
        assertThat(small.held()).isZero();
    }

    /**
     * A growth takes the new array beside the old one: a budget with room for the grown buffer alone, not for both,
     * drops the line rather than hold more than it counts, and the lines after it are still read.
     */
    @Test
    void aGrowthTheBudgetCannotHoldBesideTheOldBufferDropsTheLine() {
        // Room for the second step (128 KiB) alone, not beside the first (64 KiB).
        Counted budget = new Counted(2L * RelayUsageMeter.LINE_STEP);
        RelayUsageMeter meter = RelayUsageMeter.of("ANTHROPIC_MESSAGES", token(RelayTokenStore.RelayFormat.ANTHROPIC),
                "k", budget);
        meter.answered();
        byte[] bytes = streamWithLongLine(100 * 1024).getBytes(StandardCharsets.UTF_8);
        for (int offset = 0; offset < bytes.length; offset += 4096) {
            meter.accept(bytes, offset, Math.min(4096, bytes.length - offset));
            assertThat(budget.held()).isEqualTo(meter.capacity());
        }
        RelayUsageMeter.Answered answered = meter.result(null);
        assertThat(answered.inputTokens()).as("the long line was dropped").isEqualTo(1000);
        assertThat(answered.outputTokens()).as("the line after it was read").isEqualTo(42);
        assertThat(budget.peak()).isEqualTo(RelayUsageMeter.LINE_STEP);
    }

    private static final String STREAM = "event: message_start\n"
            + "data: {\"type\":\"message_start\",\"message\":{\"model\":\"claude-x\",\"usage\":{"
            + "\"input_tokens\":1000,\"output_tokens\":1}}}\n\n"
            + "event: content_block_delta\n"
            + "data: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"Hi\"}}\n\n"
            + "event: message_delta\n"
            + "data: {\"type\":\"message_delta\",\"usage\":{\"output_tokens\":42}}\n\n";

    /** Anthropic's stream with a {@code content_block_delta} line of about {@code bytes} bytes between its usage lines. */
    private static String streamWithLongLine(int bytes) {
        return "event: message_start\n"
                + "data: {\"type\":\"message_start\",\"message\":{\"model\":\"claude-x\",\"usage\":{"
                + "\"input_tokens\":1000,\"output_tokens\":1}}}\n\n"
                + "event: content_block_delta\n"
                + "data: {\"type\":\"content_block_delta\",\"usage\":{\"input_tokens\":999999},\"delta\":{\"text\":\""
                + "z".repeat(bytes) + "\"}}\n\n"
                + "event: message_delta\n"
                + "data: {\"type\":\"message_delta\",\"usage\":{\"output_tokens\":42}}\n\n";
    }

    @Test
    void aStreamLineBeyondTheLimitIsDroppedAndTheLinesAfterItStillRead() {
        Counted budget = unlimited();
        RelayUsageMeter meter = RelayUsageMeter.of("ANTHROPIC_MESSAGES", token(RelayTokenStore.RelayFormat.ANTHROPIC),
                "k", budget);
        meter.answered();
        byte[] bytes = streamWithLongLine(RelayUsageMeter.MAX_LINE + 1).getBytes(StandardCharsets.UTF_8);
        int peak = 0;
        for (int offset = 0; offset < bytes.length; offset += 16 * 1024) {
            meter.accept(bytes, offset, Math.min(16 * 1024, bytes.length - offset));
            peak = Math.max(peak, meter.retained());
            // The budget counts the buffer's whole capacity, never less than what it holds.
            assertThat(budget.held()).isEqualTo(meter.capacity()).isGreaterThanOrEqualTo(meter.retained());
        }
        RelayUsageMeter.Answered answered = meter.result(null);
        assertThat(answered.model()).isEqualTo("claude-x");
        assertThat(answered.inputTokens()).isEqualTo(1000);
        assertThat(answered.outputTokens()).isEqualTo(42);
        assertThat(peak).isLessThanOrEqualTo(RelayUsageMeter.MAX_LINE);
        assertThat(budget.held()).as("the line's high-water mark, at most the cap").isEqualTo(RelayUsageMeter.MAX_LINE);
        // While the buffer grew to the cap, the old array was held beside the new one until it was copied.
        assertThat(budget.peak()).isEqualTo(RelayUsageMeter.MAX_LINE / 2 + RelayUsageMeter.MAX_LINE);
        assertThat(meter.retained()).isZero();
        assertThat(meter.capacity()).as("the buffer goes at the answer's end").isZero();
    }

    @Test
    void aStreamWithinTheBudgetReadsItsUsageAndGivesTheBudgetBackWhenTheShareCloses() {
        InflightBytes inflight = new InflightBytes(1024 * 1024);
        for (int size : new int[] {1, 7, 4096}) {
            try (InflightBytes.Share share = inflight.share()) {
                RelayUsageMeter meter = RelayUsageMeter.of("ANTHROPIC_MESSAGES",
                        token(RelayTokenStore.RelayFormat.ANTHROPIC), "k", RelayUsageMeter.Budget.of(share));
                RelayUsageMeter.Answered answered = feed(meter, STREAM, size, null);
                assertThat(answered.model()).isEqualTo("claude-x");
                assertThat(answered.inputTokens()).isEqualTo(1000);
                assertThat(answered.outputTokens()).isEqualTo(42);
                // Short lines: the first step of the buffer, held until the share closes.
                assertThat(inflight.held()).isEqualTo(RelayUsageMeter.LINE_STEP);
            }
            assertThat(inflight.held()).isZero();
        }
    }

    @Test
    void aBudgetOfOneByteKeepsNoLineOfAStreamAndNeverGoesNegative() {
        InflightBytes inflight = new InflightBytes(1);
        try (InflightBytes.Share share = inflight.share()) {
            RelayUsageMeter meter = RelayUsageMeter.of("ANTHROPIC_MESSAGES",
                    token(RelayTokenStore.RelayFormat.ANTHROPIC), "k", RelayUsageMeter.Budget.of(share));
            meter.answered();
            byte[] bytes = streamWithLongLine(100 * 1024).getBytes(StandardCharsets.UTF_8);
            for (int offset = 0; offset < bytes.length; offset += 4096) {
                meter.accept(bytes, offset, Math.min(4096, bytes.length - offset));
                assertThat(meter.retained()).isZero();
                assertThat(meter.capacity()).isZero();
                assertThat(inflight.held()).isBetween(0L, 1L);
            }
            RelayUsageMeter.Answered answered = meter.result(null);
            assertThat(answered).as("the answer counts a request").isNotNull();
            assertThat(answered.model()).isEqualTo("unknown");
            assertThat(answered.inputTokens() + answered.outputTokens()).isZero();
            assertThat(inflight.held()).isZero();
        }
        assertThat(inflight.held()).isZero();
    }

    @Test
    void aLineTheBudgetCannotHoldIsDroppedAndTheLinesAfterItReadWithinWhatWasReserved() {
        InflightBytes inflight = new InflightBytes(RelayUsageMeter.LINE_STEP);
        try (InflightBytes.Share share = inflight.share()) {
            RelayUsageMeter meter = RelayUsageMeter.of("ANTHROPIC_MESSAGES",
                    token(RelayTokenStore.RelayFormat.ANTHROPIC), "k", RelayUsageMeter.Budget.of(share));
            meter.answered();
            byte[] bytes = streamWithLongLine(100 * 1024).getBytes(StandardCharsets.UTF_8);
            for (int offset = 0; offset < bytes.length; offset += 4096) {
                meter.accept(bytes, offset, Math.min(4096, bytes.length - offset));
                assertThat(meter.retained()).isLessThanOrEqualTo(RelayUsageMeter.LINE_STEP);
                assertThat(inflight.held()).isBetween(0L, (long) RelayUsageMeter.LINE_STEP);
            }
            RelayUsageMeter.Answered answered = meter.result(null);
            assertThat(answered.model()).isEqualTo("claude-x");
            assertThat(answered.inputTokens()).as("the long line was dropped").isEqualTo(1000);
            assertThat(answered.outputTokens()).as("the line after it was read").isEqualTo(42);
            assertThat(inflight.held()).isEqualTo(RelayUsageMeter.LINE_STEP);
        }
        assertThat(inflight.held()).isZero();
    }

    @Test
    void endpointsThatCostNothingAreNotMeteredAndAnUnansweredRequestCountsNothing() {
        assertThat(RelayUsageMeter.of("ANTHROPIC_COUNT_TOKENS", token(RelayTokenStore.RelayFormat.ANTHROPIC), "k",
                unlimited())).isNull();
        assertThat(RelayUsageMeter.of("OPENAI_MODELS", token(RelayTokenStore.RelayFormat.OPENAI), "k", unlimited()))
            .isNull();
        RelayUsageMeter meter = RelayUsageMeter.of("OPENAI_CHAT_COMPLETIONS",
                token(RelayTokenStore.RelayFormat.OPENAI), "k", unlimited());
        assertThat(meter.result("m")).isNull();
        assertThat(RelayUsageMeter.requestModel("{\"messages\":[{\"model\":\"inner\"}],\"model\":\"outer\"}"
            .getBytes(StandardCharsets.UTF_8))).isEqualTo("outer");
        assertThat(RelayUsageMeter.requestModel("[]".getBytes(StandardCharsets.UTF_8))).isNull();
    }
}
