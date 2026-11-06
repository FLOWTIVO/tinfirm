import { TestBed } from '@angular/core/testing';
import { settle } from '../../../testing/test-helpers';
import { groupSummary, rowTarget, TranscriptRow, TranscriptWork } from './transcript-items';
import type { ToolItem, TranscriptRowItem } from './transcript-model';

const base = { seq: 1, at: '2026-10-05T08:14:00Z' };

function tool(
  n: number,
  title: string,
  status: ToolItem['status'] = 'completed',
  toolKind: ToolItem['toolKind'] = 'execute',
  extra: Partial<ToolItem> = {},
): ToolItem {
  return {
    kind: 'tool',
    id: `tool-${n}`,
    ...base,
    seq: n,
    toolCallId: String(n),
    title,
    toolKind,
    status,
    input: null,
    locations: [],
    output: null,
    diffs: [],
    ...extra,
  };
}

async function render(item: TranscriptRowItem, live = true): Promise<HTMLElement> {
  const fixture = TestBed.createComponent(TranscriptRow);
  fixture.componentRef.setInput('item', item);
  fixture.componentRef.setInput('live', live);
  await fixture.whenStable();
  return fixture.nativeElement as HTMLElement;
}

async function click(el: Element | null | undefined): Promise<void> {
  (el as HTMLButtonElement).click();
  await settle();
}

