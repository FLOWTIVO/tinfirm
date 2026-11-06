package com.flowtivo.tinfirm.system;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * Reads a tracker's subjects for the trigger module's polls (the package 4 contract, §5.4): a listing page of the
 * subjects changed since a bound, one subject with its newest comments, and a search for the editor's "run now". Jira
 * Data Center and Cloud ({@code project in (…) AND updated >= …}, Cloud's {@code search/jql} by cursor), GitHub (one
 * listing per repository, or {@code /issues} without a scope, conditional by its ETag) and GitLab (a project, else a
 * group). Every read is made with the one credential the caller revealed for its account (T8: a poll sees only what its
 * account sees), outside any transaction (CONVENTIONS §10), through the outbound client and its guard. Failures are
 * values, never exceptions, and never hold a token: {@code UNAUTHORIZED} for a {@code 401}, {@code FORBIDDEN} or
 * {@code NOT_FOUND} for a scope the account cannot read, {@code RATE_LIMITED} with the wait the system named,
 * {@code UNAVAILABLE} for {@code 5xx}, a timeout or no connection.
 */
public interface SystemPolls {

    /** Subjects per page of a listing. */
    int PAGE_SIZE = 50;

    /** The newest comments read with a subject, at most. */
    int COMMENTS = 50;

    /** The poll interval of a system without one in its settings ({@code pollIntervalSeconds}). */
    Duration DEFAULT_INTERVAL = Duration.ofSeconds(60);

    /**
     * Opens a session of reads of the system with the credential, outside any transaction: the system's row is read in
     * a short transaction of its own. {@code INVALID} for a system that is gone or is no tracker, or whose kind or
     * base URL is not the one the credential was revealed for ({@link CallCredential#endpoint()}, ADR-0079: nothing
     * is sent); {@code OTHER} for a system an admin paused (the package 6 contract, §5.4: its accounts are used for
     * nothing).
     *
     * @throws IllegalStateException inside a database transaction
     */
    CallResult<Session> open(UUID companyId, UUID systemId, CallCredential credential);

    /**
     * The system's poll interval: its settings' {@code pollIntervalSeconds} (the Systems API's), 30 to 3600, else
     * {@link #DEFAULT_INTERVAL}. In the caller's transaction and tenant context.
     */
    Duration interval(UUID companyId, UUID systemId);

    /**
     * The listings a poll of the scopes reads, each once ignoring case: Jira one search over the projects; GitHub one
     * per repository; GitLab the issues and the merge requests of each path. No scope: everything the account sees;
     * {@code assignedOnly}: only what is assigned to the account ({@code assignee = currentUser()},
     * {@code filter=assigned}, {@code scope=assigned_to_me}), for a poll whose triggers are all unscoped and fire only
     * on {@code ASSIGNED}.
     */
    static List<Listing> listings(SystemKind kind, Collection<String> scopes, boolean assignedOnly) {
        List<String> distinct = scopes.stream()
            .filter(Objects::nonNull)
            .map(String::strip)
            .filter(scope -> !scope.isEmpty())
            .collect(java.util.stream.Collectors.toMap(scope -> scope.toLowerCase(Locale.ROOT), scope -> scope,
                    (first, second) -> first, java.util.LinkedHashMap::new))
            .values()
            .stream()
            .sorted()
            .toList();
        return switch (kind) {
            case JIRA_DC, JIRA_CLOUD -> List.of(new Listing(Listing.Kind.JIRA, assignedOnly && distinct.isEmpty()
                    ? List.of() : distinct, assignedOnly && distinct.isEmpty(), false));
            case GITHUB -> distinct.isEmpty() ? List.of(new Listing(Listing.Kind.GITHUB, List.of(), assignedOnly, false))
                    : distinct.stream().map(scope -> new Listing(Listing.Kind.GITHUB, List.of(scope), false, false))
                        .toList();
            case GITLAB -> {
                List<Listing> listings = new java.util.ArrayList<>();
                if (distinct.isEmpty()) {
                    listings.add(new Listing(Listing.Kind.GITLAB, List.of(), assignedOnly, false));
                    listings.add(new Listing(Listing.Kind.GITLAB, List.of(), assignedOnly, true));
                }
                for (String scope : distinct) {
                    listings.add(new Listing(Listing.Kind.GITLAB, List.of(scope), false, false));
                    listings.add(new Listing(Listing.Kind.GITLAB, List.of(scope), false, true));
                }
                yield List.copyOf(listings);
            }
            default -> List.of();
        };
    }

