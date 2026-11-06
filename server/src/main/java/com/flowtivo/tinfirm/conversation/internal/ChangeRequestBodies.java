package com.flowtivo.tinfirm.conversation.internal;

import java.net.URI;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

import com.flowtivo.tinfirm.conversation.ConversationInfo;
import com.flowtivo.tinfirm.system.SystemKind;

/**
 * The texts of a change request the server opens (the package 3 contract, §5.4, open point 5): its title, and its body,
 * made of
 * <ol>
 * <li>the turn's summary, or for a turn that ended on its own questions a note that says so, then its last message
 * (ADR-0036);</li>
 * <li>the conversation's page in Tinfirm;</li>
 * <li>for a conversation about a GitHub issue, the line that names it ({@link #issueLine}), so that GitHub links the
 * change request to the issue;</li>
 * <li>the block of the conversation's other change requests ({@link #relatedBlock}), which each of them keeps up to
 * date ({@link #withRelated});</li>
 * <li>who opened it: {@code Opened by {Person} via Tinfirm agent {agent}}, as the commits name them (§5.6,
 * {@link CommitAuthor}).</li>
 * </ol>
 */
final class ChangeRequestBodies {

    static final String RELATED_START = "<!-- tinfirm:related -->";

    static final String RELATED_END = "<!-- /tinfirm:related -->";

    /** GitHub's longest title of a pull request; GitLab takes 255. */
    static final int MAX_TITLE_LENGTH = 255;

    /** The key of a GitHub issue, {@code owner/repo#number}. */
    private static final Pattern GITHUB_ISSUE = Pattern.compile("^([^/#\\s]+)/([^/#\\s]+)#([0-9]{1,10})$");

    /** The web URL of a GitHub issue. */
    private static final Pattern GITHUB_ISSUE_URL = Pattern
        .compile("^https?://[^/]+/([^/#\\s]+)/([^/#\\s]+)/issues/([0-9]{1,10})/?$");

    private ChangeRequestBodies() {
    }

    /** {@code {subject key}: {title}}, or the conversation's title alone without a subject; cut to fit. */
    static String title(ConversationInfo conversation) {
        String title = conversation.subject() != null
                ? conversation.subject().key() + ": " + conversation.title() : conversation.title();
        title = title.strip();
        if (title.codePointCount(0, title.length()) > MAX_TITLE_LENGTH) {
            title = title.substring(0, title.offsetByCodePoints(0, MAX_TITLE_LENGTH - 1)).strip() + "…";
        }
        return title;
    }

    /**
     * The body of a new change request; {@code related} are the lines of the conversation's other ones. What the
     * server does not write itself (the agent's summary, the issue's line, the names) is {@link #neutral}: only the
     * server writes the related block's markers.
     */
    static String body(String summary, String conversationUrl, @Nullable String issueLine, List<String> related,
            String agentName, String personName) {
        return body(summary, conversationUrl, issueLine, related, agentName, personName, null);
    }

    /**
     * The body of a new change request; a trigger's conversation's ends {@code Opened by Tinfirm agent {agent} for the
     * trigger {name}.} (the package 4 contract, §5.7).
     */
    static String body(String summary, String conversationUrl, @Nullable String issueLine, List<String> related,
            String agentName, String personName, @Nullable String triggerName) {
        String opened = triggerName != null ? "Opened by Tinfirm agent " + agentName + " for the trigger "
                + triggerName : "Opened by " + CommitAuthor.name(personName, agentName);
        return neutral(summary) + "\n\n" + conversationUrl + "\n\n"
                + (issueLine != null ? neutral(issueLine) + "\n\n" : "") + relatedBlock(related) + "\n\n"
                + neutral(opened) + ".";
    }

    /**
     * The text with every HTML comment opener broken ({@code <!--} as {@code <!- -}), so that it holds no marker of
     * the related block: an agent's summary with the first marker alone made a later rewrite take the block from
     * there to the server's end marker, and delete the conversation's link and the issue's line between them.
     */
    static String neutral(String text) {
        return text.replace("<!--", "<!- -");
    }

