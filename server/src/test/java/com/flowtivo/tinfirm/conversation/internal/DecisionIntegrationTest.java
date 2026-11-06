package com.flowtivo.tinfirm.conversation.internal;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.conversation.ConversationEvents;
import com.flowtivo.tinfirm.support.ApiClient;
import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.JiraStub;
import com.flowtivo.tinfirm.support.RecordedEvents;
import com.flowtivo.tinfirm.support.TestCredentials;

import tools.jackson.databind.JsonNode;

/**
 * Decisions through their API (the package 3 contract, §5.8, §6 and §9; I15, I16, I17, I18): only the conversation's
 * person decides, OWNER sees and gets {@code CONVERSATION_BOUND}, anyone else {@code 404}; a decision is decided once;
 * scope {@code AGENT} needs the right to edit the agent; a paused agent approves nothing; what an approval does; and
 * V2's conversation half, a comment that is an approval, written with the person's account and the agent's label.
 * TEST FIXTURE ONLY: tokens are test values.
 */
class DecisionIntegrationTest extends ConversationBench {

    @Test
    void onlyThePersonDecidesOnceAndOthersSeeAsTheConversationSays() {
        Bench bench = bench();
        Conversation conversation = conversation(bench);
        UUID decision = pendingId(request(conversation, "ASK", Map.of("question", "Which project?\nIt matters.",
                "options", List.of("SHOP", "CRM"))));
        String path = bench.path("/decisions/" + decision);
        String other = uniqueEmail("other");
        createUser(admin(), other, false);
        addMember(bench.owner(), bench.companyId(), other, "MEMBER");
        ApiClient otherMember = loginAs(other);
        endTurn(conversation);

        // The person sees and decides it; OWNER sees it; ADMIN and another member do not (404).
        JsonNode seen = bench.person().get(path).json();
        assertThat(seen.get("kind").asString()).isEqualTo("QUESTION");
        assertThat(seen.get("mayDecide").asBoolean()).isTrue();
        assertThat(seen.get("conversationKey").asString()).isEqualTo(conversation.key());
        assertThat(bench.owner().get(path).json().get("mayDecide").asBoolean()).isFalse();
        assertThat(bench.admin().get(path).status()).isEqualTo(404);
        assertThat(otherMember.get(path).status()).isEqualTo(404);
        assertThat(ids(bench.person().get(bench.path("/decisions?open=true")))).contains(decision.toString());
        assertThat(ids(bench.person().get(bench.path("/decisions?mine=true")))).contains(decision.toString());
        assertThat(ids(bench.owner().get(bench.path("/decisions")))).contains(decision.toString());
        assertThat(ids(bench.owner().get(bench.path("/decisions?mine=true")))).isEmpty();
        assertThat(ids(bench.admin().get(bench.path("/decisions")))).isEmpty();
        assertThat(ids(otherMember.get(bench.path("/decisions")))).isEmpty();

        // I15: OWNER sees it but is not its person; ADMIN and another member do not see it.
        assertThat(bench.owner().post(path + "/decide", Map.of("option", "SHOP")).problemCode())
            .isEqualTo("CONVERSATION_BOUND");
        assertThat(bench.admin().post(path + "/decide", Map.of("option", "SHOP")).status()).isEqualTo(404);
        assertThat(otherMember.post(path + "/decide", Map.of("option", "SHOP")).status()).isEqualTo(404);
        // What a question is not decided with.
        assertThat(bench.person().post(path + "/decide", Map.of()).status()).isEqualTo(400);
        assertThat(bench.person().post(path + "/decide", Map.of("outcome", "APPROVED")).status()).isEqualTo(400);
        assertThat(bench.person().post(path + "/decide", Map.of("option", "HR")).status()).isEqualTo(400);

        ApiResponse decided = bench.person().post(path + "/decide", Map.of("option", "shop", "answer", "Go on."));
        assertThat(decided.status()).as("%s", decided).isEqualTo(200);
        assertThat(decided.json().get("status").asString()).isEqualTo("DECIDED");
        assertThat(decided.json().get("outcome").asString()).isEqualTo("ANSWERED");
        assertThat(decided.json().get("answer").asString()).isEqualTo("**SHOP**\n\nGo on.");
        assertThat(decided.json().get("mayDecide").asBoolean()).isFalse();
        // The answer through the card is the person's message quoting the question's first line.
        assertThat(rows(bench.companyId(), "SELECT kind, body, author_principal_id FROM message WHERE decision_id = ?"
                + " AND kind = 'PERSON'", decision)).singleElement().satisfies(message -> {
                    assertThat(message.get("body")).isEqualTo("> Which project?\n\n**SHOP**\n\nGo on.");
                    assertThat(message.get("author_principal_id")).isEqualTo(bench.personPrincipalId());
                });
        assertThat(requested(conversation)).containsExactly("DECIDED");
        assertThat(auditEvents(bench.owner(), bench.company(), "decision.decided")).singleElement()
            .satisfies(event -> {
                assertThat(event.get("actor").get("type").asString()).isEqualTo("USER");
                assertThat(event.toString()).doesNotContain("Go on.");
            });
        // I16: once.
        assertThat(bench.person().post(path + "/decide", Map.of("option", "CRM")).problemCode())
            .isEqualTo("DECISION_DECIDED");
        assertThat(answersOf(nextTurn(conversation, 2))).contains("**Q:** Which project?\nIt matters.",
                "**A:** **SHOP**\n\nGo on.");
    }

