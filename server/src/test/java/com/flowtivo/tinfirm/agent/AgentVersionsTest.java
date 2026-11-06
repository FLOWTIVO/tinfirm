package com.flowtivo.tinfirm.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.flowtivo.tinfirm.shared.security.SystemContext;
import com.flowtivo.tinfirm.shared.web.ApiException;
import com.flowtivo.tinfirm.shared.web.ProblemCode;
import com.flowtivo.tinfirm.support.ApiClient;
import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.IntegrationTest;
import com.flowtivo.tinfirm.support.RecordedEvents;
import com.flowtivo.tinfirm.system.ToolEffect;

import tools.jackson.databind.JsonNode;

/**
 * Widening needs acceptance (the package 3 contract, §11; the security review of 2026-10-12, row 11): every change of
 * an agent is a version; narrowing reaches everyone's next turn at once, widening only the turns of a person who
 * accepted it. The contract's test: an agent that adds an {@code ALLOW} write tool lists it for a person only after
 * they accept, and removing a tool removes it at once. Also what widened, as the app shows it, and the editor's own
 * settings; and a change of the instructions or skills, which widens only where the ceiling can act (the security
 * review of 2026-10-27, D1; the owner's decision Q4).
 */
class AgentVersionsTest extends IntegrationTest {

    @Autowired
    Settings settings;

    @Autowired
    Agents agents;

