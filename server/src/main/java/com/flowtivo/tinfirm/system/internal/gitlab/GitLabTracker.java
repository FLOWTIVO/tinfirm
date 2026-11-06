package com.flowtivo.tinfirm.system.internal.gitlab;

import java.net.http.HttpRequest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

import com.flowtivo.tinfirm.system.CallResult;
import com.flowtivo.tinfirm.system.ChangeRequests;
import com.flowtivo.tinfirm.system.internal.http.Json;
import com.flowtivo.tinfirm.system.internal.http.OutboundHttp;
import com.flowtivo.tinfirm.system.internal.tools.Failures;
import com.flowtivo.tinfirm.system.internal.tools.MarkdownLabels;
import com.flowtivo.tinfirm.system.internal.tools.Subject;
import com.flowtivo.tinfirm.system.internal.tools.Tracker;

import tools.jackson.databind.JsonNode;

/**
 * The GitLab REST API v4 of the instance at the system's base URL (gitlab.com or self-managed) for the tools (the
 * package 3 contract, §5.4), a client for one call: a personal access token in the {@code PRIVATE-TOKEN} header, an
 * OAuth access token as {@code Authorization: Bearer}, which GitLab takes for OAuth tokens only. Subjects are
 * {@code group/project#iid} (an issue) and {@code group/project!iid} (a merge request); Markdown stays as it is; the
 * system's own notes are not comments. Merge requests are its change requests.
 */
public final class GitLabTracker implements Tracker, Tracker.CodeHost {

    /** An issue or merge request key, {@code path#iid} or {@code path!iid}. */
    public static final Pattern KEY = Pattern.compile("^([^#!\\s]+)([#!])([0-9]{1,10})$");

    /** Notes read per subject, at most: a page. */
    static final int PAGE_SIZE = 100;

    /** The project path in a subject's {@code references.full}: {@code group/project#12}, {@code group/project!12}. */
    private static final Pattern REFERENCE = Pattern.compile("^([^#!\\s]+)[#!][0-9]{1,10}$");

    /** The project path in a subject's {@code web_url}: {@code https://host/group/project/-/issues/12}. */
    private static final Pattern WEB_URL = Pattern.compile("^https?://[^/]+/(.+?)/-/(?:issues|merge_requests)/\\d+$");

    private static final Pattern MERGE_REQUEST_NUMBER = Pattern.compile("/merge_requests/([0-9]{1,10})$");

    private final OutboundHttp http;

    private final String baseUrl;

    private final String token;

    private final boolean oauth;

    public GitLabTracker(OutboundHttp http, String baseUrl, String token, boolean oauth) {
        this.http = http;
        this.baseUrl = baseUrl;
        this.token = token.strip();
        this.oauth = oauth;
    }

    /** A subject of a project, by its iid. */
    record Ref(GitLabProject project, boolean mergeRequest, long iid) {
    }

    /** {@code path#iid} or {@code path!iid}, or null. */
    static @Nullable Ref ref(String key) {
        Matcher matcher = KEY.matcher(key);
        if (!matcher.matches()) {
            return null;
        }
        GitLabProject project = GitLabProject.parse(matcher.group(1)).orElse(null);
        long iid = Long.parseLong(matcher.group(3));
        return project == null || iid < 1 ? null : new Ref(project, "!".equals(matcher.group(2)), iid);
    }

    @Override
    public String name() {
        return "GitLab";
    }

    // ---- Tools -----------------------------------------------------------------------------------------------------

    /** {@code GET /projects/:id/issues/:iid} or {@code …/merge_requests/:iid}, and its newest notes of people. */
    @Override
    public CallResult<Subject> get(String key, int comments) {
        Ref ref = ref(key);
        if (ref == null) {
            return notAKey(key);
        }
        CallResult<Subject> read = call(get(subjectUrl(ref)), body -> {
            Subject subject = subject(body, ref.project(), ref.mergeRequest());
            return subject != null ? CallResult.ok(subject)
                    : CallResult.failed(CallResult.Kind.INVALID, "GitLab returned no usable "
                            + (ref.mergeRequest() ? "merge request" : "issue"));
        });
        Subject subject = read.value();
        if (subject == null || subject.commentTotal() == 0) {
            return read;
        }
        String uri = subjectUrl(ref) + "/notes?sort=desc&order_by=created_at&per_page=" + Math.clamp(comments, 1,
                PAGE_SIZE);
        CallResult<List<Subject.Comment>> notes = call(get(uri), body -> body.isArray() ? CallResult.ok(notes(body))
                : CallResult.failed(CallResult.Kind.INVALID, "GitLab answered without a list of notes"));
        return CallResult.ok(notes.value() != null ? subject.withComments(notes.value()) : subject);
    }

