package com.flowtivo.tinfirm.shared.jira;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import org.jspecify.annotations.Nullable;

import tools.jackson.databind.JsonNode;

/** Reading Jira's JSON leniently: a missing or differently typed member is null, or the fallback. */
final class JiraJson {

    private JiraJson() {
    }

    static @Nullable String text(@Nullable JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        return value != null && value.isString() ? value.stringValue() : null;
    }

    /** A string or an integral member as text (Jira's ids come as either); null otherwise. */
    static @Nullable String textOrNumber(@Nullable JsonNode node, String field) {
        JsonNode value = node != null ? node.get(field) : null;
        if (value == null) {
            return null;
        }
        return value.isString() ? value.stringValue() : value.isIntegralNumber() ? value.asString() : null;
    }

    static boolean flag(@Nullable JsonNode node, String field) {
        JsonNode value = node != null ? node.get(field) : null;
        return value != null && value.isBoolean() && value.booleanValue();
    }

    /** An integral member, or the fallback when it is absent or not an integer. */
    static int integer(@Nullable JsonNode node, String field, int fallback) {
        JsonNode value = node != null ? node.get(field) : null;
        return value != null && value.isIntegralNumber() ? value.intValue() : fallback;
    }

    /** An integral member, or a string of digits (Jira's ids); null otherwise. */
    static @Nullable Long longValue(@Nullable JsonNode node, String field) {
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

    /** A URL query value, with spaces as {@code %20}. */
    static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
