import { HttpTestingController } from '@angular/common/http/testing';
import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import type { CurrentUser, Inbox } from '../api/generated/models';
import { Session } from '../core/session';
import { aCompany, aCurrentUser, aMembership, anInbox } from '../testing/fixtures';
import { provideTestHttp, settle } from '../testing/test-helpers';
import { Shell } from './shell';

@Component({ template: '' })
class Blank {}

describe('Shell', () => {
  let http: HttpTestingController;

  async function render(user: CurrentUser, inbox: Inbox = anInbox()) {
    TestBed.configureTestingModule({
      imports: [Shell],
      providers: [
        provideRouter([{ path: 'c/:companyId', children: [{ path: '**', component: Blank }] }]),
        ...provideTestHttp(),
      ],
    });
    http = TestBed.inject(HttpTestingController);
    TestBed.inject(Session).setUser(user);
    await TestBed.inject(Router).navigateByUrl('/c/c1/inbox');
    const fixture = TestBed.createComponent(Shell);
    await settle();
    const role = user.memberships[0]?.role;
    http.expectOne('/api/companies/c1').flush(aCompany({ myRole: role }));
    http.expectOne('/api/companies/c1/inbox').flush(inbox);
    http.expectOne('/api/system/info').flush({ version: '0.6.0' });
    await settle();
    await fixture.whenStable();
    return fixture.nativeElement as HTMLElement;
  }

  const items = (el: HTMLElement, group: string) =>
    Array.from(el.querySelectorAll(`[data-nav-group=${group}] a[data-nav]`)).map((a) =>
      a.getAttribute('data-nav'),
    );

  afterEach(() => http.verify());

  it('shows a member six items, and the inbox badge of what needs them', async () => {
    const el = await render(aCurrentUser());
    expect(items(el, 'work')).toEqual([
      'inbox',
      'conversations',
      'agents',
      'skills',
      'triggers',
      'accounts',
    ]);
    expect(el.querySelector('[data-nav-group=company]')).toBeNull();
    expect(el.querySelector('a[data-nav=agents]')?.getAttribute('href')).toBe('/c/c1/agents');

    // One open decision and one Connect card; failed and waiting turns are left out.
    const badge = el.querySelector('[data-testid=inbox-badge]')!;
    expect(badge.textContent?.trim()).toBe('2');
    expect(badge.classList).toContain('text-human');
    expect(el.querySelector('a[data-nav=inbox]')?.textContent).toContain('2 need you');
  });

  it('adds the company’s setup for an admin', async () => {
    const el = await render(aCurrentUser({ memberships: [aMembership('ADMIN')] }));
    expect(items(el, 'company')).toEqual([
      'systems',
      'service-identities',
      'model-access',
      'runner-hosts',
      'members',
      'kill-switches',
      'audit',
      'retention',
      'settings',
    ]);
  });

  it('shows no badge while nothing needs the person', async () => {
    const el = await render(
      aCurrentUser(),
      anInbox({
        counts: { decisions: 0, bindings: 0, accounts: 0, failed: 2, waiting: 1 },
        items: [],
      }),
    );
    expect(el.querySelector('[data-testid=inbox-badge]')).toBeNull();
  });
});
