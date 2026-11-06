package com.flowtivo.tinfirm.trigger.internal;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

import com.flowtivo.tinfirm.system.WebhookSystems;
import com.flowtivo.tinfirm.trigger.TriggerEvent;
import com.flowtivo.tinfirm.trigger.TriggerEventSpec;
import com.flowtivo.tinfirm.trigger.TriggerInfo;

import tools.jackson.core.JsonPointer;
import tools.jackson.databind.JsonNode;

/**
 * A webhook system's mapping (the package 5 contract, §5.12): JSON Pointers (RFC 6901) only, no expressions. A string,
 * number or boolean is read as text and cut to its limit (key 200, title 500, URL 2000 and http(s) only, actor 200,
 * text 20 000); anything else, or nothing, is {@code null}. A pointer that cannot be read, or a mapped delivery id that
 * is not 1 to 200 printable characters, fails the mapping ({@code MAPPING_FAILED}), which fires nothing. Also a webhook
 * trigger's match: its events (a mapped event among their values, or any without one) and its conditions on the raw
 * body, all of which hold.
 */
final class WebhookMapping {

    /** A delivery id: 1 to 200 printable ASCII characters (V019). */
    static final Pattern DELIVERY_ID = Pattern.compile("^[\\x21-\\x7e]{1,200}$");

    static final int KEY = 200;

    static final int TITLE = 500;

    static final int URL = 2000;

    static final int ACTOR = 200;

    static final int TEXT = 20_000;

    static final int EVENT = 200;

    private WebhookMapping() {
    }

    /** What the mapping read from a body. */
    record Mapped(@Nullable String event, @Nullable String subjectKey, @Nullable String subjectTitle,
            @Nullable String subjectUrl, @Nullable String actorId, @Nullable String actorName, @Nullable String text,
            @Nullable String deliveryId) {

        /** The actor as the firing keeps it: {@code {id, login: null, name}}, as the sender said. */
        @Nullable Map<String, @Nullable String> actor() {
            if (actorId == null && actorName == null) {
                return null;
            }
            Map<String, @Nullable String> actor = new java.util.LinkedHashMap<>();
            actor.put("id", actorId);
            actor.put("login", null);
            actor.put("name", actorName != null ? actorName : actorId);
            return actor;
        }
    }

    /** The mapping's result: what it read, or why it could not. */
    sealed interface Result {

        record Read(Mapped mapped) implements Result {
        }

        record Failed(String reason) implements Result {
        }
    }

    /** Maps the body with the system's pointers. */
    static Result map(WebhookSystems.WebhookSystem system, JsonNode body) {
        try {
            String deliveryId = text(body, system.pointer(WebhookSystems.MappingField.DELIVERY_ID), Integer.MAX_VALUE);
            if (deliveryId != null && !DELIVERY_ID.matcher(deliveryId).matches()) {
                return new Result.Failed("The mapped deliveryId is not 1 to 200 printable characters");
            }
            String url = text(body, system.pointer(WebhookSystems.MappingField.SUBJECT_URL), URL);
            if (url != null && !http(url)) {
                url = null;
            }
            return new Result.Read(new Mapped(
                    text(body, system.pointer(WebhookSystems.MappingField.EVENT), EVENT),
                    text(body, system.pointer(WebhookSystems.MappingField.SUBJECT_KEY), KEY),
                    text(body, system.pointer(WebhookSystems.MappingField.SUBJECT_TITLE), TITLE),
                    url,
                    text(body, system.pointer(WebhookSystems.MappingField.ACTOR_ID), ACTOR),
                    text(body, system.pointer(WebhookSystems.MappingField.ACTOR_NAME), ACTOR),
                    text(body, system.pointer(WebhookSystems.MappingField.TEXT), TEXT),
                    deliveryId));
        }
        catch (IllegalArgumentException ex) {
            return new Result.Failed("A pointer of the mapping cannot be read: " + ex.getMessage());
        }
    }

