package com.flowtivo.tinfirm.system;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.patch;
import static com.github.tomakehurst.wiremock.client.WireMock.patchRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.shared.security.SystemContext;
import com.flowtivo.tinfirm.support.GitHubStub;

import tools.jackson.databind.JsonNode;

/**
 * GitHub's fixed tools and its pull requests through {@link SystemTools} against a stub of the REST API: each tool's
 * request and result, the rate limit (a {@code 403} with GitHub's headers) told from a {@code 403} of permissions, and
 * opening a pull request (into the default branch, or the one of the branch that is open already). TEST FIXTURE ONLY:
 * the tokens are test values.
 */
class GitHubToolsTest extends ToolsTestSupport {

    private static final String TOKEN = "test-github-token-0001";

    private static final String ISSUE = "/repos/acme/shop/issues/57";

    @Test
    void getReadsAnIssueWithItsNewestCommentsAndSearchUsesGitHubsSyntax() {
        GitHubStub.Issues github = GitHubStub.newIssues();
        Setup setup = setup("GITHUB", github.baseUrl());
        Map<String, Object> issue = new java.util.LinkedHashMap<>(GitHubStub.issue(9001, 57, "acme/shop",
                "Checkout fails", "Steps:\n1. pay", List.of("alice"), List.of("bug"), "2026-10-02T10:00:00Z", 2));
        issue.put("user", Map.of("login", "bob"));
        GitHubStub.server().stubFor(get(urlEqualTo(github.path() + ISSUE))
            .withHeader("Authorization", equalTo("Bearer " + TOKEN))
            .withHeader("X-GitHub-Api-Version", equalTo("2022-11-28"))
            .willReturn(GitHubStub.json(200, issue)));
        github.comments("acme/shop", 57, List.of(GitHubStub.comment(1, "carol", "Seen on **prod**",
                "2026-10-02T09:00:00Z"), GitHubStub.comment(2, "dave", "Me too", "2026-10-02T09:30:00Z")));
        GitHubStub.server().stubFor(get(urlPathEqualTo(github.path() + "/search/issues"))
            .withQueryParam("q", equalTo("repo:acme/shop is:open label:bug"))
            .withQueryParam("per_page", equalTo("20"))
            .willReturn(GitHubStub.json(200, Map.of("total_count", 1, "items", List.of(issue)))));

        ToolOutcome got = call(setup, token(TOKEN), "get", "{\"key\":\"acme/shop#57\"}");
        assertThat(text(got)).contains("# acme/shop#57: Checkout fails", "State: open", "Labels: bug",
                "Assignees: alice", "Author: bob", "Link: https://github.com/acme/shop/issues/57", "Steps:\n1. pay",
                "**carol** · 2026-10-02T09:00:00Z\n> Seen on **prod**", "**dave**");
        assertThat(structured(got).get("kind").asString()).isEqualTo("ISSUE");

        ToolOutcome found = call(setup, token(TOKEN), "search", "{\"query\":\"repo:acme/shop is:open label:bug\"}");
        assertThat(text(found)).isEqualTo("1 result\n\nacme/shop#57 Checkout fails (open; labels: bug; assignees: "
                + "alice)");
    }

