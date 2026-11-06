package com.flowtivo.tinfirm.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.runner.EffortLevel;
import com.flowtivo.tinfirm.runner.RuntimeKind;
import com.flowtivo.tinfirm.shared.web.ApiException;
import com.flowtivo.tinfirm.system.ToolEffect;

/**
 * The agent module's pure rules (the package 3 contract, §3.5, §5.4 and §11): what a version adds over another and the
 * agent a person's turns use (narrowing), steps 3 and 4 of a tool's mode with I19, a tool's name in a run, the grammar
 * of network hosts and of handles.
 */
class AgentRulesTest {

    private static final UUID JIRA = UUID.randomUUID();

    private static final UUID GITHUB = UUID.randomUUID();

    private static final UUID MCP = UUID.randomUUID();

    private static final UUID REPO = UUID.randomUUID();

    private static final UUID OTHER_REPO = UUID.randomUUID();

    private static AgentDefinition.ToolEntry tool(String name, ToolMode mode, ToolEffect chosenFor) {
        return new AgentDefinition.ToolEntry(name, mode, chosenFor);
    }

    private static final AgentDefinition V1 = new AgentDefinition(
            List.of(new AgentDefinition.SystemEntry(JIRA, List.of(tool("get", ToolMode.ALLOW, ToolEffect.READ),
                    tool("comment", ToolMode.ASK, ToolEffect.WRITE))),
                    new AgentDefinition.SystemEntry(GITHUB, List.of(tool("get", ToolMode.ALLOW, ToolEffect.READ)))),
            List.of(new AgentDefinition.RepositoryEntry(REPO, GITHUB, RepositoryAccess.READ)),
            List.of("api.example.com", "*.npmjs.org"));

    @Test
    void aVersionWidensWhatItAddsOrRaisesAndNarrowingKeepsTheNarrowerOfTwo() {
        // Unchanged, or only narrowed: no widening.
        assertThat(V1.wideningOver(V1).none()).isTrue();
        AgentDefinition narrowed = new AgentDefinition(
                List.of(new AgentDefinition.SystemEntry(JIRA, List.of(tool("get", ToolMode.ASK, ToolEffect.READ)))),
                List.of(), List.of("api.example.com"));
        assertThat(narrowed.wideningOver(V1).none()).isTrue();
        assertThat(V1.wideningOver(narrowed).none()).isFalse();

        // V2 widens: a system, an ALLOW write tool, ASK raised to ALLOW, a repository, READ raised to WRITE, a host.
        AgentDefinition v2 = new AgentDefinition(
                List.of(new AgentDefinition.SystemEntry(JIRA, List.of(tool("get", ToolMode.ALLOW, ToolEffect.READ),
                        tool("comment", ToolMode.ALLOW, ToolEffect.WRITE),
                        tool("transition", ToolMode.ALLOW, ToolEffect.WRITE))),
                        new AgentDefinition.SystemEntry(GITHUB, List.of()),
                        new AgentDefinition.SystemEntry(MCP, List.of(tool("search", ToolMode.ALLOW, ToolEffect.READ)))),
                List.of(new AgentDefinition.RepositoryEntry(REPO, GITHUB, RepositoryAccess.WRITE),
                        new AgentDefinition.RepositoryEntry(OTHER_REPO, GITHUB, RepositoryAccess.READ)),
                List.of("api.example.com", "*.npmjs.org", "*.example.com", "registry.npmjs.org"));
        AgentDefinition.Widening widening = v2.wideningOver(V1);
        assertThat(widening.systems()).containsExactly(MCP);
        assertThat(widening.tools()).extracting(entry -> entry.tool().name() + ":"
                + (entry.was() != null ? entry.was().mode() : null))
            .containsExactlyInAnyOrder("comment:ASK", "transition:null", "search:null");
        assertThat(widening.repositories()).extracting(entry -> entry.repository().repositoryId() + ":" + entry.was())
            .containsExactlyInAnyOrder(REPO + ":READ", OTHER_REPO + ":null");
        // registry.npmjs.org is under *.npmjs.org, which V1 allowed: not a widening.
        assertThat(widening.hosts()).containsExactly("*.example.com");

        // The agent of someone who accepted V1 while V2 is current: what both allow. GitHub's get went at once.
        AgentDefinition effective = v2.narrowedBy(V1);
        assertThat(effective.systems()).extracting(AgentDefinition.SystemEntry::systemId)
            .containsExactlyInAnyOrder(JIRA, GITHUB);
        assertThat(effective.tool(JIRA, "comment")).contains(tool("comment", ToolMode.ASK, ToolEffect.WRITE));
        assertThat(effective.tool(JIRA, "transition")).isEmpty();
        assertThat(effective.tool(GITHUB, "get")).isEmpty();
        assertThat(effective.repositories())
            .containsExactly(new AgentDefinition.RepositoryEntry(REPO, GITHUB, RepositoryAccess.READ));
        assertThat(effective.hosts()).containsExactlyInAnyOrder("api.example.com", "*.npmjs.org",
                "registry.npmjs.org");
        // Nothing of the effective agent widens the accepted version.
        assertThat(effective.wideningOver(V1).none()).isTrue();
        // Accepting V2 itself gives V2.
        assertThat(v2.narrowedBy(v2)).isEqualTo(v2);
    }

