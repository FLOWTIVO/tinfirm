package com.flowtivo.tinfirm.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.support.ApiClient;
import com.flowtivo.tinfirm.support.FakeBotFramework;
import com.flowtivo.tinfirm.support.TenantSql;
import com.flowtivo.tinfirm.support.TestRuns;

/**
 * What the database itself refuses of the chats (the package 5 contract, §3, C3, C8, C9) as the runtime role: an event
 * acts once; a held event has its hold; a thread's events are taken in
 * the platform's order; one conversation per chat thread, its place fixed, a direct message's place a chat thread; a
 * Slack system's own secrets on a Slack system only. The platforms' paths across companies are
 * {@code CrossTenantBodyTest}'s.
 */
class ChatConstraintTest extends ChatTestBench {

    private static final String INSERT_EVENT = """
            INSERT INTO chat_event (id, company_id, system_id, event_id, kind, sender, thread_key, platform_ts,
                                    payload, status, reason, held_until, next_attempt_at, received_at, handled_at)
            VALUES (?, ?, ?, ?, 'MESSAGE', 'U1', ?, ?, '{}'::jsonb, ?, ?, ?, now(), now(), ?)""";

    private static final String INSERT_CONVERSATION = """
            INSERT INTO conversation (id, company_id, agent_id, key, title, person_principal_id, runtime,
                                      payer_principal_id, model_access, place, place_ref, origin, created_at,
                                      updated_at)
            VALUES (?, ?, ?, ?, 'A direct message', ?, 'CLAUDE_CODE', ?, 'BUDGET', ?, CAST(? AS jsonb), ?, now(),
                    now())""";

    private UUID slackSystem(TestCompany company) {
        ApiClient owner = loginAs(company.email("OWNER"));
        return createSystem(owner, company, Map.of("name", unique("Slack"), "kind", "SLACK", "slack",
                Map.of("transport", "EVENTS_API")));
    }

    @Test
    void anEventActsOnceAndAHeldEventHasItsHold() throws SQLException {
        TestCompany company = companyWithRoles();
        UUID system = slackSystem(company);
        try (TenantSql sql = TenantSql.in(company.id())) {
            sql.accepted(INSERT_EVENT, UUID.randomUUID(), company.id(), system, "Ev1", "D1:1", BigDecimal.ONE,
                    "RECEIVED", null, null, null);
            assertThat(sql.refused(INSERT_EVENT, UUID.randomUUID(), company.id(), system, "Ev1", "D1:1",
                    BigDecimal.ONE, "RECEIVED", null, null, null)).isEqualTo("chat_event_uq");
            assertThat(sql.refused(INSERT_EVENT, UUID.randomUUID(), company.id(), system, "Ev2", "D1:1",
                    BigDecimal.ONE, "AWAITING_LINK", null, null, null)).isEqualTo("chat_event_held_ck");
            assertThat(sql.refused(INSERT_EVENT, UUID.randomUUID(), company.id(), system, "Ev3", "D1:1",
                    BigDecimal.ONE, "IGNORED", "BECAUSE", null, java.sql.Timestamp.from(java.time.Instant.now())))
                .isEqualTo("chat_event_reason_ck");
            assertThat(sql.refused(INSERT_EVENT, UUID.randomUUID(), company.id(), system, "Ev4", "D1:1",
                    BigDecimal.ONE, "HANDLED", null, null, null)).isEqualTo("chat_event_handled_ck");
            assertThat(sql.refused("INSERT INTO chat_link_request (token_hash, company_id, system_id, external_id,"
                    + " held_event_ids, created_at, expires_at) VALUES (?, ?, ?, 'U1',"
                    + " ARRAY(SELECT gen_random_uuid() FROM generate_series(1, 6)), now(),"
                    + " now() + interval '10 minutes')", "0".repeat(64), company.id(), system))
                .isEqualTo("chat_link_request_held_ck");
        }
    }

