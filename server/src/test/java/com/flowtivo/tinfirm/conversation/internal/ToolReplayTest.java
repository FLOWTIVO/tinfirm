package com.flowtivo.tinfirm.conversation.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.runner.EffortLevel;
import com.flowtivo.tinfirm.runner.RunnerMessages;
import com.flowtivo.tinfirm.runner.RuntimeKind;
import com.flowtivo.tinfirm.support.ProtocolSchema;
import com.flowtivo.tinfirm.system.FixedTools;
import com.flowtivo.tinfirm.system.SystemKind;
import com.flowtivo.tinfirm.system.ToolEffect;
import com.flowtivo.tinfirm.system.ToolInfo;
import com.flowtivo.tinfirm.system.ToolReplay;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The replay class of a tool (package R, §3.1): the default follows the effect ({@code READ} is {@code SAFE},
 * {@code WRITE} and {@code UNKNOWN} are {@code UNSAFE}); every fixed catalogue keeps the default, no write declared
 * {@code SAFE}; a catalogue's {@code SAFE} write keeps its class; and the run's tool list carries it
 * ({@code run.assign.tools[].replay}), which the runner protocol's v2 schema accepts, and leaves it out without one.
 */
class ToolReplayTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final JsonNode SCHEMA = JSON.createObjectNode().put("type", "object");

    @Test
    void theDefaultFollowsTheEffect() {
        assertThat(ToolReplay.of(ToolEffect.READ)).isEqualTo(ToolReplay.SAFE);
        assertThat(ToolReplay.of(ToolEffect.WRITE)).isEqualTo(ToolReplay.UNSAFE);
        assertThat(ToolReplay.of(ToolEffect.UNKNOWN)).isEqualTo(ToolReplay.UNSAFE);
        // A tool made without a class (an MCP server's, as its catalogue's refresh found it) takes its effect's.
        assertThat(new ToolInfo("lookup", null, null, SCHEMA, ToolEffect.READ, true, false).replay())
            .isEqualTo(ToolReplay.SAFE);
        assertThat(new ToolInfo("delete_contact", null, null, SCHEMA, ToolEffect.UNKNOWN, true, false).replay())
            .isEqualTo(ToolReplay.UNSAFE);
    }

    @Test
    void everyFixedToolKeepsTheDefaultAndNoWriteIsSafe() {
        for (SystemKind kind : SystemKind.values()) {
            for (ToolInfo tool : FixedTools.tools(kind)) {
                assertThat(tool.replay()).as("%s %s", kind, tool.name()).isEqualTo(ToolReplay.of(tool.effect()));
            }
        }
        assertThat(FixedTools.tool(SystemKind.JIRA_DC, FixedTools.GET).orElseThrow().replay())
            .isEqualTo(ToolReplay.SAFE);
        assertThat(FixedTools.tool(SystemKind.JIRA_DC, FixedTools.COMMENT).orElseThrow().replay())
            .isEqualTo(ToolReplay.UNSAFE);
        assertThat(FixedTools.tool(SystemKind.GITHUB, FixedTools.LABEL).orElseThrow().replay())
            .isEqualTo(ToolReplay.UNSAFE);
    }

    @Test
    void aCataloguesSafeWriteKeepsItsClassInTheRunsToolList() {
        ToolInfo put = new ToolInfo("set_priority", "Set the priority", "Sets one field to a value (PUT).", SCHEMA,
                ToolEffect.WRITE, true, true, ToolReplay.SAFE);
        assertThat(put.replay()).isEqualTo(ToolReplay.SAFE);
        assertThat(ConversationTools.spec("jira_set_priority", "Sets the priority.", put).replay())
            .isEqualTo(RunnerMessages.ToolReplay.SAFE);
        ToolInfo comment = FixedTools.tool(SystemKind.JIRA_DC, FixedTools.COMMENT).orElseThrow();
        assertThat(ConversationTools.spec("jira_comment", "Comments.", comment).replay())
            .isEqualTo(RunnerMessages.ToolReplay.UNSAFE);
        ToolInfo get = FixedTools.tool(SystemKind.JIRA_DC, FixedTools.GET).orElseThrow();
        assertThat(ConversationTools.spec("jira_get", "Reads.", get).replay())
            .isEqualTo(RunnerMessages.ToolReplay.SAFE);
    }

    @Test
    void theRunsToolListCarriesTheClassAndTheSchemaTakesIt() {
        ToolInfo get = FixedTools.tool(SystemKind.JIRA_DC, FixedTools.GET).orElseThrow();
        ToolInfo comment = FixedTools.tool(SystemKind.JIRA_DC, FixedTools.COMMENT).orElseThrow();
        RunnerMessages.RunAssign assign = RunnerMessages.RunAssign.v2(UUID.randomUUID(), 1, "claude-max", null,
                new RunnerMessages.AssignedAgent(UUID.randomUUID(), "Analyst", RuntimeKind.CLAUDE_CODE, null,
                        EffortLevel.HIGH),
                new RunnerMessages.AssignedWorkItem(UUID.randomUUID(), "SHOP-1", "Q3 report"),
                new RunnerMessages.AssignedJob(UUID.randomUUID()), "prompt", null, List.of(), List.of(),
                new RunnerMessages.Limits(3600, 200), null, List.of(), List.of(
                        ConversationTools.spec("jira_get", "Reads an issue.", get),
                        ConversationTools.spec("jira_comment", "Comments on an issue.", comment),
                        new RunnerMessages.ToolSpec("jira_search", "Searches.", SCHEMA)),
                List.of());
        // As the runner module's codec writes a frame: with its type.
        String encoded = JSON.writerFor(RunnerMessages.Message.class).writeValueAsString(assign);
        assertThat(ProtocolSchema.violations(2, encoded)).as(encoded).isEmpty();
        JsonNode frame = JSON.readTree(encoded);
        JsonNode tools = frame.get("tools");
        assertThat(tools.get(0).get("replay").asString()).isEqualTo("SAFE");
        assertThat(tools.get(1).get("replay").asString()).isEqualTo("UNSAFE");
        // Optional: a tool without one leaves it out, as a server before package R did.
        assertThat(tools.get(2).has("replay")).isFalse();
    }
}
