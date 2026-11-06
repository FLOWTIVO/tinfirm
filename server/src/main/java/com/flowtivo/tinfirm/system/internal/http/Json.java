package com.flowtivo.tinfirm.system.internal.http;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Reading the JSON answers of the systems, leniently: a missing or differently typed field is null. */
public final class Json {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private Json() {
    }

    /** The body as a tree; an empty body is an empty object; null when it is not JSON. */
    public static @Nullable JsonNode parse(String body) {
        if (body.isBlank()) {
            return MAPPER.createObjectNode();
        }
        try {
            return MAPPER.readTree(body);
        }
        catch (JacksonException ex) {
            return null;
        }
    }

    public static String write(Object value) {
        return MAPPER.writeValueAsString(value);
    }

    public static JsonNode tree(Object value) {
        return MAPPER.valueToTree(value);
    }

    /** The map form of a JSON object, for the API's DTOs. */
    public static Map<String, Object> asMap(JsonNode node) {
        @SuppressWarnings("unchecked")
        Map<String, Object> map = MAPPER.convertValue(node, Map.class);
        return map != null ? map : Map.of();
    }

    public static @Nullable String text(@Nullable JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        return value != null && value.isString() ? value.stringValue() : null;
    }

    /** A string or a number member as text (GitHub's and GitLab's numeric ids); null otherwise. */
    public static @Nullable String textOrNumber(@Nullable JsonNode node, String field) {
        JsonNode value = node != null ? node.get(field) : null;
        if (value == null) {
            return null;
        }
        return value.isString() ? value.stringValue() : value.isIntegralNumber() ? value.asString() : null;
    }

    public static boolean flag(@Nullable JsonNode node, String field) {
        JsonNode value = node != null ? node.get(field) : null;
        return value != null && value.isBoolean() && value.booleanValue();
    }

    /** A boolean member, or null when it is absent or not a boolean. */
    public static @Nullable Boolean bool(@Nullable JsonNode node, String field) {
        JsonNode value = node != null ? node.get(field) : null;
        return value != null && value.isBoolean() ? value.booleanValue() : null;
    }

    /** An integral member, or the fallback when it is absent or not an integer. */
    public static int integer(@Nullable JsonNode node, String field, int fallback) {
        JsonNode value = node != null ? node.get(field) : null;
        return value != null && value.isIntegralNumber() ? value.intValue() : fallback;
    }

    /** An integral member, or a string of digits (Jira's ids); null otherwise. */
    public static @Nullable Long longValue(@Nullable JsonNode node, String field) {
        JsonNode value = node != null ? node.get(field) : null;
        if (value == null) {
            return null;
        }
        if (value.isIntegralNumber()) {
            return value.longValue();
        }
        if (value.isString()) {
            try {
                return Long.parseLong(value.stringValue());
            }
            catch (NumberFormatException ex) {
                return null;
            }
        }
        return null;
    }

    /** An ISO offset date-time such as {@code 2026-10-03T08:15:30Z}; null when it is not one. */
    public static @Nullable Instant time(@Nullable String value) {
        if (value == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        }
        catch (DateTimeParseException ex) {
            return null;
        }
    }

    /** A URL path or query value, with spaces as {@code %20}. */
    public static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** A reason in one line, cut to {@code max} characters. */
    public static String cut(String text, int max) {
        String oneLine = text.replaceAll("\\s+", " ").strip();
        return oneLine.length() <= max ? oneLine : oneLine.substring(0, max - 1) + "…";
    }
}