describe('transcript rows', () => {
  it('renders what the agent said as sanitized Markdown', async () => {
    const el = await render({
      kind: 'message',
      id: 'm1',
      ...base,
      text: [
        '## Done',
        'Changed `server.ts`, see [the docs](https://example.com/docs).',
        '<script>alert(1)</script><img src=x onerror=alert(2)>',
        '[bad](javascript:alert(3))',
        '- [x] tests pass',
      ].join('\n\n'),
    });
    expect(el.querySelector('h2')?.textContent).toBe('Done');
    expect(el.querySelector('code')?.textContent).toBe('server.ts');
    const link = el.querySelector<HTMLAnchorElement>('a[href="https://example.com/docs"]');
    expect(link?.rel).toContain('noopener');
    // Raw HTML shows as text and never becomes elements.
    expect(el.querySelector('script, img')).toBeNull();
    expect(el.textContent).toContain('<script>alert(1)</script>');
    expect(el.innerHTML).not.toContain('javascript:');
  });

  it('shows thinking in full, in quiet italics, without a toggle', async () => {
    const el = await render({
      kind: 'thought',
      id: 'th',
      ...base,
      text: 'First line\nsecond line',
    });
    expect(el.querySelector('button')).toBeNull();
    const p = el.querySelector('p.italic')!;
    expect(p.textContent).toContain('First line\nsecond line');
    expect(p.querySelector('.sr-only')?.textContent).toBe('Thinking: ');
  });

  it('folds a stretch of calls into one sentence that opens into a row per call', async () => {
    const group: TranscriptRowItem = {
      kind: 'activity',
      id: 'activity-tool-1',
      ...base,
      items: [
        tool(1, 'git status'),
        { kind: 'logs', id: 'logs-2', ...base, lines: [{ ...base, level: 'warn', message: 'x' }] },
        tool(3, 'Read src/app.ts', 'completed', 'read'),
        tool(4, 'npm test', 'failed'),
        tool(5, 'npm run lint', 'in_progress'),
      ],
    };
    const el = await render(group);
    const toggle = el.querySelector('[data-testid=transcript-group]')!;
    expect(toggle.getAttribute('aria-expanded')).toBe('false');
    const sentence = toggle.textContent!.replace(/\s+/g, ' ').trim();
    expect(sentence).toContain('Ran 3 commands, read 1 file');
    expect(sentence).toContain('· now');
    expect(sentence).toContain('· 1 log line');
    expect(sentence).toContain('· 1 failed');
    // Closed, it names the call that runs now.
    expect(toggle.querySelector('code')?.textContent).toBe('npm run lint');
    expect(el.textContent).not.toContain('git status');

    await click(toggle);
    expect(toggle.getAttribute('aria-expanded')).toBe('true');
    const rows = Array.from(el.querySelectorAll('ol > li'));
    expect(rows.map((r) => r.getAttribute('data-kind'))).toEqual([
      'tool',
      'log',
      'tool',
      'tool',
      'tool',
    ]);
    expect(rows[0].textContent).toContain('Ran');
    expect(rows[2].textContent).toContain('Read');
    // The title's leading verb is not repeated.
    expect(rows[2].querySelector('code')?.textContent).toBe('src/app.ts');
    expect(rows[3].textContent).toContain('failed');
    expect(rows[4].textContent).toContain('running');
    expect(rows[1].getAttribute('data-level')).toBe('warn');

    // A finished run has nothing running any more: the call stopped.
    const done = await render(group, false);
    const closed = done.querySelector('[data-testid=transcript-group]')!;
    expect(closed.textContent).not.toContain('now');
    expect(closed.querySelector('code')?.textContent).toBe('npm test');
    await click(closed);
    expect(done.querySelectorAll('ol > li')[4].textContent).toContain('stopped');
  });

  it('opens a call into its input, output and diff', async () => {
    const el = await render(
      tool(1, 'Edit src/app.ts', 'completed', 'edit', {
        input: { path: 'src/app.ts' },
        locations: [{ path: 'src/app.ts', line: 12 }],
        output: 'Applied 1 edit',
        diffs: [{ path: 'src/app.ts', oldText: 'const a = 1;\n', newText: 'const a = 2;\n' }],
      }),
    );
    // A lone call is a group of one.
    const toggle = el.querySelector('[data-testid=transcript-group]')!;
    expect(toggle.textContent).toContain('Edited 1');
    await click(toggle);
    const row = el.querySelector('li[data-kind=tool]')!;
    expect(row.textContent).toContain('+1 −1');
    await click(row.querySelector('button'));
    const call = el.querySelector('[data-testid=transcript-call]')!;
    expect(call.textContent).toContain('src/app.ts:12');
    expect(call.querySelector('[aria-label=Input]')?.textContent).toContain('"path": "src/app.ts"');
    expect(call.querySelector('[aria-label=Output]')?.textContent).toBe('Applied 1 edit');
    const lines = Array.from(call.querySelectorAll('tbody tr')).map((r) =>
      r.textContent?.replace(/\s+/g, ' ').trim(),
    );
    expect(lines).toContain('@@ -1 +1 @@');
    expect(lines.some((l) => l?.includes('removed: const a = 1;'))).toBe(true);
    expect(lines.some((l) => l?.includes('added: const a = 2;'))).toBe(true);
  });

  it('shows a command as the command, and only the latest log lines of a long block', async () => {
    const el = await render({
      kind: 'activity',
      id: 'activity-tool-1',
      ...base,
      items: [
        tool(1, 'npm test', 'completed', 'execute', { input: { command: 'npm test -- --ci' } }),
        {
          kind: 'logs',
          id: 'logs-2',
          ...base,
          lines: Array.from({ length: 60 }, (_, i) => ({
            seq: i + 2,
            at: base.at,
            level: 'info' as const,
            message: `line ${i}`,
          })),
        },
      ],
    });
    await click(el.querySelector('[data-testid=transcript-group]'));
    expect(el.querySelectorAll('[data-kind=log]')).toHaveLength(50);
    await click(
      Array.from(el.querySelectorAll('button')).find((b) =>
        b.textContent?.includes('10 earlier log lines'),
      ),
    );
    expect(el.querySelectorAll('[data-kind=log]')).toHaveLength(60);
    await click(el.querySelector('li[data-kind=tool] button'));
    expect(el.querySelector('[aria-label=Input]')?.textContent).toBe('npm test -- --ci');
  });

  it('renders a plan as a checklist, its current step only while live', async () => {
    const plan: TranscriptRowItem = {
      kind: 'plan',
      id: 'p',
      ...base,
      entries: [
        { content: 'Read the code', status: 'completed' },
        { content: 'Write the fix', status: 'in_progress' },
        { content: 'Run tests', status: 'pending' },
      ],
    };
    let el = await render(plan);
    const items = el.querySelectorAll('li');
    expect(items).toHaveLength(3);
    expect(el.textContent).toContain('1 of 3');
    expect(items[0].textContent).toContain('(done)');
    expect(items[1].textContent).toContain('(in progress)');
    expect(items[2].textContent).toContain('(not done)');
    el = await render(plan, false);
    expect(el.querySelectorAll('li')[1].textContent).toContain('(not done)');
  });

  it('renders git steps, phases, errors and rate limits', async () => {
    let el = await render({
      kind: 'git',
      id: 'g',
      ...base,
      dir: 'shop-api',
      action: 'cloned',
      branch: 'agent/SHOP-241',
      headSha: '0123456789abcdef',
      commits: null,
    });
    expect(el.textContent).toContain('Checked out agent/SHOP-241');
    expect(el.querySelector('[data-testid=git-dir]')?.getAttribute('title')).toBe(
      '/workspace/shop-api',
    );
    expect(el.textContent).toContain('0123456');

    el = await render({
      kind: 'git',
      id: 'g2',
      ...base,
      dir: null,
      action: 'pushed',
      branch: 'agent/SHOP-241',
      headSha: null,
      commits: 2,
    });
    expect(el.textContent).toContain('Pushed agent/SHOP-241 (2 commits)');
    expect(el.querySelector('[data-testid=git-dir]')).toBeNull();

    el = await render({ kind: 'status', id: 's', ...base, phase: 'cloning', message: 'Cloning' });
    expect(el.querySelector('[data-phase=cloning]')?.textContent).toBe('Cloning');

    el = await render({
      kind: 'error',
      id: 'e',
      ...base,
      message: 'push rejected',
      failureClass: 'GIT_ERROR',
    });
    expect(el.querySelector('[role=note]')?.textContent).toContain('Git error: push rejected');

    el = await render({
      kind: 'rate-limit',
      id: 'rl',
      ...base,
      limit: { status: 'rejected', window: 'five_hour', resetsAt: '2026-10-05T15:00:00Z' },
    });
    expect(el.textContent).toContain('Usage limit reached (five hour window).');
    expect(el.textContent).toContain('Resets');
  });
});

