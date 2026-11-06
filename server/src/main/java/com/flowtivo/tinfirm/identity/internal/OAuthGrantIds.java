package com.flowtivo.tinfirm.identity.internal;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * The id of an OAuth grant (ADR-0045): what a person authorized one client to do in one company. A grant has no row
 * of its own; it is the set of a user's authorizations of a client whose {@code resource} is that company's MCP
 * endpoint, so its id is derived from the three (a name-based UUID). The same id names the grant in "Connected apps",
 * in {@code DELETE /api/me/oauth/grants/{grantId}}, in the audit details ({@code grantId}) and in the
 * {@link com.flowtivo.tinfirm.shared.security.BearerGrant} of a request made with one of its tokens.
 */
final class OAuthGrantIds {

    private OAuthGrantIds() {
    }

    static UUID of(String clientId, UUID userId, UUID companyId) {
        return UUID.nameUUIDFromBytes(
                ("tinfirm-oauth-grant|" + clientId + "|" + userId + "|" + companyId).getBytes(StandardCharsets.UTF_8));
    }
}
