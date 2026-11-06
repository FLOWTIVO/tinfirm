package com.flowtivo.tinfirm.shared.jira;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

import tools.jackson.databind.JsonNode;

/**
 * Jira's JQL search, as far as its URLs and answers go; the caller sends the request with its own client and
 * credential. Data Center's {@code GET …/search} pages by {@code startAt} and says the {@code total}; Cloud's
 * enhanced search {@code GET …/search/jql} pages by an opaque {@code nextPageToken} and says {@code isLast}.
 */
public final class JiraSearch {

    private JiraSearch() {
    }

    /**
     * Data Center's search.
     *
     * @param api    the REST API's base, e.g. {@code https://jira.example.com/rest/api/2}
     * @param fields the fields, comma-separated
     */
    public static String dataCenterUrl(String api, String jql, String fields, int startAt, int maxResults) {
        return api + "/search?jql=" + JiraJson.encode(jql) + "&startAt=" + startAt + "&maxResults=" + maxResults
                + "&fields=" + JiraJson.encode(fields);
    }

    /**
     * Cloud's enhanced search.
     *
     * @param api           the REST API's base, e.g. {@code https://acme.atlassian.net/rest/api/3}
     * @param fields        the fields, comma-separated
     * @param nextPageToken the previous page's token, or null for the first page
     */
    public static String cloudUrl(String api, String jql, String fields, int maxResults,
            @Nullable String nextPageToken) {
        return api + "/search/jql?jql=" + JiraJson.encode(jql) + "&maxResults=" + maxResults + "&fields="
                + JiraJson.encode(fields) + (nextPageToken != null ? "&nextPageToken=" + JiraJson.encode(nextPageToken)
                        : "");
    }

    /** A page of Cloud's search: its usable issues, how many entries it held, and the next page's token. */
    public record CloudPage(List<JiraIssue> issues, int count, @Nullable String nextPageToken) {

        public CloudPage {
            issues = List.copyOf(issues);
        }

        /** Whether this is the last page. */
        public boolean last() {
            return nextPageToken == null;
        }
    }

    /** Reads a page of Cloud's search. The token is null on the last page ({@code isLast}, or none given). */
    public static CloudPage cloudPage(JsonNode body) {
        String token = JiraJson.text(body, "nextPageToken");
        boolean last = JiraJson.flag(body, "isLast") || token == null || token.isEmpty();
        JsonNode items = body.get("issues");
        return new CloudPage(JiraIssues.issues(body), items != null && items.isArray() ? items.size() : 0,
                last ? null : token);
    }

    /**
     * Jira's messages in an error answer: {@code errorMessages}, then each field's {@code errors}, else its
     * {@code message}.
     */
    public static List<String> errors(@Nullable JsonNode body) {
        List<String> messages = new ArrayList<>();
        if (body == null) {
            return messages;
        }
        JsonNode errorMessages = body.get("errorMessages");
        if (errorMessages != null && errorMessages.isArray()) {
            errorMessages.forEach(message -> {
                if (message.isString()) {
                    messages.add(message.stringValue());
                }
            });
        }
        JsonNode errors = body.get("errors");
        if (errors != null && errors.isObject()) {
            errors.properties().forEach(entry -> {
                if (entry.getValue().isString()) {
                    messages.add(entry.getKey() + ": " + entry.getValue().stringValue());
                }
            });
        }
        String message = JiraJson.text(body, "message");
        if (message != null && !message.isBlank() && messages.isEmpty()) {
            messages.add(message);
        }
        return messages;
    }
}
