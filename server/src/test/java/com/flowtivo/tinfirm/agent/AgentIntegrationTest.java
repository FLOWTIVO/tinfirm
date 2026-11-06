package com.flowtivo.tinfirm.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import com.flowtivo.tinfirm.ee.licence.EnterpriseLicence;
import com.flowtivo.tinfirm.support.ApiClient;
import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.IntegrationTest;
import com.flowtivo.tinfirm.support.JiraStub;
import com.flowtivo.tinfirm.support.McpStub;
import com.flowtivo.tinfirm.support.RecordedEvents;
import com.flowtivo.tinfirm.support.TenantSql;

import tools.jackson.databind.JsonNode;

/**
 * Agents over HTTP (the package 3 contract, §3.1 to §3.6, §5.1, §6 to §8): who sees, uses and edits an agent (its
 * owner, the members of its groups, the members it is shared with, OWNER and ADMIN, anyone else 404) on every path;
 * names and handles; the ceiling's systems with their default modes, repositories and hosts and their errors; shares
 * with groups and with members; pause, resume and delete; what a leaving owner, a leaving member and a deleted group
 * do; the audit and the events.
 */
@ExtendWith(EnterpriseLicence.class)
class AgentIntegrationTest extends IntegrationTest {

    @Test
    void anAgentIsItsOwnersUntilSharedAndAdminsSeeAndEditEveryAgent() {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        ApiClient admin = loginAs(company.email("ADMIN"));
        ApiClient alice = loginAs(company.email("MEMBER"));
        Member bob = addMember(company, "bob");
        Member carol = addMember(company, "carol");

        ApiResponse created = alice.post(company.path("/agents"), Map.of("name", "Business analyst",
                "description", "Writes stories", "instructions", "Write user stories. SECRET-INSTRUCTION",
                "runtimes", List.of("CLAUDE_CODE", "CODEX")));
        assertThat(created.status()).as("%s", created).isEqualTo(201);
        JsonNode agent = created.json();
        String path = company.path("/agents/" + agent.get("id").asString());
        UUID agentId = UUID.fromString(agent.get("id").asString());
        assertThat(agent.get("handle").asString()).isEqualTo("business-analyst");
        assertThat(agent.get("version").asInt()).isEqualTo(1);
        assertThat(agent.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(agent.get("usableByMe").asBoolean()).isTrue();
        assertThat(agent.get("editableByMe").asBoolean()).isTrue();
        assertThat(agent.get("acceptedVersion").asInt()).isEqualTo(1);
        assertThat(agent.get("ownerPrincipalId").asString())
            .isEqualTo(memberPrincipal(company, company.userId("MEMBER")).toString());
        assertThat(agent.get("runtimes")).extracting(JsonNode::asString).containsExactly("CLAUDE_CODE", "CODEX");

        // Names and handles among the live agents.
        assertThat(bob.client().post(company.path("/agents"), Map.of("name", "BUSINESS ANALYST", "runtimes",
                List.of("PI"))).problemCode()).isEqualTo("DUPLICATE_AGENT_NAME");
        assertThat(bob.client().post(company.path("/agents"), Map.of("name", "Other", "handle", "Business-Analyst",
                "runtimes", List.of("PI"))).problemCode()).isEqualTo("DUPLICATE_AGENT_HANDLE");
        assertThat(bob.client().post(company.path("/agents"), Map.of("name", "Business analyst!", "runtimes",
                List.of("PI"))).json().get("handle").asString()).isEqualTo("business-analyst-2");
        assertThat(bob.client().post(company.path("/agents"), Map.of("name", "Twice", "runtimes",
                List.of("PI", "PI"))).problemCode()).isEqualTo("VALIDATION_FAILED");
        assertThat(bob.client().post(company.path("/agents"), Map.of("name", "None", "runtimes", List.of()))
            .status()).isEqualTo(400);

        // Private to its owner: another member gets 404 on every path, and does not list it.
        for (ApiResponse response : List.of(bob.client().get(path), bob.client().patch(path, Map.of("name", "x")),
                bob.client().delete(path), bob.client().put(path + "/shares", Map.of("groupIds", List.of())),
                bob.client().post(path + "/pause", Map.of()), bob.client().get(path + "/settings/me"),
                bob.client().post(path + "/accept", Map.of("version", 1)))) {
            assertThat(response.status()).as("%s", response).isEqualTo(404);
        }
        assertThat(names(bob.client(), company, "")).doesNotContain("Business analyst");

        // Admins and the Owner see and edit it, and use it only as its owner or through a group.
        for (ApiClient manager : List.of(admin, owner)) {
            JsonNode seen = manager.get(path).json();
            assertThat(seen.get("usableByMe").asBoolean()).isFalse();
            assertThat(seen.get("editableByMe").asBoolean()).isTrue();
            assertThat(seen.has("widening")).isFalse();
            assertThat(names(manager, company, "")).contains("Business analyst");
            assertThat(names(manager, company, "?usable=true")).doesNotContain("Business analyst");
            assertThat(manager.get(path + "/settings/me").problemCode()).isEqualTo("AGENT_NOT_USABLE");
            assertThat(manager.post(path + "/accept", Map.of("version", 1)).problemCode())
                .isEqualTo("AGENT_NOT_USABLE");
        }
        assertThat(admin.patch(path, Map.of("description", "Writes stories and acceptance criteria")).status())
            .isEqualTo(200);

        // Shared with a group: its members see and use it, and do not edit it; others still get 404.
        UUID analysts = createGroup(owner, company, unique("Analysts"), List.of(bob.userId()));
        JsonNode shared = alice.put(path + "/shares", Map.of("groupIds", List.of(analysts.toString()))).json();
        assertThat(shared.get("shares")).extracting(share -> share.get("id").asString())
            .containsExactly(analysts.toString());
        JsonNode bobsView = bob.client().get(path).json();
        assertThat(bobsView.get("usableByMe").asBoolean()).isTrue();
        assertThat(bobsView.get("editableByMe").asBoolean()).isFalse();
        assertThat(names(bob.client(), company, "?usable=true")).contains("Business analyst");
        // The list says whom it is shared with, to whoever sees it, as its page does: a group's page reads no agent.
        for (ApiClient reader : List.of(bob.client(), owner)) {
            JsonNode listed = java.util.stream.StreamSupport.stream(reader.get(company.path("/agents")).json()
                .get("items").spliterator(), false)
                .filter(item -> item.get("id").asString().equals(agentId.toString()))
                .findFirst()
                .orElseThrow();
            assertThat(listed.get("shareGroupIds")).extracting(JsonNode::asString)
                .containsExactly(analysts.toString());
        }
        assertThat(bob.client().patch(path, Map.of("name", "Mine now")).problemCode())
            .isEqualTo("AGENT_NOT_EDITABLE");
        assertThat(bob.client().delete(path).problemCode()).isEqualTo("AGENT_NOT_EDITABLE");
        assertThat(bob.client().put(path + "/systems", Map.of("systems", List.of())).problemCode())
            .isEqualTo("AGENT_NOT_EDITABLE");
        assertThat(bob.client().get(path + "/settings/me").status()).isEqualTo(200);
        assertThat(carol.client().get(path).status()).isEqualTo(404);
        assertThat(alice.put(path + "/shares", Map.of("groupIds", List.of(UUID.randomUUID().toString())))
            .problemCode()).isEqualTo("VALIDATION_FAILED");
        // At most 50 groups, as the request's schema says.
        List<String> groups = new ArrayList<>();
        for (int i = 0; i < 51; i++) {
            groups.add(UUID.randomUUID().toString());
        }
        assertThat(alice.put(path + "/shares", Map.of("groupIds", groups)).problemCode())
            .isEqualTo("VALIDATION_FAILED");

        // Audit: by ids and names, never the description or the instructions; events by ids.
        List<JsonNode> audit = auditEvents(owner, company);
        JsonNode createdEvent = audit.stream()
            .filter(event -> event.get("action").asString().equals("agent.created")
                    && event.get("targetId").asString().equals(agentId.toString()))
            .findFirst()
            .orElseThrow();
        assertThat(createdEvent.get("details").get("name").asString()).isEqualTo("Business analyst");
        assertThat(createdEvent.get("details").get("runtimes")).hasSize(2);
        assertThat(audit.toString()).doesNotContain("SECRET-INSTRUCTION").doesNotContain("Writes stories");
        JsonNode changed = auditEvents(owner, company, "agent.changed").getFirst();
        assertThat(changed.get("details").get("changed")).extracting(JsonNode::asString).containsExactly("description");
        assertThat(changed.get("actor").get("id").asString()).isEqualTo(company.userId("ADMIN").toString());
        assertThat(auditEvents(owner, company, "agent.shares_changed").getFirst().get("details").get("added"))
            .extracting(JsonNode::asString)
            .containsExactly(analysts.toString());
        assertThat(RecordedEvents.of(company.id(), AgentEvents.AgentCreated.class))
            .contains(new AgentEvents.AgentCreated(company.id(), agentId));
        assertThat(RecordedEvents.of(company.id(), AgentEvents.AgentShared.class)).anySatisfy(event -> {
            assertThat(event.agentId()).isEqualTo(agentId);
            assertThat(event.addedGroupIds()).containsExactly(analysts);
        });
        assertThat(owner.get(company.path("/audit-events/verification")).json().get("valid").asBoolean()).isTrue();
    }

    @Test
    void anAgentsTimeLimitOfATurnIsItsOwnOrTheDeployments() {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        ApiClient alice = loginAs(company.email("MEMBER"));

        // None of its own: the deployment's (TINFIRM_RUN_MAX_WALL_CLOCK, 60 minutes in the tests).
        JsonNode plain = alice.post(company.path("/agents"), Map.of("name", unique("Reviewer"), "runtimes",
                List.of("PI"))).json();
        assertThat(plain.path("turnTimeLimitMinutes").isNumber()).isFalse();
        assertThat(plain.get("defaultTurnTimeLimitMinutes").asInt()).isEqualTo(60);

        ApiResponse created = alice.post(company.path("/agents"), Map.of("name", unique("Senior developer"),
                "runtimes", List.of("PI"), "turnTimeLimitMinutes", 120));
        assertThat(created.status()).as("%s", created).isEqualTo(201);
        String path = company.path("/agents/" + created.json().get("id").asString());
        assertThat(created.json().get("turnTimeLimitMinutes").asInt()).isEqualTo(120);

        // 5 to 1440 minutes, or 0 for the deployment's.
        for (int minutes : List.of(4, -1, 1441)) {
            var refused = alice.patch(path, Map.of("turnTimeLimitMinutes", minutes));
            assertThat(refused.problemCode()).as("%s", minutes).isEqualTo("VALIDATION_FAILED");
            assertThat(refused.json().get("errors")).as("%s", minutes)
                .extracting(error -> error.get("field").asString())
                .containsExactly("turnTimeLimitMinutes");
        }
        JsonNode longer = alice.patch(path, Map.of("turnTimeLimitMinutes", 240)).json();
        assertThat(longer.get("turnTimeLimitMinutes").asInt()).isEqualTo(240);
        assertThat(longer.get("version").asInt()).isEqualTo(2);
        // An update that names it not keeps it.
        assertThat(alice.patch(path, Map.of("description", "Speeds up tests")).json().get("turnTimeLimitMinutes")
            .asInt()).isEqualTo(240);
        assertThat(alice.patch(path, Map.of("turnTimeLimitMinutes", 0)).json().path("turnTimeLimitMinutes")
            .isNumber()).isFalse();

        List<JsonNode> changes = auditEvents(owner, company, "agent.changed");
        assertThat(changes).anySatisfy(change -> {
            assertThat(change.get("details").get("changed")).extracting(JsonNode::asString)
                .containsExactly("turnTimeLimitMinutes");
            JsonNode minutes = change.get("details").path("turnTimeLimitMinutes");
            assertThat(minutes.isNumber()).isTrue();
            assertThat(minutes.asInt()).isEqualTo(240);
        });
    }

    @Test
    void theCeilingListsCompanySystemsWithDefaultModesTheirRepositoriesAndHosts() throws SQLException {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        ApiClient admin = loginAs(company.email("ADMIN"));
        ApiClient alice = loginAs(company.email("MEMBER"));
        UUID github = createSystem(owner, company, unique("GitHub"), "GITHUB", "https://api.github.com", false);
        UUID repository = addRepository(owner, company, github, "https://github.com/acme/" + unique("shop"));
        UUID git = createSystem(owner, company, unique("Git"), "GIT", null, false);
        UUID gitRepository = addRepository(owner, company, git, "https://git.example.test/acme/" + unique("lib"));
        UUID jira = createSystem(owner, company, unique("Jira"), "JIRA_DC", JiraStub.newInstance().baseUrl(), false);
        McpStub.Instance crm = McpStub.newInstance().serves(List.of(McpStub.tool("search", "Searches"),
                McpStub.tool("delete_contact", "Deletes a contact")));
        UUID mcp = createSystem(owner, company, unique("CRM"), "MCP", crm.url(), false);
        assertThat(admin.post(company.path("/systems/" + mcp + "/tools/refresh"), Map.of()).status()).isEqualTo(200);
        assertThat(admin.patch(company.path("/systems/" + mcp + "/tools/search"), Map.of("effect", "READ"))
            .status()).isEqualTo(200);
        UUID personal = createSystem(alice, company, unique("Notes"), "MCP", McpStub.newInstance().url(), true);
        UUID adminsPersonal = createSystem(admin, company, unique("Admin notes"), "MCP", McpStub.newInstance().url(),
                true);

        UUID agentId = createAgent(alice, company, "Developer");
        String path = company.path("/agents/" + agentId);

        // A system without tools named gets every tool it serves: reads ALLOW, writes and unconfirmed tools ASK.
        JsonNode agent = alice.put(path + "/systems", Map.of("systems", List.of(Map.of("systemId", github.toString()),
                Map.of("systemId", mcp.toString()), Map.of("systemId", jira.toString(), "tools",
                        List.of(Map.of("name", "get", "mode", "ALLOW")))))).json();
        assertThat(agent.get("version").asInt()).isEqualTo(2);
        assertThat(tools(agent, github)).containsExactlyInAnyOrder("get:ALLOW:READ", "search:ALLOW:READ",
                "comment:ASK:WRITE", "label:ASK:WRITE", "close:ASK:WRITE", "open_change_request:ASK:WRITE");
        assertThat(tools(agent, mcp)).containsExactlyInAnyOrder("search:ALLOW:READ", "delete_contact:ASK:WRITE");
        assertThat(tools(agent, jira)).containsExactly("get:ALLOW:READ");

        // Errors: a personal server (one's own or, unknown, another's), a tool its system does not serve, a chat.
        assertThat(alice.put(path + "/systems", Map.of("systems", List.of(Map.of("systemId", personal.toString()))))
            .problemCode()).isEqualTo("PERSONAL_SYSTEM");
        assertThat(alice.put(path + "/systems", Map.of("systems",
                List.of(Map.of("systemId", adminsPersonal.toString())))).problemCode()).isEqualTo("VALIDATION_FAILED");
        ApiResponse unknownTool = alice.put(path + "/systems", Map.of("systems", List.of(Map.of("systemId",
                jira.toString(), "tools", List.of(Map.of("name", "label", "mode", "ALLOW"))))));
        assertThat(unknownTool.problemCode()).isEqualTo("UNKNOWN_TOOL");
        // The details name the system and the tool, for the editor to point at.
        assertThat(unknownTool.json().get("details").get("systemId").asString()).isEqualTo(jira.toString());
        assertThat(unknownTool.json().get("details").get("tool").asString()).isEqualTo("label");
        assertThat(alice.put(path + "/systems", Map.of("systems", List.of(Map.of("systemId", jira.toString()),
                Map.of("systemId", jira.toString())))).problemCode()).isEqualTo("VALIDATION_FAILED");

        // Repositories only of a code host the agent lists.
        ApiResponse notOnAgent = alice.put(path + "/repositories", Map.of("repositories", List.of(Map.of(
                "repositoryId", gitRepository.toString(), "access", "READ"))));
        assertThat(notOnAgent.problemCode()).isEqualTo("SYSTEM_NOT_ON_AGENT");
        assertThat(notOnAgent.json().get("details").get("repositoryId").asString())
            .isEqualTo(gitRepository.toString());
        agent = alice.put(path + "/repositories", Map.of("repositories", List.of(Map.of("repositoryId",
                repository.toString(), "access", "WRITE")))).json();
        assertThat(agent.get("version").asInt()).isEqualTo(3);
        assertThat(agent.get("repositories").get(0).get("access").asString()).isEqualTo("WRITE");
        assertThat(agent.get("repositories").get(0).get("url").asString()).startsWith("https://github.com/acme/");

        // Hosts: normalized, once each; a wildcard never over a public suffix; at most 100, as the request's schema
        // says (an approval's 101st is NETWORK_HOST_LIMIT, AgentVersionsTest).
        agent = alice.put(path + "/hosts", Map.of("hosts", List.of(Map.of("host", "Registry.NPMjs.org", "reason",
                "npm install"), Map.of("host", "registry.npmjs.org:443"), Map.of("host", "*.example.com")))).json();
        assertThat(agent.get("hosts")).extracting(host -> host.get("host").asString())
            .containsExactly("*.example.com", "registry.npmjs.org");
        assertThat(alice.put(path + "/hosts", Map.of("hosts", List.of(Map.of("host", "*.github.io"))))
            .problemCode()).isEqualTo("VALIDATION_FAILED");
        List<Map<String, Object>> many = new ArrayList<>();
        for (int i = 0; i < 101; i++) {
            many.add(Map.of("host", "host" + i + ".example.org"));
        }
        assertThat(alice.put(path + "/hosts", Map.of("hosts", many)).problemCode()).isEqualTo("VALIDATION_FAILED");
        assertThat(alice.get(path).json().get("version").asInt()).isEqualTo(4);
        // The same request again changes nothing: no version, no audit.
        int hostChanges = auditEvents(owner, company, "agent.hosts_changed").size();
        alice.put(path + "/hosts", Map.of("hosts", List.of(Map.of("host", "registry.npmjs.org", "reason",
                "npm install"), Map.of("host", "*.example.com"))));
        assertThat(alice.get(path).json().get("version").asInt()).isEqualTo(4);
        assertThat(auditEvents(owner, company, "agent.hosts_changed")).hasSize(hostChanges);

        // An admin switches an MCP tool off: the agent keeps its row, shown as no longer offered.
        assertThat(admin.patch(company.path("/systems/" + mcp + "/tools/delete_contact"), Map.of("enabled", false))
            .status()).isEqualTo(200);
        JsonNode offered = alice.get(path).json();
        assertThat(toolsOffered(offered, mcp)).containsEntry("delete_contact", false).containsEntry("search", true);
        // Kept unchanged, it stays; named anew with another mode, it is refused.
        assertThat(alice.put(path + "/systems", Map.of("systems", List.of(Map.of("systemId", github.toString()),
                Map.of("systemId", mcp.toString(), "tools", List.of(Map.of("name", "delete_contact", "mode",
                        "ALLOW"))), Map.of("systemId", jira.toString())))).problemCode()).isEqualTo("UNKNOWN_TOOL");

        // Removing GitHub takes its tools and repository off the agent.
        agent = alice.put(path + "/systems", Map.of("systems", List.of(Map.of("systemId", mcp.toString()),
                Map.of("systemId", jira.toString())))).json();
        assertThat(agent.get("version").asInt()).isEqualTo(5);
        assertThat(agent.get("repositories")).isEmpty();
        assertThat(agent.get("systems")).hasSize(2);

        // Every change is a version; the audit holds the whole list after each change.
        try (TenantSql sql = TenantSql.in(company.id())) {
            assertThat(sql.column("SELECT widens FROM agent_version WHERE agent_id = ? ORDER BY version", agentId))
                .containsExactly(false, true, true, true, false);
        }
        JsonNode tools = auditEvents(owner, company, "agent.tools_changed").getFirst().get("details");
        assertThat(tools.get("version").asInt()).isEqualTo(5);
        assertThat(tools.get("widens").asBoolean()).isFalse();
        assertThat(tools.get("systems")).hasSize(2);
        assertThat(auditEvents(owner, company, "agent.widened")).hasSize(3);
        assertThat(RecordedEvents.of(company.id(), AgentEvents.AgentChanged.class))
            .filteredOn(event -> event.agentId().equals(agentId))
            .extracting(AgentEvents.AgentChanged::version)
            .containsExactly(2, 3, 4, 5);
        assertThat(RecordedEvents.of(company.id(), AgentEvents.AgentWidened.class))
            .filteredOn(event -> event.agentId().equals(agentId))
            .extracting(AgentEvents.AgentWidened::version)
            .containsExactly(2, 3, 4);
    }

    @Test
    void anAgentIsPausedResumedAndDeleted() throws SQLException {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        ApiClient alice = loginAs(company.email("MEMBER"));
        UUID agentId = createAgent(alice, company, "Reviewer");
        String path = company.path("/agents/" + agentId);
        assertThat(alice.put(path + "/settings/me", Map.of("instructions", "Be brief")).status()).isEqualTo(200);

        JsonNode paused = alice.post(path + "/pause", Map.of("stopRunning", true)).json();
        assertThat(paused.get("status").asString()).isEqualTo("PAUSED");
        assertThat(paused.has("pausedAt")).isTrue();
        assertThat(alice.post(path + "/pause", null).json().get("status").asString()).isEqualTo("PAUSED");
        List<JsonNode> pauses = auditEvents(owner, company, "agent.paused");
        assertThat(pauses).hasSize(1);
        assertThat(pauses.getFirst().get("details").get("stopRunning").asBoolean()).isTrue();
        assertThat(pauses.getFirst().get("details").get("runsCancelled")).isEmpty();
        assertThat(RecordedEvents.of(company.id(), AgentEvents.AgentPaused.class))
            .containsExactly(new AgentEvents.AgentPaused(company.id(), agentId, true));
        assertThat(owner.post(path + "/resume", null).json().get("status").asString()).isEqualTo("ACTIVE");
        assertThat(auditEvents(owner, company, "agent.resumed")).hasSize(1);

        assertThat(alice.delete(path).status()).isEqualTo(204);
        assertThat(alice.get(path).status()).isEqualTo(404);
        assertThat(alice.get(path + "/settings/me").status()).isEqualTo(404);
        assertThat(names(alice, company, "")).doesNotContain("Reviewer");
        // Its name and handle are free again; its row stays for its conversations' history.
        assertThat(alice.post(company.path("/agents"), Map.of("name", "Reviewer", "runtimes", List.of("CODEX")))
            .json().get("handle").asString()).isEqualTo("reviewer");
        try (TenantSql sql = TenantSql.in(company.id())) {
            assertThat(sql.count("SELECT count(*) FROM agent WHERE id = ? AND deleted_at IS NOT NULL", agentId))
                .isEqualTo(1);
            assertThat(sql.count("SELECT count(*) FROM agent_settings WHERE agent_id = ?", agentId)).isZero();
        }
        assertThat(auditEvents(owner, company, "agent.deleted").getFirst().get("details").get("name").asString())
            .isEqualTo("Reviewer");
        assertThat(RecordedEvents.of(company.id(), AgentEvents.AgentDeleted.class))
            .containsExactly(new AgentEvents.AgentDeleted(company.id(), agentId));
    }

    @Test
    void aLeavingOwnersAgentsPassToTheAdminsAndADeletedGroupUsesNoAgent() throws SQLException {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        ApiClient admin = loginAs(company.email("ADMIN"));
        Member dana = addMember(company, "dana");
        UUID agentId = createAgent(dana.client(), company, "Tester");
        String path = company.path("/agents/" + agentId);
        UUID group = createGroup(owner, company, unique("Testers"), List.of(dana.userId()));
        assertThat(dana.client().put(path + "/shares", Map.of("groupIds", List.of(group.toString()))).status())
            .isEqualTo(200);
        assertThat(dana.client().put(path + "/settings/me", Map.of("effort", "HIGH")).status()).isEqualTo(200);
        UUID danaPrincipal = memberPrincipal(company, dana.userId());

        // A deleted group uses no agent any longer, audited with the cause.
        assertThat(owner.delete(company.path("/groups/" + group)).status()).isEqualTo(204);
        assertThat(admin.get(path).json().get("shares")).isEmpty();
        JsonNode shares = auditEvents(owner, company, "agent.shares_changed").getFirst().get("details");
        assertThat(shares.get("cause").asString()).isEqualTo("group.deleted");
        assertThat(shares.get("removed")).extracting(JsonNode::asString).containsExactly(group.toString());

        // The owner leaves: the agent passes to the admins, and their settings go.
        assertThat(owner.delete(company.path("/members/" + dana.userId())).status()).isEqualTo(204);
        JsonNode passed = admin.get(path).json();
        assertThat(passed.has("ownerPrincipalId")).isFalse();
        assertThat(passed.get("editableByMe").asBoolean()).isTrue();
        assertThat(admin.patch(path, Map.of("name", "Tester (team)")).status()).isEqualTo(200);
        JsonNode audit = auditEvents(owner, company, "agent.passed_to_admins").getFirst();
        assertThat(audit.get("details").get("formerOwnerPrincipalId").asString()).isEqualTo(danaPrincipal.toString());
        assertThat(RecordedEvents.of(company.id(), AgentEvents.AgentPassedToAdmins.class))
            .containsExactly(new AgentEvents.AgentPassedToAdmins(company.id(), agentId, danaPrincipal));
        try (TenantSql sql = TenantSql.in(company.id())) {
            assertThat(sql.count("SELECT count(*) FROM agent_settings WHERE principal_id = ?", danaPrincipal))
                .isZero();
        }
    }

    @Test
    void anAgentIsSharedWithAMemberInNoGroupUntilTheyLeave() throws SQLException {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        ApiClient admin = loginAs(company.email("ADMIN"));
        ApiClient alice = loginAs(company.email("MEMBER"));
        Member bob = addMember(company, "bob");
        Member carol = addMember(company, "carol");
        UUID bobPrincipal = memberPrincipal(company, bob.userId());
        UUID agentId = createAgent(alice, company, "Analyst");
        String path = company.path("/agents/" + agentId);

        // Bob is in no group: shared with him on his own, he sees and uses it, and does not edit it.
        ApiResponse shared = alice.put(path + "/shares", Map.of("groupIds", List.of(),
                "principalIds", List.of(bobPrincipal.toString(), bobPrincipal.toString())));
        assertThat(shared.status()).as("%s", shared).isEqualTo(200);
        assertThat(shared.json().get("shares")).isEmpty();
        assertThat(shared.json().get("memberShares")).singleElement().satisfies(member -> {
            assertThat(member.get("principalId").asString()).isEqualTo(bobPrincipal.toString());
            assertThat(member.get("userId").asString()).isEqualTo(bob.userId().toString());
            assertThat(member.get("name").asString()).isNotBlank();
        });
        JsonNode bobsView = bob.client().get(path).json();
        assertThat(bobsView.get("usableByMe").asBoolean()).isTrue();
        assertThat(bobsView.get("editableByMe").asBoolean()).isFalse();
        assertThat(bobsView.get("acceptedVersion").asInt()).isEqualTo(1);
        assertThat(names(bob.client(), company, "?usable=true")).contains("Analyst");
        assertThat(bob.client().get(path + "/settings/me").status()).isEqualTo(200);
        assertThat(bob.client().patch(path, Map.of("name", "Mine now")).problemCode())
            .isEqualTo("AGENT_NOT_EDITABLE");
        assertThat(bob.client().put(path + "/shares", Map.of("groupIds", List.of())).problemCode())
            .isEqualTo("AGENT_NOT_EDITABLE");
        assertThat(carol.client().get(path).status()).isEqualTo(404);
        assertThat(names(carol.client(), company, "?usable=true")).doesNotContain("Analyst");
        JsonNode listed = java.util.stream.StreamSupport.stream(owner.get(company.path("/agents")).json()
            .get("items").spliterator(), false)
            .filter(item -> item.get("id").asString().equals(agentId.toString()))
            .findFirst()
            .orElseThrow();
        assertThat(listed.get("sharePrincipalIds")).extracting(JsonNode::asString)
            .containsExactly(bobPrincipal.toString());

        // A request without principalIds keeps the members; a group joins beside them.
        UUID analysts = createGroup(owner, company, unique("Analysts"), List.of(carol.userId()));
        JsonNode both = alice.put(path + "/shares", Map.of("groupIds", List.of(analysts.toString()))).json();
        assertThat(both.get("shares")).extracting(share -> share.get("id").asString())
            .containsExactly(analysts.toString());
        assertThat(both.get("memberShares")).extracting(member -> member.get("principalId").asString())
            .containsExactly(bobPrincipal.toString());
        assertThat(carol.client().get(path).json().get("usableByMe").asBoolean()).isTrue();

        // Only a current member's principal: not an unknown one, not a service identity's; at most 100.
        UUID robot = createServiceIdentity(owner, company, unique("Robot"));
        for (UUID wrong : List.of(UUID.randomUUID(), robot)) {
            assertThat(alice.put(path + "/shares", Map.of("groupIds", List.of(), "principalIds",
                    List.of(wrong.toString()))).problemCode()).as("%s", wrong).isEqualTo("VALIDATION_FAILED");
        }
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 101; i++) {
            many.add(UUID.randomUUID().toString());
        }
        assertThat(alice.put(path + "/shares", Map.of("groupIds", List.of(), "principalIds", many)).problemCode())
            .isEqualTo("VALIDATION_FAILED");

        // Audited with the members after the change and those added; announced with them.
        JsonNode added = auditEvents(owner, company, "agent.shares_changed").stream()
            .filter(event -> event.get("targetId").asString().equals(agentId.toString()))
            .map(event -> event.get("details"))
            .filter(details -> details.get("principalsAdded").size() > 0)
            .findFirst()
            .orElseThrow();
        assertThat(added.get("principalIds")).extracting(JsonNode::asString).containsExactly(bobPrincipal.toString());
        assertThat(added.get("principalsAdded")).extracting(JsonNode::asString)
            .containsExactly(bobPrincipal.toString());
        assertThat(RecordedEvents.of(company.id(), AgentEvents.AgentShared.class)).anySatisfy(event -> {
            assertThat(event.agentId()).isEqualTo(agentId);
            assertThat(event.addedPrincipalIds()).containsExactly(bobPrincipal);
        });

        // A new agent may be shared with members as it is created.
        ApiResponse created = alice.post(company.path("/agents"), Map.of("name", "Writer", "runtimes",
                List.of("CLAUDE_CODE"), "sharePrincipalIds", List.of(bobPrincipal.toString())));
        assertThat(created.status()).as("%s", created).isEqualTo(201);
        String writer = company.path("/agents/" + created.json().get("id").asString());
        assertThat(bob.client().get(writer).json().get("usableByMe").asBoolean()).isTrue();

        // Bob leaves: no agent is shared with him any longer, audited with the cause.
        assertThat(owner.delete(company.path("/members/" + bob.userId())).status()).isEqualTo(204);
        assertThat(admin.get(path).json().get("memberShares")).isEmpty();
        assertThat(admin.get(writer).json().get("memberShares")).isEmpty();
        JsonNode left = auditEvents(owner, company, "agent.shares_changed").stream()
            .map(event -> event.get("details"))
            .filter(details -> details.has("cause") && details.get("cause").asString().equals("principal.left"))
            .findFirst()
            .orElseThrow();
        assertThat(left.get("principalsRemoved")).extracting(JsonNode::asString)
            .containsExactly(bobPrincipal.toString());
        try (TenantSql sql = TenantSql.in(company.id())) {
            assertThat(sql.count("SELECT count(*) FROM agent_member_share WHERE principal_id = ?", bobPrincipal))
                .isZero();
        }

        // Taken from a member again: an empty list.
        UUID carolPrincipal = memberPrincipal(company, carol.userId());
        assertThat(alice.put(path + "/shares", Map.of("groupIds", List.of(), "principalIds",
                List.of(carolPrincipal.toString()))).status()).isEqualTo(200);
        assertThat(alice.put(path + "/shares", Map.of("groupIds", List.of(), "principalIds", List.of())).json()
            .get("memberShares")).isEmpty();
        assertThat(carol.client().get(path).status()).isEqualTo(404);
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    /** A member added to the company, logged in. */
    record Member(UUID userId, ApiClient client) {
    }

    private Member addMember(TestCompany company, String name) {
        String email = uniqueEmail(name);
        UUID userId = createUser(admin(), email, false);
        addMember(loginAs(company.email("OWNER")), company.id(), email, "MEMBER");
        return new Member(userId, loginAs(email));
    }

    @org.springframework.beans.factory.annotation.Autowired
    Settings settings;

    @Test
    void aPersonAndATriggersAuthorKeepTheInstructionsTheyAcceptedWhereTheAgentCanAct() throws SQLException {
        // D1 (the security review of 2026-10-27; the owner's decision Q4): the agent reaches a network host, so a
        // change of its instructions widens. A person and a trigger's author who accepted the version before keep
        // its instructions until they accept; the editor's own turns follow their change.
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        ApiClient alice = loginAs(company.email("MEMBER"));
        UUID alicePrincipal = memberPrincipal(company, company.userId("MEMBER"));
        UUID ownerPrincipal = memberPrincipal(company, company.userId("OWNER"));
        ApiResponse created = owner.post(company.path("/agents"), Map.of("name", unique("Writer"), "runtimes",
                List.of("CLAUDE_CODE"), "instructions", "Answer in English.", "hosts",
                List.of(Map.of("host", "api.example.com")), "sharePrincipalIds", List.of(alicePrincipal.toString())));
        assertThat(created.status()).as("%s", created).isEqualTo(201);
        UUID agentId = UUID.fromString(created.json().get("id").asString());
        String path = company.path("/agents/" + agentId);
        com.flowtivo.tinfirm.shared.security.SystemContext.callInCompany(company.id(),
                () -> settings.firstUse(company.id(), agentId, alicePrincipal));
        UUID triggerId = com.flowtivo.tinfirm.support.TestTriggers.schedule(company.id(), agentId, alicePrincipal,
                alicePrincipal);

        assertThat(owner.patch(path, Map.of("instructions", "Answer in German.")).json().get("version").asInt())
            .isEqualTo(2);
        for (SettingsOwner who : List.of(new SettingsOwner.Person(alicePrincipal),
                new SettingsOwner.Trigger(triggerId))) {
            EffectiveSettings effective = effective(company, agentId, who);
            assertThat(effective.agent().acceptedVersion()).as("%s", who).isEqualTo(1);
            assertThat(effective.agent().instructions()).as("%s", who).isEqualTo("Answer in English.");
            assertThat(effective.agent().pending().instructions()).as("%s", who).isTrue();
        }
        JsonNode instructions = alice.get(path).json().get("widening").get("instructions");
        assertThat(instructions.get("accepted").asString()).isEqualTo("Answer in English.");
        assertThat(instructions.get("current").asString()).isEqualTo("Answer in German.");
        assertThat(effective(company, agentId, new SettingsOwner.Person(ownerPrincipal)).agent().instructions())
            .isEqualTo("Answer in German.");
        JsonNode widened = auditEvents(owner, company, "agent.widened").getFirst().get("details");
        assertThat(widened.get("version").asInt()).isEqualTo(2);
        assertThat(widened.get("instructions").asString()).isEqualTo("changed");
        assertThat(auditEvents(owner, company, "agent.instructions_changed")).isEmpty();

        // Accepted, each uses them.
        assertThat(alice.post(path + "/accept", Map.of("version", 2)).status()).isEqualTo(200);
        try (TenantSql sql = TenantSql.in(company.id())) {
            sql.update("UPDATE agent_settings SET accepted_version = 2 WHERE trigger_id = ?", triggerId);
            sql.commit();
        }
        for (SettingsOwner who : List.of(new SettingsOwner.Person(alicePrincipal),
                new SettingsOwner.Trigger(triggerId))) {
            EffectiveSettings effective = effective(company, agentId, who);
            assertThat(effective.agent().instructions()).as("%s", who).isEqualTo("Answer in German.");
            assertThat(effective.agent().wideningPending()).as("%s", who).isFalse();
        }
    }

    private EffectiveSettings effective(TestCompany company, UUID agentId, SettingsOwner who) {
        return com.flowtivo.tinfirm.shared.security.SystemContext.callInCompany(company.id(),
                () -> settings.effective(company.id(), agentId, who));
    }

    static UUID createAgent(ApiClient actor, TestCompany company, String name) {
        ApiResponse response = actor.post(company.path("/agents"), Map.of("name", name, "runtimes",
                List.of("CLAUDE_CODE", "CODEX")));
        assertThat(response.status()).as("agent: %s", response).isEqualTo(201);
        return UUID.fromString(response.json().get("id").asString());
    }

    static UUID addRepository(ApiClient admin, TestCompany company, UUID systemId, String url) {
        ApiResponse response = admin.post(company.path("/systems/" + systemId + "/repositories"), Map.of("url", url));
        assertThat(response.status()).as("repository: %s", response).isEqualTo(201);
        return UUID.fromString(response.json().get("id").asString());
    }

    @Test
    void aRunsToolThatBecameAWriteIsConfirmedForWritesOnlyWhenItsOwnerSendsTheEffect() throws SQLException {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        ApiClient admin = loginAs(company.email("ADMIN"));
        ApiClient alice = loginAs(company.email("MEMBER"));
        McpStub.Instance crm = McpStub.newInstance().serves(List.of(McpStub.tool("search", "Searches"),
                McpStub.tool("lookup", "Looks a contact up")));
        UUID mcp = createSystem(owner, company, unique("CRM"), "MCP", crm.url(), false);
        String tools = company.path("/systems/" + mcp + "/tools/");
        assertThat(admin.post(company.path("/systems/" + mcp + "/tools/refresh"), Map.of()).status()).isEqualTo(200);
        assertThat(admin.patch(tools + "search", Map.of("effect", "READ")).status()).isEqualTo(200);
        assertThat(admin.patch(tools + "lookup", Map.of("effect", "READ")).status()).isEqualTo(200);
        UUID agentId = createAgent(alice, company, "Researcher");
        String path = company.path("/agents/" + agentId);
        assertThat(alice.put(path + "/systems", systems(mcp, tool("search", "ALLOW", null), tool("lookup", "ASK",
                null))).status()).isEqualTo(200);

        // Both turn out to write: "Allowed", chosen while search read, counts as "Needs approval" now (I19).
        assertThat(admin.patch(tools + "search", Map.of("effect", "WRITE")).status()).isEqualTo(200);
        assertThat(admin.patch(tools + "lookup", Map.of("effect", "WRITE")).status()).isEqualTo(200);
        JsonNode agent = alice.get(path).json();
        int version = agent.get("version").asInt();
        assertThat(tools(agent, mcp)).containsExactlyInAnyOrder("search:ALLOW:READ", "lookup:ASK:READ");

        // The same modes again, as a save of another part sends every mode, confirm nothing: no version.
        agent = alice.put(path + "/systems", systems(mcp, tool("search", "ALLOW", null), tool("lookup", "ASK",
                null))).json();
        assertThat(agent.get("version").asInt()).isEqualTo(version);
        assertThat(tools(agent, mcp)).contains("search:ALLOW:READ");

        // The owner chooses "Allowed" for the write it is now: one save, a widening version.
        int widened = auditEvents(owner, company, "agent.widened").size();
        agent = alice.put(path + "/systems", systems(mcp, tool("search", "ALLOW", "WRITE"), tool("lookup", "ASK",
                null))).json();
        assertThat(agent.get("version").asInt()).isEqualTo(version + 1);
        assertThat(tools(agent, mcp)).containsExactlyInAnyOrder("search:ALLOW:WRITE", "lookup:ASK:READ");
        assertThat(auditEvents(owner, company, "agent.widened")).hasSize(widened + 1);
        try (TenantSql sql = TenantSql.in(company.id())) {
            assertThat(sql.column("SELECT widens FROM agent_version WHERE agent_id = ? AND version = ?", agentId,
                    version + 1)).containsExactly(true);
        }

        // A choice made while the tool read never covers a write: "Allowed" sent with READ, as a page shown before
        // the change would, is kept for reads, and so keeps asking.
        agent = alice.put(path + "/systems", systems(mcp, tool("search", "ALLOW", null), tool("lookup", "ALLOW",
                "READ"))).json();
        assertThat(tools(agent, mcp)).containsExactlyInAnyOrder("search:ALLOW:WRITE", "lookup:ALLOW:READ");
    }

    @SafeVarargs
    private static Map<String, Object> systems(UUID systemId, Map<String, Object>... tools) {
        return Map.of("systems", List.of(Map.of("systemId", systemId.toString(), "tools", List.of(tools))));
    }

    private static Map<String, Object> tool(String name, String mode, String chosenForEffect) {
        Map<String, Object> tool = new HashMap<>();
        tool.put("name", name);
        tool.put("mode", mode);
        if (chosenForEffect != null) {
            tool.put("chosenForEffect", chosenForEffect);
        }
        return tool;
    }

    private static List<String> names(ApiClient client, TestCompany company, String query) {
        ApiResponse response = client.get(company.path("/agents" + query));
        assertThat(response.status()).as("%s", response).isEqualTo(200);
        List<String> names = new ArrayList<>();
        response.json().get("items").forEach(item -> names.add(item.get("name").asString()));
        return names;
    }

    /** The agent's tools of a system as {@code name:mode:chosenForEffect}. */
    static List<String> tools(JsonNode agent, UUID systemId) {
        List<String> tools = new ArrayList<>();
        for (JsonNode system : agent.get("systems")) {
            if (system.get("systemId").asString().equals(systemId.toString())) {
                system.get("tools").forEach(tool -> tools.add(tool.get("name").asString() + ":"
                        + tool.get("mode").asString() + ":" + tool.get("chosenForEffect").asString()));
            }
        }
        return tools;
    }

    private static Map<String, Boolean> toolsOffered(JsonNode agent, UUID systemId) {
        Map<String, Boolean> offered = new HashMap<>();
        for (JsonNode system : agent.get("systems")) {
            if (system.get("systemId").asString().equals(systemId.toString())) {
                system.get("tools").forEach(tool -> offered.put(tool.get("name").asString(),
                        tool.get("offered").asBoolean()));
            }
        }
        return offered;
    }
}
