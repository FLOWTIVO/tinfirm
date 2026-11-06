import { TestBed } from '@angular/core/testing';
import {
  type ActivatedRouteSnapshot,
  convertToParamMap,
  provideRouter,
  Router,
  type RouterStateSnapshot,
  UrlTree,
} from '@angular/router';
import type { CurrentUser } from '../api/generated/models';
import { currentUser } from '../testing/test-helpers';
import { adminGuard, authGuard, guestGuard } from './auth.guard';
import { BrowserNavigation } from './browser-navigation';
import { Session } from './session';

describe('route guards', () => {
  let user: CurrentUser | null;
  let router: Router;
  const navigation = { assign: vi.fn() };

  const state = (url: string) => ({ url }) as RouterStateSnapshot;
  const route = (query: Record<string, string> = {}) =>
    ({ queryParamMap: convertToParamMap(query) }) as ActivatedRouteSnapshot;

  const run = (guard: typeof authGuard, r: ActivatedRouteSnapshot, s: RouterStateSnapshot) =>
    TestBed.runInInjectionContext(() => guard(r, s)) as Promise<boolean | UrlTree>;

  beforeEach(() => {
    user = null;
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        { provide: Session, useValue: { ensureLoaded: () => Promise.resolve(user) } },
        { provide: BrowserNavigation, useValue: navigation },
      ],
    });
    router = TestBed.inject(Router);
  });

  describe('authGuard', () => {
    it('lets a logged-in user through', async () => {
      user = currentUser();
      expect(await run(authGuard, route(), state('/c/c1/members'))).toBe(true);
    });

    it('sends everyone else to /login with the return URL', async () => {
      const result = await run(authGuard, route(), state('/c/c1/members?x=1'));
      expect(result).toBeInstanceOf(UrlTree);
      expect(router.serializeUrl(result as UrlTree)).toBe(
        '/login?returnUrl=%2Fc%2Fc1%2Fmembers%3Fx%3D1',
      );
    });

    it('omits a pointless returnUrl for the home page', async () => {
      const result = (await run(authGuard, route(), state('/'))) as UrlTree;
      expect(router.serializeUrl(result)).toBe('/login');
    });
  });

  describe('adminGuard', () => {
    it('lets instance admins through', async () => {
      user = currentUser({ instanceAdmin: true });
      expect(await run(adminGuard, route(), state('/admin/users'))).toBe(true);
    });

    it('sends other users home', async () => {
      user = currentUser({ instanceAdmin: false });
      const result = (await run(adminGuard, route(), state('/admin/users'))) as UrlTree;
      expect(router.serializeUrl(result)).toBe('/');
    });

    it('sends anonymous users to login', async () => {
      const result = (await run(adminGuard, route(), state('/admin/users'))) as UrlTree;
      expect(router.serializeUrl(result)).toBe('/login?returnUrl=%2Fadmin%2Fusers');
    });
  });

  describe('guestGuard', () => {
    it('shows the login page to anonymous users', async () => {
      expect(await run(guestGuard, route(), state('/login'))).toBe(true);
    });

    it('sends logged-in users to their return URL', async () => {
      user = currentUser();
      const result = (await run(
        guestGuard,
        route({ returnUrl: '/c/c1/secrets' }),
        state('/login'),
      )) as UrlTree;
      expect(router.serializeUrl(result)).toBe('/c/c1/secrets');
    });

    it('sends a logged-in user back to an interrupted OAuth authorization request', async () => {
      user = currentUser();
      navigation.assign.mockClear();
      const result = await run(
        guestGuard,
        route({ returnTo: '/oauth2/authorize?client_id=x&state=y' }),
        state('/login'),
      );
      expect(result).toBe(false);
      expect(navigation.assign).toHaveBeenCalledWith('/oauth2/authorize?client_id=x&state=y');
    });

    it('ignores a returnTo that is not the authorization endpoint', async () => {
      user = currentUser();
      navigation.assign.mockClear();
      const result = (await run(
        guestGuard,
        route({ returnTo: 'https://evil.example/oauth2/authorize' }),
        state('/login'),
      )) as UrlTree;
      expect(navigation.assign).not.toHaveBeenCalled();
      expect(router.serializeUrl(result)).toBe('/');
    });

    it('ignores return URLs that leave the app', async () => {
      user = currentUser();
      const result = (await run(
        guestGuard,
        route({ returnUrl: '//evil.example/phish' }),
        state('/login'),
      )) as UrlTree;
      expect(router.serializeUrl(result)).toBe('/');
    });
  });
});