    @Test
    void anAllowWriteToolReachesAPersonOnlyOnceTheyAcceptItAndARemovedToolGoesAtOnce() {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        ApiClient alice = loginAs(company.email("MEMBER"));
        ApiClient bob = loginAs(company.email("ADMIN"));
        UUID github = createSystem(owner, company, unique("GitHub"), "GITHUB", "https://api.github.com", false);
        UUID agentId = AgentIntegrationTest.createAgent(alice, company, "Developer");
        String path = company.path("/agents/" + agentId);
        UUID group = createGroup(owner, company, unique("Developers"), List.of(company.userId("ADMIN")));
        assertThat(alice.put(path + "/shares", Map.of("groupIds", List.of(group.toString()))).status())
            .isEqualTo(200);
        // Version 2: GitHub's reads.
        assertThat(alice.put(path + "/systems", Map.of("systems", List.of(Map.of("systemId", github.toString(),
                "tools", List.of(Map.of("name", "get", "mode", "ALLOW"), Map.of("name", "search", "mode",
                        "ALLOW"))))))
            .json()
            .get("version")
            .asInt()).isEqualTo(2);
        UUID alicePrincipal = memberPrincipal(company, company.userId("MEMBER"));
        UUID bobPrincipal = memberPrincipal(company, company.userId("ADMIN"));
        // Both use it: Bob's first conversation, Alice's first save.
        EffectiveSettings first = firstUse(company, agentId, bobPrincipal);
        assertThat(first.agent().acceptedVersion()).isEqualTo(2);
        assertThat(first.stored()).isTrue();
        assertThat(alice.put(path + "/settings/me", Map.of("effort", "HIGH")).json().get("acceptedVersion").asInt())
            .isEqualTo(2);

        // Version 3 widens: label, a write, ALLOW.
        JsonNode v3 = alice.put(path + "/systems", Map.of("systems", List.of(Map.of("systemId", github.toString(),
                "tools", List.of(Map.of("name", "get", "mode", "ALLOW"), Map.of("name", "search", "mode", "ALLOW"),
                        Map.of("name", "label", "mode", "ALLOW"))))))
            .json();
        assertThat(v3.get("version").asInt()).isEqualTo(3);
        // The editor made it, and had accepted the version before: their own settings follow.
        assertThat(v3.get("acceptedVersion").asInt()).isEqualTo(3);
        assertThat(v3.get("wideningPending").asBoolean()).isFalse();
        assertThat(modeOf(company, agentId, alicePrincipal, github, "label")).isEqualTo(
                EffectiveSettings.ToolDecision.ALLOW);

        // Bob's turns do not list it until he accepts; the app shows him what widened.
        assertThat(modeOf(company, agentId, bobPrincipal, github, "label"))
            .isEqualTo(EffectiveSettings.ToolDecision.NOT_ON_AGENT);
        EffectiveSettings bobs = effective(company, agentId, bobPrincipal);
        assertThat(bobs.tools(github)).extracting(AgentDefinition.ToolEntry::name).containsExactly("get", "search");
        assertThat(bobs.agent().wideningPending()).isTrue();
        JsonNode bobsView = bob.get(path).json();
        assertThat(bobsView.get("acceptedVersion").asInt()).isEqualTo(2);
        assertThat(bobsView.get("wideningPending").asBoolean()).isTrue();
        JsonNode widening = bobsView.get("widening");
        assertThat(widening.get("acceptedVersion").asInt()).isEqualTo(2);
        assertThat(widening.get("currentVersion").asInt()).isEqualTo(3);
        assertThat(widening.get("tools")).hasSize(1);
        assertThat(widening.get("tools").get(0).get("tool").asString()).isEqualTo("label");
        assertThat(widening.get("tools").get(0).get("mode").asString()).isEqualTo("ALLOW");
        assertThat(widening.get("tools").get(0).has("was")).isFalse();
        JsonNode listed = bob.get(company.path("/agents?usable=true")).json().get("items");
        assertThat(listed).anySatisfy(item -> {
            assertThat(item.get("id").asString()).isEqualTo(agentId.toString());
            assertThat(item.get("wideningPending").asBoolean()).isTrue();
        });
        assertThat(bob.get(path + "/settings/me").json().get("widening").get("tools")).hasSize(1);

        // Version 4 narrows: search goes, for everyone at once, Bob included.
        assertThat(alice.put(path + "/systems", Map.of("systems", List.of(Map.of("systemId", github.toString(),
                "tools", List.of(Map.of("name", "get", "mode", "ALLOW"), Map.of("name", "label", "mode",
                        "ALLOW")))))).json().get("version").asInt()).isEqualTo(4);
        assertThat(modeOf(company, agentId, bobPrincipal, github, "search"))
            .isEqualTo(EffectiveSettings.ToolDecision.NOT_ON_AGENT);
        assertThat(modeOf(company, agentId, bobPrincipal, github, "get"))
            .isEqualTo(EffectiveSettings.ToolDecision.ALLOW);
        assertThat(modeOf(company, agentId, bobPrincipal, github, "label"))
            .isEqualTo(EffectiveSettings.ToolDecision.NOT_ON_AGENT);

        // Accepting: only a version of the agent; up to the one the app showed.
        assertThat(bob.post(path + "/accept", Map.of("version", 5)).problemCode()).isEqualTo("VALIDATION_FAILED");
        ApiResponse accepted = bob.post(path + "/accept", Map.of("version", 4));
        assertThat(accepted.status()).as("%s", accepted).isEqualTo(200);
        assertThat(accepted.json().get("acceptedVersion").asInt()).isEqualTo(4);
        assertThat(accepted.json().has("widening")).isFalse();
        assertThat(modeOf(company, agentId, bobPrincipal, github, "label"))
            .isEqualTo(EffectiveSettings.ToolDecision.ALLOW);
        // Accepting an older version changes nothing.
        assertThat(bob.post(path + "/accept", Map.of("version", 1)).json().get("acceptedVersion").asInt())
            .isEqualTo(4);
        List<JsonNode> acceptances = auditEvents(owner, company, "agent.accepted");
        assertThat(acceptances).hasSize(1);
        assertThat(acceptances.getFirst().get("details").get("version").asInt()).isEqualTo(4);
        assertThat(acceptances.getFirst().get("details").get("previousVersion").asInt()).isEqualTo(2);
        assertThat(RecordedEvents.of(company.id(), AgentEvents.AgentAccepted.class))
            .containsExactly(new AgentEvents.AgentAccepted(company.id(), agentId, bobPrincipal, 4));
        JsonNode widened = auditEvents(owner, company, "agent.widened").getFirst().get("details");
        assertThat(widened.get("version").asInt()).isEqualTo(3);
        assertThat(widened.get("tools").get(0).get("tool").asString()).isEqualTo("label");

        // Agents.effective of an older version: the current one narrowed by it.
        EffectiveAgent asOfTwo = SystemContext.callInCompany(company.id(),
                () -> agents.effective(company.id(), agentId, 2).orElseThrow());
        assertThat(asOfTwo.definition().tool(github, "get")).isPresent();
        assertThat(asOfTwo.definition().tool(github, "search")).isEmpty();
        assertThat(asOfTwo.definition().tool(github, "label")).isEmpty();
        assertThat(asOfTwo.pending().tools()).extracting(tool -> tool.tool().name()).containsExactly("label");
    }