describe('TranscriptWork', () => {
  it('lists the work behind a hairline, fading new entries in while live', async () => {
    const fixture = TestBed.createComponent(TranscriptWork);
    fixture.componentRef.setInput('entries', [
      { kind: 'thought', id: 't', ...base, text: 'Hmm' },
      { kind: 'message', id: 'm', ...base, text: 'Done.' },
    ]);
    fixture.componentRef.setInput('live', true);
    await fixture.whenStable();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.classList).toContain('border-s');
    const rows = el.querySelectorAll('app-transcript-row');
    expect(Array.from(rows).map((r) => r.getAttribute('data-kind'))).toEqual([
      'thought',
      'message',
    ]);
    expect(rows[0].classList).toContain('thread-enter');
  });
});

describe('tool call sentences', () => {
  it('sum up a group by kind, in order of first use', () => {
    expect(
      groupSummary([
        tool(1, 'ls'),
        tool(2, 'Read a.rs', 'completed', 'read'),
        tool(3, 'git log'),
        tool(4, 'grep x', 'completed', 'search'),
        tool(5, 'Edit a.rs', 'completed', 'edit'),
        tool(6, 'Read b.rs', 'completed', 'read'),
        tool(7, 'mcp__tinfirm__ask', 'completed', 'other'),
      ]),
    ).toBe('Ran 2 commands, read 2 files, searched 1, edited 1, used 1 tool');
  });

  it('cut a leading word that repeats the verb off the target', () => {
    expect(rowTarget(tool(1, 'Read src/app.ts', 'completed', 'read'))).toBe('src/app.ts');
    expect(rowTarget(tool(1, 'Write src/new.ts', 'completed', 'edit'))).toBe('src/new.ts');
    expect(rowTarget(tool(1, 'read -r line'))).toBe('read -r line');
    expect(rowTarget(tool(1, 'Read', 'completed', 'read'))).toBe('Read');
  });
});
