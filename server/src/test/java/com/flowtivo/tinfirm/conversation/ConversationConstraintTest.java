package com.flowtivo.tinfirm.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.support.ApiClient;
import com.flowtivo.tinfirm.support.IntegrationTest;
import com.flowtivo.tinfirm.support.TenantSql;
import com.flowtivo.tinfirm.support.TestRuns;

/**
 * What the database itself refuses of conversations (the package 3 contract, §3.8 to §3.13, §4 and §9), as the runtime
 * role: I6 (one account per system per conversation, fixed for its life, replaced only by the holder's account with the
 * same identity), I7 (a gone binding stays gone), I8 (the person's own account or a grant's, never another member's),
 * I10 (the payer is the person and is fixed), I12 (a key of its own, never changed), a request id's one decision, a
 * decision decided once, a run of a conversation, and another company's rows.
 */
class ConversationConstraintTest extends IntegrationTest {

    private static final String INSERT_CONVERSATION = """
            INSERT INTO conversation (id, company_id, agent_id, key, title, person_principal_id, runtime,
                                      payer_principal_id, model_access, subscription_label, created_at, updated_at)
            VALUES (?, ?, ?, ?, 'Fix the checkout', ?, 'CLAUDE_CODE', ?, ?, ?, now(), now())""";

    private static final String INSERT_BINDING = """
            INSERT INTO conversation_binding (id, company_id, conversation_id, system_id, state, how, account_id,
                                              holder_principal_id, external_id, grant_id, reason, waiting_since,
                                              bound_at, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now(), now())""";

    private static final String INSERT_ACCOUNT = """
            INSERT INTO account (id, company_id, system_id, principal_id, principal_kind, kind, external_id,
                                 connected_at, updated_at)
            VALUES (?, ?, ?, ?, ?, 'LINK', ?, now(), now())""";

