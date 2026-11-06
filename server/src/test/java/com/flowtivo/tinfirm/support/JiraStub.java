package com.flowtivo.tinfirm.support;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.github.tomakehurst.wiremock.WireMockServer;

import tools.jackson.databind.json.JsonMapper;

/**
 * One WireMock server for the whole test run, standing in for Jira Data Center's REST API v2. Every test gets its
 * own base path ({@link #newInstance()}), so its stubs never meet another test's. TEST FIXTURE ONLY: the tokens are
 * test values.
 */
public final class JiraStub {

    private static final WireMockServer SERVER = new WireMockServer(StubServers.options().dynamicPort());

    private static final JsonMapper JSON = JsonMapper.builder().build();

    static {
        SERVER.start();
    }

    private JiraStub() {
    }

    public static WireMockServer server() {
        return SERVER;
    }

    /** A base URL of its own, e.g. {@code http://localhost:1234/jira-<random>}, and the path prefix of its stubs. */
    public static Instance newInstance() {
        String path = "/jira-" + UUID.randomUUID().toString().substring(0, 8);
        return new Instance("http://localhost:" + SERVER.port() + path, path);
    }

    /** One Jira instance of a test. */
    public record Instance(String baseUrl, String path) {

        /**
         * {@code GET /rest/api/2/myself} answers the account, for the given token only: its user key
         * ({@code key-<name>}) and its name.
         */
        public void myself(String token, String name, String timeZone) {
            SERVER.stubFor(get(urlEqualTo(path + "/rest/api/2/myself"))
                .withHeader("Authorization", equalTo("Bearer " + token))
                .willReturn(json(200, Map.of("key", "key-" + name, "name", name, "displayName", "Display " + name,
                        "timeZone", timeZone))));
        }

        /** Every search answers these issues, as one page. */
        public void search(List<Map<String, Object>> issues) {
            Map<String, Object> page = new LinkedHashMap<>();
            page.put("startAt", 0);
            page.put("maxResults", 50);
            page.put("total", issues.size());
            page.put("issues", issues);
            SERVER.stubFor(get(urlPathEqualTo(path + "/rest/api/2/search")).willReturn(json(200, page)));
        }

        /**
         * Every search with this token (a poll's listing: {@code GET …/search}) answers these issues, as one page;
         * another token's search is not stubbed by this.
         */
        public void searchFor(String token, List<Map<String, Object>> issues) {
            Map<String, Object> page = new LinkedHashMap<>();
            page.put("startAt", 0);
            page.put("maxResults", 50);
            page.put("total", issues.size());
            page.put("issues", issues);
            searchAnswers(token, json(200, page));
        }

        /** Every search with this token answers this (a {@code 401}, a {@code 429} with {@code Retry-After}, …). */
        public void searchAnswers(String token,
                com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder response) {
            SERVER.stubFor(get(urlPathEqualTo(path + "/rest/api/2/search"))
                .withHeader("Authorization", equalTo("Bearer " + token))
                .willReturn(response));
        }

        /** {@code GET …/issue/{key}} with this token answers the issue (as a search lists it). */
        public void issueFor(String token, Map<String, Object> issue) {
            SERVER.stubFor(get(urlPathEqualTo(path + "/rest/api/2/issue/" + issue.get("key")))
                .withHeader("Authorization", equalTo("Bearer " + token))
                .willReturn(json(200, issue)));
        }

        public String url(String suffix) {
            return path + suffix;
        }
    }

    /**
     * An issue as the search returns it, created at {@code created}, of the type, by the reporter: the fields
     * {@link #issue} leaves out.
     */
    public static Map<String, Object> issue(String id, String key, String summary, String description,
            String assignee, List<String> labels, String status, String created, String updated,
            List<Map<String, Object>> comments) {
        Map<String, Object> issue = issue(id, key, summary, description, assignee, labels, status, updated, comments);
        @SuppressWarnings("unchecked")
        Map<String, Object> fields = (Map<String, Object>) issue.get("fields");
        fields.put("created", created);
        fields.put("issuetype", Map.of("name", "Story"));
        fields.put("reporter", user("reporter"));
        return issue;
    }

    /** An issue as the search returns it, with Jira's timestamp format. */
    public static Map<String, Object> issue(String id, String key, String summary, String description,
            String assignee, List<String> labels, String status, String updated, List<Map<String, Object>> comments) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("summary", summary);
        fields.put("description", description);
        fields.put("status", Map.of("name", status));
        fields.put("assignee", assignee != null ? user(assignee) : null);
        fields.put("labels", labels);
        fields.put("updated", updated);
        fields.put("comment", Map.of("startAt", 0, "maxResults", comments.size(), "total", comments.size(),
                "comments", new ArrayList<>(comments)));
        Map<String, Object> issue = new LinkedHashMap<>();
        issue.put("id", id);
        issue.put("key", key);
        issue.put("fields", fields);
        return issue;
    }

    public static Map<String, Object> comment(String id, String author, String body, String created) {
        return Map.of("id", id, "author", user(author), "body", body, "created", created);
    }

    private static Map<String, Object> user(String name) {
        return Map.of("name", name, "displayName", "Display " + name);
    }

    public static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder json(int status, Object body) {
        return aResponse().withStatus(status)
            .withHeader("Content-Type", "application/json")
            .withBody(JSON.writeValueAsString(body));
    }
}
