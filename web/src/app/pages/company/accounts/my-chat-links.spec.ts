import { HttpTestingController } from '@angular/common/http/testing';
import { type ComponentFixture, TestBed } from '@angular/core/testing';
import type { MyAccount } from '../../../api/generated/models';
import { aChatAccount, anAgent, IDS } from '../../../testing/fixtures';
import { fakeNotify, problem, provideTestHttp, settle } from '../../../testing/test-helpers';
import { chatLinkView, MyChatLinks } from './my-chat-links';

describe('chatLinkView', () => {
  it.each`
    case                     | item                                                                                                | chip                     | says
    ${'linked'}              | ${aChatAccount()}                                                                                   | ${'Linked'}              | ${'Linked to Slack user U04ABC123. What you write to the Tinfirm bot there are your own conversations, with your own accounts and budget.'}
    ${'linked, broken'}      | ${aChatAccount(true, { account: { ...aChatAccount().account!, status: 'RECONNECT' } })}             | ${'Linked, not working'} | ${'Linked to Slack user U04ABC123. What you write to the Tinfirm bot there are your own conversations, with your own accounts and budget.'}
    ${'linked without name'} | ${aChatAccount(true, { account: { ...aChatAccount().account!, login: null } })}                     | ${'Linked'}              | ${'Linked to your Slack user. What you write to the Tinfirm bot there are your own conversations, with your own accounts and budget.'}
    ${'not linked'}          | ${aChatAccount(false)}                                                                              | ${'Not linked'}          | ${'Send the Tinfirm bot a direct message in Slack: it answers with a link that you confirm here, once.'}
    ${'Teams, not linked'}   | ${aChatAccount(false, { system: { ...aChatAccount().system, kind: 'TEAMS', name: 'Acme Teams' } })} | ${'Not linked'}          | ${'Send the Tinfirm bot a direct message in Teams: it answers with a link that you confirm here, once.'}
  `('reads $case', ({ item, chip, says }) => {
    const view = chatLinkView(item);
    expect(view.chip).toBe(chip);
    expect(view.says).toBe(says);
    expect(view.linked).toBe(!!item.account);
    // Without the Slack app, nothing to sign in to.
    expect(view.signIn).toBeNull();
  });

  const signedIn = { ...aChatAccount().account!, kind: 'OAUTH' as const };
  it.each`
    case                             | item                                                                                                | chip             | says                                                                                      | signIn
    ${'not linked, the app set'}     | ${aChatAccount(false, { oauthAvailable: true })}                                                    | ${'Not linked'}  | ${'Sign in to Slack, or send the Tinfirm bot a direct message in Slack'}                  | ${'Sign in to Slack'}
    ${'linked, the app set'}         | ${aChatAccount(true, { oauthAvailable: true })}                                                     | ${'Linked'}      | ${'Sign in to Slack to let agents read channels as you.'}                                 | ${'Sign in to Slack'}
    ${'signed in'}                   | ${aChatAccount(true, { oauthAvailable: true, account: signedIn })}                                  | ${'Signed in'}   | ${'Signed in as Slack user U04ABC123: agents read Slack channels as you'}                 | ${null}
    ${'signed in, refused since'}    | ${aChatAccount(true, { oauthAvailable: true, account: { ...signedIn, status: 'RECONNECT' } })}      | ${'Sign in again'} | ${'Slack no longer accepts your sign-in as Slack user U04ABC123'}                       | ${'Sign in again'}
    ${'Teams, never a sign-in'}      | ${aChatAccount(false, { oauthAvailable: true, system: { ...aChatAccount().system, kind: 'TEAMS' } })} | ${'Not linked'}  | ${'Send the Tinfirm bot a direct message in Teams'}                                     | ${null}
  `('reads $case with the Slack app', ({ item, chip, says, signIn }) => {
    const view = chatLinkView(item);
    expect(view.chip).toBe(chip);
    expect(view.says).toContain(says);
    expect(view.signIn).toBe(signIn);
  });
});