    @Test
    void writesCommentLabelAndClose() {
        GitHubStub.Issues github = GitHubStub.newIssues();
        Setup setup = setup("GITHUB", github.baseUrl());
        String issue = github.path() + ISSUE;
        GitHubStub.server().stubFor(post(urlEqualTo(issue + "/comments")).willReturn(GitHubStub.json(201, Map.of("id",
                4242, "html_url", "https://github.com/acme/shop/issues/57#issuecomment-4242"))));
        GitHubStub.server().stubFor(post(urlEqualTo(issue + "/labels")).willReturn(GitHubStub.json(200, List.of())));
        GitHubStub.server().stubFor(delete(urlEqualTo(issue + "/labels/needs%20triage"))
            .willReturn(GitHubStub.json(200, List.of())));
        GitHubStub.server().stubFor(delete(urlEqualTo(issue + "/labels/stale"))
            .willReturn(GitHubStub.json(404, Map.of("message", "Label does not exist"))));
        GitHubStub.server().stubFor(patch(urlEqualTo(issue)).willReturn(GitHubStub.json(200, Map.of("number", 57))));

        ToolOutcome commented = call(setup, token(TOKEN), "comment", "{\"key\":\"acme/shop#57\",\"body\":\"Fixed in "
                + "#58.\"}", "Analyst (Tinfirm agent):");
        assertThat(text(commented)).isEqualTo("Commented on acme/shop#57 (comment 4242). Link: "
                + "https://github.com/acme/shop/issues/57#issuecomment-4242");
        GitHubStub.server().verify(postRequestedFor(urlEqualTo(issue + "/comments"))
            .withRequestBody(equalToJson("{\"body\":\"**Analyst (Tinfirm agent):** Fixed in #58.\"}")));

        ToolOutcome labelled = call(setup, token(TOKEN), "label", "{\"key\":\"acme/shop#57\",\"add\":[\" bug \","
                + "\"bug\"],\"remove\":[\"needs triage\",\"stale\"]}");
        assertThat(text(labelled)).isEqualTo("""
                Added `bug` to acme/shop#57.
                Removed `needs triage` from acme/shop#57.
                `stale` was not on acme/shop#57.""");
        assertThat(structured(labelled).toString()).isEqualTo("{\"key\":\"acme/shop#57\",\"added\":[\"bug\"],"
                + "\"removed\":[\"needs triage\"],\"notOn\":[\"stale\"],\"failed\":[]}");
        assertThat(done(labelled).written()).isEqualTo(new ToolOutcome.Written(ToolOutcome.WriteKind.LABELS,
                "acme/shop#57", null, null, List.of("bug"), List.of("needs triage")));
        GitHubStub.server().verify(postRequestedFor(urlEqualTo(issue + "/labels"))
            .withRequestBody(equalToJson("{\"labels\":[\"bug\"]}")));

        ToolOutcome closed = call(setup, token(TOKEN), "close", "{\"key\":\"acme/shop#57\"}");
        assertThat(text(closed)).isEqualTo("Closed acme/shop#57.");
        GitHubStub.server().verify(patchRequestedFor(urlEqualTo(issue))
            .withRequestBody(equalToJson("{\"state\":\"closed\",\"state_reason\":\"completed\"}")));

        // Labels are checked before anything is sent.
        assertThat(call(setup, token(TOKEN), "label", "{\"key\":\"acme/shop#57\",\"add\":[\"x\"],\"remove\":[\"x\"]}"))
            .isEqualTo(new ToolOutcome.Refused(ToolOutcome.Code.INVALID_REQUEST, "`label` names `x` both to add and "
                    + "to remove; name it in one of them."));
        assertThat(call(setup, token(TOKEN), "close", "{\"key\":\"acme/shop57\"}"))
            .isEqualTo(new ToolOutcome.Refused(ToolOutcome.Code.INVALID_REQUEST, "`close` takes the subject's `key`: "
                    + "owner/repo#number, e.g. acme/shop#57."));
        GitHubStub.server().verify(1, postRequestedFor(urlEqualTo(issue + "/labels")));
    }

