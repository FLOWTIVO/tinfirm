package com.flowtivo.tinfirm.conversation.internal;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.patch;
import static com.github.tomakehurst.wiremock.client.WireMock.patchRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.GitHubStub;
import com.flowtivo.tinfirm.support.TestConversations;
import com.flowtivo.tinfirm.support.TestCredentials;

import tools.jackson.databind.JsonNode;

/**
 * Change requests after a push (the package 3 contract, §5.4, open point 5, I9): under {@code ALLOW} the server opens
 * one at once, outside any transaction, with the conversation's binding on the code host (purpose
 * {@code change-request:{runId}}), and records it as a {@code CHANGE_REQUEST} output; a later push goes to the same
 * one, and after it was merged a push of another commit gets the next one (V038), also when the watch reads the merge
 * after the push; a closed one gets nothing, said once; a read-only repository, a read-only grant, an agent without the
 * tool (said) and a code host's refusal open none; under {@code ASK} part 2's approval is raised and, approved by the
 * person, opens it; a second change request lists the first, whose body is rewritten; the watch reads the state until
 * MERGED.
 */
class ChangeRequestIntegrationTest extends ChangeRequestTestBench {

    private static final String MERGED_AT = "21a02534de2d9805c3f2907966f5021453676003";

    private static final String NEXT = "039828eb2ef6c8593943c0f0b15bf99d3baa6b34";

    @Autowired
    ChangeRequestWatch watch;

    @Test
    void underAllowAPushOpensOneChangeRequestWithThePersonsAccountAndALaterPushGoesToIt() {
        Host host = codeHost("ALLOW");
        Bound bound = bound(host);
        TestConversations.subject(host.bench().companyId(), bound.conversation().id(), host.systemId(),
                "acme/shop#57", "https://github.com/acme/shop/issues/57", "Totals are wrong");
        opens(host, "shop", 12);

        UUID runId = pushed(host, bound, host.shopId(), "shop", false, 2);
        awaitChangeRequests(host, bound, 1);
        GitHubStub.server().verify(1, postRequestedFor(urlEqualTo(host.pulls("shop")))
            .withHeader("Authorization", equalTo("Bearer " + TOKEN)));
        JsonNode request = JSON.readTree(GitHubStub.server().findAll(postRequestedFor(urlEqualTo(host.pulls("shop"))))
            .getFirst()
            .getBodyAsString());
        String key = bound.conversation().key();
        assertThat(request.get("title").asString()).isEqualTo("acme/shop#57: Test conversation " + key);
        assertThat(request.get("head").asString()).isEqualTo("agent/" + key);
        assertThat(request.get("base").asString()).isEqualTo("main");
        assertThat(request.get("body").asString()).isEqualTo("Fixed the totals.\n\nhttp://localhost:8080/c/"
                + host.bench().companyId() + "/conversations/" + bound.conversation().id() + "\n\nCloses #57\n\n"
                + "<!-- tinfirm:related -->\n<!-- /tinfirm:related -->\n\nOpened by User "
                + host.bench().company().email("MEMBER") + " via Tinfirm agent " + host.bench().agentName() + ".");

        List<ConversationOutputs.OutputInfo> produced = inCompany(host.bench(),
                () -> outputs.outputs(host.bench().companyId(), bound.conversation().id()));
        assertThat(produced).singleElement().satisfies(output -> {
            assertThat(output.kind()).isEqualTo(ConversationOutputs.Kind.CHANGE_REQUEST);
            assertThat(output.url()).isEqualTo("https://github.com/acme/shop/pull/12");
            assertThat(output.title()).isEqualTo(output.repositoryName() + " #12");
            assertThat(output.repositoryId()).isEqualTo(host.shopId());
            assertThat(output.externalNumber()).isEqualTo("12");
            assertThat(output.changeRequestState()).isEqualTo(ConversationOutputs.ChangeRequestState.OPEN);
            assertThat(output.runId()).isEqualTo(runId);
        });
        assertThat(column(host.bench(), "SELECT binding_id FROM run_change_request WHERE conversation_id = ?",
                bound.conversation().id())).containsExactly(bound.bindingId());
        // Tinfirm's own writes (the package 4 contract, open point 13): the push and the change request it opened.
        assertThat(column(host.bench(), "SELECT kind || ' ' || subject_key || ' ' || external_id FROM system_write"
                + " WHERE conversation_id = ? ORDER BY written_at, kind", bound.conversation().id()))
            .containsExactly("PUSH shop@agent/" + bound.conversation().key() + " abc123",
                    "CHANGE_REQUEST " + produced.getFirst().repositoryName() + "#12 12");
        JsonNode created = auditEvents(host.bench().owner(), host.bench().company(), "output.created").getFirst();
        assertThat(created.get("actor").get("type").asString()).isEqualTo("SYSTEM");
        assertThat(created.get("details").get("kind").asString()).isEqualTo("CHANGE_REQUEST");
        assertThat(created.get("details").get("url").asString()).isEqualTo("https://github.com/acme/shop/pull/12");
        assertThat(created.get("details").get("number").asString()).isEqualTo("12");
        assertThat(auditEvents(host.bench().owner(), host.bench().company(), "secret.accessed"))
            .anySatisfy(event -> assertThat(event.get("details").get("purpose").asString())
                .isEqualTo("change-request:" + runId));

        // A later turn pushes again: the same change request, its state due to be read again; nothing is opened.
        assertThat(column(host.bench(), "SELECT state_checked_at FROM run_change_request WHERE conversation_id = ?",
                bound.conversation().id())).doesNotContainNull();
        pushed(host, bound, host.shopId(), "shop", false, 1);
        assertThat(column(host.bench(), "SELECT state_checked_at FROM run_change_request WHERE conversation_id = ?",
                bound.conversation().id())).containsOnlyNulls();
        await().during(Duration.ofSeconds(1)).atMost(WAIT).untilAsserted(() -> GitHubStub.server()
            .verify(1, postRequestedFor(urlEqualTo(host.pulls("shop")))));
        assertThat(messages(host, bound)).isEmpty();
    }

