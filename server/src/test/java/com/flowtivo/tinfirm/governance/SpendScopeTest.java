package com.flowtivo.tinfirm.governance;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.support.ApiClient;
import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.IntegrationTest;
import com.flowtivo.tinfirm.support.TenantSql;
import com.flowtivo.tinfirm.support.TestConversations;
import com.flowtivo.tinfirm.support.TestRuns;

import tools.jackson.databind.JsonNode;

/**
 * Who sees which spend (the package 6 contract, §5.2; G1), over every row of its table: a member their own; ADMIN
 * every run, another person's personal conversations folded into one row per person without keys; an instance admin
 * who is not a member as ADMIN; OWNER every run, another's personal conversation by key and never titled. A
 * {@code conversationId} the caller may not see answers exactly as an unknown one. Labels, a principal who left,
 * ranges, zones, two groupings, the validation of the query, the CSV and its audit, and tenant isolation.
 */
class SpendScopeTest extends IntegrationTest {

    private static final String RANGE = "from=2026-01-01&to=2026-12-31";

    /** The company, its four people and their personal conversations, each with a settled run. */
    private record World(TestCompany company, UUID member2User, UUID owner, UUID admin, UUID member, UUID member2,
            UUID agentId, Map<String, UUID> conversations, Map<String, String> keys) {

        String path(String query) {
            return company.path("/spend?" + RANGE + (query.isEmpty() ? "" : "&" + query));
        }
    }

    private World world() {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        String member2Email = uniqueEmail("member2");
        UUID member2User = createUser(admin(), member2Email, false);
        addMember(owner, company.id(), member2Email, "MEMBER");
        UUID ownerP = memberPrincipal(company, company.userId("OWNER"));
        UUID adminP = memberPrincipal(company, company.userId("ADMIN"));
        UUID memberP = memberPrincipal(company, company.userId("MEMBER"));
        UUID member2P = memberPrincipal(company, member2User);
        UUID agentId = TestRuns.agent(company.id());
        Map<String, UUID> conversations = new java.util.HashMap<>();
        Map<String, String> keys = new java.util.HashMap<>();
        Instant march = Instant.parse("2026-03-10T12:00:00Z");
        record Spend(String who, UUID person, String model, String cost) {
        }
        for (Spend spend : List.of(new Spend("owner", ownerP, "claude-sonnet-4-5", "1"),
                new Spend("admin", adminP, "claude-sonnet-4-5", "2"),
                new Spend("member", memberP, "claude-sonnet-4-5", "3"),
                new Spend("member2", member2P, "claude-haiku-4-5", "8"))) {
            UUID conversationId = UUID.randomUUID();
            String key = spend.who() + "-" + conversationId.toString().substring(0, 8);
            TestConversations.bare(company.id(), conversationId, key, agentId, spend.person(), "CLAUDE_CODE");
            UUID runId = UUID.randomUUID();
            TestRuns.ofConversation(company.id(), runId, conversationId, key, agentId, spend.person(), 1,
                    "SUCCEEDED");
            spendRow(company.id(), runId, spend.model(), conversationId, agentId, null, spend.person(), spend.person(),
                    true, march, new BigDecimal(spend.cost()), "GATEWAY");
            if (spend.who().equals("member")) {
                // A second model of the same run, and the relay's count of it.
                spendRow(company.id(), runId, "claude-haiku-4-5", conversationId, agentId, null, spend.person(),
                        spend.person(), true, march, new BigDecimal("1"), "RELAY");
            }
            conversations.put(spend.who(), conversationId);
            keys.put(spend.who(), key);
        }
        return new World(company, member2User, ownerP, adminP, memberP, member2P, agentId, conversations, keys);
    }

