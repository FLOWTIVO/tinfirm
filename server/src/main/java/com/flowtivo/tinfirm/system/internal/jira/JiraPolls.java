package com.flowtivo.tinfirm.system.internal.jira;

import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

import com.flowtivo.tinfirm.shared.jira.Adf;
import com.flowtivo.tinfirm.shared.jira.JiraIssue;
import com.flowtivo.tinfirm.shared.jira.JiraIssues;
import com.flowtivo.tinfirm.shared.jira.JiraMarkup;
import com.flowtivo.tinfirm.shared.jira.JiraSearch;
import com.flowtivo.tinfirm.system.CallResult;
import com.flowtivo.tinfirm.system.SystemKind;
import com.flowtivo.tinfirm.system.SystemPolls;
import com.flowtivo.tinfirm.system.internal.http.Json;
import com.flowtivo.tinfirm.system.internal.http.OutboundHttp;
import com.flowtivo.tinfirm.system.internal.tools.Failures;
import com.flowtivo.tinfirm.system.internal.tools.PollSupport;

import tools.jackson.databind.JsonNode;

/**
 * A Jira site's reads for a poll (the package 4 contract, §5.4): one JQL search
 * {@code project in (…) AND updated >= "…" ORDER BY updated ASC, key ASC} over the triggers' projects, in the time zone
 * of the account that searches (JQL dates are read in it, at minute precision); Data Center pages by {@code startAt},
 * Cloud's {@code search/jql} by its cursor, walking the pages before an offset it has no cursor for. The search carries
 * the issues' comments; the newest are read separately when it cut them. Data Center's texts are wiki markup
 * ({@link JiraMarkup}), Cloud's ADF ({@link Adf}); users are Data Center's user key and name, Cloud's account id.
 */
public final class JiraPolls implements SystemPolls.Session {

    /** The fields of a listing and a read. */
    static final String FIELDS = "summary,description,status,issuetype,assignee,reporter,labels,created,updated,"
            + "comment,resolutiondate";

    /** The fields of the editor's search. */
    static final String SEARCH_FIELDS = "summary,status,issuetype,assignee,labels,created,updated,resolutiondate";

    /** A JQL date. */
    private static final DateTimeFormatter JQL_DATE = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm", Locale.ROOT);

    /** Jira project keys. */
    private static final Pattern PROJECT_KEY = Pattern.compile("^[A-Z][A-Z0-9_]{0,49}$");

    /** An issue key. */
    private static final Pattern ISSUE_KEY = Pattern.compile("^[A-Z][A-Z0-9_]{0,49}-[0-9]{1,10}$");

    /** Pages Cloud walks at most to reach an offset it has no cursor for. */
    static final int MAX_PAGES = 40;

    private final OutboundHttp http;

    private final boolean cloud;

    private final String apiBase;

    private final String siteUrl;

    private final String authorization;

    private @Nullable ZoneId zone;

    private SystemPolls.@Nullable RateLimit rate;

    /** Where this session's last Cloud page ended: its search, the offset after it, the cursor from there. */
    private @Nullable String resumeJql;

    private int resumeOffset = -1;

    private @Nullable String resumeToken;

    private JiraPolls(OutboundHttp http, boolean cloud, String apiBase, String siteUrl, String authorization) {
        this.http = http;
        this.cloud = cloud;
        this.apiBase = apiBase;
        this.siteUrl = siteUrl;
        this.authorization = authorization;
    }

    /** Jira Data Center: a personal access token or an OAuth access token, as a bearer token. */
    public static JiraPolls dataCenter(OutboundHttp http, String baseUrl, String token) {
        return new JiraPolls(http, false, baseUrl, baseUrl, "Bearer " + token.strip());
    }

    /** Jira Cloud: an OAuth token at the gateway, or {@code email:api-token} as Basic authentication at the site. */
    public static JiraPolls cloud(OutboundHttp http, String siteUrl, String apiBase, String token, boolean oauth) {
        String authorization = oauth ? "Bearer " + token.strip()
                : "Basic " + Base64.getEncoder().encodeToString(token.strip().getBytes(StandardCharsets.UTF_8));
        return new JiraPolls(http, true, apiBase, siteUrl, authorization);
    }

    @Override
    public SystemKind kind() {
        return cloud ? SystemKind.JIRA_CLOUD : SystemKind.JIRA_DC;
    }

    @Override
    public SystemPolls.@Nullable RateLimit rate() {
        return rate;
    }

    private String api() {
        return apiBase + (cloud ? "/rest/api/3" : "/rest/api/2");
    }

