/**
 * Companies (the tenant boundary), memberships and the four roles, groups of members (ADR-0049 §2), and
 * company-scoped authorisation ({@link com.flowtivo.tinfirm.shared.access.CompanyAccessGuard}).
 * <p>
 * Companies come from the deployment's declarations ({@code TINFIRM_COMPANIES_<KEY>_*}, ADR-0075), applied at every
 * start ({@link com.flowtivo.tinfirm.company.DeclaredCompanies}) and, for their owners, at each sign-in; the identity
 * module's port {@link com.flowtivo.tinfirm.identity.ProvisionedMemberships} gives a provider's new user its company.
 * <p>
 * API: {@link com.flowtivo.tinfirm.company.CompanyDirectory}, {@link com.flowtivo.tinfirm.company.DeclaredCompanies},
 * {@link com.flowtivo.tinfirm.company.CompanyProvisioning} and {@link com.flowtivo.tinfirm.company.GroupMembership}
 * (the groups a member is in); the event
 * {@link com.flowtivo.tinfirm.company.GroupEvents.GroupMembersChanged}. Ports: {@link
 * com.flowtivo.tinfirm.company.MemberPrincipals} (a member's principal lives with the membership), implemented by the
 * principal module; {@link com.flowtivo.tinfirm.company.GroupDependents} (what other modules give a group goes before
 * it), implemented by the principal and account modules; {@link com.flowtivo.tinfirm.company.MemberAccounts} (a
 * member's accounts and their status, as admins see them on the Members page), implemented by the account module;
 * {@link com.flowtivo.tinfirm.company.GroupUses} (what a group's members may use through it), implemented by the
 * agent, account and principal modules; {@link com.flowtivo.tinfirm.company.DeclaredRunnerHosts} (the runner host a
 * declared company names, ADR-0101), implemented by the runner module.
 * <p>
 * Package 6: the company's stop of its runs, a kill switch ({@link com.flowtivo.tinfirm.company.CompanySwitches},
 * {@link com.flowtivo.tinfirm.company.CompanyEvents}), whose effects above the company the port
 * {@link com.flowtivo.tinfirm.company.CompanyRunsDependents} carries (the run module; package 4's triggers and outbox);
 * the port {@link com.flowtivo.tinfirm.company.MemberStatuses} (which members are suspended), implemented by the
 * principal module, which the access guard and the last-owner checks ask.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Company")
package com.flowtivo.tinfirm.company;
