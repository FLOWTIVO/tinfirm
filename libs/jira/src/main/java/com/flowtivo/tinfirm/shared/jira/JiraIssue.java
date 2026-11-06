package com.flowtivo.tinfirm.shared.jira;

import java.time.Instant;
import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * One issue as Jira answers it, Data Center or Cloud, read by {@link JiraIssues#parse}: the fields as they came, with
 * no meaning added. Texts are raw: Data Center's wiki markup, Cloud's Atlassian Document Format as JSON text
 * ({@link JiraIssues#markdown} converts either). A field the search did not ask for is null or empty.
 *
 * @param id           Jira's issue id, which stays when the issue moves to another project; null when absent
 * @param key          e.g. {@code SUP-1021}
 * @param project      the project's key from {@code fields.project}, null when the search did not ask for it
 * @param description  the raw description
 * @param status       the status's name
 * @param type         the issue type's name
 * @param resolution   the resolution's name ({@code Fixed}, {@code Duplicate}), null while unresolved
 * @param resolved     whether {@code resolutiondate} is set: what a closed status is depends on the workflow
 * @param resolvedAt   {@code resolutiondate}, when it parses
 * @param created      {@code created}, when it parses
 * @param updated      {@code updated}; an issue without it is not read
 * @param labels       the labels
 * @param comments     the comments the answer carried, oldest first
 * @param commentTotal how many comments the issue has, from {@code comment.total}; 0 without a comment field
 */
public record JiraIssue(@Nullable String id, String key, @Nullable String project, @Nullable String summary,
        @Nullable String description, @Nullable String status, @Nullable String type, @Nullable String resolution,
        boolean resolved, @Nullable Instant resolvedAt, @Nullable Instant created, Instant updated, List<String> labels,
        @Nullable User assignee, @Nullable User reporter, List<Comment> comments, int commentTotal) {

    public JiraIssue {
        labels = List.copyOf(labels);
        comments = List.copyOf(comments);
    }

    /**
     * A user as Jira names it: Cloud by {@code accountId}, Data Center by {@code key} and {@code name}. Each caller
     * picks the identity it keys on.
     */
    public record User(@Nullable String accountId, @Nullable String key, @Nullable String name,
            @Nullable String displayName) {
    }

    /**
     * A comment, its body raw like the description.
     *
     * @param id         the comment's numeric id, which orders the comments
     * @param restricted whether only some of the issue's readers see it: restricted to a role or group
     *                   ({@code visibility}), or internal to Jira Service Management's agents ({@code jsdPublic} false,
     *                   or the {@code sd.public.comment} property, read with {@code expand=properties})
     */
    public record Comment(long id, @Nullable User author, @Nullable String body, Instant created, boolean restricted) {
    }
}