    /**
     * {@code GET /issues?scope=all&search=...} and the same of merge requests: the newest matches of both, at most
     * {@code limit}, without notes.
     */
    @Override
    public CallResult<List<Subject>> search(String query, int limit) {
        List<Subject> found = new ArrayList<>();
        for (boolean mergeRequests : List.of(false, true)) {
            String uri = api() + (mergeRequests ? "/merge_requests" : "/issues") + "?scope=all&search="
                    + Json.encode(query) + "&per_page=" + limit;
            CallResult<List<Subject>> read = call(get(uri), body -> {
                if (!body.isArray()) {
                    return CallResult.failed(CallResult.Kind.INVALID, "GitLab answered without a list");
                }
                List<Subject> subjects = new ArrayList<>();
                for (JsonNode item : body) {
                    Subject subject = subject(item, null, mergeRequests);
                    if (subject != null) {
                        subjects.add(subject);
                    }
                }
                return CallResult.ok(subjects);
            });
            if (read.value() == null) {
                return read;
            }
            found.addAll(read.value());
        }
        found.sort((a, b) -> b.updated().compareTo(a.updated()));
        return CallResult.ok(List.copyOf(found.subList(0, Math.min(found.size(), limit))));
    }

    /** {@code POST …/notes}: Markdown, the label in front in bold. */
    @Override
    public CallResult<Subject.Posted> comment(String key, String markdown, @Nullable String label) {
        Ref ref = ref(key);
        if (ref == null) {
            return notAKey(key);
        }
        String body = label != null ? MarkdownLabels.withLabel(label, markdown) : markdown;
        return call(send("POST", subjectUrl(ref) + "/notes", Map.of("body", body)), response -> {
            Long id = Json.longValue(response, "id");
            return id != null ? CallResult.ok(new Subject.Posted(id, null))
                    : CallResult.failed(CallResult.Kind.INVALID, "GitLab returned no note id");
        });
    }

    /** {@code PUT …} with {@code add_labels}. */
    @Override
    public CallResult<Boolean> addLabels(String key, List<String> labels) {
        return labels(key, Map.of("add_labels", String.join(",", labels)));
    }

    /** {@code PUT …} with {@code remove_labels}; GitLab does not say whether the label was on it. */
    @Override
    public CallResult<Boolean> removeLabel(String key, String label) {
        return labels(key, Map.of("remove_labels", label));
    }

    private CallResult<Boolean> labels(String key, Map<String, Object> change) {
        Ref ref = ref(key);
        if (ref == null) {
            return notAKey(key);
        }
        return call(send("PUT", subjectUrl(ref), change), response -> CallResult.ok(true));
    }

    /** {@code PUT …} with {@code state_event: close}. */
    @Override
    public CallResult<Boolean> close(String key) {
        Ref ref = ref(key);
        if (ref == null) {
            return notAKey(key);
        }
        return call(send("PUT", subjectUrl(ref), Map.of("state_event", "close")), response -> CallResult.ok(true));
    }

    @Override
    public @Nullable String toMarkdown(@Nullable String text) {
        return text == null || text.isBlank() ? null : text;
    }

    // ---- Merge requests --------------------------------------------------------------------------------------------

