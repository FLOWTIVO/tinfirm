package com.flowtivo.tinfirm.system;

import org.jspecify.annotations.Nullable;

/**
 * The types of change requests (ADR-0022, ADR-0023; the package 3 contract, §5.4 and open point 5): a pull request on
 * GitHub, a merge request on GitLab, opened by {@link SystemTools#openChangeRequest}. A {@code GIT} system has no API,
 * so its repositories get none.
 */
public final class ChangeRequests {

    private ChangeRequests() {
    }

    /**
     * What a change request is opened with: the head branch is merged into the base branch.
     *
     * @param base the branch to merge into; null: the repository's default branch (its own as the code host names it,
     *             unless the repository names one)
     */
    public record New(String title, String head, @Nullable String base, String body) {
    }

    /**
     * A change request on its code host.
     *
     * @param number  the pull request's number, or the project-scoped merge request iid
     * @param created whether the call created it, rather than found the one of the branch that was open already
     * @param state   its state in the answer, when it had a usable one
     */
    public record Opened(String url, int number, boolean created, @Nullable State state) {
    }

    /** The state of a pull or merge request on its code host (ADR-0022). */
    public enum State {
        OPEN, DRAFT, MERGED, CLOSED
    }

    /**
     * A change request as read from its code host.
     *
     * @param headSha its head commit (GitHub's {@code head.sha}, GitLab's {@code sha}): for a merged one, the commit
     *                it was merged at, which later pushes to its branch do not move; null when the answer had no
     *                usable one
     */
    public record Read(State state, @Nullable String headSha) {
    }

    /** A commit's full or abbreviated hex name, as code hosts answer it; anything else is not one. */
    public static @Nullable String commit(@Nullable String sha) {
        return sha != null && sha.matches("[0-9a-f]{7,64}") ? sha : null;
    }
}
