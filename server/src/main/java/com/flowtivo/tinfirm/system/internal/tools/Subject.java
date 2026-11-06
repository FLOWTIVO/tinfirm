package com.flowtivo.tinfirm.system.internal.tools;

import java.time.Instant;
import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * An issue, a pull request or a merge request as a tracker returns it to a tool, the same for every kind. Texts are in
 * the system's markup; {@link Tracker#toMarkdown} reads them.
 *
 * @param changeRequest whether it is a pull or merge request rather than an issue
 * @param key           what people call it: {@code PROJ-123}, {@code owner/repo#57}, {@code group/project!12}
 * @param url           its page, when the system reports one
 * @param status        the workflow status, or null where subjects have none (GitHub, GitLab)
 * @param type          the issue type, or null when it has none
 * @param closed        whether the system reports it closed or merged; for Jira, whether it is resolved
 * @param created       when it was created; {@code updated} when the system reports no usable creation time
 * @param comments      the comments read, oldest first
 * @param commentTotal  how many comments it has; more than {@code comments} when not all were read
 * @param author        who created it (Jira's reporter), when the system says
 */
public record Subject(boolean changeRequest, String key, @Nullable String url, String title, @Nullable String body,
        @Nullable String status, @Nullable String type, boolean closed, List<User> assignees, List<String> labels,
        Instant created, Instant updated, List<Comment> comments, int commentTotal, @Nullable User author) {

    public Subject {
        assignees = List.copyOf(assignees);
        labels = List.copyOf(labels);
        comments = List.copyOf(comments);
    }

    /** Whether it has more comments than were read. */
    public boolean commentsCut() {
        return commentTotal > comments.size();
    }

    public Subject withComments(List<Comment> read) {
        return new Subject(changeRequest, key, url, title, body, status, type, closed, assignees, labels, created,
                updated, read, Math.max(commentTotal, read.size()), author);
    }

    public Subject withUrl(@Nullable String page) {
        return new Subject(changeRequest, key, page, title, body, status, type, closed, assignees, labels, created,
                updated, comments, commentTotal, author);
    }

    /**
     * A comment of a subject. Its id is numeric and grows with every new comment of the system; its body is in the
     * system's markup.
     */
    public record Comment(long id, @Nullable User author, String body, Instant created) {
    }

    /**
     * An account of a system.
     *
     * @param name        a Jira username or Atlassian account id, a GitHub or GitLab login
     * @param displayName the full name, where the system sends one
     */
    public record User(String name, @Nullable String displayName) {

        /** The name to show: the display name, else the account name. */
        public String shown() {
            return displayName != null && !displayName.isBlank() ? displayName : name;
        }
    }

    /**
     * A workflow transition a Jira issue offers in its current status.
     *
     * @param to the status the transition leads to, when Jira names it
     */
    public record Transition(String id, String name, @Nullable String to) {
    }

    /**
     * A comment a tool wrote.
     *
     * @param url its page, where the system names one
     */
    public record Posted(long id, @Nullable String url) {
    }
}
