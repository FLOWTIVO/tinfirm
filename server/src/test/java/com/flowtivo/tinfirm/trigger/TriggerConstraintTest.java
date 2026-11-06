package com.flowtivo.tinfirm.trigger;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.support.ApiClient;
import com.flowtivo.tinfirm.support.IntegrationTest;
import com.flowtivo.tinfirm.support.TenantSql;
import com.flowtivo.tinfirm.support.TestRuns;

/**
 * What the database itself refuses of triggers and their conversations (the package 4 contract, §3, §4 and §9), as the
 * runtime role: T1 (a trigger acts and pays only as its author or a service identity, never another member), T2 (one
 * account per system, a tracker trigger's source row with an identity, an account that went only while paused), T3 (the
 * author's own MCP servers only while every row is the author's), T4 (a conversation belongs to a person or a trigger,
 * its author and payer the trigger's at its start, then fixed), T5 ({@code FIXED} bindings only on a trigger's
 * conversation), T6 and the relaxed {@code run_payer_ck} (a run paid as its conversation is), T10 ({@code ONCE} at most
 * once per subject), T11 (one open conversation per trigger and subject), T12 (an approval names its team or its author,
 * a question nobody), T13 (a cause queued once, with the conversation's binding), {@code conversation_rebind} as the one
 * way past the fixed columns, the company's firing limits, and another company's rows.
 */
class TriggerConstraintTest extends IntegrationTest {

    private static final String INSERT_TRIGGER = """
            INSERT INTO trigger (id, company_id, name, source, system_id, events, agent_id, instruction,
                                 author_principal_id, payer_principal_id, armed_at, created_at, updated_at)
            VALUES (?, ?, ?, 'TRACKER', ?, '[{"event": "ASSIGNED"}]'::jsonb, ?, 'Prepare the issue.', ?, ?, now(),
                    now(), now())""";

    private static final String INSERT_ROW = """
            INSERT INTO trigger_account (company_id, trigger_id, system_id, account_id, holder_principal_id,
                                         external_id, filled_from, saved_at)
            VALUES (?, ?, ?, ?, ?, ?, 'CHOICE', now())""";

    private static final String INSERT_SYSTEM = """
            INSERT INTO external_system (id, company_id, name, handle, kind, base_url, owner_principal_id, created_at,
                                         updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, now(), now())""";

    private static final String INSERT_ACCOUNT = """
            INSERT INTO account (id, company_id, system_id, principal_id, principal_kind, kind, external_id,
                                 connected_at, updated_at)
            VALUES (?, ?, ?, ?, ?, 'LINK', ?, now(), now())""";

    private static final String INSERT_CREDENTIAL = """
            INSERT INTO account (id, company_id, system_id, principal_id, principal_kind, name, kind, secret_id,
                                 external_id, connected_at, updated_at)
            VALUES (?, ?, ?, NULL, 'SYSTEM', ?, 'TOKEN', ?, ?, now(), now())""";

    private static final String INSERT_LENDING = """
            INSERT INTO service_account_credential (company_id, principal_id, system_id, account_id, access,
                                                    created_at, updated_at)
            VALUES (?, ?, ?, ?, 'WRITE', now(), now())""";

    private static final String INSERT_CONVERSATION = """
            INSERT INTO conversation (id, company_id, agent_id, key, title, place, place_ref, origin,
                                      person_principal_id, trigger_id, author_principal_id, runtime,
                                      payer_principal_id, model_access, created_at, updated_at)
            VALUES (?, ?, ?, ?, 'SHOP-1: Fix it', ?, ?::jsonb, ?, ?, ?, ?, 'CLAUDE_CODE', ?, 'BUDGET', now(), now())""";

    private static final String INSERT_BINDING = """
            INSERT INTO conversation_binding (id, company_id, conversation_id, system_id, state, how, account_id,
                                              holder_principal_id, external_id, grant_id, bound_at, created_at,
                                              updated_at)
            VALUES (?, ?, ?, ?, 'BOUND', ?, ?, ?, ?, ?, now(), now(), now())""";

    private static final String INSERT_RUN = """
            INSERT INTO run (id, company_id, conversation_id, conversation_key, agent_id, runtime, turn_reason,
                             turn_number, acting_principal_id, payer_principal_id, model_access, status, trigger_id,
                             created_at)
            VALUES (?, ?, ?, 'shop-1-test-1', ?, 'CLAUDE_CODE', ?, 1, ?, ?, 'BUDGET', 'QUEUED', ?, now())""";

    private static final String INSERT_FIRING = """
            INSERT INTO trigger_firing (id, company_id, trigger_id, subject_id, subject_key, event, once, manual_by,
                                        outcome, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'STARTED', now())""";

    private static final String INSERT_DECISION = """
            INSERT INTO decision (id, company_id, conversation_id, kind, question, subject, approver_principal_id,
                                  approver_group_id, created_at)
            VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, now())""";

    private static final String SUBJECT = "{\"systemId\": \"%s\", \"subjectId\": \"%s\", \"key\": \"SHOP-1\"}";

    /**
     * A company: the author (a member), another member, a service identity, a group, an agent; a Jira system, a GIT
     * system and the author's own MCP server; LINK accounts of the author and the other member on Jira and GIT, and a
     * credential of each system that the service identity borrows (ADR-0072).
     */
    private record Setup(UUID company, UUID author, UUID other, UUID service, UUID team, UUID agent, UUID jira,
            UUID git, UUID mcp, UUID authorJira, UUID otherJira, UUID serviceJira, UUID authorGit, UUID serviceGit) {
    }

