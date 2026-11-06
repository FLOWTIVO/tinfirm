/**
 * Accounts and grants (the package 1 contract, §3.6 to §3.8 and §5; the platform redesign, Part 3): a principal's
 * credential on a system, one per principal per system, with who it is there; connected by token or by a sign-in
 * through the system's app; a member's kept in a secret the member owns and revealed only for a call that acts as
 * them; service identities' accounts lent to groups; and the binding rule that picks a conversation's or a trigger's
 * account.
 * <p>
 * API: {@link com.flowtivo.tinfirm.account.Accounts} ({@code bind}, {@code check} at every use, {@code reveal}, lookups)
 * with the types of {@link com.flowtivo.tinfirm.account.AccountBinding}, and the events of
 * {@link com.flowtivo.tinfirm.account.AccountEvents}. It implements the ports
 * {@link com.flowtivo.tinfirm.system.SystemAccounts}, {@link com.flowtivo.tinfirm.principal.PrincipalDependents} and
 * {@link com.flowtivo.tinfirm.company.GroupDependents}: accounts and grants go with their system, their service
 * identity, their leaving member and their group; {@link com.flowtivo.tinfirm.company.MemberAccounts} (a member's
 * accounts and their status for admins, I4); and {@link com.flowtivo.tinfirm.vault.SecretKeeper} (a service identity's
 * account secret is not deleted on the Secrets page).
 */
@org.springframework.modulith.ApplicationModule(displayName = "Accounts")
package com.flowtivo.tinfirm.account;
