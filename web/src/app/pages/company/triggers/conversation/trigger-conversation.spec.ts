import { HttpTestingController } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import type { Conversation, Decision } from '../../../../api/generated/models';
import { aConversation, aMessage, anApproval } from '../../../../testing/fixtures';
import { problem, provideTestHttp, settle, typeInto } from '../../../../testing/test-helpers';
import { words } from '../../../../testing/words';
import { ExternalMessage } from './external-message';
import { ResultReviewCard } from '../../../../shared/result-review-card';
import { TrackerPlaceNotice } from './tracker-place-notice';

const SUBJECT = {
  key: 'SHOP-241',
  systemName: 'Jira',
  url: 'https://jira.acme.test/browse/SHOP-241',
};

/** A team's review of a result (a `RESULT` approval), as the server raises it. */
function aReview(overrides: Partial<Decision> = {}): Decision {
  return anApproval({
    approverPrincipalId: null,
    approverGroupId: 'g-analysts',
    approverGroupName: 'Analysts',
    subject: {
      type: 'RESULT',
      runId: 'run1',
      summary: 'The brief is ready.',
      writes: [
        'A comment on SHOP-241:\n\n**Business analyst (Tinfirm agent):** The brief is ready.',
        'Move SHOP-241 through “In review”',
      ],
    },
    ...overrides,
  });
}

