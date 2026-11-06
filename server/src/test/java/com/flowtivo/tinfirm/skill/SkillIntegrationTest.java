package com.flowtivo.tinfirm.skill;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.StreamSupport;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.support.ApiClient;
import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.IntegrationTest;
import com.flowtivo.tinfirm.support.RecordedEvents;
import com.flowtivo.tinfirm.support.TenantSql;

import tools.jackson.databind.JsonNode;

/**
 * The skills library over HTTP (ADR-0064; the skills contract, §4 to §6 and §9 to §11): who sees and edits a skill on
 * every path; uploads from a zip and from a SKILL.md's text with their checks, versions and the same files twice;
 * shares; the credential scan; an agent's pins, their updates and a deleted skill; a person's switches; a leaving
 * owner.
 */
class SkillIntegrationTest extends IntegrationTest {

    @Test
    void aSkillIsItsOwnersUntilSharedAndAdminsSeeAndEditEverySkill() {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        ApiClient admin = loginAs(company.email("ADMIN"));
        ApiClient alice = loginAs(company.email("MEMBER"));
        Member bob = addMember(company, "bob");
        Member carol = addMember(company, "carol");
        String name = skillName("write-ticket");

        ApiResponse created = alice.upload(company.path("/skills"), zip(name, Map.of("templates/ticket.md",
                "## Summary\n"), Map.of("note", "First cut")));
        assertThat(created.status()).as("%s", created).isEqualTo(201);
        JsonNode skill = created.json();
        UUID skillId = UUID.fromString(skill.get("id").asString());
        String path = company.path("/skills/" + skillId);
        assertThat(skill.get("name").asString()).isEqualTo(name);
        assertThat(skill.get("latestVersion").asInt()).isEqualTo(1);
        assertThat(skill.get("editableByMe").asBoolean()).isTrue();
        assertThat(skill.get("agentCount").asInt()).isZero();
        assertThat(skill.get("ownerPrincipalId").asString())
            .isEqualTo(memberPrincipal(company, company.userId("MEMBER")).toString());
        JsonNode first = skill.get("versions").get(0);
        assertThat(first.get("version").asInt()).isEqualTo(1);
        assertThat(first.get("note").asString()).isEqualTo("First cut");
        assertThat(first.get("fileCount").asInt()).isEqualTo(2);

        // The version's files and its SKILL.md; the download is the canonical zip its hash names.
        JsonNode version = alice.get(path + "/versions/1").json();
        assertThat(version.get("files")).extracting(file -> file.get("path").asString())
            .containsExactly("SKILL.md", "templates/ticket.md");
        assertThat(version.get("skillMd").asString()).contains("name: " + name);
        HttpResponse<byte[]> download = alice.download(path + "/versions/1/content");
        assertThat(download.statusCode()).isEqualTo(200);
        assertThat(download.headers().firstValue("Content-Disposition").orElseThrow())
            .contains(name + "-v1.zip");
        assertThat(sha256(download.body())).isEqualTo(first.get("sha256").asString());
        assertThat(unzip(download.body())).containsOnlyKeys("SKILL.md", "templates/ticket.md");

        // Private to its owner: another member gets 404 on every path and does not list it.
        for (ApiResponse response : List.of(bob.client().get(path), bob.client().get(path + "/versions/1"),
                bob.client().delete(path), bob.client().put(path + "/shares", Map.of("groupIds", List.of())),
                bob.client().upload(path + "/versions", skillMd(name, "Other words")))) {
            assertThat(response.status()).as("%s", response).isEqualTo(404);
        }
        assertThat(bob.client().download(path + "/versions/1/content").statusCode()).isEqualTo(404);
        assertThat(names(bob.client(), company, "")).doesNotContain(name);

        // Admins and the Owner see and edit it.
        for (ApiClient manager : List.of(admin, owner)) {
            assertThat(manager.get(path).json().get("editableByMe").asBoolean()).isTrue();
            assertThat(names(manager, company, "")).contains(name);
        }

        // Shared with a group and with a member: they see it and use it, and do not edit it.
        UUID writers = createGroup(owner, company, unique("Writers"), List.of(bob.userId()));
        JsonNode shared = alice.put(path + "/shares", Map.of("groupIds", List.of(writers.toString()),
                "principalIds", List.of(memberPrincipal(company, carol.userId()).toString()))).json();
        assertThat(shared.get("shareGroupIds")).extracting(JsonNode::asString).containsExactly(writers.toString());
        assertThat(shared.get("memberShares")).extracting(share -> share.get("userId").asString())
            .containsExactly(carol.userId().toString());
        for (Member reader : List.of(bob, carol)) {
            JsonNode seen = reader.client().get(path).json();
            assertThat(seen.get("editableByMe").asBoolean()).isFalse();
            assertThat(seen.has("agentCount")).isFalse();
            assertThat(names(reader.client(), company, "")).contains(name);
            assertThat(reader.client().upload(path + "/versions", skillMd(name, "Mine now")).problemCode())
                .isEqualTo("SKILL_NOT_EDITABLE");
            assertThat(reader.client().delete(path).problemCode()).isEqualTo("SKILL_NOT_EDITABLE");
        }
        assertThat(names(bob.client(), company, "?q=TICKET")).contains(name);
        assertThat(names(bob.client(), company, "?q=nothing-like-it")).doesNotContain(name);
        assertThat(bob.client().get(company.path("/skills?deleted=true")).status()).isEqualTo(403);

        // Audit by names, versions and hashes, never the content; events by ids.
        JsonNode audit = auditEvents(owner, company, "skill.created").stream()
            .filter(event -> event.get("targetId").asString().equals(skillId.toString()))
            .findFirst()
            .orElseThrow();
        assertThat(audit.get("details").get("name").asString()).isEqualTo(name);
        assertThat(audit.get("details").get("sha256").asString()).isEqualTo(first.get("sha256").asString());
        assertThat(audit.get("details").toString()).doesNotContain("Summary");
        assertThat(RecordedEvents.of(company.id(), SkillEvents.SkillCreated.class))
            .contains(new SkillEvents.SkillCreated(company.id(), skillId));
        assertThat(auditEvents(owner, company, "skill.shares_changed")).isNotEmpty();
    }

