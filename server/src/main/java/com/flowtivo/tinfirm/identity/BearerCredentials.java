package com.flowtivo.tinfirm.identity;

import java.util.UUID;

/**
 * The bearer credentials of a user's AI clients (ADR-0045; CONVENTIONS §5), for the module that ends a membership:
 * personal access tokens and OAuth grants outlive nobody's intent (the security review of 2026-10-27, D9; the owner's
 * decision Q6).
 */
public interface BearerCredentials {

    /**
     * The user left the company: their personal access tokens that name it and their OAuth grants for it are revoked,
     * each audited by the current actor with {@code reason} {@code LEFT_COMPANY}. In the caller's transaction, if any.
     */
    void companyLeft(UUID userId, UUID companyId);
}