    /**
     * {@code POST /projects/:id/merge_requests} into the base branch or the project's default branch
     * ({@code GET /projects/:id}), keeping the source branch; on a 409 (or 422) "already exists", the merge request of
     * the source branch.
     */
    @Override
    public CallResult<ChangeRequests.Opened> open(String repositoryUrl, ChangeRequests.New request) {
        GitLabProject project = GitLabProject.fromUrl(repositoryUrl).orElse(null);
        if (project == null) {
            return notAddressable(repositoryUrl);
        }
        String base = request.base();
        if (base == null) {
            CallResult<String> defaultBranch = call(get(api() + "/projects/" + project.id()), body -> {
                String branch = Json.text(body, "default_branch");
                return branch != null && !branch.isBlank() ? CallResult.ok(branch)
                        : CallResult.failed(CallResult.Kind.INVALID, "GitLab named no default branch of " + project);
            });
            if (defaultBranch.value() == null) {
                return defaultBranch.failedAs();
            }
            base = defaultBranch.value();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("source_branch", request.head());
        body.put("target_branch", base);
        body.put("title", request.title());
        body.put("description", request.body());
        body.put("remove_source_branch", false);
        CallResult<OutboundHttp.Response> sent = http.send(baseUrl, send("POST", mergeRequests(project), body));
        OutboundHttp.Response response = sent.value();
        if (response == null) {
            return sent.failedAs();
        }
        JsonNode parsed = Json.parse(response.body());
        if (response.status() == 201) {
            return mergeRequest(parsed, true);
        }
        if ((response.status() == 409 || response.status() == 422) && alreadyExists(parsed)) {
            return findMergeRequest(project, request.head());
        }
        return CallResult.failed(failure(response, parsed));
    }

    /** {@code GET /projects/:id/merge_requests/:iid}: its state and {@code sha}. */
    @Override
    public CallResult<ChangeRequests.Read> state(String repositoryUrl, int number) {
        GitLabProject project = GitLabProject.fromUrl(repositoryUrl).orElse(null);
        if (project == null) {
            return notAddressable(repositoryUrl);
        }
        return call(get(mergeRequests(project) + "/" + number), body -> {
            ChangeRequests.State state = mergeRequestState(body);
            return state != null
                    ? CallResult.ok(new ChangeRequests.Read(state, ChangeRequests.commit(Json.text(body, "sha"))))
                    : CallResult.failed(CallResult.Kind.INVALID, "GitLab returned no usable merge request state");
        });
    }

    /** {@code GET /projects/:id/merge_requests/:iid}: its description. */
    @Override
    public CallResult<String> body(String repositoryUrl, int number) {
        GitLabProject project = GitLabProject.fromUrl(repositoryUrl).orElse(null);
        if (project == null) {
            return notAddressable(repositoryUrl);
        }
        return call(get(mergeRequests(project) + "/" + number), body -> {
            String text = Json.text(body, "description");
            return CallResult.ok(text != null ? text : "");
        });
    }

    /** {@code PUT /projects/:id/merge_requests/:iid} with the new description. */
    @Override
    public CallResult<Boolean> updateBody(String repositoryUrl, int number, String text) {
        GitLabProject project = GitLabProject.fromUrl(repositoryUrl).orElse(null);
        if (project == null) {
            return notAddressable(repositoryUrl);
        }
        return call(send("PUT", mergeRequests(project) + "/" + number, Map.of("description", text)),
                body -> CallResult.ok(true));
    }

    /** {@code GET /projects/:id/merge_requests?source_branch={branch}&state=all}: the open one, else the newest. */
    private CallResult<ChangeRequests.Opened> findMergeRequest(GitLabProject project, String head) {
        String uri = mergeRequests(project) + "?source_branch=" + Json.encode(head) + "&state=all";
        return call(get(uri), list -> {
            if (list.isArray() && !list.isEmpty()) {
                for (JsonNode candidate : list) {
                    if ("opened".equals(Json.text(candidate, "state"))) {
                        return mergeRequest(candidate, false);
                    }
                }
                return mergeRequest(list.get(0), false);
            }
            return CallResult.failed(CallResult.Kind.NOT_FOUND, "No merge request of " + head + " in " + project);
        });
    }

    // ---- HTTP ------------------------------------------------------------------------------------------------------

    private <T> CallResult<T> call(HttpRequest request, Function<JsonNode, CallResult<T>> handler) {
        CallResult<OutboundHttp.Response> sent = http.send(baseUrl, request);
        OutboundHttp.Response response = sent.value();
        if (response == null) {
            return sent.failedAs();
        }
        JsonNode parsed = Json.parse(response.body());
        if (!response.success()) {
            return CallResult.failed(failure(response, parsed));
        }
        if (parsed == null) {
            return CallResult.failed(CallResult.Kind.INVALID, "GitLab answered " + response.status()
                    + " without JSON");
        }
        return handler.apply(parsed);
    }

    private HttpRequest get(String uri) {
        return request(uri).GET().build();
    }

    private HttpRequest send(String method, String uri, Object body) {
        return request(uri).method(method, HttpRequest.BodyPublishers.ofString(Json.write(body)))
            .header("Content-Type", "application/json")
            .build();
    }

    private HttpRequest.Builder request(String uri) {
        HttpRequest.Builder request = http.request(uri).header("Accept", "application/json");
        return oauth ? request.header("Authorization", "Bearer " + token) : request.header("PRIVATE-TOKEN", token);
    }

    /** The REST API v4 of the instance: the base URL, or its {@code /api/v4}. */
    private String api() {
        return baseUrl.endsWith("/api/v4") ? baseUrl : baseUrl + "/api/v4";
    }

    private String mergeRequests(GitLabProject project) {
        return api() + "/projects/" + project.id() + "/merge_requests";
    }

    private String subjectUrl(Ref ref) {
        return api() + "/projects/" + ref.project().id() + (ref.mergeRequest() ? "/merge_requests/" : "/issues/")
                + ref.iid();
    }

    private static <T> CallResult<T> notAKey(String key) {
        return CallResult.failed(CallResult.Kind.INVALID, "`" + key + "` is not a GitLab key group/project#iid or "
                + "group/project!iid");
    }

    private static <T> CallResult<T> notAddressable(String url) {
        return CallResult.failed(CallResult.Kind.INVALID, "The URL " + url + " does not name a GitLab project");
    }

    // ---- Parsing (package-private for tests) -----------------------------------------------------------------------

    /** Why a call failed: the status and GitLab's message. */
    static CallResult.Failure failure(OutboundHttp.Response response, @Nullable JsonNode body) {
        String message = message(body);
        return Failures.of("GitLab", response, message != null ? List.of(message) : List.of(), false, null);
    }

    /**
     * An issue or merge request: the key {@code path#iid} or {@code path!iid}, {@code web_url}, title, description
     * (Markdown), {@code issue_type} as the type, closed when its {@code state} is not {@code opened}, labels,
     * assignees by username, the author, timestamps and {@code user_notes_count}. Without a known project (a search),
     * the project path comes from {@code references.full}, else from {@code web_url}. Null when a field it needs is
     * missing.
     */
    static @Nullable Subject subject(JsonNode item, @Nullable GitLabProject known, boolean mergeRequest) {
        JsonNode iid = item.get("iid");
        Instant updated = Json.time(Json.text(item, "updated_at"));
        GitLabProject project = known != null ? known : projectOf(item);
        if (iid == null || !iid.isIntegralNumber() || updated == null || project == null) {
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
        List<Subject.User> assignees = new ArrayList<>();
        JsonNode assigneeNodes = item.get("assignees");
        if (assigneeNodes != null && assigneeNodes.isArray()) {
            assigneeNodes.forEach(assignee -> {
                Subject.User user = user(assignee);
                if (user != null) {
                    assignees.add(user);
                }
            });
        }
        Instant created = Json.time(Json.text(item, "created_at"));
        String state = Json.text(item, "state");
        String url = Json.text(item, "web_url");
        return new Subject(mergeRequest, key, url != null && url.startsWith("https://") && url.length() <= 500 ? url
                : null, title != null && !title.isBlank() ? title : key, Json.text(item, "description"), null,
                mergeRequest ? null : Json.text(item, "issue_type"), state != null && !"opened".equals(state),
                assignees, labels, created != null ? created : updated, updated, List.of(),
                Json.integer(item, "user_notes_count", 0), user(item.get("author")));
    }

    /** The project of a listed subject: from {@code references.full}, else from {@code web_url}; null from neither. */
    static @Nullable GitLabProject projectOf(JsonNode item) {
        String reference = Json.text(item.get("references"), "full");
        if (reference != null) {
            Matcher matcher = REFERENCE.matcher(reference);
            if (matcher.matches()) {
                return GitLabProject.parse(matcher.group(1)).orElse(null);
            }
        }
        String url = Json.text(item, "web_url");
        if (url != null) {
            Matcher matcher = WEB_URL.matcher(url);
            if (matcher.matches()) {
                return GitLabProject.parse(matcher.group(1)).orElse(null);
            }
        }
        return null;
    }

    /** The notes of people, oldest first; the system's notes and those without an id or a time are left out. */
    static List<Subject.Comment> notes(JsonNode items) {
        List<Subject.Comment> notes = new ArrayList<>();
        for (JsonNode item : items) {
            JsonNode id = item.get("id");
            Instant created = Json.time(Json.text(item, "created_at"));
            if (id == null || !id.isIntegralNumber() || created == null || Json.flag(item, "system")) {
                continue;
            }
            String body = Json.text(item, "body");
            notes.add(new Subject.Comment(id.longValue(), user(item.get("author")), body != null ? body : "",
                    created));
        }
        notes.sort((a, b) -> Long.compare(a.id(), b.id()));
        return notes;
    }

    static Subject.@Nullable User user(@Nullable JsonNode node) {
        String username = Json.text(node, "username");
        return username != null && !username.isBlank() ? new Subject.User(username, Json.text(node, "name")) : null;
    }

    /**
     * {@code merged} is MERGED; {@code closed} and {@code locked} are CLOSED; {@code opened} is DRAFT when
     * {@code draft} (or, before GitLab 14, {@code work_in_progress}) is true, else OPEN; null for any other state.
     */
    static ChangeRequests.@Nullable State mergeRequestState(@Nullable JsonNode mergeRequest) {
        String state = Json.text(mergeRequest, "state");
        if (state == null) {
            return null;
        }
        return switch (state) {
            case "merged" -> ChangeRequests.State.MERGED;
            case "closed", "locked" -> ChangeRequests.State.CLOSED;
            case "opened" -> Json.flag(mergeRequest, "draft") || Json.flag(mergeRequest, "work_in_progress")
                    ? ChangeRequests.State.DRAFT : ChangeRequests.State.OPEN;
            default -> null;
        };
    }

    /**
     * The URL is shown as a link: only a plain https URL of bounded length is accepted. The number is the
     * project-scoped {@code iid}, else the URL's {@code /merge_requests/<iid>}.
     */
    static CallResult<ChangeRequests.Opened> mergeRequest(@Nullable JsonNode mergeRequest, boolean created) {
        String url = Json.text(mergeRequest, "web_url");
        if (url == null || !url.startsWith("https://") || url.length() > 500
                || url.chars().anyMatch(Character::isWhitespace)) {
            return CallResult.failed(CallResult.Kind.INVALID, "GitLab returned no usable merge request URL");
        }
        JsonNode iid = mergeRequest != null ? mergeRequest.get("iid") : null;
        if (iid != null && iid.isIntegralNumber() && iid.longValue() > 0 && iid.longValue() <= Integer.MAX_VALUE) {
            return CallResult.ok(new ChangeRequests.Opened(url, (int) iid.longValue(), created,
                    mergeRequestState(mergeRequest)));
        }
        Matcher matcher = MERGE_REQUEST_NUMBER.matcher(url);
        if (matcher.find()) {
            long parsed = Long.parseLong(matcher.group(1));
            if (parsed > 0 && parsed <= Integer.MAX_VALUE) {
                return CallResult.ok(new ChangeRequests.Opened(url, (int) parsed, created,
                        mergeRequestState(mergeRequest)));
            }
        }
        return CallResult.failed(CallResult.Kind.INVALID, "GitLab returned no usable merge request number");
    }

    /** GitLab answers a second merge request of a source branch with a message that says one already exists. */
    private static boolean alreadyExists(@Nullable JsonNode body) {
        String message = message(body);
        return message != null && message.toLowerCase(Locale.ROOT).contains("already exists");
    }

    /**
     * The text of GitLab's {@code message} (a string, a list of strings, or field errors such as
     * {@code {"title": ["can't be blank"]}}), else of its {@code error}; null without either.
     */
    static @Nullable String message(@Nullable JsonNode body) {
        if (body == null) {
            return null;
        }
        JsonNode message = body.get("message");
        if (message != null && message.isString()) {
            return message.stringValue();
        }
        if (message != null && message.isArray()) {
            List<String> parts = strings(message);
            return parts.isEmpty() ? null : String.join("; ", parts);
        }
        if (message != null && message.isObject()) {
            List<String> parts = new ArrayList<>();
            for (Map.Entry<String, JsonNode> field : message.properties()) {
                List<String> values = strings(field.getValue());
                parts.add(values.isEmpty() ? field.getKey() : field.getKey() + " " + String.join(", ", values));
            }
            return parts.isEmpty() ? null : String.join("; ", parts);
        }
        return Json.text(body, "error");
    }

    private static List<String> strings(JsonNode node) {
        List<String> strings = new ArrayList<>();
        if (node.isString()) {
            strings.add(node.stringValue());
        }
        else if (node.isArray()) {
            for (JsonNode element : node) {
                if (element.isString()) {
                    strings.add(element.stringValue());
                }
            }
        }
        return strings;
    }
}
