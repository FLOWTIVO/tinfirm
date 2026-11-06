package com.flowtivo.tinfirm.model.internal;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The JSON arrays of model names in {@code model_settings} and {@code principal_model_key}. */
final class ModelJson {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private ModelJson() {
    }

    static String write(List<String> names) {
        return JSON.writeValueAsString(names);
    }

    static @Nullable String writeNullable(@Nullable List<String> names) {
        return names != null ? write(names) : null;
    }

    /** The strings of a JSON array; an empty list for NULL. */
    static List<String> strings(@Nullable String json) {
        List<String> names = new ArrayList<>();
        if (json == null) {
            return names;
        }
        for (JsonNode node : JSON.readTree(json)) {
            if (node.isString()) {
                names.add(node.stringValue());
            }
        }
        return List.copyOf(names);
    }

    static @Nullable List<String> nullableStrings(@Nullable String json) {
        return json != null ? strings(json) : null;
    }
}