    private static final String INSERT_DECISION = """
            INSERT INTO decision (id, company_id, conversation_id, request_id, kind, question, approver_principal_id,
                                  created_at)
            VALUES (?, ?, ?, ?, 'QUESTION', 'Which branch?', ?, now())""";

    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }

    /** A company, its member (the conversations' person), another member, an agent and two systems. */
    private record Setup(TestCompany company, UUID person, UUID otherMember, UUID agent, UUID system,
            UUID otherSystem) {
    }

    private Setup setup() {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        UUID system = createSystem(owner, company, unique("Git"), "GIT", null, false);
        UUID otherSystem = createSystem(owner, company, unique("Git"), "GIT", null, false);
        return new Setup(company, memberPrincipal(company, company.userId("MEMBER")),
                memberPrincipal(company, company.userId("ADMIN")), TestRuns.agent(company.id()), system,
                otherSystem);
    }

    private static UUID conversation(TenantSql sql, Setup setup, String key) throws SQLException {
        UUID id = UUID.randomUUID();
        sql.accepted(INSERT_CONVERSATION, id, setup.company().id(), setup.agent(), key, setup.person(),
                setup.person(), "BUDGET", null);
        return id;
    }

    @Test
    void aConversationHasItsOwnKeyAndItsPersonPaysForGood() throws SQLException {
        Setup setup = setup();
        TestCompany other = companyWithRoles();
        UUID companyId = setup.company().id();
        try (TenantSql sql = TenantSql.in(companyId)) {
            UUID conversation = conversation(sql, setup, "shop-123-dev-1");
            // I12: a key of its own in the company, of the branch's characters, at most 93.
            assertThat(sql.refused(INSERT_CONVERSATION, UUID.randomUUID(), companyId, setup.agent(), "shop-123-dev-1",
                    setup.person(), setup.person(), "BUDGET", null)).isEqualTo("conversation_key_uq");
            assertThat(sql.refused(INSERT_CONVERSATION, UUID.randomUUID(), companyId, setup.agent(), "Shop 123",
                    setup.person(), setup.person(), "BUDGET", null)).isEqualTo("conversation_key_ck");
            assertThat(sql.refused(INSERT_CONVERSATION, UUID.randomUUID(), companyId, setup.agent(), "shop-",
                    setup.person(), setup.person(), "BUDGET", null)).isEqualTo("conversation_key_ck");
            // I10: nobody but its person pays; a subscription names its label, the budget none.
            assertThat(sql.refused(INSERT_CONVERSATION, UUID.randomUUID(), companyId, setup.agent(), "shop-2",
                    setup.person(), setup.otherMember(), "BUDGET", null)).isEqualTo("conversation_payer_ck");
            assertThat(sql.refused(INSERT_CONVERSATION, UUID.randomUUID(), companyId, setup.agent(), "shop-2",
                    setup.person(), setup.person(), "SUBSCRIPTION", null)).isEqualTo("conversation_model_access_ck");
            assertThat(sql.refused(INSERT_CONVERSATION, UUID.randomUUID(), companyId, setup.agent(), "shop-2",
                    setup.person(), setup.person(), "BUDGET", "claude-max")).isEqualTo("conversation_model_access_ck");

            // I10, I12 and open point 3: what a conversation fixes at its start never changes.
            for (String change : List.of("key = 'shop-123-dev-9'", "runtime = 'CODEX'",
                    "model_access = 'SUBSCRIPTION', subscription_label = 'claude-max'",
                    "agent_id = '" + TestRuns.agent(companyId) + "'")) {
                assertThat(sql.refused("UPDATE conversation SET " + change + " WHERE id = ?", conversation))
                    .as(change).isEqualTo("conversation_fixed_ck");
            }
            assertThat(sql.refused("UPDATE conversation SET person_principal_id = ?, payer_principal_id = ? "
                    + "WHERE id = ?", setup.otherMember(), setup.otherMember(), conversation))
                .isEqualTo("conversation_fixed_ck");
            // The title and the turn's state change; an ended conversation stays ended.
            sql.accepted("UPDATE conversation SET title = 'Fix the cart' WHERE id = ?", conversation);
            sql.accepted("UPDATE conversation SET state = 'ENDED', ended_reason = 'USER', ended_at = now() "
                    + "WHERE id = ?", conversation);
            assertThat(sql.refused("UPDATE conversation SET state = 'OPEN', ended_reason = NULL, ended_at = NULL "
                    + "WHERE id = ?", conversation)).isEqualTo("conversation_ended_final_ck");
            // Never deleted by the server.
            assertThat(sql.refused("DELETE FROM conversation WHERE id = ?", conversation))
                .contains("permission denied");
            // A run's conversation is one of the company's.
            assertThat(sql.refused("""
                    INSERT INTO run (id, company_id, conversation_id, conversation_key, agent_id, runtime, turn_reason,
                                     turn_number, acting_principal_id, payer_principal_id, model_access, status,
                                     created_at)
                    VALUES (?, ?, ?, 'nowhere-1', ?, 'CLAUDE_CODE', 'FIRST', 1, ?, ?, 'BUDGET', 'QUEUED', now())""",
                    UUID.randomUUID(), companyId, UUID.randomUUID(), setup.agent(), setup.person(), setup.person()))
                .isEqualTo("run_conversation_fk");

            // Another company's conversations are invisible; the same key there is its own.
            sql.context(other.id());
            assertThat(sql.count("SELECT count(*) FROM conversation WHERE id = ?", conversation)).isZero();
            assertThat(sql.update("UPDATE conversation SET title = 'Taken' WHERE id = ?", conversation)).isZero();
        }
    }

    @Test
    void aConversationBindsOneAccountPerSystemForItsLife() throws SQLException {
        Setup setup = setup();
        UUID companyId = setup.company().id();
        try (TenantSql sql = TenantSql.in(companyId)) {
            UUID conversation = conversation(sql, setup, "bind-1");
            UUID own = UUID.randomUUID();
            sql.accepted(INSERT_ACCOUNT, own, companyId, setup.system(), setup.person(), "MEMBER", "alice");
            UUID binding = UUID.randomUUID();
            sql.accepted(INSERT_BINDING, binding, companyId, conversation, setup.system(), "BOUND", "OWN", own,
                    setup.person(), "alice", null, null, null, now());
            // I6: one binding per system per conversation.
            assertThat(sql.refused(INSERT_BINDING, UUID.randomUUID(), companyId, conversation, setup.system(),
                    "WAITING", null, null, null, null, null, "NONE", now(), null))
                .isEqualTo("conversation_binding_system_uq");
            // A binding is on a system of the company, and stays on it.
            assertThat(sql.refused(INSERT_BINDING, UUID.randomUUID(), companyId, conversation, UUID.randomUUID(),
                    "WAITING", null, null, null, null, null, "NONE", now(), null))
                .isEqualTo("conversation_binding_system_ck");
            assertThat(sql.refused("UPDATE conversation_binding SET system_id = ? WHERE id = ?", setup.otherSystem(),
                    binding)).isEqualTo("conversation_binding_fixed_ck");
            // A bound binding names its account; a waiting one its reason.
            assertThat(sql.refused(INSERT_BINDING, UUID.randomUUID(), companyId, conversation, setup.otherSystem(),
                    "BOUND", null, null, null, null, null, null, null, null))
                .isEqualTo("conversation_binding_reason_ck");

            // I6: the holder, the identity and how it bound are fixed once bound.
            assertThat(sql.refused("UPDATE conversation_binding SET holder_principal_id = ? WHERE id = ?",
                    setup.otherMember(), binding)).isEqualTo("conversation_binding_fixed_account_ck");
            assertThat(sql.refused("UPDATE conversation_binding SET external_id = 'mallory' WHERE id = ?", binding))
                .isEqualTo("conversation_binding_fixed_account_ck");
            // The person disconnects and connects the same identity again: the binding takes the new account row.
            sql.accepted("DELETE FROM account WHERE id = ?", own);
            UUID again = UUID.randomUUID();
            sql.accepted(INSERT_ACCOUNT, again, companyId, setup.system(), setup.person(), "MEMBER", "alice");
            sql.accepted("UPDATE conversation_binding SET account_id = ? WHERE id = ?", again, binding);
            // Another identity never replaces it.
            sql.accepted("DELETE FROM account WHERE id = ?", again);
            UUID mallory = UUID.randomUUID();
            sql.accepted(INSERT_ACCOUNT, mallory, companyId, setup.system(), setup.person(), "MEMBER", "mallory");
            assertThat(sql.refused("UPDATE conversation_binding SET account_id = ? WHERE id = ?", mallory, binding))
                .isEqualTo("conversation_binding_fixed_account_ck");

            // I7: a gone binding stays gone.
            sql.accepted("UPDATE conversation_binding SET state = 'GONE', reason = 'IDENTITY_CHANGED' WHERE id = ?",
                    binding);
            assertThat(sql.refused("UPDATE conversation_binding SET state = 'BOUND', reason = NULL WHERE id = ?",
                    binding)).isEqualTo("conversation_binding_gone_ck");
            assertThat(sql.refused("DELETE FROM conversation_binding WHERE id = ?", binding))
                .contains("permission denied");
        }
    }

    @Test
    void aConversationBindsItsPersonsOwnAccountOrAGrantsCredentialNeverAnotherMembers() throws SQLException {
        Setup setup = setup();
        TestCompany company = setup.company();
        UUID companyId = company.id();
        ApiClient owner = loginAs(company.email("OWNER"));
        UUID analysts = createGroup(owner, company, unique("Analysts"), List.of(company.userId("MEMBER")));
        UUID secret = createSecret(owner, companyId, "TEST_CREDENTIAL", "test-credential");
        try (TenantSql sql = TenantSql.in(companyId)) {
            UUID conversation = conversation(sql, setup, "grant-1");
            UUID others = UUID.randomUUID();
            sql.accepted(INSERT_ACCOUNT, others, companyId, setup.system(), setup.otherMember(), "MEMBER", "admin");
            // A credential of the other system (ADR-0072), lent to the person's group.
            UUID bots = UUID.randomUUID();
            sql.accepted("""
                    INSERT INTO account (id, company_id, system_id, principal_id, principal_kind, name, kind, secret_id,
                                         external_id, connected_at, updated_at)
                    VALUES (?, ?, ?, NULL, 'SYSTEM', 'Bot', 'TOKEN', ?, 'bot', now(), now())""", bots, companyId,
                    setup.otherSystem(), secret);
            UUID grant = UUID.randomUUID();
            sql.accepted("""
                    INSERT INTO account_grant (id, company_id, account_id, group_id, access, created_at, updated_at)
                    VALUES (?, ?, ?, ?, 'READ', now(), now())""", grant, companyId, bots, analysts);

            // I8: never another member's account, with or without a grant.
            assertThat(sql.refused(INSERT_BINDING, UUID.randomUUID(), companyId, conversation, setup.system(),
                    "BOUND", "OWN", others, setup.otherMember(), "admin", null, null, null, now()))
                .isEqualTo("conversation_binding_holder_ck");
            assertThat(sql.refused(INSERT_BINDING, UUID.randomUUID(), companyId, conversation, setup.system(),
                    "BOUND", "GRANTED", others, null, "admin", grant, null, null, now()))
                .isEqualTo("conversation_binding_holder_ck");
            // A credential only through the grant that lends it, which names no holder.
            assertThat(sql.refused(INSERT_BINDING, UUID.randomUUID(), companyId, conversation, setup.otherSystem(),
                    "BOUND", "OWN", bots, setup.person(), "bot", null, null, null, now()))
                .isEqualTo("conversation_binding_holder_ck");
            assertThat(sql.refused(INSERT_BINDING, UUID.randomUUID(), companyId, conversation, setup.otherSystem(),
                    "BOUND", "GRANTED", bots, setup.person(), "bot", grant, null, null, now()))
                .isEqualTo("conversation_binding_holder_ck");
            // The account is on the binding's system, with the identity it names.
            assertThat(sql.refused(INSERT_BINDING, UUID.randomUUID(), companyId, conversation, setup.otherSystem(),
                    "BOUND", "GRANTED", bots, null, "someone-else", grant, null, null, now()))
                .isEqualTo("conversation_binding_holder_ck");
            sql.accepted(INSERT_BINDING, UUID.randomUUID(), companyId, conversation, setup.otherSystem(), "BOUND",
                    "GRANTED", bots, null, "bot", grant, null, null, now());

            // A waiting binding binds the same way later.
            UUID waiting = UUID.randomUUID();
            UUID otherConversation = conversation(sql, setup, "grant-2");
            sql.accepted(INSERT_BINDING, waiting, companyId, otherConversation, setup.system(), "WAITING", null, null,
                    null, null, null, "NONE", now(), null);
            assertThat(sql.refused("""
                    UPDATE conversation_binding
                       SET state = 'BOUND', how = 'OWN', account_id = ?, holder_principal_id = ?, external_id = 'admin',
                           reason = NULL, waiting_since = NULL, bound_at = now()
                     WHERE id = ?""", others, setup.otherMember(), waiting)).isEqualTo("conversation_binding_holder_ck");
        }
    }

    @Test
    void aRequestIdGetsOneDecisionDecidedOnce() throws SQLException {
        Setup setup = setup();
        UUID companyId = setup.company().id();
        try (TenantSql sql = TenantSql.in(companyId)) {
            UUID conversation = conversation(sql, setup, "ask-1");
            UUID other = conversation(sql, setup, "ask-2");
            UUID request = UUID.randomUUID();
            UUID decision = UUID.randomUUID();
            sql.accepted(INSERT_DECISION, decision, companyId, conversation, request, setup.person());
            // A request id sent again gets the same decision, never a second one; another conversation's is its own.
            assertThat(sql.refused(INSERT_DECISION, UUID.randomUUID(), companyId, conversation, request,
                    setup.person())).isEqualTo("decision_request_uq");
            sql.accepted(INSERT_DECISION, UUID.randomUUID(), companyId, other, request, setup.person());
            // What the server raises itself has no request id, any number of times.
            sql.accepted(INSERT_DECISION, UUID.randomUUID(), companyId, conversation, null, setup.person());
            sql.accepted(INSERT_DECISION, UUID.randomUUID(), companyId, conversation, null, setup.person());
            // A question is answered, an approval approved or denied; once (I16).
            assertThat(sql.refused("UPDATE decision SET status = 'DECIDED', outcome = 'APPROVED', decided_at = now() "
                    + "WHERE id = ?", decision)).isEqualTo("decision_outcome_ck");
            sql.accepted("UPDATE decision SET status = 'DECIDED', outcome = 'ANSWERED', answer = 'main', "
                    + "decided_at = now() WHERE id = ?", decision);
            assertThat(sql.refused("UPDATE decision SET status = 'CANCELLED', outcome = NULL, decided_at = NULL, "
                    + "cancel_cause = 'ENDED' WHERE id = ?", decision)).isEqualTo("decision_final_ck");
            // An approval names its subject, a question its question.
            assertThat(sql.refused("""
                    INSERT INTO decision (id, company_id, conversation_id, kind, subject, approver_principal_id,
                                          created_at)
                    VALUES (?, ?, ?, 'APPROVAL', '{"type": "DROP_TABLES"}', ?, now())""", UUID.randomUUID(), companyId,
                    conversation, setup.person())).isEqualTo("decision_subject_ck");
        }
    }
}
