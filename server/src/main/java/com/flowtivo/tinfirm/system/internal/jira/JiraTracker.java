package com.flowtivo.tinfirm.system.internal.jira;

import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

import com.flowtivo.tinfirm.shared.jira.Adf;
import com.flowtivo.tinfirm.shared.jira.JiraIssue;
import com.flowtivo.tinfirm.shared.jira.JiraIssues;
import com.flowtivo.tinfirm.shared.jira.JiraMarkup;
import com.flowtivo.tinfirm.shared.jira.JiraSearch;
import com.flowtivo.tinfirm.shared.jira.JiraTime;
import com.flowtivo.tinfirm.system.CallResult;
import com.flowtivo.tinfirm.system.SystemWrites;
import com.flowtivo.tinfirm.system.internal.http.Json;
import com.flowtivo.tinfirm.system.internal.http.OutboundHttp;
import com.flowtivo.tinfirm.system.internal.tools.Failures;
import com.flowtivo.tinfirm.system.internal.tools.Subject;
import com.flowtivo.tinfirm.system.internal.tools.Tracker;

import tools.jackson.databind.JsonNode;

/**
 * A Jira site for the tools (the package 3 contract, §5.4), a client for one call:
 * <ul>
 * <li><b>Data Center</b>: REST API v2 with a personal access token or an OAuth access token, both
 * {@code Authorization: Bearer}; texts in wiki markup ({@link JiraMarkup}); accounts by user name.</li>
 * <li><b>Cloud</b> (ADR-0048): REST API v3 with an Atlassian account's {@code email:api-token} as Basic
 * authentication at the site, or an OAuth (3LO) access token as a bearer token at Atlassian's gateway for the site
 * ({@code https://api.atlassian.com/ex/jira/{cloudId}}); texts in the Atlassian Document Format ({@link Adf}), kept as
 * JSON text; accounts by account id; the search is {@code search/jql}.</li>
 * </ul>
 * Every request sends {@code X-Atlassian-Token: no-check}, which Jira's XSRF check wants of some POSTs. An issue's page
 * is {@code {site}/browse/{key}}. A redirect is usually the login page: a wrong base URL or credential.
 */
public final class JiraTracker implements Tracker {

    /** The fields a {@code get} asks for. */
    static final String FIELDS = "summary,description,status,issuetype,assignee,reporter,labels,created,updated,"
            + "comment,resolution,resolutiondate";

    /** The fields a {@code search} asks for: no description, no comments. */
    static final String SEARCH_FIELDS = "summary,status,issuetype,assignee,reporter,labels,created,updated,"
            + "resolutiondate";


    /** A wiki line that starts a block: a heading, a list, a table, a quote, a macro or an image. */
    private static final Pattern BLOCK_START = Pattern.compile("^(h[1-6]\\.\\s|[*#-]+\\s|\\||bq\\.\\s|\\{|!).*",
            Pattern.DOTALL);

    private final OutboundHttp http;

    private final boolean cloud;

    /** Where the API is: the site, or Atlassian's gateway for a Cloud OAuth grant. */
    private final String apiBase;

    /** The site, where an issue's page is. */
    private final String siteUrl;

    private final String authorization;

    private JiraTracker(OutboundHttp http, boolean cloud, String apiBase, String siteUrl, String authorization) {
        this.http = http;
        this.cloud = cloud;
        this.apiBase = apiBase;
        this.siteUrl = siteUrl;
        this.authorization = authorization;
    }

    /** Jira Data Center at its base URL: a personal access token or an OAuth access token, as a bearer token. */
    public static JiraTracker dataCenter(OutboundHttp http, String baseUrl, String token) {
        return new JiraTracker(http, false, baseUrl, baseUrl, "Bearer " + token.strip());
    }

    /**
     * A Jira Cloud site: an OAuth access token as a bearer token at the gateway, or {@code email:api-token} as Basic
     * authentication at the site.
     *
     * @param apiBase the gateway for an OAuth token, else the site
     */
    public static JiraTracker cloud(OutboundHttp http, String siteUrl, String apiBase, String token, boolean oauth) {
        String authorization = oauth ? "Bearer " + token.strip()
                : "Basic " + Base64.getEncoder().encodeToString(token.strip().getBytes(StandardCharsets.UTF_8));
        return new JiraTracker(http, true, apiBase, siteUrl, authorization);
    }

