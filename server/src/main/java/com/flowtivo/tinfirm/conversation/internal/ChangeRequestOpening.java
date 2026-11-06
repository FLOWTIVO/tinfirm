package com.flowtivo.tinfirm.conversation.internal;

import java.util.UUID;

/**
 * Seam between parts 2 and 3: an approved change request (the package 3 contract, §5.8, "Change request approved:
 * opened with the code host's binding"). Part 2 calls it once the approving transaction has committed, outside any
 * transaction; part 3 implements it and opens the change request through {@code ConversationBindings.use} and
 * {@code SystemTools.openChangeRequest}, recording the output.
 */
public interface ChangeRequestOpening {

    void approved(UUID companyId, UUID conversationId, UUID decisionId, UUID repositoryId);
}
