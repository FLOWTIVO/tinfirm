package com.flowtivo.tinfirm.shared.access;

import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * Proof that the caller was authorised for a company. The tenant context now points at that company.
 *
 * @param companyId        the company
 * @param role             the caller's role, or null for an instance admin who is not a member
 * @param viaInstanceAdmin whether access was granted to an instance admin who is not a member, under break-glass
 *                         (audited as {@code admin.company.accessed}): ADMIN's view, and the right to appoint an OWNER
 *                         (the package 6 contract, §5.10)
 */
public record CompanyAccess(UUID companyId, @Nullable CompanyRole role, boolean viaInstanceAdmin) {

    public CompanyAccess {
        if (viaInstanceAdmin == (role != null)) {
            throw new IllegalArgumentException("Either a member's role or an instance admin's break-glass");
        }
    }

    /**
     * Whether this access already covers {@code permission} without another check: the role's answer for a member,
     * ADMIN's view and appointing an OWNER for the break-glass of an instance admin who is not a member.
     */
    public boolean covers(CompanyPermission permission) {
        return viaInstanceAdmin ? breakGlassAllows(permission) : role != null && permission.isGrantedTo(role);
    }

    /** What break-glass gives an instance admin who is not a member: ADMIN's rights, and appointing an OWNER. */
    public static boolean breakGlassAllows(CompanyPermission permission) {
        return permission.isGrantedTo(CompanyRole.ADMIN) || permission == CompanyPermission.MANAGE_OWNERS;
    }
}
