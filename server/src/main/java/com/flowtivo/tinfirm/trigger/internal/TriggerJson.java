package com.flowtivo.tinfirm.trigger.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import com.flowtivo.tinfirm.trigger.ConditionGroup;
import com.flowtivo.tinfirm.trigger.TriggerAction;
import com.flowtivo.tinfirm.trigger.TriggerEvent;
import com.flowtivo.tinfirm.trigger.TriggerEventSpec;
import com.flowtivo.tinfirm.trigger.TriggerResults;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The JSON of a trigger's {@code events}, {@code conditions} and {@code results} (V011), written field by field so that
 * what a trigger holds never follows a change of a Java type.
 */
final class TriggerJson {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private TriggerJson() {
    }

    static String events(List<TriggerEventSpec> events) {
        ArrayNode array = JSON.createArrayNode();
        for (TriggerEventSpec spec : events) {
            ObjectNode node = array.addObject().put("event", spec.event().name());
            if (spec.value() != null) {
                node.put("value", spec.value());
            }
        }
        return JSON.writeValueAsString(array);
    }

    static List<TriggerEventSpec> events(String json) {
        List<TriggerEventSpec> events = new ArrayList<>();
        for (JsonNode node : JSON.readTree(json)) {
            events.add(new TriggerEventSpec(TriggerEvent.valueOf(node.path("event").stringValue()),
                    text(node, "value")));
        }
        return events;
    }

    static String conditions(List<ConditionGroup> groups) {
        ArrayNode array = JSON.createArrayNode();
        for (ConditionGroup group : groups) {
            ObjectNode node = array.addObject();
            names(node, "statuses", group.statuses());
            names(node, "notStatuses", group.notStatuses());
            names(node, "labels", group.labels());
            names(node, "notLabels", group.notLabels());
            names(node, "types", group.types());
            names(node, "notTypes", group.notTypes());
            if (group.authors() != null) {
                node.put("authors", group.authors().name());
            }
        }
        return JSON.writeValueAsString(array);
    }

    static List<ConditionGroup> conditions(String json) {
        List<ConditionGroup> groups = new ArrayList<>();
        for (JsonNode node : JSON.readTree(json)) {
            String authors = text(node, "authors");
            groups.add(new ConditionGroup(names(node, "statuses"), names(node, "notStatuses"), names(node, "labels"),
                    names(node, "notLabels"), names(node, "types"), names(node, "notTypes"),
                    authors != null ? ConditionGroup.Authors.valueOf(authors) : null));
        }
        return groups;
    }

    /** A webhook trigger's conditions (V019): {@code [{pointer, op, values}]}. */
    static String webhookConditions(List<WebhookCondition> conditions) {
        ArrayNode array = JSON.createArrayNode();
        for (WebhookCondition condition : conditions) {
            ObjectNode node = array.addObject().put("pointer", condition.pointer()).put("op", condition.op().name());
            ArrayNode values = node.putArray("values");
            condition.values().forEach(values::add);
        }
        return JSON.writeValueAsString(array);
    }

    static List<WebhookCondition> webhookConditions(String json) {
        List<WebhookCondition> conditions = new ArrayList<>();
        for (JsonNode node : JSON.readTree(json)) {
            conditions.add(new WebhookCondition(node.path("pointer").stringValue(),
                    WebhookCondition.Op.valueOf(node.path("op").stringValue()), names(node, "values")));
        }
        return conditions;
    }

    static String results(TriggerResults results) {
        ObjectNode node = JSON.createObjectNode();
        node.put("comment", results.comment());
        node.put("review", results.review());
        actions(node.putArray("onSuccess"), results.onSuccess());
        actions(node.putArray("onFailure"), results.onFailure());
        return JSON.writeValueAsString(node);
    }

    static TriggerResults results(String json) {
        JsonNode node = JSON.readTree(json);
        return new TriggerResults(node.path("comment").asBoolean(true), node.path("review").asBoolean(false),
                actions(node.path("onSuccess")), actions(node.path("onFailure")));
    }

    /** A JSON object of the details, for a column such as {@code trigger_firing.actor}. */
    static String object(Map<String, ?> values) {
        return JSON.writeValueAsString(values);
    }

    private static void actions(ArrayNode array, List<TriggerAction> actions) {
        for (TriggerAction action : actions) {
            ObjectNode node = array.addObject().put("kind", action.kind().name());
            if (action.value() != null) {
                node.put("value", action.value());
            }
        }
    }

    private static List<TriggerAction> actions(JsonNode array) {
        List<TriggerAction> actions = new ArrayList<>();
        for (JsonNode node : array) {
            actions.add(new TriggerAction(TriggerAction.Kind.valueOf(node.path("kind").stringValue()),
                    text(node, "value")));
        }
        return actions;
    }

    private static void names(ObjectNode node, String field, List<String> names) {
        if (!names.isEmpty()) {
            ArrayNode array = node.putArray(field);
            names.forEach(array::add);
        }
    }

    private static List<String> names(JsonNode node, String field) {
        List<String> names = new ArrayList<>();
        node.path(field).forEach(name -> names.add(name.stringValue()));
        return names;
    }

    private static @Nullable String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.stringValue();
    }
}
