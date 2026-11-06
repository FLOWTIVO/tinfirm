import { HttpTestingController } from '@angular/common/http/testing';
import { type ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import type { Role, Run } from '../../../api/generated/models';
import { fakeEventSources } from '../../../testing/fake-event-source';
import { aConversation, aRun as run, aRunEvent as runEvent } from '../../../testing/fixtures';
import {
  fakeCurrentCompany,
  fakeNotify,
  problem,
  provideTestHttp,
  settle,
} from '../../../testing/test-helpers';
import { RunPage } from './run';

const URL = '/api/companies/c1/runs/run2';

const RUNNING: Run = run({
  id: 'run2',
  status: 'RUNNING',
  turnNumber: 2,
  turnReason: 'MESSAGE',
  finishedAt: null,
  result: null,
  summary: null,
  branch: 'agent/SHOP-241-1',
  repositories: [
    { repositoryId: 'r1', repositoryName: 'shop-api', dir: 'shop-api', readOnly: false },
    { repositoryId: 'r2', repositoryName: 'shop-docs', dir: 'shop-docs', readOnly: true },
  ],
});

describe('RunPage', () => {
  let fixture: ComponentFixture<RunPage>;
  let http: HttpTestingController;
  let el: HTMLElement;
  let fake: ReturnType<typeof fakeEventSources>;
  let notify: ReturnType<typeof fakeNotify>['notify'];

  async function open(r: Run | null, role: Role = 'OWNER'): Promise<void> {
    fake = fakeEventSources();
    const notifier = fakeNotify();
    notify = notifier.notify;
    TestBed.configureTestingModule({
      imports: [RunPage],
      providers: [
        provideRouter([]),
        ...provideTestHttp(),
        ...fake.providers,
        fakeCurrentCompany(role).provider,
        notifier.provider,
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(RunPage);
    fixture.componentRef.setInput('runId', 'run2');
    el = fixture.nativeElement;
    await settle();
    const req = http.expectOne({ method: 'GET', url: URL });
    if (r) req.flush(r);
    else req.flush(problem(404), { status: 404, statusText: 'Not Found' });
    await settle();
    if (r) {
      http
        .expectOne({ method: 'GET', url: '/api/companies/c1/conversations/cv1' })
        .flush(aConversation());
      await settle();
    }
  }

  const text = (testId: string) =>
    el.querySelector(`[data-testid=${testId}]`)?.textContent?.replace(/\s+/g, ' ').trim();

  afterEach(() => http.verify());

  it('names the agent, the turn and the conversation, and follows the live transcript', async () => {
    await open(RUNNING);
    expect(el.querySelector('h1')?.textContent?.trim()).toBe('Business analyst');
    expect(el.querySelector('app-run-status-badge')?.textContent?.trim()).toBe('Running');
    expect(text('run-turn')).toBe(
      'Turn 2 · a new message · in SHOP-241-1 Checkout fails for guest users',
    );
    expect(el.querySelector('[data-testid=run-conversation]')?.getAttribute('href')).toBe(
      '/c/c1/conversations/cv1',
    );
    expect(el.querySelector('[data-testid=run-back]')?.getAttribute('href')).toBe(
      '/c/c1/conversations/cv1',
    );
    const repos = el.querySelectorAll('[data-testid=run-repository]');
    expect(repos).toHaveLength(2);
    expect(repos[0].textContent).toContain('Working');
    expect(repos[1].querySelector('[data-testid=read-only]')).not.toBeNull();
    expect(el.textContent).toContain('agent/SHOP-241-1');
    expect(el.querySelector('[data-testid=run-queue-reason]')).toBeNull();

    // The transcript follows the run over SSE.
    const source = fake.latest()!;
    expect(source.url).toBe('/api/companies/c1/runs/run2/events/stream');
    source.open();
    source.send('run-event', runEvent(1, 'AGENT_MESSAGE', { text: 'Reading the checkout.' }));
    await new Promise((resolve) => setTimeout(resolve, 5));
    await settle();
    expect(el.querySelector('[role=log]')?.textContent).toContain('Reading the checkout.');

    // Its final status ends the page's run too.
    source.send('run-status', { ...RUNNING, status: 'SUCCEEDED', lastEventSeq: 1 });
    await settle();
    expect(el.querySelector('app-run-status-badge')?.textContent?.trim()).toBe('Succeeded');
    expect(el.querySelector('[data-testid=cancel-run]')).toBeNull();
  });

  it('says for whom it runs, who pays, and on which host', async () => {
    await open(RUNNING);
    const panel = el.querySelector('[data-testid=who-acts-who-pays]')!;
    expect(panel.querySelector('[data-part=acts]')?.textContent).toContain('For Alice Novak');
    expect(panel.querySelector('[data-part=pays]')?.textContent).toContain('Alice Novak’s budget');
    expect(panel.querySelector('[data-part=runs]')?.textContent).toContain(
      'Claude Code on office, a company host',
    );
    expect(el.querySelector('[data-testid=run-host] dd')?.textContent?.trim()).toBe(
      'office, a company host',
    );
  });

  it('cancels a running run', async () => {
    await open(RUNNING);
    el.querySelector<HTMLButtonElement>('[data-testid=cancel-run]')!.click();
    await settle();
    http
      .expectOne({ method: 'POST', url: `${URL}/cancel` })
      .flush({ ...RUNNING, status: 'CANCELLED', finishedAt: '2026-10-05T08:20:00Z' });
    await settle();
    expect(notify.success).toHaveBeenCalledWith('Cancelling the run');
    expect(el.querySelector('app-run-status-badge')?.textContent?.trim()).toBe('Cancelled');
    expect(el.querySelector('[data-testid=cancel-run]')).toBeNull();
  });

  it('says why a queued run waits, with the link that fixes it', async () => {
    await open(
      run({
        ...RUNNING,
        status: 'QUEUED',
        startedAt: null,
        runnerHostId: null,
        runnerHostName: null,
        runnerHostKind: null,
        queueReason: {
          code: 'BUDGET_SPENT',
          text: 'Your budget is spent',
          since: '2026-10-05T08:13:00Z',
          retryAt: '2026-11-01T00:00:00Z',
        },
      }),
      'MEMBER',
    );
    expect(text('run-queue-reason')).toContain('Your budget is spent. Resets');
    expect(el.querySelector('[data-testid=run-queue-reason] a')?.getAttribute('href')).toBe(
      '/c/c1/accounts?tab=budget',
    );
    // Whoever sees the run may cancel it.
    expect(el.querySelector('[data-testid=cancel-run]')).not.toBeNull();
  });

  it('sends an admin to the runner hosts when a host holds the run', async () => {
    await open(
      run({
        ...RUNNING,
        status: 'QUEUED',
        startedAt: null,
        queueReason: {
          code: 'RUNNER_HOST_OFFLINE',
          text: 'Runner host office is offline',
          since: '2026-10-05T08:13:00Z',
          runnerHostId: 'h-office',
          runnerHostName: 'office',
          lastSeenAt: null,
        },
      }),
      'ADMIN',
    );
    expect(text('run-queue-reason')).toBe('Runner host office is offline. Runner hosts');
    expect(el.querySelector('[data-testid=run-queue-reason] a')?.getAttribute('href')).toBe(
      '/c/c1/runner-hosts#host-h-office',
    );
  });

  it('shows a finished run with its failure, or with the result of its finish', async () => {
    const failed = run({
      ...RUNNING,
      status: 'FAILED',
      finishedAt: '2026-10-05T08:20:00Z',
      failureClass: 'USAGE_LIMIT',
      failureMessage: 'Quota exhausted',
    });
    await open(failed);
    expect(text('run-failure')).toBe('Usage limit reached. Quota exhausted');
    expect(el.querySelector('[data-testid=cancel-run]')).toBeNull();
    // A finished run is read page by page, not streamed.
    expect(fake.sources).toHaveLength(0);
    http
      .expectOne((r) => r.url === `${URL}/events`)
      .flush({ items: [runEvent(1, 'ERROR', { message: 'Quota exhausted' })], complete: true });
    http.expectOne({ method: 'GET', url: URL }).flush(failed);
    await settle();
    expect(el.querySelector('[role=log] [data-kind=error]')).not.toBeNull();

    TestBed.resetTestingModule();
    const blocked = run({
      ...RUNNING,
      status: 'SUCCEEDED',
      finishedAt: '2026-10-05T08:20:00Z',
      result: {
        outcome: 'BLOCKED',
        summary: 'I need to know the **provider**.',
        nextSteps: ['Fix the button'],
        openQuestions: ['Which provider?'],
      },
    });
    await open(blocked);
    http.expectOne((r) => r.url === `${URL}/events`).flush({ items: [], complete: true });
    http.expectOne({ method: 'GET', url: URL }).flush(blocked);
    await settle();
    const result = el.querySelector('[data-testid=run-result]')!;
    expect(result.getAttribute('data-outcome')).toBe('BLOCKED');
    expect(result.querySelector('strong')?.textContent).toBe('provider');
    expect(text('run-result-next-steps')).toContain('Fix the button');
    expect(text('run-result-open-questions')).toContain('Which provider?');
    expect(result.querySelector('a')?.getAttribute('href')).toBe('/c/c1/conversations/cv1');
  });

  it('shows the cache reads and writes of the run apart from its input', async () => {
    await open(
      run({
        ...RUNNING,
        usage: {
          inputTokens: 1200,
          outputTokens: 340,
          cachedInputTokens: 18_000,
          cacheWriteTokens: 1500,
          costUsd: 0.42,
        },
      }),
    );
    const tokens = el.querySelector('[data-testid=run-tokens] dd')!;
    expect(tokens.textContent?.trim()).toBe(
      '1,200 in · 18K cache read · 1,500 cache write · 340 out · $0.420',
    );
    expect(tokens.getAttribute('title')).toBe(
      'Uncached input 1,200 · cache read 18K · cache write 1,500 · output 340 tokens',
    );
  });

  it.each<[string, Partial<Run>, string]>([
    [
      'before a runner host took it',
      { startedAt: null },
      'This turn was cancelled before a runner host took it. Messages that waited for it stay unanswered; retry it from the conversation.',
    ],
    [
      'while it ran',
      {},
      'This turn was cancelled. Messages that waited for it stay unanswered; retry it from the conversation.',
    ],
  ])('says a run was cancelled %s, and sends the reader to retry it', async (_, at, says) => {
    const cancelled = run({
      ...RUNNING,
      ...at,
      status: 'CANCELLED',
      finishedAt: '2026-10-12T08:20:00Z',
    });
    await open(cancelled);
    http.expectOne((r) => r.url === `${URL}/events`).flush({ items: [], complete: true });
    http.expectOne({ method: 'GET', url: URL }).flush(cancelled);
    await settle();
    expect(el.querySelector('app-run-status-badge')?.textContent?.trim()).toBe('Cancelled');
    expect(text('run-cancelled')).toBe(says);
    expect(el.querySelector('[data-testid=run-cancelled] a')?.getAttribute('href')).toBe(
      '/c/c1/conversations/cv1',
    );
    expect(el.querySelector('[data-testid=cancel-run]')).toBeNull();
  });

  it('says what retention removed: its texts, and its transcript without asking for it', async () => {
    const purged = run({
      ...RUNNING,
      status: 'FAILED',
      finishedAt: '2026-10-12T08:20:00Z',
      failureClass: 'INFRA',
      failureMessage: null,
      summary: null,
      result: null,
      textsPurgedAt: '2026-10-12T09:00:00Z',
      eventsPurgedAt: '2026-10-12T09:00:00Z',
      counts: { messages: 4, toolCalls: 14, thoughts: 1 },
    });
    await open(purged);
    expect(text('run-failure')).toBe('Infrastructure error.');
    expect(text('run-texts-purged')).toBe(
      'Its summary, result and failure message were removed on Oct 12, 2026 under the company’s retention.',
    );
    expect(text('run-events-purged')).toBe(
      'The transcript was removed on Oct 12, 2026 under the company’s retention. It held 4 messages, 14 tool calls and 1 thought.',
    );
    // No transcript: neither a stream nor a page of events is asked for.
    expect(el.querySelector('app-transcript')).toBeNull();
    expect(fake.sources).toHaveLength(0);
  });

  it('says when the run does not exist', async () => {
    await open(null);
    expect(el.querySelector('[role=alert]')?.textContent).toContain('Run not found');
  });
});
