package com.flowtivo.tinfirm.governance.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.support.IntegrationTest;
import com.flowtivo.tinfirm.support.TenantSql;
import com.flowtivo.tinfirm.support.TestContent;
import com.flowtivo.tinfirm.support.TestRuns;

/**
 * What the database itself refuses (the package 6 contract, §3.7, §9): a purged text without its marker and a marker
 * with its text, a purged file that would lose its size or name, a hold naming both a conversation and a principal or
 * neither, and periods out of range.
 */
class RetentionConstraintTest extends IntegrationTest {

    @Test
    void eachPurgedTextIsNullExactlyWhenItsMarkerIsSet() throws Exception {
        TestCompany company = companyWithRoles();
        UUID member = memberPrincipal(company, company.userId("MEMBER"));
        TestContent.Content content = TestContent.conversation(company.id(), TestRuns.agent(company.id()), member,
                company.userId("MEMBER"), unique("constraints").toLowerCase(), "TINFIRM_THREAD", true);
        try (TenantSql sql = TenantSql.in(company.id())) {
            assertThat(sql.refused("UPDATE message SET body = NULL WHERE id = ?", content.personMessageId()))
                .isEqualTo("message_body_ck");
            assertThat(sql.refused("UPDATE message SET purged_at = now() WHERE id = ?", content.personMessageId()))
                .isEqualTo("message_body_ck");
            sql.accepted("UPDATE message SET body = NULL, purged_at = now() WHERE id = ?", content.personMessageId());

            assertThat(sql.refused("UPDATE decision SET purged_at = now() WHERE id = ?", content.questionId()))
                .isIn("decision_shape_ck", "decision_purged_ck");
            assertThat(sql.refused("UPDATE decision SET question = NULL WHERE id = ?", content.questionId()))
                .isEqualTo("decision_shape_ck");
            assertThat(sql.refused("""
                    UPDATE decision SET reason = NULL, note = NULL, subject = subject - 'what', purged_at = now()
                     WHERE id = ?""", content.approvalId())).isEqualTo("decision_purged_ck");

            assertThat(sql.refused("UPDATE output SET title = NULL WHERE id = ?", content.outputId()))
                .isEqualTo("output_title_ck");
            assertThat(sql.refused("UPDATE output SET purged_at = now() WHERE id = ?", content.outputId()))
                .isEqualTo("output_title_ck");

            // A purged file keeps its size and name; its content goes with its note.
            assertThat(sql.refused("UPDATE attachment SET content = NULL WHERE id = ?", content.attachmentId()))
                .isEqualTo("attachment_size_ck");
            assertThat(sql.refused("UPDATE attachment SET content = NULL, content_purged_at = now() WHERE id = ?",
                    content.attachmentId())).isEqualTo("attachment_size_ck");
            sql.accepted("""
                    UPDATE attachment SET content = NULL, note = NULL, content_purged_at = now() WHERE id = ?""",
                    content.attachmentId());
            assertThat(sql.count("SELECT size_bytes FROM attachment WHERE id = ?", content.attachmentId()))
                .isPositive();
            assertThat(sql.refused("UPDATE attachment SET size_bytes = -1 WHERE id = ?", content.attachmentId()))
                .isEqualTo("attachment_size_ck");

            assertThat(sql.refused("UPDATE run SET texts_purged_at = now() WHERE id = ?", content.runId()))
                .isEqualTo("run_texts_purged_ck");
            assertThat(sql.refused("UPDATE conversation SET title = NULL WHERE id = ?", content.conversationId()))
                .isEqualTo("conversation_title_ck");
            assertThat(sql.refused("""
                    UPDATE conversation SET title = NULL, title_purged_at = now(), content_purged_at = now()
                     WHERE id = ?""", content.conversationId())).isEqualTo("conversation_content_purged_ck");
        }
    }

    @Test
    void aHoldNamesExactlyOneTargetAndPeriodsStayInRange() throws Exception {
        TestCompany company = companyWithRoles();
        UUID member = memberPrincipal(company, company.userId("MEMBER"));
        UUID conversation = UUID.randomUUID();
        com.flowtivo.tinfirm.support.TestConversations.bare(company.id(), conversation,
                unique("held").toLowerCase(), TestRuns.agent(company.id()), member, "CLAUDE_CODE");
        try (TenantSql sql = TenantSql.in(company.id())) {
            assertThat(sql.refused("""
                    INSERT INTO legal_hold (id, company_id, conversation_id, principal_id, reason, set_at)
                    VALUES (?, ?, ?, ?, 'Both', now())""", UUID.randomUUID(), company.id(), conversation, member))
                .isEqualTo("legal_hold_target_ck");
            assertThat(sql.refused("""
                    INSERT INTO legal_hold (id, company_id, reason, set_at) VALUES (?, ?, 'Neither', now())""",
                    UUID.randomUUID(), company.id())).isEqualTo("legal_hold_target_ck");
            assertThat(sql.refused("""
                    INSERT INTO legal_hold (id, company_id, principal_id, reason, set_at) VALUES (?, ?, ?, ' ', now())""",
                    UUID.randomUUID(), company.id(), member)).isEqualTo("legal_hold_reason_ck");
            assertThat(sql.refused("""
                    INSERT INTO retention_settings (company_id, run_events_days, updated_at) VALUES (?, 6, now())""",
                    company.id())).isEqualTo("retention_run_events_ck");
            assertThat(sql.refused("""
                    INSERT INTO retention_settings (company_id, chat_content_days, content_days, updated_at)
                    VALUES (?, 100, 60, now())""", company.id())).isEqualTo("retention_chat_content_ck");
            // ADR-0077, V054: chats' content kept for good only while all content is.
            assertThat(sql.refused("""
                    INSERT INTO retention_settings (company_id, chat_content_days, content_days, updated_at)
                    VALUES (?, NULL, 60, now())""", company.id())).isEqualTo("retention_chat_content_ck");
            // Another company's conversation is never held here.
            TestCompany other = companyWithRoles();
            UUID elsewhere = UUID.randomUUID();
            com.flowtivo.tinfirm.support.TestConversations.bare(other.id(), elsewhere, unique("other").toLowerCase(),
                    TestRuns.agent(other.id()), memberPrincipal(other, other.userId("MEMBER")), "CLAUDE_CODE");
            assertThat(sql.refused("""
                    INSERT INTO legal_hold (id, company_id, conversation_id, reason, set_at)
                    VALUES (?, ?, ?, 'Elsewhere', now())""", UUID.randomUUID(), company.id(), elsewhere))
                .isEqualTo("legal_hold_conversation_fk");
        }
    }
}