    @Test
    void aSecondChangeRequestListsTheFirstWhoseBodyIsRewrittenAndTheWatchReadsTheStateUntilMerged() {
        Host host = codeHost("ALLOW");
        Bound bound = bound(host);
        opens(host, "shop", 12);
        opens(host, "web", 3);
        String shop = host.pulls("shop") + "/12";
        GitHubStub.server().stubFor(get(urlEqualTo(shop)).willReturn(GitHubStub.json(200, Map.of("number", 12,
                "state", "open", "body", "Fixed the totals.\n\n<!-- tinfirm:related -->\n<!-- /tinfirm:related -->"))));
        GitHubStub.server().stubFor(patch(urlEqualTo(shop)).willReturn(GitHubStub.json(200, Map.of("number", 12))));

        pushed(host, bound, host.shopId(), "shop", false, 2);
        awaitChangeRequests(host, bound, 1);
        pushed(host, bound, host.webId(), "web", false, 1);
        awaitChangeRequests(host, bound, 2);
        JsonNode web = JSON.readTree(GitHubStub.server().findAll(postRequestedFor(urlEqualTo(host.pulls("web"))))
            .getFirst()
            .getBodyAsString());
        assertThat(web.get("body").asString()).contains("<!-- tinfirm:related -->\nRelated change requests:\n- ")
            .contains(": https://github.com/acme/shop/pull/12\n<!-- /tinfirm:related -->");
        await().atMost(WAIT).untilAsserted(() -> GitHubStub.server().verify(1, patchRequestedFor(urlEqualTo(shop))));
        JsonNode rewritten = JSON.readTree(GitHubStub.server().findAll(patchRequestedFor(urlEqualTo(shop)))
            .getFirst()
            .getBodyAsString());
        assertThat(rewritten.get("body").asString()).startsWith("Fixed the totals.\n\n<!-- tinfirm:related -->\n"
                + "Related change requests:\n- ")
            .endsWith(": https://github.com/acme/web/pull/3\n<!-- /tinfirm:related -->");

        // The watch reads the states that are due: the shop's merged, the web's still open.
        GitHubStub.server().stubFor(get(urlEqualTo(shop)).willReturn(GitHubStub.json(200, Map.of("number", 12,
                "state", "closed", "merged", true))));
        GitHubStub.server().stubFor(get(urlEqualTo(host.pulls("web") + "/3")).willReturn(GitHubStub.json(200,
                Map.of("number", 3, "state", "open", "draft", true))));
        update(host.bench(), "UPDATE run_change_request SET state_checked_at = NULL WHERE conversation_id = ?",
                bound.conversation().id());
        assertThat(watch.refresh()).isGreaterThanOrEqualTo(2);
        assertThat(column(host.bench(), "SELECT state || ':' || watched FROM run_change_request "
                + "WHERE conversation_id = ? ORDER BY created_at", bound.conversation().id()))
            .containsExactly("MERGED:false", "DRAFT:true");
        assertThat(inCompany(host.bench(), () -> outputs.outputs(host.bench().companyId(), bound.conversation().id())))
            .extracting(ConversationOutputs.OutputInfo::changeRequestState)
            .containsExactly(ConversationOutputs.ChangeRequestState.MERGED,
                    ConversationOutputs.ChangeRequestState.DRAFT);
        // An ended conversation's change requests are no longer read.
        TestConversations.end(host.bench().companyId(), bound.conversation().id());
        transactions.runInCompany(host.bench().companyId(), () -> watch.conversationEnded(
                new com.flowtivo.tinfirm.conversation.ConversationEvents.ConversationEnded(host.bench().companyId(),
                        bound.conversation().id(), com.flowtivo.tinfirm.conversation.ConversationInfo.EndedReason.USER)));
        assertThat(column(host.bench(), "SELECT watched FROM run_change_request WHERE conversation_id = ?",
                bound.conversation().id())).containsOnly(false);
    }