    /** A ceiling that only reads and asks: an ALLOW chosen for a read, an ASK write, a repository at READ. */
    private static final AgentDefinition READS_ONLY = new AgentDefinition(
            List.of(new AgentDefinition.SystemEntry(JIRA, List.of(tool("get", ToolMode.ALLOW, ToolEffect.READ),
                    tool("comment", ToolMode.ASK, ToolEffect.WRITE)))),
            List.of(new AgentDefinition.RepositoryEntry(REPO, GITHUB, RepositoryAccess.READ)), List.of());

    private static final UUID SKILL = UUID.randomUUID();

    private static final UUID OTHER_SKILL = UUID.randomUUID();

    @Test
    void aCeilingCanActWithAnAllowWriteToolAWriteRepositoryOrAHost() {
        assertThat(READS_ONLY.canAct()).isFalse();
        assertThat(AgentDefinition.EMPTY.canAct()).isFalse();
        // An ALLOW chosen for a read stays ASK once the tool writes (I19): it does not act.
        assertThat(new AgentDefinition(List.of(new AgentDefinition.SystemEntry(MCP,
                List.of(tool("ping", ToolMode.ALLOW, ToolEffect.READ)))), List.of(), List.of()).canAct()).isFalse();
        assertThat(new AgentDefinition(List.of(new AgentDefinition.SystemEntry(MCP,
                List.of(tool("ping", ToolMode.ALLOW, ToolEffect.WRITE)))), List.of(), List.of()).canAct()).isTrue();
        assertThat(new AgentDefinition(List.of(), List.of(new AgentDefinition.RepositoryEntry(REPO, GITHUB,
                RepositoryAccess.WRITE)), List.of()).canAct()).isTrue();
        assertThat(new AgentDefinition(List.of(), List.of(), List.of("api.example.com")).canAct()).isTrue();
        assertThat(V1.canAct()).isTrue();
    }

