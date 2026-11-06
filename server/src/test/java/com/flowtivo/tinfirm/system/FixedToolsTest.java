package com.flowtivo.tinfirm.system;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;

/**
 * The fixed catalogues (the package 3 contract, §5.4): each kind's tools with their effects, and the schemas the
 * runtimes see.
 */
class FixedToolsTest {

    private static List<String> catalogue(SystemKind kind) {
        return FixedTools.tools(kind).stream().map(tool -> tool.name() + ":" + tool.effect()).toList();
    }

    @Test
    void eachTrackerHasItsReadsAndItsWrites() {
        for (SystemKind jira : List.of(SystemKind.JIRA_DC, SystemKind.JIRA_CLOUD)) {
            assertThat(catalogue(jira)).containsExactly("comment:WRITE", "get:READ", "search:READ",
                    "transition:WRITE");
        }
        for (SystemKind host : List.of(SystemKind.GITHUB, SystemKind.GITLAB)) {
            assertThat(catalogue(host)).containsExactly("close:WRITE", "comment:WRITE", "get:READ", "label:WRITE",
                    "open_change_request:WRITE", "search:READ");
        }
        // Slack's one tool, a read (the package 5 contract, open point 13); Teams and the webhook have none.
        assertThat(catalogue(SystemKind.SLACK)).containsExactly("history:READ");
        assertThat(FixedTools.tool(SystemKind.SLACK, "history").orElseThrow().replay()).isEqualTo(ToolReplay.SAFE);
        for (SystemKind none : List.of(SystemKind.GIT, SystemKind.MCP, SystemKind.TEAMS, SystemKind.WEBHOOK)) {
            assertThat(FixedTools.tools(none)).isEmpty();
        }
        assertThat(FixedTools.tools(SystemKind.GITHUB)).allSatisfy(tool -> {
            assertThat(tool.fixed()).isTrue();
            assertThat(tool.enabled()).isTrue();
            assertThat(tool.title()).isNotBlank();
            assertThat(tool.description()).isNotBlank();
        });
    }

    @Test
    void onlyTheChangeRequestIsNotCalledByTheAgent() {
        assertThat(FixedTools.calledByAgent(FixedTools.OPEN_CHANGE_REQUEST)).isFalse();
        assertThat(List.of("get", "search", "comment", "transition", "label", "close"))
            .allMatch(FixedTools::calledByAgent);
        assertThat(FixedTools.tool(SystemKind.GITHUB, "open_change_request").orElseThrow().description())
            .contains("never the agent");
    }

    @Test
    void theSchemasNameWhatEachToolTakes() {
        assertThat(required(SystemKind.JIRA_DC, "get")).containsExactly("key");
        assertThat(required(SystemKind.JIRA_DC, "search")).containsExactly("query");
        assertThat(required(SystemKind.JIRA_CLOUD, "comment")).containsExactly("key", "body");
        assertThat(required(SystemKind.JIRA_CLOUD, "transition")).containsExactly("key", "transition");
        assertThat(required(SystemKind.GITLAB, "label")).containsExactly("key");
        assertThat(required(SystemKind.GITLAB, "close")).containsExactly("key");
        JsonNode search = FixedTools.tool(SystemKind.GITHUB, "search").orElseThrow().inputSchema();
        assertThat(search.get("properties").get("limit").get("maximum").asInt()).isEqualTo(50);
        assertThat(search.get("additionalProperties").asBoolean()).isFalse();
        assertThat(FixedTools.tool(SystemKind.GITLAB, "get").orElseThrow().inputSchema().get("properties").get("key")
            .get("description").asString()).isEqualTo("group/project#iid for an issue, group/project!iid for a merge "
                    + "request");
        assertThat(FixedTools.tool(SystemKind.JIRA_DC, "comment").orElseThrow().description())
            .contains("wiki markup");
        assertThat(FixedTools.tool(SystemKind.JIRA_CLOUD, "comment").orElseThrow().description())
            .contains("document format");
        assertThat(required(SystemKind.SLACK, "history")).containsExactly("channel");
        JsonNode history = FixedTools.tool(SystemKind.SLACK, "history").orElseThrow().inputSchema();
        assertThat(history.get("properties").get("limit").get("maximum").asInt()).isEqualTo(100);
        assertThat(history.get("properties").has("thread")).isTrue();
        assertThat(history.get("properties").has("cursor")).isTrue();
        assertThat(FixedTools.tool(SystemKind.SLACK, "history").orElseThrow().description())
            .contains("other people's words");
    }

    private static List<String> required(SystemKind kind, String tool) {
        List<String> names = new ArrayList<>();
        FixedTools.tool(kind, tool).orElseThrow().inputSchema().get("required").forEach(name -> names.add(
                name.asString()));
        return names;
    }
}
