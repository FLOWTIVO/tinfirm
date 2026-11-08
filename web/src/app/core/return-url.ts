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

/** Paths the server answers itself, not pages: it refuses them as a sign-in's return path. */
const SERVER_PATHS = ['/api', '/oauth2', '/login', '/saml2', '/actuator', '/.well-known', '/scim'];

/**
 * The start of a sign-in with an identity provider (`loginUrl`, `/oauth2/authorization/{id}` or
 * `/saml2/authenticate/{id}`), with the in-app page to come back to as `returnTo` (threat model
 * 3.4, Q44). The server checks it again, keeps it with that one sign-in, and returns there after
 * a successful sign-in only. A page that is not safe, `/`, or a server path adds nothing: the
 * sign-in lands on `/`, or on the authorization request it interrupted (ADR-0045).
 */
export function ssoStartUrl(loginUrl: string, page: string | null | undefined): string {
  const safe = safeReturnUrl(page);
  if (!safe || safe === '/' || safe.startsWith('/?') || safe.includes('\\') || safe.includes('#')) {
    return loginUrl;
  }
  const path = safe.split('?')[0].toLowerCase();
  if (SERVER_PATHS.some((p) => path === p || path.startsWith(p + '/'))) return loginUrl;
  return `${loginUrl}${loginUrl.includes('?') ? '&' : '?'}returnTo=${encodeURIComponent(safe)}`;
}
