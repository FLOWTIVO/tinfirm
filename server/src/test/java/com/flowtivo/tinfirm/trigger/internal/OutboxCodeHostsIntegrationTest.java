package com.flowtivo.tinfirm.trigger.internal;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.patchRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.FakeRunner;
import com.flowtivo.tinfirm.support.GitHubStub;
import com.flowtivo.tinfirm.support.GitLabStub;

import tools.jackson.databind.json.JsonMapper;

/**
 * The write-back to GitHub's and GitLab's issues (the package 4 contract, §5.9, T9, T13): the result's comment with the
 * agent's label and the row's marker, labels added and removed, the close; a comment whose attempt may have reached
 * the system found again by its marker and not written twice; the labels and the close in every poll's snapshot. Against
 * the GitHub and GitLab stubs. TEST FIXTURE ONLY: every token is a test value.
 */
class OutboxCodeHostsIntegrationTest extends OutboxTestSupport {

    static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void aGitHubResultLabelsAndClosesTheIssueAndAnAmbiguousCommentIsFoundByItsMarker() throws Exception {
        Bench bench = bench("Hub");
        GitHubStub.Issues issues = GitHubStub.newIssues();
        UUID system = createSystem(bench.owner(), bench.company(), unique("GitHub"), "GITHUB", issues.baseUrl(),
                false);
        String token = "test-github-" + unique("alice");
        issues.user(token, "alice-gh");
        UUID account = account(bench, system, token);
        UUID triggerId = trigger(bench.alice(), bench, system, "acme/shop", account, bench.alicePrincipal(),
                bench.team(), results(true, false, List.of(action("LABEL_ADD", "ready"), action("LABEL_REMOVE",
                        "triage"), action("CLOSE", null)), List.of()));
        UUID poll = snapshot(bench.id(), system, account, bench.alicePrincipal(), "I_57", "acme/shop#57", null,
                "[\"triage\", \"web\"]");
        issues.writes("acme/shop", 57, 4242, false);
        String comments = issues.url("/repos/acme/shop/issues/57/comments");
        // The comment's answer is lost: a 503, which may have written it.
        GitHubStub.server().stubFor(post(urlEqualTo(comments)).willReturn(aResponse().withStatus(503)));

        try (FakeRunner runner = runner(bench)) {
            UUID conversationId = start(bench, triggerId, system, "I_57", "acme/shop#57",
                    "https://github.com/acme/shop/issues/57").conversation().id();
            Turn turn = take(runner);
            runner.send(completed(turn, "Labelled and closed.", List.of()));
            List<Map<String, Object>> first = awaitOutbox(bench.id(), conversationId, now -> now.size() == 4
                    && ((Number) now.getFirst().get("attempts")).intValue() == 1);
            assertThat(column(first, "kind")).containsExactly("COMMENT", "LABEL_ADD", "LABEL_REMOVE", "CLOSE");
            String marker = "<!-- tinfirm-outbox:" + first.getFirst().get("id") + " -->";
            String posted = JSON.readTree(GitHubStub.server().findAll(postRequestedFor(urlEqualTo(comments)))
                .getFirst().getBodyAsString()).get("body").asString();
            assertThat(posted).startsWith("**Hub (Tinfirm agent):** Labelled and closed.").endsWith(marker);

            // T13: the issue shows the comment with the row's marker: taken as written, and not written again.
            GitHubStub.server().stubFor(get(urlEqualTo(issues.url("/repos/acme/shop/issues/57")))
                .willReturn(GitHubStub.json(200, GitHubStub.issue(9057, 57, "acme/shop", "Checkout fails", "",
                        List.of("alice-gh"), List.of("triage"), "2026-10-12T10:00:00Z", 2))));
            issues.comments("acme/shop", 57, List.of(GitHubStub.comment(4240, "carol", "Looks odd.",
                    "2026-10-12T09:00:00Z"), GitHubStub.comment(4241, "alice-gh", posted, "2026-10-12T10:00:00Z")));
            dueNow(bench.id(), conversationId);
            delivery.deliverDue();
            List<Map<String, Object>> rows = awaitOutbox(bench.id(), conversationId, now -> allDelivered(now, 4));
            assertThat(rows.getFirst().get("external_id")).isEqualTo("4241");
            runner.assertFramesValid();
        }
        GitHubStub.server().verify(1, postRequestedFor(urlEqualTo(comments)));
        GitHubStub.server().verify(postRequestedFor(urlEqualTo(issues.url("/repos/acme/shop/issues/57/labels")))
            .withHeader("Authorization", equalTo("Bearer " + token))
            .withRequestBody(equalToJson("{\"labels\":[\"ready\"]}")));
        GitHubStub.server().verify(deleteRequestedFor(urlEqualTo(issues.url(
                "/repos/acme/shop/issues/57/labels/triage"))));
        GitHubStub.server().verify(patchRequestedFor(urlEqualTo(issues.url("/repos/acme/shop/issues/57")))
            .withRequestBody(containing("closed")));

        // T9: the labels and the close in every poll's snapshot.
        Map<String, Object> snapshot = snapshotOf(bench.id(), poll, "I_57");
        assertThat(String.valueOf(snapshot.get("labels"))).contains("ready", "web").doesNotContain("triage");
        assertThat(snapshot.get("open")).isEqualTo(false);
    }