    @Test
    void theRateLimitIsToldFromMissingPermissions() {
        GitHubStub.Issues github = GitHubStub.newIssues();
        Setup setup = setup("GITHUB", github.baseUrl());
        long reset = Instant.now().plusSeconds(120).getEpochSecond();
        GitHubStub.server().stubFor(get(urlEqualTo(github.path() + "/repos/acme/shop/issues/1"))
            .willReturn(aResponse().withStatus(403)
                .withHeader("x-ratelimit-remaining", "0")
                .withHeader("x-ratelimit-reset", Long.toString(reset))
                .withBody("{\"message\":\"API rate limit exceeded\"}")));
        GitHubStub.server().stubFor(get(urlEqualTo(github.path() + "/repos/acme/shop/issues/2"))
            .willReturn(GitHubStub.json(403, Map.of("message", "Resource not accessible by personal access token"))));
        GitHubStub.server().stubFor(get(urlEqualTo(github.path() + "/repos/acme/shop/issues/3"))
            .willReturn(GitHubStub.json(401, Map.of("message", "Bad credentials"))));
        GitHubStub.server().stubFor(get(urlEqualTo(github.path() + "/repos/acme/shop/issues/4"))
            .willReturn(GitHubStub.json(404, Map.of("message", "Not Found"))));

        ToolOutcome limited = call(setup, token(TOKEN), "get", "{\"key\":\"acme/shop#1\"}");
        assertThat(done(limited).failure().kind()).isEqualTo(CallResult.Kind.RATE_LIMITED);
        assertThat(done(limited).failure().retryAfter()).isPositive();
        assertThat(text(limited)).contains("GitHub answered 403, the rate limit is exhausted; try again in",
                "API rate limit exceeded");

        ToolOutcome forbidden = call(setup, token(TOKEN), "get", "{\"key\":\"acme/shop#2\"}");
        assertThat(done(forbidden).failure().kind()).isEqualTo(CallResult.Kind.FORBIDDEN);
        assertThat(text(forbidden)).contains("Resource not accessible by personal access token", "may not do this");

        assertThat(call(setup, token(TOKEN), "get", "{\"key\":\"acme/shop#3\"}"))
            .isEqualTo(new ToolOutcome.Reconnect("GitHub answered 401: Bad credentials"));
        assertThat(done(call(setup, token(TOKEN), "get", "{\"key\":\"acme/shop#4\"}")).failure().kind())
            .isEqualTo(CallResult.Kind.NOT_FOUND);

        // The agent never calls open_change_request; the server opens change requests.
        assertThat(call(setup, token(TOKEN), "open_change_request", "{}")).isInstanceOf(ToolOutcome.Refused.class)
            .extracting(outcome -> ((ToolOutcome.Refused) outcome).code()).isEqualTo(ToolOutcome.Code.UNKNOWN_TOOL);
    }

