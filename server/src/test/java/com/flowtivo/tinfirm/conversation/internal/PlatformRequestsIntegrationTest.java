package com.flowtivo.tinfirm.conversation.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.agent.RepositoryAccess;
import com.flowtivo.tinfirm.runner.RunnerMessages;
import com.flowtivo.tinfirm.support.ApiResponse;

/**
 * A run's platform requests other than tool calls (the package 3 contract, §5.5, §5.8, §7 and §9; V11's request
 * part): {@code ASK}, {@code ACCESS} of a repository, {@code NETWORK} of a host, {@code ARTIFACT} handed to part 3; their
 * codes, the limits of open decisions, a request id sent again, an ended conversation. Each raised decision is a card
 * in the thread and audited by the agent.
 */
class PlatformRequestsIntegrationTest extends ConversationBench {

    @Test
    void aQuestionIsACardOncePerRequestIdWithinItsLimits() {
        Bench bench = bench();
        Conversation conversation = conversation(bench);
        UUID requestId = UUID.randomUUID();
        RunnerMessages.RunRequestAck asked = request(conversation, requestId, "ASK", Map.of("question",
                "Which project?", "options", List.of("SHOP", "shop", " CRM ")));
        UUID decisionId = pendingId(asked);
        assertThat(asked.message()).contains("Ask every question you need, then end your turn without a summary");
        DecisionStore.DecisionRow question = transactions.inCompany(bench.companyId(),
                () -> decisionStore.find(decisionId)).orElseThrow();
        assertThat(question.options()).containsExactly("SHOP", "CRM");
        assertThat(question.approverPrincipalId()).isEqualTo(bench.personPrincipalId());
        assertThat(rows(bench.companyId(), "SELECT kind, body, run_id FROM message WHERE decision_id = ?",
                decisionId)).singleElement().satisfies(card -> {
                    assertThat(card).containsEntry("kind", "AGENT").containsEntry("run_id", conversation.runId());
                    assertThat(String.valueOf(card.get("body"))).isEqualTo("Which project?\n\n- SHOP\n- CRM");
                });
        assertThat(auditEvents(bench.owner(), bench.company(), "decision.created")).singleElement()
            .satisfies(event -> {
                assertThat(event.get("actor").get("type").asString()).isEqualTo("AGENT");
                assertThat(event.get("details").get("kind").asString()).isEqualTo("QUESTION");
                assertThat(event.toString()).doesNotContain("Which project");
            });
        // A request id sent again gets the same decision.
        assertThat(pendingId(request(conversation, requestId, "ASK", Map.of("question", "Which project?"))))
            .isEqualTo(decisionId);
        // What is not a question.
        assertThat(outcome(request(conversation, "ASK", Map.of("question", " ")))).isEqualTo("INVALID_REQUEST");
        assertThat(outcome(request(conversation, "ASK", Map.of("question", "x".repeat(2001)))))
            .isEqualTo("INVALID_REQUEST");
        List<String> eleven = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            eleven.add("Option " + i);
        }
        assertThat(outcome(request(conversation, "ASK", Map.of("question", "Pick", "options", eleven))))
            .isEqualTo("INVALID_REQUEST");
        // At most 10 open questions.
        for (int i = 2; i <= DecisionOps.MAX_OPEN; i++) {
            assertThat(outcome(request(conversation, "ASK", Map.of("question", "Question " + i))))
                .isEqualTo("PENDING");
        }
        assertThat(outcome(request(conversation, "ASK", Map.of("question", "One more")))).isEqualTo(
                "TOO_MANY_QUESTIONS");
        assertThat(rows(bench.companyId(), "SELECT id FROM decision WHERE conversation_id = ?", conversation.id()))
            .hasSize(DecisionOps.MAX_OPEN);
    }

    @Test
    void aRepositoryIsRequestedOnlyOfTheAgentsSystemsOnceAndNotTwice() {
        Bench bench = bench();
        UUID github = createSystem(bench.admin(), bench.company(), unique("GitHub"), "GITHUB",
                "https://api.github.com", false);
        UUID listed = repository(bench, github, "https://github.com/acme/" + unique("shop"));
        UUID other = repository(bench, github, "https://github.com/acme/" + unique("docs"));
        UUID elsewhere = repository(bench, createSystem(bench.admin(), bench.company(), unique("Other GitHub"),
                "GITHUB", "https://api.github.com", false), "https://github.com/acme/" + unique("secret"));
        assertThat(bench.person().put(bench.agentPath("/systems"), Map.of("systems", List.of(
                Map.of("systemId", bench.jiraId().toString()), Map.of("systemId", github.toString(), "tools",
                        List.of())))).status()).isEqualTo(200);
        assertThat(bench.person().put(bench.agentPath("/repositories"), Map.of("repositories", List.of(Map.of(
                "repositoryId", listed.toString(), "access", "WRITE")))).status()).isEqualTo(200);
        Conversation conversation = conversation(bench);

        // Tools are the agent's definition's, never requested.
        RunnerMessages.RunRequestAck tool = request(conversation, "ACCESS", Map.of("tool", "Jira", "reason", "x"));
        assertThat(outcome(tool)).isEqualTo("TOOL_DENIED");
        assertThat(tool.message()).contains("You may not request tools");
        assertThat(outcome(request(conversation, "ACCESS", Map.of("reason", "x")))).isEqualTo("INVALID_REQUEST");
        // Only repositories of the systems the agent lists.
        assertThat(outcome(request(conversation, "ACCESS", Map.of("repository", "acme/nothing", "reason", "x"))))
            .isEqualTo("UNKNOWN_REPOSITORY");
        assertThat(outcome(request(conversation, "ACCESS", Map.of("repository", name(bench, elsewhere), "reason",
                "x")))).isEqualTo("UNKNOWN_REPOSITORY");

        // One the agent does not list comes READ unless the approver picks WRITE; one it lists with its access.
        UUID docs = pendingId(request(conversation, "ACCESS", Map.of("repository", name(bench, other), "reason",
                "The docs.")));
        UUID shop = pendingId(request(conversation, "ACCESS", Map.of("repository", name(bench, listed), "reason",
                "The code.")));
        assertThat(subject(bench, docs).access()).isEqualTo(RepositoryAccess.READ);
        assertThat(subject(bench, shop).access()).isEqualTo(RepositoryAccess.WRITE);
        assertThat(transactions.inCompany(bench.companyId(), () -> decisionStore.find(docs)).orElseThrow().reason())
            .isEqualTo("The docs.");
        // Asked again with another request id: ALREADY_PENDING; already in the workspace: ALREADY_IN_WORKSPACE.
        assertThat(outcome(request(conversation, "ACCESS", Map.of("repository", name(bench, other), "reason", "x"))))
            .isEqualTo("ALREADY_PENDING");
        update(bench.companyId(), "UPDATE conversation SET workspace = CAST(? AS jsonb) WHERE id = ?",
                "[{\"repositoryId\":\"" + elsewhere + "\",\"readOnly\":true,\"source\":\"AGENT\"},"
                        + "{\"repositoryId\":\"" + listed + "\",\"readOnly\":false,\"source\":\"AGENT\"}]",
                conversation.id());
        update(bench.companyId(), "UPDATE decision SET status = 'CANCELLED', cancel_cause = 'TEST' WHERE id = ?",
                shop);
        assertThat(outcome(request(conversation, "ACCESS", Map.of("repository", name(bench, listed), "reason",
                "x")))).isEqualTo("ALREADY_IN_WORKSPACE");
    }

    @Test
    void aHostRequestWaitsForThePersonUnlessAllowedAndApprovalsHaveALimit() {
        Bench bench = bench();
        ApiResponse hosts = bench.person().put(bench.agentPath("/hosts"), Map.of("hosts", List.of(Map.of("host",
                "*.npmjs.org", "reason", "Packages"))));
        assertThat(hosts.status()).as("%s", hosts).isEqualTo(200);
        Conversation conversation = conversation(bench);

        // The grammar of NetworkHosts.
        assertThat(outcome(request(conversation, "NETWORK", Map.of("host", "https://api.example.com", "reason",
                "x")))).isEqualTo("INVALID_REQUEST");
        assertThat(outcome(request(conversation, "NETWORK", Map.of("host", "*.co.uk", "reason", "x"))))
            .isEqualTo("INVALID_REQUEST");
        // Allowed already: by the agent, or by an earlier approval for the conversation.
        assertThat(outcome(request(conversation, "NETWORK", Map.of("host", "registry.npmjs.org", "reason", "x"))))
            .isEqualTo("ALREADY_ALLOWED");
        update(bench.companyId(), "UPDATE conversation SET granted_hosts = '{docs.example.com}' WHERE id = ?",
                conversation.id());
        assertThat(outcome(request(conversation, "NETWORK", Map.of("host", "DOCS.example.com:443", "reason", "x"))))
            .isEqualTo("ALREADY_ALLOWED");
        // V11: a request of a host waits for the person; asked again it is the same decision.
        RunnerMessages.RunRequestAck asked = request(conversation, "NETWORK", Map.of("host", "api.example.com",
                "reason", "The API docs."));
        UUID decision = pendingId(asked);
        assertThat(asked.message()).contains("waits for", "end your turn without a summary");
        assertThat(subject(bench, decision).host()).isEqualTo("api.example.com");
        assertThat(pendingId(request(conversation, "NETWORK", Map.of("host", "api.example.com", "reason", "Again"))))
            .isEqualTo(decision);
        // At most 10 open approvals.
        for (int i = 2; i <= DecisionOps.MAX_OPEN; i++) {
            assertThat(outcome(request(conversation, "NETWORK", Map.of("host", "h" + i + ".example.com", "reason",
                    "x")))).isEqualTo("PENDING");
        }
        assertThat(outcome(request(conversation, "NETWORK", Map.of("host", "h11.example.com", "reason", "x"))))
            .isEqualTo("TOO_MANY_REQUESTS");
        // A call of an ASK tool is an approval too.
        connectOwnJira(bench, "test-limit-token", "lia");
        assertThat(outcome(call(conversation, handleOf(bench) + "_comment", Map.of("key", "SHOP-1", "body", "x"))))
            .isEqualTo("TOO_MANY_REQUESTS");
    }

    @Test
    void anArtifactGoesToPartThreeAndAnEndedConversationTakesNothing() {
        Bench bench = bench();
        Conversation conversation = conversation(bench);
        // ARTIFACT is part 3's (ArtifactRequests), in the run module's transaction: the link is an output.
        UUID artifactId = UUID.randomUUID();
        RunnerMessages.RunRequestAck artifact = request(conversation, artifactId, "ARTIFACT", Map.of("url",
                "https://example.com/report", "title", "Report"));
        assertThat(outcome(artifact)).isEqualTo("DONE");
        UUID output = ((RunnerMessages.ArtifactPayload) java.util.Objects.requireNonNull(artifact.payload()))
            .artifactId();
        assertThat(row(bench.companyId(), "SELECT kind, url, run_id FROM output WHERE id = ?", output))
            .containsEntry("kind", "LINK")
            .containsEntry("url", "https://example.com/report")
            .containsEntry("run_id", conversation.runId());
        UUID requestId = UUID.randomUUID();
        UUID question = pendingId(request(conversation, requestId, "ASK", Map.of("question", "Ready?")));
        update(bench.companyId(), """
                UPDATE conversation SET state = 'ENDED', ended_reason = 'USER', ended_at = now() WHERE id = ?""",
                conversation.id());
        assertThat(outcome(request(conversation, "ASK", Map.of("question", "Still there?"))))
            .isEqualTo("CONVERSATION_ENDED");
        assertThat(outcome(call(conversation, handleOf(bench) + "_get", Map.of("key", "SHOP-1"))))
            .isEqualTo("CONVERSATION_ENDED");
        assertThat(outcome(request(conversation, "ARTIFACT", Map.of("url", "https://example.com/other"))))
            .isEqualTo("CONVERSATION_ENDED");
        // A request id sent again still answers what it raised.
        assertThat(outcome(request(conversation, artifactId, "ARTIFACT", Map.of("url",
                "https://example.com/report", "title", "Report")))).isEqualTo("DONE");
        assertThat(pendingId(request(conversation, requestId, "ASK", Map.of("question", "Ready?"))))
            .isEqualTo(question);
    }

    private UUID repository(Bench bench, UUID systemId, String url) {
        ApiResponse response = bench.admin().post(bench.path("/systems/" + systemId + "/repositories"),
                Map.of("url", url));
        assertThat(response.status()).as("%s", response).isEqualTo(201);
        return UUID.fromString(response.json().get("id").asString());
    }

    private String name(Bench bench, UUID repositoryId) {
        return String.valueOf(row(bench.companyId(), "SELECT name FROM system_repository WHERE id = ?",
                repositoryId).get("name"));
    }

    private DecisionSubject subject(Bench bench, UUID decisionId) {
        return transactions.inCompany(bench.companyId(), () -> decisionStore.find(decisionId)).orElseThrow()
            .requireSubject();
    }

    private String handleOf(Bench bench) {
        return String.valueOf(row(bench.companyId(), "SELECT handle FROM external_system WHERE id = ?",
                bench.jiraId()).get("handle"));
    }
}