    @Test
    void aSummaryWithTheRelatedMarkerLeavesTheLinkAndTheIssueLineToARewrite() {
        Host host = codeHost("ALLOW");
        Bound bound = bound(host);
        TestConversations.subject(host.bench().companyId(), bound.conversation().id(), host.systemId(),
                "acme/shop#57", "https://github.com/acme/shop/issues/57", "Totals are wrong");
        opens(host, "shop", 12);
        opens(host, "web", 3);
        // The agent's summary (injected, say, by a file it read) holds the block's first marker.
        pushed(host, bound, host.shopId(), "shop", false, 2, com.flowtivo.tinfirm.run.RunStatus.SUCCEEDED,
                "Fixed the totals. <!-- tinfirm:related -->");
        awaitChangeRequests(host, bound, 1);
        String opened = JSON.readTree(GitHubStub.server().findAll(postRequestedFor(urlEqualTo(host.pulls("shop"))))
            .getFirst()
            .getBodyAsString()).get("body").asString();
        String link = "http://localhost:8080/c/" + host.bench().companyId() + "/conversations/"
                + bound.conversation().id();
        assertThat(opened).startsWith("Fixed the totals. <!- - tinfirm:related -->\n\n" + link + "\n\nCloses #57\n\n");
        String shop = host.pulls("shop") + "/12";
        GitHubStub.server().stubFor(get(urlEqualTo(shop)).willReturn(GitHubStub.json(200, Map.of("number", 12,
                "state", "open", "body", opened))));
        GitHubStub.server().stubFor(patch(urlEqualTo(shop)).willReturn(GitHubStub.json(200, Map.of("number", 12))));

        // A second change request rewrites the first's block, and only it.
        pushed(host, bound, host.webId(), "web", false, 1);
        awaitChangeRequests(host, bound, 2);
        await().atMost(WAIT).untilAsserted(() -> GitHubStub.server().verify(1, patchRequestedFor(urlEqualTo(shop))));
        String rewritten = JSON.readTree(GitHubStub.server().findAll(patchRequestedFor(urlEqualTo(shop)))
            .getFirst()
            .getBodyAsString()).get("body").asString();
        assertThat(rewritten).startsWith("Fixed the totals. <!- - tinfirm:related -->\n\n" + link
                + "\n\nCloses #57\n\n<!-- tinfirm:related -->\nRelated change requests:\n- ")
            .contains(": https://github.com/acme/web/pull/3\n<!-- /tinfirm:related -->\n\nOpened by ");
    }

