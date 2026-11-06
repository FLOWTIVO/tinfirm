import { inject } from '@angular/core';
import { type CanActivateFn, Router } from '@angular/router';
import { BrowserNavigation } from './browser-navigation';
import { safeAuthorizationReturn, safeReturnUrl } from './return-url';
import { Session } from './session';

function loginTree(router: Router, returnUrl: string) {
  const safe = safeReturnUrl(returnUrl);
  return router.createUrlTree(['/login'], {
    queryParams: safe && safe !== '/' ? { returnUrl: safe } : {},
  });
}

/** Lets logged-in users through; everyone else goes to `/login?returnUrl=…`. */
export const authGuard: CanActivateFn = async (_route, state) => {
  const session = inject(Session);
  const router = inject(Router);
  const user = await session.ensureLoaded();
  return user ? true : loginTree(router, state.url);
};

/** Instance admins only. Other logged-in users go home. */
export const adminGuard: CanActivateFn = async (_route, state) => {
  const session = inject(Session);
  const router = inject(Router);
  const user = await session.ensureLoaded();
  if (!user) return loginTree(router, state.url);
  return user.instanceAdmin ? true : router.createUrlTree(['/']);
};

/**
 * Keeps logged-in users away from the login page. One who arrives with an interrupted OAuth
 * authorization request (`returnTo`, ADR-0045) goes straight back to it, a server page.
 */
export const guestGuard: CanActivateFn = async (route) => {
  const session = inject(Session);
  const router = inject(Router);
  const navigation = inject(BrowserNavigation);
  const user = await session.ensureLoaded();
  if (!user) return true;
  const authorization = safeAuthorizationReturn(route.queryParamMap.get('returnTo'));
  if (authorization) {
    navigation.assign(authorization);
    return false;
  }
  return router.parseUrl(safeReturnUrl(route.queryParamMap.get('returnUrl')) ?? '/');
};
