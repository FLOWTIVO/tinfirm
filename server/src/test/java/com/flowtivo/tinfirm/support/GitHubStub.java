package com.flowtivo.tinfirm.support;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.patch;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;

import tools.jackson.databind.json.JsonMapper;

/**
 * One WireMock server for the whole test run, standing in for the GitHub REST API: the code host
 * ({@code TINFIRM_GITHUB_API_URL}, the server's root) and, under a base path of their own, the issues of a GitHub
 * tracker connection ({@link #newIssues()}). Tests stub and verify per repository or base path, so they never
 * interfere. TEST FIXTURE ONLY: the tokens are test values.
 */
public final class GitHubStub {

    private static final WireMockServer SERVER = new WireMockServer(StubServers.options().dynamicPort());

    private static final JsonMapper JSON = JsonMapper.builder().build();

    static {
        SERVER.start();
    }

    private GitHubStub() {
    }

    public static WireMockServer server() {
        return SERVER;
    }

    public static String url() {
        return "http://localhost:" + SERVER.port();
    }

    /**
     * An API base of its own for a tracker connection, e.g. {@code http://localhost:1234/gh-<random>} like GitHub
     * Enterprise Server's {@code https://<host>/api/v3}, and the path prefix of its stubs.
     */
    public static Issues newIssues() {
        String path = "/gh-" + UUID.randomUUID().toString().substring(0, 8);
        return new Issues(url() + path, path);
    }

    /** The issues API of one test. */
    public record Issues(String baseUrl, String path) {

        /** {@code GET /user} answers the login and an id made of it, for the given token only. */
        public void user(String token, String login) {
            SERVER.stubFor(get(urlEqualTo(path + "/user"))
                .withHeader("Authorization", equalTo("Bearer " + token))
                .willReturn(json(200, Map.of("id", Math.abs((long) login.hashCode()) + 1, "login", login, "name",
                        "Name of " + login))));
        }

        /** Every listing of the repository's issues answers these, as one page. */
        public void issues(String repository, List<Map<String, Object>> issues) {
            SERVER.stubFor(get(urlPathEqualTo(path + "/repos/" + repository + "/issues")).willReturn(json(200, issues)));
        }

        /** Every listing of the issue's comments answers these, as one page. */
        public void comments(String repository, int number, List<Map<String, Object>> comments) {
            SERVER.stubFor(get(urlPathEqualTo(path + "/repos/" + repository + "/issues/" + number + "/comments"))
                .willReturn(json(200, comments)));
        }

        /**
         * The writes of the issue: a comment gets {@code commentId}, labels are added, a label is removed (or is not
         * on the issue: 404 when {@code labelsMissing}), and the issue is closed.
         */
        public void writes(String repository, int number, long commentId, boolean labelsMissing) {
            String issue = path + "/repos/" + repository + "/issues/" + number;
            SERVER.stubFor(post(urlEqualTo(issue + "/comments")).willReturn(json(201, Map.of("id", commentId))));
            SERVER.stubFor(post(urlEqualTo(issue + "/labels")).willReturn(json(200, List.of())));
            SERVER.stubFor(delete(urlPathMatching(issue + "/labels/.+")).willReturn(labelsMissing
                    ? json(404, Map.of("message", "Label does not exist"))
                    : json(200, List.of())));
            SERVER.stubFor(patch(urlEqualTo(issue)).willReturn(json(200, Map.of("number", number, "state", "closed"))));
        }

        public String url(String suffix) {
            return path + suffix;
        }
    }

    /**
     * An open issue as GitHub lists it, created when it was last updated; the repository's names are the canonical
     * ones the key uses.
     */
    public static Map<String, Object> issue(long id, int number, String repository, String title, String body,
            List<String> assignees, List<String> labels, String updatedAt, int comments) {
        return issue(id, number, repository, title, body, assignees, labels, updatedAt, updatedAt, comments);
    }

    /** An open issue as GitHub lists it, created at {@code createdAt}; put {@code state} or {@code type} to change. */
    public static Map<String, Object> issue(long id, int number, String repository, String title, String body,
            List<String> assignees, List<String> labels, String createdAt, String updatedAt, int comments) {
        Map<String, Object> issue = new LinkedHashMap<>();
        issue.put("id", id);
        issue.put("number", number);
        issue.put("title", title);
        issue.put("body", body);
        issue.put("state", "open");
        issue.put("html_url", "https://github.com/" + repository + "/issues/" + number);
        issue.put("repository_url", "https://api.github.com/repos/" + repository);
        issue.put("labels", labels.stream().map(label -> Map.of("name", label)).toList());
        issue.put("assignees", assignees.stream().map(login -> Map.of("login", login)).toList());
        issue.put("created_at", createdAt);
        issue.put("updated_at", updatedAt);
        issue.put("comments", comments);
        return issue;
    }

    /** A pull request as GitHub lists it among the issues. */
    public static Map<String, Object> pullRequest(long id, int number, String repository, String updatedAt) {
        Map<String, Object> pull = new LinkedHashMap<>(issue(id, number, repository, "A pull request", "", List.of(),
                List.of(), updatedAt, 0));
        pull.put("html_url", "https://github.com/" + repository + "/pull/" + number);
        pull.put("pull_request", Map.of("url", "https://api.github.com/repos/" + repository + "/pulls/" + number));
        return pull;
    }

    public static Map<String, Object> comment(long id, String login, String body, String createdAt) {
        return Map.of("id", id, "user", Map.of("login", login), "body", body, "created_at", createdAt,
                "updated_at", createdAt);
    }

    public static ResponseDefinitionBuilder json(int status, Object body) {
        return aResponse().withStatus(status)
            .withHeader("Content-Type", "application/json")
            .withBody(JSON.writeValueAsString(body));
    }
}