    private String name() {
        return cloud ? "Jira Cloud" : "Jira";
    }

    // ---- the listing -----------------------------------------------------------------------------------------------

    @Override
    public CallResult<SystemPolls.Page> list(SystemPolls.Listing listing, Instant since, SystemPolls.Position from,
            int limit, @Nullable String etag) {
        CallResult<ZoneId> zoneRead = zone();
        if (zoneRead.value() == null) {
            return zoneRead.failedAs();
        }
        String jql;
        try {
            jql = jql(listing, since, zoneRead.value());
        }
        catch (IllegalArgumentException ex) {
            return CallResult.failed(CallResult.Kind.INVALID, ex.getMessage());
        }
        int max = Math.clamp(limit, 1, SystemPolls.PAGE_SIZE);
        if (!cloud) {
            int offset = Math.max(0, from.offset());
            return call(get(JiraSearch.dataCenterUrl(api(), jql, FIELDS, offset, max)), body -> {
                        List<SystemPolls.Subject> subjects = new ArrayList<>();
                        int count = issues(body, subjects);
                        int total = Json.integer(body, "total", offset + count);
                        int next = offset + count;
                        return CallResult.ok(new SystemPolls.Page(subjects, count, count > 0 && next < total,
                                new SystemPolls.Position(next, null), false, null));
                    });
        }
        return cloudPage(jql, from, max);
    }

    /**
     * {@code search/jql} knows no offset: a page goes on from the position's cursor, or from where this session's last
     * page ended, else the pages before the offset are walked and skipped.
     */
    private CallResult<SystemPolls.Page> cloudPage(String jql, SystemPolls.Position from, int max) {
        int toSkip = Math.max(0, from.offset());
        String cursor = null;
        if (from.cursor() != null) {
            cursor = from.cursor();
            toSkip = 0;
        }
        else if (jql.equals(resumeJql) && from.offset() == resumeOffset && resumeToken != null) {
            cursor = resumeToken;
            toSkip = 0;
        }
        int pages = 0;
        while (toSkip > 0) {
            if (++pages > MAX_PAGES) {
                return CallResult.failed(CallResult.Kind.INVALID, "More issues changed within one minute than "
                        + "Tinfirm pages through in Jira Cloud; narrow the triggers' projects");
            }
            String uri = cloudSearch(jql, Math.min(toSkip, SystemPolls.PAGE_SIZE), cursor);
            CallResult<String[]> skipped = call(get(uri), body -> {
                JsonNode issues = body.get("issues");
                int count = issues != null && issues.isArray() ? issues.size() : 0;
                return CallResult.ok(new String[] {Integer.toString(count), Json.text(body, "nextPageToken")});
            });
            if (skipped.value() == null) {
                return skipped.failedAs();
            }
            int count = Integer.parseInt(skipped.value()[0]);
            toSkip -= count;
            cursor = skipped.value()[1];
            if (count == 0 || cursor == null) {
                return CallResult.ok(new SystemPolls.Page(List.of(), 0, false, from, false, null));
            }
        }
        String uri = cloudSearch(jql, max, cursor);
        return call(get(uri), body -> {
            List<SystemPolls.Subject> subjects = new ArrayList<>();
            int count = issues(body, subjects);
            String token = Json.text(body, "nextPageToken");
            boolean last = Json.flag(body, "isLast") || token == null;
            int next = from.offset() + count;
            resumeJql = jql;
            resumeOffset = next;
            resumeToken = token;
            return CallResult.ok(new SystemPolls.Page(subjects, count, count > 0 && !last,
                    new SystemPolls.Position(next, token), false, null));
        });
    }

    private String cloudSearch(String jql, int max, @Nullable String cursor) {
        return JiraSearch.cloudUrl(api(), jql, FIELDS, max, cursor);
    }

    /** The JQL of a listing: its projects (none: every issue), or what is assigned to the account. */
    static String jql(SystemPolls.Listing listing, Instant since, ZoneId zone) {
        String bound = "updated >= \"" + JQL_DATE.format(since.atZone(zone).truncatedTo(ChronoUnit.MINUTES)) + "\"";
        String order = " ORDER BY updated ASC, key ASC";
        if (listing.assignedOnly()) {
            return "assignee = currentUser() AND " + bound + order;
        }
        if (listing.scopes().isEmpty()) {
            return bound + order;
        }
        StringBuilder keys = new StringBuilder();
        for (String key : listing.scopes()) {
            if (!PROJECT_KEY.matcher(key).matches()) {
                throw new IllegalArgumentException("Not a Jira project key: " + key);
            }
            keys.append(keys.isEmpty() ? "" : ", ").append('"').append(key).append('"');
        }
        return "project in (" + keys + ") AND " + bound + order;
    }

