package com.flowtivo.tinfirm.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.FakeRunner;
import com.flowtivo.tinfirm.support.JiraStub;

import tools.jackson.databind.JsonNode;

/**
 * Conversations end to end through the API and a fake runner host (the package 3 contract, §5.6, §5.7, §5.9, §5.10,
 * §9): starting one, its key, title and workspace, the first turn's {@code run.assign} from the conversation (a
 * snapshot: ids, key and branch, the relay's token, the git token only from the binding and only in
 * {@code workspaces[].git}), the prompt's sections, messages during a run and the next turn, what can never run, who
 * sees a conversation and its runs (I17), who speaks in it (I15), cancel, retry and end by the person and by OWNER, a
 * runtime the agent dropped, and a share the person lost (L22).
 */
class ConversationIntegrationTest extends ConversationTestBench {

    @Test
    void aConversationsTurnRunsWithItsKeyBranchAndTheBindingsGitTokenOnly() {
        Bench bench = bench(true);
        UUID[] git = gitHost(bench, "Shop");
        JiraStub.Instance jira = JiraStub.newInstance();
        UUID tracker = createSystem(bench.owner(), bench.company(), unique("Jira"), "JIRA_DC", jira.baseUrl(), false);
        UUID agentId = agent(bench.member(), bench, "Builder", "You build the shop.", List.<UUID[]>of(git), "WRITE",
                List.of());
        String handle = bench.member().get(bench.path("/agents/" + agentId)).json().get("handle").asString();
        connectOwn(bench.member(), bench, git[0], "test-own-git-token");
        try (FakeRunner runner = companyHost(bench, 4)) {
            ApiResponse started = bench.member().post(bench.path("/conversations"), Map.of("agentId",
                    agentId.toString(), "message", "# Fix the checkout\nThe cart loses items.", "subject",
                    Map.of("systemId", tracker.toString(), "key", "SHOP-123", "title", "Cart loses items", "url",
                            "https://jira.example.test/browse/SHOP-123")));
            assertThat(started.status()).as("%s", started).isEqualTo(201);
            JsonNode conversation = started.json();
            String id = conversation.get("id").asString();
            String key = conversation.get("key").asString();
            // I12: {base}-{n}, the subject's key as a slug and the agent's handle; the title is the first line.
            assertThat(key).isEqualTo("shop-123-" + handle + "-1");
            assertThat(conversation.get("title").asString()).isEqualTo("Fix the checkout");
            assertThat(conversation.get("branch").asString()).isEqualTo("agent/" + key);
            assertThat(conversation.get("payer").get("principalId").asString())
                .isEqualTo(bench.memberPrincipal().toString());
            assertThat(conversation.get("payer").get("modelAccess").asString()).isEqualTo("BUDGET");
            assertThat(conversation.get("runtime").asString()).isEqualTo("CLAUDE_CODE");
            assertThat(conversation.get("workspace")).singleElement().satisfies(repository -> {
                assertThat(repository.get("repositoryId").asString()).isEqualTo(git[1].toString());
                assertThat(repository.get("readOnly").asBoolean()).isFalse();
            });
            // The workspace's code host was bound at the first turn: the person's own account.
            assertThat(conversation.get("bindings")).singleElement().satisfies(binding -> {
                assertThat(binding.get("state").asString()).isEqualTo("BOUND");
                assertThat(binding.get("how").asString()).isEqualTo("OWN");
                assertThat(binding.get("text").asString()).endsWith(": your own account");
            });
            assertThat(conversation.get("turnCount").asInt()).isEqualTo(1);
            assertThat(conversation.get("messages")).singleElement()
                .satisfies(message -> assertThat(message.get("kind").asString()).isEqualTo("PERSON"));
            assertThat(conversation.get("decisions")).isEmpty();
            assertThat(conversation.get("outputs")).isEmpty();
            assertThat(conversation.get("attachments")).isEmpty();
            String runId = lastTurn(conversation).get("id").asString();

            JsonNode assign = runner.expect("run.assign");
            assertThat(assign.get("runId").asString()).isEqualTo(runId);
            // workItem.id and job.id are the conversation's, its key and title, its branch agent/{key} (I12).
            assertThat(assign.get("workItem").get("id").asString()).isEqualTo(id);
            assertThat(assign.get("job").get("id").asString()).isEqualTo(id);
            assertThat(assign.get("workItem").get("key").asString()).isEqualTo(key);
            assertThat(assign.get("workItem").get("title").asString()).isEqualTo("Fix the checkout");
            assertThat(assign.get("workspaces")).singleElement().satisfies(workspace -> {
                assertThat(workspace.get("componentId").asString()).isEqualTo(git[1].toString());
                assertThat(workspace.get("branch").asString()).isEqualTo("agent/" + key);
                assertThat(workspace.get("readOnly").asBoolean()).isFalse();
                assertThat(workspace.get("git").get("token").asString()).isEqualTo("test-own-git-token");
                assertThat(workspace.get("authorName").asString()).contains("via Tinfirm agent");
            });
            // I14: the git token only in workspaces[].git, the model key never, the relay's one-run token instead.
            assertThat(assign.toString().split("test-own-git-token", -1)).hasSize(2);
            assertThat(assign.get("modelAccess").get("apiKey").asString()).startsWith("tfm_");
            assertThat(assign.toString()).doesNotContain(litellm.keyOfAlias(
                    com.flowtivo.tinfirm.support.ModelGateways.alias(bench.company(), bench.memberPrincipal()))
                .orElseThrow().key);
            // The prompt (§5.9): the agent's instructions first, then the conversation's sections.
            String prompt = assign.get("prompt").asString();
            assertThat(prompt).startsWith("You build the shop.");
            assertThat(prompt).contains("# Conversation " + key + ": Fix the checkout", "SHOP-123: Cart loses items",
                    "# Request\n> # Fix the checkout", "# Accounts", "own account", "# Repositories",
                    "on branch `agent/" + key + "`", "# How to work");
            assertThat(prompt.indexOf("# Request")).isLessThan(prompt.indexOf("# Accounts"));

            // RUNNING: the agent works; a message meanwhile waits for the next turn.
            runner.accepted(UUID.fromString(runId), assign.get("epoch").asLong());
            awaitConversation(bench.member(), bench, id, now -> "RUNNING".equals(now.path("turnStatus").asString()));
            assertThat(conversation(bench.member(), bench, id).get("waitingOn").asString()).isEqualTo("AGENT");
            ApiResponse waiting = bench.member().post(bench.path("/conversations/" + id + "/messages"),
                    Map.of("body", "Also check the coupons."));
            assertThat(waiting.status()).as("%s", waiting).isEqualTo(201);
            assertThat(waiting.json().get("turnCount").asInt()).isEqualTo(1);
            assertThat(waiting.json().get("messages").get(1).get("waiting").asBoolean()).isTrue();

            // The turn ends: the agent's summary is posted, and the next turn takes the waiting message.
            runner.send(finished(runId, assign.get("epoch").asLong(), "SUCCEEDED", "Fixed the cart."));
            JsonNode second = runner.expect("run.assign");
            JsonNode after = awaitConversation(bench.member(), bench, id, now -> now.get("turnCount").asInt() == 2);
            assertThat(after.get("messages")).extracting(message -> message.get("kind").asString())
                .containsExactly("PERSON", "PERSON", "AGENT");
            assertThat(after.get("messages").get(2).get("body").asString()).isEqualTo("Fixed the cart.");
            assertThat(after.get("messages").get(1).get("runId").asString())
                .isEqualTo(second.get("runId").asString());
            assertThat(second.get("prompt").asString()).contains("# Request\n> Also check the coupons.");
            assertThat(lastTurn(after).get("turnReason").asString()).isEqualTo("MESSAGE");
            // The app's grouping by subject.
            assertThat(bench.member().get(bench.path("/subjects")).json().get("items")).singleElement()
                .satisfies(subject -> {
                    assertThat(subject.get("key").asString()).isEqualTo("SHOP-123");
                    assertThat(subject.get("conversations").asInt()).isEqualTo(1);
                    assertThat(subject.get("open").asInt()).isEqualTo(1);
                });
            assertThat(bench.admin().get(bench.path("/subjects")).json().get("items")).isEmpty();
            // On one system: the tracker's subject, and none on another system.
            assertThat(bench.member().get(bench.path("/subjects?systemId=" + tracker)).json().get("items"))
                .singleElement()
                .satisfies(subject -> assertThat(subject.get("systemId").asString()).isEqualTo(tracker.toString()));
            assertThat(bench.member().get(bench.path("/subjects?systemId=" + git[0])).json().get("items")).isEmpty();
            runner.assertFramesValid();
        }
    }