    static void spendRow(UUID companyId, UUID runId, String model, UUID conversationId, UUID agentId,
            @Nullable UUID triggerId, UUID payer, UUID actor, boolean personal, Instant endedAt,
            @Nullable BigDecimal cost, String source) {
        try (TenantSql sql = TenantSql.in(companyId)) {
            sql.accepted("""
                    INSERT INTO run_spend (company_id, run_id, model, conversation_id, agent_id, trigger_id,
                                           payer_principal_id, acting_principal_id, personal, day, ended_at, requests,
                                           input_tokens, output_tokens, cost_usd, source, recorded_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, 100, 10, ?, ?, now())""", companyId, runId, model,
                    conversationId, agentId, triggerId, payer, actor, personal,
                    endedAt.atOffset(ZoneOffset.UTC).toLocalDate(), Timestamp.from(endedAt), cost, source);
            sql.commit();
        }
        catch (SQLException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static JsonNode ok(ApiResponse response) {
        assertThat(response.status()).as("%s", response).isEqualTo(200);
        return response.json();
    }

    private static BigDecimal cost(JsonNode amounts) {
        return amounts.get("costUsd").decimalValue();
    }

    @Test
    void aMemberSeesTheirOwnSpendOnlyAndAnUnseenConversationFiltersToNothing() {
        World world = world();
        ApiClient member = loginAs(world.company().email("MEMBER"));

        JsonNode all = ok(member.get(world.path("")));
        assertThat(cost(all.get("totals"))).isEqualByComparingTo("4");
        assertThat(all.get("totals").get("runs").asLong()).isEqualTo(1);
        assertThat(all.get("totals").get("relayCostUsd").decimalValue()).isEqualByComparingTo("1");

        JsonNode byConversation = ok(member.get(world.path("groupBy=conversation")));
        assertThat(byConversation.get("rows")).hasSize(1);
        JsonNode key = byConversation.get("rows").get(0).get("keys").get(0);
        assertThat(key.get("id").asString()).isEqualTo(world.conversations().get("member").toString());
        assertThat(key.get("key").asString()).isEqualTo(world.keys().get("member"));
        assertThat(key.get("title").asString()).startsWith("Test conversation");

        // Another person's conversation answers exactly as one that does not exist (G1).
        JsonNode unseen = ok(member.get(world.path("conversationId=" + world.conversations().get("owner"))));
        JsonNode unknown = ok(member.get(world.path("conversationId=" + UUID.randomUUID())));
        assertThat(unseen.get("totals")).isEqualTo(unknown.get("totals"));
        assertThat(unseen.get("rows")).isEqualTo(unknown.get("rows"));
        assertThat(unseen.get("totals").get("runs").asLong()).isZero();
        // Filters reach no further than the scope.
        assertThat(cost(ok(member.get(world.path("payerId=" + world.owner()))).get("totals"))).isZero();
    }

    @Test
    void adminsSeeEveryRunWithOtherPeoplesPersonalConversationsFoldedPerPerson() {
        World world = world();
        ApiClient admin = loginAs(world.company().email("ADMIN"));
        JsonNode all = ok(admin.get(world.path("")));
        assertThat(cost(all.get("totals"))).isEqualByComparingTo("15");

        JsonNode rows = ok(admin.get(world.path("groupBy=conversation"))).get("rows");
        assertThat(rows).hasSize(4);
        List<JsonNode> keys = new ArrayList<>();
        rows.forEach(row -> keys.add(row.get("keys").get(0)));
        JsonNode own = keys.stream().filter(key -> !key.get("folded").asBoolean()).findFirst().orElseThrow();
        assertThat(own.get("id").asString()).isEqualTo(world.conversations().get("admin").toString());
        assertThat(own.has("title") && !own.get("title").isNull()).isTrue();
        List<JsonNode> folded = keys.stream().filter(key -> key.get("folded").asBoolean()).toList();
        assertThat(folded).hasSize(3);
        assertThat(folded).allSatisfy(key -> {
            assertThat(key.get("id") == null || key.get("id").isNull()).isTrue();
            assertThat(key.get("key") == null || key.get("key").isNull()).isTrue();
            assertThat(key.get("title") == null || key.get("title").isNull()).isTrue();
            assertThat(key.get("label").asString()).startsWith("Personal conversations of ");
        });
        assertThat(folded).extracting(key -> key.get("personPrincipalId").asString())
            .containsExactlyInAnyOrder(world.owner().toString(), world.member().toString(),
                    world.member2().toString());

        // A filter on another person's personal conversation answers as an unknown one.
        JsonNode unseen = ok(admin.get(world.path("groupBy=conversation&conversationId="
                + world.conversations().get("member"))));
        JsonNode unknown = ok(admin.get(world.path("groupBy=conversation&conversationId=" + UUID.randomUUID())));
        assertThat(unseen).isEqualTo(unknown);
        assertThat(cost(ok(admin.get(world.path("conversationId=" + world.conversations().get("admin"))))
            .get("totals"))).isEqualByComparingTo("2");

        // An instance admin who is not a member sees what ADMIN sees.
        JsonNode instance = ok(admin().get(world.path("groupBy=conversation"))).get("rows");
        assertThat(instance).hasSize(4);
        List<Boolean> foldedRows = new ArrayList<>();
        instance.forEach(row -> foldedRows.add(row.get("keys").get(0).get("folded").asBoolean()));
        assertThat(foldedRows).containsOnly(true);
    }

    @Test
    void theOwnerSeesEveryConversationByKeyAndOnlyTheirOwnTitled() {
        World world = world();
        ApiClient owner = loginAs(world.company().email("OWNER"));
        JsonNode rows = ok(owner.get(world.path("groupBy=conversation"))).get("rows");
        assertThat(rows).hasSize(4);
        rows.forEach(row -> {
            JsonNode key = row.get("keys").get(0);
            assertThat(key.get("folded").asBoolean()).isFalse();
            assertThat(key.get("key").asString()).isNotBlank();
            boolean own = key.get("id").asString().equals(world.conversations().get("owner").toString());
            boolean titled = key.has("title") && !key.get("title").isNull();
            assertThat(titled).as("titled: %s", key).isEqualTo(own);
        });
        assertThat(cost(ok(owner.get(world.path("conversationId=" + world.conversations().get("member"))))
            .get("totals"))).isEqualByComparingTo("4");
    }

    @Test
    void groupsByTwoDimensionsWithLabelsAndNamesWhoLeft() {
        World world = world();
        ApiClient owner = loginAs(world.company().email("OWNER"));
        assertThat(owner.delete(world.company().path("/members/" + world.member2User())).status()).isEqualTo(204);

        JsonNode rows = ok(owner.get(world.path("groupBy=payer,model"))).get("rows");
        // Owner, admin and member2 on one model, the member on two.
        assertThat(rows).hasSize(5);
        JsonNode first = rows.get(0);
        assertThat(first.get("keys").get(0).get("dimension").asString()).isEqualTo("payer");
        assertThat(first.get("keys").get(1).get("dimension").asString()).isEqualTo("model");
        assertThat(first.get("keys").get(0).get("id").asString()).isEqualTo(world.member2().toString());
        assertThat(first.get("keys").get(0).get("label").asString()).endsWith(" (left)");
        assertThat(cost(first.get("amounts"))).isEqualByComparingTo("8");

        JsonNode agents = ok(owner.get(world.path("groupBy=agent"))).get("rows");
        assertThat(agents.get(0).get("keys").get(0).get("label").asString()).startsWith("Test agent");
        JsonNode sources = ok(owner.get(world.path("source=RELAY"))).get("totals");
        assertThat(cost(sources)).isEqualByComparingTo("1");
    }

    @Test
    void periodsAreBucketedInTheZoneAndTheRangeIsChecked() {
        World world = world();
        ApiClient owner = loginAs(world.company().email("OWNER"));
        UUID late = UUID.randomUUID();
        TestRuns.ofConversation(world.company().id(), late, world.conversations().get("owner"),
                world.keys().get("owner"), world.agentId(), world.owner(), 2, "SUCCEEDED");
        spendRow(world.company().id(), late, "claude-sonnet-4-5", world.conversations().get("owner"),
                world.agentId(), null, world.owner(), world.owner(), true, Instant.parse("2026-03-31T23:30:00Z"),
                new BigDecimal("16"), "GATEWAY");

        JsonNode utc = ok(owner.get(world.path("groupBy=period&bucket=MONTH"))).get("rows");
        assertThat(utc).extracting(row -> row.get("keys").get(0).get("id").asString()).containsExactly("2026-03-01");
        JsonNode prague = ok(owner.get(world.path("groupBy=period&bucket=MONTH&zone=Europe/Prague"))).get("rows");
        assertThat(prague).extracting(row -> row.get("keys").get(0).get("id").asString())
            .containsExactly("2026-03-01", "2026-04-01");
        JsonNode days = ok(owner.get(world.path("groupBy=period&bucket=DAY"))).get("rows");
        assertThat(days).extracting(row -> row.get("keys").get(0).get("id").asString())
            .containsExactly("2026-03-10", "2026-03-31");
        // The default range is the current month, which holds none of these.
        assertThat(ok(owner.get(world.company().path("/spend"))).get("totals").get("runs").asLong()).isZero();

        for (String bad : List.of("from=2025-01-01&to=2026-06-01", "from=2026-03-02&to=2026-03-01",
                "from=2026-03-01&to=2026-03-01", "from=2026-01-01&to=2026-02-01&zone=Mars/Olympus",
                "from=2026-01-01&to=2026-02-01&groupBy=payer,model,agent",
                "from=2026-01-01&to=2026-02-01&groupBy=payer,payer")) {
            ApiResponse response = owner.get(world.company().path("/spend?" + bad));
            assertThat(response.status()).as(bad).isEqualTo(400);
            assertThat(response.problemCode()).as(bad).isEqualTo("SPEND_RANGE_INVALID");
        }
    }

    @Test
    void theCsvIsTheSameRowsAndIsAuditedWhileReadingIsNot() {
        World world = world();
        ApiClient member = loginAs(world.company().email("MEMBER"));
        ApiClient owner = loginAs(world.company().email("OWNER"));
        ok(member.get(world.path("groupBy=model")));
        assertThat(auditEvents(owner, world.company(), "spend.exported")).isEmpty();

        ApiResponse csv = member.send("GET", world.path("groupBy=model&model=claude-sonnet-4-5"), null,
                Map.of("Accept", "text/csv"));
        assertThat(csv.status()).as("%s", csv).isEqualTo(200);
        assertThat(csv.contentType()).startsWith("text/csv");
        List<String> lines = csv.body().lines().toList();
        assertThat(lines.getFirst()).startsWith("model_id,model_label,runs,requests,");
        assertThat(lines).hasSize(2);
        assertThat(lines.get(1)).startsWith("claude-sonnet-4-5,claude-sonnet-4-5,1,1,100,10,0,0,3,");

        List<JsonNode> exported = auditEvents(owner, world.company(), "spend.exported");
        assertThat(exported).hasSize(1);
        JsonNode details = exported.getFirst().get("details");
        assertThat(details.get("rows").asInt()).isEqualTo(1);
        assertThat(details.get("groupBy").get(0).asString()).isEqualTo("model");
        assertThat(details.get("filters").get("model").asString()).isEqualTo("claude-sonnet-4-5");
        assertThat(exported.getFirst().get("actor").get("id").asString())
            .isEqualTo(world.company().userId("MEMBER").toString());
    }

    @Test
    void anotherCompanysMemberGetsNotFound() {
        World world = world();
        TestCompany other = companyWithRoles();
        ApiClient stranger = loginAs(other.email("OWNER"));
        assertThat(stranger.get(world.path("")).status()).isEqualTo(404);
        assertThat(stranger.send("GET", world.path(""), null, Map.of("Accept", "text/csv")).status())
            .isEqualTo(404);
    }

    @Test
    void anAgentsOwnerSeesNothingOfItsSpendAsSuch() {
        World world = world();
        // The member owns the agent: they still see only what they paid (open point 3).
        try (TenantSql sql = TenantSql.in(world.company().id())) {
            sql.accepted("UPDATE agent SET owner_principal_id = ?, owner_kind = 'MEMBER' WHERE id = ?",
                    world.member(), world.agentId());
            sql.commit();
        }
        catch (SQLException ex) {
            throw new IllegalStateException(ex);
        }
        ApiClient member = loginAs(world.company().email("MEMBER"));
        assertThat(cost(ok(member.get(world.path("agentId=" + world.agentId()))).get("totals")))
            .isEqualByComparingTo("4");
    }
}
