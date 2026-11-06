package com.flowtivo.tinfirm.conversation.internal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.flowtivo.tinfirm.conversation.ConversationInfo;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The JSON columns of the conversation tables: a workspace, a result's lists. */
final class ConversationJson {

    static final JsonMapper JSON = JsonMapper.builder().build();

    private ConversationJson() {
    }

    static String workspace(List<ConversationInfo.WorkspaceEntry> entries) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (ConversationInfo.WorkspaceEntry entry : entries) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("repositoryId", entry.repositoryId().toString());
            item.put("readOnly", entry.readOnly());
            item.put("source", entry.source().name());
            list.add(item);
        }
        return JSON.writeValueAsString(list);
    }

    static List<ConversationInfo.WorkspaceEntry> workspace(@Nullable String json) {
        List<ConversationInfo.WorkspaceEntry> entries = new ArrayList<>();
        if (json == null) {
            return entries;
        }
        for (JsonNode item : JSON.readTree(json)) {
            entries.add(new ConversationInfo.WorkspaceEntry(UUID.fromString(item.get("repositoryId").asString()),
                    item.path("readOnly").asBoolean(false),
                    ConversationInfo.WorkspaceEntry.Source.valueOf(item.path("source").asString("AGENT"))));
        }
        return entries;
    }

    /** Any value as JSON: a place's reference, a message's external author. */
    static String write(Object value) {
        return JSON.writeValueAsString(value);
    }

    static String strings(List<String> values) {
        return JSON.writeValueAsString(values);
    }

    static List<String> strings(@Nullable String json) {
        List<String> values = new ArrayList<>();
        if (json == null) {
            return values;
        }
        for (JsonNode item : JSON.readTree(json)) {
            values.add(item.asString());
        }
        return values;
    }
}