    @Test
    void anEmptyRepositoryListStartsWithoutARepository() {
        Bench bench = bench(true);
        UUID[] git = gitHost(bench, "Shop");
        UUID agentId = agent(bench.member(), bench, "Builder", "You build the shop.", List.<UUID[]>of(git), "WRITE",
                List.of());
        connectOwn(bench.member(), bench, git[0], "test-own-git-token");
        try (FakeRunner runner = companyHost(bench, 4)) {
            // Without repositoryIds the workspace is every repository of the agent (above); an empty list is none.
            ApiResponse started = bench.member().post(bench.path("/conversations"), Map.of("agentId",
                    agentId.toString(), "message", "Explain the checkout.", "repositoryIds", List.of()));
            assertThat(started.status()).as("%s", started).isEqualTo(201);
            assertThat(started.json().get("workspace")).isEmpty();
            // The code host serves no repository of the conversation: nothing is bound, no git token goes out.
            assertThat(started.json().get("bindings")).isEmpty();
            JsonNode assign = runner.expect("run.assign");
            assertThat(assign.get("workspaces")).isEmpty();
            assertThat(assign.toString()).doesNotContain("test-own-git-token");
            // The agent's repository is one it may ask for.
            assertThat(assign.get("requestableRepositories")).singleElement().satisfies(
                    repository -> assertThat(repository.get("componentId").asString()).isEqualTo(git[1].toString()));
            runner.assertFramesValid();
        }
    }