    @Test
    void aPushAfterItsConversationEndedLeavesTheChangeRequestUnwatched() {
        Host host = codeHost("ALLOW");
        Bound bound = bound(host);
        opens(host, "shop", 12);
        pushed(host, bound, host.shopId(), "shop", false, 2);
        awaitChangeRequests(host, bound, 1);

        // The conversation ends during a turn that pushes: its change request is no longer watched...
        TestConversations.end(host.bench().companyId(), bound.conversation().id());
        transactions.runInCompany(host.bench().companyId(), () -> watch.conversationEnded(
                new com.flowtivo.tinfirm.conversation.ConversationEvents.ConversationEnded(host.bench().companyId(),
                        bound.conversation().id(), com.flowtivo.tinfirm.conversation.ConversationInfo.EndedReason.USER)));
        // ... and the turn's end, which reports the push, leaves it so.
        pushed(host, bound, host.shopId(), "shop", false, 1, com.flowtivo.tinfirm.run.RunStatus.CANCELLED,
                "Stopped.");
        assertThat(column(host.bench(), "SELECT watched FROM run_change_request WHERE conversation_id = ?",
                bound.conversation().id())).containsExactly(false);

        // The watch never reads it again: no token of the conversation's binding is used, then or five minutes on.
        int accessed = auditEvents(host.bench().owner(), host.bench().company(), "secret.accessed").size();
        watch.refresh();
        update(host.bench(), "UPDATE run_change_request SET state_checked_at = now() - interval '1 hour' "
                + "WHERE conversation_id = ?", bound.conversation().id());
        watch.refresh();
        assertThat(auditEvents(host.bench().owner(), host.bench().company(), "secret.accessed")).hasSize(accessed);
    }

