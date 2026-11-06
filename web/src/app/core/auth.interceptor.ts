import { HttpErrorResponse, type HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, throwError } from 'rxjs';
import { SKIP_AUTH_REDIRECT } from './http-context';
import { safeReturnUrl } from './return-url';
import { Session } from './session';

/**
 * A 401 on any API call means the session is gone (expired, logged out elsewhere, user
 * deactivated): forget the user and send them to the login page, remembering where they were.
 * The error is still re-thrown so callers can stop their spinners.
 */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const router = inject(Router);
  const session = inject(Session);

  return next(req).pipe(
    catchError((error: unknown) => {
      if (
        error instanceof HttpErrorResponse &&
        error.status === 401 &&
        !req.context.get(SKIP_AUTH_REDIRECT)
      ) {
        session.clear();
        const current = router.url;
        if (!current.startsWith('/login')) {
          const returnUrl = safeReturnUrl(current);
          void router.navigate(['/login'], {
            queryParams: returnUrl && returnUrl !== '/' ? { returnUrl } : {},
          });
        }
      }
      return throwError(() => error);
    }),
  );
};