    /**
     * Whether a trigger's scope covers the subject: its Jira project, its repository, or its GitLab project or a group
     * above it, ignoring case. A null scope covers everything.
     */
    static boolean inScope(Subject subject, @Nullable String scope) {
        if (scope == null || scope.isBlank()) {
            return true;
        }
        String wanted = scope.strip().toLowerCase(Locale.ROOT);
        String have = subject.scope().toLowerCase(Locale.ROOT);
        return have.equals(wanted) || have.startsWith(wanted + "/");
    }

    /**
     * One listing of a poll.
     *
     * @param scopes        Jira: the project keys (none: every project); GitHub and GitLab: one repository or path, or
     *                      none for everything the account sees
     * @param assignedOnly  only the subjects assigned to the account
     * @param changeRequests GitLab: the merge requests of the path, else its issues
     */
    record Listing(Kind kind, List<String> scopes, boolean assignedOnly, boolean changeRequests) {

        public Listing {
            scopes = List.copyOf(scopes);
        }

        /** A stable name of the listing, e.g. for its ETag. */
        public String id() {
            return kind.name().toLowerCase(Locale.ROOT) + (changeRequests ? ":mr" : "") + (assignedOnly ? ":assigned"
                    : "") + ":" + String.join(",", scopes);
        }

        public enum Kind {
            JIRA, GITHUB, GITLAB
        }
    }

    /** Where a listing goes on: its position, and Jira Cloud's cursor for that position. */
    record Position(int offset, @Nullable String cursor) {

        public static final Position START = new Position(0, null);
    }

    /**
     * A session of reads with one credential, for one pass of a poll or one run now; it keeps Jira's time zone and
     * Jira Cloud's cursor between its pages. Not thread-safe. Every method runs outside any transaction.
     */
    interface Session {

        SystemKind kind();

        /**
         * One page of the listing: the subjects changed at or after {@code since} (at the listing's precision,
         * {@link #precision}), oldest change first, from the position on, at most {@code limit} (at most
         * {@link #PAGE_SIZE}), with the comments the listing carries. GitHub's first page is conditional on
         * {@code etag}: unchanged, it is {@link Page#notModified()} and empty.
         */
        CallResult<Page> list(Listing listing, Instant since, Position from, int limit, @Nullable String etag);

        /**
         * The subject with what the events need beyond the listing: its newest comments written since
         * {@code commentsSince} (all of the newest {@link #COMMENTS} when null), oldest first, and a change request's
         * head commit. Fails when a read fails, so that the poll reads the subject again next time.
         */
        CallResult<Subject> complete(Subject subject, @Nullable Instant commentsSince);

        /** One subject by its key, with its newest comments and a change request's head commit. */
        CallResult<Subject> read(String key);

        /**
         * Subjects in the scope that match the query (a key, or words of the title), newest change first, at most
         * {@code limit}, without comments.
         */
        CallResult<List<Subject>> search(@Nullable String scope, @Nullable String query, int limit);

        /** The instant floored to the precision of a listing's bound: a minute in the account's zone (Jira), else a second. */
        Instant precision(Instant instant);

        /** The rate limit as the system last said it in this session, if it did. */
        @Nullable RateLimit rate();
    }

    /**
     * A page of a listing.
     *
     * @param subjects    the usable subjects of the page; an entry without an id, a key or an update time is left out
     * @param count       how many entries the page held, the left-out ones included: the next page starts after them
     * @param more        whether the listing goes on after this page
     * @param next        where the listing goes on
     * @param notModified GitHub's {@code 304}: nothing changed since the ETag
     * @param etag        the listing's ETag (GitHub's first page)
     */
    record Page(List<Subject> subjects, int count, boolean more, Position next, boolean notModified,
            @Nullable String etag) {

        public Page {
            subjects = List.copyOf(subjects);
        }

        public static Page unchanged(Position at, @Nullable String etag) {
            return new Page(List.of(), 0, false, at, true, etag);
        }
    }

