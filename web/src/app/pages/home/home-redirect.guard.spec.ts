import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { rememberSignInReturn } from '../../core/return-url';
import { Session } from '../../core/session';
import { aCurrentUser, aMembership } from '../../testing/fixtures';
import { provideTestHttp } from '../../testing/test-helpers';
import { homeRedirectGuard } from './home-redirect.guard';

@Component({ selector: 'app-page', template: '' })
class Page {}

describe('homeRedirectGuard', () => {
  async function open(url: string, memberships = [aMembership('MEMBER')]) {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([
          { path: '', pathMatch: 'full', canActivate: [homeRedirectGuard], component: Page },
          { path: 'c/:companyId/inbox', component: Page },
          { path: 'c/:companyId/accounts', component: Page },
          { path: 'me', component: Page },
        ]),
        ...provideTestHttp(),
      ],
    });
    TestBed.inject(Session).setUser(aCurrentUser({ memberships }));
    const harness = await RouterTestingHarness.create();
    await harness.navigateByUrl(url);
    return TestBed.inject(Router).url;
  }

  it('opens the first company’s inbox', async () => {
    expect(await open('/')).toBe('/c/c1/inbox');
  });

  it('takes a sign-in’s outcome the callback could not route to Accounts', async () => {
    expect(await open('/?account=invalid_state')).toBe('/c/c1/accounts?account=invalid_state');
  });

  it('goes back to the page that asked for a sign-in, once', async () => {
    rememberSignInReturn('/me');
    expect(await open('/')).toBe('/me');
    TestBed.resetTestingModule();
    expect(await open('/')).toBe('/c/c1/inbox');
  });

  it('stays without a company', async () => {
    expect(await open('/', [])).toBe('/');
  });
});
