package com.flowtivo.tinfirm.conversation;

import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.flowtivo.tinfirm.shared.access.CompanyAccess;
import com.flowtivo.tinfirm.shared.api.model.InboxItem;

/**
 * Package 6's inbox items of the audit and retention (the package 6 contract, §5.11), which a higher module computes;
 * named for them, so that other packages' ports of inbox items stand beside it as separate providers. The inbox
 * ({@code GET /inbox}) adds them to its own, longest waiting first. None is a stored notification: each is computed
 * from the modules' public APIs when the inbox is read, in its transaction and tenant context. Implemented by the
 * governance module ({@code AUDIT_CHECK}, {@code RETENTION_OPEN}).
 */
public interface RetentionInbox {

    /**
     * The items for the caller.
     *
     * @param callerPrincipalId the caller's principal; null for an instance admin who is not a member
     */
    List<InboxItem> items(CompanyAccess access, UUID userId, @Nullable UUID callerPrincipalId);
}
