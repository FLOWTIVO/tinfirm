package com.flowtivo.tinfirm.agent.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.flowtivo.tinfirm.agent.AgentContent;
import com.flowtivo.tinfirm.agent.AgentDefinition;
import com.flowtivo.tinfirm.agent.EffectiveSettings;
import com.flowtivo.tinfirm.agent.RepositoryAccess;
import com.flowtivo.tinfirm.agent.ToolMode;
import com.flowtivo.tinfirm.runner.RuntimeKind;
import com.flowtivo.tinfirm.system.ToolEffect;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The JSON of {@code agent_version.definition} (a version's name, handle, runtimes and ceiling), of
 * {@code agent_version.skills} (its pins) and of
 * {@code agent_settings.default_projects}. Written field by field, so that what a version holds never follows a
 * change of a Java type.
 */
final class AgentJson {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private AgentJson() {
    }

    /** A version as stored. */
    static String version(String name, String handle, List<RuntimeKind> runtimes, AgentDefinition definition) {
        ObjectNode root = JSON.createObjectNode();
        root.put("name", name);
        root.put("handle", handle);
        ArrayNode runtimeArray = root.putArray("runtimes");
        runtimes.forEach(runtime -> runtimeArray.add(runtime.name()));
        ArrayNode systems = root.putArray("systems");
        for (AgentDefinition.SystemEntry system : definition.systems()) {
            ObjectNode node = systems.addObject();
            node.put("systemId", system.systemId().toString());
            ArrayNode tools = node.putArray("tools");
            for (AgentDefinition.ToolEntry tool : system.tools()) {
                tools.addObject()
                    .put("name", tool.name())
                    .put("mode", tool.mode().name())
                    .put("chosenForEffect", tool.chosenForEffect().name());
            }
        }
        ArrayNode repositories = root.putArray("repositories");
        for (AgentDefinition.RepositoryEntry repository : definition.repositories()) {
            repositories.addObject()
                .put("repositoryId", repository.repositoryId().toString())
                .put("systemId", repository.systemId().toString())
                .put("access", repository.access().name());
        }
        ArrayNode hosts = root.putArray("hosts");
        definition.hosts().forEach(hosts::add);
        return JSON.writeValueAsString(root);
    }

    /** The ceiling of a stored version. */
    static AgentDefinition definition(String json) {
        JsonNode root = JSON.readTree(json);
        List<AgentDefinition.SystemEntry> systems = new ArrayList<>();
        for (JsonNode system : root.path("systems")) {
            List<AgentDefinition.ToolEntry> tools = new ArrayList<>();
            for (JsonNode tool : system.path("tools")) {
                tools.add(new AgentDefinition.ToolEntry(tool.path("name").stringValue(),
                        ToolMode.valueOf(tool.path("mode").stringValue()),
                        ToolEffect.valueOf(tool.path("chosenForEffect").stringValue())));
            }
            systems.add(new AgentDefinition.SystemEntry(UUID.fromString(system.path("systemId").stringValue()), tools));
        }
        List<AgentDefinition.RepositoryEntry> repositories = new ArrayList<>();
        for (JsonNode repository : root.path("repositories")) {
            repositories.add(new AgentDefinition.RepositoryEntry(
                    UUID.fromString(repository.path("repositoryId").stringValue()),
                    UUID.fromString(repository.path("systemId").stringValue()),
                    RepositoryAccess.valueOf(repository.path("access").stringValue())));
        }
        List<String> hosts = new ArrayList<>();
        for (JsonNode host : root.path("hosts")) {
            hosts.add(host.stringValue());
        }
        return new AgentDefinition(systems, repositories, hosts);
    }

    /** A version's pins as stored ({@code agent_version.skills}, V080): {@code [{skillId, version}]}. */
    static String skills(List<AgentContent.Pin> pins) {
        ArrayNode array = JSON.createArrayNode();
        for (AgentContent.Pin pin : pins) {
            array.addObject().put("skillId", pin.skillId().toString()).put("version", pin.version());
        }
        return JSON.writeValueAsString(array);
    }

    static List<AgentContent.Pin> skills(String json) {
        List<AgentContent.Pin> pins = new ArrayList<>();
        for (JsonNode pin : JSON.readTree(json)) {
            pins.add(new AgentContent.Pin(UUID.fromString(pin.path("skillId").stringValue()),
                    pin.path("version").intValue()));
        }
        return pins;
    }

    static String projects(List<EffectiveSettings.Project> projects) {
        ArrayNode array = JSON.createArrayNode();
        for (EffectiveSettings.Project project : projects) {
            array.addObject().put("systemId", project.systemId().toString()).put("key", project.key());
        }
        return JSON.writeValueAsString(array);
    }

    static List<EffectiveSettings.Project> projects(String json) {
        List<EffectiveSettings.Project> projects = new ArrayList<>();
        for (JsonNode project : JSON.readTree(json)) {
            projects.add(new EffectiveSettings.Project(UUID.fromString(project.path("systemId").stringValue()),
                    project.path("key").stringValue()));
        }
        return projects;
    }
}