describe('MyChatLinks', () => {
  let fixture: ComponentFixture<MyChatLinks>;
  let http: HttpTestingController;
  let notify: ReturnType<typeof fakeNotify>['notify'];
  let unlinked: string[];
  let signIns: MyAccount[];

  const el = () => fixture.nativeElement as HTMLElement;
  const overlay = () => document.querySelector('.cdk-overlay-container') as HTMLElement;
  const text = (node: Element | null | undefined) => node?.textContent?.replace(/\s+/g, ' ').trim();

  async function create(items: MyAccount[]) {
    const notifier = fakeNotify();
    notify = notifier.notify;
    TestBed.configureTestingModule({ providers: [...provideTestHttp(), notifier.provider] });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(MyChatLinks);
    fixture.componentRef.setInput('companyId', 'c1');
    fixture.componentRef.setInput('items', items);
    unlinked = [];
    fixture.componentInstance.unlinked.subscribe((id) => unlinked.push(id));
    signIns = [];
    fixture.componentInstance.signIn.subscribe((item) => signIns.push(item));
    fixture.detectChanges();
    await settle();
    // Linked, the default agent of direct messages reads the preference and the usable agents.
    for (const req of http.match('/api/companies/c1/me/chat')) req.flush({ defaultAgentId: null });
    for (const req of http.match((r) => r.url === '/api/companies/c1/agents')) {
      req.flush({ items: [anAgent()], nextCursor: null });
    }
    await settle();
    fixture.detectChanges();
  }

  afterEach(() => {
    http.verify();
    overlay()?.replaceChildren();
  });

  it('lists each chat with its link, landing a fragment on its row', async () => {
    await create([
      aChatAccount(),
      aChatAccount(false, {
        system: { ...aChatAccount().system, id: IDS.teams, kind: 'TEAMS', name: 'Acme Teams' },
      }),
    ]);
    const rows = el().querySelectorAll('[data-testid=chat-link]');
    expect(rows).toHaveLength(2);
    expect(rows[0].id).toBe(`chat-${IDS.slack}`);
    expect(rows[0].getAttribute('data-linked')).toBe('true');
    expect(rows[1].getAttribute('data-linked')).toBe('false');
    // Nothing to connect: a link is made from the chat.
    expect(rows[1].querySelector('button')).toBeNull();
    expect(rows[0].querySelector('[data-testid=chat-unlink]')).not.toBeNull();
  });

  it('asks the page to sign in to Slack where the app lets the member', async () => {
    const item = aChatAccount(true, { oauthAvailable: true });
    await create([item]);
    el().querySelector<HTMLButtonElement>('[data-testid=chat-sign-in]')!.click();
    expect(signIns).toEqual([item]);
    // While the page starts it, no second sign-in.
    fixture.componentRef.setInput('signingIn', IDS.slack);
    fixture.detectChanges();
    expect(el().querySelector<HTMLButtonElement>('[data-testid=chat-sign-in]')!.disabled).toBe(true);
  });

  it('says that unlinking a Slack sign-in stops agents reading as the member', async () => {
    await create([
      aChatAccount(true, {
        oauthAvailable: true,
        account: { ...aChatAccount().account!, kind: 'OAUTH' },
      }),
    ]);
    el().querySelector<HTMLButtonElement>('[data-testid=chat-unlink]')!.click();
    await settle();
    fixture.detectChanges();
    expect(text(overlay())).toContain(
      'The bot refuses your direct messages until you link again, and agents no longer read Slack as you.',
    );
  });

  it('offers the default agent once a chat user is linked, and not before', async () => {
    await create([aChatAccount(false)]);
    expect(el().querySelector('[data-testid=chat-agent]')).toBeNull();
  });

  it('unlinks after a confirmation, and tells the page', async () => {
    await create([aChatAccount()]);
    el().querySelector<HTMLButtonElement>('[data-testid=chat-unlink]')!.click();
    await settle();
    fixture.detectChanges();
    expect(text(overlay())).toContain('The bot refuses your direct messages until you link again.');
    overlay().querySelector<HTMLButtonElement>('[data-testid=confirm-chat-unlink]')!.click();
    await settle();
    http
      .expectOne({ method: 'DELETE', url: `/api/companies/c1/me/accounts/${IDS.slack}` })
      .flush(null, { status: 204, statusText: 'No Content' });
    await settle();
    expect(unlinked).toEqual([IDS.slack]);
    expect(notify.success).toHaveBeenCalledWith('Your Acme Slack user is unlinked');
  });

  it('says a refused unlink in the dialog', async () => {
    await create([aChatAccount()]);
    el().querySelector<HTMLButtonElement>('[data-testid=chat-unlink]')!.click();
    await settle();
    overlay().querySelector<HTMLButtonElement>('[data-testid=confirm-chat-unlink]')!.click();
    await settle();
    http
      .expectOne((r) => r.method === 'DELETE')
      .flush(problem(404), { status: 404, statusText: 'Not Found' });
    await settle();
    fixture.detectChanges();
    expect(overlay().querySelector('[hlmAlert]')).not.toBeNull();
    expect(unlinked).toEqual([]);
  });
});
