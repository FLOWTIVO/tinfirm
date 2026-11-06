import { HttpTestingController } from '@angular/common/http/testing';
import { type ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import type { ConversationBinding, Decision } from '../api/generated/models';
import { BrowserNavigation } from '../core/browser-navigation';
import {
  aBinding,
  aDecision,
  aFixedBinding,
  anApproval,
  AT,
  aWaitingBinding,
  IDS,
} from '../testing/fixtures';
import { fakeNotify, provideTestHttp, settle } from '../testing/test-helpers';
import {
  bindingTextFor,
  PinnedCards,
  pinnedCounts,
  type PinnedJump,
  pinnedItems,
} from './pinned-cards';

const QUESTION = aDecision({ createdAt: '2026-10-12T08:15:00Z' });
const APPROVAL = anApproval({ createdAt: '2026-10-12T08:18:00Z' });
const CONNECT = aWaitingBinding({ waitingSince: '2026-10-12T08:16:00Z' });
const ADMIN_MUST = aWaitingBinding({
  id: 'b-admin',
  systemId: IDS.github,
  reason: 'RECONNECT_COMPANY',
  holderName: 'svc-reader',
});

describe('pinned cards: what is pinned (plan §4.1)', () => {
  it('pins open decisions the reader decides and bindings they act on, longest waiting first', () => {
    const items = pinnedItems([APPROVAL, QUESTION], [CONNECT, ADMIN_MUST, aBinding()], {
      agentName: 'Business analyst',
      mine: true,
    });
    expect(items.map((i) => `${i.kind}:${i.id}`)).toEqual([
      'decision:d1',
      'binding:b-jira',
      'decision:d2',
    ]);
  });

  it.each<[string, Decision]>([
    ['decided', anApproval({ status: 'DECIDED', outcome: 'APPROVED' })],
    ['cancelled', anApproval({ status: 'CANCELLED' })],
    ['someone else’s to decide', anApproval({ mayDecide: false })],
  ])('leaves out a decision that is %s', (_, decision) => {
    expect(pinnedItems([decision], [], { agentName: 'A', mine: true })).toEqual([]);
  });

  it.each<[string, Decision[], ConversationBinding[], string]>([
    ['one question', [QUESTION], [], '1 open question'],
    [
      'two questions and a review',
      [
        QUESTION,
        aDecision({ id: 'd3' }),
        anApproval({ id: 'd4', subject: { type: 'RESULT', runId: 'run1', writes: [] } }),
      ],
      [],
      '2 open questions · 1 review',
    ],
    [
      'two accounts',
      [],
      [CONNECT, { ...CONNECT, id: 'b2', systemId: IDS.github, systemName: 'GitHub' }],
      '2 accounts to connect',
    ],
  ])('counts what waits: %s', (_case, decisions, bindings, says) => {
    const items = pinnedItems(decisions, bindings, { agentName: 'Business analyst', mine: true });
    expect(pinnedCounts(items)).toBe(says);
  });

  it('names the account a tool call runs as from the bound binding of its system', () => {
    const jira = aBinding({ systemId: IDS.jira, text: 'Jira: via Analysts, read-only' });
    expect(bindingTextFor(anApproval(), [aBinding(), jira])).toBe('Jira: via Analysts, read-only');
    expect(bindingTextFor(anApproval(), [aWaitingBinding()])).toBeNull();
    expect(bindingTextFor(aDecision(), [jira])).toBeNull();
    // A trigger's binding through a credential lent read-only says so too.
    expect(bindingTextFor(anApproval(), [aFixedBinding()])).toBe(
      "Jira: svc-reader's reader-bot, chosen by the trigger, read-only",
    );
  });
});

describe('PinnedCards', () => {
  let fixture: ComponentFixture<PinnedCards>;
  let el: HTMLElement;
  let http: HttpTestingController;

  async function render(
    decisions: Decision[],
    bindings: ConversationBinding[] = [],
    inputs: Record<string, unknown> = {},
  ) {
    TestBed.configureTestingModule({
      imports: [PinnedCards],
      providers: [
        provideRouter([]),
        ...provideTestHttp(),
        fakeNotify().provider,
        { provide: BrowserNavigation, useValue: { assign: vi.fn() } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(PinnedCards);
    const ref = fixture.componentRef;
    ref.setInput('decisions', decisions);
    ref.setInput('bindings', bindings);
    ref.setInput('companyId', 'c1');
    ref.setInput('conversationId', IDS.conversation);
    ref.setInput('agentName', 'Business analyst');
    for (const [name, value] of Object.entries(inputs)) ref.setInput(name, value);
    el = fixture.nativeElement;
    await fixture.whenStable();
  }

  const all = (sel: string) => Array.from(el.querySelectorAll<HTMLElement>(sel));

  afterEach(() => http.verify());

  it('renders nothing while nothing waits on the reader', async () => {
    await render([anApproval({ mayDecide: false })], [aBinding()]);
    expect(el.querySelector('[data-testid=pinned-cards]')).toBeNull();
  });

  it('pins nothing for the Owner reading someone else’s conversation', async () => {
    await render([QUESTION], [CONNECT], { mine: false });
    expect(el.querySelector('[data-testid=pinned-cards]')).toBeNull();
  });

  it('opens the oldest card and folds the others into a line each', async () => {
    await render([QUESTION, APPROVAL], [CONNECT]);
    expect(el.querySelector('#pinned-cards-title')?.textContent?.trim()).toBe('Needs you');
    expect(el.querySelector('[data-testid=pinned-counts]')?.textContent?.trim()).toBe(
      '· 1 open question · 1 approval · 1 account to connect',
    );
    // A strip with a header, then the cards: no card sits inside a box of its own.
    const strip = el.querySelector('[data-testid=pinned-cards]')!;
    expect(strip.className).not.toMatch(/\bborder\b|\brounded|bg-/);
    expect(all('app-decision-card')).toHaveLength(1);
    const folded = all('[data-testid=pinned-folded]').map((b) => b.textContent?.trim());
    expect(folded).toHaveLength(2);
    expect(folded[0]).toContain('Jira');
    expect(folded[1]).toBe('Approval · Business analyst wants to comment on SHOP-241');
    all('[data-testid=pinned-folded]')[1].click();
    await fixture.whenStable();
    expect(all('app-decision-card')).toHaveLength(2);
    (el.querySelector('[data-testid=pinned-toggle]') as HTMLElement).click();
    await fixture.whenStable();
    expect(all('app-binding-card')).toHaveLength(1);
    expect(all('[data-testid=pinned-folded]')).toHaveLength(0);
  });

  it('passes the bound account to a tool call’s card', async () => {
    await render([APPROVAL], [aBinding({ systemId: IDS.jira, text: 'Jira: your own account' })]);
    expect(el.querySelector('[data-testid=decision-account]')?.textContent?.trim()).toBe(
      'Jira: your own account',
    );
  });

  it('asks the page to scroll to where a card was raised', async () => {
    await render([QUESTION]);
    const jumps: PinnedJump[] = [];
    fixture.componentInstance.jump.subscribe((j) => jumps.push(j));
    (el.querySelector('[data-testid=pinned-jump]') as HTMLElement).click();
    expect(jumps).toEqual([{ kind: 'decision', id: 'd1' }]);
  });

  it('reviews a trigger’s result with W5’s card', async () => {
    await render([
      anApproval({
        approverGroupName: 'Analysts',
        subject: { type: 'RESULT', runId: 'run1', summary: 'Ready.', writes: ['Move SHOP-241'] },
      }),
    ]);
    expect(el.querySelector('app-result-review-card [data-testid=result-review]')).not.toBeNull();
    expect(el.querySelector('app-decision-card')).toBeNull();
  });

  describe('on a narrow screen', () => {
    it('pins one line with the oldest card’s action, which asks to scroll to it', async () => {
      await render([QUESTION, APPROVAL], [], { compact: true });
      expect(all('app-decision-card')).toHaveLength(0);
      const line = el.querySelector('[data-testid=pinned-cards]')!;
      expect(line.getAttribute('data-compact')).toBe('true');
      expect(el.querySelector('[data-testid=pinned-summary]')?.getAttribute('aria-label')).toBe(
        '2 need you · Question: Should the brief cover the mobile app too?',
      );
      const jumps: PinnedJump[] = [];
      fixture.componentInstance.jump.subscribe((j) => jumps.push(j));
      const act = el.querySelector<HTMLElement>('[data-testid=pinned-act]')!;
      expect(act.textContent?.trim()).toBe('Answer');
      act.click();
      expect(jumps).toEqual([{ kind: 'decision', id: 'd1' }]);
    });

    it.each<[string, Decision[], ConversationBinding[], string]>([
      ['an approval', [APPROVAL], [], 'Decide'],
      ['an account', [], [CONNECT], 'Connect'],
      [
        'a result',
        [anApproval({ subject: { type: 'RESULT', runId: 'run1', writes: [] } })],
        [],
        'Review',
      ],
    ])('names the action for %s', async (_case, decisions, bindings, action) => {
      await render(decisions, bindings, { compact: true });
      expect(el.querySelector('[data-testid=pinned-act]')?.textContent?.trim()).toBe(action);
    });

    it('unfolds the cards from the line, and folds them again', async () => {
      await render([QUESTION], [], { compact: true });
      el.querySelector<HTMLElement>('[data-testid=pinned-summary]')!.click();
      await fixture.whenStable();
      expect(all('app-decision-card')).toHaveLength(1);
      el.querySelector<HTMLElement>('[data-testid=pinned-fold]')!.click();
      await fixture.whenStable();
      expect(all('app-decision-card')).toHaveLength(0);
      expect(el.querySelector('[data-testid=pinned-summary]')).not.toBeNull();
    });

    it('keeps the full cards on a wide screen, and folds them on request', async () => {
      await render([QUESTION]);
      expect(all('app-decision-card')).toHaveLength(1);
      el.querySelector<HTMLElement>('[data-testid=pinned-fold]')!.click();
      await fixture.whenStable();
      expect(all('app-decision-card')).toHaveLength(0);
      expect(el.querySelector('[data-testid=pinned-cards]')?.getAttribute('data-compact')).toBe(
        'true',
      );
    });
  });

  it('hands on what the cards did', async () => {
    await render([APPROVAL]);
    const decided: Decision[] = [];
    fixture.componentInstance.decided.subscribe((d) => decided.push(d));
    (el.querySelector('[data-testid=decide-APPROVED]') as HTMLElement).click();
    await settle();
    http
      .expectOne({ method: 'POST', url: '/api/companies/c1/decisions/d2/decide' })
      .flush(anApproval({ status: 'DECIDED', outcome: 'APPROVED', decidedAt: AT.finished }));
    await settle();
    expect(decided).toHaveLength(1);
  });
});
