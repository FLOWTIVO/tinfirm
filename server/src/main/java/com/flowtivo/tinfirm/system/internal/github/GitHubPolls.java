package com.flowtivo.tinfirm.system.internal.github;

import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.BiFunction;

import org.jspecify.annotations.Nullable;

import com.flowtivo.tinfirm.system.CallResult;
import com.flowtivo.tinfirm.system.SystemKind;
import com.flowtivo.tinfirm.system.SystemPolls;
import com.flowtivo.tinfirm.system.internal.http.Json;
import com.flowtivo.tinfirm.system.internal.http.OutboundHttp;
import com.flowtivo.tinfirm.system.internal.tools.PollSupport;

import tools.jackson.databind.JsonNode;

/**
 * GitHub's reads for a poll (the package 4 contract, §5.4): a repository's issues and pull requests updated since a
 * bound ({@code GET /repos/{owner}/{repo}/issues?state=all&since=…&sort=updated
 * &direction=asc}), or, without a scope, every one the account sees ({@code GET /issues?filter=all}, or
 * {@code filter=assigned}); the first page conditional on its ETag ({@code If-None-Match}: a {@code 304} costs no rate
 * limit). A subject's comments written since a time ({@code …/comments?since=}), a pull request's head commit
 * ({@code GET …/pulls/{number}}). The rate limit of every answer ({@code x-ratelimit-*}) is kept; an exhausted one is
 * {@code RATE_LIMITED} with the reset.
 */
public final class GitHubPolls implements SystemPolls.Session {

    private static final DateTimeFormatter SINCE = DateTimeFormatter.ISO_INSTANT;

    private final OutboundHttp http;

    private final String baseUrl;

    private final String token;

    private SystemPolls.@Nullable RateLimit rate;

    public GitHubPolls(OutboundHttp http, String baseUrl, String token) {
        this.http = http;
        this.baseUrl = baseUrl;
        this.token = token.strip();
    }

    @Override
    public SystemKind kind() {
        return SystemKind.GITHUB;
    }

    @Override
    public SystemPolls.@Nullable RateLimit rate() {
        return rate;
    }

    @Override
    public Instant precision(Instant instant) {
        return instant.truncatedTo(ChronoUnit.SECONDS);
    }

    // ---- the listing -----------------------------------------------------------------------------------------------

    @Override
    public CallResult<SystemPolls.Page> list(SystemPolls.Listing listing, Instant since, SystemPolls.Position from,
            int limit, @Nullable String etag) {
        int size = SystemPolls.PAGE_SIZE;
        int offset = Math.max(0, from.offset());
        int page = offset / size + 1;
        int skip = offset % size;
        String query = "state=all&since=" + Json.encode(SINCE.format(since.truncatedTo(ChronoUnit.SECONDS)))
                + "&sort=updated&direction=asc&per_page=" + size + "&page=" + page;
        String uri;
        if (listing.scopes().isEmpty()) {
            uri = baseUrl + "/issues?filter=" + (listing.assignedOnly() ? "assigned" : "all") + "&" + query;
        }
        else {
            GitHubRepository repository = GitHubRepository.parse(listing.scopes().getFirst()).orElse(null);
            if (repository == null) {
                return CallResult.failed(CallResult.Kind.INVALID, listing.scopes().getFirst()
                        + " is not a repository owner/repo");
            }
            uri = repos(repository) + "/issues?" + query;
        }
        HttpRequest.Builder request = request(uri);
        boolean conditional = etag != null && page == 1 && skip == 0;
        if (conditional) {
            request.header("If-None-Match", etag);
        }
        CallResult<OutboundHttp.Response> sent = http.send(baseUrl, request.GET().build());
        OutboundHttp.Response response = sent.value();
        if (response == null) {
            return sent.failedAs();
        }
        keepRate(response);
        if (response.status() == 304 && conditional) {
            return CallResult.ok(SystemPolls.Page.unchanged(from, etag));
        }
        JsonNode parsed = Json.parse(response.body());
        if (!response.success()) {
            return CallResult.failed(PollSupport.failure(GitHubTracker.failure(response, parsed), response));
        }
        if (parsed == null || !parsed.isArray()) {
            return CallResult.failed(CallResult.Kind.INVALID, "GitHub answered without a list of issues");
        }
        List<SystemPolls.Subject> subjects = new ArrayList<>();
        int end = Math.min(parsed.size(), skip + Math.clamp(limit, 1, size));
        for (int i = skip; i < end; i++) {
            SystemPolls.Subject subject = subject(parsed.get(i));
            if (subject != null) {
                subjects.add(subject);
            }
        }
        int count = Math.max(0, end - skip);
        boolean hasNext = GitHubTracker.page(List.of(), response.headers()).hasNext();
        boolean more = end < parsed.size() || hasNext && parsed.size() > 0;
        String newEtag = page == 1 && skip == 0 ? response.header("etag").orElse(null) : null;
        return CallResult.ok(new SystemPolls.Page(subjects, count, more,
                new SystemPolls.Position(offset + count, null), false, newEtag));
    }