    @Override
    public Instant precision(Instant instant) {
        return instant.atZone(zone != null ? zone : ZoneOffset.UTC).truncatedTo(ChronoUnit.MINUTES).toInstant();
    }

    /** The account's time zone, which JQL dates are read in ({@code GET …/myself}), read once per session. */
    private CallResult<ZoneId> zone() {
        if (zone != null) {
            return CallResult.ok(zone);
        }
        CallResult<ZoneId> read = call(get(api() + "/myself"), body -> CallResult.ok(zoneOf(Json.text(body,
                "timeZone"))));
        if (read.value() != null) {
            zone = read.value();
        }
        return read;
    }

    static ZoneId zoneOf(@Nullable String timeZone) {
        if (timeZone == null || timeZone.isBlank()) {
            return ZoneOffset.UTC;
        }
        try {
            return ZoneId.of(timeZone);
        }
        catch (DateTimeException ex) {
            return ZoneOffset.UTC;
        }
    }

    // ---- one subject -----------------------------------------------------------------------------------------------

    @Override
    public CallResult<SystemPolls.Subject> complete(SystemPolls.Subject subject, @Nullable Instant commentsSince) {
        if (!subject.commentsCut()) {
            return CallResult.ok(subject);
        }
        String issue = api() + "/issue/" + Json.encode(subject.key());
        String uri = cloud ? issue + "/comment?orderBy=-created&maxResults=" + SystemPolls.COMMENTS
                : issue + "/comment?startAt=" + Math.max(0, subject.commentTotal() - SystemPolls.COMMENTS)
                        + "&maxResults=" + SystemPolls.COMMENTS;
        return call(get(uri), body -> CallResult.ok(subject.withComments(comments(body.get("comments")))));
    }

    @Override
    public CallResult<SystemPolls.Subject> read(String key) {
        String trimmed = key.strip();
        if (!ISSUE_KEY.matcher(trimmed).matches()) {
            return CallResult.failed(CallResult.Kind.NOT_FOUND, "`" + key + "` is not a Jira issue key");
        }
        CallResult<SystemPolls.Subject> read = call(get(api() + "/issue/" + Json.encode(trimmed) + "?fields="
                + Json.encode(FIELDS)), body -> {
                    SystemPolls.Subject subject = subject(body);
                    return subject != null ? CallResult.ok(subject)
                            : CallResult.failed(CallResult.Kind.INVALID, name() + " returned no usable issue");
                });
        return read.value() != null ? complete(read.value(), null) : read;
    }

    @Override
    public CallResult<List<SystemPolls.Subject>> search(@Nullable String scope, @Nullable String query, int limit) {
        String words = query != null ? query.strip() : "";
        StringBuilder jql = new StringBuilder();
        if (scope != null && !scope.isBlank()) {
            if (!PROJECT_KEY.matcher(scope.strip()).matches()) {
                return CallResult.failed(CallResult.Kind.INVALID, "Not a Jira project key: " + scope);
            }
            jql.append("project = \"").append(scope.strip()).append('"');
        }
        if (!words.isEmpty()) {
            String clause = ISSUE_KEY.matcher(words.toUpperCase(Locale.ROOT)).matches()
                    ? "key = \"" + words.toUpperCase(Locale.ROOT) + "\""
                    : "text ~ \"" + words.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
            jql.append(jql.isEmpty() ? "" : " AND ").append(clause);
        }
        jql.append(" ORDER BY updated DESC");
        int max = Math.clamp(limit, 1, SystemPolls.PAGE_SIZE);
        String uri = cloud ? JiraSearch.cloudUrl(api(), jql.toString().strip(), SEARCH_FIELDS, max, null)
                : JiraSearch.dataCenterUrl(api(), jql.toString().strip(), SEARCH_FIELDS, 0, max);
        return call(get(uri), body -> {
            List<SystemPolls.Subject> subjects = new ArrayList<>();
            issues(body, subjects);
            return CallResult.ok(subjects.subList(0, Math.min(subjects.size(), max)));
        });
    }

    // ---- HTTP ------------------------------------------------------------------------------------------------------