    /**
     * ADR-0077, decision 5: a question whose person is a Viewer goes to the company's Owners and Admins; the Viewer
     * reads it and decides nothing. Of a personal conversation, only the OWNER sees it, so the OWNER decides.
     */
    @Test
    void aViewersQuestionGoesToTheOwnersAndAdmins() {
        Bench bench = bench();
        Conversation conversation = conversation(bench);
        assertThat(bench.owner().patch(bench.path("/members/" + bench.company().userId("MEMBER")),
                Map.of("role", "VIEWER")).status()).isEqualTo(200);

        UUID decision = pendingId(request(conversation, "ASK", Map.of("question", "Which project?")));
        String path = bench.path("/decisions/" + decision);
        endTurn(conversation);

        JsonNode seen = bench.person().get(path).json();
        assertThat(seen.get("mayDecide").asBoolean()).isFalse();
        assertThat(ids(bench.person().get(bench.path("/decisions?mine=true")))).isEmpty();
        assertThat(bench.person().post(path + "/decide", Map.of("answer", "Shop")).problemCode())
            .isEqualTo("VIEWER_READ_ONLY");
        assertThat(bench.owner().get(path).json().get("mayDecide").asBoolean()).isTrue();
        assertThat(ids(bench.owner().get(bench.path("/decisions?mine=true")))).contains(decision.toString());
        assertThat(bench.owner().get(bench.path("/inbox")).json().get("items"))
            .anySatisfy(item -> assertThat(item.get("kind").asString()).isEqualTo("DECISION"));

        ApiResponse decided = bench.owner().post(path + "/decide", Map.of("answer", "Shop"));
        assertThat(decided.status()).as("%s", decided).isEqualTo(200);
        assertThat(decided.json().get("status").asString()).isEqualTo("DECIDED");
    }