    @Test
    void aReadOnlyRepositoryAReadOnlyGrantAnAgentWithoutTheToolAndARefusalOpenNone() {
        Host host = codeHost("ALLOW");
        opens(host, "shop", 12);
        // A read-only repository (I9): nothing at all.
        Bound readOnly = bound(host);
        pushed(host, readOnly, host.shopId(), "shop", true, 2);
        // No new commits: nothing either.
        Bound noCommits = bound(host);
        pushed(host, noCommits, host.shopId(), "shop", false, 0);

        // A grant lowered to READ after the turn: the opener refuses (I9), with a message.
        host.github().user("test-github-token-of-the-bot", "the-bot");
        UUID accountId = TestCredentials.add(host.bench().owner(), host.bench().path(""), host.systemId(), "Bot",
                "test-github-token-of-the-bot");
        UUID groupId = createGroup(host.bench().owner(), host.bench().company(), unique("Analysts"),
                List.of(host.bench().company().userId("MEMBER")));
        UUID grantId = createGrant(host.bench().owner(), host.bench().company(), accountId, groupId, "READ");
        Conversation granted = conversation(host.bench());
        UUID grantBinding = TestConversations.bind(host.bench().companyId(), granted.id(), host.systemId(), accountId,
                null, grantId);
        Bound viaGrant = new Bound(granted, grantBinding);
        pushed(host, viaGrant, host.shopId(), "shop", false, 2);
        assertThat(awaitMessages(host, viaGrant)).singleElement().asString()
            .isEqualTo("Tinfirm did not open a pull request for `agent/" + granted.key() + "` in `"
                    + repositoryName(host, host.shopId()) + "`: this conversation acts through a read-only grant "
                    + "there.");

        // The code host refuses: a message with its answer.
        Bound refused = bound(host);
        GitHubStub.server().stubFor(post(urlEqualTo(host.pulls("web"))).willReturn(GitHubStub.json(403,
                Map.of("message", "Resource not accessible by integration"))));
        pushed(host, refused, host.webId(), "web", false, 1);
        assertThat(awaitMessages(host, refused)).singleElement().asString()
            .isEqualTo("Tinfirm could not open the pull request for `agent/" + refused.conversation().key()
                    + "` in `" + repositoryName(host, host.webId()) + "`: GitHub answered 403: Resource not "
                    + "accessible by integration.");

        // An agent without open_change_request leaves the branch pushed, and says so.
        Host withoutTool = codeHost(null);
        Bound plain = bound(withoutTool);
        pushed(withoutTool, plain, withoutTool.shopId(), "shop", false, 2);

        await().during(Duration.ofSeconds(1)).atMost(WAIT).untilAsserted(() -> GitHubStub.server()
            .verify(0, postRequestedFor(urlEqualTo(host.pulls("shop")))));
        GitHubStub.server().verify(0, postRequestedFor(urlEqualTo(withoutTool.pulls("shop"))));
        for (Bound none : List.of(readOnly, noCommits)) {
            assertThat(messages(host, none)).isEmpty();
            assertThat(column(host.bench(), "SELECT id FROM output WHERE conversation_id = ?",
                    none.conversation().id())).isEmpty();
        }
        assertThat(messages(withoutTool, plain)).containsExactly("Tinfirm did not open a pull request for `agent/"
                + plain.conversation().key() + "` in `shop`: " + withoutTool.bench().agentName() + " does not have "
                + "`open_change_request` on " + systemName(withoutTool) + ", so the branch stays pushed without one.");
        // The same commit pushed again is not said again.
        pushed(withoutTool, plain, withoutTool.shopId(), "shop", false, 2);
        assertThat(messages(withoutTool, plain)).hasSize(1);
    }

    @Test
    void aPushAfterTheChangeRequestWasMergedOpensTheNextOneButTheCommitItWasMergedAtOpensNone() {
        Host host = codeHost("ALLOW");
        Bound bound = bound(host);
        opens(host, "shop", 12);
        pushedAt(host, bound, host.shopId(), "shop", MERGED_AT);
        awaitChangeRequests(host, bound, 1);

        // The watch reads it merged at the commit pushed: nothing follows.
        reads(host, "shop", 12, "closed", true, MERGED_AT);
        update(host.bench(), "UPDATE run_change_request SET state_checked_at = NULL WHERE conversation_id = ?",
                bound.conversation().id());
        assertThat(watch.refresh()).isGreaterThanOrEqualTo(1);
        assertThat(column(host.bench(), "SELECT state || ':' || watched || ':' || head_sha FROM run_change_request "
                + "WHERE conversation_id = ?", bound.conversation().id()))
            .containsExactly("MERGED:false:" + MERGED_AT);

        // A turn pushes that commit again (a squash merge leaves it ahead of main): nothing is opened.
        pushedAt(host, bound, host.shopId(), "shop", MERGED_AT);
        await().during(Duration.ofSeconds(1)).atMost(WAIT).untilAsserted(() -> GitHubStub.server()
            .verify(1, postRequestedFor(urlEqualTo(host.pulls("shop")))));

        // A turn pushes a new commit: the next pull request, which lists the merged one.
        opens(host, "shop", 13);
        GitHubStub.server().stubFor(patch(urlEqualTo(host.pulls("shop") + "/12"))
            .willReturn(GitHubStub.json(200, Map.of("number", 12))));
        UUID runId = pushedAt(host, bound, host.shopId(), "shop", NEXT);
        awaitChangeRequests(host, bound, 2);
        List<com.github.tomakehurst.wiremock.verification.LoggedRequest> posts = GitHubStub.server()
            .findAll(postRequestedFor(urlEqualTo(host.pulls("shop"))));
        assertThat(posts).hasSize(2);
        assertThat(JSON.readTree(posts.getLast().getBodyAsString()).get("body").asString())
            .contains("Related change requests:\n- ").contains("https://github.com/acme/shop/pull/12");
        assertThat(inCompany(host.bench(), () -> outputs.outputs(host.bench().companyId(), bound.conversation().id())))
            .extracting(ConversationOutputs.OutputInfo::externalNumber, ConversationOutputs.OutputInfo::changeRequestState)
            .containsExactly(org.assertj.core.groups.Tuple.tuple("12", ConversationOutputs.ChangeRequestState.MERGED),
                    org.assertj.core.groups.Tuple.tuple("13", ConversationOutputs.ChangeRequestState.OPEN));
        assertThat(column(host.bench(), "SELECT run_id FROM run_change_request WHERE conversation_id = ? AND number = 13",
                bound.conversation().id())).containsExactly(runId);
        assertThat(messages(host, bound)).isEmpty();

        // The next push goes to the new one.
        pushedAt(host, bound, host.shopId(), "shop", NEXT);
        await().during(Duration.ofSeconds(1)).atMost(WAIT).untilAsserted(() -> GitHubStub.server()
            .verify(2, postRequestedFor(urlEqualTo(host.pulls("shop")))));
    }

