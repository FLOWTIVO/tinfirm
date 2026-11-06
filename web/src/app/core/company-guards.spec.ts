import { TestBed } from '@angular/core/testing';
import {
  type ActivatedRouteSnapshot,
  convertToParamMap,
  provideRouter,
  Router,
  type RouterStateSnapshot,
  UrlTree,
} from '@angular/router';
import type { CurrentUser, Role } from '../api/generated/models';
import { currentUser } from '../testing/test-helpers';
import { companyAdminGuard, companyMemberGuard } from './company-guards';
import { Session } from './session';

describe('company route guards', () => {
  let user: CurrentUser | null;
  let router: Router;

  /** A child route under `/c/:companyId`, as the router hands it to a guard. */
  const route = (companyId = 'c1') =>
    ({
      paramMap: convertToParamMap({}),
      parent: { paramMap: convertToParamMap({ companyId }), parent: null },
    }) as unknown as ActivatedRouteSnapshot;

  const run = (guard: typeof companyAdminGuard, r = route()) =>
    TestBed.runInInjectionContext(() =>
      guard(r, { url: '/c/c1/x' } as RouterStateSnapshot),
    ) as Promise<boolean | UrlTree>;

  const as = (role: Role | null, instanceAdmin = false) =>
    currentUser({
      instanceAdmin,
      memberships: role ? [{ companyId: 'c1', companyName: 'Acme', role }] : [],
    });

  beforeEach(() => {
    user = null;
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        { provide: Session, useValue: { ensureLoaded: () => Promise.resolve(user) } },
      ],
    });
    router = TestBed.inject(Router);
  });

  const outcome = async (guard: typeof companyAdminGuard) => {
    const result = await run(guard);
    return result instanceof UrlTree ? router.serializeUrl(result) : result;
  };

  it.each`
    who                         | user                    | admin            | member
    ${'an owner'}               | ${() => as('OWNER')}    | ${true}          | ${true}
    ${'an admin'}               | ${() => as('ADMIN')}    | ${true}          | ${true}
    ${'a member'}               | ${() => as('MEMBER')}   | ${'/c/c1/inbox'} | ${true}
    ${'an instance admin only'} | ${() => as(null, true)} | ${true}          | ${'/c/c1/inbox'}
    ${'a stranger'}             | ${() => as(null)}       | ${'/c/c1/inbox'} | ${'/c/c1/inbox'}
  `('lets $who through by role, sending the others to the inbox', async (row) => {
    user = (row.user as () => CurrentUser)();
    expect(await outcome(companyAdminGuard)).toBe(row.admin);
    expect(await outcome(companyMemberGuard)).toBe(row.member);
  });

  it('reads the role of the company in the URL, not of another membership', async () => {
    user = currentUser({
      memberships: [
        { companyId: 'c1', companyName: 'Acme', role: 'MEMBER' },
        { companyId: 'c2', companyName: 'Beta', role: 'OWNER' },
      ],
    });
    const result = await run(companyAdminGuard, route('c2'));
    expect(result).toBe(true);
    expect(router.serializeUrl((await run(companyAdminGuard)) as UrlTree)).toBe('/c/c1/inbox');
  });

  it('sends a signed-out caller home', async () => {
    expect(await outcome(companyMemberGuard)).toBe('/');
  });
});