    @Test
    void anApprovedRequestGivesTheConversationOrTheAgentWhatItAsked() {
        Bench bench = bench();
        UUID github = createSystem(bench.admin(), bench.company(), unique("GitHub"), "GITHUB",
                "https://api.github.com", false);
        ApiResponse repository = bench.admin().post(bench.path("/systems/" + github + "/repositories"),
                Map.of("url", "https://github.com/acme/" + unique("docs")));
        UUID repositoryId = UUID.fromString(repository.json().get("id").asString());
        assertThat(bench.person().put(bench.agentPath("/systems"), Map.of("systems", List.of(
                Map.of("systemId", bench.jiraId().toString()), Map.of("systemId", github.toString(), "tools",
                        List.of())))).status()).isEqualTo(200);
        Conversation conversation = conversation(bench);
        String name = String.valueOf(row(bench.companyId(), "SELECT name FROM system_repository WHERE id = ?",
                repositoryId).get("name"));

        // A repository the agent does not list, approved with WRITE: it joins the workspace, writable.
        UUID repo = pendingId(request(conversation, "ACCESS", Map.of("repository", name, "reason", "Docs.")));
        endTurn(conversation);
        assertThat(bench.person().post(bench.path("/decisions/" + repo + "/decide"), Map.of("outcome", "APPROVED",
                "access", "WRITE", "scope", "CONVERSATION")).status()).isEqualTo(200);
        assertThat(String.valueOf(row(bench.companyId(), "SELECT workspace::text AS w FROM conversation WHERE id = ?",
                conversation.id()).get("w"))).contains(repositoryId.toString(), "\"readOnly\": false",
                        "\"source\": \"REQUESTED\"");
        // A host for the conversation, another for the agent (its owner edits it).
        UUID host = pendingId(request(conversation, "NETWORK", Map.of("host", "api.example.com", "reason", "API")));
        assertThat(bench.person().post(bench.path("/decisions/" + host + "/decide"), Map.of("outcome", "APPROVED",
                "access", "READ")).status()).isEqualTo(400);
        assertThat(bench.person().post(bench.path("/decisions/" + host + "/decide"), Map.of("outcome", "APPROVED"))
            .status()).isEqualTo(200);
        assertThat(String.valueOf(row(bench.companyId(), "SELECT granted_hosts::text AS h FROM conversation WHERE "
                + "id = ?", conversation.id()).get("h"))).contains("api.example.com");
        UUID forGood = pendingId(request(conversation, "NETWORK", Map.of("host", "cdn.example.com", "reason",
                "Assets")));
        JsonNode decided = bench.person().post(bench.path("/decisions/" + forGood + "/decide"), Map.of("outcome",
                "APPROVED", "scope", "AGENT")).json();
        assertThat(decided.get("scope").asString()).isEqualTo("AGENT");
        assertThat(row(bench.companyId(), "SELECT decision_id FROM agent_host WHERE agent_id = ? AND host = ?",
                bench.agentId(), "cdn.example.com")).containsEntry("decision_id", forGood);
        // Denied: nothing happens.
        UUID denied = pendingId(request(conversation, "NETWORK", Map.of("host", "evil.example.com", "reason", "x")));
        assertThat(bench.person().post(bench.path("/decisions/" + denied + "/decide"), Map.of("outcome", "DENIED",
                "note", "No.")).json().get("outcome").asString()).isEqualTo("DENIED");
        assertThat(String.valueOf(row(bench.companyId(), "SELECT granted_hosts::text AS h FROM conversation WHERE "
                + "id = ?", conversation.id()).get("h"))).doesNotContain("evil");

        String answers = answersOf(nextTurn(conversation, 2));
        assertThat(answers).contains("Your request of the repository " + name + ": approved by",
                "`api.example.com`: approved by", "for this conversation",
                "`cdn.example.com`: approved by", "for the agent", "`evil.example.com`: denied by", "Their note: No.");
        assertThat(requested(conversation)).containsExactly("DECIDED");
    }

    @Test
    void aRepositoryApprovedWithWriteStaysReadOnlyThroughAReadGrant() {
        // V5's workspace half: the analyst asks for a repository and is given WRITE; the read-only grant they act
        // through keeps it read-only.
        Bench bench = bench();
        UUID git = createSystem(bench.owner(), bench.company(), unique("Git"), "GIT", null, false);
        ApiResponse repository = bench.owner().post(bench.path("/systems/" + git + "/repositories"),
                Map.of("url", "https://git.example.test/acme/" + unique("shop").toLowerCase() + ".git"));
        assertThat(repository.status()).as("%s", repository).isEqualTo(201);
        UUID repositoryId = UUID.fromString(repository.json().get("id").asString());
        UUID credential = TestCredentials.add(bench.owner(), bench.path(""), git, "Bot", "test-bot-read-v5");
        UUID group = createGroup(bench.owner(), bench.company(), unique("Analysts"),
                List.of(bench.company().userId("MEMBER")));
        createGrant(bench.owner(), bench.company(), credential, group, "READ");
        assertThat(bench.person().put(bench.agentPath("/systems"), Map.of("systems", List.of(
                Map.of("systemId", bench.jiraId().toString()), Map.of("systemId", git.toString(), "tools",
                        List.of())))).status()).isEqualTo(200);
        Conversation conversation = conversation(bench);
        String name = String.valueOf(row(bench.companyId(), "SELECT name FROM system_repository WHERE id = ?",
                repositoryId).get("name"));

        UUID decision = pendingId(request(conversation, "ACCESS", Map.of("repository", name, "reason", "Fix it.")));
        endTurn(conversation);
        assertThat(bench.person().post(bench.path("/decisions/" + decision + "/decide"), Map.of("outcome",
                "APPROVED", "access", "WRITE")).json().get("access").asString()).isEqualTo("WRITE");
        // The next turn binds the code host through the grant, read-only.
        assertThat(transactions.inCompany(bench.companyId(), () -> bindings.resolve(bench.companyId(),
                conversation.id(), git)).grantId()).isNotNull();
        assertThat(workspaceOf(conversation)).singleElement().satisfies(entry -> {
            assertThat(entry.repositoryId()).isEqualTo(repositoryId);
            assertThat(entry.source()).isEqualTo(com.flowtivo.tinfirm.conversation.ConversationInfo.WorkspaceEntry
                .Source.REQUESTED);
            assertThat(entry.readOnly()).isTrue();
            assertThat(entry.readOnlyCause()).isEqualTo(com.flowtivo.tinfirm.run.TurnSource.ReadOnlyCause.GRANT);
        });
    }

