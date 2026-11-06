package com.flowtivo.tinfirm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.yaml.snakeyaml.Yaml;

import com.flowtivo.tinfirm.ee.licence.EnterpriseLicence;
import com.flowtivo.tinfirm.support.ApiClient;
import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.FakeRunner;
import com.flowtivo.tinfirm.support.IntegrationTest;
import com.flowtivo.tinfirm.support.JiraStub;
import com.flowtivo.tinfirm.support.TenantSql;
import com.flowtivo.tinfirm.support.TestAuditChecks;
import com.flowtivo.tinfirm.support.TestConversations;
import com.flowtivo.tinfirm.support.TestRuns;
import com.flowtivo.tinfirm.support.TestTriggers;

import tools.jackson.databind.JsonNode;

/**
 * CONVENTIONS §10: reads every company-scoped path from {@code api/openapi.yaml} and calls every operation as the
 * OWNER of company A against company B, with a valid request body generated from the schema. Every call must be
 * 404. A new path or operation is covered automatically; every path parameter points at a real object of company B.
 * It varies only the path's company: the paths without one (a runner host's, the model relay's), the MCP endpoint and
 * another company's ids in bodies and queries are {@link CrossTenantBodyTest}'s. Company B's call journal and
 * submission keys (package R) stay invisible to company A's tenant context.
 */
@ExtendWith(EnterpriseLicence.class)
class TenantIsolationTest extends IntegrationTest {

    private static final List<String> METHODS = List.of("get", "put", "post", "patch", "delete");

    private static final List<String> STRING_CANDIDATES = List.of("ABC", "ISOLATION_TEST", "Isolation test",
            "isolation-test-value", "https://isolation.test/repo.git");

