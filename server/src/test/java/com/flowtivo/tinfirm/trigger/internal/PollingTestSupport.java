package com.flowtivo.tinfirm.trigger.internal;

import static com.flowtivo.tinfirm.support.TestTriggers.withModel;
import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;

import com.flowtivo.tinfirm.shared.security.SystemContext;
import com.flowtivo.tinfirm.support.ApiClient;
import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.FakeLiteLlm;
import com.flowtivo.tinfirm.support.IntegrationTest;
import com.flowtivo.tinfirm.support.ModelGateways;
import com.flowtivo.tinfirm.support.TenantSql;

import tools.jackson.databind.JsonNode;

/**
 * What the polling tests share (TEST FIXTURE ONLY): a company with Alice (the author), Bob (the team) and Carol (neither),
 * the bundled gateway with their keys, an agent shared with Alice's and Bob's group; a tracker trigger made through
 * the API; its poll's pass run on demand, its times moved back so that changes of the stubs fall after its arming.
 */
abstract class PollingTestSupport extends IntegrationTest {

    static final Duration WAIT = Duration.ofSeconds(20);

    static final DateTimeFormatter JIRA_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSxx")
        .withZone(ZoneOffset.UTC);

    final FakeLiteLlm litellm = FakeLiteLlm.bundled();

    @Autowired
    TriggerPolls polls;

    @Autowired
    ScheduleFirings schedules;

    record Bench(TestCompany company, ApiClient owner, ApiClient alice, ApiClient bob, ApiClient carol,
            UUID alicePrincipal, UUID bobPrincipal, UUID carolPrincipal, UUID bobUser, UUID carolUser, UUID team,
            UUID users, UUID agentId, int agentVersion) {

        String path(String suffix) {
            return company.path(suffix);
        }

        UUID id() {
            return company.id();
        }
    }

    Bench bench() {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        String bobEmail = uniqueEmail("bob");
        String carolEmail = uniqueEmail("carol");
        UUID bobUser = createUser(admin(), bobEmail, false);
        UUID carolUser = createUser(admin(), carolEmail, false);
        addMember(owner, company.id(), bobEmail, "MEMBER");
        addMember(owner, company.id(), carolEmail, "MEMBER");
        UUID alicePrincipal = memberPrincipal(company, company.userId("MEMBER"));
        UUID bobPrincipal = memberPrincipal(company, bobUser);
        UUID carolPrincipal = memberPrincipal(company, carolUser);
        ModelGateways.configureBundled(owner, company);
        for (UUID principal : List.of(alicePrincipal, bobPrincipal)) {
            ModelGateways.awaitKey(litellm, company, principal);
            ModelGateways.awaitStatus(owner, company, principal, "OK");
        }
        UUID users = createGroup(owner, company, unique("Users"), List.of(company.userId("MEMBER"), bobUser));
        UUID team = createGroup(owner, company, unique("Team"), List.of(bobUser));
        ApiResponse agent = owner.post(company.path("/agents"), Map.of("name", unique("Analyst"), "runtimes",
                List.of("CLAUDE_CODE"), "instructions", "You analyse issues.", "shareGroupIds",
                List.of(users.toString())));
        assertThat(agent.status()).as("%s", agent).isEqualTo(201);
        return new Bench(company, owner, loginAs(company.email("MEMBER")), loginAs(bobEmail), loginAs(carolEmail),
                alicePrincipal, bobPrincipal, carolPrincipal, bobUser, carolUser, team, users,
                UUID.fromString(agent.json().get("id").asString()), agent.json().get("version").asInt());
    }