    @Test
    void anInstructionOrSkillChangeWidensOnlyWhereTheCeilingAfterItCanAct() {
        AgentContent accepted = new AgentContent("Review pull requests.", List.of(new AgentContent.Pin(SKILL, 1)));
        AgentContent instructions = new AgentContent("Review pull requests and merge them.", accepted.skills());
        AgentContent added = new AgentContent(accepted.instructions(), List.of(new AgentContent.Pin(SKILL, 1),
                new AgentContent.Pin(OTHER_SKILL, 3)));
        AgentContent moved = new AgentContent(accepted.instructions(), List.of(new AgentContent.Pin(SKILL, 2)));
        AgentContent removed = new AgentContent(accepted.instructions(), List.of());

        // ADR-0103: within a ceiling that only reads and asks, nothing of it widens: it is announced instead.
        assertThat(READS_ONLY.wideningOver(READS_ONLY, instructions, accepted).none()).isTrue();
        assertThat(READS_ONLY.wideningOver(READS_ONLY, added, accepted).none()).isTrue();

        // Within one that can act, the instructions and a skill added or moved widen; a skill removed narrows.
        AgentDefinition.Widening changed = V1.wideningOver(V1, instructions, accepted);
        assertThat(changed.none()).isFalse();
        assertThat(changed.instructions()).isTrue();
        assertThat(changed.skills()).isEmpty();
        assertThat(changed.details()).containsEntry("instructions", "changed").doesNotContainKey("skills");
        assertThat(V1.wideningOver(V1, added, accepted).skills())
            .containsExactly(new AgentDefinition.Widening.Skill(OTHER_SKILL, 3, null));
        AgentDefinition.Widening pinMoved = V1.wideningOver(V1, moved, accepted);
        assertThat(pinMoved.skills()).containsExactly(new AgentDefinition.Widening.Skill(SKILL, 2, 1));
        assertThat(pinMoved.instructions()).isFalse();
        assertThat(pinMoved.details()).containsEntry("skills", "changed");
        assertThat(V1.wideningOver(V1, removed, accepted).none()).isTrue();
        assertThat(V1.wideningOver(V1, accepted, accepted).none()).isTrue();

        // The ceiling after the change decides: one that gains a host makes the instructions part of the widening.
        AgentDefinition withHost = new AgentDefinition(READS_ONLY.systems(), READS_ONLY.repositories(),
                List.of("api.example.com"));
        AgentDefinition.Widening both = withHost.wideningOver(READS_ONLY, instructions, accepted);
        assertThat(both.hosts()).containsExactly("api.example.com");
        assertThat(both.instructions()).isTrue();
        // And one that loses its last way to act takes the change out of it.
        assertThat(READS_ONLY.wideningOver(V1, instructions, accepted).none()).isTrue();

        // While the change waits, the accepted content is used, less the skills the agent no longer has.
        AgentContent kept = new AgentContent("Old.", List.of(new AgentContent.Pin(SKILL, 1),
                new AgentContent.Pin(OTHER_SKILL, 2)))
            .within(new AgentContent("New.", List.of(new AgentContent.Pin(SKILL, 4))));
        assertThat(kept).isEqualTo(new AgentContent("Old.", List.of(new AgentContent.Pin(SKILL, 1))));
    }

    @Test
    void anAllowChosenForAReadIsBelowAnAllowChosenForAWrite() {
        AgentDefinition forRead = new AgentDefinition(List.of(new AgentDefinition.SystemEntry(MCP,
                List.of(tool("ping", ToolMode.ALLOW, ToolEffect.READ)))), List.of(), List.of());
        AgentDefinition forWrite = new AgentDefinition(List.of(new AgentDefinition.SystemEntry(MCP,
                List.of(tool("ping", ToolMode.ALLOW, ToolEffect.UNKNOWN)))), List.of(), List.of());
        // UNKNOWN is chosen as WRITE.
        assertThat(forWrite.tool(MCP, "ping").orElseThrow().chosenForEffect()).isEqualTo(ToolEffect.WRITE);
        assertThat(forWrite.wideningOver(forRead).tools()).hasSize(1);
        assertThat(forRead.wideningOver(forWrite).none()).isTrue();
        assertThat(forWrite.narrowedBy(forRead).tool(MCP, "ping")).contains(tool("ping", ToolMode.ALLOW,
                ToolEffect.READ));
        // I19: an ALLOW chosen for a read is ASK while the tool writes, or is unconfirmed.
        AgentDefinition.ToolEntry entry = forRead.tool(MCP, "ping").orElseThrow();
        assertThat(entry.modeFor(ToolEffect.READ)).isEqualTo(ToolMode.ALLOW);
        assertThat(entry.modeFor(ToolEffect.WRITE)).isEqualTo(ToolMode.ASK);
        assertThat(entry.modeFor(ToolEffect.UNKNOWN)).isEqualTo(ToolMode.ASK);
        assertThat(forWrite.tool(MCP, "ping").orElseThrow().modeFor(ToolEffect.UNKNOWN)).isEqualTo(ToolMode.ALLOW);
    }

