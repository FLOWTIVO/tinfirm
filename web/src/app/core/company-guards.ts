import { inject } from '@angular/core';
import { type ActivatedRouteSnapshot, type CanActivateFn, Router } from '@angular/router';
import { type CompanyAccess, isMember, managesCompany, mayWork } from './permissions';
import { Session } from './session';

function companyIdOf(route: ActivatedRouteSnapshot): string | undefined {
  let current: ActivatedRouteSnapshot | null = route;
  while (current) {
    const id = current.paramMap.get('companyId');
    if (id) return id;
    current = current.parent;
  }
  return undefined;
}

/**
 * Lets through callers whose access in the route's company (`/c/:companyId/…`) passes `allowed`,
 * from the session's memberships; everyone else goes to that company's inbox. The server checks
 * every request again: the guard only keeps people off pages whose every call would be refused.
 */
export function companyAccessGuard(allowed: (access: CompanyAccess) => boolean): CanActivateFn {
  return async (route) => {
    const session = inject(Session);
    const router = inject(Router);
    const user = await session.ensureLoaded();
    const companyId = companyIdOf(route);
    if (!user || !companyId) return router.createUrlTree(['/']);
    const access: CompanyAccess = {
      role: user.memberships.find((m) => m.companyId === companyId)?.role ?? null,
      instanceAdmin: user.instanceAdmin,
    };
    return allowed(access) ? true : router.createUrlTree(['/c', companyId, 'inbox']);
  };
}

/** The company's setup pages: OWNER, ADMIN, and an instance admin acting in the company. */
export const companyAdminGuard: CanActivateFn = companyAccessGuard(managesCompany);

/**
 * Pages about one's own principal (accounts, budget, personal runner hosts; starting a
 * conversation): every member, never an instance admin who is not one.
 */
export const companyMemberGuard: CanActivateFn = companyAccessGuard(isMember);

/**
 * Pages that start or shape work (a new conversation, agent or trigger; one's settings of an agent;
 * one's accounts): every member but a Viewer, who never works (ADR-0077 decision 5).
 */
export const companyWorkerGuard: CanActivateFn = companyAccessGuard(mayWork);
