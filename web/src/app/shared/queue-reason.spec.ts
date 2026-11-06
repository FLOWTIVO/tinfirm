import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import type { QueueReason, Run } from '../api/generated/models';
import { aQueuedRun, aRun, IDS } from '../testing/fixtures';
import { settle } from '../testing/test-helpers';
import {
  type QueueReasonReader,
  QueueReasonText,
  queueReasonText,
  queueReasonView,
} from './queue-reason';

const NOW = Date.parse('2026-10-12T12:00:00Z');
const inSeconds = (s: number) => new Date(NOW + s * 1000).toISOString();

const MEMBER: QueueReasonReader = { member: true, admin: false };
const ADMIN: QueueReasonReader = { member: true, admin: true };
const INSTANCE_ADMIN: QueueReasonReader = { member: false, admin: true };

const queued = (reason: Partial<QueueReason>) => aQueuedRun(reason);

describe('queueReasonView (the package 3 contract, §5.7)', () => {
  it('says the server’s sentence, and nothing for a run that does not wait', () => {
    const view = queueReasonView(queued({ code: 'RUNNER_HOST_FULL' }), MEMBER, NOW);
    expect(view?.text).toBe('Waiting for a free slot on office (2 of 2 in use).');
    expect(queueReasonView(aRun(), MEMBER, NOW)).toBeNull();
    expect(queueReasonView(null, MEMBER, NOW)).toBeNull();
  });

  it('says it is queued before the dispatcher looked', () => {
    const run = aQueuedRun({}, { queueReason: null });
    expect(queueReasonView(run, MEMBER, NOW)?.text).toBe(
      'Queued. Waiting for a runner host to pick it up.',
    );
    expect(queueReasonView({ ...run, runnerHostName: 'office' }, MEMBER, NOW)?.text).toBe(
      'Queued. Waiting for office to pick it up.',
    );
  });

  it.each`
    code                         | reader    | label                    | commands                               | query                | fragment
    ${'AGENT_PAUSED'}            | ${MEMBER} | ${'Open the agent'}      | ${['agents', IDS.agent]}               | ${undefined}         | ${undefined}
    ${'ACCOUNT_REQUIRED'}        | ${MEMBER} | ${'Connect the account'} | ${['conversations', IDS.conversation]} | ${undefined}         | ${'bindings'}
    ${'BUDGET_SPENT'}            | ${MEMBER} | ${'Your budget'}         | ${['accounts']}                        | ${{ tab: 'budget' }} | ${undefined}
    ${'KEY_ERROR'}               | ${MEMBER} | ${'Your budget'}         | ${['accounts']}                        | ${{ tab: 'budget' }} | ${undefined}
    ${'HOST_OFFLINE'}            | ${MEMBER} | ${'Your runner hosts'}   | ${['accounts']}                        | ${{ tab: 'hosts' }}  | ${undefined}
    ${'NO_RUNNER_HOST'}          | ${MEMBER} | ${'Your runner hosts'}   | ${['accounts']}                        | ${{ tab: 'hosts' }}  | ${undefined}
    ${'NO_HOST_FOR_SKILLS'}      | ${MEMBER} | ${'Your runner hosts'}   | ${['accounts']}                        | ${{ tab: 'hosts' }}  | ${undefined}
    ${'NO_HOST_FOR_SKILLS'}      | ${ADMIN}  | ${'Runner hosts'}        | ${['runner-hosts']}                    | ${undefined}         | ${'host-h-office'}
    ${'NO_HOST_FOR_CREDENTIALS'} | ${ADMIN}  | ${'Runner hosts'}        | ${['runner-hosts']}                    | ${undefined}         | ${'host-h-office'}
    ${'RUNNER_HOST_OFFLINE'}     | ${ADMIN}  | ${'Runner hosts'}        | ${['runner-hosts']}                    | ${undefined}         | ${'host-h-office'}
  `(
    'links $code to its fix for the reader',
    ({ code, reader, label, commands, query, fragment }) => {
      const fix = queueReasonView(queued({ code }), reader, NOW)?.fix;
      expect(fix).toEqual({
        label,
        commands,
        ...(query ? { queryParams: query } : {}),
        ...(fragment ? { fragment } : {}),
      });
    },
  );

  it('offers a member nothing to fix while a company host is busy, and no budget to a non-member', () => {
    expect(queueReasonView(queued({ code: 'RUNNER_HOST_FULL' }), MEMBER, NOW)?.fix).toBeNull();
    expect(queueReasonView(queued({ code: 'BUDGET_SPENT' }), INSTANCE_ADMIN, NOW)?.fix).toBeNull();
    expect(queueReasonView(queued({ code: 'OTHER', message: 'x' }), ADMIN, NOW)?.fix).toBeNull();
  });

  it('names the reset of a spent budget, and when a host tries again', () => {
    const spent = queued({
      code: 'BUDGET_SPENT',
      text: 'Your budget is spent',
      retryAt: '2026-11-01T00:00:00Z',
    });
    const view = queueReasonView(spent, MEMBER, NOW);
    expect(view?.text).toBe('Your budget is spent.');
    expect(view?.time).toMatchObject({ prefix: 'Resets', iso: '2026-11-01T00:00:00Z' });

    const rejected = queued({ code: 'RUNNER_HOST_REJECTED', retryAt: inSeconds(8) });
    expect(queueReasonText(rejected, NOW)).toContain('Tries again in 8 seconds.');
    const passed = queued({ code: 'PREVIOUS_RUN_ACTIVE', retryAt: inSeconds(-1) });
    expect(queueReasonText(passed, NOW)).toContain('Tries again shortly.');
  });

  it('says when an automatic retry of a failed turn runs, with nothing to fix', () => {
    const scheduled = queued({
      code: 'RETRY_SCHEDULED',
      text: 'The previous turn failed for a reason that may pass; Tinfirm retries it by itself (automatic retry 1 of 3).',
      retryAt: inSeconds(60),
      used: 1,
      limit: 3,
    });
    const view = queueReasonView(scheduled, MEMBER, NOW);
    expect(view?.text).toContain('(automatic retry 1 of 3).');
    expect(view?.time).toMatchObject({ prefix: 'Tries again' });
    expect(view?.fix).toBeNull();
    expect(queueReasonView(scheduled, ADMIN, NOW)?.fix).toBeNull();
  });

  it('falls back to the message, then to a plain “Queued.”', () => {
    expect(queueReasonText(queued({ code: 'OTHER', text: '', message: 'Disk full' }), NOW)).toBe(
      'Disk full.',
    );
    expect(queueReasonText(queued({ code: 'OTHER', text: '', message: null }), NOW)).toBe(
      'Queued.',
    );
  });
});