    @Test
    void settingsNarrowTheAgentsToolsStepsThreeAndFour() {
        UUID own = UUID.randomUUID();
        EffectiveAgent agent = new EffectiveAgent(info(), 1, V1, AgentDefinition.Widening.NONE);
        EffectiveSettings settings = new EffectiveSettings(UUID.randomUUID(),
                new SettingsOwner.Person(UUID.randomUUID()), true, agent, RuntimeKind.CLAUDE_CODE, null, null,
                EffortLevel.DEFAULT, new ModelAccessSetting.Budget(), null, List.of(), List.of(), Set.of(GITHUB),
                List.of(new EffectiveSettings.ToolOverride(JIRA, "get", EffectiveSettings.OverrideMode.ASK)),
                List.of(new EffectiveSettings.OwnServer(own, List.of(tool("notes", ToolMode.ALLOW, ToolEffect.READ),
                        tool("write_note", ToolMode.ALLOW, ToolEffect.WRITE)))));
        record Row(UUID system, String tool, ToolEffect effect, EffectiveSettings.ToolDecision expected) {
        }
        for (Row row : List.of(
                // The agent's mode, turned to ASK by the settings.
                new Row(JIRA, "get", ToolEffect.READ, EffectiveSettings.ToolDecision.ASK),
                new Row(JIRA, "comment", ToolEffect.WRITE, EffectiveSettings.ToolDecision.ASK),
                // A tool the agent does not list.
                new Row(JIRA, "transition", ToolEffect.WRITE, EffectiveSettings.ToolDecision.NOT_ON_AGENT),
                // A system switched off.
                new Row(GITHUB, "get", ToolEffect.READ, EffectiveSettings.ToolDecision.SWITCHED_OFF),
                // The person's own server: their modes, with I19.
                new Row(own, "notes", ToolEffect.READ, EffectiveSettings.ToolDecision.ALLOW),
                new Row(own, "notes", ToolEffect.UNKNOWN, EffectiveSettings.ToolDecision.ASK),
                new Row(own, "write_note", ToolEffect.WRITE, EffectiveSettings.ToolDecision.ALLOW),
                new Row(own, "other", ToolEffect.READ, EffectiveSettings.ToolDecision.NOT_ON_AGENT),
                // A system that is neither the agent's nor theirs.
                new Row(MCP, "search", ToolEffect.READ, EffectiveSettings.ToolDecision.NOT_ON_AGENT))) {
            assertThat(settings.modeOf(row.system(), row.tool(), row.effect())).as("%s", row).isEqualTo(row.expected());
        }
        assertThat(settings.systems()).containsExactly(JIRA, own);
        assertThat(settings.tools(JIRA)).containsExactly(tool("comment", ToolMode.ASK, ToolEffect.WRITE),
                tool("get", ToolMode.ASK, ToolEffect.READ));
        assertThat(settings.tools(GITHUB)).isEmpty();
        // GitHub is off: its repository is out of the workspace.
        assertThat(settings.repositories()).isEmpty();
    }

    @Test
    void aToolsNameInARunFitsSixtyFourCharacters() {
        assertThat(ToolNames.of("jira", "get")).isEqualTo("jira_get");
        String exact = "a".repeat(64 - "crm_".length());
        assertThat(ToolNames.of("crm", exact)).isEqualTo("crm_" + exact);
        String longName = "list_every_open_opportunity_of_the_account_with_its_contacts_and_notes";
        String cut = ToolNames.of("salesforce_crm", longName);
        assertThat(cut).hasSize(64).startsWith("salesforce_crm_list_every_open").matches(".*_[0-9a-f]{4}$");
        // Stable, and two tools cut to the same prefix differ.
        assertThat(ToolNames.of("salesforce_crm", longName)).isEqualTo(cut);
        assertThat(ToolNames.of("salesforce_crm", longName + "_2")).isNotEqualTo(cut).hasSize(64);
        String longest = ToolNames.of("a".repeat(20), "b".repeat(64));
        assertThat(longest).hasSize(64).matches("a{20}_b{38}_[0-9a-f]{4}");
    }