    /**
     * The first paragraph of the body for a turn that ended on its own questions (ADR-0036): its last message is no
     * summary of the work, so the body says that the agent asked in the conversation, then gives that message when it
     * left one.
     */
    static String questionsSummary(String agentName, @Nullable String lastMessage) {
        String note = agentName + " asked a question in the conversation before finishing; this change request holds "
                + "the work so far. The conversation continues in Tinfirm.";
        return lastMessage != null && !lastMessage.isBlank() ? note + "\n\n" + lastMessage.strip() : note;
    }

    /**
     * The line of the body that names the GitHub issue the conversation is about, so that GitHub links the change
     * request to it: {@code Closes #57} in the issue's own repository (owner and name compared ignoring case), which
     * closes the issue when the pull request is merged; {@code Refs owner/repo#57} in another GitHub repository;
     * {@code Issue: <url>} in a GitLab merge request. Null for a conversation without a GitHub issue as its subject.
     *
     * @param subjectKind    the kind of the subject's system; null when it has none (any more)
     * @param repositoryKind the kind of the change request's code host
     */
    static @Nullable String issueLine(ConversationInfo.@Nullable Subject subject, @Nullable SystemKind subjectKind,
            String repositoryUrl, SystemKind repositoryKind) {
        if (subject == null || subjectKind != SystemKind.GITHUB) {
            return null;
        }
        Matcher issue = GITHUB_ISSUE.matcher(subject.key());
        if (!issue.matches()) {
            issue = subject.url() != null ? GITHUB_ISSUE_URL.matcher(subject.url()) : null;
            if (issue == null || !issue.matches()) {
                return null;
            }
        }
        String owner = issue.group(1);
        String name = issue.group(2);
        String number = issue.group(3);
        if (repositoryKind == SystemKind.GITHUB) {
            String[] path = githubPath(repositoryUrl);
            boolean same = path != null && path[0].equalsIgnoreCase(owner) && path[1].equalsIgnoreCase(name);
            return same ? "Closes #" + number : "Refs " + owner + "/" + name + "#" + number;
        }
        return "Issue: " + (subject.url() != null ? subject.url() : owner + "/" + name + "#" + number);
    }

    /** {@code {owner, name}} of a GitHub repository URL, or null when the URL has no such path. */
    static String @Nullable [] githubPath(String url) {
        try {
            String path = URI.create(url).getPath();
            if (path == null) {
                return null;
            }
            String[] parts = path.replaceAll("^/+", "").replaceAll("\\.git$", "").split("/");
            return parts.length >= 2 && !parts[0].isEmpty() && !parts[1].isEmpty()
                    ? new String[] { parts[0], parts[1] } : null;
        }
        catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /** The block between the markers: the conversation's other change requests, or nothing when there is none. */
    static String relatedBlock(List<String> lines) {
        return lines.isEmpty() ? RELATED_START + "\n" + RELATED_END
                : RELATED_START + "\nRelated change requests:\n"
                        + String.join("\n", lines.stream().map(ChangeRequestBodies::neutral).toList()) + "\n"
                        + RELATED_END;
    }

    /**
     * Replaces the related block of a body, or appends it when the body has none. The block is the last pair of
     * markers, which the server wrote after everything else but the closing line: a body opened before
     * {@link #neutral} may hold a marker in the agent's summary.
     */
    static String withRelated(String body, String block) {
        int start = body.lastIndexOf(RELATED_START);
        int end = start >= 0 ? body.indexOf(RELATED_END, start) : -1;
        if (start >= 0 && end >= 0) {
            return body.substring(0, start) + block + body.substring(end + RELATED_END.length());
        }
        return body.isEmpty() ? block : body + "\n\n" + block;
    }

    /** {@code - {repository name}: {url}}: one line of the related block. */
    static String line(String repositoryName, String url) {
        return "- " + repositoryName + ": " + url;
    }
}
