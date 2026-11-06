import { inject } from '@angular/core';
import { type CanActivateFn, Router } from '@angular/router';
import { takeSignInReturn } from '../../core/return-url';
import { Session } from '../../core/session';

/**
 * `/` opens the first company the user belongs to. Without memberships the route renders the
 * empty state instead. (Instance admins are not sent into companies they are not a member of:
 * each such visit is audited as `admin.company.accessed`.)
 *
 * The accounts' OAuth callback returns to `/` with `?account={reason}` when it cannot tell the
 * page the sign-in started from (an unknown or used state): that outcome goes to Accounts, which
 * says it, instead of being dropped on the way to the inbox.
 *
 * A sign-in the app asked for (`rememberSignInReturn`, e.g. to create a personal access token)
 * lands here from the identity provider and goes back to the page that asked.
 */
export const homeRedirectGuard: CanActivateFn = async (route) => {
  const session = inject(Session);
  const router = inject(Router);
  const user = await session.ensureLoaded();
  const back = user ? takeSignInReturn() : null;
  if (back && back !== '/' && !back.startsWith('/?')) return router.parseUrl(back);
  const first = user?.memberships[0];
  if (!first) return true;
  const account = route.queryParamMap.get('account');
  return account
    ? router.createUrlTree(['/c', first.companyId, 'accounts'], { queryParams: { account } })
    : router.createUrlTree(['/c', first.companyId, 'inbox']);
};