    @Test
    void raisingAModeOrAnAccessWidensAndLoweringNarrowsAtOnce() {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        ApiClient alice = loginAs(company.email("MEMBER"));
        ApiClient bob = loginAs(company.email("ADMIN"));
        UUID github = createSystem(owner, company, unique("GitHub"), "GITHUB", "https://api.github.com", false);
        UUID repository = AgentIntegrationTest.addRepository(owner, company, github,
                "https://github.com/acme/" + unique("shop"));
        UUID agentId = AgentIntegrationTest.createAgent(alice, company, "Developer");
        String path = company.path("/agents/" + agentId);
        UUID group = createGroup(owner, company, unique("Developers"), List.of(company.userId("ADMIN")));
        alice.put(path + "/shares", Map.of("groupIds", List.of(group.toString())));
        alice.put(path + "/systems", Map.of("systems", List.of(Map.of("systemId", github.toString(), "tools",
                List.of(Map.of("name", "get", "mode", "ALLOW"), Map.of("name", "label", "mode", "ASK"))))));
        alice.put(path + "/repositories", Map.of("repositories", List.of(Map.of("repositoryId",
                repository.toString(), "access", "READ"))));
        alice.put(path + "/hosts", Map.of("hosts", List.of(Map.of("host", "api.example.com"))));
        UUID bobPrincipal = memberPrincipal(company, company.userId("ADMIN"));
        int accepted = firstUse(company, agentId, bobPrincipal).agent().acceptedVersion();

        // Raised: label ASK to ALLOW, the repository to WRITE, a wildcard over the host. Lowered: get to ASK.
        alice.put(path + "/systems", Map.of("systems", List.of(Map.of("systemId", github.toString(), "tools",
                List.of(Map.of("name", "get", "mode", "ASK"), Map.of("name", "label", "mode", "ALLOW"))))));
        alice.put(path + "/repositories", Map.of("repositories", List.of(Map.of("repositoryId",
                repository.toString(), "access", "WRITE"))));
        alice.put(path + "/hosts", Map.of("hosts", List.of(Map.of("host", "*.example.com"))));

        EffectiveSettings bobs = effective(company, agentId, bobPrincipal);
        assertThat(bobs.agent().acceptedVersion()).isEqualTo(accepted);
        assertThat(bobs.modeOf(github, "get", ToolEffect.READ)).isEqualTo(EffectiveSettings.ToolDecision.ASK);
        assertThat(bobs.modeOf(github, "label", ToolEffect.WRITE)).isEqualTo(EffectiveSettings.ToolDecision.ASK);
        assertThat(bobs.repositories()).extracting(AgentDefinition.RepositoryEntry::access)
            .containsExactly(RepositoryAccess.READ);
        assertThat(bobs.hosts()).containsExactly("api.example.com");
        JsonNode widening = bob.get(path).json().get("widening");
        assertThat(widening.get("tools").get(0).get("was").asString()).isEqualTo("ASK");
        assertThat(widening.get("repositories").get(0).get("was").asString()).isEqualTo("READ");
        assertThat(widening.get("hosts")).extracting(JsonNode::asString).containsExactly("*.example.com");

        int current = bob.get(path).json().get("version").asInt();
        assertThat(bob.post(path + "/accept", Map.of("version", current)).status()).isEqualTo(200);
        bobs = effective(company, agentId, bobPrincipal);
        assertThat(bobs.modeOf(github, "label", ToolEffect.WRITE)).isEqualTo(EffectiveSettings.ToolDecision.ALLOW);
        assertThat(bobs.repositories()).extracting(AgentDefinition.RepositoryEntry::access)
            .containsExactly(RepositoryAccess.WRITE);
        assertThat(bobs.hosts()).containsExactly("*.example.com");

        // Settings of nobody: the current version, nothing pending.
        UUID ownerPrincipal = memberPrincipal(company, company.userId("OWNER"));
        EffectiveSettings none = effective(company, agentId, ownerPrincipal);
        assertThat(none.stored()).isFalse();
        assertThat(none.agent().acceptedVersion()).isEqualTo(current);
        assertThat(none.agent().wideningPending()).isFalse();
    }

