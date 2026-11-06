import { HttpTestingController } from '@angular/common/http/testing';
import { Component, signal } from '@angular/core';
import { type ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import type { Run } from '../../../api/generated/models';
import { type FakeEventSource, fakeEventSources } from '../../../testing/fake-event-source';
import { aRun as run, aRunEvent as runEvent } from '../../../testing/fixtures';
import { provideTestHttp, settle } from '../../../testing/test-helpers';
import { Transcript } from './transcript';

const RUN: Run = run({
  id: 'r1',
  status: 'QUEUED',
  runnerHostId: 'h1',
  runnerHostName: 'office',
  startedAt: null,
  finishedAt: null,
  result: null,
  summary: null,
});

@Component({
  imports: [Transcript],
  template: `<app-transcript
    companyId="c1"
    runId="r1"
    [run]="run()"
    (runChange)="changes.push($event)"
  />`,
})
class Host {
  readonly run = signal<Run | null>(RUN);
  readonly changes: Run[] = [];
}

describe('Transcript', () => {
  let fixture: ComponentFixture<Host>;
  let http: HttpTestingController;
  let fake: ReturnType<typeof fakeEventSources>;
  let el: HTMLElement;

  const source = (): FakeEventSource => fake.latest()!;
  const empty = () => el.querySelector('[data-testid=transcript-empty]');
  const announced = () => el.querySelector('[aria-live=polite]')?.textContent;

  async function send(...events: ReturnType<typeof runEvent>[]): Promise<void> {
    for (const event of events) source().send('run-event', event);
    await new Promise((resolve) => setTimeout(resolve, 5));
    await settle();
  }

  beforeEach(async () => {
    fake = fakeEventSources();
    TestBed.configureTestingModule({
      imports: [Host],
      providers: [provideRouter([]), ...provideTestHttp(), ...fake.providers],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(Host);
    el = fixture.nativeElement;
    await settle();
    source().open();
    await settle();
  });

  afterEach(() => http.verify());

  it('says a queued run waits, and announces why as the queue reason changes', async () => {
    expect(el.querySelector('[data-testid=stream-state]')?.textContent).toContain('Live');
    expect(empty()?.textContent?.trim()).toBe(
      'Waiting in the queue: the events start when a runner host takes the run.',
    );
    source().send('run-status', {
      ...RUN,
      queueReason: {
        code: 'RUNNER_HOST_FULL',
        text: 'Waiting for a free slot on office (1 of 1 in use)',
        since: '2026-10-05T08:13:30Z',
        runnerHostId: 'h1',
        runnerHostName: 'office',
        used: 1,
        limit: 1,
      },
    });
    await settle();
    expect(announced()).toBe('Waiting for a free slot on office (1 of 1 in use).');
    expect(fixture.componentInstance.changes.at(-1)?.queueReason?.code).toBe('RUNNER_HOST_FULL');

    source().send('run-status', { ...RUN, status: 'RUNNING', queueReason: null });
    await settle();
    expect(empty()?.textContent?.trim()).toBe('Waiting for events…');
    expect(announced()).toBe('Run running');
  });

  it('shows the rows, the phases, the token meter and a rate-limit banner', async () => {
    source().send('run-status', { ...RUN, status: 'RUNNING' });
    await send(
      runEvent(1, 'STATUS', { phase: 'cloning' }),
      runEvent(2, 'GIT', { action: 'cloned', branch: 'agent/SHOP-241' }),
      runEvent(3, 'STATUS', { phase: 'running' }),
      runEvent(4, 'TOOL_CALL', {
        toolCallId: 'a',
        title: 'ls',
        kind: 'execute',
        status: 'completed',
      }),
      runEvent(5, 'AGENT_MESSAGE', { text: 'Looking at the guest flow.' }),
      runEvent(6, 'USAGE', {
        inputTokens: 12_345,
        outputTokens: 678,
        contextUsed: 90,
        contextSize: 100,
      }),
      runEvent(7, 'RATE_LIMIT', {
        status: 'allowed_warning',
        utilization: 0.9,
        window: 'five_hour',
      }),
    );
    const rows = Array.from(el.querySelectorAll('[role=log] app-transcript-row'));
    expect(rows.map((r) => r.getAttribute('data-kind'))).toEqual([
      'status',
      'git',
      'status',
      'activity',
      'message',
      'rate-limit',
    ]);
    const chips = Array.from(el.querySelectorAll('[aria-label="Run phases"] li'));
    expect(chips.map((c) => c.textContent?.trim())).toEqual([
      'Cloning',
      'Running',
      'Finalizing',
      'Pushing',
    ]);
    expect(chips[1].getAttribute('aria-current')).toBe('step');
    const meter = el.querySelector('[data-testid=token-meter]')!;
    expect(meter.textContent).toContain('12.3K');
    expect(meter.querySelector('[role=meter]')?.getAttribute('aria-valuenow')).toBe('90');
    expect(el.querySelector('[role=alert]')?.textContent).toContain(
      'Close to the usage limit (five hour window): 90% used.',
    );
    expect(el.querySelector('[data-testid=agent-working]')).not.toBeNull();
  });

  it('renders the newest 300 entries and shows earlier ones on demand', async () => {
    source().send('run-status', { ...RUN, status: 'RUNNING' });
    await send(
      ...Array.from({ length: 305 }, (_, i) =>
        runEvent(i + 1, i % 2 ? 'AGENT_THOUGHT' : 'AGENT_MESSAGE', { text: `entry ${i}` }),
      ),
    );
    expect(el.querySelectorAll('[role=log] app-transcript-row')).toHaveLength(300);
    const more = Array.from(el.querySelectorAll('button')).find((b) =>
      b.textContent?.includes('Show 5 earlier entries'),
    )!;
    more.click();
    await settle();
    expect(el.querySelectorAll('[role=log] app-transcript-row')).toHaveLength(305);
    // 305 rows of Markdown take jsdom a few seconds on a busy machine.
  }, 20_000);

  it('follows a run again when the server resumes it after marking it lost', async () => {
    const first = source();
    first.send('run-status', { ...RUN, status: 'RUNNING', queueReason: null });
    await settle();
    first.send('run-status', { ...RUN, status: 'LOST', queueReason: null });
    await settle();
    expect(first.readyState).toBe(2);

    fixture.componentInstance.run.set({ ...RUN, status: 'RUNNING', queueReason: null });
    await settle();
    expect(source()).not.toBe(first);
    source().open();
    await settle();
    expect(announced()).toBe('Run running');
  });
});
