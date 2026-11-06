package com.flowtivo.tinfirm.system;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.shared.security.SystemContext;
import com.flowtivo.tinfirm.support.GitHubStub;
import com.flowtivo.tinfirm.support.GitLabStub;

/**
 * GitLab's fixed tools and its merge requests through {@link SystemTools} against a stub of REST API v4 on https: a
 * personal access token in {@code PRIVATE-TOKEN}, an OAuth token as a bearer token; issues {@code group/project#iid}
 * and merge requests {@code group/project!iid}; the system's own notes are not comments. TEST FIXTURE ONLY: the tokens
 * are test values.
 */
class GitLabToolsTest extends ToolsTestSupport {

    private static final String TOKEN = "test-gitlab-token-0001";

    /** A base URL of its own under the stub's origin, as a GitLab under a path: its API is {@code {base}/api/v4}. */
    private static String base() {
        return GitLabStub.origin() + "/gl-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String path(String base) {
        return base.substring(GitLabStub.origin().length());
    }

    private static Map<String, Object> mergeRequest(int iid, String title, String state, String updated, int notes) {
        Map<String, Object> mergeRequest = new LinkedHashMap<>();
        mergeRequest.put("id", 1000 + iid);
        mergeRequest.put("iid", iid);
        mergeRequest.put("title", title);
        mergeRequest.put("description", "Fixes the **totals**");
        mergeRequest.put("state", state);
        mergeRequest.put("labels", List.of("billing"));
        mergeRequest.put("assignees", List.of(Map.of("username", "alice", "name", "Alice Adams")));
        mergeRequest.put("author", Map.of("username", "bob", "name", "Bob Brown"));
        mergeRequest.put("web_url", "https://gitlab.example.test/acme/shop/-/merge_requests/" + iid);
        mergeRequest.put("references", Map.of("full", "acme/shop!" + iid));
        mergeRequest.put("created_at", "2026-10-01T10:00:00Z");
        mergeRequest.put("updated_at", updated);
        mergeRequest.put("user_notes_count", notes);
        return mergeRequest;
    }

    @Test
    void getReadsAMergeRequestWithThePeoplesNotesAndSearchMergesIssuesAndMergeRequests() {
        String base = base();
        Setup setup = setup("GITLAB", base);
        String project = path(base) + "/api/v4/projects/acme%2Fshop";
        GitLabStub.server().stubFor(get(urlEqualTo(project + "/merge_requests/7"))
            .withHeader("PRIVATE-TOKEN", equalTo(TOKEN))
            .willReturn(GitHubStub.json(200, mergeRequest(7, "Fix totals", "merged", "2026-10-02T10:00:00Z", 2))));
        GitLabStub.server().stubFor(get(urlPathEqualTo(project + "/merge_requests/7/notes"))
            .withQueryParam("sort", equalTo("desc"))
            .withQueryParam("per_page", equalTo("20"))
            .willReturn(GitHubStub.json(200, List.of(
                    Map.of("id", 3, "body", "approved this merge request", "system", true, "created_at",
                            "2026-10-02T09:40:00Z", "author", Map.of("username", "carol")),
                    Map.of("id", 2, "body", "LGTM", "system", false, "created_at", "2026-10-02T09:30:00Z", "author",
                            Map.of("username", "carol", "name", "Carol C")),
                    Map.of("id", 1, "body", "Please add a test", "system", false, "created_at",
                            "2026-10-02T09:00:00Z", "author", Map.of("username", "dave"))))));
        Map<String, Object> issue = new LinkedHashMap<>(mergeRequest(9, "Totals wrong", "opened",
                "2026-10-03T10:00:00Z", 0));
        issue.put("references", Map.of("full", "acme/shop#9"));
        issue.put("web_url", "https://gitlab.example.test/acme/shop/-/issues/9");
        GitLabStub.server().stubFor(get(urlPathEqualTo(path(base) + "/api/v4/issues"))
            .withQueryParam("search", equalTo("totals"))
            .withQueryParam("scope", equalTo("all"))
            .willReturn(GitHubStub.json(200, List.of(issue))));
        GitLabStub.server().stubFor(get(urlPathEqualTo(path(base) + "/api/v4/merge_requests"))
            .withQueryParam("search", equalTo("totals"))
            .willReturn(GitHubStub.json(200, List.of(mergeRequest(7, "Fix totals", "merged",
                    "2026-10-02T10:00:00Z", 2)))));

        ToolOutcome got = call(setup, token(TOKEN), "get", "{\"key\":\"acme/shop!7\"}");
        assertThat(text(got)).contains("# acme/shop!7: Fix totals", "Kind: change request", "State: closed",
                "Assignees: Alice Adams (alice)", "Fixes the **totals**", "**dave** · 2026-10-02T09:00:00Z\n> Please "
                        + "add a test", "**Carol C** · 2026-10-02T09:30:00Z\n> LGTM")
            .doesNotContain("approved this merge request");
        assertThat(structured(got).get("kind").asString()).isEqualTo("CHANGE_REQUEST");

        ToolOutcome found = call(setup, token(TOKEN), "search", "{\"query\":\"totals\",\"limit\":1}");
        assertThat(text(found)).isEqualTo("1 result\n\nacme/shop#9 Totals wrong (open; labels: billing; assignees: "
                + "Alice Adams)");
    }

