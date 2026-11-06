package com.flowtivo.tinfirm.company.scim;

import java.util.UUID;

import com.flowtivo.tinfirm.shared.api.model.ScimTokenCreateRequest;
import com.flowtivo.tinfirm.shared.api.model.ScimTokenCreated;

/**
 * Port: issuing a company's SCIM token, Enterprise's (ADR-0087, decisions 3 and 12; ADR-0077, decision 4,
 * {@code SCIM}): a paid feature's code, under {@code ee/}, which checks the licence in force. The core finds it when
 * used ({@link ScimTokenService}) and never names its implementation: without {@code ee/} the server answers as
 * Community, {@code 403 EDITION_REQUIRED}. Listing and revoking are the core's, in every edition.
 * <p>
 * Runs in the caller's transaction and tenant context, after its access check ({@code MANAGE_PROVISIONING}).
 */
public interface ScimTokens {

    /**
     * Issues a token, shown once in the answer: {@code 409 SCIM_NOT_ENABLED} when the deployment does not allow the
     * company's SCIM, {@code 409 SCIM_TOKEN_LIMIT} beyond two active tokens. Audited {@code scim.token.created}.
     */
    ScimTokenCreated issue(UUID companyId, ScimTokenCreateRequest request);
}
