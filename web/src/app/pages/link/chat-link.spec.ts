import { HttpErrorResponse } from '@angular/common/http';
import { HttpTestingController } from '@angular/common/http/testing';
import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router, withComponentInputBinding } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import type { CurrentUser } from '../../api/generated/models';
import { Session } from '../../core/session';
import { aChatLink, IDS } from '../../testing/fixtures';
import { currentUser, problem, provideTestHttp, settle } from '../../testing/test-helpers';
import { ChatLinkPage, linkQuestion, linkRefusal, waitingMessages } from './chat-link';
import { chatLinkGuard, chatLinkToken, stashChatLinkToken } from './chat-link-token';

@Component({ selector: 'app-stub', template: '<h1>Stub</h1>' })
class Stub {}

const TOKEN = 'q3Jt0aZ8vXk2mN5pR7sT9uW1yB4cD6eF8gH0iJ2kL4m'; // gitleaks:allow (test fixture)
const READ = '/api/companies/c1/chat-links/read';
const CONFIRM = '/api/companies/c1/chat-links/confirm';

describe('the link’s words', () => {
  it.each`
    status | code                   | heading
    ${410} | ${'CHAT_LINK_EXPIRED'} | ${'This link has expired'}
    ${409} | ${'CHAT_LINK_USED'}    | ${'This link was already used'}
    ${409} | ${'IDENTITY_IN_USE'}   | ${'This chat user is someone else’s'}
    ${409} | ${'ALREADY_LINKED'}    | ${'You are linked to another chat user'}
    ${404} | ${undefined}           | ${'This link is not for this account'}
    ${500} | ${undefined}           | ${'Couldn’t check this link'}
  `('a $status $code reads "$heading"', ({ status, code, heading }) => {
    expect(
      linkRefusal(new HttpErrorResponse({ status, error: problem(status, code) })).heading,
    ).toBe(heading);
  });

  it('names the chat user by what they cannot choose, and the name as anyone’s', () => {
    const q = linkQuestion(aChatLink(), 'alice@acme.test', new Date('2026-10-12T08:16:00Z'));
    expect(q.title).toBe('Link the Slack user U04ABC123 to your account?');
    expect(q.who).toBe(
      'The Slack user U04ABC123 of the workspace Acme (T02XYZ789), on Acme Slack, to your account alice@acme.test.',
    );
    expect(q.when).toMatch(/^Asked for this link at .+, 3 minutes ago\.$/);
    expect(q.name).toBe('Its display name, Jane Doe, is one anyone can set.');
  });

  it.each`
    case                     | link                                                                                                    | who
    ${'Teams, no workspace'} | ${aChatLink({ systemKind: 'TEAMS', systemName: 'Acme Teams', workspaceId: null, workspaceName: null })} | ${'The Teams user U04ABC123, on Acme Teams, to your account a@b.c.'}
    ${'only its id'}         | ${aChatLink({ workspaceName: null })}                                                                   | ${'The Slack user U04ABC123 of the workspace T02XYZ789, on Acme Slack, to your account a@b.c.'}
  `('says $case', ({ link, who }) => {
    expect(linkQuestion(link, 'a@b.c').who).toBe(who);
  });

  it('says nothing of a display name when there is none', () => {
    expect(linkQuestion(aChatLink({ displayName: null }), 'a@b.c').name).toBeNull();
  });

  it.each`
    case                | link                                                                                       | live
    ${'Slack, checked'} | ${aChatLink({ platformChecked: true, platformName: 'Ada Lovelace' })}                      | ${'Slack names this user Ada Lovelace right now.'}
    ${'Teams, checked'} | ${aChatLink({ systemKind: 'TEAMS', platformChecked: true, platformName: 'Ada Lovelace' })} | ${'Teams names this user Ada Lovelace (their Entra name).'}
    ${'not asked'}      | ${aChatLink({ platformChecked: false, platformName: null })}                               | ${'Tinfirm could not ask Slack who this user is just now. Confirm only if you are sure it is you.'}
  `('names who the platform says the user is: $case', ({ link, live }) => {
    expect(linkQuestion(link, 'a@b.c').live).toBe(live);
  });

  it('says the messages that waited are not sent', () => {
    expect(waitingMessages(0)).toBeNull();
    expect(waitingMessages(1)).toBe(
      'The message this user wrote before linking will not be sent: send it again to the bot once linked.',
    );
    expect(waitingMessages(3)).toBe(
      'The 3 messages this user wrote before linking will not be sent: send them again to the bot once linked.',
    );
  });
});

