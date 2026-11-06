package com.flowtivo.tinfirm.audit.internal;

import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The canonical form and hash of an audit event (CONVENTIONS §8).
 * <p>
 * {@code canonical} is JSON with object keys sorted recursively and no whitespace, containing {@code companyId},
 * {@code seq}, {@code occurredAt} (UTC, microseconds), {@code actor} ({@code {id, type}} only), {@code action},
 * {@code targetType}, {@code targetId} and {@code details}. {@code hash = hex(sha256(prevHash + "\n" + canonical))}.
 * <p>
 * Details may hold strings, integers, booleans, nulls and nested arrays or objects of those. Fractional numbers are
 * rejected because their textual form is not stable across a JSON round trip.
 */
final class AuditCanonicalForm {

    /** The {@code prevHash} of the first event of every chain. */
    static final String GENESIS_HASH = "0".repeat(64);

    private static final DateTimeFormatter OCCURRED_AT = DateTimeFormatter
        .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'")
        .withZone(ZoneOffset.UTC);

    /** A plain mapper, independent of the web configuration, so the canonical form never changes by accident. */
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private AuditCanonicalForm() {
    }

    /** Hashed content of one event. */
    record Content(@Nullable UUID companyId, long seq, Instant occurredAt, String actorType, @Nullable UUID actorId,
            String action, @Nullable String targetType, @Nullable String targetId, JsonNode details) {
    }

    static JsonNode detailsTree(Map<String, @Nullable Object> details) {
        JsonNode tree = MAPPER.valueToTree(details);
        requireCanonicalizable(tree);
        return tree;
    }

    static JsonNode parseDetails(String json) {
        return MAPPER.readTree(json);
    }

    static String canonical(Content content) {
        ObjectNode event = MAPPER.createObjectNode();
        putNullable(event, "companyId", content.companyId());
        event.put("seq", content.seq());
        event.put("occurredAt", OCCURRED_AT.format(content.occurredAt()));
        ObjectNode actor = event.putObject("actor");
        actor.put("type", content.actorType());
        putNullable(actor, "id", content.actorId());
        event.put("action", content.action());
        putNullable(event, "targetType", content.targetType());
        putNullable(event, "targetId", content.targetId());
        event.set("details", content.details());
        return canonicalJson(event);
    }

    /**
     * An event's line of an export (the package 6 contract, §5.8): its canonical form with {@code prevHash} and
     * {@code hash} added, keys sorted, no whitespace. Dropping the two keys gives the canonical form back.
     */
    static String exportLine(StoredAuditEvent event) {
        ObjectNode line = (ObjectNode) MAPPER.readTree(canonical(event.content()));
        line.put("prevHash", event.prevHash());
        line.put("hash", event.hash());
        return canonicalJson(line);
    }

    /** A line of an export that is not an event: the given fields, keys sorted, no whitespace. */
    static String exportLine(Map<String, @Nullable Object> fields) {
        return canonicalJson(MAPPER.valueToTree(fields));
    }

    static String hash(String prevHash, String canonical) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] digest = sha256.digest((prevHash + "\n" + canonical).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        }
        catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    /** JSON with object keys sorted recursively (by UTF-16 code units) and no whitespace. */
    static String canonicalJson(JsonNode node) {
        StringWriter writer = new StringWriter();
        try (JsonGenerator generator = MAPPER.createGenerator(writer)) {
            write(generator, node);
        }
        return writer.toString();
    }

    private static void write(JsonGenerator generator, JsonNode node) {
        switch (node.getNodeType()) {
            case OBJECT -> {
                generator.writeStartObject();
                Map<String, JsonNode> sorted = new TreeMap<>();
                for (Map.Entry<String, JsonNode> property : node.properties()) {
                    sorted.put(property.getKey(), property.getValue());
                }
                for (Map.Entry<String, JsonNode> property : sorted.entrySet()) {
                    generator.writeName(property.getKey());
                    write(generator, property.getValue());
                }
                generator.writeEndObject();
            }
            case ARRAY -> {
                generator.writeStartArray();
                for (JsonNode element : node.values()) {
                    write(generator, element);
                }
                generator.writeEndArray();
            }
            case STRING -> generator.writeString(node.stringValue());
            case NUMBER -> {
                if (!node.isIntegralNumber()) {
                    throw new IllegalArgumentException("Audit details must not contain fractional numbers");
                }
                generator.writeNumber(node.bigIntegerValue());
            }
            case BOOLEAN -> generator.writeBoolean(node.booleanValue());
            case NULL -> generator.writeNull();
            default -> throw new IllegalArgumentException("Unsupported JSON node in audit details: " + node.getNodeType());
        }
    }

    private static void requireCanonicalizable(JsonNode details) {
        if (!details.isObject()) {
            throw new IllegalArgumentException("Audit details must be a JSON object");
        }
        canonicalJson(details);
    }

    private static void putNullable(ObjectNode node, String name, @Nullable Object value) {
        if (value == null) {
            node.putNull(name);
        }
        else {
            node.put(name, value.toString());
        }
    }
}