    @Test
    void aPullRequestOpensIntoTheDefaultBranchOrIsTheOneOfTheBranch() {
        GitHubStub.Issues github = GitHubStub.newIssues();
        Setup setup = setup("GITHUB", github.baseUrl());
        UUID shop = addRepository(setup, "https://localhost/acme/shop.git", null);
        UUID web = addRepository(setup, "https://localhost/acme/web", "develop");
        String pulls = github.path() + "/repos/acme/shop/pulls";
        GitHubStub.server().stubFor(get(urlEqualTo(github.path() + "/repos/acme/shop"))
            .willReturn(GitHubStub.json(200, Map.of("default_branch", "main"))));
        GitHubStub.server().stubFor(post(urlEqualTo(pulls)).willReturn(GitHubStub.json(201, Map.of("number", 12,
                "html_url", "https://github.com/acme/shop/pull/12", "state", "open", "draft", false))));
        GitHubStub.server().stubFor(get(urlEqualTo(pulls + "/12")).willReturn(GitHubStub.json(200, Map.of("number", 12,
                "state", "closed", "merged", true, "head", Map.of("ref", "agent/shop-1-dev-1", "sha",
                        "21a02534de2d9805c3f2907966f5021453676003")))));
        String webPulls = github.path() + "/repos/acme/web/pulls";
        GitHubStub.server().stubFor(post(urlEqualTo(webPulls)).willReturn(GitHubStub.json(422, Map.of("message",
                "Validation Failed", "errors", List.of(Map.of("message",
                        "A pull request already exists for acme:agent/shop-1-dev-1."))))));
        GitHubStub.server().stubFor(get(urlEqualTo(webPulls + "?state=open&head=acme%3Aagent%2Fshop-1-dev-1"))
            .willReturn(GitHubStub.json(200, List.of(Map.of("number", 3, "html_url",
                    "https://github.com/acme/web/pull/3", "state", "open", "draft", true)))));
        ChangeRequests.New request = new ChangeRequests.New("SHOP-1: Fix totals", "agent/shop-1-dev-1", null,
                "Summary\n\nOpened by Tinfirm agent Dev");

        CallResult<ChangeRequests.Opened> opened = SystemContext.callInCompany(setup.company().id(),
                () -> tools.openChangeRequest(setup.company().id(), shop, token(TOKEN), request));
        assertThat(opened.value()).isEqualTo(new ChangeRequests.Opened("https://github.com/acme/shop/pull/12", 12,
                true, ChangeRequests.State.OPEN));
        GitHubStub.server().verify(postRequestedFor(urlEqualTo(pulls))
            .withHeader("Authorization", equalTo("Bearer " + TOKEN))
            .withRequestBody(equalToJson("{\"title\":\"SHOP-1: Fix totals\",\"head\":\"agent/shop-1-dev-1\","
                    + "\"base\":\"main\",\"body\":\"Summary\\n\\nOpened by Tinfirm agent Dev\"}")));

        CallResult<ChangeRequests.Opened> existing = SystemContext.callInCompany(setup.company().id(),
                () -> tools.openChangeRequest(setup.company().id(), web, token(TOKEN), request));
        assertThat(existing.value()).isEqualTo(new ChangeRequests.Opened("https://github.com/acme/web/pull/3", 3,
                false, ChangeRequests.State.DRAFT));
        // The repository names its default branch: no need to ask GitHub.
        GitHubStub.server().verify(postRequestedFor(urlEqualTo(webPulls))
            .withRequestBody(matchingJsonPath("$.base", equalTo("develop"))));
        GitHubStub.server().verify(0, getRequestedFor(urlEqualTo(github.path() + "/repos/acme/web")));

        // Its state, and the commit it was merged at.
        assertThat(SystemContext.callInCompany(setup.company().id(), () -> tools.changeRequestState(
                setup.company().id(), shop, token(TOKEN), 12)).value()).isEqualTo(new ChangeRequests.Read(
                        ChangeRequests.State.MERGED, "21a02534de2d9805c3f2907966f5021453676003"));
    }

    @Test
    void aGitRepositoryGetsNoChangeRequestAndARefusedOneSaysWhy() {
        Setup git = setup("GIT", null);
        UUID repository = addRepository(git, "https://git.example.test/acme/tools.git", null);
        CallResult<ChangeRequests.Opened> none = SystemContext.callInCompany(git.company().id(),
                () -> tools.openChangeRequest(git.company().id(), repository, token(TOKEN), new ChangeRequests.New(
                        "Title", "agent/x-1", null, "Body")));
        assertThat(none.failure().kind()).isEqualTo(CallResult.Kind.INVALID);
        assertThat(none.failureMessage()).isEqualTo("A repository of a GIT system gets no change request: only "
                + "GitHub and GitLab have an API for them");

        GitHubStub.Issues github = GitHubStub.newIssues();
        Setup setup = setup("GITHUB", github.baseUrl());
        UUID shop = addRepository(setup, "https://localhost/acme/shop", "main");
        GitHubStub.server().stubFor(post(urlEqualTo(github.path() + "/repos/acme/shop/pulls"))
            .willReturn(GitHubStub.json(403, Map.of("message", "Resource not accessible by integration"))));
        CallResult<ChangeRequests.Opened> refused = SystemContext.callInCompany(setup.company().id(),
                () -> tools.openChangeRequest(setup.company().id(), shop, token(TOKEN), new ChangeRequests.New(
                        "Title", "agent/x-1", null, "Body")));
        assertThat(refused.failure().kind()).isEqualTo(CallResult.Kind.FORBIDDEN);
        assertThat(refused.failureMessage()).isEqualTo("GitHub answered 403: Resource not accessible by integration");
        JsonNode body = JSON.readTree(GitHubStub.server()
            .findAll(postRequestedFor(urlEqualTo(github.path() + "/repos/acme/shop/pulls")))
            .getFirst()
            .getBodyAsString());
        assertThat(body.get("base").asString()).isEqualTo("main");
    }
}
