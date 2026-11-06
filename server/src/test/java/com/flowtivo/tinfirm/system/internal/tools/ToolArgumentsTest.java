package com.flowtivo.tinfirm.system.internal.tools;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.system.SystemKind;
import com.flowtivo.tinfirm.system.ToolCheck;
import com.flowtivo.tinfirm.system.ToolOutcome;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The checks of a call's arguments before it runs or waits for an approval: normalized, kind by kind, and what the call
 * does in words.
 */
class ToolArgumentsTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static ToolCheck check(SystemKind kind, String tool, String arguments) {
        return ToolArguments.check(kind, tool, JSON.readTree(arguments));
    }

    private static ToolCheck.Valid valid(ToolCheck check) {
        assertThat(check).as("%s", check).isInstanceOf(ToolCheck.Valid.class);
        return (ToolCheck.Valid) check;
    }

    private static ToolCheck.Invalid invalid(ToolCheck check) {
        assertThat(check).as("%s", check).isInstanceOf(ToolCheck.Invalid.class);
        return (ToolCheck.Invalid) check;
    }

    private static String json(JsonNode node) {
        return JSON.writeValueAsString(node);
    }

    @Test
    void theReadsAreTrimmedAndTheLimitClamped() {
        ToolCheck.Valid get = valid(check(SystemKind.JIRA_DC, "get", "{\"key\":\" SHOP-1 \",\"extra\":1}"));
        assertThat(json(get.arguments())).isEqualTo("{\"key\":\"SHOP-1\"}");
        assertThat(get.what()).isEqualTo("read SHOP-1");

        assertThat(json(valid(check(SystemKind.GITHUB, "search", "{\"query\":\"is:open\"}")).arguments()))
            .isEqualTo("{\"query\":\"is:open\",\"limit\":20}");
        assertThat(json(valid(check(SystemKind.GITLAB, "search", "{\"query\":\"x\",\"limit\":500}")).arguments()))
            .isEqualTo("{\"query\":\"x\",\"limit\":50}");
        assertThat(json(valid(check(SystemKind.GITLAB, "search", "{\"query\":\"x\",\"limit\":0}")).arguments()))
            .isEqualTo("{\"query\":\"x\",\"limit\":1}");
        assertThat(invalid(check(SystemKind.GITLAB, "search", "{\"query\":\"x\",\"limit\":\"5\"}")).message())
            .isEqualTo("The `limit` of `search` is a whole number from 1 to 50.");
        assertThat(valid(check(SystemKind.JIRA_DC, "search", "{\"query\":\"" + "a".repeat(300) + "\"}")).what())
            .hasSize("search ``".length() + 200).endsWith("…`");
    }

    @Test
    void keysFollowTheSystemsFormat() {
        assertThat(valid(check(SystemKind.GITHUB, "get", "{\"key\":\"acme/shop#57\"}")).what())
            .isEqualTo("read acme/shop#57");
        assertThat(valid(check(SystemKind.GITLAB, "get", "{\"key\":\"acme/sub/shop!7\"}")).what())
            .isEqualTo("read acme/sub/shop!7");
        for (String key : new String[] { "acme#57", "acme/shop#0", "acme/shop", "acme/shop#99999999999" }) {
            assertThat(invalid(check(SystemKind.GITHUB, "get", "{\"key\":\"" + key + "\"}")).message())
                .as(key)
                .isEqualTo("`get` takes the subject's `key`: owner/repo#number, e.g. acme/shop#57.");
        }
        assertThat(invalid(check(SystemKind.GITLAB, "close", "{\"key\":\"shop#7\"}")).code())
            .isEqualTo(ToolOutcome.Code.INVALID_REQUEST);
        assertThat(invalid(check(SystemKind.JIRA_CLOUD, "get", "{\"key\":42}")).message())
            .isEqualTo("`get` takes the subject's `key`: the issue key, e.g. SHOP-241.");
    }

    @Test
    void theWritesSayWhatTheyDo() {
        assertThat(valid(check(SystemKind.JIRA_DC, "comment", "{\"key\":\"SHOP-1\",\"body\":\" Done \"}")).arguments()
            .get("body").asString()).isEqualTo("Done");
        assertThat(valid(check(SystemKind.JIRA_DC, "comment", "{\"key\":\"SHOP-1\",\"body\":\"Done\"}")).what())
            .isEqualTo("comment on SHOP-1");
        assertThat(invalid(check(SystemKind.GITHUB, "comment", "{\"key\":\"a/b#1\",\"body\":\""
                + "x".repeat(30_001) + "\"}")).message())
            .isEqualTo("`comment` takes `body`, the comment in Markdown, a text of 1 to 30000 characters.");
        assertThat(valid(check(SystemKind.JIRA_CLOUD, "transition", "{\"key\":\"SHOP-1\",\"transition\":\"Done\"}"))
            .what()).isEqualTo("move SHOP-1 through `Done`");
        assertThat(valid(check(SystemKind.GITHUB, "close", "{\"key\":\"a/b#1\"}")).what()).isEqualTo("close a/b#1");
        ToolCheck.Valid label = valid(check(SystemKind.GITLAB, "label", "{\"key\":\"a/b#1\",\"add\":[\"x\",\" x \"],"
                + "\"remove\":[\"y\"]}"));
        assertThat(json(label.arguments())).isEqualTo("{\"key\":\"a/b#1\",\"add\":[\"x\"],\"remove\":[\"y\"]}");
        assertThat(label.what()).isEqualTo("a/b#1: add `x`; remove `y`");
        assertThat(invalid(check(SystemKind.GITLAB, "label", "{\"key\":\"a/b#1\"}")).message())
            .startsWith("`label` takes `add`");
    }

    @Test
    void slacksHistoryTakesAChannelAThreadALimitAndACursor() {
        ToolCheck.Valid channel = valid(check(SystemKind.SLACK, "history", "{\"channel\":\" C0123ABCD \",\"x\":1}"));
        assertThat(json(channel.arguments())).isEqualTo("{\"channel\":\"C0123ABCD\",\"limit\":20}");
        assertThat(channel.what()).isEqualTo("read the messages of C0123ABCD");
        ToolCheck.Valid thread = valid(check(SystemKind.SLACK, "history", "{\"channel\":\"G0123ABCD\","
                + "\"thread\":\"1712345678.000100\",\"limit\":500,\"cursor\":\"bmV4dA==\"}"));
        assertThat(json(thread.arguments())).isEqualTo("{\"channel\":\"G0123ABCD\",\"thread\":\"1712345678.000100\","
                + "\"limit\":100,\"cursor\":\"bmV4dA==\"}");
        assertThat(thread.what()).isEqualTo("read the thread 1712345678.000100 of G0123ABCD");
        for (String bad : new String[] { "#general", "D0123ABCD", "c0123abcd", "" }) {
            assertThat(invalid(check(SystemKind.SLACK, "history", "{\"channel\":\"" + bad + "\"}")).message())
                .as(bad)
                .isEqualTo("`history` takes `channel`: the channel's id, e.g. C0123ABCD.");
        }
        assertThat(invalid(check(SystemKind.SLACK, "history", "{\"channel\":\"C0123ABCD\",\"thread\":\"yesterday\"}"))
            .message()).startsWith("`history` takes `thread`");
        assertThat(invalid(check(SystemKind.SLACK, "history", "{\"channel\":\"C0123ABCD\",\"cursor\":\"a b\"}"))
            .message()).isEqualTo("The `cursor` of `history` is the one a previous result named.");
        assertThat(invalid(check(SystemKind.SLACK, "history", "{\"channel\":\"C0123ABCD\",\"limit\":\"5\"}"))
            .message()).isEqualTo("The `limit` of `history` is a whole number from 1 to 100.");
        // Slack has no other tool: no post (open point 13), and none of a tracker's.
        assertThat(invalid(check(SystemKind.SLACK, "post", "{}")).code()).isEqualTo(ToolOutcome.Code.UNKNOWN_TOOL);
        assertThat(invalid(check(SystemKind.SLACK, "get", "{\"key\":\"SHOP-1\"}")).code())
            .isEqualTo(ToolOutcome.Code.UNKNOWN_TOOL);
        assertThat(invalid(check(SystemKind.TEAMS, "history", "{\"channel\":\"C0123ABCD\"}")).code())
            .isEqualTo(ToolOutcome.Code.UNKNOWN_TOOL);
    }

    @Test
    void unknownToolsAndTheServersOwnAreToldApart() {
        assertThat(invalid(check(SystemKind.JIRA_DC, "close", "{\"key\":\"SHOP-1\"}")).code())
            .isEqualTo(ToolOutcome.Code.UNKNOWN_TOOL);
        assertThat(invalid(check(SystemKind.GITHUB, "open_change_request", "{}")).code())
            .isEqualTo(ToolOutcome.Code.UNKNOWN_TOOL);
        assertThat(invalid(check(SystemKind.GIT, "get", "{}")).code()).isEqualTo(ToolOutcome.Code.UNKNOWN_TOOL);
        // An MCP server validates its own tools' arguments: they pass as they are.
        assertThat(json(valid(check(SystemKind.MCP, "anything", "{\"a\":[1,2]}")).arguments()))
            .isEqualTo("{\"a\":[1,2]}");
        assertThat(invalid(check(SystemKind.MCP, "anything", "\"text\"")).code())
            .isEqualTo(ToolOutcome.Code.INVALID_REQUEST);
        assertThat(invalid(check(SystemKind.MCP, "anything", "{\"a\":\"" + "x".repeat(70_000) + "\"}")).message())
            .isEqualTo("The arguments of `anything` are larger than 64 KiB.");
    }

    @Test
    void argumentsOf64KiBAsJsonPassAndOneByteMoreDoNot() {
        // {"a":"…"}: 8 bytes around the value.
        String at = "{\"a\":\"" + "x".repeat(64 * 1024 - 8) + "\"}";
        assertThat(valid(check(SystemKind.MCP, "anything", at)).arguments().get("a").asString())
            .hasSize(64 * 1024 - 8);
        String over = "{\"a\":\"" + "x".repeat(64 * 1024 - 7) + "\"}";
        assertThat(invalid(check(SystemKind.MCP, "anything", over)).code())
            .isEqualTo(ToolOutcome.Code.INVALID_REQUEST);
    }
}