    @Test
    void whatCanNeverRunIsRefusedAtTheStart() {
        Bench bench = bench(false);
        UUID agentId = agent(bench.member(), bench, "Analyst", "", List.<UUID[]>of(), "READ", List.of());
        // No gateway: nothing pays.
        ApiResponse noGateway = bench.member().post(bench.path("/conversations"), Map.of("agentId",
                agentId.toString(), "message", "Hi"));
        assertThat(noGateway.status()).as("%s", noGateway).isEqualTo(409);
        assertThat(noGateway.json().get("code").asString()).isEqualTo("MODEL_ACCESS_UNAVAILABLE");
        assertThat(noGateway.json().get("details").get("reason").asString()).isEqualTo("GATEWAY_NOT_CONFIGURED");

        com.flowtivo.tinfirm.support.ModelGateways.configureBundled(bench.owner(), bench.company());
        com.flowtivo.tinfirm.support.ModelGateways.awaitKey(litellm, bench.company(), bench.memberPrincipal());
        // Settings that name no model, as a first use makes them: the budget pays only for a model the gateway serves.
        ApiResponse fresh = bench.member().post(bench.path("/agents"), Map.of("name", unique("Fresh"), "runtimes",
                List.of("CLAUDE_CODE")));
        assertThat(fresh.status()).as("%s", fresh).isEqualTo(201);
        ApiResponse noModel = bench.member().post(bench.path("/conversations"), Map.of("agentId",
                fresh.json().get("id").asString(), "message", "Hi"));
        assertThat(noModel.status()).as("%s", noModel).isEqualTo(409);
        assertThat(noModel.json().get("code").asString()).isEqualTo("MODEL_ACCESS_UNAVAILABLE");
        assertThat(noModel.json().get("details").get("reason").asString()).isEqualTo("MODEL_REQUIRED");
        // No runner host at all, connected or not, may run it.
        ApiResponse noHost = bench.member().post(bench.path("/conversations"), Map.of("agentId", agentId.toString(),
                "message", "Hi"));
        assertThat(noHost.status()).as("%s", noHost).isEqualTo(409);
        assertThat(noHost.json().get("code").asString()).isEqualTo("NO_HOST_FOR_CONVERSATION");
        assertThat(noHost.json().get("details").get("reason").asString()).isEqualTo("NO_RUNNER_HOST");
        // A company host registered and never connected is enough to start: the first turn waits.
        registerRunnerHost(bench.owner(), bench.id(), unique("host"), "COMPANY");
        ApiResponse waits = bench.member().post(bench.path("/conversations"), Map.of("agentId", agentId.toString(),
                "message", "Hi"));
        assertThat(waits.status()).as("%s", waits).isEqualTo(201);
        assertThat(waits.json().get("waitingOn").asString()).isEqualTo("RUNNER");

        // OWNER sees the member's agent but does not use it; another member does not see it.
        ApiResponse owner = bench.owner().post(bench.path("/conversations"), Map.of("agentId", agentId.toString(),
                "message", "Hi"));
        assertThat(owner.status()).as("%s", owner).isEqualTo(403);
        assertThat(owner.json().get("code").asString()).isEqualTo("AGENT_NOT_USABLE");
        UUID adminsAgent = agent(bench.admin(), bench, "Private", "", List.<UUID[]>of(), "READ", List.of());
        assertThat(bench.member().post(bench.path("/conversations"), Map.of("agentId", adminsAgent.toString(),
                "message", "Hi")).status()).isEqualTo(404);
    }