describe('a trigger’s conversation', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideRouter([]), ...provideTestHttp()] });
  });

  describe('ExternalMessage', () => {
    function render(overrides = {}) {
      const fixture = TestBed.createComponent(ExternalMessage);
      fixture.componentRef.setInput(
        'message',
        aMessage({
          kind: 'EXTERNAL',
          authorName: 'Petra Jira',
          authorPrincipalId: null,
          externalId: '10001',
          body: 'Please also check **the refunds**.',
          ...overrides,
        }),
      );
      fixture.componentRef.setInput('subject', SUBJECT);
      fixture.detectChanges();
      return fixture.nativeElement as HTMLElement;
    }

    it('reads as a comment on the issue, by its author at the system, as their words', () => {
      const el = render();
      expect(words(el.querySelector('p'))).toContain('Petra Jira commented on Jira SHOP-241');
      expect(el.querySelector('strong')?.textContent).toBe('the refunds');
      expect(el.querySelector('a')?.getAttribute('href')).toBe(SUBJECT.url);
      expect(el.querySelector('[data-testid=external-answers]')).toBeNull();
    });

    it('says when it answered the open questions, and shows a purged one as its marker', () => {
      expect(
        render({ answersOpenQuestions: true }).querySelector('[data-testid=external-answers]'),
      ).not.toBeNull();
      TestBed.resetTestingModule();
      TestBed.configureTestingModule({ providers: [provideRouter([])] });
      const el = render({ purgedAt: '2026-10-12T09:00:00Z', body: '(removed)' });
      expect(el.querySelector('[data-testid=external-purged]')?.textContent).toBe('(removed)');
    });

    it('reads as a webhook’s delivery, without an author, in a webhook trigger’s conversation', () => {
      const fixture = TestBed.createComponent(ExternalMessage);
      fixture.componentRef.setInput(
        'message',
        aMessage({
          kind: 'EXTERNAL',
          authorName: null,
          authorPrincipalId: null,
          body: 'Order 1042 failed.',
        }),
      );
      fixture.componentRef.setInput('subject', {
        key: 'ORD-1042',
        systemName: 'Shop events',
        url: 'https://shop.test/orders/1042',
      });
      fixture.componentRef.setInput('place', 'WEBHOOK');
      fixture.detectChanges();
      const el = fixture.nativeElement as HTMLElement;
      expect(words(el.querySelector('p'))).toContain('Shop events sent about ORD-1042');
      expect(words(el.querySelector('a'))).toBe('open ORD-1042');
      expect(el.querySelector('a')?.getAttribute('href')).toBe('https://shop.test/orders/1042');
    });

    it('never renders the comment’s HTML (ADR-0047)', () => {
      const el = render({ body: '<img src=x onerror="alert(1)"> hi' });
      expect(el.querySelector('img[onerror]')).toBeNull();
    });
  });

  describe('ResultReviewCard', () => {
    let http: HttpTestingController;

    async function render(decision: Decision) {
      http = TestBed.inject(HttpTestingController);
      const fixture = TestBed.createComponent(ResultReviewCard);
      fixture.componentRef.setInput('decision', decision);
      fixture.componentRef.setInput('companyId', 'c1');
      const decided: Decision[] = [];
      fixture.componentInstance.decided.subscribe((d) => decided.push(d));
      await settle();
      return { el: fixture.nativeElement as HTMLElement, fixture, decided };
    }

    afterEach(() => http?.verify());

    it('shows exactly what would be written, and approves it', async () => {
      const { el, decided } = await render(aReview());
      const card = el.querySelector('[data-testid=result-review]')!;
      expect(card.className).toContain('border-human');
      expect(words(el.querySelector('[data-testid=review-writes] p'))).toBe('What will be written');
      // One box, each write its own item under what it is.
      const writes = Array.from(el.querySelectorAll('[data-testid=review-writes] li'));
      expect(writes.map((w) => words(w.querySelector('[data-testid=write-label]')))).toEqual([
        'A comment on SHOP-241',
        'A transition of SHOP-241',
      ]);
      expect(words(el.querySelector('[data-write=comment] .markdown'))).toBe(
        'Business analyst (Tinfirm agent): The brief is ready.',
      );
      expect(words(el.querySelector('[data-write=action] .markdown'))).toBe(
        'Move SHOP-241 through “In review”',
      );
      // Returning needs a note.
      expect(el.querySelector<HTMLButtonElement>('[data-testid=return-result]')!.disabled).toBe(
        true,
      );
      el.querySelector<HTMLButtonElement>('[data-testid=approve-result]')!.click();
      await settle();
      const req = http.expectOne('/api/companies/c1/decisions/d2/decide');
      expect(req.request.body).toEqual({ outcome: 'APPROVED' });
      req.flush(aReview({ status: 'DECIDED', outcome: 'APPROVED', mayDecide: false }));
      await settle();
      expect(decided[0].outcome).toBe('APPROVED');
    });

    it('returns it with a note', async () => {
      const { el } = await render(aReview());
      typeInto(el.querySelector('textarea'), 'Name the refunds too.');
      await settle();
      el.querySelector<HTMLButtonElement>('[data-testid=return-result]')!.click();
      await settle();
      const req = http.expectOne('/api/companies/c1/decisions/d2/decide');
      expect(req.request.body).toEqual({ outcome: 'DENIED', note: 'Name the refunds too.' });
      req.flush(problem(409, 'DECISION_DECIDED'), { status: 409, statusText: 'Conflict' });
      await settle();
      expect(words(el.querySelector('[role=alert]'))).toBe(
        'This was already decided, or it was cancelled.',
      );
    });

    it('labels each write by its kind and target when the server gives them', async () => {
      const { el } = await render(
        aReview({
          subject: {
            type: 'RESULT',
            writes: ['Move SHOP-241 through “In review”'],
            writeItems: [
              {
                kind: 'TRANSITION',
                target: 'SHOP-241',
                value: 'In review',
                text: 'Move SHOP-241 through “In review”',
              },
            ],
          },
        }),
      );
      expect(words(el.querySelector('[data-testid=write-label]'))).toBe('A transition of SHOP-241');
      expect(words(el.querySelector('[data-write=action] .markdown'))).toBe(
        'Moves SHOP-241 to “In review”',
      );
    });

    it('shows a link it writes by its words', async () => {
      const { el } = await render(
        aReview({
          subject: {
            type: 'RESULT',
            writes: ['Link SHOP-241 to this conversation in Tinfirm (https://t.test/c/c1/x)'],
          },
        }),
      );
      expect(words(el.querySelector('[data-testid=write-label]'))).toBe('A link on SHOP-241');
      const link = el.querySelector('[data-write=action] a')!;
      expect(link.textContent).toBe('Link SHOP-241 to this conversation in Tinfirm');
      expect(link.getAttribute('href')).toBe('https://t.test/c/c1/x');
    });

    it('claims nothing about the issue when the server leaves out what it writes', async () => {
      const { el } = await render(aReview({ subject: { type: 'RESULT' } }));
      expect(el.querySelector('[data-testid=review-writes]')).toBeNull();
      expect(el.textContent).not.toContain('Nothing is written');
    });

    it.each<[string, Partial<Decision>, string]>([
      ['waits for its team, neutral', { mayDecide: false }, 'Waiting for Analysts to review it.'],
      [
        'says it was approved',
        { status: 'DECIDED', outcome: 'APPROVED', mayDecide: false, decidedByName: 'Carol' },
        'Approved by Carol: written to the issue.',
      ],
      [
        'says it was returned, with the note',
        {
          status: 'DECIDED',
          outcome: 'DENIED',
          mayDecide: false,
          note: 'Too long',
          decidedByName: 'Carol',
        },
        'Returned by Carol: “Too long”',
      ],
    ])('%s', async (_case, overrides, says) => {
      const { el } = await render(aReview(overrides));
      expect(
        words(el.querySelector('[data-testid=review-waiting], [data-testid=review-outcome]')),
      ).toBe(says);
      expect(el.querySelector('[data-testid=result-review]')!.className).not.toContain(
        'border-human',
      );
      expect(el.querySelector('[data-testid=approve-result]')).toBeNull();
    });
  });

  describe('TrackerPlaceNotice', () => {
    function render(conversation: Partial<Conversation>, openQuestions = 0) {
      const fixture = TestBed.createComponent(TrackerPlaceNotice);
      fixture.componentRef.setInput('conversation', aConversation(conversation));
      fixture.componentRef.setInput('openQuestions', openQuestions);
      fixture.detectChanges();
      return fixture.nativeElement as HTMLElement;
    }

    const trigger = {
      triggerId: 't-prepare',
      name: 'Prepare assigned issues',
      authorPrincipalId: 'p-alice',
      chainDepth: 0,
    };

    it.each<[string, Partial<Conversation>, number, string | null]>([
      ['a personal conversation', { place: 'TINFIRM_THREAD' }, 0, null],
      [
        'an issue without questions',
        { place: 'TRACKER_ISSUE', trigger, subject: SUBJECT },
        0,
        'People talk on the issue: comment there to tell the agent more.',
      ],
      [
        'an issue with an open question',
        { place: 'TRACKER_ISSUE', trigger, subject: SUBJECT },
        1,
        'here you can answer its open questions, and your answer is posted there.',
      ],
      [
        'a schedule',
        { place: 'SCHEDULE', trigger, subject: null },
        0,
        'Its results stay in Tinfirm, with its team.',
      ],
      [
        'a webhook’s delivery',
        {
          place: 'WEBHOOK',
          trigger,
          subject: { key: 'ORD-1042', systemName: 'Shop events', url: null },
        },
        0,
        'Started by Prepare assigned issues on a webhook’s delivery from Shop events about ORD-1042. Its results stay in Tinfirm',
      ],
    ])('on %s', (_case, conversation, questions, says) => {
      const el = render(conversation, questions);
      const notice = el.querySelector('[data-testid=place-notice]');
      if (says === null) expect(notice).toBeNull();
      else expect(words(notice)).toContain(says);
    });
  });
});