    @Test
    void uploadsAreCheckedAndVersionedAndTheSameFilesAreNoNewVersion() {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        ApiClient alice = loginAs(company.email("MEMBER"));
        String name = skillName("release-notes");

        ApiResponse created = alice.upload(company.path("/skills"), skillMd(name, "Writes release notes"));
        assertThat(created.status()).as("%s", created).isEqualTo(201);
        String path = company.path("/skills/" + created.json().get("id").asString());

        // The name is unique among the live skills; the front matter and the paths are checked.
        assertThat(alice.upload(company.path("/skills"), skillMd(name, "Again")).problemCode())
            .isEqualTo("DUPLICATE_SKILL_NAME");
        ApiResponse invalid = alice.upload(company.path("/skills"), ApiClient.Multipart.fields(Map.of("skillMd",
                "# No front matter")));
        assertThat(invalid.problemCode()).isEqualTo("SKILL_INVALID");
        assertThat(invalid.json().get("details").get("rule").asString()).isEqualTo("FRONT_MATTER");
        ApiResponse badPath = alice.upload(company.path("/skills"), new ApiClient.Multipart("skill.zip",
                "application/zip", zipBytes(Map.of("SKILL.md", skillText(skillName("x"), "x"), "../evil", "x"))));
        assertThat(badPath.problemCode()).isEqualTo("SKILL_INVALID");
        assertThat(badPath.json().get("details").get("rule").asString()).isEqualTo("PATH");
        // Every file under "..": the top folder is checked before it goes, never stripped into a skill's folder.
        Map<String, String> outside = new LinkedHashMap<>();
        outside.put("../SKILL.md", skillText(skillName("x"), "x"));
        outside.put("../script.sh", "echo hi");
        ApiResponse up = alice.upload(company.path("/skills"), new ApiClient.Multipart("skill.zip",
                "application/zip", zipBytes(outside)));
        assertThat(up.status()).as("%s", up).isEqualTo(400);
        assertThat(up.problemCode()).isEqualTo("SKILL_INVALID");
        assertThat(up.json().get("details").get("rule").asString()).isEqualTo("PATH");
        assertThat(up.json().get("details").get("path").asString()).isEqualTo("../SKILL.md");
        assertThat(alice.upload(company.path("/skills"), new ApiClient.Multipart("skill.zip", "application/zip",
                "not a zip".getBytes(StandardCharsets.UTF_8))).problemCode()).isEqualTo("SKILL_INVALID");
        assertThat(alice.upload(company.path("/skills"), ApiClient.Multipart.fields(Map.of())).problemCode())
            .isEqualTo("VALIDATION_FAILED");

        // The same files again store nothing; other files are the next version; the name stays.
        ApiResponse same = alice.upload(path + "/versions", skillMd(name, "Writes release notes"));
        assertThat(same.status()).isEqualTo(200);
        assertThat(same.json().get("latestVersion").asInt()).isEqualTo(1);
        ApiResponse next = alice.upload(path + "/versions", skillMd(name, "Writes release notes for each tag"));
        assertThat(next.status()).isEqualTo(201);
        assertThat(next.json().get("latestVersion").asInt()).isEqualTo(2);
        assertThat(next.json().get("description").asString()).isEqualTo("Writes release notes for each tag");
        assertThat(next.json().get("versions")).extracting(v -> v.get("version").asInt()).containsExactly(2, 1);
        assertThat(alice.upload(path + "/versions", skillMd(skillName("renamed"), "x")).problemCode())
            .isEqualTo("SKILL_NAME_CHANGED");
        assertThat(auditEvents(owner, company, "skill.version_added").getFirst().get("details")
            .get("previousVersion").asInt()).isEqualTo(1);
    }