    @Test
    void onlyItsPersonAndOwnerSeeAConversationAndOnlyItsPersonSpeaksInIt() {
        Bench bench = bench(true);
        UUID agentId = agent(bench.member(), bench, "Helper", "", List.<UUID[]>of(), "READ", List.of());
        try (FakeRunner runner = companyHost(bench, 4)) {
            JsonNode conversation = start(bench.member(), bench, agentId, "Plan the release");
            String id = conversation.get("id").asString();
            String runId = lastTurn(conversation).get("id").asString();
            runner.expect("run.assign");

            // I17: its person and OWNER see it, its runs too; ADMIN, who is not OWNER, does not.
            assertThat(conversation(bench.owner(), bench, id).get("seenByOwner").asBoolean()).isTrue();
            // Who may write in it, as the POST checks it: its person, not OWNER, who reads it.
            assertThat(conversation(bench.member(), bench, id).get("mayPost").asBoolean()).isTrue();
            assertThat(conversation(bench.member(), bench, id).get("mayAnswer").asBoolean()).isTrue();
            assertThat(conversation(bench.owner(), bench, id).get("mayPost").asBoolean()).isFalse();
            assertThat(conversation(bench.owner(), bench, id).get("mayAnswer").asBoolean()).isFalse();
            assertThat(bench.admin().get(bench.path("/conversations/" + id)).status()).isEqualTo(404);
            assertThat(bench.admin().get(bench.path("/runs/" + runId)).status()).isEqualTo(404);
            assertThat(bench.owner().get(bench.path("/runs/" + runId)).status()).isEqualTo(200);
            assertThat(bench.member().get(bench.path("/runs/" + runId)).status()).isEqualTo(200);
            assertThat(bench.admin().get(bench.path("/conversations")).json().get("items")).isEmpty();
            assertThat(bench.member().get(bench.path("/conversations")).json().get("items")).singleElement()
                .satisfies(item -> assertThat(item.get("id").asString()).isEqualTo(id));
            assertThat(bench.owner().get(bench.path("/conversations?personId=" + bench.memberPrincipal()))
                .json().get("items")).singleElement()
                .satisfies(item -> assertThat(item.get("key").asString()).isEqualTo(conversation.get("key")
                    .asString()));
            assertThat(bench.admin().post(bench.path("/conversations/" + id + "/end"), null).status())
                .isEqualTo(404);

            // I15: only its person speaks and renames; OWNER is bound out.
            ApiResponse ownerSays = bench.owner().post(bench.path("/conversations/" + id + "/messages"),
                    Map.of("body", "Hello from the owner"));
            assertThat(ownerSays.status()).as("%s", ownerSays).isEqualTo(409);
            assertThat(ownerSays.json().get("code").asString()).isEqualTo("CONVERSATION_BOUND");
            assertThat(bench.owner().patch(bench.path("/conversations/" + id), Map.of("title", "Owner's"))
                .json().get("code").asString()).isEqualTo("CONVERSATION_BOUND");
            assertThat(bench.member().patch(bench.path("/conversations/" + id), Map.of("title", "The release"))
                .json().get("title").asString()).isEqualTo("The release");

            // OWNER cancels the queued turn and then ends the conversation; it takes nothing more.
            JsonNode cancelled = bench.owner().post(bench.path("/conversations/" + id + "/cancel"), null).json();
            assertThat(cancelled.get("turnStatus").asString()).isEqualTo("CANCELLED");
            JsonNode ended = bench.owner().post(bench.path("/conversations/" + id + "/end"), null).json();
            assertThat(ended.get("state").asString()).isEqualTo("ENDED");
            assertThat(ended.get("endedReason").asString()).isEqualTo("USER");
            ApiResponse late = bench.member().post(bench.path("/conversations/" + id + "/messages"),
                    Map.of("body", "One more thing"));
            assertThat(late.json().get("code").asString()).isEqualTo("CONVERSATION_ENDED");
            assertThat(auditEvents(bench.owner(), bench.company(), "conversation.started")).isNotEmpty();
            assertThat(auditEvents(bench.owner(), bench.company(), "conversation.ended")).singleElement()
                .satisfies(event -> assertThat(event.get("details").get("cause").asString()).isEqualTo("USER"));
        }
    }