    @Test
    void scopeAgentNeedsTheEditRightAndAPausedAgentApprovesNothing() {
        Bench bench = bench();
        // The OWNER's agent, shared with a group of the person: they use it, but do not edit it.
        String agentName = unique("Shared");
        UUID group = createGroup(bench.admin(), bench.company(), unique("Users"),
                List.of(bench.company().userId("MEMBER")));
        ApiResponse agent = bench.owner().post(bench.path("/agents"), Map.of("name", agentName, "runtimes",
                List.of("CLAUDE_CODE"), "shareGroupIds", List.of(group.toString())));
        assertThat(agent.status()).as("%s", agent).isEqualTo(201);
        UUID agentId = UUID.fromString(agent.json().get("id").asString());
        Conversation conversation = conversation(bench, agentId, bench.personPrincipalId());
        UUID host = pendingId(request(conversation, "NETWORK", Map.of("host", "api.example.com", "reason", "API")));
        String decide = bench.path("/decisions/" + host + "/decide");
        // The decision says the person does not edit the agent, so the app offers no approval for good; its owner
        // does.
        assertThat(bench.person().get(bench.path("/decisions/" + host)).json().get("agentEditableByMe").asBoolean())
            .isFalse();
        assertThat(bench.person().get(bench.path("/inbox")).json().get("items"))
            .filteredOn(item -> item.path("decision").path("id").asString("").equals(host.toString()))
            .singleElement()
            .satisfies(item -> assertThat(item.get("decision").get("agentEditableByMe").asBoolean()).isFalse());
        assertThat(bench.owner().get(bench.path("/decisions/" + host)).json().get("agentEditableByMe").asBoolean())
            .isTrue();

        assertThat(bench.person().post(decide, Map.of("outcome", "APPROVED", "scope", "AGENT")).problemCode())
            .isEqualTo("AGENT_NOT_EDITABLE");
        assertThat(bench.person().get(bench.path("/decisions/" + host)).json().get("status").asString())
            .isEqualTo("OPEN");

        // I18: paused, the agent's approvals wait; a denial and an answer do not.
        assertThat(bench.owner().post(bench.path("/agents/" + agentId + "/pause"), Map.of()).status())
            .isEqualTo(200);
        assertThat(bench.person().post(decide, Map.of("outcome", "APPROVED")).problemCode())
            .isEqualTo("AGENT_PAUSED");
        UUID question = pendingId(request(conversation, "ASK", Map.of("question", "Proceed?")));
        assertThat(bench.person().post(bench.path("/decisions/" + question + "/decide"), Map.of("answer", "Yes"))
            .status()).isEqualTo(200);
        assertThat(bench.person().post(decide, Map.of("outcome", "DENIED")).status()).isEqualTo(200);
    }