    @Test
    void aCredentialInAnyFileRefusesTheUploadAndStoresNothing() throws Exception {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        ApiClient alice = loginAs(company.email("MEMBER"));
        String secret = "vault-value-" + UUID.randomUUID();
        String secretName = "DEPLOY_KEY_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        createSecret(owner, company.id(), secretName, secret);
        String name = skillName("deploy");

        ApiResponse withSecret = alice.upload(company.path("/skills"), zip(name, Map.of("scripts/deploy.sh",
                "curl -H 'Authorization: " + secret + "' https://example.com\n"), Map.of()));
        assertThat(withSecret.status()).isEqualTo(422);
        assertThat(withSecret.problemCode()).isEqualTo("SKILL_CONTAINS_CREDENTIAL");
        assertThat(withSecret.json().get("credentialMatches").get(0).get("kind").asString())
            .isEqualTo("VAULT_SECRET");
        assertThat(withSecret.body()).doesNotContain(secret);

        ApiResponse withToken = alice.upload(company.path("/skills"), skillMd(name, "Uses ghp_"
                + "a".repeat(36)));
        assertThat(withToken.problemCode()).isEqualTo("SKILL_CONTAINS_CREDENTIAL");
        assertThat(withToken.json().get("credentialMatches").get(0).get("kind").asString()).isEqualTo("FORMAT");
        try (TenantSql sql = TenantSql.in(company.id())) {
            assertThat(sql.count("SELECT count(*) FROM skill WHERE name = ?", name)).isZero();
        }
    }