    @Test
    void cancelAndRetryRunTheTurnAgainAndARuntimeTheAgentDroppedFailsTheTurn() {
        Bench bench = bench(true);
        UUID agentId = agent(bench.member(), bench, "Fixer", "", List.<UUID[]>of(), "READ", List.of());
        try (FakeRunner runner = companyHost(bench, 4)) {
            JsonNode conversation = start(bench.member(), bench, agentId, "Fix the flaky test");
            String id = conversation.get("id").asString();
            JsonNode assign = runner.expect("run.assign");
            String runId = assign.get("runId").asString();
            runner.accepted(UUID.fromString(runId), assign.get("epoch").asLong());
            awaitConversation(bench.member(), bench, id, now -> "RUNNING".equals(now.path("turnStatus").asString()));

            // Nothing to retry while the turn is active.
            assertThat(bench.member().post(bench.path("/conversations/" + id + "/retry"), null).json().get("code")
                .asString()).isEqualTo("TURN_ACTIVE");
            // A message meanwhile, then the person cancels: the running turn is cancelled through its host, and the
            // waiting message stays unanswered.
            bench.member().post(bench.path("/conversations/" + id + "/messages"), Map.of("body", "Never mind"));
            bench.member().post(bench.path("/conversations/" + id + "/cancel"), null);
            JsonNode cancel = runner.expect("run.cancel");
            assertThat(cancel.get("runId").asString()).isEqualTo(runId);
            runner.send(Map.of("type", "run.finished", "runId", runId, "epoch", assign.get("epoch").asLong(),
                    "outcome", "CANCELLED", "lastSeq", 0));
            JsonNode cancelled = awaitConversation(bench.member(), bench, id,
                    now -> "CANCELLED".equals(now.path("turnStatus").asString()));
            assertThat(cancelled.get("messages").get(1).get("unanswered").asBoolean()).isTrue();
            assertThat(cancelled.get("turnCount").asInt()).isEqualTo(1);

            // Retry runs the cancelled turn's request again.
            JsonNode retried = bench.member().post(bench.path("/conversations/" + id + "/retry"), null).json();
            assertThat(lastTurn(retried).get("turnReason").asString()).isEqualTo("RETRY");
            JsonNode again = runner.expect("run.assign");
            assertThat(again.get("prompt").asString()).contains("# Request\n> Fix the flaky test");
            runner.send(finished(again.get("runId").asString(), again.get("epoch").asLong(), "SUCCEEDED", "Done."));
            awaitConversation(bench.member(), bench, id, now -> "SUCCEEDED".equals(now.path("turnStatus").asString()));
            assertThat(bench.member().post(bench.path("/conversations/" + id + "/retry"), null).json().get("code")
                .asString()).isEqualTo("NOTHING_TO_RETRY");

            // The agent drops the conversation's runtime: the next turn fails at once, without a run.
            assertThat(bench.member().patch(bench.path("/agents/" + agentId), Map.of("runtimes", List.of("CODEX")))
                .status()).isEqualTo(200);
            JsonNode failed = bench.member().post(bench.path("/conversations/" + id + "/messages"),
                    Map.of("body", "And the other test")).json();
            assertThat(failed.get("turnStatus").asString()).isEqualTo("FAILED");
            assertThat(failed.get("failureClass").asString()).isEqualTo("RUNTIME_REMOVED");
            assertThat(failed.get("waitingOn").asString()).isEqualTo("PERSON");
            assertThat(failed.get("turnCount").asInt()).isEqualTo(2);
            runner.expectNone("run.assign", java.time.Duration.ofSeconds(1));
        }
    }

    @Test
    void anAgentsDeletionAndItsPersonsLeavingEndTheirConversations() {
        Bench bench = bench(true);
        UUID agentId = agent(bench.member(), bench, "Short-lived", "", List.<UUID[]>of(), "READ", List.of());
        UUID otherAgent = agent(bench.member(), bench, "Other", "", List.<UUID[]>of(), "READ", List.of());
        try (FakeRunner runner = companyHost(bench, 4)) {
            JsonNode first = start(bench.member(), bench, agentId, "First");
            JsonNode assign = runner.expect("run.assign");
            runner.send(finished(assign.get("runId").asString(), assign.get("epoch").asLong(), "SUCCEEDED", "Ok."));
            awaitConversation(bench.member(), bench, first.get("id").asString(),
                    now -> "SUCCEEDED".equals(now.path("turnStatus").asString()));
            assertThat(bench.member().delete(bench.path("/agents/" + agentId)).status()).isEqualTo(204);
            JsonNode ended = conversation(bench.member(), bench, first.get("id").asString());
            assertThat(ended.get("state").asString()).isEqualTo("ENDED");
            assertThat(ended.get("endedReason").asString()).isEqualTo("AGENT_DELETED");

            JsonNode second = start(bench.member(), bench, otherAgent, "Second");
            runner.expect("run.assign");
            ApiResponse removed = bench.owner().delete(bench.path("/members/" + bench.company().userId("MEMBER")));
            assertThat(removed.status()).as("%s", removed).isEqualTo(204);
            // The running turn is cancelled through its host; the conversation stays readable to OWNER.
            runner.expect("run.cancel");
            JsonNode left = conversation(bench.owner(), bench, second.get("id").asString());
            assertThat(left.get("state").asString()).isEqualTo("ENDED");
            assertThat(left.get("endedReason").asString()).isEqualTo("PERSON_LEFT");
            await().atMost(WAIT).untilAsserted(() -> assertThat(auditEvents(bench.owner(), bench.company(),
                    "conversation.ended")).hasSize(2));
        }
    }