    @Test
    void writesCommentLabelAndCloseWithEitherKindOfToken() {
        String base = base();
        Setup setup = setup("GITLAB", base);
        String issue = path(base) + "/api/v4/projects/acme%2Fshop/issues/9";
        GitLabStub.server().stubFor(post(urlEqualTo(issue + "/notes")).withHeader("Authorization",
                equalTo("Bearer test-gitlab-oauth-0001")).willReturn(GitHubStub.json(201, Map.of("id", 501))));
        GitLabStub.server().stubFor(put(urlEqualTo(issue)).withHeader("PRIVATE-TOKEN", equalTo(TOKEN))
            .willReturn(GitHubStub.json(200, Map.of("iid", 9))));

        ToolOutcome commented = call(setup, oauth("test-gitlab-oauth-0001"), "comment", "{\"key\":\"acme/shop#9\","
                + "\"body\":\"- fixed\\n- tested\"}", "Dev (Tinfirm agent) for Alice:");
        assertThat(text(commented)).isEqualTo("Commented on acme/shop#9 (comment 501).");
        GitLabStub.server().verify(postRequestedFor(urlEqualTo(issue + "/notes"))
            .withRequestBody(equalToJson("{\"body\":\"**Dev (Tinfirm agent) for Alice:**\\n\\n- fixed\\n- tested\"}")));

        ToolOutcome labelled = call(setup, token(TOKEN), "label", "{\"key\":\"acme/shop#9\",\"add\":[\"bug\","
                + "\"web\"],\"remove\":[\"triage\"]}");
        assertThat(done(labelled).result().isError()).isFalse();
        GitLabStub.server().verify(putRequestedFor(urlEqualTo(issue))
            .withRequestBody(equalToJson("{\"add_labels\":\"bug,web\"}")));
        GitLabStub.server().verify(putRequestedFor(urlEqualTo(issue))
            .withRequestBody(equalToJson("{\"remove_labels\":\"triage\"}")));

        assertThat(text(call(setup, token(TOKEN), "close", "{\"key\":\"acme/shop#9\"}"))).isEqualTo("Closed "
                + "acme/shop#9.");
        GitLabStub.server().verify(putRequestedFor(urlEqualTo(issue))
            .withRequestBody(equalToJson("{\"state_event\":\"close\"}")));

        // A refused token: the account must be connected again.
        GitLabStub.server().stubFor(put(urlEqualTo(issue)).withHeader("PRIVATE-TOKEN", equalTo("test-gitlab-old-0001"))
            .willReturn(GitHubStub.json(401, Map.of("message", "401 Unauthorized"))));
        assertThat(call(setup, token("test-gitlab-old-0001"), "close", "{\"key\":\"acme/shop#9\"}"))
            .isEqualTo(new ToolOutcome.Reconnect("GitLab answered 401: 401 Unauthorized"));
    }