    @Test
    void anApprovalForGoodAddsARepositoryOrAHostAsAVersionThatWidens() throws java.sql.SQLException {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        ApiClient alice = loginAs(company.email("MEMBER"));
        UUID github = createSystem(owner, company, unique("GitHub"), "GITHUB", "https://api.github.com", false);
        UUID repository = AgentIntegrationTest.addRepository(owner, company, github,
                "https://github.com/acme/" + unique("shop"));
        UUID agentId = AgentIntegrationTest.createAgent(alice, company, "Developer");
        String path = company.path("/agents/" + agentId);
        UUID bobPrincipal = memberPrincipal(company, company.userId("ADMIN"));
        UUID group = createGroup(owner, company, unique("Developers"), List.of(company.userId("ADMIN")));
        alice.put(path + "/shares", Map.of("groupIds", List.of(group.toString())));
        firstUse(company, agentId, bobPrincipal);
        UUID decision = UUID.randomUUID();

        // The repository joins with its code host, without tools; the same or a lower access again changes nothing.
        assertThat(inCompany(company, () -> agents.addRepository(company.id(), agentId, repository,
                RepositoryAccess.WRITE, decision))).isEqualTo(2);
        assertThat(inCompany(company, () -> agents.addRepository(company.id(), agentId, repository,
                RepositoryAccess.READ, decision))).isEqualTo(2);
        JsonNode agent = alice.get(path).json();
        assertThat(AgentIntegrationTest.tools(agent, github)).isEmpty();
        assertThat(agent.get("repositories").get(0).get("access").asString()).isEqualTo("WRITE");
        // A host, unless one the agent has covers it already.
        assertThat(inCompany(company, () -> agents.addHost(company.id(), agentId, "*.Example.com", "docs",
                decision))).isEqualTo(3);
        assertThat(inCompany(company, () -> agents.addHost(company.id(), agentId, "api.example.com", null,
                decision))).isEqualTo(3);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> inCompany(company,
                () -> agents.addHost(company.id(), agentId, "https://example.com/x", null, decision)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(alice.get(path).json().get("hosts").get(0).get("decisionId").asString())
            .isEqualTo(decision.toString());

        // Widening: Bob's turns do not use them until he accepts.
        EffectiveSettings bobs = effective(company, agentId, bobPrincipal);
        assertThat(bobs.repositories()).isEmpty();
        assertThat(bobs.hosts()).isEmpty();
        assertThat(bobs.agent().pending().repositories()).hasSize(1);
        assertThat(auditEvents(owner, company, "agent.repositories_changed").getFirst().get("details")
            .get("decisionId").asString()).isEqualTo(decision.toString());
        assertThat(auditEvents(owner, company, "agent.hosts_changed").getFirst().get("details").get("decisionId")
            .asString()).isEqualTo(decision.toString());
        try (com.flowtivo.tinfirm.support.TenantSql sql = com.flowtivo.tinfirm.support.TenantSql.in(company.id())) {
            assertThat(sql.column("SELECT decision_id FROM agent_version WHERE agent_id = ? AND version > 1"
                    + " ORDER BY version", agentId)).containsExactly(decision, decision);
        }

        // An agent reaches at most 100 hosts: an approval's 101st is NETWORK_HOST_LIMIT.
        List<Map<String, Object>> hundred = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            hundred.add(Map.of("host", "host" + i + ".example.org"));
        }
        assertThat(alice.put(path + "/hosts", Map.of("hosts", hundred)).status()).isEqualTo(200);
        assertThatThrownBy(() -> inCompany(company, () -> agents.addHost(company.id(), agentId,
                "host100.example.org", null, decision)))
            .isInstanceOfSatisfying(ApiException.class,
                    ex -> assertThat(ex.code()).isEqualTo(ProblemCode.NETWORK_HOST_LIMIT));
    }