    @Test
    void aliceAndBobTalkToOneSharedAgentInParallelEachForThemselves() {
        // V1, the conversation's half: own keys and branches, own payers, and neither sees the other's.
        Bench bench = bench(true);
        UUID team = createGroup(bench.owner(), bench.company(), unique("Team"),
                List.of(bench.company().userId("MEMBER"), bench.company().userId("ADMIN")));
        UUID agentId = agent(bench.owner(), bench, "Shared", "", List.<UUID[]>of(), "READ", List.of(team));
        try (FakeRunner runner = companyHost(bench, 4)) {
            JsonNode alice = start(bench.member(), bench, agentId, "Alice's question");
            JsonNode bob = start(bench.admin(), bench, agentId, "Bob's question");
            assertThat(alice.get("key").asString()).isNotEqualTo(bob.get("key").asString());
            assertThat(alice.get("branch").asString()).isNotEqualTo(bob.get("branch").asString());
            // Both turns run at once.
            JsonNode first = runner.expect("run.assign");
            JsonNode second = runner.expect("run.assign");
            assertThat(List.of(first.get("workItem").get("id").asString(), second.get("workItem").get("id")
                .asString())).containsExactlyInAnyOrder(alice.get("id").asString(), bob.get("id").asString());
            assertThat(first.get("modelAccess").get("apiKey").asString())
                .isNotEqualTo(second.get("modelAccess").get("apiKey").asString());
            String aliceRun = lastTurn(alice).get("id").asString();
            String bobRun = lastTurn(bob).get("id").asString();
            // Each pays for their own (I10).
            assertThat(bench.member().get(bench.path("/runs/" + aliceRun)).json().get("payerPrincipalId")
                .asString()).isEqualTo(bench.memberPrincipal().toString());
            assertThat(bench.admin().get(bench.path("/runs/" + bobRun)).json().get("payerPrincipalId").asString())
                .isEqualTo(bench.adminPrincipal().toString());
            // Bob gets 404 for Alice's conversation and its run, and the other way round (I17).
            assertThat(bench.admin().get(bench.path("/conversations/" + alice.get("id").asString())).status())
                .isEqualTo(404);
            assertThat(bench.admin().get(bench.path("/runs/" + aliceRun)).status()).isEqualTo(404);
            assertThat(bench.member().get(bench.path("/conversations/" + bob.get("id").asString())).status())
                .isEqualTo(404);
            assertThat(bench.member().get(bench.path("/runs/" + bobRun)).status()).isEqualTo(404);
            assertThat(bench.member().get(bench.path("/conversations")).json().get("items")).singleElement()
                .satisfies(item -> assertThat(item.get("id").asString()).isEqualTo(alice.get("id").asString()));
            assertThat(bench.owner().get(bench.path("/conversations?agentId=" + agentId)).json().get("items"))
                .hasSize(2);
        }
    }