    // ---- one subject -----------------------------------------------------------------------------------------------

    @Override
    public CallResult<SystemPolls.Subject> complete(SystemPolls.Subject subject, @Nullable Instant commentsSince) {
        GitHubTracker.Ref ref = GitHubTracker.ref(subject.key());
        if (ref == null) {
            return CallResult.failed(CallResult.Kind.INVALID, "`" + subject.key() + "` is not a GitHub key");
        }
        SystemPolls.Subject read = subject;
        if (subject.commentTotal() > 0) {
            String uri = repos(ref.repository()) + "/issues/" + ref.number() + "/comments?per_page=100"
                    + (commentsSince != null ? "&since=" + Json.encode(SINCE.format(commentsSince
                        .truncatedTo(ChronoUnit.SECONDS))) : "");
            CallResult<List<SystemPolls.Comment>> comments = call(get(uri), (body, headers) -> body.isArray()
                    ? CallResult.ok(comments(body))
                    : CallResult.failed(CallResult.Kind.INVALID, "GitHub answered without a list of comments"));
            if (comments.value() == null) {
                return comments.failedAs();
            }
            List<SystemPolls.Comment> all = comments.value();
            read = read.withComments(all.subList(Math.max(0, all.size() - SystemPolls.COMMENTS), all.size()));
        }
        if (subject.kind() == SystemPolls.SubjectKind.CHANGE_REQUEST) {
            CallResult<String> head = call(get(repos(ref.repository()) + "/pulls/" + ref.number()),
                    (body, headers) -> CallResult.ok(Json.text(body.get("head"), "sha")));
            if (!head.succeeded()) {
                return head.failedAs();
            }
            read = read.withHeadSha(head.value());
        }
        return CallResult.ok(read);
    }

    @Override
    public CallResult<SystemPolls.Subject> read(String key) {
        GitHubTracker.Ref ref = GitHubTracker.ref(key.strip());
        if (ref == null) {
            return CallResult.failed(CallResult.Kind.NOT_FOUND, "`" + key + "` is not a GitHub key owner/repo#number");
        }
        CallResult<SystemPolls.Subject> read = call(get(repos(ref.repository()) + "/issues/" + ref.number()),
                (body, headers) -> {
                    SystemPolls.Subject subject = subject(body);
                    return subject != null ? CallResult.ok(subject)
                            : CallResult.failed(CallResult.Kind.INVALID, "GitHub returned no usable issue");
                });
        return read.value() != null ? complete(read.value(), null) : read;
    }

    @Override
    public CallResult<List<SystemPolls.Subject>> search(@Nullable String scope, @Nullable String query, int limit) {
        String words = query != null ? query.strip() : "";
        if (GitHubTracker.KEY.matcher(words).matches()) {
            return read(words).map(List::of);
        }
        StringBuilder q = new StringBuilder();
        if (scope != null && !scope.isBlank()) {
            q.append("repo:").append(scope.strip());
        }
        if (words.matches("#?[0-9]{1,10}") && scope != null && !scope.isBlank()) {
            return read(scope.strip() + "#" + words.replace("#", "")).map(List::of);
        }
        if (!words.isEmpty()) {
            q.append(q.isEmpty() ? "" : " ").append(words);
        }
        if (q.isEmpty()) {
            q.append("involves:@me");
        }
        int max = Math.clamp(limit, 1, SystemPolls.PAGE_SIZE);
        String uri = baseUrl + "/search/issues?q=" + Json.encode(q.toString()) + "&sort=updated&order=desc&per_page="
                + max;
        return call(get(uri), (body, headers) -> {
            JsonNode items = body.get("items");
            List<SystemPolls.Subject> subjects = new ArrayList<>();
            if (items != null && items.isArray()) {
                for (JsonNode item : items) {
                    SystemPolls.Subject subject = subject(item);
                    if (subject != null && subjects.size() < max) {
                        subjects.add(subject);
                    }
                }
            }
            return CallResult.ok(subjects);
        });
    }

    // ---- HTTP ------------------------------------------------------------------------------------------------------

