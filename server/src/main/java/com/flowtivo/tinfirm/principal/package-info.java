/**
 * Principals (the package 1 contract, §3.1 and §3.2; the platform redesign, Part 1): whom work acts for. A member's
 * principal is created with the membership and kept, as {@code LEFT}, when it ends; a service identity is a non-human
 * principal that admins manage, holds accounts exactly as a person does, never logs in, and is suspended and resumed
 * as a kill switch (I7). Who may use a service identity is a group's right (OWNER always may).
 * <p>
 * API: {@link com.flowtivo.tinfirm.principal.Principals} (lookups, the use right, display names) with
 * {@link com.flowtivo.tinfirm.principal.PrincipalInfo}; the events of {@link
 * com.flowtivo.tinfirm.principal.PrincipalEvents}. It implements the company module's ports
 * {@link com.flowtivo.tinfirm.company.MemberPrincipals} and {@link com.flowtivo.tinfirm.company.GroupDependents}. Port:
 * {@link com.flowtivo.tinfirm.principal.PrincipalDependents} (what hangs from a service identity goes with it, a
 * leaving member's with the membership), implemented by the account and system modules.
 * <p>
 * Package 6: {@link com.flowtivo.tinfirm.principal.PrincipalSwitches}, a principal's suspension for both kinds (a
 * member's too, and every principal of a user an instance admin deactivated) and the state of a member's departure;
 * it implements the company module's {@link com.flowtivo.tinfirm.company.MemberStatuses}.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Principals")
package com.flowtivo.tinfirm.principal;