    @Test
    void anInstructionChangeIsAnnouncedInTheNextTurnOrWaitsForAcceptanceWhereTheAgentCanAct() {
        // D1 (the security review of 2026-10-27; the owner's decision Q4): within a ceiling that cannot act, a change
        // of the instructions reaches the person's next turn, which says so in the conversation; within one that can,
        // it waits for their acceptance, and their turns keep the instructions they accepted.
        Bench bench = bench(true);
        UUID team = createGroup(bench.owner(), bench.company(), unique("Team"),
                List.of(bench.company().userId("MEMBER")));
        UUID agentId = agent(bench.owner(), bench, "Writer", "Answer in English.", List.<UUID[]>of(), "READ",
                List.of(team));
        String agentPath = bench.path("/agents/" + agentId);
        try (FakeRunner runner = companyHost(bench, 4)) {
            String id = start(bench.member(), bench, agentId, "First question").get("id").asString();
            JsonNode assign = runner.expect("run.assign");
            assertThat(assign.get("prompt").asString()).contains("Answer in English.");
            runner.send(finished(assign.get("runId").asString(), assign.get("epoch").asLong(), "SUCCEEDED", "Done."));
            awaitConversation(bench.member(), bench, id, now -> "SUCCEEDED".equals(now.path("turnStatus").asString()));

            // The agent can only read: the change widens nothing and the next turn uses it, announced.
            assertThat(bench.owner().patch(agentPath, Map.of("instructions", "Answer in French.")).status())
                .isEqualTo(200);
            bench.member().post(bench.path("/conversations/" + id + "/messages"), Map.of("body", "Second question"));
            assign = runner.expect("run.assign");
            assertThat(assign.get("prompt").asString()).contains("Answer in French.")
                .doesNotContain("Answer in English.");
            JsonNode after = conversation(bench.member(), bench, id);
            assertThat(after.get("messages"))
                .filteredOn(message -> "SYSTEM".equals(message.get("kind").asString()))
                .singleElement()
                .satisfies(message -> assertThat(message.get("body").asString())
                    .startsWith("The agent's instructions changed on ")
                    .endsWith(".")
                    .contains(" by "));
            runner.send(finished(assign.get("runId").asString(), assign.get("epoch").asLong(), "SUCCEEDED", "Done."));
            awaitConversation(bench.member(), bench, id, now -> "SUCCEEDED".equals(now.path("turnStatus").asString()));

            // A network host lets it act; the member accepts that version.
            bench.owner().put(agentPath + "/hosts", Map.of("hosts", List.of(Map.of("host", "api.example.com"))));
            int withHost = bench.owner().get(agentPath).json().get("version").asInt();
            assertThat(bench.member().post(agentPath + "/accept", Map.of("version", withHost)).status())
                .isEqualTo(200);

            // Now a change of the instructions waits: the member's turns keep the accepted ones, with no notice.
            assertThat(bench.owner().patch(agentPath, Map.of("instructions", "Answer in German.")).status())
                .isEqualTo(200);
            JsonNode widening = bench.member().get(agentPath).json().get("widening");
            assertThat(widening.get("instructions").get("accepted").asString()).isEqualTo("Answer in French.");
            assertThat(widening.get("instructions").get("current").asString()).isEqualTo("Answer in German.");
            bench.member().post(bench.path("/conversations/" + id + "/messages"), Map.of("body", "Third question"));
            assign = runner.expect("run.assign");
            assertThat(assign.get("prompt").asString()).contains("Answer in French.")
                .doesNotContain("Answer in German.");
            assertThat(conversation(bench.member(), bench, id).get("messages"))
                .filteredOn(message -> "SYSTEM".equals(message.get("kind").asString()))
                .hasSize(1);
            runner.send(finished(assign.get("runId").asString(), assign.get("epoch").asLong(), "SUCCEEDED", "Done."));
            awaitConversation(bench.member(), bench, id, now -> "SUCCEEDED".equals(now.path("turnStatus").asString()));

            // Accepted, the next turn uses them, announced.
            int german = bench.member().get(agentPath).json().get("version").asInt();
            assertThat(bench.member().post(agentPath + "/accept", Map.of("version", german)).status()).isEqualTo(200);
            bench.member().post(bench.path("/conversations/" + id + "/messages"), Map.of("body", "Fourth question"));
            assign = runner.expect("run.assign");
            assertThat(assign.get("prompt").asString()).contains("Answer in German.");
            assertThat(conversation(bench.member(), bench, id).get("messages"))
                .filteredOn(message -> "SYSTEM".equals(message.get("kind").asString()))
                .hasSize(2);
        }
    }

    @Test
    void aMemberWhoLostTheAgentsShareIsRefusedAndTheirNextTurnWaitsUntilItIsSharedAgain() {
        // L22 (the security review of 2026-10-27): the share that let the member use the agent is removed while their
        // conversation runs. The turn their waiting message asked for waits, saying why; a new message is refused
        // (409 AGENT_NOT_USABLE); shared again, the waiting turn runs.
        Bench bench = bench(true);
        UUID team = createGroup(bench.owner(), bench.company(), unique("Team"),
                List.of(bench.company().userId("MEMBER")));
        UUID agentId = agent(bench.owner(), bench, "Shared", "", List.<UUID[]>of(), "READ", List.of(team));
        try (FakeRunner runner = companyHost(bench, 4)) {
            String id = start(bench.member(), bench, agentId, "First question").get("id").asString();
            JsonNode assign = runner.expect("run.assign");
            String runId = assign.get("runId").asString();
            runner.accepted(UUID.fromString(runId), assign.get("epoch").asLong());
            awaitConversation(bench.member(), bench, id, now -> "RUNNING".equals(now.path("turnStatus").asString()));
            ApiResponse waiting = bench.member().post(bench.path("/conversations/" + id + "/messages"),
                    Map.of("body", "And a second one."));
            assertThat(waiting.status()).as("%s", waiting).isEqualTo(201);

            ApiResponse unshared = bench.owner().put(bench.path("/agents/" + agentId + "/shares"),
                    Map.of("groupIds", List.of()));
            assertThat(unshared.status()).as("%s", unshared).isEqualTo(200);

            // The running turn ends; the next one, which the waiting message asked for, waits with the reason.
            runner.send(finished(runId, assign.get("epoch").asLong(), "SUCCEEDED", "Answered."));
            JsonNode after = awaitConversation(bench.member(), bench, id, now -> now.get("turnCount").asInt() == 2);
            String next = lastTurn(after).get("id").asString();
            await().atMost(WAIT).untilAsserted(() -> {
                JsonNode run = bench.owner().get(bench.path("/runs/" + next)).json();
                assertThat(run.get("status").asString()).isEqualTo("QUEUED");
                assertThat(run.path("queueReason").path("code").asString()).isEqualTo("OTHER");
                assertThat(run.path("queueReason").path("text").asString()).contains("no longer shared");
            });
            runner.expectNone("run.assign", java.time.Duration.ofSeconds(1));

            ApiResponse refused = bench.member().post(bench.path("/conversations/" + id + "/messages"),
                    Map.of("body", "Are you there?"));
            assertThat(refused.status()).as("%s", refused).isEqualTo(409);
            assertThat(refused.json().get("code").asString()).isEqualTo("AGENT_NOT_USABLE");

            // Shared again: the waiting turn runs.
            ApiResponse shared = bench.owner().put(bench.path("/agents/" + agentId + "/shares"),
                    Map.of("groupIds", List.of(team.toString())));
            assertThat(shared.status()).as("%s", shared).isEqualTo(200);
            assertThat(runner.expect("run.assign").get("runId").asString()).isEqualTo(next);
        }
    }

