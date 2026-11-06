import { HttpTestingController } from '@angular/common/http/testing';
import { type ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import type { Decision, DecisionSubject, DecisionSubjectType } from '../api/generated/models';
import { aDecision, anApproval, IDS } from '../testing/fixtures';
import { fakeNotify, problem, provideTestHttp, settle, typeInto } from '../testing/test-helpers';
import {
  approverText,
  DecisionCard,
  decidedLabel,
  decisionActions,
  decisionTitle,
  prettyArguments,
} from './decision-card';

const URL = '/api/companies/c1/decisions/d2/decide';
const AT_PURGED = '2026-10-12T09:00:00Z';

const subject = (s: Partial<DecisionSubject> & Pick<DecisionSubject, 'type'>): Decision =>
  anApproval({ subject: s });

const REPOSITORY = subject({ type: 'REPOSITORY', repositoryName: 'acme/docs', access: 'WRITE' });
const HOST = subject({ type: 'HOST', host: '*.pypi.org' });
const CHANGE_REQUEST = subject({ type: 'CHANGE_REQUEST', repositoryName: 'acme/docs' });
const RESULT = subject({
  type: 'RESULT',
  runId: 'run1',
  summary: 'The checkout fails for guests because the **session** expires.',
  writes: [
    'The checkout fails for guests because the session expires.',
    'Move SHOP-241 to *In review*',
  ],
});
/** A trigger's team decides it (package 4). */
const TEAM = anApproval({
  approverPrincipalId: null,
  approverName: null,
  approverGroupId: 'g-analysts',
  approverGroupName: 'Analysts',
});

/**
 * Every subject of an approval, and a question, with the part of the card that shows it: a subject
 * the API adds and this table lacks breaks the build (plan §8).
 */
const EVERY_SUBJECT: Record<
  DecisionSubjectType | 'QUESTION',
  { decision: Decision; part: string }
> = {
  QUESTION: { decision: aDecision(), part: 'decision-options' },
  TOOL_CALL: { decision: anApproval(), part: 'decision-what' },
  REPOSITORY: { decision: REPOSITORY, part: 'decision-repository' },
  HOST: { decision: HOST, part: 'decision-host' },
  CHANGE_REQUEST: { decision: CHANGE_REQUEST, part: 'decision-repository' },
  RESULT: { decision: RESULT, part: 'decision-writes' },
};
const SUBJECT_CASES = Object.entries(EVERY_SUBJECT).map(([type, c]) => ({ type, ...c }));

describe('decision words (plan §4.1)', () => {
  it.each`
    decision                                                                   | title
    ${aDecision()}                                                             | ${'Business analyst asks'}
    ${anApproval()}                                                            | ${'Business analyst wants to comment on SHOP-241'}
    ${subject({ type: 'TOOL_CALL', tool: 'add_comment', systemName: 'Jira' })} | ${'Business analyst wants to call add_comment on Jira'}
    ${REPOSITORY}                                                              | ${'Business analyst wants repository acme/docs'}
    ${HOST}                                                                    | ${'Business analyst wants to reach *.pypi.org'}
    ${CHANGE_REQUEST}                                                          | ${'Business analyst wants to open a change request on acme/docs'}
    ${RESULT}                                                                  | ${'Business analyst’s result waits for a review before anything is written'}
  `('titles $decision.subject.type', ({ decision, title }) => {
    expect(decisionTitle(decision)).toBe(title);
  });

  it('says what was decided', () => {
    expect(decidedLabel(aDecision({ status: 'DECIDED', outcome: 'ANSWERED' }))).toBe('Answered');
    expect(decidedLabel(anApproval({ status: 'DECIDED', outcome: 'DENIED' }))).toBe('Denied');
    expect(decidedLabel({ ...HOST, status: 'DECIDED', outcome: 'APPROVED', scope: 'AGENT' })).toBe(
      'Allowed for the agent, for good',
    );
    expect(decidedLabel(anApproval({ status: 'CANCELLED' }))).toBe('Cancelled');
    expect(decidedLabel({ ...RESULT, status: 'DECIDED', outcome: 'APPROVED' })).toBe(
      'Approved, written',
    );
    expect(decidedLabel({ ...RESULT, status: 'DECIDED', outcome: 'DENIED' })).toBe('Sent back');
  });

  it('names who decides, as the server says', () => {
    expect(approverText(anApproval())).toBe('Alice Novak');
    expect(approverText(TEAM)).toBe('the team Analysts');
    expect(
      approverText(
        aDecision({ approverPrincipalId: null, approverName: null, approverGroupId: null }),
      ),
    ).toBe('an answer from the trigger’s author or team, or on the issue');
  });

  it('sends a result back only with a note', () => {
    expect(decisionActions(RESULT, true)).toEqual([
      expect.objectContaining({ id: 'APPROVED', outcome: 'APPROVED' }),
      expect.objectContaining({ id: 'DENIED', outcome: 'DENIED', needsText: true }),
    ]);
  });

  it('offers “for good” only to whoever may edit the agent', () => {
    expect(decisionActions(HOST, false).map((a) => a.id)).toEqual([
      'APPROVED-CONVERSATION',
      'DENIED',
    ]);
    expect(decisionActions(REPOSITORY, true).map((a) => a.id)).toEqual([
      'APPROVED-CONVERSATION',
      'APPROVED-AGENT',
      'DENIED',
    ]);
    expect(decisionActions(anApproval(), true).map((a) => a.id)).toEqual(['APPROVED', 'DENIED']);
    expect(decisionActions(aDecision(), true).map((a) => a.id)).toEqual(['ANSWERED']);
  });

  it('prints a tool call’s arguments', () => {
    expect(prettyArguments({ issue: 'SHOP-241' })).toBe('{\n  "issue": "SHOP-241"\n}');
    expect(prettyArguments('not json')).toBe('not json');
    expect(prettyArguments(null)).toBeNull();
  });
});

describe('DecisionCard', () => {
  let fixture: ComponentFixture<DecisionCard>;
  let http: HttpTestingController;
  let el: HTMLElement;
  let decided: Decision[];
  let notify: ReturnType<typeof fakeNotify>['notify'];

  async function render(decision: Decision, inputs: Record<string, unknown> = {}) {
    const notifier = fakeNotify();
    notify = notifier.notify;
    TestBed.configureTestingModule({
      imports: [DecisionCard],
      providers: [provideRouter([]), ...provideTestHttp(), notifier.provider],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(DecisionCard);
    fixture.componentRef.setInput('decision', decision);
    fixture.componentRef.setInput('companyId', 'c1');
    for (const [name, value] of Object.entries(inputs)) fixture.componentRef.setInput(name, value);
    decided = [];
    fixture.componentInstance.decided.subscribe((d) => decided.push(d));
    el = fixture.nativeElement;
    await fixture.whenStable();
  }

  const q = (testId: string) => el.querySelector<HTMLElement>(`[data-testid=${testId}]`);

  afterEach(() => http.verify());

  it.each(SUBJECT_CASES)(
    'draws an open $type in orange, answerable by its approver',
    async ({ decision, part }) => {
      await render(decision);
      expect(q('decision')?.classList).toContain('border-human');
      expect(q(part)).not.toBeNull();
      expect(q('decision-read-only')).toBeNull();
      expect(el.querySelectorAll('[data-testid^=decide-]').length).toBeGreaterThan(0);
    },
  );

  it.each(SUBJECT_CASES)(
    'waits for its approver without mayDecide, without orange ($type)',
    async ({ decision, part }) => {
      await render({ ...decision, mayDecide: false });
      expect(q('decision')?.classList).not.toContain('border-human');
      expect(q(part)).not.toBeNull();
      expect(q('decision-read-only')?.textContent?.trim()).toBe('Waiting for Alice Novak.');
      expect(el.querySelector('[data-testid^=decide-]')).toBeNull();
      expect(el.querySelector('textarea')).toBeNull();
    },
  );

  it('names the system and the account a tool call runs as', async () => {
    await render(anApproval());
    expect(q('decision-what')?.textContent?.trim()).toBe('comment on SHOP-241');
    expect(q('decision-system')?.textContent?.trim()).toBe('Jira');
    expect(q('decision-account')?.textContent?.trim()).toBe(
      'Jira: the account this conversation uses there',
    );
  });

  it('waits for a trigger’s team, and tells a member of it that it is decided here', async () => {
    await render({ ...TEAM, mayDecide: false });
    expect(q('decision-read-only')?.textContent?.trim()).toBe('Waiting for the team Analysts.');
    fixture.destroy();
    TestBed.resetTestingModule();
    await render(TEAM);
    expect(q('decision-team')?.textContent).toContain('whoever decides first decides for everyone');
    expect(q('decision-team')?.textContent).toContain('never through an AI client');
  });

  it('shows what a result would write, and sends it back with a note', async () => {
    await render(RESULT);
    expect(q('decision-summary')?.querySelector('strong')?.textContent).toBe('session');
    expect(q('decision-writes')?.querySelectorAll('li')).toHaveLength(2);
    expect(q('decision-writes')?.querySelector('em')?.textContent).toBe('In review');
    const back = q('decide-DENIED') as HTMLButtonElement;
    expect(back.textContent?.trim()).toBe('Send back');
    expect(back.disabled).toBe(true);
    expect(q('decision-needs-note')).not.toBeNull();
    typeInto(el.querySelector('textarea'), 'Name the session timeout.');
    await fixture.whenStable();
    expect(back.disabled).toBe(false);
    back.click();
    await settle();
    const req = http.expectOne({ method: 'POST', url: URL });
    expect(req.request.body).toEqual({ outcome: 'DENIED', note: 'Name the session timeout.' });
    req.flush({ ...RESULT, status: 'DECIDED', outcome: 'DENIED' });
    await settle();
    expect(decided).toHaveLength(1);
  });

  it('approves a result as it is shown', async () => {
    await render(RESULT);
    expect(q('decide-APPROVED')?.textContent?.trim()).toBe('Approve and write it');
    q('decide-APPROVED')!.click();
    await settle();
    const req = http.expectOne({ method: 'POST', url: URL });
    expect(req.request.body).toEqual({ outcome: 'APPROVED' });
    req.flush({ ...RESULT, status: 'DECIDED', outcome: 'APPROVED' });
    await settle();
  });

  it('keeps one Idempotency-Key per submission, and reuses it when the same one is sent again', async () => {
    await render(anApproval());
    q('decide-APPROVED')!.click();
    await settle();
    const first = http.expectOne({ method: 'POST', url: URL });
    const key = first.request.headers.get('Idempotency-Key');
    expect(key).toMatch(/^[0-9a-f-]{36}$/);
    first.flush(null, { status: 503, statusText: 'Unavailable' });
    await settle();
    q('decide-APPROVED')!.click();
    await settle();
    const retry = http.expectOne({ method: 'POST', url: URL });
    expect(retry.request.headers.get('Idempotency-Key')).toBe(key);
    retry.flush(problem(409, 'AGENT_PAUSED'), { status: 409, statusText: 'Conflict' });
    await settle();
    q('decide-APPROVED')!.click();
    await settle();
    const after = http.expectOne({ method: 'POST', url: URL });
    expect(after.request.headers.get('Idempotency-Key')).not.toBe(key);
    after.flush(anApproval({ status: 'DECIDED', outcome: 'APPROVED' }));
    await settle();
  });

  it('a different submission gets a new key', async () => {
    await render(anApproval());
    q('decide-APPROVED')!.click();
    await settle();
    const first = http.expectOne({ method: 'POST', url: URL });
    const key = first.request.headers.get('Idempotency-Key');
    first.flush(null, { status: 0, statusText: 'Unknown Error' });
    await settle();
    q('decide-DENIED')!.click();
    await settle();
    const other = http.expectOne({ method: 'POST', url: URL });
    expect(other.request.headers.get('Idempotency-Key')).not.toBe(key);
    other.flush(anApproval({ status: 'DECIDED', outcome: 'DENIED' }));
    await settle();
  });

  it('says when retention removed its texts', async () => {
    await render(
      aDecision({ status: 'DECIDED', outcome: 'ANSWERED', question: null, purgedAt: AT_PURGED }),
    );
    expect(q('decision-purged')).not.toBeNull();
  });

  it('answers a question with an option and a free answer', async () => {
    await render(aDecision());
    const send = q('decide-ANSWERED') as HTMLButtonElement;
    expect(send.disabled).toBe(true);
    Array.from(el.querySelectorAll<HTMLButtonElement>('[data-testid=decision-options] button'))
      .find((b) => b.textContent?.trim() === 'Yes')!
      .click();
    typeInto(el.querySelector('textarea'), 'And the tablet.');
    await fixture.whenStable();
    send.click();
    await settle();
    const req = http.expectOne({ method: 'POST', url: '/api/companies/c1/decisions/d1/decide' });
    expect(req.request.body).toEqual({ option: 'Yes', answer: 'And the tablet.' });
    req.flush(aDecision({ status: 'DECIDED', outcome: 'ANSWERED', option: 'Yes' }));
    await settle();
    expect(decided).toHaveLength(1);
  });

  it('approves a tool call that runs as the conversation’s account, its arguments folded', async () => {
    await render(anApproval(), { bindingText: 'Jira: your own account' });
    expect(q('decision-account')?.textContent?.trim()).toBe('Jira: your own account');
    expect(el.querySelector('details')?.open).toBe(false);
    expect(q('decision-arguments')?.textContent).toContain('"issue": "SHOP-241"');
    q('decide-APPROVED')!.click();
    await settle();
    const req = http.expectOne({ method: 'POST', url: URL });
    expect(req.request.body).toEqual({ outcome: 'APPROVED' });
    req.flush(anApproval({ status: 'DECIDED', outcome: 'APPROVED', result: 'Commented.' }));
    await settle();
    expect(decided[0].result).toBe('Commented.');
  });

  it('allows a repository for good with the access picked', async () => {
    await render(REPOSITORY, { agentEditable: true });
    expect(el.querySelector('app-segmented')?.getAttribute('data-value')).toBe('WRITE');
    el.querySelector<HTMLInputElement>('#decision-access-d2-READ')!.click();
    await fixture.whenStable();
    q('decide-APPROVED-AGENT')!.click();
    await settle();
    const req = http.expectOne({ method: 'POST', url: URL });
    expect(req.request.body).toEqual({ outcome: 'APPROVED', scope: 'AGENT', access: 'READ' });
    req.flush({ ...REPOSITORY, status: 'DECIDED', outcome: 'APPROVED', scope: 'AGENT' });
    await settle();
  });

  it('denies a host with a note', async () => {
    await render(HOST);
    typeInto(el.querySelector('textarea'), 'Use the mirror.');
    await fixture.whenStable();
    q('decide-DENIED')!.click();
    await settle();
    const req = http.expectOne({ method: 'POST', url: URL });
    expect(req.request.body).toEqual({ outcome: 'DENIED', note: 'Use the mirror.' });
    req.flush({ ...HOST, status: 'DECIDED', outcome: 'DENIED' });
    await settle();
  });

  it('says when someone decided first', async () => {
    await render(anApproval());
    let outdated = 0;
    fixture.componentInstance.outdated.subscribe(() => outdated++);
    q('decide-APPROVED')!.click();
    await settle();
    http
      .expectOne({ method: 'POST', url: URL })
      .flush(problem(409, 'DECISION_DECIDED'), { status: 409, statusText: 'Conflict' });
    await settle();
    expect(notify.error).toHaveBeenCalled();
    expect(outdated).toBe(1);
  });

  describe('inline, for a host that draws the frame and the title (the inbox)', () => {
    it('says a tool call in one sentence, why, and its arguments folded', async () => {
      await render(anApproval({ reason: 'The brief is ready to share.' }), {
        appearance: 'inline',
      });
      expect(q('decision')?.getAttribute('data-appearance')).toBe('inline');
      expect(el.querySelector('h3')).toBeNull();
      expect(el.querySelector('.border-human')).toBeNull();
      expect(q('decision-what')?.textContent?.replace(/\s+/g, ' ').trim()).toBe(
        'Comment on SHOP-241 on Jira, as the account this conversation uses there.',
      );
      expect(q('decision-reason')?.textContent?.trim()).toBe(
        'Why, in Business analyst’s words: “The brief is ready to share.”',
      );
      expect(q('decision-arguments')).toBeNull();
      const toggle = q('decision-arguments-toggle')!;
      expect(toggle.getAttribute('aria-expanded')).toBe('false');
      toggle.click();
      await fixture.whenStable();
      expect(toggle.getAttribute('aria-expanded')).toBe('true');
      expect(toggle.textContent).toContain('Hide the arguments');
      expect(q('decision-arguments')?.textContent).toContain('"issue": "SHOP-241"');
      expect(el.querySelector('label')?.textContent).toContain('Note for Business analyst');
    });

    it('names the account a tool call runs as, when the conversation says it', async () => {
      await render(anApproval(), { appearance: 'inline', bindingText: 'Jira: your own account' });
      expect(q('decision-what')?.textContent?.replace(/\s+/g, ' ').trim()).toBe(
        'Comment on SHOP-241 on Jira. Runs as Jira: your own account.',
      );
    });

    it('keeps Send answer enabled and says what it lacks; Cancel asks the host to fold', async () => {
      await render(aDecision(), { appearance: 'inline' });
      let dismissed = 0;
      fixture.componentInstance.dismissed.subscribe(() => dismissed++);
      const send = q('decide-ANSWERED') as HTMLButtonElement;
      expect(send.disabled).toBe(false);
      send.click();
      await fixture.whenStable();
      expect(q('decision-missing')?.textContent?.trim()).toBe('Pick an option or write an answer.');
      expect(q('decision-missing')?.getAttribute('role')).toBe('alert');
      expect(el.querySelector('textarea')?.getAttribute('aria-describedby')).toBe(
        'decision-missing-d1',
      );
      Array.from(el.querySelectorAll<HTMLButtonElement>('[data-testid=decision-options] button'))
        .find((b) => b.textContent?.trim() === 'Yes')!
        .click();
      await fixture.whenStable();
      expect(q('decision-missing')).toBeNull();
      q('decision-cancel')!.click();
      expect(dismissed).toBe(1);
    });

    it('leaves out the question when the host’s title says it whole', async () => {
      await render(aDecision(), { appearance: 'inline', showQuestion: false });
      expect(el.querySelector('app-markdown')).toBeNull();
      fixture.destroy();
      TestBed.resetTestingModule();
      await render(aDecision(), { appearance: 'inline' });
      expect(el.querySelector('app-markdown')?.textContent).toContain('mobile app');
    });

    it('says a result goes back only with a note, once Send back is pressed without one', async () => {
      await render(RESULT, { appearance: 'inline' });
      const back = q('decide-DENIED') as HTMLButtonElement;
      expect(back.disabled).toBe(false);
      back.click();
      await fixture.whenStable();
      expect(q('decision-missing')?.textContent?.trim()).toBe(
        'Sending it back needs a note: what should change.',
      );
      typeInto(el.querySelector('textarea'), 'Name the session timeout.');
      await fixture.whenStable();
      expect(el.textContent).toContain('Your note goes to Business analyst with the decision.');
      back.click();
      await settle();
      const req = http.expectOne({ method: 'POST', url: URL });
      expect(req.request.body).toEqual({ outcome: 'DENIED', note: 'Name the session timeout.' });
      req.flush({ ...RESULT, status: 'DECIDED', outcome: 'DENIED' });
      await settle();
      expect(decided).toHaveLength(1);
    });
  });

  it('shows a decided approval without orange, with the call’s result', async () => {
    await render(
      anApproval({
        status: 'DECIDED',
        outcome: 'APPROVED',
        decidedByName: 'Alice Novak',
        note: 'Go ahead.',
        result: 'Commented on **SHOP-241**.',
      }),
      { showConversation: true },
    );
    expect(q('decision')?.classList).not.toContain('border-human');
    expect(el.querySelector('.text-human')).toBeNull();
    expect(q('decision-status')?.textContent?.trim()).toBe('Approved');
    expect(q('decision-answer')?.textContent).toContain('Go ahead.');
    expect(q('decision-result')?.querySelector('strong')?.textContent).toBe('SHOP-241');
    expect(q('decision-conversation')?.getAttribute('href')).toBe(
      `/c/c1/conversations/${IDS.conversation}`,
    );
  });
});