    /**
     * The pointer's value as text, cut to the limit: a string, number or boolean; null for anything else, for nothing,
     * and for a null pointer.
     */
    static @Nullable String text(JsonNode body, @Nullable String pointer, int limit) {
        if (pointer == null) {
            return null;
        }
        JsonNode node = at(body, pointer);
        String text;
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isString()) {
            text = node.asString();
        }
        else if (node.isNumber() || node.isBoolean()) {
            text = node.asString();
        }
        else {
            return null;
        }
        text = text.strip();
        if (text.isEmpty()) {
            return null;
        }
        return cut(text, limit);
    }

    /** The text cut to at most {@code limit} UTF-16 units, never inside a surrogate pair. */
    static String cut(String text, int limit) {
        if (text.length() <= limit) {
            return text;
        }
        int end = Character.isHighSurrogate(text.charAt(limit - 1)) ? limit - 1 : limit;
        return text.substring(0, end);
    }

    /** The node at the pointer; a missing node for nothing. */
    static @Nullable JsonNode at(JsonNode body, String pointer) {
        return body.at(JsonPointer.compile(pointer));
    }

    private static boolean http(String url) {
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme() != null ? uri.getScheme().toLowerCase(Locale.ROOT) : "";
            return (scheme.equals("https") || scheme.equals("http")) && uri.getHost() != null;
        }
        catch (IllegalArgumentException ex) {
            return false;
        }
    }

    /** Whether the trigger takes the delivery: one of its events, and every condition. */
    static boolean matches(TriggerInfo trigger, List<WebhookCondition> conditions, Mapped mapped, JsonNode body) {
        return mismatch(trigger, conditions, mapped, body) == null;
    }

    /** The cut of what a failed condition's pointer reads, in a {@link Mismatch.Condition}. */
    static final int ACTUAL = 500;

    /**
     * Why the trigger does not take the delivery: the first of its tests the body fails, its events first, then its
     * conditions in their order; null when it takes it. The receiver and the mapping's try ask this one rule.
     */
    static @Nullable Mismatch mismatch(TriggerInfo trigger, List<WebhookCondition> conditions, Mapped mapped,
            JsonNode body) {
        boolean event = trigger.events().isEmpty() || trigger.events().stream()
            .anyMatch(spec -> spec.event() == TriggerEvent.DELIVERED
                    && (spec.value() == null || spec.value().equals(mapped.event())));
        if (!event) {
            return new Mismatch.Event(trigger.events().stream()
                .filter(spec -> spec.event() == TriggerEvent.DELIVERED && spec.value() != null)
                .map(TriggerEventSpec::value)
                .toList(), mapped.event());
        }
        return failed(conditions, body);
    }

    /** The first of the conditions that does not hold on the raw body; null when every one holds. */
    static Mismatch.@Nullable Condition failed(List<WebhookCondition> conditions, JsonNode body) {
        for (int index = 0; index < conditions.size(); index++) {
            WebhookCondition condition = conditions.get(index);
            JsonNode node;
            try {
                node = at(body, condition.pointer());
            }
            catch (IllegalArgumentException ex) {
                return new Mismatch.Condition(index, condition, null);
            }
            boolean present = node != null && !node.isMissingNode() && !node.isNull();
            String text = present ? text(body, condition.pointer(), Integer.MAX_VALUE) : null;
            boolean holds = switch (condition.op()) {
                case PRESENT -> present;
                case ABSENT -> !present;
                case EQUALS -> text != null && !condition.values().isEmpty() && text.equals(condition.values().get(0));
                case IN -> text != null && condition.values().contains(text);
            };
            if (!holds) {
                return new Mismatch.Condition(index, condition, text != null ? cut(text, ACTUAL) : null);
            }
        }
        return null;
    }

    /** The first test of a webhook trigger a body fails. */
    sealed interface Mismatch {

        /**
         * No {@code DELIVERED} event of the trigger takes the mapped event.
         *
         * @param events the mapped events the trigger takes
         * @param event  the event the mapping read; null for none
         */
        record Event(List<String> events, @Nullable String event) implements Mismatch {
        }

        /**
         * A condition does not hold.
         *
         * @param index  its index among the trigger's conditions, from 0
         * @param actual what its pointer reads, as text, cut to {@value WebhookMapping#ACTUAL} characters; null for
         *               nothing (or no string, number or boolean), and for a pointer that cannot be read
         */
        record Condition(int index, WebhookCondition condition, @Nullable String actual) implements Mismatch {
        }
    }

    /** The SHA-256 of the body in hex: a delivery's id when nothing else names it ({@code SECRET_HEADER}). */
    static String sha256(byte[] body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        }
        catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** The body cut to 16 KiB for a prompt (package 4, decision 12), on a code point's boundary. */
    static String cutBody(byte[] body) {
        String text = new String(body, StandardCharsets.UTF_8);
        int max = 16 * 1024;
        if (text.length() <= max) {
            return text;
        }
        return cut(text, max) + "\n… (cut at 16 KiB)";
    }
}
