package com.flowtivo.tinfirm.system.internal.github;

import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.BiFunction;
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
 * The GitHub REST API on github.com ({@code https://api.github.com}) or GitHub Enterprise Server
 * ({@code https://<host>/api/v3}) for the tools (the package 3 contract, §5.4), a client for one call:
 * {@code Authorization: Bearer} for a personal access token and an OAuth access token alike,
 * {@code Accept: application/vnd.github+json} and {@code X-GitHub-Api-Version: 2022-11-28}. Subjects are
 * {@code owner/repo#number}; Markdown stays as it is. An exhausted rate limit (a {@code 403} or {@code 429} with
 * {@code x-ratelimit-remaining: 0} or a {@code retry-after}) is {@code RATE_LIMITED} with the time to wait; any other
 * {@code 403} is {@code FORBIDDEN}. Pull requests are its change requests.
 */
public final class GitHubTracker implements Tracker, Tracker.CodeHost {

    /** The REST API version every request asks for. */
    public static final String API_VERSION = "2022-11-28";

    /** Items per page of a list. */
    static final int PAGE_SIZE = 100;

    /** An issue or pull request key, {@code owner/repo#number}. */
    public static final Pattern KEY = Pattern.compile("^([^/#\\s]+)/([^/#\\s]+)#([0-9]{1,10})$");

    /** The owner and the name at the end of {@code repository_url}: {@code .../repos/{owner}/{repo}}. */
    private static final Pattern REPOS_URL = Pattern.compile("/repos/([^/]+)/([^/]+)/?$");

    /** The owner and the name in {@code html_url}: {@code https://{host}/{owner}/{repo}/issues/{number}}. */
    private static final Pattern HTML_URL = Pattern.compile("^https?://[^/]+/([^/]+)/([^/]+)/(?:issues|pull)/\\d+$");

    private static final Pattern LINK = Pattern.compile("<([^>]*)>\\s*;\\s*rel=\"([^\"]+)\"");

    private static final Pattern PAGE_PARAMETER = Pattern.compile("[?&]page=(\\d+)");

    private static final Pattern PULL_NUMBER = Pattern.compile("/pull/([0-9]{1,10})$");

    private final OutboundHttp http;

    private final String baseUrl;

    private final String token;

    public GitHubTracker(OutboundHttp http, String baseUrl, String token) {
        this.http = http;
        this.baseUrl = baseUrl;
        this.token = token.strip();
    }

    /** A subject of a repository, by its number. */
    record Ref(GitHubRepository repository, int number) {
    }

    /** {@code owner/repo#number}, or null. */
    static @Nullable Ref ref(String key) {
        Matcher matcher = KEY.matcher(key);
        if (!matcher.matches()) {
            return null;
        }
        long number = Long.parseLong(matcher.group(3));
        return number > Integer.MAX_VALUE || number < 1 ? null
                : new Ref(new GitHubRepository(matcher.group(1), matcher.group(2)), (int) number);
    }

    @Override
    public String name() {
        return "GitHub";
    }

    // ---- Tools -----------------------------------------------------------------------------------------------------

    /** {@code GET /repos/{owner}/{repo}/issues/{number}}, and its newest comments. */
    @Override
    public CallResult<Subject> get(String key, int comments) {
        Ref ref = ref(key);
        if (ref == null) {
            return notAKey(key);
        }
        CallResult<Subject> read = call(get(repos(ref.repository()) + "/issues/" + ref.number()), (body, headers) -> {
            Subject subject = subject(body);
            return subject != null ? CallResult.ok(subject)
                    : CallResult.failed(CallResult.Kind.INVALID, "GitHub returned no usable issue");
        });
        Subject subject = read.value();
        if (subject == null || subject.commentTotal() == 0) {
            return read;
        }
        CallResult<List<Subject.Comment>> newest = newestComments(ref, comments);
        return CallResult.ok(newest.value() != null ? subject.withComments(newest.value()) : subject);
    }

    /** {@code GET /search/issues?q=...&per_page=N}: the matching issues and pull requests, without comments. */
    @Override
    public CallResult<List<Subject>> search(String query, int limit) {
        String uri = baseUrl + "/search/issues?q=" + Json.encode(query) + "&per_page=" + limit;
        return call(get(uri), (body, headers) -> {
            JsonNode items = body.get("items");
            if (items == null || !items.isArray()) {
                return CallResult.failed(CallResult.Kind.INVALID, "GitHub answered without search results");
            }
            List<Subject> subjects = new ArrayList<>();
            for (JsonNode item : items) {
                Subject subject = subject(item);
                if (subject != null && subjects.size() < limit) {
                    subjects.add(subject);
                }
            }
            return CallResult.ok(subjects);
        });
    }

    /** {@code POST /repos/{owner}/{repo}/issues/{number}/comments}: Markdown, the label in front in bold. */
    @Override
    public CallResult<Subject.Posted> comment(String key, String markdown, @Nullable String label) {
        Ref ref = ref(key);
        if (ref == null) {
            return notAKey(key);
        }
        String body = label != null ? MarkdownLabels.withLabel(label, markdown) : markdown;
        return call(send("POST", repos(ref.repository()) + "/issues/" + ref.number() + "/comments",
                Map.of("body", body)), (response, headers) -> {
                    Long id = Json.longValue(response, "id");
                    String url = Json.text(response, "html_url");
                    return id != null ? CallResult.ok(new Subject.Posted(id, httpsUrl(url)))
                            : CallResult.failed(CallResult.Kind.INVALID, "GitHub returned no comment id");
                });
    }

    /** {@code POST /repos/{owner}/{repo}/issues/{number}/labels}: adds the labels; GitHub creates unknown ones. */
    @Override
    public CallResult<Boolean> addLabels(String key, List<String> labels) {
        Ref ref = ref(key);
        if (ref == null) {
            return notAKey(key);
        }
        return call(send("POST", repos(ref.repository()) + "/issues/" + ref.number() + "/labels",
                Map.of("labels", labels)), (response, headers) -> CallResult.ok(true));
    }

    /** {@code DELETE /repos/{owner}/{repo}/issues/{number}/labels/{name}}; false when the label was not on it. */
    @Override
    public CallResult<Boolean> removeLabel(String key, String label) {
        Ref ref = ref(key);
        if (ref == null) {
            return notAKey(key);
        }
        HttpRequest request = request(repos(ref.repository()) + "/issues/" + ref.number() + "/labels/"
                + Json.encode(label)).DELETE().build();
        CallResult<Boolean> result = call(request, (response, headers) -> CallResult.ok(true));
        return result.failure() != null && result.failure().kind() == CallResult.Kind.NOT_FOUND
                ? CallResult.ok(false) : result;
    }

    /** {@code PATCH /repos/{owner}/{repo}/issues/{number}} with {@code state: closed, state_reason: completed}. */
    @Override
    public CallResult<Boolean> close(String key) {
        Ref ref = ref(key);
        if (ref == null) {
            return notAKey(key);
        }
        return call(send("PATCH", repos(ref.repository()) + "/issues/" + ref.number(),
                Map.of("state", "closed", "state_reason", "completed")), (response, headers) -> CallResult.ok(true));
    }

    @Override
    public @Nullable String toMarkdown(@Nullable String text) {
        return text == null || text.isBlank() ? null : text;
    }

    // ---- Pull requests ---------------------------------------------------------------------------------------------

    /**
     * {@code POST /repos/{owner}/{repo}/pulls}, into the base branch or the repository's default branch
     * ({@code GET /repos/{owner}/{repo}}); on a 422 "already exists", the open pull request of the branch.
     */
    @Override
    public CallResult<ChangeRequests.Opened> open(String repositoryUrl, ChangeRequests.New request) {
        GitHubRepository repository = GitHubRepository.fromUrl(repositoryUrl).orElse(null);
        if (repository == null) {
            return notAddressable(repositoryUrl);
        }
        String base = request.base();
        if (base == null) {
            CallResult<String> defaultBranch = call(get(repos(repository)), (body, headers) -> {
                String branch = Json.text(body, "default_branch");
                return branch != null && !branch.isBlank() ? CallResult.ok(branch)
                        : CallResult.failed(CallResult.Kind.INVALID, "GitHub named no default branch of "
                                + repository);
            });
            if (defaultBranch.value() == null) {
                return defaultBranch.failedAs();
            }
            base = defaultBranch.value();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", request.title());
        body.put("head", request.head());
        body.put("base", base);
        body.put("body", request.body());
        CallResult<OutboundHttp.Response> sent = http.send(baseUrl, send("POST", repos(repository) + "/pulls", body));
        OutboundHttp.Response response = sent.value();
        if (response == null) {
            return sent.failedAs();
        }
        JsonNode parsed = Json.parse(response.body());
        if (response.status() == 201) {
            return pullRequest(parsed, true);
        }
        if (response.status() == 422 && alreadyExists(parsed)) {
            return findPull(repository, request.head());
        }
        return CallResult.failed(failure(response, parsed));
    }

    /** {@code GET /repos/{owner}/{repo}/pulls/{number}}: its state and {@code head.sha}. */
    @Override
    public CallResult<ChangeRequests.Read> state(String repositoryUrl, int number) {
        GitHubRepository repository = GitHubRepository.fromUrl(repositoryUrl).orElse(null);
        if (repository == null) {
            return notAddressable(repositoryUrl);
        }
        return call(get(repos(repository) + "/pulls/" + number), (body, headers) -> {
            ChangeRequests.State state = pullState(body);
            return state != null
                    ? CallResult.ok(new ChangeRequests.Read(state,
                            ChangeRequests.commit(Json.text(body.path("head"), "sha"))))
                    : CallResult.failed(CallResult.Kind.INVALID, "GitHub returned no usable pull request state");
        });
    }

    /** {@code GET /repos/{owner}/{repo}/pulls/{number}}: its body. */
    @Override
    public CallResult<String> body(String repositoryUrl, int number) {
        GitHubRepository repository = GitHubRepository.fromUrl(repositoryUrl).orElse(null);
        if (repository == null) {
            return notAddressable(repositoryUrl);
        }
        return call(get(repos(repository) + "/pulls/" + number), (body, headers) -> {
            String text = Json.text(body, "body");
            return CallResult.ok(text != null ? text : "");
        });
    }

    /** {@code PATCH /repos/{owner}/{repo}/pulls/{number}} with the new body. */
    @Override
    public CallResult<Boolean> updateBody(String repositoryUrl, int number, String text) {
        GitHubRepository repository = GitHubRepository.fromUrl(repositoryUrl).orElse(null);
        if (repository == null) {
            return notAddressable(repositoryUrl);
        }
        return call(send("PATCH", repos(repository) + "/pulls/" + number, Map.of("body", text)),
                (body, headers) -> CallResult.ok(true));
    }

    /** {@code GET /repos/{owner}/{repo}/pulls?state=open&head={owner}:{branch}}: the first one. */
    private CallResult<ChangeRequests.Opened> findPull(GitHubRepository repository, String head) {
        String uri = repos(repository) + "/pulls?state=open&head=" + Json.encode(repository.owner() + ":" + head);
        return call(get(uri), (list, headers) -> {
            if (list.isArray() && !list.isEmpty()) {
                return pullRequest(list.get(0), false);
            }
            return CallResult.failed(CallResult.Kind.NOT_FOUND, "No open pull request of " + head + " in "
                    + repository);
        });
    }

    // ---- Comments --------------------------------------------------------------------------------------------------

    /** A page of a list, with the links of its {@code Link} header. */
    record Page<T>(List<T> items, boolean hasNext, @Nullable Integer lastPage) {
    }

    /** {@code GET /repos/{owner}/{repo}/issues/{number}/comments?per_page=100&page=N}, oldest first. */
    private CallResult<Page<Subject.Comment>> comments(Ref ref, int page) {
        String uri = repos(ref.repository()) + "/issues/" + ref.number() + "/comments?per_page=" + PAGE_SIZE + "&page="
                + page;
        return call(get(uri), (body, headers) -> {
            if (!body.isArray()) {
                return CallResult.failed(CallResult.Kind.INVALID, "GitHub answered without a list of comments");
            }
            return CallResult.ok(page(comments(body), headers));
        });
    }

    /**
     * The newest {@code max} comments of an issue, oldest first: the first page of the list, then its last page and,
     * when that has fewer than {@code max}, the page before it. At most three requests, as {@code max} is at most a
     * page.
     */
    private CallResult<List<Subject.Comment>> newestComments(Ref ref, int max) {
        CallResult<Page<Subject.Comment>> first = comments(ref, 1);
        Page<Subject.Comment> page = first.value();
        if (page == null) {
            return first.failedAs();
        }
        Map<Long, Subject.Comment> all = new TreeMap<>();
        page.items().forEach(comment -> all.put(comment.id(), comment));
        Integer last = page.lastPage();
        if (last != null && last > 1) {
            for (int read = last; read >= Math.max(2, last - 1); read--) {
                CallResult<Page<Subject.Comment>> more = comments(ref, read);
                Page<Subject.Comment> morePage = more.value();
                if (morePage == null) {
                    return more.failedAs();
                }
                morePage.items().forEach(comment -> all.put(comment.id(), comment));
                if (morePage.items().size() >= max) {
                    break;
                }
            }
        }
        List<Subject.Comment> sorted = List.copyOf(all.values());
        return CallResult.ok(sorted.subList(Math.max(0, sorted.size() - max), sorted.size()));
    }

    // ---- HTTP ------------------------------------------------------------------------------------------------------

    /** Sends the request; any 2xx is success. */
    private <T> CallResult<T> call(HttpRequest request, BiFunction<JsonNode, HttpHeaders, CallResult<T>> handler) {
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
            return CallResult.failed(CallResult.Kind.INVALID, "GitHub answered " + response.status()
                    + " without JSON");
        }
        return handler.apply(parsed, response.headers());
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
        return http.request(uri)
            .header("Accept", "application/vnd.github+json")
            .header("Authorization", "Bearer " + token)
            .header("X-GitHub-Api-Version", API_VERSION);
    }

    private String repos(GitHubRepository repository) {
        return baseUrl + "/repos/" + Json.encode(repository.owner()) + "/" + Json.encode(repository.name());
    }

    private static <T> CallResult<T> notAKey(String key) {
        return CallResult.failed(CallResult.Kind.INVALID, "`" + key + "` is not a GitHub key owner/repo#number");
    }

    private static <T> CallResult<T> notAddressable(String url) {
        return CallResult.failed(CallResult.Kind.INVALID, "The URL " + url + " does not name a GitHub repository "
                + "owner/repo");
    }

    // ---- Parsing (package-private for tests) -----------------------------------------------------------------------

    /** GitHub's rate limit: a 403 or 429 with {@code x-ratelimit-remaining: 0} or a {@code retry-after}. */
    static boolean rateLimited(int status, HttpHeaders headers) {
        return (status == 403 || status == 429) && (headers.firstValue("x-ratelimit-remaining")
            .filter("0"::equals)
            .isPresent() || headers.firstValue("retry-after").isPresent());
    }

    /** Why a call failed: the status, whether the rate limit is the reason and when it resets, GitHub's message. */
    static CallResult.Failure failure(OutboundHttp.Response response, @Nullable JsonNode body) {
        boolean rateLimited = rateLimited(response.status(), response.headers());
        Duration retryAfter = null;
        if (rateLimited) {
            String after = response.header("retry-after").orElse(null);
            String reset = response.header("x-ratelimit-reset").orElse(null);
            if (after != null && after.matches("\\d{1,9}")) {
                retryAfter = Duration.ofSeconds(Long.parseLong(after));
            }
            else if (reset != null && reset.matches("\\d{1,12}")) {
                retryAfter = Duration.between(Instant.now(), Instant.ofEpochSecond(Long.parseLong(reset)));
            }
        }
        List<String> messages = new ArrayList<>();
        if (body != null) {
            String message = Json.text(body, "message");
            if (message != null) {
                messages.add(message);
            }
            JsonNode errors = body.get("errors");
            if (errors != null && errors.isArray()) {
                errors.forEach(error -> {
                    String detail = error.isString() ? error.stringValue() : Json.text(error, "message");
                    if (detail != null) {
                        messages.add(detail);
                    }
                });
            }
        }
        return Failures.of("GitHub", response, messages, rateLimited, retryAfter);
    }

    /**
     * An issue, or a pull request GitHub lists among the issues: the key {@code owner/repo#number} with the names
     * GitHub reports for the repository, its page, title, body (Markdown), {@code type.name} (the organisation's
     * issue type), whether its {@code state} is {@code closed}, labels, assignees, the author, times and the number
     * of comments; the comments are read separately. Null when a field it needs is missing.
     */
    static @Nullable Subject subject(JsonNode item) {
        JsonNode number = item.get("number");
        Instant updated = Json.time(Json.text(item, "updated_at"));
        String htmlUrl = Json.text(item, "html_url");
        GitHubRepository repository = repository(Json.text(item, "repository_url"), htmlUrl);
        if (number == null || !number.isIntegralNumber() || updated == null || repository == null) {
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
        List<Subject.User> assignees = new ArrayList<>();
        JsonNode assigneeNodes = item.get("assignees");
        if (assigneeNodes != null && assigneeNodes.isArray()) {
            assigneeNodes.forEach(assignee -> {
                String login = Json.text(assignee, "login");
                if (login != null && !login.isBlank()) {
                    assignees.add(new Subject.User(login, null));
                }
            });
        }
        Instant created = Json.time(Json.text(item, "created_at"));
        String author = Json.text(item.get("user"), "login");
        return new Subject(item.has("pull_request"), key, httpsUrl(htmlUrl),
                title != null && !title.isBlank() ? title : key, Json.text(item, "body"), null,
                Json.text(item.get("type"), "name"), "closed".equals(Json.text(item, "state")), assignees, labels,
                created != null ? created : updated, updated, List.of(), Json.integer(item, "comments", 0),
                author != null && !author.isBlank() ? new Subject.User(author, null) : null);
    }

    /** The repository of an issue: from {@code repository_url}, else from {@code html_url}; null from neither. */
    static @Nullable GitHubRepository repository(@Nullable String repositoryUrl, @Nullable String htmlUrl) {
        if (repositoryUrl != null) {
            Matcher matcher = REPOS_URL.matcher(repositoryUrl);
            if (matcher.find()) {
                return new GitHubRepository(matcher.group(1), matcher.group(2));
            }
        }
        if (htmlUrl != null) {
            Matcher matcher = HTML_URL.matcher(htmlUrl);
            if (matcher.matches()) {
                return new GitHubRepository(matcher.group(1), matcher.group(2));
            }
        }
        return null;
    }

    /** The comments, oldest first; comments without an id or a time are left out. The author is the login. */
    static List<Subject.Comment> comments(JsonNode items) {
        List<Subject.Comment> comments = new ArrayList<>();
        for (JsonNode item : items) {
            JsonNode id = item.get("id");
            Instant created = Json.time(Json.text(item, "created_at"));
            if (id == null || !id.isIntegralNumber() || created == null) {
                continue;
            }
            String login = Json.text(item.get("user"), "login");
            String body = Json.text(item, "body");
            comments.add(new Subject.Comment(id.longValue(), login != null && !login.isBlank()
                    ? new Subject.User(login, null) : null, body != null ? body : "", created));
        }
        comments.sort((a, b) -> Long.compare(a.id(), b.id()));
        return comments;
    }

    /** The page with the links of its {@code Link} header: is there a next page, which is the last. */
    static <T> Page<T> page(List<T> items, HttpHeaders headers) {
        boolean hasNext = false;
        Integer last = null;
        for (String link : headers.allValues("link")) {
            Matcher matcher = LINK.matcher(link);
            while (matcher.find()) {
                if ("next".equals(matcher.group(2))) {
                    hasNext = true;
                }
                else if ("last".equals(matcher.group(2))) {
                    Matcher page = PAGE_PARAMETER.matcher(matcher.group(1));
                    if (page.find() && page.group(1).length() <= 6) {
                        last = Integer.parseInt(page.group(1));
                    }
                }
            }
        }
        return new Page<>(items, hasNext, last);
    }

    /**
     * MERGED when {@code merged} is true or {@code merged_at} is set, CLOSED for {@code state} "closed", DRAFT for
     * an open one with {@code draft} true, else OPEN; null without a known {@code state}.
     */
    static ChangeRequests.@Nullable State pullState(@Nullable JsonNode pullRequest) {
        if (pullRequest == null) {
            return null;
        }
        if (Json.flag(pullRequest, "merged") || Json.text(pullRequest, "merged_at") != null) {
            return ChangeRequests.State.MERGED;
        }
        String state = Json.text(pullRequest, "state");
        if ("closed".equals(state)) {
            return ChangeRequests.State.CLOSED;
        }
        if (!"open".equals(state)) {
            return null;
        }
        return Json.flag(pullRequest, "draft") ? ChangeRequests.State.DRAFT : ChangeRequests.State.OPEN;
    }

    /**
     * The URL is shown as a link: only a plain https URL of bounded length is accepted. The number comes from
     * {@code number}, else from the URL's {@code /pull/<number>}.
     */
    static CallResult<ChangeRequests.Opened> pullRequest(@Nullable JsonNode pullRequest, boolean created) {
        String url = httpsUrl(Json.text(pullRequest, "html_url"));
        if (url == null) {
            return CallResult.failed(CallResult.Kind.INVALID, "GitHub returned no usable pull request URL");
        }
        JsonNode number = pullRequest != null ? pullRequest.get("number") : null;
        if (number != null && number.isIntegralNumber() && number.longValue() > 0
                && number.longValue() <= Integer.MAX_VALUE) {
            return CallResult.ok(new ChangeRequests.Opened(url, (int) number.longValue(), created,
                    pullState(pullRequest)));
        }
        Matcher matcher = PULL_NUMBER.matcher(url);
        if (matcher.find()) {
            long parsed = Long.parseLong(matcher.group(1));
            if (parsed > 0 && parsed <= Integer.MAX_VALUE) {
                return CallResult.ok(new ChangeRequests.Opened(url, (int) parsed, created, pullState(pullRequest)));
            }
        }
        return CallResult.failed(CallResult.Kind.INVALID, "GitHub returned no usable pull request number");
    }

    /** A plain https URL of at most 500 characters, else null. */
    static @Nullable String httpsUrl(@Nullable String url) {
        return url != null && url.startsWith("https://") && url.length() <= 500
                && url.chars().noneMatch(Character::isWhitespace) ? url : null;
    }

    private static boolean alreadyExists(@Nullable JsonNode body) {
        if (body == null) {
            return false;
        }
        JsonNode errors = body.get("errors");
        if (errors != null && errors.isArray()) {
            for (JsonNode error : errors) {
                String message = Json.text(error, "message");
                if (message != null && message.toLowerCase(Locale.ROOT).contains("already exists")) {
                    return true;
                }
            }
        }
        String message = Json.text(body, "message");
        return message != null && message.toLowerCase(Locale.ROOT).contains("already exists");
    }
}
