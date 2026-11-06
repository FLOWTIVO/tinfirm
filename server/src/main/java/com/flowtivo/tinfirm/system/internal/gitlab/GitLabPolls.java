package com.flowtivo.tinfirm.system.internal.gitlab;

import java.net.http.HttpRequest;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

import org.jspecify.annotations.Nullable;

import com.flowtivo.tinfirm.system.CallResult;
import com.flowtivo.tinfirm.system.SystemKind;
import com.flowtivo.tinfirm.system.SystemPolls;
import com.flowtivo.tinfirm.system.internal.http.Json;
import com.flowtivo.tinfirm.system.internal.http.OutboundHttp;
import com.flowtivo.tinfirm.system.internal.tools.PollSupport;

import tools.jackson.databind.JsonNode;

/**
 * GitLab's reads for a poll (the package 4 contract, §5.4): the issues or the merge requests of a path updated after a
 * bound ({@code updated_after=…&order_by=updated_at&sort=asc}), as a project, else as a group when no project has the
 * path, or of every project the account sees ({@code scope=all}, or {@code scope=assigned_to_me}). A merge request's
 * head commit is its {@code sha}; a subject's notes of people ({@code …/notes?sort=desc&order_by=created_at}). Its
 * stable id is GitLab's global id, {@code issue:} or {@code mr:} in front, since the two count apart.
 */
public final class GitLabPolls implements SystemPolls.Session {

    private static final DateTimeFormatter AFTER = DateTimeFormatter.ISO_INSTANT;

    private final OutboundHttp http;

    private final String baseUrl;

    private final String token;

    private final boolean oauth;

    private SystemPolls.@Nullable RateLimit rate;

    public GitLabPolls(OutboundHttp http, String baseUrl, String token, boolean oauth) {
        this.http = http;
        this.baseUrl = baseUrl;
        this.token = token.strip();
        this.oauth = oauth;
    }

    @Override
    public SystemKind kind() {
        return SystemKind.GITLAB;
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
        String what = listing.changeRequests() ? "/merge_requests" : "/issues";
        String query = "?updated_after=" + Json.encode(AFTER.format(since.truncatedTo(ChronoUnit.SECONDS)))
                + "&order_by=updated_at&sort=asc&per_page=" + size + "&page=" + page;
        CallResult<Listed> read;
        if (listing.scopes().isEmpty()) {
            read = listed(api() + what + query + "&scope=" + (listing.assignedOnly() ? "assigned_to_me" : "all"),
                    listing.changeRequests());
        }
        else {
            GitLabProject path = GitLabProject.parse(listing.scopes().getFirst()).orElse(null);
            String group = path == null ? groupPath(listing.scopes().getFirst()) : null;
            if (path == null && group == null) {
                return CallResult.failed(CallResult.Kind.INVALID, listing.scopes().getFirst()
                        + " is not a GitLab path");
            }
            if (path != null) {
                read = listed(api() + "/projects/" + path.id() + what + query, listing.changeRequests());
                if (read.failure() != null && read.failure().kind() == CallResult.Kind.NOT_FOUND) {
                    read = listed(api() + "/groups/" + path.id() + what + query, listing.changeRequests());
                }
            }
            else {
                read = listed(api() + "/groups/" + group + what + query, listing.changeRequests());
            }
        }
        Listed listed = read.value();
        if (listed == null) {
            return read.failedAs();
        }
        List<SystemPolls.Subject> subjects = new ArrayList<>();
        int end = Math.min(listed.items().size(), skip + Math.clamp(limit, 1, size));
        for (int i = skip; i < end; i++) {
            SystemPolls.Subject subject = listed.items().get(i);
            if (subject != null) {
                subjects.add(subject);
            }
        }
        int count = Math.max(0, end - skip);
        boolean more = end < listed.items().size() || listed.hasNext() && !listed.items().isEmpty();
        return CallResult.ok(new SystemPolls.Page(subjects, count, more,
                new SystemPolls.Position(offset + count, null), false, null));
    }

    /** A page of a list, its entries in order (null for an unusable one), and whether another page follows. */
    private record Listed(List<SystemPolls.@Nullable Subject> items, boolean hasNext) {
    }

