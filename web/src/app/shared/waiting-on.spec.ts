import { type ComponentFixture, TestBed } from '@angular/core/testing';
import { aConversationSummary } from '../testing/fixtures';
import { type Waiting, waitingBadge, WaitingOnBadge } from './waiting-on';

describe('waitingBadge', () => {
  const waiting = (waitingOn: Waiting['waitingOn'], waitingReason?: string): Waiting => ({
    waitingOn,
    waitingReason,
  });

  it.each`
    waitingOn   | reason            | label                       | tone
    ${'PERSON'} | ${'QUESTION'}     | ${'Needs you · Question'}   | ${'human'}
    ${'PERSON'} | ${'APPROVAL'}     | ${'Needs you · Approval'}   | ${'human'}
    ${'PERSON'} | ${'ACCOUNT'}      | ${'Needs you · Account'}    | ${'human'}
    ${'PERSON'} | ${'FAILED'}       | ${'Turn failed'}            | ${'destructive'}
    ${'AGENT'}  | ${'RUNNING'}      | ${'Working'}                | ${'agent'}
    ${'RUNNER'} | ${'BUDGET_SPENT'} | ${'Waiting · Budget spent'} | ${'muted'}
    ${'RUNNER'} | ${'QUEUED'}       | ${'Waiting · Queued'}       | ${'muted'}
    ${'RUNNER'} | ${'NEW_REASON'}   | ${'Waiting for a runner'}   | ${'muted'}
  `('$waitingOn $reason reads “$label”', ({ waitingOn, reason, label, tone }) => {
    expect(waitingBadge(waiting(waitingOn, reason))).toEqual({ label, tone });
  });

  it('is neutral for a turn stopped at its time limit: it goes on with Continue', () => {
    expect(
      waitingBadge({ waitingOn: 'PERSON', waitingReason: 'FAILED', failureClass: 'TIME_LIMIT' }),
    ).toEqual({ label: 'Stopped · Time limit', tone: 'muted' });
    expect(
      waitingBadge({ waitingOn: 'PERSON', waitingReason: 'FAILED', failureClass: 'GUARD_STOPPED' }),
    ).toEqual({ label: 'Turn failed', tone: 'destructive' });
  });

  it('is neutral for a reader who is not the person, and says nothing when nothing waits', () => {
    expect(waitingBadge(waiting('PERSON', 'QUESTION'), false, 'Alice')).toEqual({
      label: 'Waits for Alice · Question',
      tone: 'muted',
    });
    expect(waitingBadge(waiting('NONE'))).toBeNull();
  });
});

describe('WaitingOnBadge', () => {
  let fixture: ComponentFixture<WaitingOnBadge>;

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [WaitingOnBadge] });
    fixture = TestBed.createComponent(WaitingOnBadge);
  });

  it('shows orange only for the person, with since when', async () => {
    fixture.componentRef.setInput(
      'item',
      aConversationSummary({
        waitingOn: 'PERSON',
        waitingReason: 'QUESTION',
        waitingSince: '2026-10-05T09:00:00Z',
      }),
    );
    await fixture.whenStable();
    const badge = fixture.nativeElement.querySelector('[data-testid=waiting-on]')!;
    expect(badge.textContent).toContain('Needs you · Question');
    expect(badge.classList).toContain('text-human');
    expect(badge.getAttribute('title')).toContain('Since');

    fixture.componentRef.setInput('mine', false);
    await fixture.whenStable();
    expect(fixture.nativeElement.querySelector('[data-testid=waiting-on]').classList).not.toContain(
      'text-human',
    );

    fixture.componentRef.setInput('item', aConversationSummary({ waitingOn: 'NONE' }));
    await fixture.whenStable();
    expect(fixture.nativeElement.querySelector('[data-testid=waiting-on]')).toBeNull();
  });
});