    @org.springframework.beans.factory.annotation.Autowired
    com.flowtivo.tinfirm.conversation.internal.ConversationTurns conversationTurns;

    @org.springframework.beans.factory.annotation.Autowired
    com.flowtivo.tinfirm.shared.tx.SystemTransactions transactions;

    @Test
    void openQuestionsHoldAPlainMessageAndATurnEndingOnThemPostsNoSummary() throws java.sql.SQLException {
        Bench bench = bench(true);
        UUID agentId = agent(bench.member(), bench, "Asker", "", List.<UUID[]>of(), "READ", List.of());
        try (FakeRunner runner = companyHost(bench, 4)) {
            JsonNode conversation = start(bench.member(), bench, agentId, "Plan the migration");
            String id = conversation.get("id").asString();
            JsonNode assign = runner.expect("run.assign");
            String runId = assign.get("runId").asString();
            runner.accepted(UUID.fromString(runId), assign.get("epoch").asLong());
            // The agent asks (part 2 raises the question; here the row as it writes it).
            UUID question = UUID.randomUUID();
            try (com.flowtivo.tinfirm.support.TenantSql sql = com.flowtivo.tinfirm.support.TenantSql.in(bench.id())) {
                sql.update("""
                        INSERT INTO decision (id, company_id, conversation_id, run_id, request_id, kind, question,
                                              approver_principal_id, created_at)
                        VALUES (?, ?, ?, ?, ?, 'QUESTION', 'Which database?', ?, now())""", question, bench.id(),
                        UUID.fromString(id), UUID.fromString(runId), UUID.randomUUID(), bench.memberPrincipal());
                sql.commit();
            }
            transactions.runInCompany(bench.id(), () -> conversationTurns.refreshWaiting(bench.id(),
                    UUID.fromString(id)));
            // The turn ends on its own question: no summary (ADR-0036); the conversation waits on its person.
            runner.send(Map.of("type", "run.finished", "runId", runId, "epoch", assign.get("epoch").asLong(),
                    "outcome", "SUCCEEDED", "lastSeq", 0, "summary", "I asked which database."));
            JsonNode waiting = awaitConversation(bench.member(), bench, id,
                    now -> "WAITING".equals(now.path("turnStatus").asString()));
            assertThat(waiting.get("waitingOn").asString()).isEqualTo("PERSON");
            assertThat(waiting.get("waitingReason").asString()).isEqualTo("QUESTION");
            assertThat(waiting.get("messages")).extracting(message -> message.get("kind").asString())
                .containsExactly("PERSON");
            // A plain message while a question is open wakes nothing (ADR-0033): it waits.
            JsonNode said = bench.member().post(bench.path("/conversations/" + id + "/messages"),
                    Map.of("body", "By the way, it is urgent.")).json();
            assertThat(said.get("turnCount").asInt()).isEqualTo(1);
            assertThat(said.get("messages").get(1).get("waiting").asBoolean()).isTrue();
            runner.expectNone("run.assign", java.time.Duration.ofSeconds(1));
            // Cancel: the open question is cancelled, the waiting message stays unanswered.
            JsonNode cancelled = bench.member().post(bench.path("/conversations/" + id + "/cancel"), null).json();
            assertThat(cancelled.get("turnStatus").asString()).isEqualTo("CANCELLED");
            assertThat(cancelled.get("waitingOn").asString()).isEqualTo("NONE");
            assertThat(cancelled.get("messages").get(1).get("unanswered").asBoolean()).isTrue();
            try (com.flowtivo.tinfirm.support.TenantSql sql = com.flowtivo.tinfirm.support.TenantSql.in(bench.id())) {
                assertThat(sql.column("SELECT status FROM decision WHERE id = ?", question))
                    .containsExactly("CANCELLED");
            }
        }
    }
}
