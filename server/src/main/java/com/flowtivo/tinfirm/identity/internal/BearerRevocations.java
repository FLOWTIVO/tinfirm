package com.flowtivo.tinfirm.identity.internal;

import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.flowtivo.tinfirm.identity.BearerCredentials;

/**
 * Revokes a user's bearer credentials when their intent changes (CONVENTIONS §5; the owner's decision Q6 of
 * 2026-10-27): every personal access token and OAuth grant on a password change or an admin's reset, and those of a
 * company on leaving it (a token that names the company; a grant for its endpoint). A token that names no company
 * keeps working in the user's other companies: it reaches only those the user is a member of.
 */
@Component
class BearerRevocations implements BearerCredentials {

    private final ApiTokenService tokens;

    private final OAuthGrantService grants;

    BearerRevocations(ApiTokenService tokens, OAuthGrantService grants) {
        this.tokens = tokens;
        this.grants = grants;
    }

    @Override
    @Transactional
    public void companyLeft(UUID userId, UUID companyId) {
        tokens.revokeAll(userId, companyId, ApiTokenService.RevokeReason.LEFT_COMPANY);
        grants.revokeAllOf(userId, companyId, ApiTokenService.RevokeReason.LEFT_COMPANY.name());
    }

    /** The user's password changed, by themselves or by an instance admin: every token and grant goes. */
    @Transactional
    void passwordChanged(UUID userId, boolean reset) {
        ApiTokenService.RevokeReason reason = reset ? ApiTokenService.RevokeReason.PASSWORD_RESET
                : ApiTokenService.RevokeReason.PASSWORD_CHANGED;
        tokens.revokeAll(userId, null, reason);
        grants.revokeAllOf(userId, null, reason.name());
    }
}