    /** A tracker trigger's body. */
    static Map<String, Object> tracker(Bench bench, String name, UUID systemId, UUID accountId, UUID payer,
            List<Map<String, Object>> events, String subject, @Nullable String scope, @Nullable UUID team) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("source", "TRACKER");
        body.put("systemId", systemId.toString());
        body.put("events", events);
        body.put("subject", subject);
        if (scope != null) {
            body.put("scope", scope);
        }
        body.put("agentId", bench.agentId().toString());
        body.put("agentVersion", bench.agentVersion());
        body.put("instruction", "Prepare the issue for the team.");
        body.put("accounts", List.of(Map.of("systemId", systemId.toString(), "accountId", accountId.toString())));
        body.put("payerPrincipalId", payer.toString());
        if (team != null) {
            body.put("teamGroupId", team.toString());
        }
        body.put("acknowledgeOpenEvents", true);
        return body;
    }

    static Map<String, Object> event(String event) {
        return Map.of("event", event);
    }

    static Map<String, Object> event(String event, String value) {
        return Map.of("event", event, "value", value);
    }

    UUID create(ApiClient author, Bench bench, Map<String, Object> body) {
        ApiResponse created = author.post(bench.path("/triggers"), withModel(body));
        assertThat(created.status()).as("%s", created).isEqualTo(201);
        return UUID.fromString(created.json().get("id").asString());
    }

    /** The trigger's poll, made by its creation. */
    UUID pollOf(Bench bench, UUID triggerId) {
        return SystemContext.callInCompany(bench.id(), () -> polls.pollOf(bench.id(), triggerId)).orElseThrow();
    }

    /** The trigger armed, and its poll's window started, {@code ago} before now. */
    void armedAgo(Bench bench, UUID triggerId, Duration ago) throws SQLException {
        OffsetDateTime at = OffsetDateTime.now(ZoneOffset.UTC).minus(ago);
        try (TenantSql sql = TenantSql.in(bench.id())) {
            sql.update("UPDATE trigger SET armed_at = ? WHERE id = ?", at, triggerId);
            sql.update("""
                    UPDATE trigger_poll SET since = ?, offset_keys = '{}', cursor = NULL
                     WHERE id IN (SELECT p.id FROM trigger_poll p JOIN trigger_account a ON a.account_id = p.account_id
                                   WHERE a.trigger_id = ?)""", at, triggerId);
            sql.commit();
        }
    }

    /** The poll's snapshots read {@code ago} before now, as if its last pass ran then. */
    static void readAgo(Bench bench, UUID pollId, Duration ago) throws SQLException {
        try (TenantSql sql = TenantSql.in(bench.id())) {
            sql.update("UPDATE trigger_subject SET seen_at = ? WHERE poll_id = ?",
                    OffsetDateTime.now(ZoneOffset.UTC).minus(ago), pollId);
            sql.commit();
        }
    }

    /** One pass of the poll now. */
    TriggerPolls.Outcome pass(Bench bench, UUID pollId) {
        TriggerPolls.Outcome outcome = SystemContext.callInCompany(bench.id(), () -> polls.poll(bench.id(), pollId,
                true));
        assertThat(outcome).as("the pass ran").isNotNull();
        return outcome;
    }

    /** The trigger's firings, oldest first: {@code event:outcome:subjectKey}. */
    static List<String> firings(Bench bench, UUID triggerId) throws SQLException {
        List<String> found = new ArrayList<>();
        try (TenantSql sql = TenantSql.in(bench.id())) {
            for (Map<String, Object> row : sql.rows("SELECT event, outcome, subject_key FROM trigger_firing"
                    + " WHERE trigger_id = ? ORDER BY created_at, id", triggerId)) {
                found.add(row.get("event") + ":" + row.get("outcome") + (row.get("subject_key") != null ? ":"
                        + row.get("subject_key") : ""));
            }
        }
        return found;
    }

    static List<Map<String, Object>> firingRows(Bench bench, UUID triggerId) throws SQLException {
        try (TenantSql sql = TenantSql.in(bench.id())) {
            return sql.rows("SELECT * FROM trigger_firing WHERE trigger_id = ? ORDER BY created_at, id", triggerId);
        }
    }

    static Map<String, Object> pollRow(Bench bench, UUID pollId) throws SQLException {
        try (TenantSql sql = TenantSql.in(bench.id())) {
            return sql.rows("SELECT * FROM trigger_poll WHERE id = ?", pollId).getFirst();
        }
    }

    static Map<String, Object> triggerRow(Bench bench, UUID triggerId) throws SQLException {
        try (TenantSql sql = TenantSql.in(bench.id())) {
            return sql.rows("SELECT * FROM trigger WHERE id = ?", triggerId).getFirst();
        }
    }

    /** The trigger's conversations, oldest first. */
    static List<Map<String, Object>> conversations(Bench bench, UUID triggerId) throws SQLException {
        try (TenantSql sql = TenantSql.in(bench.id())) {
            return sql.rows("SELECT id, state, ended_reason, chain_depth, place_ref ->> 'key' AS key FROM conversation"
                    + " WHERE trigger_id = ? ORDER BY created_at, id", triggerId);
        }
    }

    /** The conversation's messages, oldest first: {@code kind:body}. */
    static List<String> messages(Bench bench, UUID conversationId) throws SQLException {
        List<String> found = new ArrayList<>();
        try (TenantSql sql = TenantSql.in(bench.id())) {
            for (Map<String, Object> row : sql.rows("SELECT kind, body FROM message WHERE conversation_id = ?"
                    + " ORDER BY created_at, id", conversationId)) {
                found.add(row.get("kind") + ":" + row.get("body"));
            }
        }
        return found;
    }

    /** A Jira timestamp {@code ago} before now. */
    static String jiraAgo(Duration ago) {
        return JIRA_TIME.format(Instant.now().minus(ago));
    }

    /** An ISO timestamp {@code ago} before now, to the second (GitHub, GitLab). */
    static String isoAgo(Duration ago) {
        return Instant.now().minus(ago).truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString();
    }

    static JsonNode json(ApiResponse response) {
        assertThat(response.status()).as("%s", response).isBetween(200, 299);
        return response.json();
    }
}