describe('ChatLinkPage', () => {
  let http: HttpTestingController;
  let harness: RouterTestingHarness;
  const user = signal<CurrentUser | null>(currentUser({ email: 'alice@acme.test' }));

  const el = () => harness.routeNativeElement as HTMLElement;
  const by = (testId: string) => el().querySelector<HTMLElement>(`[data-testid=${testId}]`);
  const text = (node: Element | null | undefined) => node?.textContent?.replace(/\s+/g, ' ').trim();

  async function open(url = `/link/c1#${TOKEN}`, signedIn = true) {
    user.set(signedIn ? currentUser({ email: 'alice@acme.test' }) : null);
    TestBed.configureTestingModule({
      providers: [
        provideRouter(
          [
            { path: 'login', component: Stub },
            { path: 'c/:companyId/inbox', component: Stub },
            { path: 'c/:companyId/accounts', component: Stub },
            { path: 'link/:companyId', canActivate: [chatLinkGuard], component: ChatLinkPage },
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
  afterEach(() => http.verify());

  it('keeps the token from the fragment and takes it out of the address', async () => {
    await open();
    expect(TestBed.inject(Router).url).toBe('/link/c1');
    expect(chatLinkToken('c1')).toBe(TOKEN);
    await read(aChatLink());
  });

  it('sends whoever is not signed in to the login, without the token in the return URL', async () => {
    await open(`/link/c1#${TOKEN}`, false);
    expect(TestBed.inject(Router).url).toBe('/login?returnUrl=%2Flink%2Fc1');
    expect(chatLinkToken('c1')).toBe(TOKEN);
  });

  it('comes back from the login to the kept token', async () => {
    stashChatLinkToken('c1', TOKEN);
    await open('/link/c1');
    await read(aChatLink());
    expect(text(by('link-title'))).toBe('Link the Slack user U04ABC123 to your account?');
  });

  it('asks before it links: who, when, the warning and the Owner’s notice', async () => {
    await open();
    await read(aChatLink());
    expect(text(by('link-who'))).toContain('to your account alice@acme.test');
    expect(text(by('link-warning'))).toContain(
      'Its display name, Jane Doe, is one anyone can set.',
    );
    expect(text(by('link-warning'))).toContain(
      'Confirm only if you just wrote to the bot yourself',
    );
    expect(text(by('link-owner-notice'))).toContain('the Owner of Acme can read them');
  });

  it('shows who Slack says the user is, with the picture, and what waits', async () => {
    await open();
    await read(
      aChatLink({
        platformChecked: true,
        platformName: 'Ada Lovelace',
        avatar: 'data:image/png;base64,iVBORw0KGgo=',
        waitingMessages: 2,
      }),
    );
    expect(text(by('link-live-name'))).toBe('Slack names this user Ada Lovelace right now.');
    expect(by('link-avatar')?.getAttribute('src')).toBe('data:image/png;base64,iVBORw0KGgo=');
    expect(text(by('link-waiting'))).toContain('The 2 messages this user wrote before linking');
  });

  it('links on confirmation, tells both sides, and forgets the token', async () => {
    await open();
    await read(aChatLink());
    by('link-confirm')!.click();
    await settle();
    const req = http.expectOne({ method: 'POST', url: CONFIRM });
    expect(req.request.body).toEqual({ token: TOKEN });
    req.flush(aChatLink({ linked: true, accountId: 'acc-1' }));
    await settle();
    harness.detectChanges();
    expect(text(by('link-title'))).toBe('Your chat account is linked');
    expect(text(by('link-done'))).toContain('The bot said so in your direct message');
    expect(text(by('link-done'))).toContain('Messages sent while the link waited are not answered');
    expect(by('link-accounts')?.getAttribute('href')).toBe(
      `/c/c1/accounts?tab=systems#chat-${IDS.slack}`,
    );
    expect(chatLinkToken('c1')).toBeNull();
  });

  it.each`
    status | code                   | heading
    ${409} | ${'IDENTITY_IN_USE'}   | ${'This chat user is someone else’s'}
    ${409} | ${'ALREADY_LINKED'}    | ${'You are linked to another chat user'}
    ${410} | ${'CHAT_LINK_EXPIRED'} | ${'This link has expired'}
  `(
    'says a refused confirmation ($code), and forgets the used token',
    async ({ status, code, heading }) => {
      await open();
      await read(aChatLink());
      by('link-confirm')!.click();
      await settle();
      http.expectOne(CONFIRM).flush(problem(status, code), { status, statusText: 'Error' });
      await settle();
      harness.detectChanges();
      expect(text(by('link-refused'))).toContain(heading);
      expect(by('link-refused')?.getAttribute('data-code')).toBe(code);
      expect(by('link-confirm')).toBeNull();
      expect(chatLinkToken('c1')).toBeNull();
    },
  );

  it.each`
    status | code
    ${410} | ${'CHAT_LINK_EXPIRED'}
    ${409} | ${'CHAT_LINK_USED'}
    ${404} | ${undefined}
  `(
    'says a link it cannot read ($status $code) and offers nothing to confirm',
    async ({ status, code }) => {
      await open();
      await read(problem(status, code), status);
      expect(by('link-refused')).not.toBeNull();
      expect(by('link-confirm')).toBeNull();
      expect(chatLinkToken('c1')).toBeNull();
    },
  );

  it('keeps the token when the server could not be reached, to try again', async () => {
    await open();
    await read(aChatLink());
    by('link-confirm')!.click();
    await settle();
    http.expectOne(CONFIRM).flush(problem(503), { status: 503, statusText: 'Unavailable' });
    await settle();
    harness.detectChanges();
    expect(by('link-error')).not.toBeNull();
    expect(by('link-confirm')).not.toBeNull();
    expect(chatLinkToken('c1')).toBe(TOKEN);
  });

  it('links nothing on “It wasn’t me”', async () => {
    await open();
    await read(aChatLink());
    by('link-decline')!.click();
    harness.detectChanges();
    expect(text(by('link-declined'))).toContain('Nothing was linked.');
    expect(chatLinkToken('c1')).toBeNull();
    http.expectNone(CONFIRM);
  });

  it('says a link without a token is incomplete', async () => {
    await open('/link/c1');
    expect(by('link-no-token')).not.toBeNull();
    http.expectNone(READ);
  });

  it('ignores a fragment that is no token', async () => {
    await open('/link/c1#<script>');
    expect(chatLinkToken('c1')).toBeNull();
    expect(by('link-no-token')).not.toBeNull();
  });
});