    @Test
    void anAgentPinsAVersionOfEachSkillAndItsEditorsTakeUpdates() {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        ApiClient alice = loginAs(company.email("MEMBER"));
        Member bob = addMember(company, "bob");
        String name = skillName("write-story");
        UUID skillId = UUID.fromString(alice.upload(company.path("/skills"), skillMd(name, "Writes stories")).json()
            .get("id").asString());
        String skillPath = company.path("/skills/" + skillId);
        UUID agentId = createAgent(alice, company, unique("Analyst"));
        String agentPath = company.path("/agents/" + agentId);
        UUID analysts = createGroup(owner, company, unique("Analysts"), List.of(bob.userId()));
        assertThat(alice.put(agentPath + "/shares", Map.of("groupIds", List.of(analysts.toString()))).status())
            .isEqualTo(200);
        int before = alice.get(agentPath).json().get("version").asInt();

        // Added at its latest version: a version of the agent that widens nothing, audited.
        JsonNode agent = alice.put(agentPath + "/skills", Map.of("skills", List.of(Map.of("skillId",
                skillId.toString())))).json();
        assertThat(agent.get("skills")).hasSize(1);
        JsonNode pinned = agent.get("skills").get(0);
        assertThat(pinned.get("name").asString()).isEqualTo(name);
        assertThat(pinned.get("version").asInt()).isEqualTo(1);
        assertThat(pinned.get("latestVersion").asInt()).isEqualTo(1);
        assertThat(pinned.get("deleted").asBoolean()).isFalse();
        assertThat(agent.get("version").asInt()).isEqualTo(before + 1);
        assertThat(agent.get("wideningPending").asBoolean()).isFalse();
        assertThat(bob.client().get(agentPath).json().get("wideningPending").asBoolean()).isFalse();
        JsonNode changed = auditEvents(owner, company, "agent.skills_changed").getFirst().get("details");
        assertThat(changed.get("added").get(0).get("name").asString()).isEqualTo(name);
        assertThat(alice.get(skillPath).json().get("agentCount").asInt()).isEqualTo(1);
        // Saving the same list changes nothing.
        assertThat(alice.put(agentPath + "/skills", Map.of("skills", List.of(Map.of("skillId", skillId.toString()))))
            .json().get("version").asInt()).isEqualTo(before + 1);

        // A new version reaches no agent: its editors see the update and take it.
        alice.upload(skillPath + "/versions", skillMd(name, "Writes stories with acceptance criteria"));
        JsonNode stale = alice.get(agentPath).json().get("skills").get(0);
        assertThat(stale.get("version").asInt()).isEqualTo(1);
        assertThat(stale.get("latestVersion").asInt()).isEqualTo(2);
        assertThat(stale.get("description").asString()).isEqualTo("Writes stories");
        assertThat(summary(alice, company, agentId).get("skillUpdates").asInt()).isEqualTo(1);
        assertThat(summary(bob.client(), company, agentId).get("skillUpdates").asInt()).isZero();
        JsonNode moved = alice.put(agentPath + "/skills", Map.of("skills", List.of(Map.of("skillId",
                skillId.toString(), "version", 2)))).json().get("skills").get(0);
        assertThat(moved.get("version").asInt()).isEqualTo(2);
        assertThat(summary(alice, company, agentId).get("skillUpdates").asInt()).isZero();
        assertThat(alice.put(agentPath + "/skills", Map.of("skills", List.of(Map.of("skillId", skillId.toString(),
                "version", 9)))).problemCode()).isEqualTo("VALIDATION_FAILED");

        // Who sees the agent reads its skills' content through it, without seeing the skill in the library.
        assertThat(bob.client().get(skillPath).status()).isEqualTo(404);
        HttpResponse<byte[]> content = bob.client().download(agentPath + "/skills/" + skillId + "/content");
        assertThat(content.statusCode()).isEqualTo(200);
        assertThat(new String(unzip(content.body()).get("SKILL.md"), StandardCharsets.UTF_8))
            .contains("acceptance criteria");
        assertThat(bob.client().download(agentPath + "/skills/" + UUID.randomUUID() + "/content").statusCode())
            .isEqualTo(404);
        // An editor adds only a skill they see in the library.
        String hidden = skillName("hidden");
        UUID hiddenId = UUID.fromString(owner.upload(company.path("/skills"), skillMd(hidden, "Only the owner's"))
            .json().get("id").asString());
        assertThat(alice.put(agentPath + "/skills", Map.of("skills", List.of(Map.of("skillId", skillId.toString()),
                Map.of("skillId", hiddenId.toString())))).problemCode()).isEqualTo("VALIDATION_FAILED");

        // A person switches a skill off in their settings; a skill the agent lacks is refused.
        ApiResponse saved = bob.client().put(agentPath + "/settings/me", Map.of("skillsOff",
                List.of(skillId.toString())));
        assertThat(saved.status()).as("%s", saved).isEqualTo(200);
        assertThat(saved.json().get("skillsOff")).extracting(JsonNode::asString).containsExactly(skillId.toString());
        assertThat(bob.client().put(agentPath + "/settings/me", Map.of("skillsOff",
                List.of(hiddenId.toString()))).problemCode()).isEqualTo("SKILL_NOT_ON_AGENT");

        // Deleted from the library: the agent keeps it, it moves nowhere, and its name is free again.
        assertThat(alice.delete(skillPath).status()).isEqualTo(204);
        assertThat(alice.get(skillPath).status()).isEqualTo(404);
        assertThat(auditEvents(owner, company, "skill.deleted").getFirst().get("details").get("agentCount").asInt())
            .isEqualTo(1);
        JsonNode kept = alice.get(agentPath).json().get("skills").get(0);
        assertThat(kept.get("deleted").asBoolean()).isTrue();
        assertThat(kept.get("version").asInt()).isEqualTo(2);
        assertThat(alice.put(agentPath + "/skills", Map.of("skills", List.of(Map.of("skillId", skillId.toString(),
                "version", 1)))).problemCode()).isEqualTo("SKILL_DELETED");
        UUID again = UUID.fromString(alice.upload(company.path("/skills"), skillMd(name, "A new one")).json()
            .get("id").asString());
        assertThat(alice.put(agentPath + "/skills", Map.of("skills", List.of(Map.of("skillId", skillId.toString()),
                Map.of("skillId", again.toString())))).problemCode()).isEqualTo("SKILL_NAME_CONFLICT");
        assertThat(names(owner, company, "?deleted=true")).contains(name);

        // Removed from the agent: every switch of it goes with it.
        assertThat(alice.put(agentPath + "/skills", Map.of("skills", List.of())).json().get("skills")).isEmpty();
        assertThat(bob.client().get(agentPath + "/settings/me").json().get("skillsOff")).isEmpty();
    }