    @Test
    void aThreadsEventsAreTakenInThePlatformsOrder() throws SQLException {
        TestCompany company = companyWithRoles();
        UUID system = slackSystem(company);
        try (TenantSql sql = TenantSql.in(company.id())) {
            UUID later = UUID.randomUUID();
            UUID earlier = UUID.randomUUID();
            UUID otherThread = UUID.randomUUID();
            // Arrived reversed: the later message first.
            sql.accepted(INSERT_EVENT, later, company.id(), system, "EvLater", "D1:100.000001",
                    new BigDecimal("100.000002"), "RECEIVED", null, null, null);
            sql.accepted(INSERT_EVENT, earlier, company.id(), system, "EvEarlier", "D1:100.000001",
                    new BigDecimal("100.000001"), "RECEIVED", null, null, null);
            sql.accepted(INSERT_EVENT, otherThread, company.id(), system, "EvOther", "D1:200.000001",
                    new BigDecimal("200.000001"), "RECEIVED", null, null, null);
            List<Object> due = sql.column("SELECT event_id FROM chat_event_due(now() + interval '1 second', 500)");
            assertThat(due).contains(earlier, otherThread).doesNotContain(later);
            // While the earlier one is claimed, its thread waits; the other thread does not.
            sql.accepted("UPDATE chat_event SET claimed_until = now() + interval '1 minute' WHERE id = ?", earlier);
            due = sql.column("SELECT event_id FROM chat_event_due(now() + interval '1 second', 500)");
            assertThat(due).contains(otherThread).doesNotContain(earlier, later);
            sql.accepted("UPDATE chat_event SET status = 'HANDLED', handled_at = now(), claimed_until = NULL"
                    + " WHERE id = ?", earlier);
            due = sql.column("SELECT event_id FROM chat_event_due(now() + interval '1 second', 500)");
            assertThat(due).contains(later);
        }
    }

    @Test
    void aChatThreadIsOneConversationForGoodHeldWhereItStarted() throws SQLException {
        TestCompany company = companyWithRoles();
        UUID system = slackSystem(company);
        UUID person = memberPrincipal(company, company.userId("MEMBER"));
        UUID agent = TestRuns.agent(company.id());
        String dm = "{\"systemId\":\"" + system + "\",\"kind\":\"DM\",\"channelId\":\"D1\",\"threadId\":\"1.1\"}";
        try (TenantSql sql = TenantSql.in(company.id())) {
            UUID conversation = UUID.randomUUID();
            sql.accepted(INSERT_CONVERSATION, conversation, company.id(), agent, "dm-1", person, person,
                    "CHAT_THREAD", dm, "DIRECT_MESSAGE");
            assertThat(sql.refused(INSERT_CONVERSATION, UUID.randomUUID(), company.id(), agent, "dm-2", person,
                    person, "CHAT_THREAD", dm, "DIRECT_MESSAGE")).isEqualTo("conversation_chat_thread_uq");
            assertThat(sql.refused(INSERT_CONVERSATION, UUID.randomUUID(), company.id(), agent, "dm-3", person,
                    person, "CHAT_THREAD", "{\"systemId\":\"" + system + "\",\"kind\":\"DM\"}", "DIRECT_MESSAGE"))
                .isEqualTo("conversation_place_ref_chat_ck");
            assertThat(sql.refused(INSERT_CONVERSATION, UUID.randomUUID(), company.id(), agent, "dm-4", person,
                    person, "CHAT_THREAD", dm.replace("\"DM\"", "\"CHANNEL\"").replace("1.1", "2.2"),
                    "DIRECT_MESSAGE")).isEqualTo("conversation_chat_origin_ck");
            assertThat(sql.refused(INSERT_CONVERSATION, UUID.randomUUID(), company.id(), agent, "dm-5", person,
                    person, "CHAT_THREAD", dm.replace("1.1", "3.3"), "APP")).isEqualTo("conversation_chat_origin_ck");
            assertThat(sql.refused("UPDATE conversation SET place_ref = CAST(? AS jsonb) WHERE id = ?",
                    dm.replace("D1", "D2"), conversation)).isEqualTo("conversation_place_fixed_ck");
            // Package 4's conversation_fixed_ck fixes every conversation's place and origin.
            assertThat(sql.refused("UPDATE conversation SET origin = 'TRIGGER' WHERE id = ?", conversation))
                .isEqualTo("conversation_fixed_ck");
        }
    }

    @Test
    void aSlackSystemsOwnSecretsAreASlackSystemsOnly() throws SQLException {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        FakeBotFramework.Tenant tenant = FakeBotFramework.tenant();
        UUID teams = createSystem(owner, company, Map.of("name", unique("Teams"), "kind", "TEAMS", "teams",
                Map.of("tenantId", tenant.tenantId, "appId", tenant.appId)));
        UUID secret = createSecret(owner, company.id(), unique("SECRET").toUpperCase().replace('-', '_'),
                "test-value");
        try (TenantSql sql = TenantSql.in(company.id())) {
            assertThat(sql.refused("UPDATE external_system SET chat_signing_secret_id = ? WHERE id = ?", secret,
                    teams)).isEqualTo("external_system_chat_secrets_ck");
        }
    }
}
