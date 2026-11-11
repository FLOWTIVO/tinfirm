import { effect, inject, type Signal, signal, untracked } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router } from '@angular/router';

/**
 * Where the one OAuth callback of accounts (`completeSignIn`) sends the person back: the page the
 * sign-in started from, with `?account=connected&system={systemId}` or `?account={reason}`. A page
 * that starts a sign-in (Accounts, a Connect card) reads these and says what happened, once.
 */

const REASONS: Record<string, string> = {
  denied: 'You declined at the system, so nothing was connected.',
  invalid_state: 'The sign-in could not be matched to one you started here. Start it again.',
  expired: 'The sign-in took longer than ten minutes and expired. Start it again.',
  exchange_failed: 'The system did not hand over the account. Try again, or paste a token instead.',
  refused: 'The system refused the account.',
  app_refused:
    'The system refused its sign-in app: the app’s client ID or secret is not the one the system knows. Ask whoever manages the system to set up its app again.',
  site_not_granted:
    'The sign-in did not include this Jira site. Try again, and choose that site when you allow Tinfirm at Atlassian.',
  identity_in_use: 'That account on the system already belongs to someone else in this company.',
  identity_shared:
    'That account on the system is one of the company’s credentials there, which groups use as a shared account. Ask an owner or admin to give the credential another user, such as a machine user; then connect yours.',
  name_taken:
    'Another credential of the system took this name in the meantime, so nothing was added. Add it again under another name.',
  already_linked: 'You are linked to another Slack user here. Sign in as that user instead.',
  system_moved:
    'The system moved to another endpoint while you signed in, so nothing was connected. Connect it again.',
  forbidden: 'You may no longer connect this account.',
};

export interface SignInResult {
  readonly connected: boolean;
  /** The system connected, for `connected`. */
  readonly systemId: string | null;
  readonly message: string;
}

/** What `?account=` (and `?system=`) say, or null when the page was not opened by a sign-in. */
export function signInResult(
  account: string | null | undefined,
  system?: string | null,
): SignInResult | null {
  if (!account) return null;
  if (account === 'connected') {
    return { connected: true, systemId: system ?? null, message: 'Your account is connected.' };
  }
  return {
    connected: false,
    systemId: null,
    // An unknown value is the query string's, so it is not echoed; `Object.hasOwn` keeps
    // `?account=constructor` from reading the prototype's.
    message: Object.hasOwn(REASONS, account)
      ? REASONS[account]
      : 'The system did not connect your account. Start it again, or paste a token instead.',
  };
}

/** The query parameters the callback adds, which a new sign-in's `returnTo` leaves out. */
const SIGN_IN_PARAMS = ['account', 'system'] as const;

/**
 * The path a sign-in started on this page comes back to: the page's URL without an earlier
 * outcome's `account` and `system`, and without a fragment, which `returnTo` may not carry.
 */
export function signInReturnTo(router: Router, url: string = router.url): string {
  const tree = router.parseUrl(url);
  const queryParams = { ...tree.queryParams };
  for (const name of SIGN_IN_PARAMS) delete queryParams[name];
  tree.queryParams = queryParams;
  tree.fragment = null;
  return router.serializeUrl(tree);
}

export interface SignInReturn {
  /** The outcome the page was opened with, until dismissed; null when it was not opened by one. */
  readonly result: Signal<SignInResult | null>;
  dismiss(): void;
}

/**
 * Reads the outcome of a sign-in the page started (`?account=` and `?system=`) once, and drops
 * both parameters from the URL without a new history entry, so that a reload says nothing again.
 * Accounts shows it; the conversation page continues the binding of `systemId` with it (plan
 * §4.1). Must be called in an injection context.
 */
export function signInReturn(): SignInReturn {
  const route = inject(ActivatedRoute);
  const router = inject(Router);
  const result = signal<SignInResult | null>(null);
  const params = toSignal(route.queryParamMap, { initialValue: route.snapshot.queryParamMap });
  effect(() => {
    const map = params();
    const read = signInResult(map.get('account'), map.get('system'));
    if (!read) return;
    untracked(() => {
      result.set(read);
      void router.navigate([], {
        relativeTo: route,
        queryParams: { account: null, system: null },
        queryParamsHandling: 'merge',
        replaceUrl: true,
      });
    });
  });
  return { result: result.asReadonly(), dismiss: () => result.set(null) };
}
