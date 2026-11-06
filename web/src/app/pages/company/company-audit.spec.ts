import { HttpTestingController, type TestRequest } from '@angular/common/http/testing';
import { type ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import type { AuditEvent, Licence } from '../../api/generated/models';
import { aCommunityLicence, aMember, aServiceIdentity, IDS } from '../../testing/fixtures';
import {
  fakeCurrentCompany,
  fakeLicence,
  provideTestHttp,
  settle,
  submitForm,
  typeInto,
} from '../../testing/test-helpers';
import { words } from '../../testing/words';
import { CompanyAudit } from './company-audit';
import { dayKey } from './audit/days';

const EVENTS = '/api/companies/c1/audit-events';
const ALICE = '0192f0c4-7d1e-7c3a-9b4e-2f1a3c5d7e9f';
const ALICE_PRINCIPAL = '5b1e0c4a-7d1e-7c3a-9b4e-2f1a3c5d7e9f';
const CONVERSATION = '9ca950af-0000-7c3a-9b4e-2f1a3c5d7e9f';

function anEvent(overrides: Partial<AuditEvent> = {}): AuditEvent {
  return {
    id: 'e1',
    seq: 42,
    occurredAt: '2026-10-12T08:00:00Z',
    actor: { type: 'USER', id: ALICE, display: 'Alice Analyst' },
    action: 'runner_host.updated',
    targetType: 'runner_host',
    targetId: 'h-1',
    targetName: 'build-01',
    details: { conversationId: CONVERSATION },
    hash: 'a'.repeat(64),
    prevHash: 'b'.repeat(64),
    ...overrides,
  };
}

describe('CompanyAudit', () => {
  let fixture: ComponentFixture<CompanyAudit>;
  let http: HttpTestingController;
  let el: HTMLElement;
  let navigate: ReturnType<typeof vi.spyOn>;

  /**
   * Opens the page with the URL's filters; answers the pickers' members and service accounts,
   * the checks and the events. The server names the targets: no list of agents or systems is read.
   */
  async function open(
    query: Record<string, string> = {},
    events: AuditEvent[] = [anEvent()],
    licence: Licence | null = null,
  ) {
    TestBed.configureTestingModule({
      imports: [CompanyAudit],
      providers: [
        provideRouter([]),
        ...provideTestHttp(),
        fakeCurrentCompany('ADMIN').provider,
        fakeLicence(licence).provider,
      ],
    });
    http = TestBed.inject(HttpTestingController);
    navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    fixture = TestBed.createComponent(CompanyAudit);
    for (const [key, value] of Object.entries(query)) fixture.componentRef.setInput(key, value);
    el = fixture.nativeElement;
    await settle();
    http
      .expectOne((r) => r.url === '/api/companies/c1/members')
      .flush({
        items: [
          aMember({ userId: ALICE, displayName: 'Alice Analyst', principalId: ALICE_PRINCIPAL }),
        ],
      });
    http
      .expectOne((r) => r.url === '/api/companies/c1/service-identities')
      .flush({ items: [aServiceIdentity()] });
    http.expectNone((r) => r.url === '/api/companies/c1/agents');
    http.expectNone((r) => r.url === '/api/companies/c1/systems');
    // The daily checks are Pro's: Community reads none.
    for (const checks of http.match((r) => r.url === `${EVENTS}/checks`)) {
      checks.flush({ items: [] });
    }
    const req = http.expectOne((r) => r.url === EVENTS);
    req.flush({ items: events });
    await settle();
    await fixture.whenStable();
    return req;
  }

  const q = (testId: string) => el.querySelector<HTMLElement>(`[data-testid=${testId}]`);
  const all = (testId: string) =>
    Array.from(el.querySelectorAll<HTMLElement>(`[data-testid=${testId}]`));
  const button = (name: string) =>
    Array.from(el.querySelectorAll<HTMLButtonElement>('button')).find((b) => words(b) === name);
  const lastQuery = () => navigate.mock.calls.at(-1)?.[1]?.queryParams as Record<string, unknown>;
  const overlay = () => document.querySelector('.cdk-overlay-container') as HTMLElement;
  const select = (id: string, value: string) => {
    const element = el.querySelector<HTMLSelectElement>(`#${id}`)!;
    element.value = value;
    element.dispatchEvent(new Event('change'));
  };

  afterEach(() => {
    http.verify();
    overlay()?.replaceChildren();
  });

  it('under Community shows the log without filters, verification, checks or export (ADR-0077)', async () => {
    await open({}, [anEvent()], aCommunityLicence());

    expect(q('verify-chain')).toBeNull();
    expect(el.querySelector('app-audit-export')).toBeNull();
    expect(el.querySelector('app-audit-checks')).toBeNull();
    expect(el.querySelector('app-audit-filter-bar')).toBeNull();
    const note = q('audit-community')!;
    expect(words(note.querySelector('[data-testid=edition-badge-AUDIT_TOOLS] [aria-hidden]'))).toBe(
      'Pro',
    );
    expect(words(note)).toContain(
      'Every change is still recorded and chained. Community shows the last 90 days. Filters, chain verification and the daily checks need a Pro licence.',
    );
    expect(all('audit-event')).toHaveLength(1);
  });

  it('under Community reads the log without the filters of a saved link', async () => {
    const req = await open(
      { action: 'tool.', actorType: 'USER', actorId: ALICE },
      [anEvent()],
      aCommunityLicence(),
    );
    expect(req.request.params.has('action')).toBe(false);
    expect(req.request.params.has('actorId')).toBe(false);
  });

  it('keeps the export while the licence’s grace for it lasts', async () => {
    await open(
      {},
      [anEvent()],
      aCommunityLicence({ state: 'EXPIRED', auditExportUntil: '2099-01-01' }),
    );

    expect(q('verify-chain')).toBeNull();
    expect(el.querySelector('app-audit-export')).not.toBeNull();
  });

  it.each<[string, Record<string, string>, Record<string, string>, string[]]>([
    ['no filter', {}, {}, []],
    ['an action prefix', { action: 'tool.' }, { action: 'tool.' }, ['Action tool.']],
    [
      'a person and two days',
      { actorType: 'USER', actorId: ALICE, from: '2026-10-01', to: '2026-10-02' },
      {
        actorType: 'USER',
        actorId: ALICE,
        from: new Date(2026, 9, 1).toISOString(),
        to: new Date(2026, 9, 3).toISOString(),
      },
      ['By Alice Analyst', '1 Oct to 2 Oct'],
    ],
    [
      'a conversation and whom they are about',
      { conversationId: CONVERSATION, principalId: ALICE_PRINCIPAL },
      { conversationId: CONVERSATION, principalId: ALICE_PRINCIPAL },
      ['In conversation 9ca950af', 'About Alice Analyst'],
    ],
    [
      'a conversation by its key',
      { conversationKey: 'Shop-1-dev-2' },
      { conversationKey: 'shop-1-dev-2' },
      ['In conversation shop-1-dev-2'],
    ],
    [
      'a target the events name',
      { targetType: 'runner_host', targetId: 'h-1' },
      { targetType: 'runner_host', targetId: 'h-1' },
      ['On runner host build-01'],
    ],
    ['a filter that is no filter (an edited link)', { conversationId: 'SHOP-1' }, {}, []],
    ['a key that is none (an edited link)', { conversationKey: 'shop 1' }, {}, []],
  ])('asks the API with the URL’s filters: %s', async (_, query, params, chips) => {
    const req: TestRequest = await open(query);
    const sent: Record<string, string> = {};
    for (const key of req.request.params.keys()) {
      if (key !== 'limit') sent[key] = req.request.params.get(key)!;
    }
    expect(sent).toEqual(params);
    expect(all('filter-chip').map((c) => words(c))).toEqual(chips);
  });

  it('applies an action on Enter, and refuses one that is none', async () => {
    await open();
    typeInto(el.querySelector('#audit-action'), 'Tool called');
    submitForm(el.querySelector('form[role=search]'));
    await settle();
    expect(navigate).not.toHaveBeenCalled();
    expect(words(el.querySelector('#audit-action-error'))).toBe(
      'Lower-case letters, digits, dots and underscores, such as member. or tool.called.',
    );
    expect(el.querySelector('#audit-action')?.getAttribute('aria-invalid')).toBe('true');

    typeInto(el.querySelector('#audit-action'), ' member. ');
    submitForm(el.querySelector('form[role=search]'));
    await settle();
    expect(lastQuery()).toEqual({
      action: 'member.',
      actorType: null,
      actorId: null,
      targetType: null,
      targetId: null,
      when: null,
      from: null,
      to: null,
      conversationId: null,
      conversationKey: null,
      principalId: null,
    });
  });

  it('filters by who acted, at once', async () => {
    await open({ action: 'tool.' });
    select('audit-actor', `USER:${ALICE}`);
    await settle();
    expect(lastQuery()).toMatchObject({ action: 'tool.', actorType: 'USER', actorId: ALICE });
    select('audit-actor', 'SYSTEM');
    await settle();
    expect(lastQuery()).toMatchObject({ actorType: 'SYSTEM', actorId: null });
  });

  it('filters by a time relative to now, and lets go of it on a second press', async () => {
    await open({ from: '2026-10-01' });
    const hour = el.querySelector<HTMLButtonElement>('[data-testid=when] [data-value=hour]')!;
    expect(hour.getAttribute('aria-pressed')).toBe('false');
    hour.click();
    await settle();
    expect(lastQuery()).toMatchObject({ when: 'hour', from: null, to: null });

    TestBed.resetTestingModule();
    await open({ when: 'hour' });
    const pressed = el.querySelector<HTMLButtonElement>('[data-testid=when] [data-value=hour]')!;
    expect(pressed.getAttribute('aria-pressed')).toBe('true');
    pressed.click();
    await settle();
    expect(lastQuery()).toMatchObject({ when: null });
  });

  it('filters by days picked on the calendar, both included', async () => {
    await open({ when: 'today' });
    const custom = q('custom-days')!;
    expect(custom.getAttribute('aria-expanded')).toBe('false');
    custom.click();
    await settle();
    await fixture.whenStable();
    expect(custom.getAttribute('aria-expanded')).toBe('true');
    const popover = overlay().querySelector<HTMLElement>('[data-testid=day-range]')!;
    expect(popover.getAttribute('role')).toBe('dialog');
    const days = popover.querySelectorAll<HTMLButtonElement>('[data-day]');
    const first = days[0].getAttribute('data-day')!;
    const last = days[days.length - 1].getAttribute('data-day')!;
    days[days.length - 1].click();
    await settle();
    expect(words(popover.querySelector('[role=status]'))).toMatch(/Pick the last day\.$/);
    expect(popover.querySelector<HTMLButtonElement>('[data-testid=apply-days]')!.disabled).toBe(
      true,
    );
    days[0].click();
    await settle();
    expect(words(popover.querySelector('[role=status]'))).toMatch(/both days included\.$/);
    popover.querySelector<HTMLButtonElement>('[data-testid=apply-days]')!.click();
    await settle();
    expect(lastQuery()).toMatchObject({ when: null, from: first, to: last });
    expect(custom.getAttribute('aria-expanded')).toBe('false');
    expect(first <= dayKey(new Date())).toBe(true);
  });

  it('opens the other filters, and filters by a target and whom it concerns', async () => {
    await open();
    expect(el.querySelector('#audit-more-filters')).toBeNull();
    q('toggle-more-filters')!.click();
    await settle();
    expect(q('toggle-more-filters')!.getAttribute('aria-expanded')).toBe('true');
    select('audit-target-type', 'runner_host');
    await settle();
    expect(lastQuery()).toMatchObject({ targetType: 'runner_host' });
    select('audit-principal', IDS.svcReader);
    await settle();
    expect(lastQuery()).toMatchObject({ principalId: IDS.svcReader });
  });

  it('refuses a conversation’s key that is none', async () => {
    await open({ targetType: 'runner_host' });
    expect(words(q('more-filter-count'))).toBe('1');
    typeInto(el.querySelector('#audit-conversation'), 'analyst 4');
    submitForm(el.querySelector('form[role=search]'));
    await settle();
    expect(navigate).not.toHaveBeenCalled();
    expect(words(el.querySelector('#audit-conversation-error'))).toBe(
      'Type a conversation’s key, such as shop-123-dev-1.',
    );
    expect(el.querySelector('#audit-conversation')?.getAttribute('aria-invalid')).toBe('true');
  });

  it('filters by a conversation’s key as typed, or by an id pasted from its address', async () => {
    await open({ conversationId: CONVERSATION });
    const field = el.querySelector<HTMLInputElement>('#audit-conversation')!;
    expect(field.value).toBe(CONVERSATION);
    expect(field.placeholder).toBe('Its key, such as shop-123-dev-1');
    typeInto(field, ' Shop-1-dev-2 ');
    submitForm(el.querySelector('form[role=search]'));
    await settle();
    expect(lastQuery()).toMatchObject({ conversationKey: 'shop-1-dev-2', conversationId: null });

    TestBed.resetTestingModule();
    const req = await open({ conversationKey: 'shop-1-dev-2' });
    expect(req.request.params.get('conversationKey')).toBe('shop-1-dev-2');
    expect(el.querySelector<HTMLInputElement>('#audit-conversation')!.value).toBe('shop-1-dev-2');
    typeInto(el.querySelector('#audit-conversation'), CONVERSATION);
    submitForm(el.querySelector('form[role=search]'));
    await settle();
    expect(lastQuery()).toMatchObject({ conversationId: CONVERSATION, conversationKey: null });
  });

  it('says when no event is about a key nobody knows', async () => {
    await open({ conversationKey: 'nobody-1' }, []);
    expect(words(q('audit-empty'))).toContain('No event matches');
    expect(all('filter-chip').map((c) => words(c))).toEqual(['In conversation nobody-1']);
  });

  it('removes one filter with its chip, or every one with Clear', async () => {
    await open({ action: 'tool.', when: 'week' });
    expect(all('filter-chip').map((c) => words(c))).toEqual(['Action tool.', 'Last 7 days']);
    all('filter-chip')[1].querySelector<HTMLButtonElement>('button')!.click();
    await settle();
    expect(lastQuery()).toMatchObject({ action: 'tool.', when: null });
    button('Clear')!.click();
    await settle();
    expect(Object.values(lastQuery()).every((v) => v === null)).toBe(true);
  });

  it.each<[string, Record<string, string>, AuditEvent]>([
    ['By Alice Analyst', { actorType: 'USER', actorId: ALICE }, anEvent()],
    ['On this runner host', { targetType: 'runner_host', targetId: 'h-1' }, anEvent()],
    ['In this conversation', { conversationId: CONVERSATION }, anEvent()],
    [
      'About Alice Analyst (left)',
      { principalId: ALICE_PRINCIPAL },
      anEvent({
        action: 'principal.left',
        targetType: 'principal',
        targetId: ALICE_PRINCIPAL,
        targetName: 'Alice Analyst (left)',
        details: {},
      }),
    ],
  ])('narrows to an event’s “%s”', async (label, filter, event) => {
    await open({ action: 'runner_host.' }, [event]);
    el.querySelector<HTMLButtonElement>('[aria-controls="audit-details-e1"]')!.click();
    await settle();
    await fixture.whenStable();
    all('quick-filter')
      .find((b) => words(b) === label)!
      .click();
    await settle();
    expect(lastQuery()).toMatchObject({ action: 'runner_host.', ...filter });
  });

  it('reads each event in a calm row, by day', async () => {
    const today = new Date();
    today.setHours(9, 2, 31, 0);
    await open({}, [
      anEvent({
        id: 'e3',
        seq: 1216,
        occurredAt: today.toISOString(),
        actor: { type: 'SYSTEM', id: null, display: 'system' },
        action: 'model_key.created',
        targetType: 'principal_model_key',
        targetId: ALICE_PRINCIPAL,
        targetName: 'Alice Analyst',
        details: { principalId: ALICE_PRINCIPAL },
      }),
      anEvent({ id: 'e2', seq: 1215, occurredAt: today.toISOString() }),
      anEvent({
        seq: 1188,
        actor: { type: 'AGENT', id: IDS.agent, display: 'Business analyst' },
        action: 'system.paused',
        targetType: 'system',
        targetId: IDS.github,
        targetName: 'GitHub',
        details: {},
      }),
      anEvent({
        id: 'e0',
        seq: 1101,
        actor: { type: 'USER', id: ALICE, display: 'erased person' },
        action: 'principal.left',
        targetType: 'principal',
        targetId: '7c2d0e4a-7d1e-7c3a-9b4e-2f1a3c5d7e9f',
        targetName: null,
        details: {},
      }),
    ]);
    const headings = Array.from(el.querySelectorAll('h3')).map((h) => words(h));
    expect(headings[0]).toMatch(/^Today \w{3} \d+ \w{3} 2 events$/);
    expect(headings).toHaveLength(2);
    const rows = all('audit-event').map((r) => words(r.querySelector('button')));
    expect(rows[0]).toBe(
      '09:02:31 T Tinfirm model_key. created Model key of Alice Analyst · 5b1e0c4a 1216',
    );
    expect(rows[1]).toBe(
      '09:02:31 AA Alice Analyst runner_host. updated Runner host build-01 · h-1 1215',
    );
    expect(rows[2]).toMatch(
      /^\d\d:\d\d:\d\d BA Business analyst system. paused System GitHub · sys-gith 1188$/,
    );
    // An erased person: no name as the actor, nor as the target.
    expect(rows[3]).toMatch(
      /^\d\d:\d\d:\d\d \? Erased person principal. left Principal · 7c2d0e4a 1101$/,
    );
    expect(words(q('audit-end'))).toBe('4 events · start of the chain');
    expect(el.textContent).not.toContain('SYSTEM');
  });

  it('opens an event in place: its ids, hashes and details', async () => {
    await open({}, [anEvent({ seq: 1, prevHash: '0'.repeat(64) })]);
    el.querySelector<HTMLButtonElement>('[aria-controls="audit-details-e1"]')!.click();
    await settle();
    const details = q('audit-details')!;
    expect(words(details)).toContain('Actor Alice Analyst · member ' + ALICE);
    expect(words(details)).toContain('Target Runner host build-01 runner_host h-1');
    expect(words(details)).toContain('The chain’s start: no event comes before it.');
    expect(q('event-hash')?.textContent).toBe('a'.repeat(64));
    expect(
      Array.from(details.querySelectorAll('button[aria-label]')).map((b) =>
        b.getAttribute('aria-label'),
      ),
    ).toEqual(['Copy the hash', 'Copy the previous hash', 'Copy the details']);
    expect(JSON.parse(q('event-details')!.textContent!)).toEqual({
      conversationId: CONVERSATION,
    });
  });

  it('says when no event matches the filters, and clears them', async () => {
    await open({ action: 'tool.' }, []);
    expect(words(q('audit-empty'))).toContain(
      'No event matches Change the filters, or clear them to see every event.',
    );
    button('Clear the filters')!.click();
    await settle();
    expect(Object.values(lastQuery()).every((v) => v === null)).toBe(true);
  });

  it('verifies the chain and says so under the status line', async () => {
    await open();
    q('verify-chain')!.click();
    await settle();
    http
      .expectOne((r) => r.url === `${EVENTS}/verification`)
      .flush({ valid: true, checkedEvents: 1216 });
    await settle();
    expect(words(q('verification'))).toMatch(
      /^Chain intact. Verified at \d\d:\d\d:\d\d: recomputed 1,216 events. Every hash matches, so no event was changed or removed.$/,
    );
  });
});