    /**
     * Why a stored Jira Cloud credential cannot be {@code email:api-token}, or null when it can. Never names its
     * content.
     */
    public static @Nullable String cloudCredentialProblem(String credential) {
        String trimmed = credential.strip();
        int colon = trimmed.indexOf(':');
        if (colon <= 0 || colon == trimmed.length() - 1
                || trimmed.chars().anyMatch(c -> Character.isWhitespace(c) || c < 0x20 || c > 0x7e)) {
            return "The account's credential is not an Atlassian account's email and API token as email:token";
        }
        return null;
    }

    @Override
    public String name() {
        return cloud ? "Jira Cloud" : "Jira";
    }

    private String api() {
        return apiBase + (cloud ? "/rest/api/3" : "/rest/api/2");
    }

    private String issueUrl(String key) {
        return api() + "/issue/" + Json.encode(key);
    }

    // ---- Tools -----------------------------------------------------------------------------------------------------

    /** {@code GET …/issue/{key}}, and its newest comments when the issue's own list was cut. */
    @Override
    public CallResult<Subject> get(String key, int comments) {
        CallResult<Subject> read = call(get(issueUrl(key) + "?fields=" + Json.encode(FIELDS)), body -> {
            Subject issue = issue(body, cloud);
            return issue != null ? CallResult.ok(withUrl(issue))
                    : CallResult.failed(CallResult.Kind.INVALID, name() + " returned no usable issue");
        });
        Subject issue = read.value();
        if (issue == null || !issue.commentsCut()) {
            return read;
        }
        String uri = cloud ? issueUrl(issue.key()) + "/comment?orderBy=-created&maxResults=" + comments
                : issueUrl(issue.key()) + "/comment?startAt=" + Math.max(0, issue.commentTotal() - comments)
                        + "&maxResults=" + comments;
        CallResult<List<Subject.Comment>> newest = call(get(uri),
                body -> CallResult.ok(comments(body.get("comments"), cloud)));
        return CallResult.ok(newest.value() != null ? issue.withComments(newest.value()) : issue);
    }

    /** A JQL search: Data Center's {@code search}, Cloud's {@code search/jql}; the first {@code limit} issues. */
    @Override
    public CallResult<List<Subject>> search(String query, int limit) {
        String uri = cloud
                ? JiraSearch.cloudUrl(api(), query, SEARCH_FIELDS, limit, null)
                : JiraSearch.dataCenterUrl(api(), query, SEARCH_FIELDS, 0, limit);
        return call(get(uri), body -> {
            List<Subject> issues = new ArrayList<>();
            JsonNode items = body.get("issues");
            if (items != null && items.isArray()) {
                for (JsonNode item : items) {
                    Subject issue = issue(item, cloud);
                    if (issue != null && issues.size() < limit) {
                        issues.add(withUrl(issue));
                    }
                }
            }
            return CallResult.ok(issues);
        });
    }

    /**
     * {@code POST …/issue/{key}/comment}: Markdown as wiki markup (Data Center) or as an ADF document (Cloud), the
     * label in front in bold.
     */
    @Override
    public CallResult<Subject.Posted> comment(String key, String markdown, @Nullable String label) {
        Object body;
        if (cloud) {
            String document = Adf.fromMarkdown(markdown);
            body = Adf.documentOf(label != null ? Adf.withLabel(label, document) : document);
        }
        else {
            String wiki = JiraMarkup.toWiki(markdown);
            body = label != null ? labelled("*" + plain(label) + "*", wiki) : wiki;
        }
        return call(post(issueUrl(key) + "/comment", Map.of("body", body)), response -> {
            Long id = Json.longValue(response, "id");
            return id != null
                    ? CallResult.ok(new Subject.Posted(id, page(key, "?focusedCommentId=" + id)))
                    : CallResult.failed(CallResult.Kind.INVALID, name() + " returned no comment id");
        });
    }

    /** {@code GET …/issue/{key}/transitions}: what the issue's current status offers. */
    @Override
    public CallResult<List<Subject.Transition>> transitions(String key) {
        return call(get(issueUrl(key) + "/transitions"), body -> {
            List<Subject.Transition> transitions = new ArrayList<>();
            JsonNode items = body.get("transitions");
            if (items != null && items.isArray()) {
                for (JsonNode item : items) {
                    String id = Json.text(item, "id");
                    String name = Json.text(item, "name");
                    if (id != null && name != null) {
                        transitions.add(new Subject.Transition(id, name, Json.text(item.get("to"), "name")));
                    }
                }
            }
            return CallResult.ok(transitions);
        });
    }