    @Test
    void deletingASkillWithRemoveFromAgentsTakesItFromEveryAgentThatPinsIt() {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        ApiClient alice = loginAs(company.email("MEMBER"));
        Member bob = addMember(company, "bob");
        String name = skillName("write-story");
        UUID skillId = UUID.fromString(alice.upload(company.path("/skills"), skillMd(name, "Writes stories")).json()
            .get("id").asString());
        String skillPath = company.path("/skills/" + skillId);
        UUID writers = createGroup(owner, company, unique("Writers"), List.of(bob.userId()));
        assertThat(alice.put(skillPath + "/shares", Map.of("groupIds", List.of(writers.toString()))).status())
            .isEqualTo(200);
        // Bob's own agent, which Alice neither edits nor sees, and Alice's; both pin the skill.
        UUID bobsAgent = createAgent(bob.client(), company, unique("Bob's analyst"));
        UUID alicesAgent = createAgent(alice, company, unique("Alice's analyst"));
        for (Map.Entry<ApiClient, UUID> agent : Map.of(bob.client(), bobsAgent, alice, alicesAgent).entrySet()) {
            assertThat(agent.getKey().put(company.path("/agents/" + agent.getValue() + "/skills"), Map.of("skills",
                    List.of(Map.of("skillId", skillId.toString())))).status()).isEqualTo(200);
        }
        assertThat(bob.client().put(company.path("/agents/" + bobsAgent + "/settings/me"), Map.of("skillsOff",
                List.of(skillId.toString()))).status()).isEqualTo(200);
        int bobsVersion = bob.client().get(company.path("/agents/" + bobsAgent)).json().get("version").asInt();
        assertThat(alice.get(company.path("/agents/" + bobsAgent)).status()).isEqualTo(404);

        assertThat(alice.delete(skillPath + "?removeFromAgents=true").status()).isEqualTo(204);

        // Gone from both, a narrowing that widens nothing; Bob's switch of it went with it.
        JsonNode bobs = bob.client().get(company.path("/agents/" + bobsAgent)).json();
        assertThat(bobs.get("skills")).isEmpty();
        assertThat(bobs.get("version").asInt()).isEqualTo(bobsVersion + 1);
        assertThat(bobs.get("wideningPending").asBoolean()).isFalse();
        assertThat(alice.get(company.path("/agents/" + alicesAgent)).json().get("skills")).isEmpty();
        assertThat(bob.client().get(company.path("/agents/" + bobsAgent + "/settings/me")).json().get("skillsOff"))
            .isEmpty();
        assertThat(bob.client().download(company.path("/agents/" + bobsAgent + "/skills/" + skillId + "/content"))
            .statusCode()).isEqualTo(404);
        List<JsonNode> changes = auditEvents(owner, company, "agent.skills_changed").stream()
            .filter(event -> event.get("details").has("cause"))
            .toList();
        assertThat(changes).extracting(event -> event.get("targetId").asString())
            .containsExactlyInAnyOrder(bobsAgent.toString(), alicesAgent.toString());
        assertThat(changes).allSatisfy(event -> {
            assertThat(event.get("details").get("cause").asString()).isEqualTo("skill.deleted");
            assertThat(event.get("details").get("removed").get(0).get("name").asString()).isEqualTo(name);
        });
        JsonNode deleted = auditEvents(owner, company, "skill.deleted").getFirst().get("details");
        assertThat(deleted.get("agentCount").asInt()).isEqualTo(2);
        assertThat(deleted.get("removedFromAgents").asBoolean()).isTrue();
    }

