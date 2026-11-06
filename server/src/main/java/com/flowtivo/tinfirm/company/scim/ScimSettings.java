package com.flowtivo.tinfirm.company.scim;

import java.util.UUID;

import com.flowtivo.tinfirm.shared.access.CompanyRole;

/**
 * A company's SCIM as the deployment allows it (ADR-0087, decision 2): its declaration's
 * {@code TINFIRM_COMPANIES_<KEY>_SCIM_*}, found by the company's key. A company no declaration names serves no SCIM.
 * Runs in the caller's transaction and tenant context, which must see the company.
 */
public interface ScimSettings {

    /** The company's SCIM, off for a company no declaration names. */
    Settings of(UUID companyId);

    /**
     * @param enabled    whether the deployment allows SCIM for the company
     * @param role       a new member's role when the request names none; never OWNER
     * @param roleSource who decides the role after creation (ADR-0088, decision 8)
     */
    record Settings(boolean enabled, CompanyRole role, ScimRoleSource roleSource) {

        public static final Settings OFF = new Settings(false, CompanyRole.MEMBER, ScimRoleSource.IDP);
    }
}
