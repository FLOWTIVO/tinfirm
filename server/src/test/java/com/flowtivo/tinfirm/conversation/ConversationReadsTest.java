package com.flowtivo.tinfirm.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.flowtivo.tinfirm.audit.AuditAction;
import com.flowtivo.tinfirm.audit.AuditLog;
import com.flowtivo.tinfirm.audit.AuditRecord;
import com.flowtivo.tinfirm.shared.security.Actor;
import com.flowtivo.tinfirm.shared.tx.SystemTransactions;
import com.flowtivo.tinfirm.support.ApiClient;
import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.IntegrationTest;
import com.flowtivo.tinfirm.support.TestConversations;
import com.flowtivo.tinfirm.support.TestRuns;

import tools.jackson.databind.JsonNode;

/**
 * The person sees who read their conversation (the package 6 contract, §5.6; open point 10): one entry per reader from
 * the {@code conversation.read} events, recorded for every read by an OWNER who is not its person (§11), written here
 * as the reads would write them.
 */
class ConversationReadsTest extends IntegrationTest {

    @Autowired
    private AuditLog auditLog;

    @Autowired
    private SystemTransactions transactions;

    @Test
    void thePersonSeesWhoReadTheirConversationWhenAndHow() {
        TestCompany company = companyWithRoles();
        UUID person = memberPrincipal(company, company.userId("MEMBER"));
        UUID conversationId = UUID.randomUUID();
        TestConversations.bare(company.id(), conversationId, unique("read").toLowerCase(), TestRuns.agent(company.id()),
                person, "CLAUDE_CODE");
        UUID other = UUID.randomUUID();
        TestConversations.bare(company.id(), other, unique("other").toLowerCase(), TestRuns.agent(company.id()),
                person, "CLAUDE_CODE");
        UUID owner = company.userId("OWNER");
        read(company.id(), owner, conversationId, person, "DETAIL");
        read(company.id(), owner, conversationId, person, "EVENTS");
        read(company.id(), owner, conversationId, person, "DETAIL");
        read(company.id(), owner, other, person, "FILES");

        ApiClient member = loginAs(company.email("MEMBER"));
        ApiResponse reads = member.get(company.path("/conversations/" + conversationId + "/reads"));

        assertThat(reads.status()).as("%s", reads).isEqualTo(200);
        assertThat(reads.json()).hasSize(1);
        JsonNode reader = reads.json().get(0);
        assertThat(reader.get("userId").asString()).isEqualTo(owner.toString());
        assertThat(reader.get("name").asString()).isNotBlank().isNotEqualTo("deleted user");
        assertThat(reader.get("reads").asLong()).isEqualTo(3);
        assertThat(reader.get("kinds")).extracting(JsonNode::asString).containsExactly("DETAIL", "EVENTS");
        assertThat(reader.get("firstReadAt").asString()).isLessThanOrEqualTo(reader.get("lastReadAt").asString());
        // OWNER sees the list too; ADMIN and anyone else get 404, as for the conversation.
        assertThat(loginAs(company.email("OWNER")).get(company.path("/conversations/" + conversationId + "/reads"))
            .json()).hasSize(1);
        assertThat(loginAs(company.email("ADMIN")).get(company.path("/conversations/" + conversationId + "/reads"))
            .status()).isEqualTo(404);
        assertThat(member.get(company.path("/conversations/" + UUID.randomUUID() + "/reads")).status())
            .isEqualTo(404);
    }

    @Test
    void aConversationNobodyReadHasNoReaders() {
        TestCompany company = companyWithRoles();
        UUID person = memberPrincipal(company, company.userId("MEMBER"));
        UUID conversationId = UUID.randomUUID();
        TestConversations.bare(company.id(), conversationId, unique("unread").toLowerCase(),
                TestRuns.agent(company.id()), person, "CLAUDE_CODE");

        assertThat(loginAs(company.email("MEMBER")).get(company.path("/conversations/" + conversationId + "/reads"))
            .json()).isEmpty();
    }

    private void read(UUID companyId, UUID reader, UUID conversationId, UUID person, String kind) {
        transactions.runInCompany(companyId, () -> auditLog.record(AuditRecord
            .company(companyId, AuditAction.CONVERSATION_READ, Actor.user(reader))
            .target("conversation", conversationId)
            .detail("conversationId", conversationId.toString())
            .detail("personPrincipalId", person.toString())
            .detail("what", kind)
            .build()));
    }
}
