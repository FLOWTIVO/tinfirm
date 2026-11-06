import { HttpTestingController } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import type { Run, RunEvent, RunEventPage } from '../../../api/generated/models';
import { fakeEventSources } from '../../../testing/fake-event-source';
import { aRun as run } from '../../../testing/fixtures';
import { problem, provideTestHttp, settle } from '../../../testing/test-helpers';
import { RunStream } from './run-stream';
import type { MessageItem } from './transcript-model';

const RUN: Run = run({
  id: 'r1',
  status: 'RUNNING',
  finishedAt: null,
  result: null,
  summary: null,
});

const message = (seq: number, text: string): RunEvent => ({
  seq,
  type: 'AGENT_MESSAGE',
  at: '2026-10-05T08:14:00Z',
  payload: { text },
});

describe('RunStream', () => {
  let fake: ReturnType<typeof fakeEventSources>;
  let http: HttpTestingController;
  let stream: RunStream;

  const sources = () => fake.sources;
  const latest = () => fake.latest()!;
  const texts = () => stream.items().map((i) => (i as MessageItem).text);

  beforeEach(() => {
    fake = fakeEventSources();
    TestBed.configureTestingModule({
      providers: [provideRouter([]), ...provideTestHttp(), RunStream, ...fake.providers],
    });
    http = TestBed.inject(HttpTestingController);
    stream = TestBed.inject(RunStream);
  });

  afterEach(() => {
    stream.stop();
    http.verify();
  });

  it('opens the SSE stream and coalesces message chunks into one row', async () => {
    stream.start('c1', 'r1', RUN);
    expect(latest().url).toBe('/api/companies/c1/runs/r1/events/stream');
    expect(stream.state()).toBe('connecting');

    latest().open();
    expect(stream.state()).toBe('live');
    latest().send('run-event', message(1, 'Hel'), '1');
    latest().send('run-event', message(2, 'lo'), '2');
    await settle();

    expect(texts()).toEqual(['Hello']);
    expect(stream.summary().lastSeq).toBe(2);
  });

  it('keeps the connection while the browser reconnects with Last-Event-ID, and drops replayed events', async () => {
    stream.start('c1', 'r1', RUN);
    const source = latest();
    source.open();
    source.send('run-event', message(1, 'a'), '1');
    source.send('run-event', message(2, 'b'), '2');

    // Network drop: the browser retries on its own and sends Last-Event-ID: 2.
    source.fail(0);
    expect(stream.state()).toBe('reconnecting');
    expect(sources()).toHaveLength(1);
    expect(source.closed).toBe(false);

    // The server replays from the id it got; an overlapping event is ignored.
    source.open();
    source.send('run-event', message(2, 'b'), '2');
    source.send('run-event', message(3, 'c'), '3');
    stream.flush();
    expect(texts()).toEqual(['abc']);
  });

  it('re-opens a stream the browser gave up on, after the last seen event', async () => {
    stream.start('c1', 'r1', RUN);
    latest().open();
    latest().send('run-event', message(1, 'a'), '1');
    latest().send('run-event', message(2, 'b'), '2');

    latest().fail(2);
    expect(latest().closed).toBe(true);
    await settle();

    expect(sources()).toHaveLength(2);
    expect(latest().url).toBe('/api/companies/c1/runs/r1/events/stream?afterSeq=2');
  });

  it('falls back to polling listRunEvents when SSE keeps failing', async () => {
    stream.start('c1', 'r1', RUN);
    latest().send('run-event', message(1, 'a'), '1');
    for (let attempt = 0; attempt < 3; attempt++) {
      latest().fail(2);
      await settle();
    }
    expect(stream.state()).toBe('polling');
    expect(sources()).toHaveLength(3);

    const page: RunEventPage = { items: [message(2, 'b'), message(3, 'c')], complete: false };
    http
      .expectOne((r) => r.url === '/api/companies/c1/runs/r1/events')
      .flush(page, { status: 200, statusText: 'OK' });
    http.expectOne('/api/companies/c1/runs/r1').flush(RUN);
    await settle();
    expect(texts()).toEqual(['abc']);

    // Next poll continues after the last event and stops once the run is complete.
    const next = http.expectOne((r) => r.url === '/api/companies/c1/runs/r1/events');
    expect(next.request.params.get('afterSeq')).toBe('3');
    next.flush({ items: [], complete: true });
    http.expectOne('/api/companies/c1/runs/r1').flush({ ...RUN, status: 'SUCCEEDED' });
    await settle();
    expect(stream.state()).toBe('ended');
    expect(stream.run()?.status).toBe('SUCCEEDED');
  });

  it('falls back after repeated silent reconnects', async () => {
    stream.start('c1', 'r1', RUN);
    for (let i = 0; i < 4; i++) latest().fail(0);
    expect(latest().closed).toBe(true);
    expect(stream.state()).toBe('polling');
    await settle();
    http
      .expectOne((r) => r.url === '/api/companies/c1/runs/r1/events')
      .flush({
        items: [],
        complete: true,
      });
    http.expectOne('/api/companies/c1/runs/r1').flush({ ...RUN, status: 'FAILED' });
    await settle();
    expect(stream.state()).toBe('ended');
  });

  it('reads a finished run page by page, without a stream', async () => {
    const created = sources().length;
    stream.start('c1', 'r1', { ...RUN, status: 'SUCCEEDED' });
    expect(sources()).toHaveLength(created);
    expect(stream.state()).toBe('polling');
    await settle();
    http
      .expectOne((r) => r.url === '/api/companies/c1/runs/r1/events')
      .flush({ items: [message(1, 'done')], complete: true });
    http.expectOne('/api/companies/c1/runs/r1').flush({ ...RUN, status: 'SUCCEEDED' });
    await settle();
    expect(texts()).toEqual(['done']);
    expect(stream.state()).toBe('ended');
  });

  it('polls right away when the browser has no EventSource', async () => {
    fake.state.available = false;
    stream.start('c1', 'r1', RUN);
    expect(stream.state()).toBe('polling');
    await settle();
    const req = http.expectOne((r) => r.url === '/api/companies/c1/runs/r1/events');
    expect(req.request.params.get('afterSeq')).toBe('0');
    req.flush({ items: [message(1, 'hi')], complete: true });
    http.expectOne('/api/companies/c1/runs/r1').flush({ ...RUN, status: 'SUCCEEDED' });
    await settle();
    expect(texts()).toEqual(['hi']);
    expect(stream.state()).toBe('ended');
  });

  it('follows a queued run whose queue reason changes, and stays connected', () => {
    stream.start('c1', 'r1', { ...RUN, status: 'QUEUED' });
    const source = latest();
    source.open();
    source.send('run-status', {
      ...RUN,
      status: 'QUEUED',
      queueReason: { code: 'RUNNER_HOST_OFFLINE', since: '2026-10-05T08:13:00Z' },
    });
    expect(stream.run()?.queueReason?.code).toBe('RUNNER_HOST_OFFLINE');
    source.send('run-status', {
      ...RUN,
      status: 'QUEUED',
      queueReason: { code: 'ASSIGNED', since: '2026-10-05T08:13:05Z' },
    });
    expect(stream.run()?.queueReason?.code).toBe('ASSIGNED');
    expect(stream.state()).toBe('live');
    expect(source.closed).toBe(false);
  });

  it('stops at the final run-status and does not reconnect', async () => {
    stream.start('c1', 'r1', RUN);
    const source = latest();
    source.open();
    source.send('run-event', message(1, 'done'), '1');
    source.send('run-status', { ...RUN, status: 'RUNNING', lastEventSeq: 1 });
    expect(source.closed).toBe(false);

    source.send('run-status', { ...RUN, status: 'SUCCEEDED', lastEventSeq: 1 });
    expect(source.closed).toBe(true);
    expect(stream.state()).toBe('ended');
    expect(stream.run()?.status).toBe('SUCCEEDED');
    expect(texts()).toEqual(['done']);
    await settle();
    expect(sources()).toHaveLength(1);
  });

  it('reads missed events once when the final status is ahead of the stream', async () => {
    stream.start('c1', 'r1', RUN);
    latest().open();
    latest().send('run-event', message(1, 'a'), '1');
    latest().send('run-status', { ...RUN, status: 'FAILED', lastEventSeq: 2 });
    expect(stream.state()).toBe('polling');
    await settle();
    const req = http.expectOne((r) => r.url === '/api/companies/c1/runs/r1/events');
    expect(req.request.params.get('afterSeq')).toBe('1');
    req.flush({ items: [message(2, 'b')], complete: true });
    http.expectOne('/api/companies/c1/runs/r1').flush({ ...RUN, status: 'FAILED' });
    await settle();
    expect(texts()).toEqual(['ab']);
    expect(stream.state()).toBe('ended');
  });

  it('gives up when the run is gone', async () => {
    fake.state.available = false;
    stream.start('c1', 'r1', RUN);
    await settle();
    http
      .expectOne((r) => r.url === '/api/companies/c1/runs/r1/events')
      .flush(problem(404), { status: 404, statusText: 'Not Found' });
    http.expectOne('/api/companies/c1/runs/r1').flush(RUN);
    await settle();
    expect(stream.state()).toBe('failed');
  });

  it('ignores callbacks of a previous run after switching', async () => {
    stream.start('c1', 'r1', RUN);
    const old = latest();
    stream.start('c1', 'r2', { ...RUN, id: 'r2' });
    expect(old.closed).toBe(true);
    old.send('run-event', message(1, 'stale'));
    latest().send('run-event', message(1, 'fresh'));
    stream.flush();
    expect(texts()).toEqual(['fresh']);
  });
});