    @Test
    void anInstructionOrSkillChangeWaitsForAcceptanceOnlyWhereTheCeilingCanAct() {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        ApiClient alice = loginAs(company.email("MEMBER"));
        ApiClient bob = loginAs(company.email("ADMIN"));
        UUID github = createSystem(owner, company, unique("GitHub"), "GITHUB", "https://api.github.com", false);
        UUID agentId = AgentIntegrationTest.createAgent(alice, company, "Developer");
        String path = company.path("/agents/" + agentId);
        UUID group = createGroup(owner, company, unique("Developers"), List.of(company.userId("ADMIN")));
        alice.put(path + "/shares", Map.of("groupIds", List.of(group.toString())));
        alice.put(path + "/systems", Map.of("systems", List.of(Map.of("systemId", github.toString(), "tools",
                List.of(Map.of("name", "get", "mode", "ALLOW"), Map.of("name", "label", "mode", "ASK"))))));
        alice.patch(path, Map.of("instructions", "Answer in English."));
        UUID alicePrincipal = memberPrincipal(company, company.userId("MEMBER"));
        UUID bobPrincipal = memberPrincipal(company, company.userId("ADMIN"));
        firstUse(company, agentId, bobPrincipal);

        // D1, Q4: the ceiling only reads and asks, so a change of the instructions widens nothing. It reaches Bob's
        // next turn at once, and is audited as agent.instructions_changed.
        int widenedBefore = auditEvents(owner, company, "agent.widened").size();
        int announced = alice.patch(path, Map.of("instructions", "Answer in French.")).json().get("version").asInt();
        EffectiveSettings bobs = effective(company, agentId, bobPrincipal);
        assertThat(bobs.agent().instructions()).isEqualTo("Answer in French.");
        assertThat(bobs.agent().contentVersion()).isEqualTo(announced);
        assertThat(bobs.agent().wideningPending()).isFalse();
        assertThat(bob.get(path).json().has("widening")).isFalse();
        JsonNode changed = auditEvents(owner, company, "agent.instructions_changed").getFirst().get("details");
        assertThat(changed.get("agentId").asString()).isEqualTo(agentId.toString());
        assertThat(changed.get("version").asInt()).isEqualTo(announced);
        assertThat(changed.get("by").asString()).isEqualTo(alicePrincipal.toString());
        assertThat(changed.get("changed")).extracting(JsonNode::asString).containsExactly("instructions");
        assertThat(auditEvents(owner, company, "agent.widened")).hasSize(widenedBefore);

        // A network host lets it act (a widening Bob accepts).
        alice.put(path + "/hosts", Map.of("hosts", List.of(Map.of("host", "api.example.com"))));
        assertThat(bob.post(path + "/accept", Map.of("version", bob.get(path).json().get("version").asInt()))
            .status()).isEqualTo(200);
        int acceptedByBob = bob.get(path).json().get("acceptedVersion").asInt();

        // Now a change of the instructions widens: Bob's turns keep the instructions he accepted until he accepts, and
        // the app shows him both.
        int widened = alice.patch(path, Map.of("instructions", "Answer in German.")).json().get("version").asInt();
        bobs = effective(company, agentId, bobPrincipal);
        assertThat(bobs.agent().instructions()).isEqualTo("Answer in French.");
        assertThat(bobs.agent().contentVersion()).isEqualTo(acceptedByBob);
        assertThat(bobs.agent().pending().instructions()).isTrue();
        JsonNode widening = bob.get(path).json().get("widening");
        assertThat(widening.get("currentVersion").asInt()).isEqualTo(widened);
        assertThat(widening.get("instructions").get("accepted").asString()).isEqualTo("Answer in French.");
        assertThat(widening.get("instructions").get("current").asString()).isEqualTo("Answer in German.");
        assertThat(widening.get("tools")).isEmpty();
        assertThat(auditEvents(owner, company, "agent.widened"))
            .filteredOn(event -> event.get("details").get("version").asInt() == widened)
            .singleElement()
            .satisfies(event -> assertThat(event.get("details").get("instructions").asString())
                .isEqualTo("changed"));
        // The editor follows their own change.
        assertThat(effective(company, agentId, alicePrincipal).agent().instructions()).isEqualTo("Answer in German.");

        // A skill added waits as well: Bob's turns do not get it until he accepts.
        String skillName = "release-notes-" + UUID.randomUUID().toString().substring(0, 8);
        UUID skillId = UUID.fromString(alice.upload(company.path("/skills"), ApiClient.Multipart.fields(Map.of(
                "skillMd", "---\nname: " + skillName + "\ndescription: Writes release notes\n---\n# Notes\n")))
            .json()
            .get("id")
            .asString());
        int withSkill = alice.put(path + "/skills", Map.of("skills", List.of(Map.of("skillId", skillId.toString()))))
            .json()
            .get("version")
            .asInt();
        bobs = effective(company, agentId, bobPrincipal);
        assertThat(bobs.skills()).isEmpty();
        widening = bob.get(path).json().get("widening");
        assertThat(widening.get("currentVersion").asInt()).isEqualTo(withSkill);
        assertThat(widening.get("skills")).singleElement().satisfies(skill -> {
            assertThat(skill.get("skillId").asString()).isEqualTo(skillId.toString());
            assertThat(skill.get("name").asString()).isEqualTo(skillName);
            assertThat(skill.get("version").asInt()).isEqualTo(1);
            assertThat(skill.has("was") && !skill.get("was").isNull()).isFalse();
        });

        // Accepting gives both.
        assertThat(bob.post(path + "/accept", Map.of("version", withSkill)).status()).isEqualTo(200);
        bobs = effective(company, agentId, bobPrincipal);
        assertThat(bobs.agent().instructions()).isEqualTo("Answer in German.");
        assertThat(bobs.skills()).extracting(skill -> skill.skillId()).containsExactly(skillId);

        // A skill removed narrows: it goes for everyone at once, announced.
        int removed = alice.put(path + "/skills", Map.of("skills", List.of())).json().get("version").asInt();
        assertThat(effective(company, agentId, bobPrincipal).skills()).isEmpty();
        assertThat(auditEvents(owner, company, "agent.instructions_changed"))
            .filteredOn(event -> event.get("details").get("version").asInt() == removed)
            .singleElement()
            .satisfies(event -> assertThat(event.get("details").get("changed")).extracting(JsonNode::asString)
                .containsExactly("skills"));
    }

    private static <T> T inCompany(TestCompany company, java.util.function.Supplier<T> work) {
        return SystemContext.callInCompany(company.id(), work);
    }

    private EffectiveSettings firstUse(TestCompany company, UUID agentId, UUID principalId) {
        return SystemContext.callInCompany(company.id(), () -> settings.firstUse(company.id(), agentId, principalId));
    }

    private EffectiveSettings effective(TestCompany company, UUID agentId, UUID principalId) {
        return SystemContext.callInCompany(company.id(),
                () -> settings.effective(company.id(), agentId, new SettingsOwner.Person(principalId)));
    }

    private EffectiveSettings.ToolDecision modeOf(TestCompany company, UUID agentId, UUID principalId, UUID systemId,
            String tool) {
        return effective(company, agentId, principalId).modeOf(systemId, tool,
                tool.equals("label") ? ToolEffect.WRITE : ToolEffect.READ);
    }
}