@Component({
  imports: [QueueReasonText],
  template: `<app-queue-reason [run]="run()" companyId="c1" [reader]="reader()" />`,
})
class Host {
  readonly run = signal<Run | null>(null);
  readonly reader = signal<QueueReasonReader>(MEMBER);
}

describe('QueueReasonText', () => {
  it('draws the sentence and the link to the fix', async () => {
    TestBed.configureTestingModule({ imports: [Host], providers: [provideRouter([])] });
    const fixture = TestBed.createComponent(Host);
    fixture.componentInstance.run.set(
      queued({ code: 'BUDGET_SPENT', text: 'Your budget is spent', retryAt: inSeconds(3600) }),
    );
    await settle();
    fixture.detectChanges();
    const el: HTMLElement = fixture.nativeElement;
    expect(el.textContent).toContain('Your budget is spent.');
    expect(el.textContent).toContain('Resets');
    const link = el.querySelector<HTMLAnchorElement>('[data-testid=queue-reason-fix]')!;
    expect(link.textContent?.trim()).toBe('Your budget');
    expect(link.getAttribute('href')).toBe('/c/c1/accounts?tab=budget');

    fixture.componentInstance.run.set(aRun());
    await settle();
    fixture.detectChanges();
    expect(el.textContent?.trim()).toBe('');
  });
});
