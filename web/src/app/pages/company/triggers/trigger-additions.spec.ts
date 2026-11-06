import { HttpTestingController } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import type { InboxTrigger } from '../../../api/generated/models';
import {
  anInboxItem,
  anInboxTrigger,
  aTrigger,
  aTriggerPoll,
  aTriggerSummary,
  IDS,
} from '../../../testing/fixtures';
import { serve } from '../../../testing/serve';
import { provideTestHttp, settle } from '../../../testing/test-helpers';
import { words } from '../../../testing/words';
import { IdentityTriggers } from './identity-triggers';
import { InboxTriggerItem } from './inbox-trigger-item';
import { SystemTriggers } from './system-triggers';

const API = '/api/companies/c1';

describe('the trigger additions to other pages', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideRouter([]), ...provideTestHttp()] });
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => http.verify());

  describe('InboxTriggerItem', () => {
    it.each<[Partial<InboxTrigger>, string, boolean]>([
      [{ reason: 'PAUSED', detail: 'ACCOUNT_BROKEN' }, 'Paused: an account it acts with', true],
      [{ reason: 'WAITING', detail: 'COMPANY_STOPPED' }, 'stopped the company’s runs', false],
      [{ reason: 'WIDENING', detail: null }, 'may do more', true],
      [{ reason: 'TO_REASSIGN', detail: null }, 'until an admin reassigns it', true],
      [{ reason: 'WRITE_BACK_DROPPED', count: 1 }, 'A result could not', false],
    ])('%o', async (trigger, says, orange) => {
      const fixture = TestBed.createComponent(InboxTriggerItem);
      fixture.componentRef.setInput(
        'item',
        anInboxItem({
          kind: 'TRIGGER',
          decision: null,
          trigger: anInboxTrigger({ detail: null, ...trigger }),
        }),
      );
      fixture.componentRef.setInput('companyId', 'c1');
      await settle();
      // A new version to accept reads the trigger for its versions.
      for (const req of http.match(`${API}/triggers/t-prepare`)) req.flush(aTrigger());
      await fixture.whenStable();
      const el = fixture.nativeElement as HTMLElement;
      const link = el.querySelector('[data-testid=inbox-row-link]')!;
      expect(link.getAttribute('href')).toBe('/c/c1/triggers/t-prepare');
      expect(words(link)).toBe('Open trigger');
      expect(words(el)).toContain('Prepare assigned issues');
      expect(words(el)).toContain(says);
      expect(el.querySelector('[data-testid=inbox-notice]')?.getAttribute('data-tone')).toBe(
        orange ? 'human' : 'muted',
      );
    });

    it('takes the notes to review straight to the trigger’s memory', async () => {
      const fixture = TestBed.createComponent(InboxTriggerItem);
      fixture.componentRef.setInput(
        'item',
        anInboxItem({
          kind: 'TRIGGER',
          decision: null,
          trigger: anInboxTrigger({ reason: 'MEMORY_REVIEW', detail: null, count: 2 }),
        }),
      );
      fixture.componentRef.setInput('companyId', 'c1');
      await settle();
      await fixture.whenStable();
      const el = fixture.nativeElement as HTMLElement;
      const link = el.querySelector('[data-testid=inbox-row-link]')!;
      expect(link.getAttribute('href')).toBe('/c/c1/triggers/t-prepare#memory');
      expect(words(link)).toBe('Review notes');
      expect(words(el)).toContain('2 notes for its memory wait for your review');
      expect(el.querySelector('[data-testid=inbox-notice]')?.getAttribute('data-tone')).toBe(
        'human',
      );
    });
  });

  describe('IdentityTriggers', () => {
    it('lists the triggers acting with its accounts or paid by its budget, and only those', async () => {
      const fixture = TestBed.createComponent(IdentityTriggers);
      fixture.componentRef.setInput('companyId', 'c1');
      fixture.componentRef.setInput('principalId', IDS.svcReader);
      fixture.componentRef.setInput('name', 'svc-reader');
      const served = await serve(http, {
        [`GET ${API}/triggers`]: (req: {
          request: { params: URLSearchParams | { get(k: string): string | null } };
        }) =>
          req.request.params.get('accountHolderPrincipalId')
            ? {
                items: [
                  aTriggerSummary(),
                  aTriggerSummary({ id: 't-rows', name: 'Rows only', payerPrincipalId: IDS.alice }),
                ],
              }
            : {
                items: [
                  aTriggerSummary(),
                  aTriggerSummary({
                    id: 't-own',
                    name: 'Alice’s own',
                    payerPrincipalId: IDS.alice,
                  }),
                  aTriggerSummary({
                    id: 't-paid',
                    name: 'Paid only',
                    payerPrincipalId: IDS.svcReader,
                  }),
                ],
              },
      });
      expect(
        served.some((r) => r.request.params.get('accountHolderPrincipalId') === IDS.svcReader),
      ).toBe(true);
      const el = fixture.nativeElement as HTMLElement;
      const rows = [...el.querySelectorAll('[data-trigger]')];
      expect(rows.map((r) => r.getAttribute('data-trigger'))).toEqual([
        't-paid',
        't-prepare',
        't-rows',
      ]);
      expect(words(rows[1])).toContain(
        'Acts with what it borrows, paid by its budget · by Alice Novak',
      );
      expect(words(rows[0])).toContain('Paid by its budget');
      expect(words(rows[2])).toContain('Acts with what it borrows · by Alice Novak');
    });

    it('says when none uses it', async () => {
      const fixture = TestBed.createComponent(IdentityTriggers);
      fixture.componentRef.setInput('companyId', 'c1');
      fixture.componentRef.setInput('principalId', IDS.svcReader);
      fixture.componentRef.setInput('name', 'svc-reader');
      await serve(http, { [`GET ${API}/triggers`]: { items: [] } });
      expect(
        words(
          (fixture.nativeElement as HTMLElement).querySelector(
            '[data-testid=identity-triggers-empty]',
          ),
        ),
      ).toBe('No trigger acts through svc-reader or pays with its budget.');
    });
  });

  describe('SystemTriggers', () => {
    it('lists the triggers on the system and how it is read, for an admin', async () => {
      const fixture = TestBed.createComponent(SystemTriggers);
      fixture.componentRef.setInput('companyId', 'c1');
      fixture.componentRef.setInput('systemId', IDS.jira);
      fixture.componentRef.setInput('name', 'Jira');
      const served = await serve(http, {
        [`GET ${API}/triggers`]: {
          items: [aTriggerSummary({ status: 'PAUSED', pausedReason: 'SYSTEM_PAUSED' })],
        },
        [`GET ${API}/systems/${IDS.jira}/polls`]: {
          items: [
            aTriggerPoll({
              status: 'WAITING',
              lastError: 'The system is paused by an admin',
              rateLimit: 5000,
              rateRemaining: 4980,
            }),
            aTriggerPoll({
              id: 'poll2',
              accountId: 'acc-alice-jira',
              holderPrincipalId: IDS.alice,
              holderName: 'Alice Novak',
              credentialName: null,
            }),
          ],
        },
      });
      expect(served[0].request.params.get('systemId')).toBe(IDS.jira);
      const el = fixture.nativeElement as HTMLElement;
      expect(words(el.querySelector('[data-trigger]'))).toContain(
        'Paused: An admin paused its system',
      );
      // A credential's poll, for every service account that borrows it, says the credential.
      const poll = words(el.querySelector('[data-poll=poll1]'));
      expect(poll).toContain('With the credential reader-bot · Waiting · 1 trigger');
      expect(poll).toContain('4980 of 5000 requests left');
      expect(poll).toContain('The system is paused by an admin');
      // A member's own account's poll says whose it is.
      expect(words(el.querySelector('[data-poll=poll2]'))).toContain(
        'With Alice Novak’s own account · Read · 1 trigger',
      );
    });

    it('reads no polls for a member', async () => {
      const fixture = TestBed.createComponent(SystemTriggers);
      fixture.componentRef.setInput('companyId', 'c1');
      fixture.componentRef.setInput('systemId', IDS.jira);
      fixture.componentRef.setInput('showPolls', false);
      await serve(http, { [`GET ${API}/triggers`]: { items: [] } });
      expect(
        (fixture.nativeElement as HTMLElement).querySelector('[data-testid=system-polls]'),
      ).toBeNull();
    });
  });
});