    @Test
    void aLeavingOwnersSkillsPassToTheAdmins() {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        ApiClient admin = loginAs(company.email("ADMIN"));
        Member dana = addMember(company, "dana");
        UUID danaPrincipal = memberPrincipal(company, dana.userId());
        String name = skillName("test-plan");
        UUID skillId = UUID.fromString(dana.client().upload(company.path("/skills"), skillMd(name, "Plans tests"))
            .json().get("id").asString());

        assertThat(owner.delete(company.path("/members/" + dana.userId())).status()).isEqualTo(204);

        JsonNode passed = admin.get(company.path("/skills/" + skillId)).json();
        assertThat(passed.has("ownerPrincipalId")).isFalse();
        assertThat(passed.get("editableByMe").asBoolean()).isTrue();
        JsonNode audit = auditEvents(owner, company, "skill.passed_to_admins").getFirst();
        assertThat(audit.get("details").get("formerOwnerPrincipalId").asString()).isEqualTo(danaPrincipal.toString());
        assertThat(RecordedEvents.of(company.id(), SkillEvents.SkillPassedToAdmins.class))
            .containsExactly(new SkillEvents.SkillPassedToAdmins(company.id(), skillId, danaPrincipal));
    }

    // ---- helpers -----------------------------------------------------------------------------------------------

    record Member(UUID userId, ApiClient client) {
    }

    private Member addMember(TestCompany company, String name) {
        String email = uniqueEmail(name);
        UUID userId = createUser(admin(), email, false);
        addMember(loginAs(company.email("OWNER")), company.id(), email, "MEMBER");
        return new Member(userId, loginAs(email));
    }

    private static UUID createAgent(ApiClient actor, TestCompany company, String name) {
        ApiResponse response = actor.post(company.path("/agents"), Map.of("name", name, "runtimes",
                List.of("CLAUDE_CODE", "CODEX")));
        assertThat(response.status()).as("agent: %s", response).isEqualTo(201);
        return UUID.fromString(response.json().get("id").asString());
    }

    private static JsonNode summary(ApiClient client, TestCompany company, UUID agentId) {
        return StreamSupport.stream(client.get(company.path("/agents?limit=200")).json().get("items").spliterator(),
                false)
            .filter(item -> item.get("id").asString().equals(agentId.toString()))
            .findFirst()
            .orElseThrow();
    }

    private static List<String> names(ApiClient client, TestCompany company, String query) {
        List<String> names = new ArrayList<>();
        client.get(company.path("/skills" + query)).json().get("items")
            .forEach(item -> names.add(item.get("name").asString()));
        return names;
    }

    /** A skill name unique across the test run: skills' names are unique per company only, but runs share one. */
    private static String skillName(String base) {
        return base + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    static String skillText(String name, String description) {
        return "---\nname: " + name + "\ndescription: " + description + "\n---\n# " + name + "\n\nFollow the steps.\n";
    }

    static ApiClient.Multipart skillMd(String name, String description) {
        return ApiClient.Multipart.fields(Map.of("skillMd", skillText(name, description)));
    }

    static ApiClient.Multipart zip(String name, Map<String, String> files, Map<String, String> fields) {
        Map<String, String> all = new LinkedHashMap<>();
        all.put(name + "/SKILL.md", skillText(name, "Writes a ticket the way the team does"));
        files.forEach((path, text) -> all.put(name + "/" + path, text));
        return new ApiClient.Multipart(name + ".zip", "application/zip", zipBytes(all), fields);
    }

    static byte[] zipBytes(Map<String, String> files) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, String> file : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(file.getKey()));
                zip.write(file.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
        return out.toByteArray();
    }

    static Map<String, byte[]> unzip(byte[] zip) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                files.put(entry.getName(), in.readAllBytes());
            }
        }
        catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
        return files;
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        }
        catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
