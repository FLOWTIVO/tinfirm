package com.flowtivo.tinfirm.shared.access;

import java.util.UUID;

/**
 * The single entry point for company-scoped authorisation, implemented by the company module.
 * <p>
 * Every service method behind a {@code /api/companies/{companyId}/...} path calls {@link #require} first, inside
 * its transaction. The guard:
 * <ul>
 * <li>returns 404 for a non-member who is not an instance admin (never 403),</li>
 * <li>answers a member by their role only, an instance admin included: 403 for a role that lacks the permission,</li>
 * <li>gives an instance admin who is not a member ADMIN's view under break-glass (the package 6 contract, §5.10),
 * with the right to appoint an OWNER, audited as {@code admin.company.accessed}; 403 for a permission beyond it,</li>
 * <li>refuses a suspended member (the package 6 contract, §5.4, open point 24): {@code 403 PRINCIPAL_SUSPENDED} for a
 * read, {@code 409 PRINCIPAL_SUSPENDED} for a write,</li>
 * <li>answers a bearer call (a personal access token, an OAuth grant) as a non-member's, without break-glass, when its
 * user is not an active member (G9, G6): 404,</li>
 * <li>switches the tenant context ({@code app.company_id}) to the company, which row-level security relies on.</li>
 * </ul>
 */
public interface CompanyAccessGuard {

    CompanyAccess require(UUID companyId, CompanyPermission permission);

    /**
     * Checks again, inside the caller's transaction, an access that {@link #require} granted earlier, for work that
     * outlives its request (a run's event stream): the same answers, 404 and 403 included, with the user read again,
     * so a member removed or with a lower role, a deactivated user and a demoted instance admin lose it. A break-glass
     * that was audited when it was granted is not audited again; one that begins at the re-check is.
     */
    CompanyAccess recheck(CompanyAccess granted, CompanyPermission permission);

    /**
     * As {@link #require}, but a suspended member passes: the paths a suspended member still reads, the company's page
     * and its kill switches with their own suspension (open point 24).
     */
    CompanyAccess requireAllowingSuspended(UUID companyId, CompanyPermission permission);

    /**
     * As {@link #require} for an active member only: no break-glass for an instance admin who is not a member, and a
     * suspended member is a non-member here; both are 404. For the authorization server's consent, which gives a
     * client the member's own rights (§5.10: {@code access_denied} for anyone else).
     */
    CompanyAccess requireActiveMember(UUID companyId, CompanyPermission permission);
}
