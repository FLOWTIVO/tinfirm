package com.flowtivo.tinfirm.model.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.flowtivo.tinfirm.shared.net.InflightBytes;

import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * What the relay sees of one answer's usage (the package 6 contract, §3.1, §5.1): the bytes of a successful answer
 * pass through it as they are streamed back, and at its end it knows the answer's model and tokens as the format
 * reports them. A streamed answer is read line by line ({@code data:} lines of server-sent events), keeping at most
 * one line of {@value #MAX_LINE} bytes at a time (a longer line is dropped, and the lines after it still read); a
 * plain JSON answer is kept up to {@value #MAX_JSON} bytes and read at its end. What the meter keeps is reserved from
 * the replica's budget of bytes in flight ({@link Budget}: the request's {@code InflightBytes.Share}, which gives it
 * back when the request ends, CONVENTIONS §15.4), so that many answers at once cannot hold more memory than the budget
 * allows. Both are kept in one buffer that grows by doubling from {@value #LINE_STEP} bytes to its cap: each growth
 * takes the new array from the budget before it is allocated and gives the old one back once it is copied, so that
 * the budget counts every array the meter holds, the old one too while both live, and never less than the capacity
 * allocated. A stream's buffer and its reservation last at the line's high-water mark (at most {@value #MAX_LINE} per
 * request) until the answer ends and the request's share closes; a line the budget cannot hold is dropped like a line
 * too long, and the lines after it are still read within the capacity already reserved. A line is read where it is
 * kept, never copied, and parsed only when it is a {@code data:} line that names a usage or a model. A plain answer
 * the budget or the cap cannot hold gives its buffer back at once and is passed on unmetered, counting a request and
 * no tokens, with one warning. The fields are the last ones reported, since a stream reports its usage as running
 * totals: Anthropic's {@code message_start} and {@code message_delta}, OpenAI's final chunk ({@code usage} with
 * {@code prompt_tokens}) and the Responses API's {@code response.completed}. An answer without usage counts a request
 * and no tokens. Never sees a key: the key's LiteLLM id is its SHA-256.
 */
final class RelayUsageMeter {

    private static final Logger log = LoggerFactory.getLogger(RelayUsageMeter.class);

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** The most of one line of a stream kept, until its end. */
    static final int MAX_LINE = 4 * 1024 * 1024;

    /** The first capacity of the buffer, reserved at the first byte kept; it doubles from there, up to its cap. */
    static final int LINE_STEP = 64 * 1024;

    /** The most of a plain JSON answer kept, until its end; a chat answer is far smaller (LiteLLM's own cap, 1 MiB). */
    static final int MAX_JSON = 4 * 1024 * 1024;

    /** What a stream's line starts with when it carries an event's data, and what a usage or a model is named. */
    private static final byte[] DATA = "data:".getBytes(StandardCharsets.US_ASCII);

    private static final byte[] USAGE = "\"usage\"".getBytes(StandardCharsets.US_ASCII);

    private static final byte[] MODEL = "\"model\"".getBytes(StandardCharsets.US_ASCII);

    /** An answer of a run, metered: published as an event of this module, which {@link RelayUsageStore} records. */
    record Answered(UUID companyId, UUID runId, String model, String keyId, long inputTokens, long outputTokens,
            long cachedInputTokens, long cacheWriteTokens) {
    }

    private final UUID companyId;

    private final UUID runId;

    private final String keyId;

    private final RelayTokenStore.RelayFormat format;

    private final Budget budget;

    /**
     * What the meter keeps, a plain JSON answer or the current line of a stream, in an array allocated at the capacity
     * reserved from the budget; null before the first byte kept.
     */
    private byte @Nullable [] kept;

    private int keptLength;

    private final Map<String, Long> fields = new HashMap<>();

    private @Nullable String model;

    private @Nullable Boolean json;

    private boolean overflow;

    private boolean answered;

    private boolean warned;

    private RelayUsageMeter(UUID companyId, UUID runId, String keyId, RelayTokenStore.RelayFormat format,
            Budget budget) {
        this.companyId = companyId;
        this.runId = runId;
        this.keyId = keyId;
        this.format = format;
        this.budget = budget;
    }

    /** The replica's budget of bytes in flight, as the meter takes its part and gives back what it let go of. */
    interface Budget {

        /** Takes this many more bytes; false, and nothing taken, when the budget is full. */
        boolean reserve(long bytes);

        /** Gives back this many of the bytes taken. */
        void release(long bytes);

        /** The request's share of the replica's budget. */
        static Budget of(InflightBytes.Share share) {
            return new Budget() {

                @Override
                public boolean reserve(long bytes) {
                    return share.add(bytes);
                }

                @Override
                public void release(long bytes) {
                    share.release(bytes);
                }
            };
        }
    }

    /**
     * A meter for an answer of the endpoint, or null for one that costs nothing (counting tokens, listing models).
     *
     * @param key    the paying key the request is forwarded with; only its SHA-256 is kept
     * @param budget the replica's budget of bytes in flight, for the buffer the meter keeps the answer in; the caller
     *               gives back what is left of it when the request ends
     */
    static @Nullable RelayUsageMeter of(String endpoint, RelayTokenStore.TokenRow token, String key,
            Budget budget) {
        if (!endpoint.equals("ANTHROPIC_MESSAGES") && !endpoint.equals("OPENAI_RESPONSES")
                && !endpoint.equals("OPENAI_CHAT_COMPLETIONS")) {
            return null;
        }
        return new RelayUsageMeter(token.companyId(), token.runId(), keyId(key), token.format(), budget);
    }

    /** LiteLLM's id of a key: the hex SHA-256 of the key. */
    static String keyId(String key) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(key.getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** The top-level {@code model} of a request's body as the relay forwards it; null without one. */
    static @Nullable String requestModel(byte @Nullable [] body) {
        if (body == null) {
            return null;
        }
        try (JsonParser parser = JSON.createParser(body)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                return null;
            }
            for (JsonToken token = parser.nextToken(); token == JsonToken.PROPERTY_NAME; token = parser.nextToken()) {
                String name = parser.currentName();
                JsonToken value = parser.nextToken();
                if (name.equals("model") && value == JsonToken.VALUE_STRING) {
                    return parser.getString();
                }
                parser.skipChildren();
            }
        }
        catch (JacksonException ex) {
            return null;
        }
        return null;
    }

    /** A successful answer began: it counts as a request, whatever follows. */
    void answered() {
        answered = true;
    }

    /** Bytes of the answer, as they are passed on. */
    void accept(byte[] bytes, int offset, int length) {
        int end = offset + length;
        int i = offset;
        if (json == null) {
            while (i < end) {
                byte b = bytes[i];
                if (b != ' ' && b != '\n' && b != '\r' && b != '\t') {
                    json = b == '{';
                    break;
                }
                i++;
            }
            if (json == null) {
                return;
            }
        }
        if (json) {
            keepAnswer(bytes, i, end - i);
            return;
        }
        while (i < end) {
            int newline = i;
            while (newline < end && bytes[newline] != '\n') {
                newline++;
            }
            append(bytes, i, newline - i);
            if (newline == end) {
                return;
            }
            line();
            i = newline + 1;
        }
    }

    /**
     * Keeps a part of the current line of a stream, within the buffer's capacity, which grows to {@value #MAX_LINE}
     * bytes; beyond the cap or the budget the rest of the line is dropped, and the next line read again.
     */
    private void append(byte[] bytes, int offset, int length) {
        if (!overflow && !keep(bytes, offset, length, MAX_LINE)) {
            overflow = true;
        }
    }

    /**
     * Keeps a part of a plain JSON answer, within the cap and the budget; beyond either, the buffer and its
     * reservation go at once, and nothing of the answer is kept from then on (it counts a request and no tokens).
     */
    private void keepAnswer(byte[] bytes, int offset, int length) {
        if (overflow) {
            return;
        }
        if (keptLength + length > MAX_JSON || !keep(bytes, offset, length, MAX_JSON)) {
            overflow = true;
            if (kept != null) {
                budget.release(kept.length);
                kept = null;
            }
            keptLength = 0;
        }
    }

    /**
     * Keeps bytes after those kept, growing the buffer as they need: false, with the bytes beyond the buffer not kept,
     * at the cap or when the budget cannot hold the next growth.
     */
    private boolean keep(byte[] bytes, int offset, int length, int cap) {
        while (length > 0) {
            byte[] buf = kept;
            if (buf == null || keptLength == buf.length) {
                buf = grow(buf != null ? buf.length : 0, cap);
                if (buf == null) {
                    return false;
                }
            }
            int n = Math.min(length, buf.length - keptLength);
            System.arraycopy(bytes, offset, buf, keptLength, n);
            keptLength += n;
            offset += n;
            length -= n;
        }
        return true;
    }

    /**
     * Grows the buffer one step, doubling from {@value #LINE_STEP} bytes up to the cap: the new array is taken from the
     * budget before it is allocated, beside the old one, which is given back once it is copied, so that the budget
     * holds both while both live. The grown buffer, or null at the cap or when the budget cannot hold the new array.
     */
    private byte @Nullable [] grow(int capacity, int cap) {
        if (capacity >= cap) {
            return null;
        }
        int next = capacity == 0 ? LINE_STEP : Math.min(capacity * 2, cap);
        if (!budget.reserve(next)) {
            if (!warned) {
                warned = true;
                if (Boolean.TRUE.equals(json)) {
                    log.warn("The usage of a relayed answer of run {} was not metered: the replica's budget of bytes "
                            + "in flight is full", runId);
                }
                else {
                    log.warn("A line of a relayed streamed answer of run {} was not read: the replica's budget of "
                            + "bytes in flight is full", runId);
                }
            }
            return null;
        }
        byte[] grown = kept != null ? Arrays.copyOf(kept, next) : new byte[next];
        kept = grown;
        budget.release(capacity);
        return grown;
    }

    /** The bytes the meter keeps now, of a plain answer or of the current line of a stream. */
    int retained() {
        return keptLength;
    }

    /** The capacity of the buffer, as allocated and reserved from the budget. */
    int capacity() {
        return kept != null ? kept.length : 0;
    }

    /** What the answer used, or null when no successful answer began. */
    @Nullable Answered result(@Nullable String requestModel) {
        if (!answered) {
            return null;
        }
        if (Boolean.TRUE.equals(json)) {
            if (!overflow && kept != null) {
                read(parse(kept, 0, keptLength));
            }
        }
        else {
            line();
        }
        // The buffer goes now; its reservation when the request's share closes, moments later.
        kept = null;
        keptLength = 0;
        String named = requestModel != null && !requestModel.isBlank() ? requestModel : model;
        String shown = named != null && !named.isBlank() ? named : "unknown";
        if (shown.length() > 200) {
            shown = shown.substring(0, 200);
        }
        long cached = field("cache_read_input_tokens") + field("cached_tokens");
        long input;
        long output;
        if (format == RelayTokenStore.RelayFormat.ANTHROPIC) {
            input = field("input_tokens");
            output = field("output_tokens");
        }
        else if (fields.containsKey("prompt_tokens")) {
            input = Math.max(0, field("prompt_tokens") - cached);
            output = field("completion_tokens");
        }
        else {
            input = Math.max(0, field("input_tokens") - cached);
            output = field("output_tokens");
        }
        return new Answered(companyId, runId, shown, keyId, input, output, cached,
                field("cache_creation_input_tokens"));
    }

    private long field(String name) {
        Long value = fields.get(name);
        return value != null && value > 0 ? value : 0;
    }

    /**
     * Reads the line kept, where it is: a {@code data:} line whose JSON names a usage or a model is parsed from the
     * buffer, never copied into a string.
     */
    private void line() {
        if (overflow) {
            overflow = false;
            keptLength = 0;
            return;
        }
        byte[] buf = kept;
        int end = keptLength;
        keptLength = 0;
        if (buf == null || end == 0) {
            return;
        }
        int start = skipSpace(buf, 0, end);
        while (end > start && space(buf[end - 1])) {
            end--;
        }
        if (!at(buf, start, end, DATA)) {
            return;
        }
        start = skipSpace(buf, start + DATA.length, end);
        if (start < end && buf[start] == '{'
                && (contains(buf, start, end, USAGE) || contains(buf, start, end, MODEL))) {
            read(parse(buf, start, end - start));
        }
    }

    private static boolean space(byte b) {
        return b == ' ' || b == '\t' || b == '\r' || b == '\n' || b == '\f';
    }

    private static int skipSpace(byte[] bytes, int from, int end) {
        int i = from;
        while (i < end && space(bytes[i])) {
            i++;
        }
        return i;
    }

    /** Whether {@code bytes[from, end)} starts with {@code word}. */
    private static boolean at(byte[] bytes, int from, int end, byte[] word) {
        if (end - from < word.length) {
            return false;
        }
        for (int j = 0; j < word.length; j++) {
            if (bytes[from + j] != word[j]) {
                return false;
            }
        }
        return true;
    }

    /** Whether {@code bytes[from, end)} contains {@code word}. */
    private static boolean contains(byte[] bytes, int from, int end, byte[] word) {
        for (int i = from; i <= end - word.length; i++) {
            if (at(bytes, i, end, word)) {
                return true;
            }
        }
        return false;
    }

    private static @Nullable JsonNode parse(byte[] bytes, int offset, int length) {
        try {
            return JSON.readTree(bytes, offset, length);
        }
        catch (JacksonException ex) {
            return null;
        }
    }

    private void read(@Nullable JsonNode node) {
        if (node == null || !node.isObject()) {
            return;
        }
        for (JsonNode holder : new JsonNode[] {node, node.get("message"), node.get("response")}) {
            if (holder == null || !holder.isObject()) {
                continue;
            }
            JsonNode name = holder.get("model");
            if (name != null && name.isString() && !name.asString().isBlank()) {
                model = name.asString();
            }
            JsonNode usage = holder.get("usage");
            if (usage != null && usage.isObject()) {
                usage(usage);
            }
        }
    }

    private void usage(JsonNode usage) {
        for (String name : new String[] {"input_tokens", "output_tokens", "cache_read_input_tokens",
                "cache_creation_input_tokens", "prompt_tokens", "completion_tokens"}) {
            JsonNode value = usage.get(name);
            if (value != null && value.isNumber()) {
                fields.put(name, value.asLong());
            }
        }
        for (String details : new String[] {"prompt_tokens_details", "input_tokens_details"}) {
            JsonNode node = usage.get(details);
            JsonNode cached = node != null ? node.get("cached_tokens") : null;
            if (cached != null && cached.isNumber()) {
                fields.put("cached_tokens", cached.asLong());
            }
        }
    }
}