    @Test
    void aPushTheMergedChangeRequestDoesNotHaveGetsTheNextOneWhenTheWatchReadsTheMergeLater() {
        Host host = codeHost("ALLOW");
        // Merged at MERGED_AT; a turn pushes a new commit before Tinfirm read the merge.
        Bound bound = bound(host);
        opens(host, "shop", 12);
        pushedAt(host, bound, host.shopId(), "shop", MERGED_AT);
        awaitChangeRequests(host, bound, 1);
        opens(host, "shop", 13);
        pushedAt(host, bound, host.shopId(), "shop", NEXT);
        await().during(Duration.ofSeconds(1)).atMost(WAIT).untilAsserted(() -> GitHubStub.server()
            .verify(1, postRequestedFor(urlEqualTo(host.pulls("shop")))));
        // The watch reads the merge: the push gets the next one.
        reads(host, "shop", 12, "closed", true, MERGED_AT);
        assertThat(watch.refresh()).isGreaterThanOrEqualTo(1);
        awaitChangeRequests(host, bound, 2);

        // A change request merged before Tinfirm kept the commit it was merged at (V038): the next push has it read
        // once more, and the watch then opens the next one.
        Bound legacy = bound(host);
        opens(host, "web", 3);
        pushedAt(host, legacy, host.webId(), "web", MERGED_AT);
        awaitChangeRequests(host, legacy, 1);
        update(host.bench(), "UPDATE run_change_request SET state = 'MERGED', watched = false, head_sha = NULL "
                + "WHERE conversation_id = ?", legacy.conversation().id());
        opens(host, "web", 4);
        pushedAt(host, legacy, host.webId(), "web", NEXT);
        assertThat(column(host.bench(), "SELECT watched FROM run_change_request WHERE conversation_id = ?",
                legacy.conversation().id())).containsExactly(true);
        reads(host, "web", 3, "closed", true, MERGED_AT);
        watch.refresh();
        awaitChangeRequests(host, legacy, 2);
        assertThat(column(host.bench(), "SELECT number FROM run_change_request WHERE conversation_id = ? "
                + "ORDER BY created_at", legacy.conversation().id())).containsExactly(3, 4);
    }