    @Test
    void everyCompanyScopedOperationIsNotFoundForMembersOfAnotherCompany() throws IOException, SQLException {
        Map<String, Object> spec = new Yaml().load(Files.readString(specPath()));
        TestCompany a = companyWithRoles();
        TestCompany b = companyWithRoles();
        ApiClient ownerOfB = loginAs(b.email("OWNER"));
        UUID secretOfB = createSecret(ownerOfB, b.id(), "B_ONLY", "b-value");
        // A runner host of company B, connected.
        RunnerHostRef hostOfB = registerRunnerHost(ownerOfB, b.id(), "b-host");
        FakeRunner runnerOfB = connectRunner(hostOfB, List.of(FakeRunner.claudeCredential("claude-max")));
        Map<String, String> pathValues = new LinkedHashMap<>();
        pathValues.put("{companyId}", b.id().toString());
        pathValues.put("{userId}", b.userId("MEMBER").toString());
        pathValues.put("{secretId}", secretOfB.toString());
        pathValues.put("{runnerHostId}", hostOfB.id().toString());
        // A group of company B with a member (ADR-0049 §2).
        ApiResponse group = ownerOfB.post(b.path("/groups"), Map.of("name", "B group", "memberIds",
                List.of(b.userId("MEMBER").toString())));
        assertThat(group.status()).as("group: %s", group).isEqualTo(201);
        pathValues.put("{groupId}", group.json().get("id").asString());
        // A service identity of company B (the package 1 contract, §3.1).
        ApiResponse serviceIdentity = ownerOfB.post(b.path("/service-identities"), Map.of("name", "B bot"));
        assertThat(serviceIdentity.status()).as("service identity: %s", serviceIdentity).isEqualTo(201);
        pathValues.put("{serviceIdentityId}", serviceIdentity.json().get("id").asString());
        // A principal of company B: its service identity's (the package 2 contract, §8).
        pathValues.put("{principalId}", serviceIdentity.json().get("id").asString());
        // A code host of company B with a repository; its fixed tool "get" (the package 1 contract, §3.3 to §3.5).
        UUID systemOfB = createSystem(ownerOfB, b, "B GitHub", "GITHUB", "https://api.github.com", false);
        ApiResponse repository = ownerOfB.post(b.path("/systems/" + systemOfB + "/repositories"),
                Map.of("url", "https://github.com/acme/b-only"));
        assertThat(repository.status()).as("repository: %s", repository).isEqualTo(201);
        pathValues.put("{systemId}", systemOfB.toString());
        pathValues.put("{repositoryId}", repository.json().get("id").asString());
        pathValues.put("{toolName}", "get");
        // A grant of company B: a credential of its Jira, lent to its service identity and its group (contract §3.6,
        // §3.7; ADR-0072).
        JiraStub.Instance jiraOfB = JiraStub.newInstance();
        UUID jiraSystemOfB = createSystem(ownerOfB, b, "B Jira", "JIRA_DC", jiraOfB.baseUrl(), false);
        UUID accountOfB = connectServiceToken(ownerOfB, b, UUID.fromString(serviceIdentity.json().get("id")
            .asString()), jiraSystemOfB, jiraOfB, "test-b-only-token", "b-only");
        pathValues.put("{credentialId}", accountOfB.toString());
        pathValues.put("{grantId}", createGrant(ownerOfB, b, accountOfB,
                UUID.fromString(group.json().get("id").asString()), "READ").toString());
        // An agent of company B with its code host, shared with its group (the package 3 contract, §3.1).
        ApiResponse agent = ownerOfB.post(b.path("/agents"), Map.of("name", "B agent", "runtimes",
                List.of("CLAUDE_CODE"), "systems", List.of(Map.of("systemId", systemOfB.toString())),
                "shareGroupIds", List.of(group.json().get("id").asString())));
        assertThat(agent.status()).as("agent: %s", agent).isEqualTo(201);
        pathValues.put("{agentId}", agent.json().get("id").asString());
        // A skill of company B (ADR-0064), not pinned by its agent, whose version stays the first.
        ApiResponse skill = ownerOfB.upload(b.path("/skills"), ApiClient.Multipart.fields(Map.of("skillMd",
                "---\nname: b-only-skill\ndescription: Company B's own\n---\nB only\n")));
        assertThat(skill.status()).as("skill: %s", skill).isEqualTo(201);
        pathValues.put("{skillId}", skill.json().get("id").asString());
        pathValues.put("{version}", "1");
        // A conversation of company B: its member's, with its agent (the package 3 contract, §3.8).
        UUID conversationOfB = UUID.randomUUID();
        TestConversations.bare(b.id(), conversationOfB, "b-only-1", UUID.fromString(pathValues.get("{agentId}")),
                memberPrincipal(b, b.userId("MEMBER")), "CLAUDE_CODE");
        pathValues.put("{conversationId}", conversationOfB.toString());
        // A conversation's key, as a hold's lookup takes it.
        pathValues.put("{key}", "b-only-1");
        // A file of that conversation (contract §3.13), uploaded with an idempotency key (package R, §3.5).
        ApiResponse attachmentOfB = loginAs(b.email("MEMBER")).send("POST", b.path("/conversations/" + conversationOfB
                + "/attachments"), new ApiClient.Multipart("b-only.txt", "text/plain",
                        "b only".getBytes(StandardCharsets.UTF_8)), Map.of("Idempotency-Key", "b-only-key"));
        assertThat(attachmentOfB.status()).as("attachment: %s", attachmentOfB).isEqualTo(201);
        pathValues.put("{attachmentId}", attachmentOfB.json().get("id").asString());
        // An open question of that conversation (the package 3 contract, §3.12).
        UUID decisionOfB = TestConversations.question(b.id(), conversationOfB, memberPrincipal(b, b.userId("MEMBER")),
                "B only?");
        pathValues.put("{decisionId}", decisionOfB.toString());
        // A trigger of company B: its member's schedule with its agent (the package 4 contract, §3.1).
        UUID memberOfB = memberPrincipal(b, b.userId("MEMBER"));
        UUID triggerOfB = TestTriggers.schedule(b.id(), UUID.fromString(pathValues.get("{agentId}")), memberOfB,
                memberOfB);
        pathValues.put("{triggerId}", triggerOfB.toString());
        // A SCIM token of company B (ADR-0087): B serves no SCIM, so it is stored as the store keeps one.
        UUID scimTokenOfB = UUID.randomUUID();
        try (TenantSql sql = TenantSql.in(b.id())) {
            sql.update("INSERT INTO scim_token (id, company_id, name, token_hash, created_at) VALUES (?, ?, 'B only', ?,"
                    + " now())", scimTokenOfB, b.id(), scimTokenOfB.toString().replace("-", "").repeat(2));
            sql.commit();
        }
        pathValues.put("{tokenId}", scimTokenOfB.toString());
        // A run of company B, running on its host (the package 3 contract, §3.11).
        UUID runOfB = UUID.randomUUID();
        TestRuns.running(b.id(), runOfB, 1, hostOfB.id(), UUID.fromString(serviceIdentity.json().get("id")
            .asString()));
        pathValues.put("{runId}", runOfB.toString());
        // A tool call of that run in the call journal (package R, §3.2).
        try (TenantSql sqlOfB = TenantSql.in(b.id())) {
            sqlOfB.accepted("""
                    INSERT INTO tool_call (company_id, run_id, request_id, conversation_id, system_id, tool, name,
                                           effect, replay, arguments_sha256, state, attempt, started_at, finished_at,
                                           result)
                    VALUES (?, ?, ?, ?, ?, 'get', 'b_get', 'READ', 'SAFE', ?, 'DONE', 1, now(), now(),
                            '{"status":"DONE"}')""", b.id(), runOfB, UUID.randomUUID(), conversationOfB, systemOfB,
                    "0".repeat(64));
            sqlOfB.commit();
        }
        // A legal hold of company B on its conversation, and a daily check of its audit chain (the package 6
        // contract, §3.6, §3.7); an outside identity's id at its system.
        ApiResponse holdOfB = ownerOfB.post(b.path("/holds"), Map.of("conversationId", conversationOfB.toString(),
                "reason", "B only"));
        assertThat(holdOfB.status()).as("hold: %s", holdOfB).isEqualTo(201);
        pathValues.put("{holdId}", holdOfB.json().get("id").asString());
        pathValues.put("{checkId}", TestAuditChecks.failed(b.id()).toString());
        pathValues.put("{externalId}", "b-only-identity");
        // A notice of the inbox of company B's OWNER, which they dismissed (CONVENTIONS §15.7).
        String noticeOfB = "COMPANY_SPEND:" + YearMonth.now(ZoneOffset.UTC);
        try (TenantSql sql = TenantSql.in(b.id())) {
            sql.accepted("""
                    INSERT INTO company_spend_alert (company_id, month, crossed_at, total_usd, alert_usd)
                    VALUES (?, ?, now(), 1250, 1000)""", b.id(), YearMonth.now(ZoneOffset.UTC).atDay(1));
            sql.commit();
        }
        assertThat(ownerOfB.put(b.path("/inbox/dismissals/" + noticeOfB), null).status()).isEqualTo(204);
        pathValues.put("{noticeKey}", noticeOfB);
        // A note of a memory (ADR-0063): its number; another company's agent and trigger are 404 before it.
        pathValues.put("{noteNumber}", "1");
        // A Google account its MEMBER linked for the Gmail add-on (ADR-0083).
        UUID linkOfB = UUID.randomUUID();
        try (TenantSql sql = TenantSql.in(b.id())) {
            sql.accepted("""
                    INSERT INTO gmail_link (id, company_id, user_id, google_subject, google_email, linked_at,
                                            last_used_at)
                    VALUES (?, ?, ?, 'b-only-google-subject', 'b@gmail.test', now(), now())""", linkOfB, b.id(),
                    b.userId("MEMBER"));
            sql.commit();
        }
        pathValues.put("{linkId}", linkOfB.toString());
        ApiClient ownerOfA = loginAs(a.email("OWNER"));

        List<String> checked = new ArrayList<>();
        for (Map.Entry<String, Object> path : map(spec.get("paths")).entrySet()) {
            if (!path.getKey().contains("{companyId}")) {
                continue;
            }
            String url = path.getKey();
            for (Map.Entry<String, String> value : pathValues.entrySet()) {
                url = url.replace(value.getKey(), value.getValue());
            }
            if (url.contains("{")) {
                fail("No test value for a path parameter of %s: extend pathValues", path.getKey());
            }
            for (String method : METHODS) {
                Map<String, Object> operation = map(map(path.getValue()).get(method));
                if (operation.isEmpty()) {
                    continue;
                }
                Object body = requestBody(spec, operation);
                ApiResponse response = ownerOfA.send(method.toUpperCase(), url, body);
                assertThat(response.status()).as("%s %s -> %s", method.toUpperCase(), path.getKey(), response)
                    .isEqualTo(404);
                checked.add(method.toUpperCase() + " " + path.getKey());
            }
        }

        assertThat(checked).as("company-scoped operations found in the spec").hasSizeGreaterThanOrEqualTo(20);
        // Nothing in company B changed.
        assertThat(ownerOfB.get(b.path("/secrets")).json().get("items").get(0).get("version").asInt()).isEqualTo(1);
        assertThat(ownerOfB.get(b.path("/members")).json().get("items")).hasSize(3);
        JsonNode hostOfBNow = ownerOfB.get(b.path("/runner-hosts")).json().get("items").get(0);
        assertThat(hostOfBNow.has("desiredMaxConcurrentRuns")).isFalse();
        assertThat(hostOfBNow.get("configPending").asBoolean()).isFalse();
        assertThat(ownerOfB.get(b.path("/runner-hosts")).json().get("items")).hasSize(1);
        JsonNode groupOfBNow = ownerOfB.get(b.path("/groups/" + group.json().get("id").asString())).json();
        assertThat(groupOfBNow.get("name").asString()).isEqualTo("B group");
        assertThat(groupOfBNow.get("members")).hasSize(1);
        JsonNode agentOfBNow = ownerOfB.get(b.path("/agents/" + pathValues.get("{agentId}"))).json();
        assertThat(agentOfBNow.get("name").asString()).isEqualTo("B agent");
        assertThat(agentOfBNow.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(agentOfBNow.get("version").asInt()).isEqualTo(1);
        JsonNode skillOfBNow = ownerOfB.get(b.path("/skills/" + pathValues.get("{skillId}"))).json();
        assertThat(skillOfBNow.get("latestVersion").asInt()).isEqualTo(1);
        assertThat(skillOfBNow.get("deleted").asBoolean()).isFalse();
        assertThat(skillOfBNow.get("shareGroupIds")).isEmpty();
        assertThat(ownerOfB.get(b.path("/runs/" + runOfB)).json().get("status").asString()).isEqualTo("RUNNING");
        JsonNode conversationOfBNow = ownerOfB.get(b.path("/conversations/" + conversationOfB)).json();
        assertThat(conversationOfBNow.get("state").asString()).isEqualTo("OPEN");
        assertThat(conversationOfBNow.get("title").asString()).isEqualTo("Test conversation b-only-1");
        assertThat(conversationOfBNow.get("messages")).isEmpty();
        assertThat(ownerOfB.get(b.path("/conversations/" + conversationOfB + "/attachments")).json()).hasSize(1);
        assertThat(conversationOfBNow.get("decisions")).singleElement()
            .satisfies(decision -> assertThat(decision.get("status").asString()).isEqualTo("OPEN"));
        assertThat(ownerOfB.get(b.path("/decisions/" + decisionOfB)).json().get("status").asString())
            .isEqualTo("OPEN");
        JsonNode triggerOfBNow = ownerOfB.get(b.path("/triggers/" + triggerOfB)).json();
        assertThat(triggerOfBNow.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(triggerOfBNow.has("deletedAt")).isFalse();
        assertThat(ownerOfB.get(b.path("/settings")).json().get("triggerFiringsPerHour").get("max").asInt())
            .isEqualTo(300);
        runnerOfB.assertFramesValid();
        runnerOfB.close();
        assertThat(ownerOfB.get(b.path("/audit-events/verification")).json().get("valid").asBoolean()).isTrue();
        assertThat(ownerOfB.get(b.path("/holds")).json().get("items")).singleElement()
            .satisfies(hold -> assertThat(hold.get("id").asString()).isEqualTo(pathValues.get("{holdId}")));
        assertThat(ownerOfB.get(b.path("/retention")).json().get("defaults").asBoolean()).isTrue();
        // Company B's call journal and submission keys (package R) are invisible to company A, and B keeps them.
        try (TenantSql sql = TenantSql.in(a.id())) {
            assertThat(sql.count("SELECT count(*) FROM tool_call WHERE company_id = ?", b.id())).isZero();
            assertThat(sql.count("SELECT count(*) FROM submission_key WHERE company_id = ?", b.id())).isZero();
            assertThat(sql.count("SELECT count(*) FROM submission_key WHERE key = 'b-only-key'")).isZero();
            sql.context(b.id());
            assertThat(sql.count("SELECT count(*) FROM tool_call WHERE company_id = ?", b.id())).isEqualTo(1);
            assertThat(sql.count("SELECT count(*) FROM submission_key WHERE key = 'b-only-key'")).isEqualTo(1);
            // B's OWNER's dismissal stays: company A's undo reached nothing.
            assertThat(sql.count("SELECT count(*) FROM inbox_dismissal WHERE notice_key = ?", noticeOfB))
                .isEqualTo(1);
        }
    }

    private static Path specPath() {
        String configured = System.getProperty("tinfirm.openapi.spec");
        return Path.of(configured != null ? configured : "../api/openapi.yaml");
    }

    private static Object requestBody(Map<String, Object> spec, Map<String, Object> operation) {
        Map<String, Object> requestBody = map(operation.get("requestBody"));
        if (requestBody.isEmpty()) {
            return null;
        }
        Map<String, Object> content = map(requestBody.get("content"));
        if (content.containsKey("multipart/form-data")) {
            return new ApiClient.Multipart("isolation-test.txt", "text/plain",
                    "isolation test".getBytes(StandardCharsets.UTF_8));
        }
        Map<String, Object> schema = map(map(content.get("application/json")).get("schema"));
        return example(spec, schema);
    }

    /** A minimal valid example: required properties only, strings that satisfy format, pattern and length. */
    private static Object example(Map<String, Object> spec, Map<String, Object> schema) {
        if (schema.containsKey("$ref")) {
            String name = ((String) schema.get("$ref")).substring("#/components/schemas/".length());
            return example(spec, map(map(map(spec.get("components")).get("schemas")).get(name)));
        }
        if (schema.containsKey("enum")) {
            return ((List<?>) schema.get("enum")).getFirst();
        }
        String type = (String) schema.getOrDefault("type", "object");
        return switch (type) {
            case "object" -> {
                Map<String, Object> properties = map(schema.get("properties"));
                Map<String, Object> example = new LinkedHashMap<>();
                for (Object required : (List<?>) schema.getOrDefault("required", List.of())) {
                    example.put((String) required, example(spec, map(properties.get(required))));
                }
                yield example;
            }
            case "string" -> string(schema);
            case "integer", "number" -> schema.getOrDefault("minimum", 1);
            case "boolean" -> false;
            case "array" -> {
                int minItems = ((Number) schema.getOrDefault("minItems", 0)).intValue();
                yield java.util.Collections.nCopies(minItems, example(spec, map(schema.get("items"))));
            }
            default -> throw new IllegalStateException("Unsupported schema type " + type);
        };
    }

    private static String string(Map<String, Object> schema) {
        String format = (String) schema.get("format");
        if ("email".equals(format)) {
            return "isolation-test@example.test";
        }
        if ("uuid".equals(format)) {
            return UUID.randomUUID().toString();
        }
        if ("date-time".equals(format)) {
            return OffsetDateTime.now().toString();
        }
        int minLength = ((Number) schema.getOrDefault("minLength", 0)).intValue();
        int maxLength = ((Number) schema.getOrDefault("maxLength", Integer.MAX_VALUE)).intValue();
        Pattern pattern = schema.containsKey("pattern") ? Pattern.compile((String) schema.get("pattern")) : null;
        for (String candidate : STRING_CANDIDATES) {
            if (candidate.length() >= minLength && candidate.length() <= maxLength
                    && (pattern == null || pattern.matcher(candidate).matches())) {
                return candidate;
            }
        }
        throw new IllegalStateException("No example string for " + schema + ": extend STRING_CANDIDATES");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }
}