    private CallResult<Listed> listed(String uri, boolean mergeRequests) {
        CallResult<OutboundHttp.Response> sent = http.send(baseUrl, get(uri));
        OutboundHttp.Response response = sent.value();
        if (response == null) {
            return sent.failedAs();
        }
        keepRate(response);
        JsonNode parsed = Json.parse(response.body());
        if (!response.success()) {
            return CallResult.failed(PollSupport.failure(GitLabTracker.failure(response, parsed), response));
        }
        if (parsed == null || !parsed.isArray()) {
            return CallResult.failed(CallResult.Kind.INVALID, "GitLab answered without a list");
        }
        List<SystemPolls.@Nullable Subject> items = new ArrayList<>();
        parsed.forEach(item -> items.add(subject(item, mergeRequests)));
        String nextPage = response.header("x-next-page").map(String::strip).orElse("");
        boolean hasNext = !nextPage.isEmpty() || response.headers().allValues("link").stream()
            .anyMatch(link -> link.contains("rel=\"next\""));
        return CallResult.ok(new Listed(items, hasNext));
    }

    /** A single-segment path is a top-level group: its id in API paths. */
    private static @Nullable String groupPath(String scope) {
        String path = scope.strip();
        return path.matches("^[A-Za-z0-9_.][A-Za-z0-9_.-]{0,254}$") ? Json.encode(path) : null;
    }

    // ---- one subject -----------------------------------------------------------------------------------------------

    @Override
    public CallResult<SystemPolls.Subject> complete(SystemPolls.Subject subject, @Nullable Instant commentsSince) {
        if (subject.commentTotal() == 0) {
            return CallResult.ok(subject);
        }
        GitLabTracker.Ref ref = GitLabTracker.ref(subject.key());
        if (ref == null) {
            return CallResult.failed(CallResult.Kind.INVALID, "`" + subject.key() + "` is not a GitLab key");
        }
        String uri = subjectUrl(ref) + "/notes?sort=desc&order_by=created_at&per_page=" + SystemPolls.COMMENTS;
        return call(get(uri), body -> body.isArray() ? CallResult.ok(subject.withComments(notes(body)))
                : CallResult.failed(CallResult.Kind.INVALID, "GitLab answered without a list of notes"));
    }

    @Override
    public CallResult<SystemPolls.Subject> read(String key) {
        GitLabTracker.Ref ref = GitLabTracker.ref(key.strip());
        if (ref == null) {
            return CallResult.failed(CallResult.Kind.NOT_FOUND, "`" + key + "` is not a GitLab key group/project#iid "
                    + "or group/project!iid");
        }
        CallResult<SystemPolls.Subject> read = call(get(subjectUrl(ref)), body -> {
            SystemPolls.Subject subject = subject(body, ref.mergeRequest());
            return subject != null ? CallResult.ok(subject)
                    : CallResult.failed(CallResult.Kind.INVALID, "GitLab returned no usable subject");
        });
        return read.value() != null ? complete(read.value(), null) : read;
    }

    @Override
    public CallResult<List<SystemPolls.Subject>> search(@Nullable String scope, @Nullable String query, int limit) {
        String words = query != null ? query.strip() : "";
        if (GitLabTracker.KEY.matcher(words).matches()) {
            return read(words).map(List::of);
        }
        int max = Math.clamp(limit, 1, SystemPolls.PAGE_SIZE);
        List<SystemPolls.Subject> found = new ArrayList<>();
        for (boolean mergeRequests : List.of(false, true)) {
            String what = mergeRequests ? "/merge_requests" : "/issues";
            String parameters = "?order_by=updated_at&sort=desc&per_page=" + max
                    + (words.isEmpty() ? "" : "&search=" + Json.encode(words));
            CallResult<Listed> read;
            if (scope != null && !scope.isBlank()) {
                GitLabProject path = GitLabProject.parse(scope).orElse(null);
                String group = path == null ? groupPath(scope) : null;
                if (path == null && group == null) {
                    return CallResult.failed(CallResult.Kind.INVALID, scope + " is not a GitLab path");
                }
                read = path != null ? listed(api() + "/projects/" + path.id() + what + parameters, mergeRequests)
                        : listed(api() + "/groups/" + group + what + parameters, mergeRequests);
                if (path != null && read.failure() != null && read.failure().kind() == CallResult.Kind.NOT_FOUND) {
                    read = listed(api() + "/groups/" + path.id() + what + parameters, mergeRequests);
                }
            }
            else {
                read = listed(api() + what + parameters + "&scope=all", mergeRequests);
            }
            if (read.value() == null) {
                return read.failedAs();
            }
            read.value().items().stream().filter(item -> item != null).forEach(found::add);
        }
        found.sort((a, b) -> b.updated().compareTo(a.updated()));
        return CallResult.ok(List.copyOf(found.subList(0, Math.min(found.size(), max))));
    }