    /** {@code POST …/issue/{key}/transitions}. */
    @Override
    public CallResult<Boolean> transition(String key, String transitionId) {
        return call(post(issueUrl(key) + "/transitions", Map.of("transition", Map.of("id", transitionId))),
                body -> CallResult.ok(true));
    }

    /**
     * {@code POST …/issue/{key}/attachments}: one file as {@code multipart/form-data}; Jira answers the attachments it
     * added, the first of which is this one.
     */
    @Override
    public CallResult<String> attach(String key, String fileName, String contentType, byte[] content) {
        String boundary = "tinfirm-" + UUID.randomUUID();
        String name = fileName.replace("\"", "").replace("\r", "").replace("\n", "");
        String type = contentType.replace("\r", "").replace("\n", "");
        byte[] head = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + name
                + "\"\r\nContent-Type: " + type + "\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        HttpRequest request = request(issueUrl(key) + "/attachments")
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .POST(HttpRequest.BodyPublishers.ofByteArrays(List.of(head, content, tail)))
            .build();
        return call(request, body -> {
            JsonNode first = body.isArray() && !body.isEmpty() ? body.get(0) : body;
            String id = Json.textOrNumber(first, "id");
            return id != null ? CallResult.ok(id)
                    : CallResult.failed(CallResult.Kind.INVALID, name() + " returned no attachment id");
        });
    }

    /** {@code GET …/issue/{key}?fields=attachment}: the issue's attachments by name and size. */
    @Override
    public CallResult<List<SystemWrites.Attachment>> attachments(String key) {
        return call(get(issueUrl(key) + "?fields=attachment"), body -> {
            List<SystemWrites.Attachment> attachments = new ArrayList<>();
            JsonNode fields = body.get("fields");
            JsonNode items = fields != null ? fields.get("attachment") : null;
            if (items != null && items.isArray()) {
                for (JsonNode item : items) {
                    String id = Json.textOrNumber(item, "id");
                    String name = Json.text(item, "filename");
                    Long size = Json.longValue(item, "size");
                    if (id != null && name != null) {
                        attachments.add(new SystemWrites.Attachment(id, name, size != null ? size : -1));
                    }
                }
            }
            return CallResult.ok(attachments);
        });
    }

    /**
     * {@code POST …/issue/{key}/remotelink} with the global id {@code tinfirm:{url}}, so that a second call updates the
     * same link.
     */
    @Override
    public CallResult<String> remoteLink(String key, String url, String title) {
        return call(post(issueUrl(key) + "/remotelink", Map.of("globalId", "tinfirm:" + url, "object",
                Map.of("url", url, "title", title))), body -> {
                    String id = Json.textOrNumber(body, "id");
                    return CallResult.ok(id != null ? id : "");
                });
    }

    @Override
    public @Nullable String toMarkdown(@Nullable String text) {
        return cloud ? Adf.toMarkdown(text) : JiraMarkup.toMarkdown(text);
    }

    // ---- HTTP ------------------------------------------------------------------------------------------------------

    /** Sends the request; any 2xx is success (Jira is not uniform about 200, 201 and 204). */
    private <T> CallResult<T> call(HttpRequest request, Function<JsonNode, CallResult<T>> handler) {
        CallResult<OutboundHttp.Response> sent = http.send(apiBase, request);
        OutboundHttp.Response response = sent.value();
        if (response == null) {
            return sent.failedAs();
        }
        JsonNode parsed = Json.parse(response.body());
        if (!response.success()) {
            return CallResult.failed(Failures.of(name(), response, messages(response, parsed), false, null));
        }
        if (parsed == null) {
            return CallResult.failed(CallResult.Kind.INVALID, name() + " answered " + response.status()
                    + " without JSON");
        }
        return handler.apply(parsed);
    }

    private HttpRequest get(String uri) {
        return request(uri).GET().build();
    }

    private HttpRequest post(String uri, Object body) {
        return request(uri).POST(HttpRequest.BodyPublishers.ofString(Json.write(body)))
            .header("Content-Type", "application/json")
            .build();
    }

