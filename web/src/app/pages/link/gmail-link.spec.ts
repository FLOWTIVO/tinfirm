import { HttpErrorResponse } from '@angular/common/http';
import { HttpTestingController } from '@angular/common/http/testing';
import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router, withComponentInputBinding } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import type { CurrentUser, GmailLink, GmailLinkRequest } from '../../api/generated/models';
import { Session } from '../../core/session';
import { currentUser, problem, provideTestHttp, settle } from '../../testing/test-helpers';
import { GmailLinkPage, gmailLinkQuestion, gmailLinkRefusal } from './gmail-link';
import { gmailLinkGuard, gmailLinkToken, stashGmailLinkToken } from './gmail-link-token';

@Component({ selector: 'app-stub', template: '<h1>Stub</h1>' })
class Stub {}

const TOKEN = 'q3Jt0aZ8vXk2mN5pR7sT9uW1yB4cD6eF8gH0iJ2kL4m'; // gitleaks:allow (test fixture)
const READ = '/api/companies/c1/gmail/links/read';
const CONFIRM = '/api/companies/c1/gmail/links/confirm';

function aRequest(overrides: Partial<GmailLinkRequest> = {}): GmailLinkRequest {
  return {
    googleEmail: 'alice@gmail.test',
    requestedAt: '2026-10-20T08:13:00Z',
    expiresAt: '2026-10-20T08:23:00Z',
    ownerNotice:
      'Conversations you start through the Gmail add-on are personal: agents act with your own accounts and budget. The Owners of Acme can read them in full; each read is recorded and shown to you.',
    ...overrides,
  };
}

function aLink(overrides: Partial<GmailLink> = {}): GmailLink {
  return {
    id: 'l1',
    companyId: 'c1',
    companyName: 'Acme',
    googleEmail: 'alice@gmail.test',
    linkedAt: '2026-10-20T08:14:00Z',
    lastUsedAt: '2026-10-20T08:14:00Z',
    ...overrides,
  };
}

describe('the Gmail link’s words', () => {
  it.each`
    status | code                    | heading
    ${410} | ${'GMAIL_LINK_EXPIRED'} | ${'This link has expired'}
    ${409} | ${'GMAIL_LINK_USED'}    | ${'This link was already used'}
    ${409} | ${'IDENTITY_IN_USE'}    | ${'This Google account is someone else’s'}
    ${404} | ${undefined}            | ${'This link is not for this account'}
    ${500} | ${undefined}            | ${'Couldn’t check this link'}
  `('a $status $code reads "$heading"', ({ status, code, heading }) => {
    expect(
      gmailLinkRefusal(new HttpErrorResponse({ status, error: problem(status, code) })).heading,
    ).toBe(heading);
  });

  it('names the Google account by the email Google asserted, and when the link was asked for', () => {
    const q = gmailLinkQuestion(aRequest(), 'alice@acme.test', new Date('2026-10-20T08:16:00Z'));
    expect(q.who).toBe(
      'The Google account alice@gmail.test, in Gmail, to your Tinfirm account alice@acme.test.',
    );
    expect(q.when).toMatch(/^Asked for this link at .+, 3 minutes ago\.$/);
    expect(gmailLinkQuestion(aRequest({ googleEmail: null }), 'a@b.c').who).toBe(
      'This Google account, in Gmail, to your Tinfirm account a@b.c.',
    );
  });
});