    @Test
    void aPushToAClosedChangeRequestOpensNoneAndSaysSoOnce() {
        Host host = codeHost("ALLOW");
        Bound bound = bound(host);
        opens(host, "shop", 12);
        pushedAt(host, bound, host.shopId(), "shop", MERGED_AT);
        awaitChangeRequests(host, bound, 1);
        update(host.bench(), "UPDATE run_change_request SET state = 'CLOSED' WHERE conversation_id = ?",
                bound.conversation().id());

        pushedAt(host, bound, host.shopId(), "shop", NEXT);
        String said = "Tinfirm did not open a pull request for `agent/" + bound.conversation().key() + "` in `shop`: "
                + "this conversation's pull request #12 there is closed, so the push is on none that is open. Reopen "
                + "#12 to have it reviewed.";
        assertThat(messages(host, bound)).containsExactly(said);
        // Its state is read again, and the same commit pushed again is not said again.
        assertThat(column(host.bench(), "SELECT state_checked_at FROM run_change_request WHERE conversation_id = ?",
                bound.conversation().id())).containsOnlyNulls();
        pushedAt(host, bound, host.shopId(), "shop", NEXT);
        assertThat(messages(host, bound)).containsExactly(said);
        await().during(Duration.ofSeconds(1)).atMost(WAIT).untilAsserted(() -> GitHubStub.server()
            .verify(1, postRequestedFor(urlEqualTo(host.pulls("shop")))));
    }

    @Test
    void underAskThePersonApprovesAndTheChangeRequestOpensWithTheirAccount() {
        Host host = codeHost("ASK");
        Bound bound = bound(host);
        opens(host, "shop", 12);
        UUID runId = pushed(host, bound, host.shopId(), "shop", false, 2);

        // The turn's end raised part 2's approval, to the person, with its card in the thread; nothing is opened.
        List<Object> open = column(host.bench(), "SELECT id FROM decision WHERE conversation_id = ? AND status = "
                + "'OPEN'", bound.conversation().id());
        assertThat(open).hasSize(1);
        UUID decisionId = (UUID) open.getFirst();
        JsonNode decision = host.bench().person().get(host.bench().path("/decisions/" + decisionId)).json();
        assertThat(decision.get("kind").asString()).isEqualTo("APPROVAL");
        assertThat(decision.get("mayDecide").asBoolean()).isTrue();
        assertThat(decision.get("subject").get("type").asString()).isEqualTo("CHANGE_REQUEST");
        assertThat(decision.get("subject").get("repositoryId").asString()).isEqualTo(host.shopId().toString());
        assertThat(messages(host, bound)).singleElement().asString().isEqualTo("Open a pull request of `agent/"
                + bound.conversation().key() + "` in shop on " + systemName(host) + ".\n\nIt opens once you approve "
                + "it.");
        GitHubStub.server().verify(0, postRequestedFor(urlEqualTo(host.pulls("shop"))));

        // The person approves: after the commit, part 3 opens it with the person's account, for the turn that pushed.
        ApiResponse approved = host.bench().person().post(host.bench().path("/decisions/" + decisionId + "/decide"),
                Map.of("outcome", "APPROVED"));
        assertThat(approved.status()).as("%s", approved).isEqualTo(200);
        awaitChangeRequests(host, bound, 1);
        GitHubStub.server().verify(1, postRequestedFor(urlEqualTo(host.pulls("shop")))
            .withHeader("Authorization", equalTo("Bearer " + TOKEN)));
        assertThat(inCompany(host.bench(), () -> outputs.outputs(host.bench().companyId(), bound.conversation().id())))
            .singleElement()
            .satisfies(output -> {
                assertThat(output.kind()).isEqualTo(ConversationOutputs.Kind.CHANGE_REQUEST);
                assertThat(output.runId()).isEqualTo(runId);
            });
        assertThat(auditEvents(host.bench().owner(), host.bench().company(), "secret.accessed"))
            .anySatisfy(event -> assertThat(event.get("details").get("purpose").asString())
                .isEqualTo("change-request:" + runId));
    }

    private String systemName(Host host) {
        return (String) column(host.bench(), "SELECT name FROM external_system WHERE id = ?", host.systemId())
            .getFirst();
    }

    private String repositoryName(Host host, UUID repositoryId) {
        return (String) column(host.bench(), "SELECT name FROM system_repository WHERE id = ?", repositoryId)
            .getFirst();
    }
}
