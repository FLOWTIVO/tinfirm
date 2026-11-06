package com.flowtivo.tinfirm.support;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * A Jira Cloud site on the shared WireMock server of {@link JiraStub}, standing in for REST API v3 (ADR-0048):
 * Basic authentication with {@code email:token}, account ids, Atlassian Document Format texts, the cursor-paged
 * {@code search/jql}. Every test gets its own base path ({@link #newInstance()}). TEST FIXTURE ONLY: the credentials
 * are test values.
 */
public final class JiraCloudStub {

    private JiraCloudStub() {
    }

    /** A site of its own, e.g. {@code http://localhost:1234/cloud-<random>}, and the path prefix of its stubs. */
    public static Instance newInstance() {
        String path = "/cloud-" + UUID.randomUUID().toString().substring(0, 8);
        return new Instance("http://localhost:" + JiraStub.server().port() + path, path);
    }

    /** The {@code Authorization} header Jira Cloud expects for a credential {@code email:token}. */
    public static String basic(String credential) {
        return "Basic " + Base64.getEncoder().encodeToString(credential.getBytes(StandardCharsets.UTF_8));
    }

    /** One Jira Cloud site of a test. */
    public record Instance(String baseUrl, String path) {

        /** {@code GET /rest/api/3/myself} answers the account, for the given credential only. */
        public void myself(String credential, String accountId, String displayName, String timeZone) {
            JiraStub.server().stubFor(get(urlEqualTo(path + "/rest/api/3/myself"))
                .withHeader("Authorization", equalTo(basic(credential)))
                .willReturn(JiraStub.json(200, Map.of("accountId", accountId, "displayName", displayName, "timeZone",
                        timeZone, "active", true))));
        }

        /** Every search answers these issues, as one last page. */
        public void search(List<Map<String, Object>> issues) {
            Map<String, Object> page = new LinkedHashMap<>();
            page.put("issues", issues);
            page.put("isLast", true);
            JiraStub.server().stubFor(get(urlPathEqualTo(path + "/rest/api/3/search/jql"))
                .willReturn(JiraStub.json(200, page)));
        }

        public String url(String suffix) {
            return path + suffix;
        }
    }

    /** An ADF document of one paragraph of plain text. */
    public static Map<String, Object> adf(String text) {
        return doc(List.of(paragraph(List.of(textNode(text)))));
    }

    public static Map<String, Object> doc(List<Map<String, Object>> blocks) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("version", 1);
        doc.put("type", "doc");
        doc.put("content", new ArrayList<>(blocks));
        return doc;
    }

    public static Map<String, Object> paragraph(List<Map<String, Object>> inline) {
        return Map.of("type", "paragraph", "content", new ArrayList<>(inline));
    }

    public static Map<String, Object> textNode(String text) {
        return Map.of("type", "text", "text", text);
    }

    public static Map<String, Object> strong(String text) {
        return Map.of("type", "text", "text", text, "marks", List.of(Map.of("type", "strong")));
    }

    /** A mention of the account, as Jira Cloud's editor inserts it. */
    public static Map<String, Object> mention(String accountId, String shownName) {
        return Map.of("type", "mention", "attrs", Map.of("id", accountId, "text", "@" + shownName));
    }

    /** A user by account id, as {@code assignee}, {@code reporter} and a comment's {@code author} come. */
    public static Map<String, Object> user(String accountId) {
        return Map.of("accountId", accountId, "displayName", "Display " + accountId);
    }

    /** A comment as the issue's {@code comment} field and the comments endpoint list it, with an ADF body. */
    public static Map<String, Object> comment(String id, String authorAccountId, Map<String, Object> body,
            String created) {
        return Map.of("id", id, "author", user(authorAccountId), "body", body, "created", created);
    }

    /**
     * An issue as the search returns it, with Jira's timestamp format, ADF texts and account ids.
     *
     * @param description the ADF description, or null for none
     */
    public static Map<String, Object> issue(String id, String key, String summary,
            @Nullable Map<String, Object> description, @Nullable String assigneeAccountId, String reporterAccountId,
            List<String> labels, String status, String type, String created, String updated,
            @Nullable String resolved, List<Map<String, Object>> comments) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("summary", summary);
        fields.put("description", description);
        fields.put("status", Map.of("name", status));
        fields.put("issuetype", Map.of("name", type));
        fields.put("assignee", assigneeAccountId != null ? user(assigneeAccountId) : null);
        fields.put("reporter", user(reporterAccountId));
        fields.put("labels", labels);
        fields.put("created", created);
        fields.put("updated", updated);
        fields.put("resolutiondate", resolved);
        fields.put("comment", Map.of("startAt", 0, "maxResults", comments.size(), "total", comments.size(),
                "comments", new ArrayList<>(comments)));
        Map<String, Object> issue = new LinkedHashMap<>();
        issue.put("id", id);
        issue.put("key", key);
        issue.put("fields", fields);
        return issue;
    }
}
