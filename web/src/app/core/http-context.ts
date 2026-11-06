import { HttpContext, HttpContextToken } from '@angular/common/http';

/**
 * Set on requests whose 401 is an expected answer (the session probe, the login call), so the
 * auth interceptor does not redirect to the login page.
 */
export const SKIP_AUTH_REDIRECT = new HttpContextToken<boolean>(() => false);

export function skipAuthRedirect(): HttpContext {
  return new HttpContext().set(SKIP_AUTH_REDIRECT, true);
}
