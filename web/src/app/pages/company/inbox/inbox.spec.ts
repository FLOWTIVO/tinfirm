import { HttpTestingController } from '@angular/common/http/testing';
import { type ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import type { Inbox as InboxModel, InboxItemKind, MyAccount } from '../../../api/generated/models';
import { BrowserNavigation } from '../../../core/browser-navigation';
import { InboxCounts } from '../../../core/inbox-counts';
import {
  aBinding,
  aConversation,
  aDecision,
  aFullInbox,
  aGrantChoice,
  aModelAccess,
  aMyAccount,
  anAgent,
  anApproval,
  anInbox,
  anInboxItem,
  aTrigger,
  INBOX_ITEMS,
  IDS,
} from '../../../testing/fixtures';
import {
  fakeCurrentCompany,
  fakeNotify,
  problem,
  provideTestHttp,
  settle,
  typeInto,
} from '../../../testing/test-helpers';
import { Inbox } from './inbox';

const INBOX = '/api/companies/c1/inbox';
const ACCOUNTS = '/api/companies/c1/me/accounts';
const DECIDE_D1 = '/api/companies/c1/decisions/d1/decide';
const DECIDE_D2 = '/api/companies/c1/decisions/d2/decide';
const NO_COUNTS = { decisions: 0, bindings: 0, failed: 0, waiting: 0, accounts: 0 };

/** Where each kind shows: an orange row of what needs the reader, a stopped turn, or a notice. */
const SHOWN_AS: Record<InboxItemKind, string> = {
  DECISION: '[data-testid=inbox-need][data-kind=DECISION]',
  BINDING: '[data-testid=inbox-need][data-kind=BINDING]',
  ACCOUNT: '[data-testid=inbox-need][data-kind=ACCOUNT]',
  AUDIT_CHECK: '[data-testid=inbox-need][data-kind=AUDIT_CHECK]',
  FAILED: '[data-testid=inbox-row][data-kind=FAILED]',
  WAITING: '[data-testid=inbox-row][data-kind=WAITING]',
  RETENTION_OPEN: '[data-testid=inbox-notice][data-kind=RETENTION_OPEN]',
  BUDGET: '[data-testid=inbox-notice][data-kind=BUDGET]',
  COMPANY_SPEND: '[data-testid=inbox-notice][data-kind=COMPANY_SPEND]',
  SPEND_SYNC: '[data-testid=inbox-notice][data-kind=SPEND_SYNC]',
  TRIGGER: '[data-testid=inbox-trigger] [data-testid=inbox-notice][data-kind=TRIGGER]',
  DEPARTURE: '[data-testid=inbox-notice][data-kind=DEPARTURE]',
  CHAT_LINKED: '[data-testid=inbox-notice][data-kind=CHAT_LINKED]',
  CHAT_SUBSCRIPTION_SILENT: '[data-testid=inbox-notice][data-kind=CHAT_SUBSCRIPTION_SILENT]',
  SEAT_FALLBACK: '[data-testid=inbox-notice][data-kind=SEAT_FALLBACK]',
  LICENCE: '[data-testid=inbox-notice][data-kind=LICENCE]',
  SCIM_LAST_OWNER: '[data-testid=inbox-notice][data-kind=SCIM_LAST_OWNER]',
};

describe('Inbox (plan §5)', () => {
  let fixture: ComponentFixture<Inbox>;
  let el: HTMLElement;
  let http: HttpTestingController;
  let badge: { refresh: ReturnType<typeof vi.fn> };
  let assign: ReturnType<typeof vi.fn>;

  interface RenderOptions {
    readonly url?: string;
    readonly role?: 'OWNER' | 'MEMBER' | 'VIEWER';
    /** The reader's accounts, for the Connect cards. */
    readonly accounts?: MyAccount[];
    /** `Agent.editableByMe` of the agents asking for a repository or a host. */
    readonly editable?: boolean;
    /** The reader's principal; a trigger's author is Alice. */
    readonly principalId?: string;
  }

  const JIRA_TO_CONNECT = aMyAccount({
    system: { id: IDS.jira, name: 'Jira', handle: 'jira', kind: 'JIRA_DC', personal: false },
    account: null,
    binding: 'CONNECT',
    oauthAvailable: false,
  });

  async function render(inbox: InboxModel | null, options: RenderOptions = {}) {
    const notifier = fakeNotify();
    badge = { refresh: vi.fn() };
    assign = vi.fn();
    TestBed.configureTestingModule({
      providers: [
        provideRouter([{ path: 'c/:companyId/inbox', component: Inbox }]),
        ...provideTestHttp(),
        notifier.provider,
        fakeCurrentCompany(options.role ?? 'OWNER').provider,
        { provide: InboxCounts, useValue: badge },
        { provide: BrowserNavigation, useValue: { assign } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    await TestBed.inject(Router).navigateByUrl(options.url ?? '/c/c1/inbox');
    fixture = TestBed.createComponent(Inbox);
    el = fixture.nativeElement;
    await settle();
    if (inbox) {
      http.expectOne(INBOX).flush(inbox);
      await answerReads(options);
    }
  }

  /** Answers what the page and its rows read beside the inbox: accounts, agents, triggers. */
  async function answerReads(options: RenderOptions = {}) {
    await settle();
    for (const req of http.match(ACCOUNTS))
      req.flush({ items: options.accounts ?? [JIRA_TO_CONNECT] });
    for (const req of http.match((r) => r.url.startsWith('/api/companies/c1/agents/'))) {
      req.flush(anAgent({ editableByMe: options.editable ?? false }));
    }
    for (const req of http.match((r) => r.url.startsWith('/api/companies/c1/triggers/'))) {
      req.flush(
        aTrigger({ id: 't-triage', acceptedVersion: 5, currentVersion: 6, wideningPending: true }),
      );
    }
    for (const req of http.match('/api/companies/c1/me/model-access')) {
      req.flush(aModelAccess({ principalId: options.principalId ?? IDS.alice }));
    }
    await settle();
    await fixture.whenStable();
  }

  /** The inbox read again after an action, answered with what it holds then. */
  async function reread(inbox: InboxModel) {
    await settle();
    for (const req of http.match(INBOX)) req.flush(inbox);
    await answerReads();
  }

  const q = (sel: string) => el.querySelector<HTMLElement>(sel);
  const all = (sel: string) => Array.from(el.querySelectorAll<HTMLElement>(sel));
  const text = (sel: string) => q(sel)?.textContent?.replace(/\s+/g, ' ').trim();
  const button = (name: string, within: ParentNode = el) =>
    Array.from(within.querySelectorAll<HTMLButtonElement>('button')).find(
      (b) => b.textContent?.replace(/\s+/g, ' ').trim() === name,
    );
  const pillCount = (filter: string) =>
    text(`[data-testid=inbox-filters] [data-filter=${filter}] span`);

  afterEach(() => {
    try {
      http.verify();
    } finally {
      TestBed.resetTestingModule();
    }
  });

  it('says when nothing needs the reader, with where to go instead', async () => {
    await render({ items: [], counts: NO_COUNTS });
    expect(text('[data-testid=inbox-empty] h2')).toBe('Nothing needs you');
    expect(q('[data-testid=inbox-empty-conversations]')?.getAttribute('href')).toBe(
      '/c/c1/conversations',
    );
    expect(q('[data-testid=inbox-empty-agents]')?.getAttribute('href')).toBe('/c/c1/agents');
    expect(q('[data-testid=inbox-filters]')).toBeNull();
  });

  it('tells a viewer that they see what waits but answer nothing (ADR-0077)', async () => {
    await render({ items: [], counts: NO_COUNTS }, { role: 'VIEWER' });
    expect(text('[data-testid=viewer-notice]')).toContain(
      'Viewers see what waits here, but don’t answer questions or decide approvals.',
    );
  });

  it('says when the inbox cannot be read', async () => {
    await render(null);
    http.expectOne(INBOX).flush(problem(500), { status: 500, statusText: 'Error' });
    await settle();
    expect(el.textContent).toContain("Couldn't load the inbox");
  });

  it.each(Object.entries(SHOWN_AS) as [InboxItemKind, string][])(
    'shows a %s item',
    async (kind, selector) => {
      await render({ items: [INBOX_ITEMS[kind]()], counts: aFullInbox().counts });
      expect(q(selector)).not.toBeNull();
      expect(q('[data-testid=inbox-empty]')).toBeNull();
    },
  );

  it('groups every kind: needs you, stopped, notices; with their counts on the pills', async () => {
    await render(aFullInbox());
    expect(all('section h2').map((h) => h.textContent?.trim())).toEqual([
      'Needs you',
      'Stopped',
      'Notices',
    ]);
    expect(q('[data-testid=inbox-needs]')?.querySelectorAll('app-inbox-need')).toHaveLength(4);
    expect(q('[data-testid=inbox-turns]')?.querySelectorAll('app-inbox-row')).toHaveLength(2);
    // Ten notices, and the trigger's own (W5).
    expect(q('[data-testid=inbox-notices]')?.querySelectorAll('app-inbox-notice')).toHaveLength(11);
    expect(
      q('[data-testid=inbox-notices]')?.querySelectorAll('app-inbox-trigger-item'),
    ).toHaveLength(1);
    expect(pillCount('needs')).toBe('4');
    expect(pillCount('turns')).toBe('2');
    expect(pillCount('notices')).toBe('11');
    expect(q('[data-filter=needs] span')?.className).toContain('text-human');
    expect(q('[data-filter=turns] span')?.className).not.toContain('text-human');
    expect(q('[data-testid=inbox-truncated]')).toBeNull();
  });

  it('shows one group alone from its pill, kept in the URL', async () => {
    await render(aFullInbox());
    q('[data-filter=turns]')!.click();
    await fixture.whenStable();
    expect(q('[data-filter=turns]')?.getAttribute('aria-pressed')).toBe('true');
    expect(q('[data-filter=all]')?.getAttribute('aria-pressed')).toBe('false');
    expect(all('section h2').map((h) => h.textContent?.trim())).toEqual(['Stopped']);
    expect(TestBed.inject(Router).url).toBe('/c/c1/inbox?show=stopped');
    q('[data-filter=all]')!.click();
    await answerReads();
    expect(all('section h2')).toHaveLength(3);
  });

  it('says a group is empty while its pill shows it alone', async () => {
    await render(anInbox({ items: [INBOX_ITEMS.FAILED()] }));
    q('[data-filter=needs]')!.click();
    await fixture.whenStable();
    expect(text('[data-testid=inbox-needs] li')).toBe('Nothing needs you now.');
  });

  it('approves from the row, then collapses it and reads the inbox and the badge again', async () => {
    await render(
      anInbox({ items: [INBOX_ITEMS.DECISION()], counts: { ...NO_COUNTS, decisions: 1 } }),
    );
    const row = q('[data-testid=inbox-need][data-decision-id=d2]')!;
    expect(text('[data-testid=inbox-need-ask]')).toBe(
      'Business analyst wants to comment on SHOP-241',
    );
    expect(q('[data-testid=inbox-row-conversation]')?.getAttribute('href')).toBe(
      `/c/c1/conversations/${IDS.conversation}`,
    );
    expect(row.textContent).toContain('waiting');
    expect(button('Add a note', row)).toBeDefined();
    q('[data-testid=inbox-quick-APPROVED]')!.click();
    await settle();
    const req = http.expectOne({ method: 'POST', url: DECIDE_D2 });
    expect(req.request.headers.has('Idempotency-Key')).toBe(true);
    expect(req.request.body).toEqual({ outcome: 'APPROVED' });
    req.flush(anApproval({ status: 'DECIDED', outcome: 'APPROVED' }));
    await settle();
    expect(badge.refresh).toHaveBeenCalled();
    expect(text('[data-testid=inbox-done]')).toContain(
      'Approved Comment on SHOP-241 · Business analyst goes on with it',
    );
    expect(q('[data-testid=inbox-need]')).toBeNull();
    expect(pillCount('needs')).toBe('0');
    await reread(anInbox({ items: [], counts: NO_COUNTS }));
    // Settled here, it keeps its line; the page does not turn empty under the reader.
    expect(q('[data-testid=inbox-done]')).not.toBeNull();
    expect(q('[data-testid=inbox-empty]')).toBeNull();
  });

  it('answers a question with one of its options, from the row', async () => {
    await render(anInbox({ items: [anInboxItem()] }));
    const options = all('[data-testid=inbox-quick-option]');
    expect(options.map((b) => b.textContent?.trim())).toEqual(['Yes', 'No, the web shop only']);
    expect(options[0].getAttribute('aria-label')).toBe('Answer Yes');
    options[0].click();
    await settle();
    const req = http.expectOne({ method: 'POST', url: DECIDE_D1 });
    expect(req.request.body).toEqual({ option: 'Yes' });
    req.flush(aDecision({ status: 'DECIDED', outcome: 'ANSWERED', option: 'Yes' }));
    await settle();
    expect(text('[data-testid=inbox-done]')).toContain('Answered Yes · Business analyst goes on');
    await reread(anInbox({ items: [], counts: NO_COUNTS }));
  });

  it('says when someone decided it first, and reads the inbox again', async () => {
    await render(anInbox({ items: [INBOX_ITEMS.DECISION()] }));
    q('[data-testid=inbox-quick-DENIED]')!.click();
    await settle();
    http
      .expectOne({ method: 'POST', url: DECIDE_D2 })
      .flush(problem(409, 'DECISION_DECIDED', 'Someone decided it first.'), {
        status: 409,
        statusText: 'Conflict',
      });
    await settle();
    expect(q('[data-testid=inbox-row-error]')).not.toBeNull();
    await reread(anInbox({ items: [], counts: NO_COUNTS }));
  });

  it('opens a question in place, one item at a time, and explains a Send with nothing chosen', async () => {
    const approval = INBOX_ITEMS.DECISION();
    await render(anInbox({ items: [anInboxItem(), approval] }));
    button('Reply…')!.click();
    await fixture.whenStable();
    await settle();
    const card = q('[data-testid=inbox-need][data-open=true]')!;
    expect(card.getAttribute('data-decision-id')).toBe('d1');
    expect(card.querySelector('h3')?.textContent?.trim()).toBe(
      'Should the brief cover the mobile app too?',
    );
    // The title says the question whole: the card does not repeat it.
    expect(card.querySelector('app-markdown')).toBeNull();
    expect(document.activeElement).toBe(card.querySelector('textarea'));
    const send = q('[data-testid=decide-ANSWERED]') as HTMLButtonElement;
    expect(send.disabled).toBe(false);
    send.click();
    await fixture.whenStable();
    expect(text('[data-testid=decision-missing]')).toBe('Pick an option or write an answer.');

    // Opening another item folds this one.
    button('Add a note')!.click();
    await fixture.whenStable();
    expect(all('[data-testid=inbox-need][data-open=true]')).toHaveLength(1);
    expect(q('[data-testid=inbox-need][data-open=true]')?.getAttribute('data-decision-id')).toBe(
      'd2',
    );
    button('Reply…')!.click();
    await fixture.whenStable();
    button('No, the web shop only')!.click();
    typeInto(el.querySelector('textarea'), 'The app follows next quarter.');
    await fixture.whenStable();
    q('[data-testid=decide-ANSWERED]')!.click();
    await settle();
    const req = http.expectOne({ method: 'POST', url: DECIDE_D1 });
    expect(req.request.body).toEqual({
      option: 'No, the web shop only',
      answer: 'The app follows next quarter.',
    });
    req.flush(
      aDecision({
        status: 'DECIDED',
        outcome: 'ANSWERED',
        option: 'No, the web shop only',
        answer: 'The app follows next quarter.',
      }),
    );
    await settle();
    expect(text('[data-testid=inbox-done]')).toContain(
      'No, the web shop only, “The app follows next quarter.”',
    );
    await reread(anInbox({ items: [approval], counts: { ...NO_COUNTS, decisions: 1 } }));
    expect(pillCount('needs')).toBe('1');
  });

  it('opens an approval as one sentence, its arguments folded, and approves it with a note', async () => {
    await render(anInbox({ items: [INBOX_ITEMS.DECISION()] }));
    button('Add a note')!.click();
    await fixture.whenStable();
    expect(text('[data-testid=decision-what]')).toBe(
      'Comment on SHOP-241 on Jira, as the account this conversation uses there.',
    );
    expect(q('[data-testid=decision-arguments]')).toBeNull();
    q('[data-testid=decision-arguments-toggle]')!.click();
    await fixture.whenStable();
    expect(text('[data-testid=decision-arguments]')).toContain('"issue": "SHOP-241"');
    typeInto(el.querySelector('textarea'), 'Link the brief.');
    await fixture.whenStable();
    expect(el.textContent).toContain('Your note goes to Business analyst with the decision.');
    q('[data-testid=decide-APPROVED]')!.click();
    await settle();
    const req = http.expectOne({ method: 'POST', url: DECIDE_D2 });
    expect(req.request.body).toEqual({ outcome: 'APPROVED', note: 'Link the brief.' });
    req.flush(anApproval({ status: 'DECIDED', outcome: 'APPROVED', note: 'Link the brief.' }));
    await settle();
    expect(text('[data-testid=inbox-done]')).toContain('goes on with it and your note');
    await reread(anInbox({ items: [], counts: NO_COUNTS }));
  });

  it('folds an open item back to its row', async () => {
    await render(anInbox({ items: [INBOX_ITEMS.DECISION()] }));
    q('[data-testid=inbox-need-ask]')!.click();
    await fixture.whenStable();
    expect(q('[data-testid=inbox-need][data-open=true]')).not.toBeNull();
    q('[data-testid=inbox-need-fold]')!.click();
    await fixture.whenStable();
    await settle();
    expect(q('[data-testid=inbox-need][data-open=true]')).toBeNull();
    expect(document.activeElement).toBe(q('[data-testid=inbox-need-ask]'));
  });

  it('offers “for good” on a repository only when the server says the reader may edit the agent', async () => {
    const item = INBOX_ITEMS.DECISION();
    item.decision = anApproval({ subject: { type: 'REPOSITORY', repositoryName: 'acme/docs' } });
    await render(anInbox({ items: [item] }), { editable: true });
    expect(q('[data-testid=inbox-quick-APPROVED-CONVERSATION]')?.textContent?.trim()).toBe(
      'Allow for this conversation',
    );
    expect(q('[data-testid=inbox-quick-APPROVED-AGENT]')).toBeNull();
    button('More…')!.click();
    await fixture.whenStable();
    expect(q('[data-testid=decide-APPROVED-AGENT]')).not.toBeNull();
    fixture.destroy();
    TestBed.resetTestingModule();
    await render(anInbox({ items: [item] }), { editable: false });
    button('More…')!.click();
    await fixture.whenStable();
    expect(q('[data-testid=decide-APPROVED-CONVERSATION]')).not.toBeNull();
    expect(q('[data-testid=decide-APPROVED-AGENT]')).toBeNull();
  });

  it('connects an account in place: the token the system takes', async () => {
    await render(anInbox({ items: [INBOX_ITEMS.BINDING()] }));
    const row = q('[data-testid=inbox-need][data-kind=BINDING]')!;
    expect(text('[data-testid=inbox-need] [class*=bg-human-soft]')).toBe('Connect Jira');
    expect(q('[data-testid=inbox-row-conversation]')?.textContent).toContain('SHOP-241-1');
    // Jira's app offers no sign-in here: a token only.
    expect(q('[data-testid=inbox-binding-sign-in]')).toBeNull();
    button('Paste a token', row)!.click();
    await fixture.whenStable();
    await settle();
    const token = q('[data-testid=binding-token]') as HTMLInputElement;
    expect(document.activeElement).toBe(token);
    expect(q('[data-testid=binding-card]')?.getAttribute('data-appearance')).toBe('inline');
    typeInto(token, 'jira-pat');
    await fixture.whenStable();
    token.form!.requestSubmit();
    await settle();
    const store = http.expectOne({ method: 'PUT', url: `${ACCOUNTS}/${IDS.jira}/token` });
    store.flush(aMyAccount());
    await settle();
    http
      .expectOne({
        method: 'POST',
        url: `/api/companies/c1/conversations/${IDS.conversation}/bindings/${IDS.jira}/continue`,
      })
      .flush(aBinding({ systemId: IDS.jira, systemName: 'Jira', text: 'Jira: your own account' }));
    await settle();
    expect(text('[data-testid=inbox-done]')).toContain(
      'Connected Jira: your own account · Business analyst goes on',
    );
    await reread(anInbox({ items: [], counts: NO_COUNTS }));
  });

  it('signs in with the system of a Connect card from its row, coming back to the inbox', async () => {
    await render(anInbox({ items: [INBOX_ITEMS.BINDING()] }), {
      accounts: [{ ...JIRA_TO_CONNECT, oauthAvailable: true }],
    });
    q('[data-testid=inbox-binding-sign-in]')!.click();
    await settle();
    const req = http.expectOne({ method: 'POST', url: `${ACCOUNTS}/${IDS.jira}/oauth` });
    expect(req.request.body).toEqual({ returnTo: '/c/c1/inbox' });
    req.flush({ authorizationUrl: 'https://jira.test/authorize' });
    await settle();
    expect(assign).toHaveBeenCalledWith('https://jira.test/authorize');
  });

  it('picks a grant from a Connect card, its choices from the reader’s accounts', async () => {
    const item = INBOX_ITEMS.BINDING();
    item.binding = {
      ...item.binding!,
      systemId: IDS.github,
      systemName: 'GitHub',
      reason: 'PICK_GRANT',
      choices: [IDS.analystsGrant],
    };
    await render(anInbox({ items: [item] }), {
      accounts: [aMyAccount({ account: null, binding: 'CHOOSE', grants: [aGrantChoice()] })],
    });
    button('Choose…')!.click();
    await fixture.whenStable();
    const choice = all('[data-testid=binding-grant]').find((b) =>
      b.textContent?.includes('Analysts'),
    );
    expect(choice?.textContent).toContain('svc-reader');
    choice!.click();
    await settle();
    const req = http.expectOne({
      method: 'POST',
      url: `/api/companies/c1/conversations/${IDS.conversation}/bindings/${IDS.github}/choose`,
    });
    expect(req.request.body).toEqual({ grantId: IDS.analystsGrant });
    req.flush({ ...aConversation().bindings[0] });
    await settle();
    expect(badge.refresh).toHaveBeenCalled();
    expect(text('[data-testid=inbox-done]')).toContain('Connected');
    await reread(anInbox({ items: [] }));
  });

  it('retries a failed turn with an Idempotency-Key, then collapses its row', async () => {
    await render(anInbox({ items: [INBOX_ITEMS.FAILED()] }));
    expect(text('[data-kind=FAILED] [data-testid=inbox-row-title]')).toContain(
      'Usage limit reached',
    );
    expect(q('[data-kind=FAILED] [data-testid=inbox-row-body]')?.textContent).toContain(
      'spent until 1 Nov',
    );
    expect(q('[data-kind=FAILED] [class*=bg-human-soft]')).toBeNull();
    q('[data-testid=inbox-retry]')!.click();
    await settle();
    const req = http.expectOne({
      method: 'POST',
      url: `/api/companies/c1/conversations/${IDS.conversation}/retry`,
    });
    expect(req.request.headers.get('Idempotency-Key')).toMatch(/^[0-9a-f-]{36}$/);
    req.flush(aConversation());
    await settle();
    expect(text('[data-testid=inbox-done]')).toContain(
      'Queued again Checkout fails for guest users · Business analyst tries again',
    );
    await reread(anInbox({ items: [] }));
  });

  it('says why a turn waits, with its reset and the link to the fix', async () => {
    await render(anInbox({ items: [INBOX_ITEMS.WAITING()] }));
    expect(text('[data-kind=WAITING] [data-testid=inbox-row-title]')).toContain(
      'Your budget is spent.',
    );
    expect(text('[data-kind=WAITING] [data-testid=inbox-row-body]')).toContain('Resets');
    expect(q('[data-testid=queue-reason-fix]')?.getAttribute('href')).toBe(
      '/c/c1/accounts?tab=budget',
    );
  });

  it('signs a system’s credential in again from its row, coming back to the inbox', async () => {
    await render(anInbox({ items: [INBOX_ITEMS.ACCOUNT()] }));
    // The link lands on the credential itself, on its system's page.
    expect(q('[data-testid=inbox-row-link]')?.getAttribute('href')).toBe(
      `/c/c1/systems/${IDS.github}#credential-${IDS.readerGithubAccount}`,
    );
    q('[data-testid=inbox-sign-in]')!.click();
    await settle();
    const req = http.expectOne({
      method: 'POST',
      url: `/api/companies/c1/systems/${IDS.github}/credentials/${IDS.readerGithubAccount}/oauth`,
    });
    expect(req.request.body).toEqual({ returnTo: '/c/c1/inbox' });
    req.flush({ authorizationUrl: 'https://github.test/authorize' });
    await settle();
    expect(assign).toHaveBeenCalledWith('https://github.test/authorize');
  });

  it('enables a disabled credential from its row, and what waited on it goes on', async () => {
    const item = INBOX_ITEMS.ACCOUNT();
    item.account = {
      ...item.account!,
      credential: { ...item.account!.credential!, enabled: false },
    };
    await render(anInbox({ items: [item] }));
    expect(text('[data-kind=ACCOUNT] [data-testid=inbox-row-title]')).toBe(
      'The credential svc-reader of GitHub is disabled',
    );
    expect(q('[data-testid=inbox-sign-in]')).toBeNull();
    q('[data-testid=inbox-enable]')!.click();
    await settle();
    const req = http.expectOne({
      method: 'PATCH',
      url: `/api/companies/c1/systems/${IDS.github}/credentials/${IDS.readerGithubAccount}`,
    });
    expect(req.request.body).toEqual({ enabled: true });
    req.flush({});
    await settle();
    expect(text('[data-testid=inbox-done]')).toContain(
      'Enabled svc-reader · 3 conversations go on',
    );
    await reread(anInbox({ items: [], counts: NO_COUNTS }));
  });

  it('sends a refused token to its credential on the system’s page, with nothing to do here', async () => {
    const item = INBOX_ITEMS.ACCOUNT();
    item.account = {
      ...item.account!,
      credential: { ...item.account!.credential!, kind: 'TOKEN' },
    };
    await render(anInbox({ items: [item] }));
    expect(q('[data-testid=inbox-sign-in]')).toBeNull();
    expect(q('[data-testid=inbox-enable]')).toBeNull();
    const link = q('[data-testid=inbox-row-link]')!;
    expect(text('[data-testid=inbox-row-link]')).toBe('Store a new token');
    expect(link.getAttribute('href')).toBe(
      `/c/c1/systems/${IDS.github}#credential-${IDS.readerGithubAccount}`,
    );
  });

  it('unlinks a chat account that was not the reader’s, from its notice', async () => {
    await render(anInbox({ items: [INBOX_ITEMS.CHAT_LINKED()] }));
    expect(text('[data-kind=CHAT_LINKED] [data-testid=inbox-notice-title]')).toBe(
      'A Slack account was linked to you',
    );
    expect(q('[data-kind=CHAT_LINKED] button[aria-label]')?.getAttribute('aria-label')).toBe(
      'Unlink the Slack account',
    );
    q('[data-testid=inbox-chat-unlink]')!.click();
    await settle();
    http
      .expectOne({ method: 'DELETE', url: '/api/companies/c1/me/accounts/sys-slack' })
      .flush(null);
    await settle();
    expect(text('[data-kind=CHAT_LINKED] [role=status]')).toBe('Unlinked');
    await reread(anInbox({ items: [] }));
  });

  it('accepts a trigger’s new version from its notice, naming the version', async () => {
    await render(anInbox({ items: [INBOX_ITEMS.TRIGGER()] }));
    expect(text('[data-testid=inbox-notice-text]')).toBe(
      'Runs version 5 of Business analyst; version 6 may do more.',
    );
    expect(q('[data-kind=TRIGGER] [data-testid=inbox-row-link]')?.getAttribute('href')).toBe(
      '/c/c1/triggers/t-triage',
    );
    q('[data-testid=inbox-trigger-accept]')!.click();
    await settle();
    const req = http.expectOne({
      method: 'POST',
      url: '/api/companies/c1/triggers/t-triage/accept',
    });
    expect(req.request.body).toEqual({ version: 6 });
    req.flush(aTrigger({ id: 't-triage', acceptedVersion: 6, currentVersion: 6 }));
    await settle();
    expect(text('[data-kind=TRIGGER] [role=status]')).toBe('Runs version 6');
    await reread(anInbox({ items: [] }));
  });

  it('offers a new version’s acceptance to the trigger’s author alone (H2)', async () => {
    await render(anInbox({ items: [INBOX_ITEMS.TRIGGER()] }), { principalId: 'p-someone-else' });
    expect(text('[data-testid=inbox-notice-text]')).toBe(
      'Runs version 5 of Business analyst; version 6 may do more.',
    );
    expect(q('[data-testid=inbox-trigger-accept]')).toBeNull();
  });

  describe('dismissing a notice', () => {
    const SPEND = '/api/companies/c1/inbox/dismissals/COMPANY_SPEND%3A2026-10';
    const spendInbox = () => anInbox({ items: [INBOX_ITEMS.COMPANY_SPEND()], counts: NO_COUNTS });
    const dismissButton = () => q('[data-testid=inbox-notice-dismiss]');

    it('offers Dismiss only on a notice that only informs the reader', async () => {
      await render(aFullInbox());
      const dismissible = all('[data-testid=inbox-notice]')
        .filter((row) => row.querySelector('[data-testid=inbox-notice-dismiss]'))
        .map((row) => row.dataset['kind']);
      expect(dismissible).toEqual([
        'BUDGET',
        'COMPANY_SPEND',
        'SPEND_SYNC',
        'CHAT_LINKED',
        'SEAT_FALLBACK',
        'LICENCE',
        'SCIM_LAST_OWNER',
      ]);
      // Named for a screen reader by what it dismisses; 44px on a phone.
      const button = q('[data-kind=COMPANY_SPEND] [data-testid=inbox-notice-dismiss]')!;
      expect(button.getAttribute('aria-label')).toBe(
        'Dismiss the notice: The company’s spend crossed its alert amount',
      );
      expect(button.className).toContain('max-md:size-11');
    });

    it('offers it on a trigger’s notice the server says only informs', async () => {
      await render(
        anInbox({
          items: [
            anInboxItem({
              kind: 'TRIGGER',
              decision: null,
              conversation: null,
              noticeKey: 'TRIGGER:t-triage:PAUSED:USER:1792051200000',
              dismissible: true,
              trigger: {
                triggerId: 't-triage',
                name: 'Triage new bugs',
                reason: 'PAUSED',
                detail: 'USER',
              },
            }),
          ],
          counts: NO_COUNTS,
        }),
      );
      q('[data-kind=TRIGGER] [data-testid=inbox-notice-dismiss]')!.click();
      await settle();
      http
        .expectOne({
          method: 'PUT',
          url: '/api/companies/c1/inbox/dismissals/TRIGGER%3At-triage%3APAUSED%3AUSER%3A1792051200000',
        })
        .flush(null);
      await settle();
      expect(text('[data-testid=inbox-dismissed]')).toContain('Triage new bugs');
      await reread(anInbox({ items: [], counts: NO_COUNTS }));
    });

    it('hides it at once for the reader, with Undo in its place, until they undo it', async () => {
      await render(spendInbox());
      expect(pillCount('notices')).toBe('1');
      dismissButton()!.focus();
      dismissButton()!.click();
      await settle();
      // At once, before the server answers: its line, with Undo, and nothing left to count.
      expect(text('[data-testid=inbox-dismissed]')).toBe(
        'Dismissed The company’s spend crossed its alert amount · It comes back if it changes. Undo',
      );
      expect(q('[data-testid=inbox-notice]')).toBeNull();
      expect(pillCount('notices')).toBe('0');
      const undo = q('[data-testid=inbox-dismissed-undo]')!;
      expect(undo.getAttribute('aria-label')).toBe(
        'Undo dismissing The company’s spend crossed its alert amount',
      );
      expect(document.activeElement).toBe(undo);
      http.expectOne({ method: 'PUT', url: SPEND }).flush(null);
      await settle();
      // The inbox, read again, no longer lists it; its line keeps its place on this visit.
      await reread(anInbox({ items: [], counts: NO_COUNTS }));
      expect(q('[data-testid=inbox-dismissed]')).not.toBeNull();
      expect(q('[data-testid=inbox-empty]')).toBeNull();

      q('[data-testid=inbox-dismissed-undo]')!.click();
      await settle();
      // Undo: the row is back at once, and the inbox is read again once the server shows it.
      expect(q('[data-testid=inbox-dismissed]')).toBeNull();
      expect(q('[data-testid=inbox-notice][data-kind=COMPANY_SPEND]')).not.toBeNull();
      expect(document.activeElement).toBe(dismissButton());
      http.expectOne({ method: 'DELETE', url: SPEND }).flush(null);
      await settle();
      http.expectOne(INBOX).flush(spendInbox());
      await answerReads();
      expect(q('[data-testid=inbox-notice][data-kind=COMPANY_SPEND]')).not.toBeNull();
      expect(q('[data-testid=inbox-dismissed]')).toBeNull();
      expect(pillCount('notices')).toBe('1');
    });

    it('brings the notice back, saying why, when the server refuses it', async () => {
      await render(spendInbox());
      dismissButton()!.click();
      await settle();
      http
        .expectOne({ method: 'PUT', url: SPEND })
        .flush(problem(409, 'NOTICE_NOT_DISMISSIBLE', 'This notice asks you to act.'), {
          status: 409,
          statusText: 'Conflict',
        });
      await settle();
      expect(q('[data-testid=inbox-dismissed]')).toBeNull();
      expect(text('[data-kind=COMPANY_SPEND] [data-testid=inbox-row-error]')).toBe(
        "Couldn't dismiss it: This notice asks you to act, so it stays until that is done.",
      );
      expect(
        q('[data-kind=COMPANY_SPEND] [data-testid=inbox-row-error]')?.getAttribute('role'),
      ).toBe('alert');
      expect(pillCount('notices')).toBe('1');
    });

    it('keeps it dismissed, saying why, when the undo fails', async () => {
      await render(spendInbox());
      dismissButton()!.click();
      await settle();
      http.expectOne({ method: 'PUT', url: SPEND }).flush(null);
      await settle();
      await reread(anInbox({ items: [], counts: NO_COUNTS }));
      q('[data-testid=inbox-dismissed-undo]')!.click();
      await settle();
      http
        .expectOne({ method: 'DELETE', url: SPEND })
        .flush(problem(500, undefined, 'The server failed.'), {
          status: 500,
          statusText: 'Error',
        });
      await settle();
      expect(text('[data-testid=inbox-dismissed]')).toContain('Dismissed');
      expect(text('[data-testid=inbox-dismissed-error]')).toBe(
        "Couldn't undo: Something went wrong on the server. Try again in a moment.",
      );
      expect(q('[data-testid=inbox-notice]')).toBeNull();
    });
  });

  it('back from a sign-in, continues the waiting bindings of that system', async () => {
    await render(null, { url: `/c/c1/inbox?account=connected&system=${IDS.jira}` });
    // The page's read and the continue's own read of the inbox, if it came first.
    for (const req of http.match(INBOX)) req.flush(anInbox({ items: [INBOX_ITEMS.BINDING()] }));
    await settle(5);
    for (const req of http.match(ACCOUNTS)) req.flush({ items: [JIRA_TO_CONNECT] });
    http
      .expectOne({
        method: 'POST',
        url: `/api/companies/c1/conversations/${IDS.conversation}/bindings/${IDS.jira}/continue`,
      })
      .flush(aConversation().bindings[0]);
    await settle();
    for (const req of http.match(INBOX)) req.flush(anInbox({ items: [] }));
    await settle();
    await fixture.whenStable();
    expect(q('[data-testid=inbox-sign-in-result]')?.textContent).toContain(
      'Its conversation goes on.',
    );
  });

  it('says a sign-in that failed', async () => {
    await render(anInbox({ items: [] }), { url: '/c/c1/inbox?account=denied' });
    expect(q('[data-testid=inbox-sign-in-result]')?.textContent).toContain('You declined');
  });

  it('says when a kind holds more than the inbox lists, and counts them', async () => {
    await render(
      anInbox({
        items: [INBOX_ITEMS.FAILED()],
        counts: { ...NO_COUNTS, failed: 140 },
      }),
    );
    expect(q('[data-testid=inbox-truncated]')).not.toBeNull();
    expect(pillCount('turns')).toBe('140');
  });
});
