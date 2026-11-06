package com.flowtivo.tinfirm.conversation;

import java.util.List;
import java.util.UUID;

/**
 * A person's conversations by counts, for the checklist of a member who leaves (the package 6 contract, §5.5). Runs in
 * the caller's transaction and tenant context; reads no content.
 */
public interface PersonConversations {

    /** How many of the person's personal conversations are open (they end {@code PERSON_LEFT} when they leave). */
    int openOf(UUID companyId, UUID personPrincipalId);

    /** How many of the person's personal conversations ended. */
    int endedOf(UUID companyId, UUID personPrincipalId);

    /** The change requests still open that conversations opened with the principal's own account, newest first. */
    List<OpenChangeRequest> openChangeRequestsOf(UUID companyId, UUID holderPrincipalId);

    /** An open change request, by its link (never its title). */
    record OpenChangeRequest(UUID conversationId, String url, String state) {
    }
}