    private Setup setup() throws SQLException {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        UUID author = memberPrincipal(company, company.userId("MEMBER"));
        UUID other = memberPrincipal(company, company.userId("ADMIN"));
        UUID service = createServiceIdentity(owner, company, unique("svc-ba"));
        UUID team = createGroup(owner, company, unique("Analysts"), List.of(company.userId("ADMIN")));
        UUID agent = TestRuns.agent(company.id());
        UUID jira = UUID.randomUUID();
        UUID git = UUID.randomUUID();
        UUID mcp = UUID.randomUUID();
        Setup setup = new Setup(company.id(), author, other, service, team, agent, jira, git, mcp, UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        UUID secret = createSecret(owner, company.id(), "TEST_CREDENTIAL", "test-credential");
        try (TenantSql sql = TenantSql.in(company.id())) {
            sql.accepted(INSERT_SYSTEM, jira, company.id(), unique("Jira"), handle("jira"), "JIRA_DC",
                    "https://jira.example.com", null);
            sql.accepted(INSERT_SYSTEM, git, company.id(), unique("Git"), handle("git"), "GIT", null, null);
            sql.accepted(INSERT_SYSTEM, mcp, company.id(), unique("Notes"), handle("notes"), "MCP",
                    "https://notes.example.com/mcp", author);
            sql.accepted(INSERT_ACCOUNT, setup.authorJira(), company.id(), jira, author, "MEMBER", "alice");
            sql.accepted(INSERT_ACCOUNT, setup.otherJira(), company.id(), jira, other, "MEMBER", "bob");
            sql.accepted(INSERT_CREDENTIAL, setup.serviceJira(), company.id(), jira, "svc-ba", secret, "svc-ba");
            sql.accepted(INSERT_ACCOUNT, setup.authorGit(), company.id(), git, author, "MEMBER", null);
            sql.accepted(INSERT_CREDENTIAL, setup.serviceGit(), company.id(), git, "svc-ba", secret, null);
            sql.accepted(INSERT_LENDING, company.id(), service, jira, setup.serviceJira());
            sql.accepted(INSERT_LENDING, company.id(), service, git, setup.serviceGit());
            sql.commit();
        }
        return setup;
    }

    private static String handle(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    /** A tracker trigger on Jira with its source row, paid by the payer; checked at once. */
    private static UUID trigger(TenantSql sql, Setup setup, UUID payer, UUID sourceAccount, UUID holder,
            String identity) throws SQLException {
        UUID id = UUID.randomUUID();
        sql.accepted(INSERT_TRIGGER, id, setup.company(), "Prepare " + id, setup.jira(), setup.agent(),
                setup.author(), payer);
        sql.accepted(INSERT_ROW, setup.company(), id, setup.jira(), sourceAccount, holder, identity);
        commitChecks(sql);
        return id;
    }

    /** Runs the deferred checks now (T1, T2), and defers them again. */
    private static void commitChecks(TenantSql sql) throws SQLException {
        sql.update("SET CONSTRAINTS ALL IMMEDIATE");
        sql.update("SET CONSTRAINTS ALL DEFERRED");
    }

    private static String refusedAtCommit(TenantSql sql) throws SQLException {
        return sql.refused("SET CONSTRAINTS ALL IMMEDIATE");
    }

    @Test
    void aTriggerActsAndPaysOnlyAsItsAuthorOrAServiceIdentity() throws SQLException {
        Setup setup = setup();
        UUID company = setup.company();
        try (TenantSql sql = TenantSql.in(company)) {
            UUID trigger = trigger(sql, setup, setup.author(), setup.authorJira(), setup.author(), "alice");
            // T1: the payer is the author or a service identity, never another member.
            assertThat(sql.refused(INSERT_TRIGGER, UUID.randomUUID(), company, unique("Other"), setup.jira(),
                    setup.agent(), setup.author(), setup.other())).isEqualTo("trigger_payer_holder_ck");
            assertThat(sql.refused("UPDATE trigger SET payer_principal_id = ? WHERE id = ?", setup.other(), trigger))
                .isEqualTo("trigger_payer_holder_ck");
            sql.accepted("UPDATE trigger SET payer_principal_id = ? WHERE id = ?", setup.service(), trigger);
            // T1: a row holds the author's own account, or the credential a service identity borrows on the system.
            assertThat(sql.refused(INSERT_ROW, company, trigger, setup.git(), setup.authorGit(), setup.other(), null))
                .isEqualTo("trigger_account_holder_ck");
            assertThat(sql.refused(INSERT_ROW, company, trigger, setup.git(), setup.serviceGit(), setup.author(),
                    null)).isEqualTo("trigger_account_holder_ck");
            UUID notLent = UUID.randomUUID();
            sql.accepted(INSERT_CREDENTIAL, notLent, company, setup.git(), "Not lent", sql.column(
                    "SELECT secret_id FROM account WHERE id = ?", setup.serviceGit()).getFirst(), null);
            assertThat(sql.refused(INSERT_ROW, company, trigger, setup.git(), notLent, setup.service(), null))
                .isEqualTo("trigger_account_holder_ck");
            sql.accepted(INSERT_ROW, company, trigger, setup.git(), setup.serviceGit(), setup.service(), null);
            assertThat(sql.refused("UPDATE trigger_account SET account_id = ?, external_id = 'bob' WHERE trigger_id = ? "
                    + "AND system_id = ?", setup.otherJira(), trigger, setup.jira()))
                .isEqualTo("trigger_account_holder_ck");
            // T2: one account per system.
            assertThat(sql.refused(INSERT_ROW, company, trigger, setup.git(), setup.authorGit(), setup.author(), null))
                .isEqualTo("trigger_account_pk");
            commitChecks(sql);

            // A tracker trigger belongs to a company tracker system; its source, system and agent are fixed.
            assertThat(sql.refused(INSERT_TRIGGER, UUID.randomUUID(), company,
                    unique("On git"), setup.git(), setup.agent(), setup.author(), setup.author()))
                .isEqualTo("trigger_source_ck");
            assertThat(sql.refused("UPDATE trigger SET agent_id = ? WHERE id = ?", TestRuns.agent(company), trigger))
                .isIn("trigger_fixed_ck", "trigger_id_agent_uq");
            assertThat(sql.refused("UPDATE trigger SET system_id = ? WHERE id = ?", setup.git(), trigger))
                .isEqualTo("trigger_fixed_ck");
            // A tracker trigger has one to five events and no schedule; a name among the live ones once.
            assertThat(sql.refused("UPDATE trigger SET events = '[]'::jsonb WHERE id = ?", trigger))
                .isEqualTo("trigger_shape_ck");
            assertThat(sql.refused("UPDATE trigger SET schedule_cron = '0 9 * * *' WHERE id = ?", trigger))
                .isEqualTo("trigger_shape_ck");
            assertThat(sql.refused(INSERT_TRIGGER, UUID.randomUUID(), company, ("PREPARE " + trigger).toLowerCase(),
                    setup.jira(), setup.agent(), setup.author(), setup.author())).isEqualTo("trigger_name_uq");
            // Paused with its reason; ACCOUNT_BROKEN among them (§11).
            assertThat(sql.refused("UPDATE trigger SET status = 'PAUSED' WHERE id = ?", trigger))
                .isEqualTo("trigger_paused_ck");
            sql.accepted("UPDATE trigger SET status = 'PAUSED', paused_reason = 'ACCOUNT_BROKEN', paused_at = now() "
                    + "WHERE id = ?", trigger);
            // Never removed; a deleted trigger stays deleted and frees its name.
            assertThat(sql.refused("DELETE FROM trigger WHERE id = ?", trigger)).contains("permission denied");
            sql.accepted("UPDATE trigger SET deleted_at = now(), deleted_cause = 'USER' WHERE id = ?", trigger);
            assertThat(sql.refused("UPDATE trigger SET deleted_at = NULL, deleted_cause = NULL WHERE id = ?",
                    trigger)).isEqualTo("trigger_deleted_final_ck");
            sql.accepted(INSERT_TRIGGER, UUID.randomUUID(), company, "Prepare " + trigger, setup.jira(), setup.agent(),
                    setup.author(), setup.author());
        }
    }

    @Test
    void aTrackerTriggerHasAnIdentityOnItsSourceAndAnAccountThatWentOnlyWhilePaused() throws SQLException {
        Setup setup = setup();
        UUID company = setup.company();
        // T2: without its source row, at the commit.
        try (TenantSql sql = TenantSql.in(company)) {
            sql.accepted(INSERT_TRIGGER, UUID.randomUUID(), company, unique("No source"), setup.jira(), setup.agent(),
                    setup.author(), setup.author());
            assertThat(refusedAtCommit(sql)).isEqualTo("trigger_source_account_ck");
        }
        // A source row needs an identity.
        try (TenantSql sql = TenantSql.in(company)) {
            UUID trigger = UUID.randomUUID();
            sql.accepted(INSERT_TRIGGER, trigger, company, unique("No identity"), setup.jira(), setup.agent(),
                    setup.author(), setup.author());
            sql.accepted(INSERT_ROW, company, trigger, setup.jira(), setup.authorJira(), setup.author(), null);
            assertThat(refusedAtCommit(sql)).isEqualTo("trigger_source_account_ck");
        }
        // The account goes (ON DELETE SET NULL): only a paused trigger keeps a row without one.
        try (TenantSql sql = TenantSql.in(company)) {
            UUID trigger = trigger(sql, setup, setup.service(), setup.serviceJira(), setup.service(), "svc-ba");
            sql.accepted("UPDATE trigger_account SET account_id = NULL WHERE trigger_id = ?", trigger);
            assertThat(refusedAtCommit(sql)).isEqualTo("trigger_account_paused_ck");
            sql.accepted("UPDATE trigger SET status = 'PAUSED', paused_reason = 'ACCOUNT_REMOVED', paused_at = now() "
                    + "WHERE id = ?", trigger);
            commitChecks(sql);
            assertThat(sql.count("SELECT count(*) FROM trigger_account WHERE trigger_id = ? AND account_id IS NULL "
                    + "AND external_id = 'svc-ba'", trigger)).isEqualTo(1);
            // Resumed, it needs the account again.
            sql.accepted("UPDATE trigger SET status = 'ACTIVE', paused_reason = NULL, paused_at = NULL WHERE id = ?",
                    trigger);
            assertThat(refusedAtCommit(sql)).isEqualTo("trigger_account_paused_ck");
        }
        // A deleted account empties its rows.
        try (TenantSql sql = TenantSql.in(company)) {
            UUID trigger = trigger(sql, setup, setup.service(), setup.serviceJira(), setup.service(), "svc-ba");
            sql.accepted("UPDATE trigger SET status = 'PAUSED', paused_reason = 'ACCOUNT_REMOVED', paused_at = now() "
                    + "WHERE id = ?", trigger);
            sql.accepted("DELETE FROM account WHERE id = ?", setup.serviceJira());
            commitChecks(sql);
            assertThat(sql.count("SELECT count(*) FROM trigger_account WHERE trigger_id = ? AND account_id IS NULL",
                    trigger)).isEqualTo(1);
        }
        // T1 over the table: a reassignment changes the author and the rows together.
        try (TenantSql sql = TenantSql.in(company)) {
            UUID trigger = trigger(sql, setup, setup.author(), setup.authorJira(), setup.author(), "alice");
            sql.accepted("UPDATE trigger SET author_principal_id = ?, payer_principal_id = ?, status = 'PAUSED', "
                    + "paused_reason = 'AUTHOR_LEFT', paused_at = now() WHERE id = ?", setup.other(), setup.other(),
                    trigger);
            assertThat(refusedAtCommit(sql)).isEqualTo("trigger_account_holder_ck");
            sql.accepted("UPDATE trigger_account SET account_id = ?, holder_principal_id = ?, external_id = 'bob' "
                    + "WHERE trigger_id = ?", setup.otherJira(), setup.other(), trigger);
            commitChecks(sql);
        }
    }

    @Test
    void aTriggersSettingsAddTheAuthorsOwnServersOnlyWhileEveryRowIsTheAuthors() throws SQLException {
        Setup setup = setup();
        UUID company = setup.company();
        String insertSettings = """
                INSERT INTO agent_settings (id, company_id, agent_id, principal_id, trigger_id, accepted_version,
                                            created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 1, now(), now())""";
        String insertSystem = "INSERT INTO agent_settings_system (company_id, settings_id, system_id, state) "
                + "VALUES (?, ?, ?, 'ADDED')";
        try (TenantSql sql = TenantSql.in(company)) {
            UUID trigger = trigger(sql, setup, setup.author(), setup.authorJira(), setup.author(), "alice");
            // Exactly one owner, and the trigger's agent.
            assertThat(sql.refused(insertSettings, UUID.randomUUID(), company, setup.agent(), setup.author(), trigger))
                .isEqualTo("agent_settings_owner_ck");
            assertThat(sql.refused(insertSettings, UUID.randomUUID(), company, setup.agent(), null, null))
                .isEqualTo("agent_settings_owner_ck");
            assertThat(sql.refused(insertSettings, UUID.randomUUID(), company, TestRuns.agent(company), null,
                    trigger)).isEqualTo("agent_settings_trigger_fk");
            UUID settings = UUID.randomUUID();
            sql.accepted(insertSettings, settings, company, setup.agent(), null, trigger);
            assertThat(sql.refused(insertSettings, UUID.randomUUID(), company, setup.agent(), null, trigger))
                .isEqualTo("agent_settings_trigger_uq");
            // T3: the author's own MCP server while every row is the author's own account.
            sql.accepted(insertSystem, company, settings, setup.mcp());
            assertThat(sql.refused(INSERT_ROW, company, trigger, setup.git(), setup.serviceGit(), setup.service(),
                    null)).isEqualTo("agent_settings_widen_ck");
            sql.accepted("DELETE FROM agent_settings_system WHERE settings_id = ?", settings);
            sql.accepted(INSERT_ROW, company, trigger, setup.git(), setup.serviceGit(), setup.service(), null);
            assertThat(sql.refused(insertSystem, company, settings, setup.mcp())).isEqualTo("agent_settings_widen_ck");
        }
    }

    @Test
    void aConversationBelongsToAPersonOrToATriggerWhoseAuthorAndPayerItKeeps() throws SQLException {
        Setup setup = setup();
        UUID company = setup.company();
        try (TenantSql sql = TenantSql.in(company)) {
            UUID trigger = trigger(sql, setup, setup.service(), setup.serviceJira(), setup.service(), "svc-ba");
            String place = SUBJECT.formatted(setup.jira(), "10001");
            // T4: never both owners; a trigger's has its author, the TRIGGER origin and a trigger's place.
            assertThat(sql.refused(INSERT_CONVERSATION, UUID.randomUUID(), company, setup.agent(), "shop-1-a-1",
                    "TRACKER_ISSUE", place, "TRIGGER", setup.author(), trigger, setup.author(), setup.service()))
                .isEqualTo("conversation_payer_ck");
            assertThat(sql.refused(INSERT_CONVERSATION, UUID.randomUUID(), company, setup.agent(), "shop-1-a-1",
                    "TRACKER_ISSUE", place, "APP", null, trigger, setup.author(), setup.service()))
                .isEqualTo("conversation_payer_ck");
            assertThat(sql.refused(INSERT_CONVERSATION, UUID.randomUUID(), company, setup.agent(), "shop-1-a-1",
                    "TINFIRM_THREAD", null, "TRIGGER", null, trigger, setup.author(), setup.service()))
                .isEqualTo("conversation_payer_ck");
            // A personal one is paid by its person (I10).
            assertThat(sql.refused(INSERT_CONVERSATION, UUID.randomUUID(), company, setup.agent(), "mine-1",
                    "TINFIRM_THREAD", null, "APP", setup.author(), null, null, setup.service()))
                .isEqualTo("conversation_payer_ck");
            // T4: the trigger's author and payer at its start.
            assertThat(sql.refused(INSERT_CONVERSATION, UUID.randomUUID(), company, setup.agent(), "shop-1-a-1",
                    "TRACKER_ISSUE", place, "TRIGGER", null, trigger, setup.author(), setup.author()))
                .isEqualTo("conversation_trigger_ck");
            assertThat(sql.refused(INSERT_CONVERSATION, UUID.randomUUID(), company, setup.agent(), "shop-1-a-1",
                    "TRACKER_ISSUE", place, "TRIGGER", null, trigger, setup.other(), setup.service()))
                .isEqualTo("conversation_trigger_ck");
            assertThat(sql.refused(INSERT_CONVERSATION, UUID.randomUUID(), company, TestRuns.agent(company),
                    "shop-1-a-1", "TRACKER_ISSUE", place, "TRIGGER", null, trigger, setup.author(), setup.service()))
                .isEqualTo("conversation_trigger_fk");
            UUID conversation = UUID.randomUUID();
            sql.accepted(INSERT_CONVERSATION, conversation, company, setup.agent(), "shop-1-a-1", "TRACKER_ISSUE",
                    place, "TRIGGER", null, trigger, setup.author(), setup.service());
            // T11: one open conversation per trigger and subject.
            assertThat(sql.refused(INSERT_CONVERSATION, UUID.randomUUID(), company, setup.agent(), "shop-1-a-2",
                    "TRACKER_ISSUE", place, "TRIGGER", null, trigger, setup.author(), setup.service()))
                .isEqualTo("conversation_trigger_subject_open_uq");
            sql.accepted(INSERT_CONVERSATION, UUID.randomUUID(), company, setup.agent(), "shop-2-a-1",
                    "TRACKER_ISSUE", SUBJECT.formatted(setup.jira(), "10002"), "TRIGGER", null, trigger,
                    setup.author(), setup.service());
            // T4: then fixed: the trigger, the author, the payer, the place's subject, the chain depth.
            for (String change : List.of("author_principal_id = '" + setup.other() + "'",
                    "payer_principal_id = '" + setup.author() + "'", "trigger_id = NULL", "chain_depth = 1",
                    "place_ref = '" + SUBJECT.formatted(setup.jira(), "10003") + "'::jsonb")) {
                assertThat(sql.refused("UPDATE conversation SET " + change + " WHERE id = ?", conversation))
                    .as(change).isIn("conversation_fixed_ck", "conversation_payer_ck");
            }
            // Spoofing the rebind's setting passes nothing: only conversation_rebind, as the schema's owner, does.
            sql.column("SELECT set_config('tinfirm.conversation_rebind', ?, true)", conversation.toString());
            assertThat(sql.refused("UPDATE conversation SET payer_principal_id = ? WHERE id = ?", setup.author(),
                    conversation)).isEqualTo("conversation_fixed_ck");
            // Its end frees the subject; the new reasons of an end.
            sql.accepted("UPDATE conversation SET state = 'ENDED', ended_reason = 'SUBJECT_CLOSED', ended_at = now() "
                    + "WHERE id = ?", conversation);
            sql.accepted(INSERT_CONVERSATION, UUID.randomUUID(), company, setup.agent(), "shop-1-a-3",
                    "TRACKER_ISSUE", place, "TRIGGER", null, trigger, setup.author(), setup.service());
        }
    }

    @Test
    void aTriggersConversationBindsOnlyFixedAccountsOfItsAuthorOrAServiceIdentity() throws SQLException {
        Setup setup = setup();
        UUID company = setup.company();
        try (TenantSql sql = TenantSql.in(company)) {
            UUID trigger = trigger(sql, setup, setup.author(), setup.authorJira(), setup.author(), "alice");
            UUID conversation = UUID.randomUUID();
            sql.accepted(INSERT_CONVERSATION, conversation, company, setup.agent(), "shop-1-b-1", "TRACKER_ISSUE",
                    SUBJECT.formatted(setup.jira(), "10001"), "TRIGGER", null, trigger, setup.author(),
                    setup.author());
            UUID personal = UUID.randomUUID();
            sql.accepted(INSERT_CONVERSATION, personal, company, setup.agent(), "mine-b-1", "TINFIRM_THREAD", null,
                    "APP", setup.author(), null, null, setup.author());
            // T5: FIXED only on a trigger's conversation, and nothing else there.
            assertThat(sql.refused(INSERT_BINDING, UUID.randomUUID(), company, personal, setup.jira(), "FIXED",
                    setup.authorJira(), setup.author(), "alice", null)).isEqualTo("conversation_binding_fixed_how_ck");
            assertThat(sql.refused(INSERT_BINDING, UUID.randomUUID(), company, conversation, setup.jira(), "OWN",
                    setup.authorJira(), setup.author(), "alice", null)).isEqualTo("conversation_binding_fixed_how_ck");
            assertThat(sql.refused("""
                    INSERT INTO conversation_binding (id, company_id, conversation_id, system_id, state, reason,
                                                      waiting_since, created_at, updated_at)
                    VALUES (?, ?, ?, ?, 'WAITING', 'NONE', now(), now(), now())""", UUID.randomUUID(), company,
                    conversation, setup.git())).isEqualTo("conversation_binding_fixed_how_ck");
            // T1: never another member's account; the author's own or a service identity's, without a grant.
            assertThat(sql.refused(INSERT_BINDING, UUID.randomUUID(), company, conversation, setup.jira(), "FIXED",
                    setup.otherJira(), setup.other(), "bob", null)).isEqualTo("conversation_binding_holder_ck");
            UUID jiraBinding = UUID.randomUUID();
            sql.accepted(INSERT_BINDING, jiraBinding, company, conversation, setup.jira(), "FIXED",
                    setup.authorJira(), setup.author(), "alice", null);
            UUID gitBinding = UUID.randomUUID();
            sql.accepted(INSERT_BINDING, gitBinding, company, conversation, setup.git(), "FIXED", setup.serviceGit(),
                    setup.service(), null, null);
            // Fixed once bound; it waits NOT_ENTITLED, and a gone one stays gone.
            assertThat(sql.refused("UPDATE conversation_binding SET account_id = ?, holder_principal_id = ?, "
                    + "external_id = 'svc-ba' WHERE id = ?", setup.serviceJira(), setup.service(), jiraBinding))
                .isEqualTo("conversation_binding_fixed_account_ck");
            sql.accepted("UPDATE conversation_binding SET state = 'WAITING', reason = 'NOT_ENTITLED', "
                    + "waiting_since = now() WHERE id = ?", gitBinding);
            sql.accepted("UPDATE conversation_binding SET state = 'GONE', reason = 'ACCOUNT_REMOVED', "
                    + "waiting_since = NULL WHERE id = ?", gitBinding);
            assertThat(sql.refused("UPDATE conversation_binding SET state = 'BOUND', reason = NULL WHERE id = ?",
                    gitBinding)).isEqualTo("conversation_binding_gone_ck");
        }
    }

    @Test
    void aRunIsPaidAsItsConversationIs() throws SQLException {
        Setup setup = setup();
        UUID company = setup.company();
        try (TenantSql sql = TenantSql.in(company)) {
            UUID trigger = trigger(sql, setup, setup.service(), setup.serviceJira(), setup.service(), "svc-ba");
            UUID conversation = UUID.randomUUID();
            sql.accepted(INSERT_CONVERSATION, conversation, company, setup.agent(), "shop-1-c-1", "TRACKER_ISSUE",
                    SUBJECT.formatted(setup.jira(), "10001"), "TRIGGER", null, trigger, setup.author(),
                    setup.service());
            UUID personal = UUID.randomUUID();
            sql.accepted(INSERT_CONVERSATION, personal, company, setup.agent(), "mine-c-1", "TINFIRM_THREAD", null,
                    "APP", setup.author(), null, null, setup.author());
            // The relaxed I10: a personal run by its person; a trigger's run only by its conversation's payer (T6).
            assertThat(sql.refused(INSERT_RUN, UUID.randomUUID(), company, personal, setup.agent(), "FIRST",
                    setup.author(), setup.service(), null)).isEqualTo("run_payer_ck");
            assertThat(sql.refused(INSERT_RUN, UUID.randomUUID(), company, personal, setup.agent(), "FIRST",
                    setup.author(), setup.author(), trigger)).isEqualTo("run_conversation_ck");
            assertThat(sql.refused(INSERT_RUN, UUID.randomUUID(), company, conversation, setup.agent(), "FIRST",
                    setup.author(), setup.service(), null)).isEqualTo("run_payer_ck");
            assertThat(sql.refused(INSERT_RUN, UUID.randomUUID(), company, conversation, setup.agent(), "FIRST",
                    setup.author(), setup.author(), trigger)).isEqualTo("run_conversation_ck");
            assertThat(sql.refused(INSERT_RUN, UUID.randomUUID(), company, conversation, setup.agent(), "FIRST",
                    setup.other(), setup.service(), trigger)).isEqualTo("run_conversation_ck");
            UUID run = UUID.randomUUID();
            sql.accepted(INSERT_RUN, run, company, conversation, setup.agent(), "EVENT", setup.author(),
                    setup.service(), trigger);
            sql.accepted(INSERT_RUN, UUID.randomUUID(), company, personal, setup.agent(), "FIRST", setup.author(),
                    setup.author(), null);
            // The new reasons: EVENT, REVIEW_RETURNED; a paused trigger's turn waits TRIGGER_PAUSED.
            sql.accepted("UPDATE run SET queue_reason = 'TRIGGER_PAUSED', queue_reason_since = now() WHERE id = ?",
                    run);
            assertThat(sql.refused(INSERT_RUN, UUID.randomUUID(), company, conversation, setup.agent(), "LATER",
                    setup.author(), setup.service(), trigger)).isEqualTo("run_turn_reason_ck");
        }
    }

    @Test
    void aOnceTriggerFiresOncePerSubjectAndRunNowNeverCountsAsOnce() throws SQLException {
        Setup setup = setup();
        UUID company = setup.company();
        try (TenantSql sql = TenantSql.in(company)) {
            UUID trigger = trigger(sql, setup, setup.author(), setup.authorJira(), setup.author(), "alice");
            sql.accepted(INSERT_FIRING, UUID.randomUUID(), company, trigger, "10001", "SHOP-1", "ASSIGNED", true,
                    null);
            // T10: ONCE at most once per trigger and subject, across replicas.
            assertThat(sql.refused(INSERT_FIRING, UUID.randomUUID(), company, trigger, "10001", "SHOP-1",
                    "LABEL_ADDED", true, null)).isEqualTo("trigger_firing_once_uq");
            sql.accepted(INSERT_FIRING, UUID.randomUUID(), company, trigger, "10002", "SHOP-2", "ASSIGNED", true,
                    null);
            sql.accepted(INSERT_FIRING, UUID.randomUUID(), company, trigger, "10001", "SHOP-1", "COMMENTED", false,
                    null);
            UUID user = (UUID) sql.column("SELECT user_id FROM principal WHERE id = ?", setup.author()).getFirst();
            assertThat(sql.refused(INSERT_FIRING, UUID.randomUUID(), company, trigger, "10003", "SHOP-3", "ASSIGNED",
                    true, user)).isEqualTo("trigger_firing_manual_ck");
            // A subject's firing names its subject; a schedule's none.
            assertThat(sql.refused(INSERT_FIRING, UUID.randomUUID(), company, trigger, null, null, "ASSIGNED", false,
                    null)).isEqualTo("trigger_firing_subject_ck");
            assertThat(sql.refused(INSERT_FIRING, UUID.randomUUID(), company, trigger, "10001", "SHOP-1", "SCHEDULE",
                    false, null)).isEqualTo("trigger_firing_subject_ck");
        }
    }

    @Test
    void anApprovalOfATriggersConversationNamesItsTeamOrItsAuthorAndAQuestionNobody() throws SQLException {
        Setup setup = setup();
        UUID company = setup.company();
        try (TenantSql sql = TenantSql.in(company)) {
            UUID trigger = trigger(sql, setup, setup.author(), setup.authorJira(), setup.author(), "alice");
            UUID conversation = UUID.randomUUID();
            sql.accepted(INSERT_CONVERSATION, conversation, company, setup.agent(), "shop-1-d-1", "TRACKER_ISSUE",
                    SUBJECT.formatted(setup.jira(), "10001"), "TRIGGER", null, trigger, setup.author(),
                    setup.author());
            UUID personal = UUID.randomUUID();
            sql.accepted(INSERT_CONVERSATION, personal, company, setup.agent(), "mine-d-1", "TINFIRM_THREAD", null,
                    "APP", setup.author(), null, null, setup.author());
            String result = "{\"type\": \"RESULT\", \"runId\": \"" + UUID.randomUUID() + "\", \"summary\": \"Done\"}";
            // T12: a question of a trigger's conversation names nobody: anyone in the place answers.
            assertThat(sql.refused(INSERT_DECISION, UUID.randomUUID(), company, conversation, "QUESTION", "Which?",
                    null, setup.author(), null)).isEqualTo("decision_conversation_approver_ck");
            sql.accepted(INSERT_DECISION, UUID.randomUUID(), company, conversation, "QUESTION", "Which?", null, null,
                    null);
            // An approval names exactly one: the team, or the author without one.
            assertThat(sql.refused(INSERT_DECISION, UUID.randomUUID(), company, conversation, "APPROVAL", null,
                    result, null, null)).isEqualTo("decision_approver_ck");
            assertThat(sql.refused(INSERT_DECISION, UUID.randomUUID(), company, conversation, "APPROVAL", null,
                    result, setup.author(), setup.team())).isEqualTo("decision_approver_ck");
            sql.accepted(INSERT_DECISION, UUID.randomUUID(), company, conversation, "APPROVAL", null, result, null,
                    setup.team());
            sql.accepted(INSERT_DECISION, UUID.randomUUID(), company, conversation, "APPROVAL", null, result,
                    setup.author(), null);
            // A personal conversation's decisions name its person, never a group.
            assertThat(sql.refused(INSERT_DECISION, UUID.randomUUID(), company, personal, "APPROVAL", null, result,
                    null, setup.team())).isEqualTo("decision_conversation_approver_ck");
            assertThat(sql.refused(INSERT_DECISION, UUID.randomUUID(), company, personal, "QUESTION", "Which?", null,
                    setup.other(), null)).isEqualTo("decision_conversation_approver_ck");
            assertThat(sql.refused(INSERT_DECISION, UUID.randomUUID(), company, personal, "APPROVAL", null,
                    "{\"type\": \"OTHER\"}", setup.author(), null)).isEqualTo("decision_subject_ck");

            // EXTERNAL messages: a comment of the place, once per comment id.
            String insertMessage = "INSERT INTO message (id, company_id, conversation_id, kind, body, external_id, "
                    + "external_author, created_at) VALUES (?, ?, ?, 'EXTERNAL', 'Use the staging branch.', ?, ?::jsonb, "
                    + "now())";
            assertThat(sql.refused(insertMessage, UUID.randomUUID(), company, conversation, "c-1", null))
                .isEqualTo("message_author_ck");
            sql.accepted(insertMessage, UUID.randomUUID(), company, conversation, "c-1",
                    "{\"id\": \"u-9\", \"login\": \"carol\", \"name\": \"Carol\"}");
            assertThat(sql.refused(insertMessage, UUID.randomUUID(), company, conversation, "c-1",
                    "{\"id\": \"u-9\"}")).isEqualTo("message_external_uq");
            assertThat(sql.refused("INSERT INTO message (id, company_id, conversation_id, kind, body, external_id, "
                    + "created_at) VALUES (?, ?, ?, 'SYSTEM', 'Note', 'c-2', now())", UUID.randomUUID(), company,
                    conversation)).isEqualTo("message_author_ck");
        }
    }

    @Test
    void theOutboxQueuesACauseOnceWithItsConversationsBindingAndDeliversItOnce() throws SQLException {
        Setup setup = setup();
        UUID company = setup.company();
        String insert = """
                INSERT INTO outbox (id, company_id, conversation_id, system_id, binding_id, subject_key, seq, kind,
                                    cause, cause_key, body, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 'SHOP-1', ?, 'COMMENT', 'RESULT', ?, 'Done.', now(), now())""";
        try (TenantSql sql = TenantSql.in(company)) {
            UUID trigger = trigger(sql, setup, setup.author(), setup.authorJira(), setup.author(), "alice");
            UUID conversation = UUID.randomUUID();
            sql.accepted(INSERT_CONVERSATION, conversation, company, setup.agent(), "shop-1-e-1", "TRACKER_ISSUE",
                    SUBJECT.formatted(setup.jira(), "10001"), "TRIGGER", null, trigger, setup.author(),
                    setup.author());
            UUID other = UUID.randomUUID();
            sql.accepted(INSERT_CONVERSATION, other, company, setup.agent(), "shop-2-e-1", "TRACKER_ISSUE",
                    SUBJECT.formatted(setup.jira(), "10002"), "TRIGGER", null, trigger, setup.author(),
                    setup.author());
            UUID binding = UUID.randomUUID();
            sql.accepted(INSERT_BINDING, binding, company, conversation, setup.jira(), "FIXED", setup.authorJira(),
                    setup.author(), "alice", null);
            UUID othersBinding = UUID.randomUUID();
            sql.accepted(INSERT_BINDING, othersBinding, company, other, setup.jira(), "FIXED", setup.authorJira(),
                    setup.author(), "alice", null);
            UUID row = UUID.randomUUID();
            sql.accepted(insert, row, company, conversation, setup.jira(), binding, 1, "RESULT:run-1");
            // T13: a cause queued twice queues once; written with the conversation's binding on its system.
            assertThat(sql.refused(insert, UUID.randomUUID(), company, conversation, setup.jira(), binding, 2,
                    "RESULT:run-1")).isEqualTo("outbox_cause_uq");
            assertThat(sql.refused(insert, UUID.randomUUID(), company, conversation, setup.jira(), othersBinding, 2,
                    "RESULT:run-2")).isEqualTo("outbox_binding_ck");
            // Delivered once.
            sql.accepted("UPDATE outbox SET status = 'DELIVERED', delivered_at = now(), external_id = '100' "
                    + "WHERE id = ?", row);
            assertThat(sql.refused("UPDATE outbox SET status = 'PENDING', delivered_at = NULL WHERE id = ?", row))
                .isEqualTo("outbox_final_ck");
            assertThat(sql.refused("UPDATE outbox SET body = 'Other' WHERE id = ?", row)).isEqualTo("outbox_fixed_ck");
            assertThat(sql.refused("DELETE FROM outbox WHERE id = ?", row)).contains("permission denied");
        }
    }

    @Test
    void anMcpServerThatNeedsNoAccountIsBoundOpenAndARebindLeavesItSo() throws SQLException {
        Setup setup = setup();
        UUID company = setup.company();
        try (TenantSql sql = TenantSql.in(company)) {
            UUID crm = UUID.randomUUID();
            sql.accepted(INSERT_SYSTEM, crm, company, unique("CRM"), handle("crm"), "MCP",
                    "https://crm.example.com/mcp", null);
            UUID trigger = trigger(sql, setup, setup.author(), setup.authorJira(), setup.author(), "alice");
            sql.accepted("""
                    INSERT INTO agent_settings (id, company_id, agent_id, trigger_id, accepted_version, created_at,
                                                updated_at)
                    VALUES (?, ?, ?, ?, 1, now(), now())""", UUID.randomUUID(), company, setup.agent(), trigger);
            UUID conversation = UUID.randomUUID();
            sql.accepted(INSERT_CONVERSATION, conversation, company, setup.agent(), "shop-1-o-1", "TRACKER_ISSUE",
                    SUBJECT.formatted(setup.jira(), "10001"), "TRIGGER", null, trigger, setup.author(),
                    setup.author());
            UUID personal = UUID.randomUUID();
            sql.accepted(INSERT_CONVERSATION, personal, company, setup.agent(), "mine-o-1", "TINFIRM_THREAD", null,
                    "APP", setup.author(), null, null, setup.author());
            commitChecks(sql);

            // Only an MCP server needs no account, and only once it is set so.
            assertThat(sql.refused("UPDATE external_system SET accounts_required = false WHERE id = ?", setup.jira()))
                .isEqualTo("external_system_accounts_required_ck");
            assertThat(sql.refused(INSERT_BINDING, UUID.randomUUID(), company, conversation, crm, "OPEN", null, null,
                    null, null)).isEqualTo("conversation_binding_open_ck");
            sql.accepted("UPDATE external_system SET accounts_required = false WHERE id = ?", crm);
            // OPEN names no account, holder, identity or grant; a trigger's conversation and a personal one bind so.
            UUID authorCrm = UUID.randomUUID();
            sql.accepted(INSERT_ACCOUNT, authorCrm, company, crm, setup.author(), "MEMBER", "alice");
            assertThat(sql.refused(INSERT_BINDING, UUID.randomUUID(), company, personal, crm, "OPEN", authorCrm,
                    setup.author(), "alice", null)).isEqualTo("conversation_binding_how_ck");
            UUID open = UUID.randomUUID();
            sql.accepted(INSERT_BINDING, open, company, conversation, crm, "OPEN", null, null, null, null);
            UUID mine = UUID.randomUUID();
            sql.accepted(INSERT_BINDING, mine, company, personal, crm, "OPEN", null, null, null, null);

            // Needing accounts again: the binding waits as it is, and binds OPEN again only once the system needs none.
            sql.accepted("UPDATE external_system SET accounts_required = true WHERE id = ?", crm);
            sql.accepted("UPDATE conversation_binding SET state = 'WAITING', reason = 'NONE', waiting_since = now() "
                    + "WHERE id = ?", mine);
            assertThat(sql.refused("UPDATE conversation_binding SET state = 'BOUND', reason = NULL, "
                    + "waiting_since = NULL WHERE id = ?", mine)).isEqualTo("conversation_binding_open_ck");
            sql.accepted("UPDATE external_system SET accounts_required = false WHERE id = ?", crm);
            sql.accepted("UPDATE conversation_binding SET state = 'BOUND', reason = NULL, waiting_since = NULL "
                    + "WHERE id = ?", mine);

            // A rebind leaves the OPEN binding: it came from no row of the trigger's table.
            String changed = String.valueOf(sql.column("SELECT conversation_rebind(?)::text", conversation)
                .getFirst());
            assertThat(changed).doesNotContain(crm.toString());
            assertThat(sql.rows("SELECT state, how, account_id FROM conversation_binding WHERE id = ?", open)
                .getFirst()).containsEntry("state", "BOUND").containsEntry("how", "OPEN")
                .containsEntry("account_id", null);
        }
    }

    @Test
    void conversationRebindIsTheOneWayToAReassignedTriggersNewTableAndPayer() throws SQLException {
        Setup setup = setup();
        UUID company = setup.company();
        try (TenantSql sql = TenantSql.in(company)) {
            UUID trigger = trigger(sql, setup, setup.author(), setup.authorJira(), setup.author(), "alice");
            sql.accepted(INSERT_ROW, company, trigger, setup.git(), setup.authorGit(), setup.author(), null);
            sql.accepted("""
                    INSERT INTO agent_settings (id, company_id, agent_id, trigger_id, accepted_version, created_at,
                                                updated_at)
                    VALUES (?, ?, ?, ?, 1, now(), now())""", UUID.randomUUID(), company, setup.agent(), trigger);
            UUID conversation = UUID.randomUUID();
            sql.accepted(INSERT_CONVERSATION, conversation, company, setup.agent(), "shop-1-f-1", "TRACKER_ISSUE",
                    SUBJECT.formatted(setup.jira(), "10001"), "TRIGGER", null, trigger, setup.author(),
                    setup.author());
            UUID jiraBinding = UUID.randomUUID();
            sql.accepted(INSERT_BINDING, jiraBinding, company, conversation, setup.jira(), "FIXED",
                    setup.authorJira(), setup.author(), "alice", null);
            UUID gitBinding = UUID.randomUUID();
            sql.accepted(INSERT_BINDING, gitBinding, company, conversation, setup.git(), "FIXED", setup.authorGit(),
                    setup.author(), null, null);
            commitChecks(sql);
            // A paused trigger's conversation is not rebound.
            sql.accepted("UPDATE trigger SET status = 'PAUSED', paused_reason = 'AUTHOR_LEFT', paused_at = now() "
                    + "WHERE id = ?", trigger);
            assertThat(sql.refused("SELECT conversation_rebind(?)", conversation)).isEqualTo("conversation_rebind_ck");
            // Reassigned to the other member with the service identity's Jira account and budget, then resumed.
            sql.accepted("UPDATE trigger SET author_principal_id = ?, payer_principal_id = ? WHERE id = ?",
                    setup.other(), setup.service(), trigger);
            sql.accepted("UPDATE trigger_account SET account_id = ?, holder_principal_id = ?, external_id = 'svc-ba' "
                    + "WHERE trigger_id = ? AND system_id = ?", setup.serviceJira(), setup.service(), trigger,
                    setup.jira());
            sql.accepted("DELETE FROM trigger_account WHERE trigger_id = ? AND system_id = ?", trigger, setup.git());
            sql.accepted("UPDATE trigger SET status = 'ACTIVE', paused_reason = NULL, paused_at = NULL, "
                    + "armed_at = now() WHERE id = ?", trigger);
            commitChecks(sql);
            // The runtime role cannot do it by hand.
            assertThat(sql.refused("UPDATE conversation SET author_principal_id = ?, payer_principal_id = ? "
                    + "WHERE id = ?", setup.other(), setup.service(), conversation)).isEqualTo("conversation_fixed_ck");
            assertThat(sql.refused("UPDATE conversation_binding SET account_id = ?, holder_principal_id = ?, "
                    + "external_id = 'svc-ba' WHERE id = ?", setup.serviceJira(), setup.service(), jiraBinding))
                .isEqualTo("conversation_binding_fixed_account_ck");
            // conversation_rebind does, and says what changed.
            String changed = String.valueOf(sql.column("SELECT conversation_rebind(?)::text", conversation)
                .getFirst());
            assertThat(changed).contains("\"from\": \"" + setup.author() + "\"")
                .contains("\"to\": \"" + setup.service() + "\"")
                .contains("\"state\": \"GONE\"");
            List<Map<String, Object>> after = sql.rows("SELECT author_principal_id, payer_principal_id FROM "
                    + "conversation WHERE id = ?", conversation);
            assertThat(after.getFirst()).containsEntry("author_principal_id", setup.other())
                .containsEntry("payer_principal_id", setup.service());
            assertThat(sql.rows("SELECT state, account_id, holder_principal_id FROM conversation_binding "
                    + "WHERE id = ?", jiraBinding).getFirst())
                .containsEntry("state", "BOUND")
                .containsEntry("account_id", setup.serviceJira())
                .containsEntry("holder_principal_id", setup.service());
            assertThat(sql.rows("SELECT state, reason FROM conversation_binding WHERE id = ?", gitBinding).getFirst())
                .containsEntry("state", "GONE")
                .containsEntry("reason", "ACCOUNT_REMOVED");
            // Its setting does not outlive it: a later change is refused again.
            assertThat(sql.refused("UPDATE conversation SET payer_principal_id = ? WHERE id = ?", setup.other(),
                    conversation)).isEqualTo("conversation_fixed_ck");
        }
    }

    @Test
    void aServiceAccountsBindingFollowsOnlyTheCredentialItBorrowsNow() throws SQLException {
        Setup setup = setup();
        UUID company = setup.company();
        try (TenantSql sql = TenantSql.in(company)) {
            UUID trigger = trigger(sql, setup, setup.author(), setup.serviceJira(), setup.service(), "svc-ba");
            UUID conversation = UUID.randomUUID();
            sql.accepted(INSERT_CONVERSATION, conversation, company, setup.agent(), "shop-1-f-1", "TRACKER_ISSUE",
                    SUBJECT.formatted(setup.jira(), "10001"), "TRIGGER", null, trigger, setup.author(),
                    setup.author());
            UUID binding = UUID.randomUUID();
            sql.accepted(INSERT_BINDING, binding, company, conversation, setup.jira(), "FIXED", setup.serviceJira(),
                    setup.service(), "svc-ba", null);
            UUID secret = UUID.fromString(String.valueOf(sql.column("SELECT secret_id FROM account WHERE id = ?",
                    setup.serviceJira()).getFirst()));
            UUID other = UUID.randomUUID();
            sql.accepted(INSERT_CREDENTIAL, other, company, setup.jira(), "svc-two", secret, "svc-two");
            String follow = "UPDATE conversation_binding SET account_id = ?, external_id = ? WHERE id = ?";
            // Not lent to the service account: the binding stays fixed.
            assertThat(sql.refused(follow, other, "svc-two", binding))
                .isEqualTo("conversation_binding_fixed_account_ck");
            // Lent in place of the first (ADR-0072): the binding follows it, with its identity and its holder only.
            sql.accepted("UPDATE service_account_credential SET account_id = ? WHERE principal_id = ? "
                    + "AND system_id = ?", other, setup.service(), setup.jira());
            assertThat(sql.refused(follow, other, "svc-ba", binding)).isEqualTo("conversation_binding_holder_ck");
            assertThat(sql.refused("UPDATE conversation_binding SET account_id = ?, external_id = 'alice', "
                    + "holder_principal_id = ? WHERE id = ?", setup.authorJira(), setup.author(), binding))
                .isEqualTo("conversation_binding_fixed_account_ck");
            sql.accepted(follow, other, "svc-two", binding);
            // Never back to the one it no longer borrows.
            assertThat(sql.refused(follow, setup.serviceJira(), "svc-ba", binding))
                .isEqualTo("conversation_binding_fixed_account_ck");
        }
    }

    @Test
    void conversationRebindBindsARowWhoseServiceAccountChangedWithTheSameCredential() throws SQLException {
        Setup setup = setup();
        UUID company = setup.company();
        try (TenantSql sql = TenantSql.in(company)) {
            // A second service account borrows the same credential (ADR-0072).
            UUID second = UUID.randomUUID();
            sql.accepted("""
                    INSERT INTO principal (id, company_id, kind, name, status, created_at, updated_at)
                    VALUES (?, ?, 'SERVICE', ?, 'ACTIVE', now(), now())""", second, company, unique("svc-two"));
            sql.accepted(INSERT_LENDING, company, second, setup.jira(), setup.serviceJira());
            UUID trigger = trigger(sql, setup, setup.service(), setup.serviceJira(), setup.service(), "svc-ba");
            sql.accepted("""
                    INSERT INTO agent_settings (id, company_id, agent_id, trigger_id, accepted_version, created_at,
                                                updated_at)
                    VALUES (?, ?, ?, ?, 1, now(), now())""", UUID.randomUUID(), company, setup.agent(), trigger);
            UUID conversation = UUID.randomUUID();
            sql.accepted(INSERT_CONVERSATION, conversation, company, setup.agent(), "shop-1-h-1", "TRACKER_ISSUE",
                    SUBJECT.formatted(setup.jira(), "10001"), "TRIGGER", null, trigger, setup.author(),
                    setup.service());
            UUID binding = UUID.randomUUID();
            sql.accepted(INSERT_BINDING, binding, company, conversation, setup.jira(), "FIXED", setup.serviceJira(),
                    setup.service(), "svc-ba", null);
            commitChecks(sql);
            // Paused, its row acts through the other service account with the same credential; then resumed.
            sql.accepted("UPDATE trigger SET status = 'PAUSED', paused_reason = 'NOT_ENTITLED', paused_at = now() "
                    + "WHERE id = ?", trigger);
            sql.accepted("UPDATE trigger_account SET holder_principal_id = ? WHERE trigger_id = ?", second, trigger);
            sql.accepted("UPDATE trigger SET status = 'ACTIVE', paused_reason = NULL, paused_at = NULL, "
                    + "armed_at = now() WHERE id = ?", trigger);
            commitChecks(sql);
            // Another company's tenant context never reaches it, though the function bypasses row-level security.
            sql.context(companyWithRoles().id());
            assertThat(sql.refused("SELECT conversation_rebind(?)", conversation)).isEqualTo("conversation_rebind_ck");
            sql.context(company);
            String changed = String.valueOf(sql.column("SELECT conversation_rebind(?)::text", conversation)
                .getFirst());
            assertThat(changed).contains("\"toHolder\": \"" + second + "\"");
            assertThat(sql.rows("SELECT state, account_id, holder_principal_id FROM conversation_binding WHERE id = ?",
                    binding).getFirst())
                .containsEntry("state", "BOUND")
                .containsEntry("account_id", setup.serviceJira())
                .containsEntry("holder_principal_id", second);
        }
    }

    @Test
    void theCompanysFiringLimitsAndAnotherCompanysTriggersAreItsOwn() throws SQLException {
        Setup setup = setup();
        UUID company = setup.company();
        TestCompany other = companyWithRoles();
        UUID trigger;
        try (TenantSql sql = TenantSql.in(company)) {
            assertThat(sql.refused("INSERT INTO trigger_setting (company_id, firings_per_hour_default, "
                    + "firings_per_hour_max, updated_at) VALUES (?, 40, 30, now())", company))
                .isEqualTo("trigger_setting_limits_ck");
            sql.accepted("INSERT INTO trigger_setting (company_id, firings_per_hour_default, firings_per_hour_max, "
                    + "updated_at) VALUES (?, 20, 100, now())", company);
            trigger = trigger(sql, setup, setup.author(), setup.authorJira(), setup.author(), "alice");
            assertThat(sql.refused("UPDATE trigger SET max_firings_per_hour = 0 WHERE id = ?", trigger))
                .isEqualTo("trigger_rate_ck");
            sql.commit();
        }
        try (TenantSql sql = TenantSql.in(other.id())) {
            assertThat(sql.count("SELECT count(*) FROM trigger WHERE id = ?", trigger)).isZero();
            assertThat(sql.count("SELECT count(*) FROM trigger_account WHERE trigger_id = ?", trigger)).isZero();
            assertThat(sql.count("SELECT count(*) FROM trigger_setting WHERE company_id = ?", company)).isZero();
            assertThat(sql.update("UPDATE trigger SET name = 'Taken' WHERE id = ?", trigger)).isZero();
            // A row naming another company's trigger is refused, whatever its context names.
            assertThat(sql.refused(INSERT_ROW, company, trigger, setup.jira(), null, setup.author(), "alice"))
                .contains("row-level security");
        }
    }
}