    @Test
    void aCommentIsAnApprovalWrittenWithThePersonsAccountAndTheAgentsLabel() throws java.sql.SQLException {
        // V2, the conversation's half: the BA agent comments on the subject only once the person approves it.
        Bench bench = bench();
        String handle = String.valueOf(row(bench.companyId(), "SELECT handle FROM external_system WHERE id = ?",
                bench.jiraId()).get("handle"));
        connectOwnJira(bench, "test-alice-token", "alice");
        Conversation conversation = conversation(bench);
        String comment = bench.jira().url("/rest/api/2/issue/SHOP-7/comment");
        JiraStub.server().stubFor(post(urlEqualTo(comment))
            .withHeader("Authorization", equalTo("Bearer test-alice-token"))
            .willReturn(JiraStub.json(201, Map.of("id", "10900"))));

        UUID decision = pendingId(call(conversation, handle + "_comment", Map.of("key", "SHOP-7", "body",
                "The requirements are clear.")));
        JsonNode card = bench.person().get(bench.path("/decisions/" + decision)).json();
        assertThat(card.get("subject").get("type").asString()).isEqualTo("TOOL_CALL");
        assertThat(card.get("subject").get("name").asString()).isEqualTo(handle + "_comment");
        assertThat(card.get("subject").get("what").asString()).contains("SHOP-7");
        // The account it runs as, in the binding's words.
        assertThat(card.get("subject").get("accountText").asString()).endsWith(": your own account");
        assertThat(card.has("agentEditableByMe")).isFalse();
        assertThat(card.get("subject").get("arguments").get("body").asString()).isEqualTo(
                "The requirements are clear.");
        JiraStub.server().verify(0, postRequestedFor(urlEqualTo(comment)));
        endTurn(conversation);

        ApiResponse approved = bench.person().post(bench.path("/decisions/" + decision + "/decide"),
                Map.of("outcome", "APPROVED"));
        assertThat(approved.status()).as("%s", approved).isEqualTo(200);
        awaitWorkers();

        // Written once, with the person's own account and the agent's label; nothing else reached Jira.
        JiraStub.server().verify(1, postRequestedFor(urlEqualTo(comment))
            .withHeader("Authorization", equalTo("Bearer test-alice-token"))
            .withRequestBody(equalToJson("{\"body\":\"*" + bench.agentName() + " (Tinfirm agent):* The "
                    + "requirements are clear.\"}")));
        JiraStub.server().verify(1, postRequestedFor(urlMatching(bench.jira().path() + "/.*")));
        DecisionStore.DecisionRow done = transactions.inCompany(bench.companyId(), () -> decisionStore.find(decision))
            .orElseThrow();
        assertThat(done.result()).contains("Commented on SHOP-7 (comment 10900)");
        assertThat(requested(conversation)).containsExactly("DECIDED");
        assertThat(auditEvents(bench.owner(), bench.company(), "tool.called")).singleElement().satisfies(event -> {
            assertThat(event.get("details").get("decisionId").asString()).isEqualTo(decision.toString());
            assertThat(event.get("details").get("mode").asString()).isEqualTo("ASK");
            assertThat(event.get("details").get("ok").asBoolean()).isTrue();
        });
        assertThat(auditEvents(bench.owner(), bench.company(), "secret.accessed")).extracting(event -> event
            .get("details").get("purpose").asString()).contains("decision-tool:" + decision);
        assertThat(RecordedEvents.of(bench.companyId(), ConversationEvents.SystemWritten.class)).containsExactly(
                new ConversationEvents.SystemWritten(bench.companyId(), conversation.id(), bench.jiraId(),
                        "COMMENT SHOP-7", "10900"));
        // Recorded as Tinfirm's own write (the package 4 contract, §3.12): a poll raises no event for it.
        try (com.flowtivo.tinfirm.support.TenantSql sql = com.flowtivo.tinfirm.support.TenantSql.in(
                bench.companyId())) {
            assertThat(sql.column("SELECT kind || ' ' || subject_key || ' ' || external_id FROM system_write"
                    + " WHERE conversation_id = ?", conversation.id())).containsExactly("COMMENT SHOP-7 10900");
        }
        assertThat(answersOf(nextTurn(conversation, 2))).contains("Your call of `" + handle + "_comment`",
                "approved by", "Result:", "> Commented on SHOP-7 (comment 10900)");
    }

    @Test
    void aHostAWildcardGrantedAlreadyCoversIsApprovedAsCovered() {
        Bench bench = bench();
        Conversation conversation = conversation(bench);
        // Two requests open at once: a wildcard, and a host under it.
        UUID wildcard = pendingId(request(conversation, "NETWORK", Map.of("host", "*.example.com", "reason",
                "The whole site")));
        UUID api = pendingId(request(conversation, "NETWORK", Map.of("host", "api.example.com", "reason", "The API")));
        endTurn(conversation);

        assertThat(bench.person().post(bench.path("/decisions/" + wildcard + "/decide"), Map.of("outcome",
                "APPROVED")).status()).isEqualTo(200);
        // The wildcard covers the host already: approving it is no limit's business, and adds nothing.
        ApiResponse covered = bench.person().post(bench.path("/decisions/" + api + "/decide"), Map.of("outcome",
                "APPROVED"));
        assertThat(covered.status()).as("%s", covered).isEqualTo(200);
        assertThat(covered.json().get("outcome").asString()).isEqualTo("APPROVED");
        assertThat(String.valueOf(row(bench.companyId(), "SELECT granted_hosts::text AS h FROM conversation WHERE "
                + "id = ?", conversation.id()).get("h"))).isEqualTo("{*.example.com}");
    }

    private static List<String> ids(ApiResponse page) {
        assertThat(page.status()).as("%s", page).isEqualTo(200);
        List<String> ids = new java.util.ArrayList<>();
        page.json().get("items").forEach(item -> ids.add(item.get("id").asString()));
        return ids;
    }
}