    // ---- HTTP ------------------------------------------------------------------------------------------------------

    private <T> CallResult<T> call(HttpRequest request, Function<JsonNode, CallResult<T>> handler) {
        CallResult<OutboundHttp.Response> sent = http.send(baseUrl, request);
        OutboundHttp.Response response = sent.value();
        if (response == null) {
            return sent.failedAs();
        }
        keepRate(response);
        JsonNode parsed = Json.parse(response.body());
        if (!response.success()) {
            return CallResult.failed(PollSupport.failure(GitLabTracker.failure(response, parsed), response));
        }
        if (parsed == null) {
            return CallResult.failed(CallResult.Kind.INVALID, "GitLab answered " + response.status()
                    + " without JSON");
        }
        return handler.apply(parsed);
    }

    private void keepRate(OutboundHttp.Response response) {
        SystemPolls.RateLimit said = PollSupport.rate(response);
        if (said != null) {
            rate = said;
        }
    }

    private HttpRequest get(String uri) {
        HttpRequest.Builder request = http.request(uri).header("Accept", "application/json");
        return (oauth ? request.header("Authorization", "Bearer " + token) : request.header("PRIVATE-TOKEN", token))
            .GET()
            .build();
    }

    private String api() {
        return baseUrl.endsWith("/api/v4") ? baseUrl : baseUrl + "/api/v4";
    }

    private String subjectUrl(GitLabTracker.Ref ref) {
        return api() + "/projects/" + ref.project().id() + (ref.mergeRequest() ? "/merge_requests/" : "/issues/")
                + ref.iid();
    }

    // ---- parsing ---------------------------------------------------------------------------------------------------

    /** An issue or merge request; null when a field it needs is missing. */
    static SystemPolls.@Nullable Subject subject(JsonNode item, boolean mergeRequest) {
        String id = Json.textOrNumber(item, "id");
        JsonNode iid = item.get("iid");
        Instant updated = Json.time(Json.text(item, "updated_at"));
        GitLabProject project = GitLabTracker.projectOf(item);
        if (id == null || iid == null || !iid.isIntegralNumber() || updated == null || project == null) {
            return null;
        }
        String key = project.path() + (mergeRequest ? "!" : "#") + iid.longValue();
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
        else {
            SystemPolls.User single = user(item.get("assignee"));
            if (single != null) {
                assignees.add(single);
            }
        }
        Instant created = Json.time(Json.text(item, "created_at"));
        String state = Json.text(item, "state");
        String url = Json.text(item, "web_url");
        String description = Json.text(item, "description");
        return new SystemPolls.Subject((mergeRequest ? "mr:" : "issue:") + id, key, mergeRequest
                ? SystemPolls.SubjectKind.CHANGE_REQUEST : SystemPolls.SubjectKind.ISSUE, project.path(),
                url != null && url.startsWith("https://") && url.length() <= 500 ? url : null,
                title != null && !title.isBlank() ? title : key,
                description != null && !description.isBlank() ? description : null, null,
                mergeRequest ? null : Json.text(item, "issue_type"), state == null || "opened".equals(state),
                assignees, labels, created != null ? created : updated, updated, List.of(),
                Json.integer(item, "user_notes_count", 0), user(item.get("author")),
                mergeRequest ? Json.text(item, "sha") : null);
    }

    /** The notes of people, oldest first; the system's notes and those without an id or a time are left out. */
    static List<SystemPolls.Comment> notes(JsonNode items) {
        Map<Long, SystemPolls.Comment> sorted = new TreeMap<>();
        for (JsonNode item : items) {
            JsonNode id = item.get("id");
            Instant created = Json.time(Json.text(item, "created_at"));
            if (id == null || !id.isIntegralNumber() || created == null || Json.flag(item, "system")) {
                continue;
            }
            String body = Json.text(item, "body");
            sorted.put(id.longValue(), new SystemPolls.Comment(Long.toString(id.longValue()), id.longValue(),
                    user(item.get("author")), body != null ? body : "", created, PollSupport.atMentions(body)));
        }
        return List.copyOf(sorted.values());
    }

    static SystemPolls.@Nullable User user(@Nullable JsonNode node) {
        String username = Json.text(node, "username");
        if (username == null || username.isBlank()) {
            return null;
        }
        return new SystemPolls.User(Json.textOrNumber(node, "id"), username, Json.text(node, "name"));
    }
}
