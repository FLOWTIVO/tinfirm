import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { settle } from '../testing/test-helpers';
import { signInResult, signInReturn, signInReturnTo } from './sign-in-result';

describe('signInResult', () => {
  it('reads a connected account and its system', () => {
    expect(signInResult('connected', 'sys-github')).toEqual({
      connected: true,
      systemId: 'sys-github',
      message: 'Your account is connected.',
    });
  });

  it.each`
    reason                | words
    ${'denied'}           | ${'You declined'}
    ${'invalid_state'}    | ${'could not be matched'}
    ${'expired'}          | ${'expired'}
    ${'exchange_failed'}  | ${'paste a token'}
    ${'refused'}          | ${'refused the account'}
    ${'app_refused'}      | ${'set up its app again'}
    ${'site_not_granted'} | ${'Jira site'}
    ${'identity_in_use'}  | ${'someone else'}
    ${'identity_shared'}  | ${'shared account'}
    ${'already_linked'}   | ${'another Slack user'}
    ${'system_moved'}     | ${'moved to another endpoint'}
    ${'forbidden'}        | ${'no longer connect'}
    ${'name_taken'}       | ${'Add it again under another name'}
  `('says why $reason did not connect', ({ reason, words }) => {
    const result = signInResult(reason);
    expect(result?.connected).toBe(false);
    expect(result?.message).toContain(words);
  });

  it.each(['brand_new', '<img src=x>', 'constructor', '__proto__', 'toString'])(
    'does not echo the unknown value %s',
    (reason) => {
      const result = signInResult(reason);
      expect(result?.connected).toBe(false);
      expect(result?.message).toBe(
        'The system did not connect your account. Start it again, or paste a token instead.',
      );
    },
  );

  it('says nothing when the page was not opened by a sign-in', () => {
    expect(signInResult(null)).toBeNull();
    expect(signInResult('')).toBeNull();
  });
});

@Component({ selector: 'app-sign-in-host', template: '' })
class SignInHost {
  readonly back = signInReturn();
}

describe('signInReturn', () => {
  async function open(url: string) {
    TestBed.configureTestingModule({
      providers: [provideRouter([{ path: 'c/:companyId/accounts', component: SignInHost }])],
    });
    const harness = await RouterTestingHarness.create();
    const host = await harness.navigateByUrl(url, SignInHost);
    await settle();
    return { host, router: TestBed.inject(Router) };
  }

  it('says once how the sign-in went, and drops its parameters from the URL', async () => {
    const { host, router } = await open('/c/c1/accounts?tab=systems&account=connected&system=s1');
    expect(host.back.result()).toEqual({
      connected: true,
      systemId: 's1',
      message: 'Your account is connected.',
    });
    expect(router.url).toBe('/c/c1/accounts?tab=systems');
    host.back.dismiss();
    expect(host.back.result()).toBeNull();
  });

  it('reads a refusal', async () => {
    const { host, router } = await open('/c/c1/accounts?account=denied');
    expect(host.back.result()?.connected).toBe(false);
    expect(host.back.result()?.message).toContain('You declined');
    expect(router.url).toBe('/c/c1/accounts');
  });

  it('says nothing for a page that no sign-in opened', async () => {
    const { host, router } = await open('/c/c1/accounts?tab=hosts');
    expect(host.back.result()).toBeNull();
    expect(router.url).toBe('/c/c1/accounts?tab=hosts');
  });
});

describe('signInReturnTo', () => {
  it('returns to the page, without an earlier outcome or a fragment', () => {
    TestBed.configureTestingModule({ providers: [provideRouter([])] });
    const router = TestBed.inject(Router);
    expect(signInReturnTo(router, '/c/c1/conversations/cv1?account=denied&x=1#bindings')).toBe(
      '/c/c1/conversations/cv1?x=1',
    );
    expect(signInReturnTo(router, '/c/c1/accounts?account=connected&system=s1')).toBe(
      '/c/c1/accounts',
    );
  });
});
