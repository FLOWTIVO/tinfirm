import { TestBed } from '@angular/core/testing';
import { provideRouter, Router, withComponentInputBinding } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import type { CurrentUser, Role } from './api/generated/models';
import { companyRoutes, routes } from './app.routes';
import { companyAdminGuard, companyMemberGuard, companyWorkerGuard } from './core/company-guards';
import { Session } from './core/session';
import { chatLinkGuard } from './pages/link/chat-link-token';
import { aCurrentUser, aMembership } from './testing/fixtures';
import { provideTestHttp } from './testing/test-helpers';

/**
 * The pages of the plan's §3 to §5 under `/c/:companyId`, and who reaches each: every member
 * (`member`), every member but a Viewer, who never works (`worker`, ADR-0077), or OWNER and ADMIN.
 */
const PLAN: readonly [path: string, who: 'everyone' | 'member' | 'worker' | 'admin'][] = [
  ['inbox', 'everyone'],
  ['conversations', 'everyone'],
  ['conversations/new', 'worker'],
  ['conversations/:conversationId', 'everyone'],
  ['runs/:runId', 'everyone'],
  ['agents', 'everyone'],
  ['agents/new', 'worker'],
  ['agents/:agentId', 'everyone'],
  ['agents/:agentId/edit', 'everyone'],
  ['agents/:agentId/settings', 'worker'],
  ['skills', 'everyone'],
  ['skills/new', 'worker'],
  ['skills/:skillId', 'everyone'],
  ['triggers', 'everyone'],
  ['triggers/new', 'worker'],
  ['triggers/:triggerId', 'everyone'],
  ['triggers/:triggerId/edit', 'everyone'],
  ['accounts', 'worker'],
  ['systems', 'admin'],
  ['systems/:systemId', 'everyone'],
  ['service-identities', 'admin'],
  ['service-identities/:principalId', 'admin'],
  ['model-access', 'admin'],
  ['runner-hosts', 'admin'],
  ['members', 'admin'],
  ['audit', 'admin'],
  ['settings', 'admin'],
];

const route = (path: string) => companyRoutes.find((r) => r.path === path);

describe('the routes of the plan (§3 to §5)', () => {
  it.each(PLAN)('routes %s, guarded for %s', (path, who) => {
    const r = route(path);
    expect(r, path).toBeDefined();
    const guards = r?.canActivate ?? [];
    expect(guards.includes(companyAdminGuard)).toBe(who === 'admin');
    expect(guards.includes(companyMemberGuard)).toBe(who === 'member');
    expect(guards.includes(companyWorkerGuard)).toBe(who === 'worker');
  });

  it('builds the trigger pages (W5)', () => {
    for (const path of [
      'triggers',
      'triggers/new',
      'triggers/:triggerId',
      'triggers/:triggerId/edit',
    ]) {
      expect(route(path)?.data?.['page'], path).toBeUndefined();
      expect(route(path)?.loadComponent, path).toBeDefined();
    }
  });

  it('opens the chat sign-in link outside the shell, behind its own guard', () => {
    const link = routes.find((r) => r.path === 'link/:companyId');
    expect(link?.canActivate).toEqual([chatLinkGuard]);
    const shell = routes.find((r) => r.path === '' && r.children);
    expect(shell?.children?.some((r) => r.path?.startsWith('link'))).toBe(false);
  });

  it('serves the Outlook add-in outside the shell and without a session (ADR-0074)', () => {
    for (const path of ['outlook/callback', 'outlook/:companyId']) {
      const page = routes.find((r) => r.path === path);
      expect(page?.loadComponent, path).toBeDefined();
      expect(page?.canActivate, path).toBeUndefined();
    }
    // The callback first, so that it is never read as a company.
    const paths = routes.map((r) => r.path);
    expect(paths.indexOf('outlook/callback')).toBeLessThan(paths.indexOf('outlook/:companyId'));
    const shell = routes.find((r) => r.path === '' && r.children);
    expect(shell?.children?.some((r) => r.path?.startsWith('outlook'))).toBe(false);
  });

  it('splits the settings into their sections, one a page', () => {
    const settings = route('settings');
    expect(settings?.children?.map((c) => [c.path, c.title])).toEqual([
      ['', 'Settings'],
      ['limits', 'Chats and webhooks'],
      ['memory', 'Memory settings'],
      ['secrets', 'Secrets'],
      ['sign-in', 'Sign-in'],
      ['licence', 'Licence'],
      ['**', undefined],
    ]);
  });

  it('keeps no page of the removed domain', () => {
    for (const path of ['work', 'connections', 'credentials', 'runners', 'groups', 'secrets']) {
      expect(route(path)?.loadComponent, path).toBeUndefined();
      expect(route(path)?.redirectTo, path).toBeDefined();
    }
  });
});

describe('navigating the company', () => {
  async function harness(role: Role | null, instanceAdmin = false) {
    const user: CurrentUser = aCurrentUser({
      instanceAdmin,
      memberships: role ? [aMembership(role)] : [],
    });
    TestBed.configureTestingModule({
      providers: [
        provideRouter(
          [{ path: 'c/:companyId', children: companyRoutes }],
          withComponentInputBinding(),
        ),
        ...provideTestHttp(),
      ],
    });
    TestBed.inject(Session).setUser(user);
    return RouterTestingHarness.create();
  }

  it.each([
    ['/c/c1/inbox', 'Inbox'],
    ['/c/c1/conversations', 'Conversations'],
    ['/c/c1/agents/a1/settings', 'My settings'],
    ['/c/c1/triggers/new', 'New trigger'],
    ['/c/c1/accounts?tab=budget', 'Accounts'],
  ])('opens %s for a member', async (url, title) => {
    const h = await harness('MEMBER');
    await h.navigateByUrl(url);
    expect(h.routeNativeElement?.querySelector('h1')?.textContent?.trim()).toBe(title);
  });

  it('sends a member from the company’s setup to the inbox', async () => {
    const h = await harness('MEMBER');
    await h.navigateByUrl('/c/c1/systems');
    expect(TestBed.inject(Router).url).toBe('/c/c1/inbox');
  });

  it('opens the setup for an admin, and keeps an instance admin off Accounts', async () => {
    const admin = await harness('ADMIN');
    await admin.navigateByUrl('/c/c1/service-identities');
    expect(admin.routeNativeElement?.querySelector('h1')?.textContent?.trim()).toBe(
      'Service accounts',
    );

    TestBed.resetTestingModule();
    const instanceAdmin = await harness(null, true);
    await instanceAdmin.navigateByUrl('/c/c1/accounts');
    expect(TestBed.inject(Router).url).toBe('/c/c1/inbox');
  });

  it('sends the paths of before to their pages of now', async () => {
    const h = await harness('OWNER');
    await h.navigateByUrl('/c/c1/groups');
    expect(TestBed.inject(Router).url).toBe('/c/c1/members?tab=groups');
  });

  it('sends the secrets of before to the settings’ Secrets section', async () => {
    const h = await harness('OWNER');
    await h.navigateByUrl('/c/c1/secrets');
    expect(TestBed.inject(Router).url).toBe('/c/c1/settings/secrets');
  });
});
