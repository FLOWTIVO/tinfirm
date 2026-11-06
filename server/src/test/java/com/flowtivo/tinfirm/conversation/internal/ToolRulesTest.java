package com.flowtivo.tinfirm.conversation.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.account.GrantInfo;
import com.flowtivo.tinfirm.agent.AgentDefinition;
import com.flowtivo.tinfirm.agent.AgentInfo;
import com.flowtivo.tinfirm.agent.AgentStatus;
import com.flowtivo.tinfirm.agent.EffectiveAgent;
import com.flowtivo.tinfirm.agent.EffectiveSettings;
import com.flowtivo.tinfirm.agent.ModelAccessSetting;
import com.flowtivo.tinfirm.agent.SettingsOwner;
import com.flowtivo.tinfirm.agent.ToolMode;
import com.flowtivo.tinfirm.agent.ToolNames;
import com.flowtivo.tinfirm.conversation.BindingInfo;
import com.flowtivo.tinfirm.runner.EffortLevel;
import com.flowtivo.tinfirm.runner.RuntimeKind;
import com.flowtivo.tinfirm.system.FixedTools;
import com.flowtivo.tinfirm.system.SystemInfo;
import com.flowtivo.tinfirm.system.SystemKind;
import com.flowtivo.tinfirm.system.ToolEffect;
import com.flowtivo.tinfirm.system.ToolInfo;

import tools.jackson.databind.json.JsonMapper;

/**
 * The {@code ConversationTools} table test (the package 3 contract, §5.4 and §9; I3, I4, I9, I19): steps 1 to 5 of a
 * tool's mode as a table, each row one case the contract lists, with whether the run lists the tool and what a call
 * answers. The step that needs the database (settings that try {@code ALLOW} on the agent's tool, which the settings'
 * API and the database refuse) and the names of the tools are {@code ConversationToolsIntegrationTest}'s.
 */
class ToolRulesTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final UUID COMPANY = UUID.randomUUID();

    private static final UUID PERSON = UUID.randomUUID();

    private static final UUID OTHER_MEMBER = UUID.randomUUID();

    private static final SystemInfo JIRA = new SystemInfo(UUID.randomUUID(), COMPANY, "Jira", "jira",
            SystemKind.JIRA_DC, "https://jira.example.test", null, null, false);

    private static final SystemInfo GITHUB = new SystemInfo(UUID.randomUUID(), COMPANY, "GitHub", "github",
            SystemKind.GITHUB, "https://api.github.com", null, null, false);

    private static final SystemInfo MCP = new SystemInfo(UUID.randomUUID(), COMPANY, "Docs MCP", "docs",
            SystemKind.MCP, "https://mcp.example.test", null, null, false);

    private static final SystemInfo OWN = new SystemInfo(UUID.randomUUID(), COMPANY, "My notes", "notes",
            SystemKind.MCP, "https://notes.example.test", PERSON, null, false);

    private static final SystemInfo OTHERS = new SystemInfo(UUID.randomUUID(), COMPANY, "Their notes", "theirs",
            SystemKind.MCP, "https://theirs.example.test", OTHER_MEMBER, null, false);

    private static final ToolRules.Names NAMES = new ToolRules.Names("Analyst", "Alice", "Jira");

    private static AgentDefinition.ToolEntry entry(String name, ToolMode mode, ToolEffect chosenFor) {
        return new AgentDefinition.ToolEntry(name, mode, chosenFor);
    }

    /** The agent: Jira's get ALLOW, comment ASK, transition ALLOW; GitHub's get and comment ALLOW; the MCP server's. */
    private static final AgentDefinition AGENT = new AgentDefinition(List.of(
            new AgentDefinition.SystemEntry(JIRA.id(), List.of(entry("get", ToolMode.ALLOW, ToolEffect.READ),
                    entry("search", ToolMode.ALLOW, ToolEffect.READ),
                    entry("comment", ToolMode.ASK, ToolEffect.WRITE),
                    entry("transition", ToolMode.ALLOW, ToolEffect.WRITE))),
            new AgentDefinition.SystemEntry(GITHUB.id(), List.of(entry("get", ToolMode.ALLOW, ToolEffect.READ),
                    entry("comment", ToolMode.ALLOW, ToolEffect.WRITE))),
            new AgentDefinition.SystemEntry(MCP.id(), List.of(entry("lookup", ToolMode.ALLOW, ToolEffect.READ),
                    entry("purge", ToolMode.ALLOW, ToolEffect.READ),
                    entry("draft", ToolMode.ALLOW, ToolEffect.WRITE)))),
            List.of(), List.of());

    /** The person's settings: Jira's search switched off, transition turned to ASK; their own notes server. */
    private static final EffectiveSettings SETTINGS = new EffectiveSettings(UUID.randomUUID(),
            new SettingsOwner.Person(PERSON), true, new EffectiveAgent(new AgentInfo(UUID.randomUUID(), COMPANY,
                    "Analyst", "analyst", null, "", List.of(RuntimeKind.CLAUDE_CODE), AgentStatus.ACTIVE, PERSON, 1,
                    null), 1, AGENT, AgentDefinition.Widening.NONE),
            RuntimeKind.CLAUDE_CODE, null, null, EffortLevel.DEFAULT, new ModelAccessSetting.Budget(), null, List.of(),
            List.of(), Set.of(), List.of(
                    new EffectiveSettings.ToolOverride(JIRA.id(), "search", EffectiveSettings.OverrideMode.OFF),
                    new EffectiveSettings.ToolOverride(JIRA.id(), "transition", EffectiveSettings.OverrideMode.ASK)),
            List.of(new EffectiveSettings.OwnServer(OWN.id(), List.of(entry("note", ToolMode.ALLOW,
                    ToolEffect.READ), entry("erase", ToolMode.ASK, ToolEffect.WRITE)))));

    private static ToolInfo fixed(SystemKind kind, String name) {
        return FixedTools.tool(kind, name).orElseThrow();
    }

    private static ToolInfo mcp(String name, ToolEffect effect) {
        return new ToolInfo(name, null, "The " + name + " tool.", JSON.createObjectNode().put("type", "object"),
                effect, true, false);
    }

    record Row(String what, SystemInfo system, String tool, @Nullable ToolInfo served, ToolRules.BindingView binding,
            String expected, ToolRules.@Nullable Step step) {
    }

    @Test
    void stepsOneToFiveAnswerEachCaseOfTheContract() {
        ToolRules.BindingView own = ToolRules.BindingView.own();
        ToolRules.BindingView readGrant = ToolRules.BindingView.granted(GrantInfo.Access.READ, "Bot", "Analysts");
        ToolRules.BindingView writeGrant = ToolRules.BindingView.granted(GrantInfo.Access.WRITE, "Bot", "Analysts");
        ToolRules.BindingView readLent = ToolRules.BindingView.fixed("svc-ro", GrantInfo.Access.READ);
        ToolRules.BindingView writeLent = ToolRules.BindingView.fixed("svc-rw", GrantInfo.Access.WRITE);
        ToolRules.BindingView unbound = ToolRules.BindingView.unbound();
        ToolRules.BindingView waiting = ToolRules.BindingView.waiting(BindingInfo.Reason.NONE);
        ToolRules.BindingView gone = ToolRules.BindingView.gone(BindingInfo.Reason.GRANT_REMOVED);
        List<Row> rows = List.of(
                // ALLOW and ASK on the agent.
                new Row("ALLOW on the agent", JIRA, "get", fixed(SystemKind.JIRA_DC, "get"), own, "ALLOW", null),
                new Row("ASK on the agent", JIRA, "comment", fixed(SystemKind.JIRA_DC, "comment"), own, "ASK", null),
                // A tool the agent does not list (I3): not listed, TOOL_DENIED.
                new Row("not on the agent", GITHUB, "label", fixed(SystemKind.GITHUB, "label"), own, "TOOL_DENIED",
                        ToolRules.Step.AGENT),
                // A tool its system does not serve: UNKNOWN_TOOL; open_change_request is never the agent's to call.
                new Row("not served", JIRA, "delete", null, own, "UNKNOWN_TOOL", ToolRules.Step.SERVED),
                new Row("open_change_request", GITHUB, FixedTools.OPEN_CHANGE_REQUEST,
                        fixed(SystemKind.GITHUB, FixedTools.OPEN_CHANGE_REQUEST), own, "UNKNOWN_TOOL",
                        ToolRules.Step.SERVED),
                new Row("switched off on its MCP server", MCP, "lookup", new ToolInfo("lookup", null, null,
                        JSON.createObjectNode(), ToolEffect.READ, false, false), own, "UNKNOWN_TOOL",
                        ToolRules.Step.SERVED),
                // Settings OFF and ASK (I4).
                new Row("settings OFF", JIRA, "search", fixed(SystemKind.JIRA_DC, "search"), own, "TOOL_DENIED",
                        ToolRules.Step.SETTINGS),
                new Row("settings ASK", JIRA, "transition", fixed(SystemKind.JIRA_DC, "transition"), own, "ASK",
                        null),
                // I19: an ALLOW chosen for a read whose effect became WRITE is ASK; an unconfirmed MCP tool writes.
                new Row("ALLOW for a read, now a write", MCP, "purge", mcp("purge", ToolEffect.WRITE), own, "ASK",
                        null),
                new Row("ALLOW for a read, unconfirmed", MCP, "lookup", mcp("lookup", ToolEffect.UNKNOWN), own, "ASK",
                        null),
                new Row("ALLOW for a write, unconfirmed", MCP, "draft", mcp("draft", ToolEffect.UNKNOWN), own,
                        "ALLOW", null),
                new Row("ALLOW for a read, still a read", MCP, "lookup", mcp("lookup", ToolEffect.READ), own, "ALLOW",
                        null),
                // A READ and a WRITE grant (I9): a read-only grant lists no write, an unconfirmed tool included.
                new Row("a read through a READ grant", GITHUB, "get", fixed(SystemKind.GITHUB, "get"), readGrant,
                        "ALLOW", null),
                new Row("a write through a READ grant", GITHUB, "comment", fixed(SystemKind.GITHUB, "comment"),
                        readGrant, "TOOL_DENIED", ToolRules.Step.BINDING),
                new Row("an unconfirmed tool through a READ grant", MCP, "draft", mcp("draft", ToolEffect.UNKNOWN),
                        readGrant, "TOOL_DENIED", ToolRules.Step.BINDING),
                new Row("a write through a WRITE grant", GITHUB, "comment", fixed(SystemKind.GITHUB, "comment"),
                        writeGrant, "ALLOW", null),
                // A trigger's credential lent to its service account READ or WRITE (ADR-0072), as a grant's.
                new Row("a write through a READ lending", GITHUB, "comment", fixed(SystemKind.GITHUB, "comment"),
                        readLent, "TOOL_DENIED", ToolRules.Step.BINDING),
                new Row("a read through a READ lending", GITHUB, "get", fixed(SystemKind.GITHUB, "get"), readLent,
                        "ALLOW", null),
                new Row("a write through a WRITE lending", GITHUB, "comment", fixed(SystemKind.GITHUB, "comment"),
                        writeLent, "ALLOW", null),
                // An unbound system's tools are listed: the first call binds; a waiting one's too, a call parks.
                new Row("an unbound system", GITHUB, "comment", fixed(SystemKind.GITHUB, "comment"), unbound, "ALLOW",
                        null),
                new Row("a waiting binding", JIRA, "comment", fixed(SystemKind.JIRA_DC, "comment"), waiting, "ASK",
                        null),
                // A gone binding removes the system's tools.
                new Row("a gone binding", GITHUB, "get", fixed(SystemKind.GITHUB, "get"), gone, "ACCOUNT_GONE",
                        ToolRules.Step.BINDING),
                // The person's own MCP server: their settings' rows; another member's server is none of theirs.
                new Row("own server, ALLOW", OWN, "note", mcp("note", ToolEffect.READ), own, "ALLOW", null),
                new Row("own server, ASK", OWN, "erase", mcp("erase", ToolEffect.WRITE), own, "ASK", null),
                new Row("own server, a tool not set", OWN, "other", mcp("other", ToolEffect.READ), own, "TOOL_DENIED",
                        ToolRules.Step.AGENT),
                new Row("another member's server", OTHERS, "note", mcp("note", ToolEffect.READ), own, "UNKNOWN_TOOL",
                        ToolRules.Step.SERVED));
        for (Row row : rows) {
            String name = ToolNames.of(row.system().handle(), row.tool());
            ToolRules.Decision decision = ToolRules.decide(row.system(), name, row.tool(), row.served(), PERSON,
                    SETTINGS, row.binding(), NAMES);
            String answered = switch (decision) {
                case ToolRules.Decision.Allow allow -> "ALLOW";
                case ToolRules.Decision.Ask ask -> "ASK";
                case ToolRules.Decision.Refused refused -> refused.code();
            };
            assertThat(answered).as(row.what()).isEqualTo(row.expected());
            assertThat(decision.listed()).as(row.what() + " listed").isEqualTo(row.step() == null);
            if (decision instanceof ToolRules.Decision.Refused refused) {
                assertThat(refused.step()).as(row.what() + " step").isEqualTo(row.step());
                assertThat(refused.message()).as(row.what() + " message").isNotBlank();
            }
        }
    }

    @Test
    void theRefusalsSayWhyAndAReadOnlyGrantIsNamed() {
        ToolRules.Decision write = ToolRules.decide(GITHUB, "github_comment", "comment",
                fixed(SystemKind.GITHUB, "comment"), PERSON, SETTINGS,
                ToolRules.BindingView.granted(GrantInfo.Access.READ, "Bot", "Analysts"),
                new ToolRules.Names("Analyst", "Alice", "GitHub"));
        assertThat(((ToolRules.Decision.Refused) write).message())
            .isEqualTo("`github_comment` writes, and you act through the read-only grant of Analysts on GitHub. Tell "
                    + "Alice in your summary when your work needs it.");
        ToolRules.Decision lent = ToolRules.decide(GITHUB, "github_comment", "comment",
                fixed(SystemKind.GITHUB, "comment"), PERSON, SETTINGS,
                ToolRules.BindingView.fixed("svc-ro", GrantInfo.Access.READ), new ToolRules.Names("Analyst", "Alice",
                        "GitHub"));
        assertThat(((ToolRules.Decision.Refused) lent).message())
            .isEqualTo("`github_comment` writes, and you act through the credential lent read-only to svc-ro on "
                    + "GitHub. Tell Alice in your summary when your work needs it.");
        ToolRules.Decision notListed = ToolRules.decide(GITHUB, "github_label", "label",
                fixed(SystemKind.GITHUB, "label"), PERSON, SETTINGS, ToolRules.BindingView.own(), NAMES);
        assertThat(((ToolRules.Decision.Refused) notListed).message())
            .startsWith("`github_label` is not one of Analyst's tools; whoever edits the agent adds tools.");
        ToolRules.Decision gone = ToolRules.decide(JIRA, "jira_get", "get", fixed(SystemKind.JIRA_DC, "get"), PERSON,
                SETTINGS, ToolRules.BindingView.gone(BindingInfo.Reason.LEFT_GROUP), NAMES);
        assertThat(((ToolRules.Decision.Refused) gone).message())
            .startsWith("Jira can no longer be used in this conversation (Alice left the group of the grant it used).");
    }

    @Test
    void aDescriptionEndsWithWhomTheToolRunsAsOnceBound() {
        ToolInfo comment = fixed(SystemKind.JIRA_DC, "comment");
        ToolRules.Decision ask = new ToolRules.Decision.Ask(comment, ToolEffect.WRITE);
        ToolRules.Decision allow = new ToolRules.Decision.Allow(comment, ToolEffect.WRITE);
        assertThat(ToolRules.description(comment, allow, ToolRules.BindingView.own(), NAMES))
            .startsWith(comment.description())
            .endsWith("\n\nRuns as Alice's own account.");
        assertThat(ToolRules.description(comment, ask, ToolRules.BindingView.granted(GrantInfo.Access.READ, "Bot",
                "Analysts"), NAMES))
            .endsWith("Each call waits for Alice's approval. Runs through Bot via Analysts, read-only.");
        assertThat(ToolRules.description(comment, allow, ToolRules.BindingView.granted(GrantInfo.Access.WRITE, "Bot",
                "Analysts"), NAMES))
            .endsWith("Runs through Bot via Analysts.");
        assertThat(ToolRules.description(comment, allow, ToolRules.BindingView.unbound(), NAMES))
            .endsWith("Not connected yet: the first call uses Alice's account on Jira, or asks Alice to connect one.");
        ToolInfo long1 = new ToolInfo("x", null, "d".repeat(5000), JSON.createObjectNode(), ToolEffect.READ, true,
                false);
        assertThat(ToolRules.description(long1, allow, ToolRules.BindingView.own(), NAMES))
            .hasSizeLessThanOrEqualTo(ToolRules.MAX_DESCRIPTION)
            .endsWith("Runs as Alice's own account.");
    }

    @Test
    void aChannelsThreadReadsThatThreadOnly() {
        SystemInfo slack = new SystemInfo(UUID.randomUUID(), COMPANY, "Slack", "slack", SystemKind.SLACK,
                "https://slack.com/api", null, null, false);
        SystemInfo otherSlack = new SystemInfo(UUID.randomUUID(), COMPANY, "Other Slack", "other", SystemKind.SLACK,
                "https://slack.com/api", null, null, false);
        ToolRules.ThreadPlace asked = new ToolRules.ThreadPlace(slack.id(), "C0SUPPORT", "1712345678.000100");
        // The thread asked in, and any read outside a channel's thread: not this rule's.
        assertThat(ToolRules.outsideThread(asked, slack, "slack_history", "history", "C0SUPPORT",
                "1712345678.000100", NAMES)).isNull();
        assertThat(ToolRules.outsideThread(null, slack, "slack_history", "history", "C0FINANCE", null, NAMES))
            .isNull();
        assertThat(ToolRules.outsideThread(asked, JIRA, "jira_get", "get", null, null, NAMES)).isNull();
        // The channel's own messages, another thread of it, another channel, another workspace: refused at the
        // binding's step, naming the thread to read.
        for (String[] read : new String[][] { { "C0SUPPORT", null }, { "C0SUPPORT", "1712345699.000200" },
                { "C0FINANCE", "1712345678.000100" } }) {
            ToolRules.Decision.Refused refused = ToolRules.outsideThread(asked, slack, "slack_history", "history",
                    read[0], read[1], NAMES);
            assertThat(refused).as("%s %s", read[0], read[1]).isNotNull();
            assertThat(refused.step()).isEqualTo(ToolRules.Step.BINDING);
            assertThat(refused.code()).isEqualTo(ToolRules.TOOL_DENIED);
            assertThat(refused.message()).contains("`slack_history` reads only the thread you were asked in",
                    "\"channel\": \"C0SUPPORT\" and \"thread\": \"1712345678.000100\"", "Alice");
        }
        ToolRules.Decision.Refused otherWorkspace = ToolRules.outsideThread(asked, otherSlack, "other_history",
                "history", "C0SUPPORT", "1712345678.000100", NAMES);
        assertThat(otherWorkspace).isNotNull();
        assertThat(otherWorkspace.message()).contains("`other_history` reads nothing here");
    }

    @Test
    void namesFitTheProtocolAndTheRuntimes() {
        String tool = "Search_All_Records_In_The_Archive_Including_The_Very_Old_Ones_Too";
        String wire = ToolNameStore.wireName("docs", tool);
        assertThat(wire).hasSize(ToolNames.MAX_LENGTH).matches("^[a-z0-9][a-z0-9_-]{0,63}$")
            .isEqualTo(ToolNames.of("docs", tool).toLowerCase(java.util.Locale.ROOT));
        assertThat(ToolNameStore.wireName("jira", "get")).isEqualTo("jira_get");
        // Unique names when the first is taken: within 64 characters, different per attempt and per tool.
        String first = ToolNameStore.hashed("docs", MCP.id(), tool, 1);
        String second = ToolNameStore.hashed("docs", MCP.id(), tool, 2);
        assertThat(first).hasSizeLessThanOrEqualTo(64).matches("^[a-z0-9][a-z0-9_-]{0,63}$").isNotEqualTo(second);
        assertThat(ToolNameStore.hashed("docs", MCP.id(), "getIssue", 1))
            .isNotEqualTo(ToolNameStore.hashed("docs", MCP.id(), "getissue", 1))
            .startsWith("docs_getissue_");
        assertThat(ToolNameStore.PLATFORM_TOOLS).contains("request_access", "ask", "finish");
    }
}