    private HttpRequest.Builder request(String uri) {
        return http.request(uri)
            .header("Accept", "application/json")
            .header("Authorization", authorization)
            .header("X-Atlassian-Token", "no-check");
    }

    private Subject withUrl(Subject issue) {
        return issue.withUrl(page(issue.key(), ""));
    }

    /** The issue's page on the site, when it is a plain http(s) URL of at most 500 characters. */
    private @Nullable String page(String key, String suffix) {
        String url = siteUrl + "/browse/" + key + suffix;
        return url.length() <= 500 && url.matches("^https?://\\S+$") ? url : null;
    }

    // ---- Parsing (package-private for tests) -----------------------------------------------------------------------

    /** Jira's login reason, its error messages and its field errors. */
    static List<String> messages(OutboundHttp.Response response, @Nullable JsonNode body) {
        List<String> messages = new ArrayList<>();
        response.header("X-Seraph-LoginReason").filter(reason -> !reason.isBlank())
            .ifPresent(reason -> messages.add("(" + reason + ")"));
        messages.addAll(JiraSearch.errors(body));
        return messages;
    }

    /**
     * An issue; null without a key or {@code updated}. Without a usable {@code created}, the issue counts as created
     * when it was updated. Its type is the name of {@code issuetype}, its author the {@code reporter}; it is closed
     * when it is resolved ({@code resolutiondate} set), since what a closed status is depends on the workflow. Cloud's
     * texts are ADF documents kept as JSON text, its users account ids.
     */
    static @Nullable Subject issue(JsonNode item, boolean cloud) {
        JiraIssue issue = JiraIssues.parse(item);
        if (issue == null) {
            return null;
        }
        String summary = issue.summary();
        Subject.User assignee = user(issue.assignee(), cloud);
        List<Subject.Comment> comments = comments(issue.comments(), cloud);
        return new Subject(false, issue.key(), null, summary != null && !summary.isBlank() ? summary : issue.key(),
                issue.description(), issue.status(), issue.type(), issue.resolved(),
                assignee != null ? List.of(assignee) : List.of(), issue.labels(),
                issue.created() != null ? issue.created() : issue.updated(), issue.updated(), comments,
                Math.max(issue.commentTotal(), comments.size()), user(issue.reporter(), cloud));
    }

    /** The comments, oldest first; comments without a numeric id or a time are left out. */
    static List<Subject.Comment> comments(@Nullable JsonNode items, boolean cloud) {
        return comments(JiraIssues.comments(items), cloud);
    }

    private static List<Subject.Comment> comments(List<JiraIssue.Comment> read, boolean cloud) {
        List<Subject.Comment> comments = new ArrayList<>(read.size());
        for (JiraIssue.Comment comment : read) {
            comments.add(new Subject.Comment(comment.id(), user(comment.author(), cloud),
                    comment.body() != null ? comment.body() : "", comment.created()));
        }
        return comments;
    }

    /** A user: Data Center's by name, Cloud's by account id; null without one (an app, a deleted account). */
    static Subject.@Nullable User user(@Nullable JsonNode node, boolean cloud) {
        return user(JiraIssues.user(node), cloud);
    }

    private static Subject.@Nullable User user(JiraIssue.@Nullable User user, boolean cloud) {
        String name = user == null ? null : cloud ? user.accountId() : user.name();
        return name != null && !name.isBlank() ? new Subject.User(name, user.displayName()) : null;
    }

    /** A text field: wiki markup as it is, an ADF document as JSON text, null when absent. */
    static @Nullable String text(@Nullable JsonNode node) {
        return JiraIssues.text(node);
    }

    /** Jira's {@code 2026-10-03T10:15:30.000+0200}, or an ISO offset date-time; null when neither. */
    static @Nullable Instant time(@Nullable String value) {
        return JiraTime.parse(value);
    }

    /** A label in front of a wiki comment: on the same line before text, on its own line before a block. */
    static String labelled(String label, String wiki) {
        if (wiki.isEmpty()) {
            return label;
        }
        return BLOCK_START.matcher(wiki).matches() ? label + "\n" + wiki : label + " " + wiki;
    }

    /** A name inside a bold wiki label: characters that would end or change it are escaped. */
    static String plain(String name) {
        return name.replaceAll("([*_{}\\[\\]|!\\\\])", "\\\\$1");
    }
}
