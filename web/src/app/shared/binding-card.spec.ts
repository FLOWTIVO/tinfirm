import { HttpTestingController } from '@angular/common/http/testing';
import { type ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { Api } from '../api/generated/api';
import type { ConversationBinding } from '../api/generated/models';
import { BrowserNavigation } from '../core/browser-navigation';
import { aBinding, anAccount, aWaitingBinding, IDS } from '../testing/fixtures';
import { problem, provideTestHttp, settle, typeInto } from '../testing/test-helpers';
import { BindingCard, bindingCardText, continueBindingAfterSignIn } from './binding-card';

type Reason = NonNullable<ConversationBinding['reason']>;

/**
 * Every reason of the contract, once: a reason the API adds and this table lacks breaks the build
 * (plan §8, fixtures typed by the generated models).
 */
const EVERY_REASON: Record<Reason, true> = {
  NONE: true,
  RECONNECT_YOURS: true,
  RECONNECT_COMPANY: true,
  CHOOSE: true,
  PICK_GRANT: true,
  SUSPENDED: true,
  SYSTEM_OFF: true,
  NOT_ENTITLED: true,
  GRANT_REMOVED: true,
  LEFT_GROUP: true,
  IDENTITY_CHANGED: true,
  HOLDER_LEFT: true,
  SYSTEM_REMOVED: true,
  ACCOUNT_REMOVED: true,
};

interface CardCase {
  readonly state: 'WAITING' | 'GONE';
  readonly reason: Reason;
  /** Its border and title in orange: the reader acts. */
  readonly orange: boolean;
  /** Sign in with the system, and paste a token. */
  readonly connect: boolean;
  /** The grants offered. */
  readonly grants: number;
  /** The button that turns it into a connect of one's own. */
  readonly reconnect: string | null;
  /** "Start a new conversation with …". */
  readonly fresh: boolean;
}

/** The card by reason, each in the state the API raises it in (plan §4.1's table of binding cards). */
const CARD_CASES: readonly CardCase[] = [
  {
    state: 'WAITING',
    reason: 'NONE',
    orange: true,
    connect: true,
    grants: 0,
    reconnect: null,
    fresh: false,
  },
  {
    state: 'WAITING',
    reason: 'RECONNECT_YOURS',
    orange: true,
    connect: true,
    grants: 0,
    reconnect: null,
    fresh: false,
  },
  {
    state: 'WAITING',
    reason: 'PICK_GRANT',
    orange: true,
    connect: false,
    grants: 1,
    reconnect: 'Connect my own instead',
    fresh: false,
  },
  {
    state: 'WAITING',
    reason: 'CHOOSE',
    orange: true,
    connect: false,
    grants: 1,
    reconnect: 'Sign in again',
    fresh: false,
  },
  {
    state: 'WAITING',
    reason: 'RECONNECT_COMPANY',
    orange: false,
    connect: false,
    grants: 0,
    reconnect: null,
    fresh: false,
  },
  {
    state: 'WAITING',
    reason: 'SUSPENDED',
    orange: false,
    connect: false,
    grants: 0,
    reconnect: null,
    fresh: false,
  },
  {
    state: 'WAITING',
    reason: 'SYSTEM_OFF',
    orange: false,
    connect: false,
    grants: 0,
    reconnect: null,
    fresh: false,
  },
  {
    state: 'WAITING',
    reason: 'NOT_ENTITLED',
    orange: false,
    connect: false,
    grants: 0,
    reconnect: null,
    fresh: false,
  },
  {
    state: 'GONE',
    reason: 'GRANT_REMOVED',
    orange: false,
    connect: false,
    grants: 0,
    reconnect: null,
    fresh: true,
  },
  {
    state: 'GONE',
    reason: 'LEFT_GROUP',
    orange: false,
    connect: false,
    grants: 0,
    reconnect: null,
    fresh: true,
  },
  {
    state: 'GONE',
    reason: 'IDENTITY_CHANGED',
    orange: false,
    connect: false,
    grants: 0,
    reconnect: null,
    fresh: true,
  },
  {
    state: 'GONE',
    reason: 'HOLDER_LEFT',
    orange: false,
    connect: false,
    grants: 0,
    reconnect: null,
    fresh: true,
  },
  {
    state: 'GONE',
    reason: 'SYSTEM_REMOVED',
    orange: false,
    connect: false,
    grants: 0,
    reconnect: null,
    fresh: true,
  },
  {
    state: 'GONE',
    reason: 'ACCOUNT_REMOVED',
    orange: false,
    connect: false,
    grants: 0,
    reconnect: null,
    fresh: true,
  },
];

const GRANT_CHOICE = {
  grantId: IDS.analystsGrant,
  groupId: IDS.analysts,
  groupName: 'Analysts',
  credentialName: 'svc-reader',
  access: 'READ' as const,
};

const BASE = `/api/companies/c1/conversations/${IDS.conversation}/bindings/${IDS.jira}`;

describe('bindingCardText (plan §4.1)', () => {
  const ctx = { agentName: 'Business analyst', mine: true };
  const waiting = (b: Partial<ConversationBinding>) => aWaitingBinding(b);

  it.each`
    reason                 | title                                         | actionable
    ${'NONE'}              | ${'Connect Jira to continue'}                 | ${true}
    ${'RECONNECT_YOURS'}   | ${'Sign in to Jira again'}                    | ${true}
    ${'PICK_GRANT'}        | ${'Act on Jira through:'}                     | ${true}
    ${'CHOOSE'}            | ${'Your Jira account needs signing in again'} | ${true}
    ${'RECONNECT_COMPANY'} | ${'Jira waits for an admin'}                  | ${false}
    ${'SUSPENDED'}         | ${'Jira waits for an admin'}                  | ${false}
    ${'SYSTEM_OFF'}        | ${'Jira is switched off'}                     | ${false}
    ${'NOT_ENTITLED'}      | ${'Jira waits for the trigger’s author'}      | ${false}
  `('a waiting binding of reason $reason', ({ reason, title, actionable }) => {
    const text = bindingCardText(
      waiting({ reason, holderName: 'svc-reader', choices: [GRANT_CHOICE] }),
      ctx,
    );
    expect(text).toMatchObject({ title, actionable });
  });

  it('names who must act when it is not the reader', () => {
    expect(
      bindingCardText(waiting({ reason: 'RECONNECT_COMPANY', credentialName: 'svc-dev' }), ctx)
        ?.body,
    ).toBe(
      'An admin must sign the credential svc-dev in again, or store a new token for it. Admins were told.',
    );
    // What the admin must do follows from the credential's state when the binding names it.
    const company = (credentialKind: 'TOKEN' | 'OAUTH', credentialEnabled: boolean) =>
      bindingCardText(
        waiting({
          reason: 'RECONNECT_COMPANY',
          credentialName: 'CI bot',
          credentialKind,
          credentialEnabled,
        }),
        ctx,
      )?.body;
    expect(company('TOKEN', false)).toBe(
      'An admin must enable the credential CI bot. Admins were told.',
    );
    expect(company('TOKEN', true)).toBe(
      'An admin must store a new token for the credential CI bot. Admins were told.',
    );
    expect(company('OAUTH', true)).toBe(
      'An admin must sign the credential CI bot in again. Admins were told.',
    );
    expect(
      bindingCardText(
        waiting({ reason: 'NOT_ENTITLED', holderName: 'svc-dev', credentialName: 'CI bot' }),
        ctx,
      )?.body,
    ).toBe(
      'The trigger’s author may no longer use svc-dev with CI bot. They or an admin change the trigger’s accounts.',
    );
    expect(
      bindingCardText(waiting({ reason: 'NONE' }), { ...ctx, mine: false, personName: 'Alice' }),
    ).toMatchObject({ title: 'Waiting for Alice', actionable: false });
  });

  it('offers the grant in the CHOOSE sentence', () => {
    expect(bindingCardText(waiting({ reason: 'CHOOSE', choices: [GRANT_CHOICE] }), ctx)?.body).toBe(
      'Sign in again, or use the grant of Analysts (read-only).',
    );
  });

  it.each`
    reason                | why
    ${'GRANT_REMOVED'}    | ${'(the grant was removed)'}
    ${'LEFT_GROUP'}       | ${'(you are no longer in Analysts)'}
    ${'IDENTITY_CHANGED'} | ${'(the account now signs in as someone else)'}
    ${'SYSTEM_REMOVED'}   | ${'(the system was removed)'}
    ${'HOLDER_LEFT'}      | ${'(svc-reader is gone)'}
    ${'SUSPENDED'}        | ${'(svc-reader is suspended)'}
    ${'ACCOUNT_REMOVED'}  | ${'(the trigger’s accounts no longer include one on it)'}
    ${null}               | ${'can no longer be used in this conversation.'}
  `('says why a binding is gone: $reason', ({ reason, why }) => {
    const text = bindingCardText(
      aBinding({
        state: 'GONE',
        reason,
        groupName: 'Analysts',
        holderName: 'svc-reader',
        systemName: 'GitHub',
      }),
      ctx,
    );
    expect(text?.title).toBe('GitHub can no longer be used here');
    expect(text?.body).toContain(why);
    expect(text?.actionable).toBe(false);
  });

  it('says nothing of a bound binding', () => {
    expect(bindingCardText(aBinding(), ctx)).toBeNull();
  });
});

describe('BindingCard', () => {
  let fixture: ComponentFixture<BindingCard>;
  let http: HttpTestingController;
  let el: HTMLElement;
  let changed: ConversationBinding[];
  const navigation = { assign: vi.fn() };

  async function render(binding: ConversationBinding, inputs: Record<string, unknown> = {}) {
    navigation.assign.mockReset();
    TestBed.configureTestingModule({
      imports: [BindingCard],
      providers: [
        provideRouter([]),
        ...provideTestHttp(),
        { provide: BrowserNavigation, useValue: navigation },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    vi.spyOn(TestBed.inject(Router), 'url', 'get').mockReturnValue('/c/c1/conversations/cv1');
    fixture = TestBed.createComponent(BindingCard);
    fixture.componentRef.setInput('binding', binding);
    fixture.componentRef.setInput('companyId', 'c1');
    fixture.componentRef.setInput('conversationId', IDS.conversation);
    fixture.componentRef.setInput('agentName', 'Business analyst');
    fixture.componentRef.setInput('agentId', IDS.agent);
    for (const [name, value] of Object.entries(inputs)) fixture.componentRef.setInput(name, value);
    changed = [];
    fixture.componentInstance.changed.subscribe((b) => changed.push(b));
    el = fixture.nativeElement;
    await fixture.whenStable();
  }

  const q = (testId: string) => el.querySelector<HTMLElement>(`[data-testid=${testId}]`);

  afterEach(() => http.verify());

  it('connects with a pasted token, then continues the binding', async () => {
    await render(aWaitingBinding());
    const card = q('binding-card')!;
    expect(card.classList).toContain('border-human');
    expect(card.querySelector('h3')?.textContent?.trim()).toBe('Connect Jira to continue');
    // Without a sign-in of the system's app, a token is the only way.
    expect(q('binding-sign-in')).toBeNull();

    q('binding-paste-token')!.click();
    await fixture.whenStable();
    typeInto(q('binding-token'), 'jira-token');
    await fixture.whenStable();
    el.querySelector('form')!.dispatchEvent(new Event('submit', { cancelable: true }));
    await settle();

    const store = http.expectOne({
      method: 'PUT',
      url: `/api/companies/c1/me/accounts/${IDS.jira}/token`,
    });
    expect(store.request.body).toEqual({ token: 'jira-token' });
    store.flush(anAccount({ systemId: IDS.jira, kind: 'TOKEN' }));
    await settle();
    const bound = aBinding({
      systemId: IDS.jira,
      systemName: 'Jira',
      text: 'Jira: your own account',
    });
    http.expectOne({ method: 'POST', url: `${BASE}/continue` }).flush(bound);
    await settle();

    expect(changed).toEqual([bound]);
  });

  it('focuses the token field when it opens, and takes a binding settled meanwhile as settled', async () => {
    await render(aWaitingBinding());
    const outdated: unknown[] = [];
    fixture.componentInstance.outdated.subscribe(() => outdated.push(true));
    q('binding-paste-token')!.click();
    await fixture.whenStable();
    expect(document.activeElement).toBe(q('binding-token'));
    typeInto(q('binding-token'), 'jira-token');
    await fixture.whenStable();
    el.querySelector('form')!.dispatchEvent(new Event('submit', { cancelable: true }));
    await settle();
    http
      .expectOne(`/api/companies/c1/me/accounts/${IDS.jira}/token`)
      .flush(anAccount({ systemId: IDS.jira, kind: 'TOKEN' }));
    await settle();
    // The server continued the binding itself when the account was connected.
    http
      .expectOne({ method: 'POST', url: `${BASE}/continue` })
      .flush(problem(409, 'BINDING_NOT_WAITING'), { status: 409, statusText: 'Conflict' });
    await settle();
    expect(outdated).toEqual([true]);
    expect(changed).toEqual([]);
    expect(q('binding-error')).toBeNull();
  });

  it('starts a sign-in that comes back to this page', async () => {
    await render(aWaitingBinding({ reason: 'RECONNECT_YOURS' }), { signInAvailable: true });
    expect(q('binding-card')?.querySelector('h3')?.textContent?.trim()).toBe(
      'Sign in to Jira again',
    );
    q('binding-sign-in')!.click();
    await settle();
    const start = http.expectOne({
      method: 'POST',
      url: `/api/companies/c1/me/accounts/${IDS.jira}/oauth`,
    });
    expect(start.request.body).toEqual({ returnTo: '/c/c1/conversations/cv1' });
    start.flush({ authorizationUrl: 'https://auth.example/authorize?x=1' });
    await settle();
    expect(navigation.assign).toHaveBeenCalledWith('https://auth.example/authorize?x=1');
  });

  it('picks a grant, or connects one’s own instead', async () => {
    await render(aWaitingBinding({ reason: 'PICK_GRANT', choices: [GRANT_CHOICE] }));
    const grant = q('binding-grant')!;
    expect(grant.textContent?.trim()).toBe('Analysts: the credential svc-reader, read-only');
    grant.click();
    await settle();
    const choose = http.expectOne({ method: 'POST', url: `${BASE}/choose` });
    expect(choose.request.body).toEqual({ grantId: IDS.analystsGrant });
    choose.flush(aBinding({ systemId: IDS.jira, how: 'CHOSEN' }));
    await settle();
    expect(changed).toHaveLength(1);

    q('binding-reconnect')!.click();
    await settle();
    const own = http.expectOne({ method: 'POST', url: `${BASE}/choose` });
    expect(own.request.body).toEqual({ reconnect: true });
    own.flush(aWaitingBinding());
    await settle();
    expect(changed).toHaveLength(2);
  });

  it('says why an action failed', async () => {
    await render(aWaitingBinding({ reason: 'CHOOSE', choices: [GRANT_CHOICE] }));
    expect(q('binding-reconnect')?.textContent?.trim()).toBe('Sign in again');
    q('binding-grant')!.click();
    await settle();
    http
      .expectOne({ method: 'POST', url: `${BASE}/choose` })
      .flush(problem(400, 'GRANT_NOT_OFFERED'), { status: 400, statusText: 'Bad Request' });
    await settle();
    expect(q('binding-error')?.textContent).toContain('This grant cannot be used here now.');
    expect(changed).toEqual([]);
  });

  it('stays neutral, without actions, while an admin must act', async () => {
    await render(aWaitingBinding({ reason: 'RECONNECT_COMPANY', credentialName: 'svc-reader' }));
    const card = q('binding-card')!;
    expect(card.classList).not.toContain('border-human');
    expect(card.querySelector('.text-human')).toBeNull();
    expect(card.querySelector('button')).toBeNull();
    expect(card.textContent).toContain('An admin must sign the credential svc-reader in again');
  });

  it('shows the Owner who reads it that its person acts', async () => {
    await render(aWaitingBinding(), { mine: false, personName: 'Alice Novak' });
    expect(q('binding-card')?.querySelector('h3')?.textContent?.trim()).toBe(
      'Waiting for Alice Novak',
    );
    expect(q('binding-card')?.querySelector('button')).toBeNull();
  });

  it('offers a new conversation once the binding is gone', async () => {
    await render(aBinding({ state: 'GONE', reason: 'GRANT_REMOVED' }));
    const link = q('binding-new-conversation')!;
    expect(link.textContent?.trim()).toBe('Start a new conversation with Business analyst');
    expect(link.getAttribute('href')).toBe(`/c/c1/conversations/new?agentId=${IDS.agent}`);
  });

  it('draws nothing for a bound binding', async () => {
    await render(aBinding());
    expect(q('binding-card')).toBeNull();
  });

  it('has a case for every reason of the contract', () => {
    expect(CARD_CASES.map((c) => c.reason).sort()).toEqual(Object.keys(EVERY_REASON).sort());
  });

  it.each(CARD_CASES)(
    'a $state binding of reason $reason offers what its reader can do',
    async ({ state, reason, orange, connect, grants, reconnect, fresh }) => {
      await render(
        aWaitingBinding({
          state,
          reason,
          holderName: 'svc-reader',
          groupName: 'Analysts',
          choices: [GRANT_CHOICE],
        }),
        { signInAvailable: true },
      );
      const card = q('binding-card')!;
      expect(card.getAttribute('data-reason')).toBe(reason);
      expect(card.classList.contains('border-human')).toBe(orange);
      expect(card.querySelector('.text-human') !== null).toBe(orange);
      expect(card.querySelector('h3')?.textContent?.trim()).toBeTruthy();
      expect(q('binding-sign-in') !== null).toBe(connect);
      expect(q('binding-paste-token') !== null).toBe(connect);
      expect(el.querySelectorAll('[data-testid=binding-grant]')).toHaveLength(grants);
      expect(q('binding-reconnect')?.textContent?.trim() ?? null).toBe(reconnect);
      expect(q('binding-new-conversation') !== null).toBe(fresh);
      if (!orange && !fresh) expect(card.querySelector('button, a')).toBeNull();
    },
  );

  it('asks for the token the system takes, and says a refusal in the system’s words', async () => {
    await render(aWaitingBinding());
    q('binding-paste-token')!.click();
    await fixture.whenStable();
    const input = q('binding-token') as HTMLInputElement;
    expect(input.placeholder).toBe('you@example.com:api-token');
    expect(el.querySelector('label')?.textContent?.trim()).toBe('Email and API token for Jira');
    typeInto(input, 'ana@acme.test:wrong-token');
    await fixture.whenStable();
    el.querySelector('form')!.dispatchEvent(new Event('submit', { cancelable: true }));
    await settle();
    http
      .expectOne({ method: 'PUT', url: `/api/companies/c1/me/accounts/${IDS.jira}/token` })
      .flush(
        problem(
          400,
          'ACCOUNT_REFUSED',
          'The system refused the token: Jira answered 401 when asked whose the token is',
        ),
        {
          status: 400,
          statusText: 'Bad Request',
        },
      );
    await settle();
    const error = q('binding-error')!;
    expect(error.textContent?.trim()).toBe('Jira answered 401 when asked whose the token is.');
    expect(el.textContent).not.toContain('wrong-token');
    // The form stays, to try another token; nothing was continued.
    expect(q('binding-token')).not.toBeNull();
    expect(changed).toEqual([]);
  });

  it('returns from a sign-in to this page without an earlier outcome', async () => {
    await render(aWaitingBinding(), { signInAvailable: true });
    vi.spyOn(TestBed.inject(Router), 'url', 'get').mockReturnValue(
      '/c/c1/conversations/cv1?account=denied',
    );
    q('binding-sign-in')!.click();
    await settle();
    const start = http.expectOne({
      method: 'POST',
      url: `/api/companies/c1/me/accounts/${IDS.jira}/oauth`,
    });
    expect(start.request.body).toEqual({ returnTo: '/c/c1/conversations/cv1' });
    start.flush(problem(409, 'OAUTH_NOT_AVAILABLE'), { status: 409, statusText: 'Conflict' });
    await settle();
    expect(q('binding-error')?.textContent).toContain('Jira offers no sign-in here');
    expect(navigation.assign).not.toHaveBeenCalled();
  });
});

describe('continueBindingAfterSignIn', () => {
  let http: HttpTestingController;
  let api: Api;
  const where = { companyId: 'c1', conversationId: IDS.conversation };

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [...provideTestHttp()] });
    http = TestBed.inject(HttpTestingController);
    api = TestBed.inject(Api);
  });
  afterEach(() => http.verify());

  it('continues the waiting binding of the system just connected', async () => {
    const pending = continueBindingAfterSignIn(
      api,
      where,
      { connected: true, systemId: IDS.jira, message: 'Your account is connected.' },
      [aBinding(), aWaitingBinding()],
    );
    await settle();
    const bound = aBinding({ systemId: IDS.jira, systemName: 'Jira' });
    http.expectOne({ method: 'POST', url: `${BASE}/continue` }).flush(bound);
    await expect(pending).resolves.toEqual(bound);
  });

  it('answers nothing when the server continued the binding first', async () => {
    const pending = continueBindingAfterSignIn(
      api,
      where,
      { connected: true, systemId: IDS.jira, message: 'Your account is connected.' },
      [aWaitingBinding()],
    );
    await settle();
    http
      .expectOne({ method: 'POST', url: `${BASE}/continue` })
      .flush(problem(409, 'BINDING_NOT_WAITING'), { status: 409, statusText: 'Conflict' });
    await expect(pending).resolves.toBeNull();
  });

  it.each`
    case                         | result
    ${'no sign-in'}              | ${null}
    ${'a refused sign-in'}       | ${{ connected: false, systemId: null, message: 'You declined.' }}
    ${'a system without a card'} | ${{ connected: true, systemId: IDS.github, message: 'Connected.' }}
  `('does nothing after $case', async ({ result }) => {
    await expect(
      continueBindingAfterSignIn(api, where, result, [aBinding(), aWaitingBinding()]),
    ).resolves.toBeNull();
  });
});
