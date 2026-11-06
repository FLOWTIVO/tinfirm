package com.flowtivo.tinfirm.system.internal.tools;

import java.util.List;

import org.jspecify.annotations.Nullable;

import com.flowtivo.tinfirm.system.CallResult;
import com.flowtivo.tinfirm.system.ChangeRequests;

/**
 * One tracker behind its base URL, called with one credential for one tool call: what the fixed tools of
 * {@link com.flowtivo.tinfirm.system.FixedTools} do on Jira Data Center, Jira Cloud, GitHub and GitLab, over the
 * outbound client and only at the system's origin. Every call returns a {@link CallResult}: failures are returned,
 * never thrown, and never contain the credential. A {@code 401} is {@code UNAUTHORIZED}, a {@code 403} that is not a
 * rate limit {@code FORBIDDEN}. What the kind does not do fails {@code INVALID}.
 */
public interface Tracker {

    /** Comments a {@code get} reads with a subject, at most: the newest. */
    int COMMENTS = 20;

    /** The system's name, as answers name it: {@code Jira}, {@code Jira Cloud}, {@code GitHub}, {@code GitLab}. */
    String name();

    /** The subject by its key, with its newest comments (at most {@code comments}), oldest first. */
    CallResult<Subject> get(String key, int comments);

    /** Subjects the query finds, at most {@code limit}, without their comments. */
    CallResult<List<Subject>> search(String query, int limit);

    /** Adds a comment: Markdown converted to the system's markup, with the bold label in front when there is one. */
    CallResult<Subject.Posted> comment(String key, String markdown, @Nullable String label);

    /** The transitions the issue's status offers (Jira). */
    default CallResult<List<Subject.Transition>> transitions(String key) {
        return unsupported("transitions");
    }

    /** Applies the transition (Jira). */
    default CallResult<Boolean> transition(String key, String transitionId) {
        return unsupported("transitions");
    }

    /** Adds labels; the system creates unknown ones where it can (GitHub, GitLab). */
    default CallResult<Boolean> addLabels(String key, List<String> labels) {
        return unsupported("labels");
    }

    /** Removes a label; false when the subject did not have it (GitHub, GitLab). */
    default CallResult<Boolean> removeLabel(String key, String label) {
        return unsupported("labels");
    }

    /** Closes the subject (GitHub, GitLab). */
    default CallResult<Boolean> close(String key) {
        return unsupported("closing");
    }

    /** Attaches one file to the subject; answers its id (Jira). */
    default CallResult<String> attach(String key, String fileName, String contentType, byte[] content) {
        return unsupported("attachments");
    }

    /** The subject's attachments (Jira). */
    default CallResult<List<com.flowtivo.tinfirm.system.SystemWrites.Attachment>> attachments(String key) {
        return unsupported("attachments");
    }

    /** Links a URL to the subject, idempotent by the URL; answers the link's id (Jira). */
    default CallResult<String> remoteLink(String key, String url, String title) {
        return unsupported("remote links");
    }

    /** A text of the system as Markdown; null and blank give null. */
    @Nullable String toMarkdown(@Nullable String text);

    private <T> CallResult<T> unsupported(String what) {
        return CallResult.failed(CallResult.Kind.INVALID, name() + " takes no " + what + " from Tinfirm");
    }

    /** The change requests of a code host's repositories, by their clone URL (GitHub, GitLab). */
    interface CodeHost {

        /**
         * Opens a change request; when the code host answers that one is open for the head branch already, that one,
         * with {@code created} false. Without a base branch, into the repository's default branch.
         */
        CallResult<ChangeRequests.Opened> open(String repositoryUrl, ChangeRequests.New request);

        /** The state and head commit of the change request of that number. */
        CallResult<ChangeRequests.Read> state(String repositoryUrl, int number);

        /** The body (Markdown) of the change request of that number; empty when it has none. */
        CallResult<String> body(String repositoryUrl, int number);

        /** Replaces the body of the change request of that number. */
        CallResult<Boolean> updateBody(String repositoryUrl, int number, String body);
    }
}
