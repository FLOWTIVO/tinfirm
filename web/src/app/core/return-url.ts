/**
 * Accepts only in-app absolute paths ("/c/123/members?x=1"), never protocol-relative or absolute
 * URLs, backslash tricks or the login page itself. Returns null for anything else.
 */
export function safeReturnUrl(value: string | null | undefined): string | null {
  if (!value || typeof value !== 'string') return null;
  if (!value.startsWith('/') || value.startsWith('//') || value.startsWith('/\\')) return null;
  for (let i = 0; i < value.length; i++) {
    if (value.charCodeAt(i) < 0x20) return null;
  }
  if (value === '/login' || value.startsWith('/login?') || value.startsWith('/login/')) return null;
  return value;
}

/** The server's OAuth authorization endpoint (ADR-0045), the only place `returnTo` may lead. */
export const AUTHORIZATION_ENDPOINT = '/oauth2/authorize';

/**
 * Accepts only a same-origin path to the OAuth authorization endpoint, as the server sends in
 * `/login?returnTo=` when a login interrupts an authorization request (ADR-0045). Returns null for
 * anything else, so a crafted link cannot send the browser elsewhere after the login.
 */
export function safeAuthorizationReturn(value: string | null | undefined): string | null {
  const safe = safeReturnUrl(value);
  if (!safe || safe.includes('\\')) return null;
  if (safe !== AUTHORIZATION_ENDPOINT && !safe.startsWith(AUTHORIZATION_ENDPOINT + '?'))
    return null;
  return safe;
}

const SIGN_IN_RETURN_KEY = 'tinfirm.signInReturn';
const SIGN_IN_RETURN_TTL_MS = 15 * 60 * 1000;

/**
 * Remembers, in this tab, the in-app page a sign-in that the app asked for should come back to: a
 * sign-in with an identity provider lands on `/` (its callback keeps no page), so `/` takes it
 * from here (`takeSignInReturn`). Only a safe in-app path is kept, for 15 minutes; storage that
 * fails keeps nothing, and the sign-in lands on `/` as always.
 */
export function rememberSignInReturn(path: string, now: number = Date.now()): void {
  const safe = safeReturnUrl(path);
  if (!safe) return;
  try {
    sessionStorage.setItem(SIGN_IN_RETURN_KEY, JSON.stringify({ path: safe, at: now }));
  } catch {
    // No session storage: the sign-in lands on `/`.
  }
}

/** The page {@link rememberSignInReturn} kept, once: it is forgotten as it is read. */
export function takeSignInReturn(now: number = Date.now()): string | null {
  let raw: string | null;
  try {
    raw = sessionStorage.getItem(SIGN_IN_RETURN_KEY);
    sessionStorage.removeItem(SIGN_IN_RETURN_KEY);
  } catch {
    return null;
  }
  if (!raw) return null;
  try {
    const kept = JSON.parse(raw) as { path?: unknown; at?: unknown };
    if (typeof kept.at !== 'number' || now - kept.at > SIGN_IN_RETURN_TTL_MS || now < kept.at) {
      return null;
    }
    return typeof kept.path === 'string' ? safeReturnUrl(kept.path) : null;
  } catch {
    return null;
  }
}