    private <T> CallResult<T> call(HttpRequest request, Function<JsonNode, CallResult<T>> handler) {
        CallResult<OutboundHttp.Response> sent = http.send(apiBase, request);
        OutboundHttp.Response response = sent.value();
        if (response == null) {
            return sent.failedAs();
        }
        SystemPolls.RateLimit said = PollSupport.rate(response);
        if (said != null) {
            rate = said;
        }
        JsonNode parsed = Json.parse(response.body());
        if (!response.success()) {
            return CallResult.failed(PollSupport.failure(Failures.of(name(), response,
                    JiraTracker.messages(response, parsed), false, null), response));
        }
        if (parsed == null) {
            return CallResult.failed(CallResult.Kind.INVALID, name() + " answered " + response.status()
                    + " without JSON");
        }
        return handler.apply(parsed);
    }

    private HttpRequest get(String uri) {
        return http.request(uri)
            .header("Accept", "application/json")
            .header("Authorization", authorization)
            .header("X-Atlassian-Token", "no-check")
            .GET()
            .build();
    }

    // ---- parsing ---------------------------------------------------------------------------------------------------

    /** The usable issues of a search into the list; answers how many entries it held. */
    private int issues(JsonNode body, List<SystemPolls.Subject> into) {
        JsonNode items = body.get("issues");
        if (items == null || !items.isArray()) {
            return 0;
        }
        for (JsonNode item : items) {
            SystemPolls.Subject subject = subject(item);
            if (subject != null) {
                into.add(subject);
            }
        }
        return items.size();
    }

    /**
     * An issue; null without an id, a key or {@code updated} (the shared reading, {@link JiraIssues#parse}). Closed once
     * resolved ({@code resolutiondate}).
     */
    SystemPolls.@Nullable Subject subject(JsonNode item) {
        JiraIssue issue = JiraIssues.parse(item);
        if (issue == null || issue.id() == null) {
            return null;
        }
        String key = issue.key();
        List<SystemPolls.Comment> comments = comments(issue.comments());
        SystemPolls.User assignee = user(issue.assignee());
        String summary = issue.summary();
        String page = siteUrl + "/browse/" + key;
        return new SystemPolls.Subject(issue.id(), key, SystemPolls.SubjectKind.ISSUE, JiraIssues.projectOf(key),
                page.length() <= 500 && page.matches("^https?://\\S+$") ? page : null,
                summary != null && !summary.isBlank() ? summary : key, markdown(issue.description()), issue.status(),
                issue.type(), !issue.resolved(), assignee != null ? List.of(assignee) : List.of(), issue.labels(),
                issue.created() != null ? issue.created() : issue.updated(), issue.updated(), comments,
                Math.max(issue.commentTotal(), comments.size()), user(issue.reporter()), null);
    }

    /** The comments, oldest first; those without a numeric id or a time are left out. */
    List<SystemPolls.Comment> comments(@Nullable JsonNode items) {
        return comments(JiraIssues.comments(items));
    }

    private List<SystemPolls.Comment> comments(List<JiraIssue.Comment> read) {
        List<SystemPolls.Comment> comments = new ArrayList<>(read.size());
        for (JiraIssue.Comment comment : read) {
            String markdown = markdown(comment.body());
            comments.add(new SystemPolls.Comment(Long.toString(comment.id()), comment.id(), user(comment.author()),
                    markdown != null ? markdown : "", comment.created(), mentions(comment.body())));
        }
        return comments;
    }

    /** The identities a raw comment mentions: wiki mentions, and Cloud's mention nodes. */
    private Set<String> mentions(@Nullable String raw) {
        Set<String> mentions = new LinkedHashSet<>(PollSupport.wikiMentions(raw));
        if (cloud && raw != null) {
            mentions.addAll(Adf.mentionIds(raw));
        }
        return mentions;
    }

    /** A user: Data Center's key and name, Cloud's account id; null without either. */
    SystemPolls.@Nullable User user(@Nullable JsonNode node) {
        return user(JiraIssues.user(node));
    }

    private SystemPolls.@Nullable User user(JiraIssue.@Nullable User user) {
        if (user == null) {
            return null;
        }
        if (cloud) {
            String accountId = user.accountId();
            return accountId != null && !accountId.isBlank() ? new SystemPolls.User(accountId, null, user.displayName())
                    : null;
        }
        String name = user.name();
        String key = user.key();
        if ((name == null || name.isBlank()) && (key == null || key.isBlank())) {
            return null;
        }
        return new SystemPolls.User(key != null && !key.isBlank() ? key : null, name, user.displayName());
    }

    private @Nullable String markdown(@Nullable String raw) {
        return JiraIssues.markdown(raw, cloud);
    }
}
