package com.flowtivo.tinfirm.conversation.internal;

import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * Seam between parts 2 and 3: a change request under {@code ASK} (the package 3 contract, §5.4 and §5.8). After a
 * turn pushed to a repository without a change request of the conversation, or whose latest was merged at another
 * commit (V038), part 3 ({@link ChangeRequestPolicy}) asks part 2 for an approval with
 * the subject {@code {type: CHANGE_REQUEST, repositoryId}}, decided by the conversation's person. Called inside a
 * transaction in the conversation's company; answers the decision's id (the same one when an approval for that
 * repository is already open). Part 2 implements it.
 */
public interface ChangeRequestApprovals {

    UUID raise(UUID companyId, UUID conversationId, @Nullable UUID runId, UUID repositoryId, String what);
}