    /**
     * The rate limit as the system said it.
     *
     * @param resetsAt when the limit is full again
     */
    record RateLimit(@Nullable Integer limit, @Nullable Integer remaining, @Nullable Instant resetsAt) {

        /** Whether less than a tenth of the limit is left. */
        public boolean low() {
            return limit != null && remaining != null && limit > 0 && remaining * 10L < limit;
        }
    }

    /** {@code ISSUE} or {@code CHANGE_REQUEST}. */
    enum SubjectKind {
        ISSUE, CHANGE_REQUEST
    }

    /**
     * A subject as a poll reads it, the same for every kind.
     *
     * @param id           the system's stable id: Jira's issue id, GitHub's issue id, GitLab's global id with
     *                     {@code issue:} or {@code mr:}; a moved issue keeps it
     * @param key          what people call it: {@code SHOP-241}, {@code owner/repo#57}, {@code group/project!12}
     * @param scope        what a trigger's scope names: the Jira project key, {@code owner/repo}, the project path
     * @param description  as Markdown
     * @param status       Jira's workflow status; null on GitHub and GitLab
     * @param open         false once closed, merged or (Jira) resolved
     * @param commentTotal how many comments it has; more than {@code comments} when not all were read
     * @param headSha      a change request's head commit, once read
     */
    record Subject(String id, String key, SubjectKind kind, String scope, @Nullable String url, String title,
            @Nullable String description, @Nullable String status, @Nullable String type, boolean open,
            List<User> assignees, List<String> labels, Instant created, Instant updated, List<Comment> comments,
            int commentTotal, @Nullable User author, @Nullable String headSha) {

        public Subject {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(key, "key");
            assignees = List.copyOf(assignees);
            labels = List.copyOf(labels);
            comments = List.copyOf(comments);
        }

        public Subject withComments(List<Comment> read) {
            return new Subject(id, key, kind, scope, url, title, description, status, type, open, assignees, labels,
                    created, updated, read, Math.max(commentTotal, read.size()), author, headSha);
        }

        public Subject withHeadSha(@Nullable String sha) {
            return new Subject(id, key, kind, scope, url, title, description, status, type, open, assignees, labels,
                    created, updated, comments, commentTotal, author, sha);
        }

        /** Whether it has more comments than were read. */
        public boolean commentsCut() {
            return commentTotal > comments.size();
        }
    }

    /**
     * An account of the system as a subject names it.
     *
     * @param id    its stable id: Jira Data Center's user key, an Atlassian account id, GitHub's or GitLab's numeric id;
     *              null where the system sent none
     * @param login its name there: a Jira user name, a GitHub or GitLab login; null where it has none (Jira Cloud)
     * @param name  the display name, where the system sends one
     */
    record User(@Nullable String id, @Nullable String login, @Nullable String name) {

        /** Whether this is the account of that identity: its id, or its login ignoring case. */
        public boolean is(@Nullable String externalId, @Nullable String externalLogin) {
            return id != null && externalId != null && id.equalsIgnoreCase(externalId)
                    || login != null && externalLogin != null && login.equalsIgnoreCase(externalLogin);
        }

        /** The name to show: the display name, else the login, else the id. */
        public String shown() {
            return name != null && !name.isBlank() ? name : login != null ? login : Objects.toString(id, "someone");
        }
    }

    /**
     * A comment of a subject.
     *
     * @param id       the system's id
     * @param order    its numeric id, which grows with every new comment of the system
     * @param markdown its body as Markdown
     * @param mentions the identities it mentions in the system's syntax ({@code [~name]}, {@code [~accountid:…]}, a
     *                 mention node, {@code @login}), lower case
     */
    record Comment(String id, long order, @Nullable User author, String markdown, Instant created,
            Set<String> mentions) {

        public Comment {
            mentions = Set.copyOf(mentions);
        }

        /** Whether it mentions that identity. */
        public boolean mentions(@Nullable String externalId, @Nullable String externalLogin) {
            return externalId != null && mentions.contains(externalId.toLowerCase(Locale.ROOT))
                    || externalLogin != null && mentions.contains(externalLogin.toLowerCase(Locale.ROOT));
        }
    }
}