    private <T> CallResult<T> call(HttpRequest request, BiFunction<JsonNode, HttpHeaders, CallResult<T>> handler) {
        CallResult<OutboundHttp.Response> sent = http.send(baseUrl, request);
        OutboundHttp.Response response = sent.value();
        if (response == null) {
            return sent.failedAs();
        }
        keepRate(response);
        JsonNode parsed = Json.parse(response.body());
        if (!response.success()) {
            return CallResult.failed(PollSupport.failure(GitHubTracker.failure(response, parsed), response));
        }
        if (parsed == null) {
            return CallResult.failed(CallResult.Kind.INVALID, "GitHub answered " + response.status()
                    + " without JSON");
        }
        return handler.apply(parsed, response.headers());
    }

    private void keepRate(OutboundHttp.Response response) {
        SystemPolls.RateLimit said = PollSupport.rate(response);
        if (said != null) {
            rate = said;
        }
    }

    private HttpRequest get(String uri) {
        return request(uri).GET().build();
    }

    private HttpRequest.Builder request(String uri) {
        return http.request(uri)
            .header("Accept", "application/vnd.github+json")
            .header("Authorization", "Bearer " + token)
            .header("X-GitHub-Api-Version", GitHubTracker.API_VERSION);
    }

    private String repos(GitHubRepository repository) {
        return baseUrl + "/repos/" + Json.encode(repository.owner()) + "/" + Json.encode(repository.name());
    }

    // ---- parsing ---------------------------------------------------------------------------------------------------

    /**
     * An issue, or a pull request GitHub lists among the issues; its stable id is GitHub's {@code id} of the issue.
     * Null when a field it needs is missing.
     */
    static SystemPolls.@Nullable Subject subject(JsonNode item) {
        String id = Json.textOrNumber(item, "id");
        JsonNode number = item.get("number");
        Instant updated = Json.time(Json.text(item, "updated_at"));
        String htmlUrl = Json.text(item, "html_url");
        GitHubRepository repository = GitHubTracker.repository(Json.text(item, "repository_url"), htmlUrl);
        if (id == null || number == null || !number.isIntegralNumber() || updated == null || repository == null) {
            return null;
        }
        String key = repository + "#" + number.longValue();
        String title = Json.text(item, "title");
        List<String> labels = new ArrayList<>();
        JsonNode labelNodes = item.get("labels");
        if (labelNodes != null && labelNodes.isArray()) {
            labelNodes.forEach(label -> {
                String name = label.isString() ? label.stringValue() : Json.text(label, "name");
                if (name != null) {
                    labels.add(name);
                }
            });
        }
        List<SystemPolls.User> assignees = new ArrayList<>();
        JsonNode assigneeNodes = item.get("assignees");
        if (assigneeNodes != null && assigneeNodes.isArray()) {
            assigneeNodes.forEach(assignee -> {
                SystemPolls.User user = user(assignee);
                if (user != null) {
                    assignees.add(user);
                }
            });
        }
        Instant created = Json.time(Json.text(item, "created_at"));
        return new SystemPolls.Subject(id, key, item.has("pull_request") ? SystemPolls.SubjectKind.CHANGE_REQUEST
                : SystemPolls.SubjectKind.ISSUE, repository.toString(), GitHubTracker.httpsUrl(htmlUrl),
                title != null && !title.isBlank() ? title : key, blankToNull(Json.text(item, "body")), null,
                Json.text(item.get("type"), "name"), !"closed".equals(Json.text(item, "state")), assignees, labels,
                created != null ? created : updated, updated, List.of(), Json.integer(item, "comments", 0),
                user(item.get("user")), null);
    }

    /** The comments, oldest first; those without an id or a time are left out. */
    static List<SystemPolls.Comment> comments(JsonNode items) {
        Map<Long, SystemPolls.Comment> sorted = new TreeMap<>();
        for (JsonNode item : items) {
            JsonNode id = item.get("id");
            Instant created = Json.time(Json.text(item, "created_at"));
            if (id == null || !id.isIntegralNumber() || created == null) {
                continue;
            }
            String body = Json.text(item, "body");
            sorted.put(id.longValue(), new SystemPolls.Comment(Long.toString(id.longValue()), id.longValue(),
                    user(item.get("user")), body != null ? body : "", created, PollSupport.atMentions(body)));
        }
        return List.copyOf(sorted.values());
    }

    static SystemPolls.@Nullable User user(@Nullable JsonNode node) {
        String login = Json.text(node, "login");
        if (login == null || login.isBlank()) {
            return null;
        }
        return new SystemPolls.User(Json.textOrNumber(node, "id"), login, Json.text(node, "name"));
    }

    private static @Nullable String blankToNull(@Nullable String text) {
        return text == null || text.isBlank() ? null : text;
    }
}