    @Test
    void aMergeRequestOpensIntoTheDefaultBranchOrIsTheOneOfTheSourceBranch() {
        String base = base();
        Setup setup = setup("GITLAB", base);
        UUID shop = addRepository(setup, "https://localhost/acme/shop.git", null);
        UUID web = addRepository(setup, "https://localhost/acme/web", "develop");
        String api = path(base) + "/api/v4/projects/";
        GitLabStub.server().stubFor(get(urlEqualTo(api + "acme%2Fshop")).willReturn(GitHubStub.json(200,
                Map.of("default_branch", "main"))));
        GitLabStub.server().stubFor(post(urlEqualTo(api + "acme%2Fshop/merge_requests")).willReturn(GitHubStub.json(201,
                Map.of("iid", 4, "web_url", "https://gitlab.example.test/acme/shop/-/merge_requests/4", "state",
                        "opened"))));
        GitLabStub.server().stubFor(get(urlEqualTo(api + "acme%2Fshop/merge_requests/4")).willReturn(GitHubStub.json(
                200, Map.of("iid", 4, "state", "merged", "description", "Summary\n\nOld block", "sha",
                        "9f1e2d3c4b5a69788796a5b4c3d2e1f0a9b8c7d6"))));
        GitLabStub.server().stubFor(put(urlEqualTo(api + "acme%2Fshop/merge_requests/4")).willReturn(GitHubStub.json(
                200, Map.of("iid", 4))));
        GitLabStub.server().stubFor(post(urlEqualTo(api + "acme%2Fweb/merge_requests")).willReturn(GitHubStub.json(409,
                Map.of("message", List.of("Another open merge request already exists for this source branch: !2")))));
        GitLabStub.server().stubFor(get(urlEqualTo(api + "acme%2Fweb/merge_requests?source_branch=agent%2Fx-1&state=all"))
            .willReturn(GitHubStub.json(200, List.of(
                    Map.of("iid", 1, "state", "merged", "web_url", "https://gitlab.example.test/acme/web/-/merge_requests/1"),
                    Map.of("iid", 2, "state", "opened", "draft", true, "web_url",
                            "https://gitlab.example.test/acme/web/-/merge_requests/2")))));
        ChangeRequests.New request = new ChangeRequests.New("X-1: Fix", "agent/x-1", null, "Summary");

        CallResult<ChangeRequests.Opened> opened = SystemContext.callInCompany(setup.company().id(),
                () -> tools.openChangeRequest(setup.company().id(), shop, token(TOKEN), request));
        assertThat(opened.value()).isEqualTo(new ChangeRequests.Opened(
                "https://gitlab.example.test/acme/shop/-/merge_requests/4", 4, true, ChangeRequests.State.OPEN));
        GitLabStub.server().verify(postRequestedFor(urlEqualTo(api + "acme%2Fshop/merge_requests"))
            .withHeader("PRIVATE-TOKEN", equalTo(TOKEN))
            .withRequestBody(equalToJson("{\"source_branch\":\"agent/x-1\",\"target_branch\":\"main\",\"title\":"
                    + "\"X-1: Fix\",\"description\":\"Summary\",\"remove_source_branch\":false}")));

        CallResult<ChangeRequests.Opened> existing = SystemContext.callInCompany(setup.company().id(),
                () -> tools.openChangeRequest(setup.company().id(), web, token(TOKEN), request));
        assertThat(existing.value()).isEqualTo(new ChangeRequests.Opened(
                "https://gitlab.example.test/acme/web/-/merge_requests/2", 2, false, ChangeRequests.State.DRAFT));
        GitLabStub.server().verify(postRequestedFor(urlEqualTo(api + "acme%2Fweb/merge_requests"))
            .withRequestBody(equalToJson("{\"target_branch\":\"develop\"}", true, true)));

        // Its state, and the commit it was merged at.
        assertThat(SystemContext.callInCompany(setup.company().id(), () -> tools.changeRequestState(
                setup.company().id(), shop, token(TOKEN), 4)).value()).isEqualTo(new ChangeRequests.Read(
                        ChangeRequests.State.MERGED, "9f1e2d3c4b5a69788796a5b4c3d2e1f0a9b8c7d6"));
        // The body, read and replaced (the related block of a conversation's change requests).
        assertThat(SystemContext.callInCompany(setup.company().id(), () -> tools.changeRequestBody(
                setup.company().id(), shop, token(TOKEN), 4)).value()).isEqualTo("Summary\n\nOld block");
        assertThat(SystemContext.callInCompany(setup.company().id(), () -> tools.updateChangeRequestBody(
                setup.company().id(), shop, token(TOKEN), 4, "Summary\n\nNew block")).value()).isTrue();
        GitLabStub.server().verify(putRequestedFor(urlEqualTo(api + "acme%2Fshop/merge_requests/4"))
            .withHeader("PRIVATE-TOKEN", equalTo(TOKEN))
            .withRequestBody(equalToJson("{\"description\":\"Summary\\n\\nNew block\"}")));
    }
}