    @Test
    void aGitLabResultIsANoteWithItsLabelThenTheLabelAndTheClose() throws Exception {
        Bench bench = bench("Lab");
        String path = "/gl-" + UUID.randomUUID().toString().substring(0, 8);
        String base = GitLabStub.origin() + path;
        String token = "test-gitlab-" + unique("alice");
        GitLabStub.server().stubFor(get(urlEqualTo(path + "/api/v4/user"))
            .withHeader("PRIVATE-TOKEN", equalTo(token))
            .willReturn(GitHubStub.json(200, Map.of("id", 77, "username", "alice-gl", "name", "Alice"))));
        UUID system = createSystem(bench.owner(), bench.company(), unique("GitLab"), "GITLAB", base, false);
        UUID account = account(bench, system, token);
        UUID triggerId = trigger(bench.alice(), bench, system, "acme/shop", account, bench.alicePrincipal(),
                bench.team(), results(true, false, List.of(action("LABEL_ADD", "ready"), action("CLOSE", null)),
                        List.of()));
        String issue = path + "/api/v4/projects/acme%2Fshop/issues/12";
        GitLabStub.server().stubFor(post(urlEqualTo(issue + "/notes")).withHeader("PRIVATE-TOKEN", equalTo(token))
            .willReturn(GitHubStub.json(201, Map.of("id", 5151))));
        GitLabStub.server().stubFor(put(urlEqualTo(issue)).willReturn(GitHubStub.json(200, Map.of("iid", 12))));

        try (FakeRunner runner = runner(bench)) {
            UUID conversationId = start(bench, triggerId, system, "G_12", "acme/shop#12",
                    "https://gitlab.example.test/acme/shop/-/issues/12").conversation().id();
            Turn turn = take(runner);
            runner.send(completed(turn, "Ready to ship.", List.of("Tag the release.")));
            List<Map<String, Object>> rows = awaitOutbox(bench.id(), conversationId, now -> allDelivered(now, 3));
            assertThat(column(rows, "kind")).containsExactly("COMMENT", "LABEL_ADD", "CLOSE");
            assertThat(rows.getFirst().get("external_id")).isEqualTo("5151");
            String note = JSON.readTree(GitLabStub.server().findAll(postRequestedFor(urlEqualTo(issue + "/notes")))
                .getFirst().getBodyAsString()).get("body").asString();
            assertThat(note).startsWith("**Lab (Tinfirm agent):** Ready to ship.")
                .contains("Tag the release.", "<!-- tinfirm-outbox:" + rows.getFirst().get("id") + " -->");
            runner.assertFramesValid();
        }
        GitLabStub.server().verify(putRequestedFor(urlEqualTo(issue)).withRequestBody(containing("add_labels")));
        GitLabStub.server().verify(putRequestedFor(urlEqualTo(issue)).withRequestBody(containing("close")));
        assertThat(sourceWritten(bench)).hasSize(3);
    }

    /** Alice's own account on the system, by its token (the system's "who am I" answers it). */
    private UUID account(Bench bench, UUID system, String token) {
        ApiResponse response = bench.alice().put(bench.path("/me/accounts/" + system + "/token"),
                Map.of("token", token));
        assertThat(response.status()).as("token: %s", response).isEqualTo(200);
        return UUID.fromString(response.json().get("id").asString());
    }
}