describe('GmailLinkPage', () => {
  let http: HttpTestingController;
  let harness: RouterTestingHarness;
  const user = signal<CurrentUser | null>(currentUser({ email: 'alice@acme.test' }));

  const el = () => harness.routeNativeElement as HTMLElement;
  const by = (testId: string) => el().querySelector<HTMLElement>(`[data-testid=${testId}]`);
  const text = (node: Element | null | undefined) => node?.textContent?.replace(/\s+/g, ' ').trim();

  async function open(url = `/gmail/link/c1#${TOKEN}`, signedIn = true) {
    user.set(signedIn ? currentUser({ email: 'alice@acme.test' }) : null);
    TestBed.configureTestingModule({
      providers: [
        provideRouter(
          [
            { path: 'login', component: Stub },
            { path: 'c/:companyId/inbox', component: Stub },
            {
              path: 'gmail/link/:companyId',
              canActivate: [gmailLinkGuard],
              component: GmailLinkPage,
            },
          ],
          withComponentInputBinding(),
        ),
        ...provideTestHttp(),
        {
          provide: Session,
          useValue: { user, ensureLoaded: () => Promise.resolve(user()) },
        },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    harness = await RouterTestingHarness.create();
    await harness.navigateByUrl(url);
    await settle();
    harness.detectChanges();
  }

  async function read(answer: object, status = 200) {
    const req = http.expectOne({ method: 'POST', url: READ });
    expect(req.request.body).toEqual({ token: TOKEN });
    if (status === 200) req.flush(answer);
    else req.flush(answer, { status, statusText: 'Error' });
    await settle();
    harness.detectChanges();
  }

  beforeEach(() => sessionStorage.clear());
  afterEach(() => {
    http.verify();
    vi.restoreAllMocks();
  });

  it('keeps the token from the fragment and takes it out of the address', async () => {
    await open();
    expect(TestBed.inject(Router).url).toBe('/gmail/link/c1');
    expect(gmailLinkToken('c1')).toBe(TOKEN);
    await read(aRequest());
  });

  it('sends whoever is not signed in to the login, without the token in the return URL', async () => {
    await open(`/gmail/link/c1#${TOKEN}`, false);
    expect(TestBed.inject(Router).url).toBe('/login?returnUrl=%2Fgmail%2Flink%2Fc1');
    expect(gmailLinkToken('c1')).toBe(TOKEN);
  });

  it('comes back from the login to the kept token, and asks: who, when, the warning, the notice', async () => {
    stashGmailLinkToken('c1', TOKEN);
    await open('/gmail/link/c1');
    await read(aRequest());
    expect(text(by('link-title'))).toBe('Link your Google account to Tinfirm?');
    expect(text(by('link-who'))).toContain('alice@gmail.test');
    expect(text(by('link-who'))).toContain('to your Tinfirm account alice@acme.test');
    expect(text(by('link-warning'))).toContain(
      'Confirm only if you just chose Sign in to Tinfirm in Gmail',
    );
    expect(text(by('link-owner-notice'))).toContain('The Owners of Acme can read them');
  });

  it('links on confirmation, forgets the token and closes its window for Gmail', async () => {
    const close = vi.spyOn(globalThis, 'close').mockImplementation(() => undefined);
    await open();
    await read(aRequest());
    by('link-confirm')!.click();
    await settle();
    const req = http.expectOne({ method: 'POST', url: CONFIRM });
    expect(req.request.body).toEqual({ token: TOKEN });
    req.flush(aLink());
    await settle();
    harness.detectChanges();
    expect(text(by('link-title'))).toBe('Your Google account is linked');
    expect(text(by('link-done'))).toContain('Close this window to go back to Gmail.');
    expect(gmailLinkToken('c1')).toBeNull();
    expect(close).toHaveBeenCalled();
  });

  it.each`
    status | code                    | heading
    ${409} | ${'IDENTITY_IN_USE'}    | ${'This Google account is someone else’s'}
    ${410} | ${'GMAIL_LINK_EXPIRED'} | ${'This link has expired'}
  `(
    'says a refused confirmation ($code), and forgets the used token',
    async ({ status, code, heading }) => {
      await open();
      await read(aRequest());
      by('link-confirm')!.click();
      await settle();
      http.expectOne(CONFIRM).flush(problem(status, code), { status, statusText: 'Error' });
      await settle();
      harness.detectChanges();
      expect(text(by('link-refused'))).toContain(heading);
      expect(by('link-refused')?.getAttribute('data-code')).toBe(code);
      expect(gmailLinkToken('c1')).toBeNull();
    },
  );

  it('says a link it cannot read, and offers nothing to confirm', async () => {
    await open();
    await read(problem(409, 'GMAIL_LINK_USED'), 409);
    expect(text(by('link-refused'))).toContain('This link was already used');
    expect(by('link-confirm')).toBeNull();
    expect(gmailLinkToken('c1')).toBeNull();
  });

  it('keeps the token when the server could not be reached, to try again', async () => {
    await open();
    await read(aRequest());
    by('link-confirm')!.click();
    await settle();
    http.expectOne(CONFIRM).flush(problem(503), { status: 503, statusText: 'Unavailable' });
    await settle();
    harness.detectChanges();
    expect(by('link-error')).not.toBeNull();
    expect(gmailLinkToken('c1')).toBe(TOKEN);
  });

  it('links nothing on “It wasn’t me”, and says when the link is incomplete', async () => {
    await open();
    await read(aRequest());
    by('link-decline')!.click();
    harness.detectChanges();
    expect(text(by('link-declined'))).toContain('Nothing was linked.');
    expect(gmailLinkToken('c1')).toBeNull();
    http.expectNone(CONFIRM);
  });

  it('says a link without a token is incomplete', async () => {
    await open('/gmail/link/c1');
    expect(by('link-no-token')).not.toBeNull();
    http.expectNone(READ);
  });
});
