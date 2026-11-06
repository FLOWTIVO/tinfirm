import { inject } from '@angular/core';
import { type CanActivateFn, RedirectCommand, Router } from '@angular/router';
import { Session } from '../../core/session';

/**
 * A Gmail sign-in link's token (ADR-0083): `/gmail/link/{companyId}#{token}`, which the Gmail
 * add-on opens in a window. As the chat's link (`chat-link-token.ts`): the token travels in the
 * fragment, which no request carries, and in request bodies only. The guard keeps it in this tab's
 * session storage across the sign-in and takes it out of the address; the page sends it and
 * forgets it once the link is settled.
 */

const KEY = 'tinfirm.gmail-link.';

function storage(): Storage | null {
  try {
    return globalThis.sessionStorage ?? null;
  } catch {
    return null;
  }
}

export function stashGmailLinkToken(companyId: string, token: string): void {
  storage()?.setItem(KEY + companyId, token);
}

export function gmailLinkToken(companyId: string): string | null {
  return storage()?.getItem(KEY + companyId) ?? null;
}

export function forgetGmailLinkToken(companyId: string): void {
  storage()?.removeItem(KEY + companyId);
}

/** A token as the server hands it out: 32 random bytes, base64url; anything else is no token. */
const TOKEN_PATTERN = /^[A-Za-z0-9_-]{16,200}$/;

/**
 * Keeps the fragment's token, then sends whoever is not signed in to the login with a `returnUrl`
 * without it, and drops it from the address of whoever is.
 */
export const gmailLinkGuard: CanActivateFn = async (route) => {
  const session = inject(Session);
  const router = inject(Router);
  const companyId = route.paramMap.get('companyId') ?? '';
  const fragment = route.fragment;
  if (fragment && TOKEN_PATTERN.test(fragment)) stashGmailLinkToken(companyId, fragment);
  const user = await session.ensureLoaded();
  if (!user) {
    return router.createUrlTree(['/login'], {
      queryParams: { returnUrl: `/gmail/link/${encodeURIComponent(companyId)}` },
    });
  }
  if (fragment) {
    return new RedirectCommand(router.createUrlTree(['/gmail/link', companyId]), {
      replaceUrl: true,
    });
  }
  return true;
};