    @Test
    void aToolsNameIsTheRunnerProtocolsInTheEditorAsInARun() throws java.io.IOException {
        // ToolSpec.name of runner protocol v2, as the schema states it: lower case only.
        String pattern = tools.jackson.databind.json.JsonMapper.builder().build()
            .readTree(java.nio.file.Files.readString(com.flowtivo.tinfirm.support.ProtocolSchema.path(2)))
            .at("/$defs/ToolSpec/properties/name/pattern")
            .asString();
        assertThat(pattern).isEqualTo("^[a-z0-9][a-z0-9_-]{0,99}$");
        // An MCP server's tools keep their spelling in the system; the run and the editor list them in lower case.
        assertThat(ToolNames.of("crm", "getIssue")).isEqualTo("crm_getissue").matches(pattern);
        assertThat(ToolNames.of("docs", "Create-Page_V2")).isEqualTo("docs_create-page_v2").matches(pattern);
        // A long name is cut and hashed by the tool's own spelling: two tools differing in case only still differ.
        String upper = "Search_All_Records_In_The_Archive_Including_The_Very_Old_Ones_Too";
        String cut = ToolNames.of("docs", upper);
        assertThat(cut).hasSize(ToolNames.MAX_LENGTH).matches(pattern).startsWith("docs_search_all_records");
        assertThat(ToolNames.of("docs", upper.toLowerCase(java.util.Locale.ROOT))).isNotEqualTo(cut)
            .hasSize(ToolNames.MAX_LENGTH).matches(pattern);
    }

    @Test
    void networkHostsAreNormalizedAndAWildcardNeverCoversAPublicSuffix() {
        assertThat(NetworkHosts.parse(" Registry.NPMjs.org. ").host()).isEqualTo("registry.npmjs.org");
        assertThat(NetworkHosts.parse("api.example.com:443").host()).isEqualTo("api.example.com");
        assertThat(NetworkHosts.parse("api.example.com:8443").host()).isEqualTo("api.example.com:8443");
        assertThat(NetworkHosts.parse("*.example.com").host()).isEqualTo("*.example.com");
        for (String refused : List.of("https://example.com", "example.com/path", "user@example.com", "*.com",
                "*.co.uk", "*.github.io", "10.0.0.1", "[::1]", "ex*ample.com", "example.com:0", "a..b", "")) {
            assertThat(NetworkHosts.parse(refused).valid()).as(refused).isFalse();
        }
        assertThat(NetworkHosts.covers("*.example.com", "api.example.com")).isTrue();
        assertThat(NetworkHosts.covers("*.example.com", "*.api.example.com")).isTrue();
        assertThat(NetworkHosts.covers("*.example.com", "example.com")).isFalse();
        assertThat(NetworkHosts.covers("*.example.com", "api.example.com:8443")).isFalse();
        assertThat(NetworkHosts.covers("api.example.com", "*.example.com")).isFalse();
    }

    @Test
    void handlesFollowAdr0034() {
        assertThat(Handles.fromName("Vývojář Backend")).isEqualTo("vyvojar-backend");
        assertThat(Handles.fromName("日本")).isEqualTo("agent");
        assertThat(Handles.unique("dev", Set.of("dev", "dev-2"))).isEqualTo("dev-3");
        assertThat(Handles.requested("Dev-Ops")).isEqualTo("dev-ops");
        assertThatThrownBy(() -> Handles.requested("-dev")).isInstanceOf(ApiException.class);
    }

    private static AgentInfo info() {
        return new AgentInfo(UUID.randomUUID(), UUID.randomUUID(), "Analyst", "analyst", null, "", List.of(
                RuntimeKind.CLAUDE_CODE), AgentStatus.ACTIVE, null, 1, null);
    }
}
