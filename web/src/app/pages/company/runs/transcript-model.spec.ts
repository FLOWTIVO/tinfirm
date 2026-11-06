import type { RunEvent } from '../../../api/generated/models';
import { unifiedDiff } from './diff';
import {
  agentMessages,
  countsText,
  groupActivity,
  type LogsItem,
  type MessageItem,
  type ToolItem,
  TranscriptBuilder,
  workCounts,
  workEntries,
} from './transcript-model';

let seq = 0;
function ev(type: RunEvent['type'], payload: RunEvent['payload']): RunEvent {
  seq += 1;
  return { seq, type, at: `2026-09-26T10:00:${String(seq % 60).padStart(2, '0')}Z`, payload };
}

describe('TranscriptBuilder', () => {
  beforeEach(() => (seq = 0));

  it('coalesces consecutive message chunks into one message', () => {
    const b = new TranscriptBuilder();
    b.apply([
      ev('AGENT_MESSAGE', { text: 'Hello ' }),
      ev('AGENT_MESSAGE', { text: 'world' }),
      ev('USAGE', { inputTokens: 10, outputTokens: 2 }),
      ev('AGENT_MESSAGE', { text: '!' }),
    ]);
    const items = b.snapshot();
    expect(items).toHaveLength(1);
    expect((items[0] as MessageItem).text).toBe('Hello world!');
    // USAGE feeds the meter, not the rows.
    expect(b.summary().usage).toMatchObject({ inputTokens: 10, outputTokens: 2 });
  });

  it('keeps coalescing across batches without touching earlier snapshots', () => {
    const b = new TranscriptBuilder();
    b.apply([ev('AGENT_MESSAGE', { text: 'a' })]);
    const first = b.snapshot();
    b.apply([ev('AGENT_MESSAGE', { text: 'b' })]);
    const second = b.snapshot();
    expect((first[0] as MessageItem).text).toBe('a');
    expect((second[0] as MessageItem).text).toBe('ab');
    expect(second[0]).not.toBe(first[0]);
    expect(second[0].id).toBe(first[0].id);
  });

  it('splits messages around other rows and keeps thoughts separate', () => {
    const b = new TranscriptBuilder();
    b.apply([
      ev('AGENT_THOUGHT', { text: 'Let me think' }),
      ev('AGENT_THOUGHT', { text: ' harder' }),
      ev('AGENT_MESSAGE', { text: 'Reading the file.' }),
      ev('TOOL_CALL', {
        toolCallId: 't1',
        title: 'Read README.md',
        kind: 'read',
        status: 'pending',
      }),
      ev('AGENT_MESSAGE', { text: 'Done.' }),
    ]);
    expect(b.snapshot().map((i) => i.kind)).toEqual(['thought', 'message', 'tool', 'message']);
    expect(b.snapshot()[0]).toMatchObject({ kind: 'thought', text: 'Let me think harder' });
  });

  it('merges TOOL_CALL_UPDATEs into their tool row', () => {
    const b = new TranscriptBuilder();
    b.apply([
      ev('TOOL_CALL', {
        toolCallId: 't1',
        title: 'Edit src/a.ts',
        kind: 'edit',
        status: 'pending',
        locations: [{ path: 'src/a.ts', line: 3 }],
      }),
      ev('AGENT_MESSAGE', { text: 'Editing…' }),
      ev('TOOL_CALL_UPDATE', { toolCallId: 't1', status: 'in_progress' }),
    ]);
    b.apply([
      ev('TOOL_CALL_UPDATE', {
        toolCallId: 't1',
        status: 'completed',
        output: 'ok',
        diffs: [{ path: 'src/a.ts', oldText: 'a\n', newText: 'b\n' }],
      }),
    ]);
    const tool = b.snapshot()[0] as ToolItem;
    expect(b.snapshot()).toHaveLength(2);
    expect(tool).toMatchObject({
      kind: 'tool',
      id: 'tool-t1',
      title: 'Edit src/a.ts',
      toolKind: 'edit',
      status: 'completed',
      output: 'ok',
    });
    expect(tool.locations).toEqual([{ path: 'src/a.ts', line: 3 }]);
    expect(tool.diffs).toHaveLength(1);
  });

  it('groups logs, replaces plans in place and tracks phases', () => {
    const b = new TranscriptBuilder();
    b.apply([
      ev('STATUS', { phase: 'cloning', message: 'Cloning' }),
      ev('LOG', { level: 'info', message: 'one' }),
      ev('LOG', { level: 'warn', message: 'two' }),
      ev('PLAN', { entries: [{ content: 'Step 1', status: 'pending' }] }),
      ev('PLAN', { entries: [{ content: 'Step 1', status: 'completed' }] }),
      ev('STATUS', { phase: 'running' }),
      ev('GIT', { action: 'pushed', branch: 'agent/T-1', commits: 2, headSha: 'abcdef123' }),
      ev('ERROR', { message: 'boom', failureClass: 'GIT_ERROR' }),
      ev('GIT', {
        componentId: 'comp-web',
        dir: 'web',
        action: 'pushed',
        branch: 'agent/T-1',
        commits: 1,
      }),
    ]);
    const kinds = b.snapshot().map((i) => i.kind);
    expect(kinds).toEqual(['status', 'logs', 'plan', 'status', 'git', 'error', 'git']);
    // Stored events of single-repository runs have no directory; newer ones name the repository.
    expect(b.snapshot()[4]).toMatchObject({ kind: 'git', dir: null, commits: 2 });
    expect(b.snapshot()[6]).toMatchObject({ kind: 'git', dir: 'web', commits: 1 });
    expect((b.snapshot()[1] as LogsItem).lines.map((l) => l.message)).toEqual(['one', 'two']);
    expect(b.snapshot()[2]).toMatchObject({
      entries: [{ content: 'Step 1', status: 'completed' }],
    });
    expect(b.summary()).toMatchObject({ phase: 'running', phases: ['cloning', 'running'] });
  });

  it('keeps rate-limit warnings as rows and the latest as summary', () => {
    const b = new TranscriptBuilder();
    b.apply([
      ev('RATE_LIMIT', {
        status: 'rejected',
        resetsAt: '2026-09-26T15:00:00Z',
        window: 'five_hour',
      }),
      ev('RATE_LIMIT', { status: 'allowed' }),
    ]);
    expect(b.snapshot().map((i) => i.kind)).toEqual(['rate-limit']);
    expect(b.summary().rateLimit?.status).toBe('allowed');
  });

  it('ignores duplicates and out-of-date events', () => {
    const b = new TranscriptBuilder();
    const first = ev('AGENT_MESSAGE', { text: 'x' });
    b.apply([first, first, { ...first, seq: 0 }]);
    expect((b.snapshot()[0] as MessageItem).text).toBe('x');
    expect(b.summary()).toMatchObject({ eventCount: 1, lastSeq: 1 });
  });

  it('handles 20 000 events in small batches quickly', () => {
    const b = new TranscriptBuilder();
    const start = performance.now();
    for (let batch = 0; batch < 200; batch++) {
      const events: RunEvent[] = [];
      for (let i = 0; i < 100; i++) {
        events.push(
          i % 10 === 0
            ? ev('LOG', { level: 'debug', message: `line ${i}` })
            : ev('AGENT_MESSAGE', { text: 'chunk ' }),
        );
      }
      b.apply(events);
      b.snapshot();
    }
    expect(b.summary().eventCount).toBe(20_000);
    expect(performance.now() - start).toBeLessThan(2000);
  });
});

describe('groupActivity', () => {
  beforeEach(() => (seq = 0));

  it('folds every stretch of tool calls and logs, even a lone one, between the other rows', () => {
    const b = new TranscriptBuilder();
    b.apply([
      ev('STATUS', { phase: 'running' }),
      ev('LOG', { level: 'info', message: 'alone' }),
      ev('AGENT_MESSAGE', { text: 'Looking.' }),
      ev('TOOL_CALL', { toolCallId: 'a', title: 'ls' }),
      ev('LOG', { level: 'info', message: 'between' }),
      ev('TOOL_CALL', { toolCallId: 'b', title: 'cat' }),
      ev('AGENT_THOUGHT', { text: 'Hmm' }),
      ev('TOOL_CALL', { toolCallId: 'c', title: 'grep' }),
      ev('TOOL_CALL', { toolCallId: 'd', title: 'sed' }),
      ev('AGENT_MESSAGE', { text: 'Done.' }),
    ]);
    const rows = groupActivity(b.snapshot());
    expect(rows.map((r) => r.kind)).toEqual([
      'status',
      'activity',
      'message',
      'activity',
      'thought',
      'activity',
      'message',
    ]);
    expect(rows[1].kind === 'activity' && rows[1].items.map((i) => i.kind)).toEqual(['logs']);
    const group = rows[3];
    expect(group.kind === 'activity' && group.items.map((i) => i.kind)).toEqual([
      'tool',
      'logs',
      'tool',
    ]);
    expect(group.id).toBe('activity-tool-a');
    expect(rows[5].id).toBe('activity-tool-c');
  });

  it('keeps an unchanged group and its id as the run grows', () => {
    const b = new TranscriptBuilder();
    b.apply([
      ev('TOOL_CALL', { toolCallId: 'a', title: 'ls' }),
      ev('TOOL_CALL', { toolCallId: 'b', title: 'cat' }),
      ev('AGENT_MESSAGE', { text: 'Next.' }),
      ev('TOOL_CALL', { toolCallId: 'c', title: 'grep' }),
      ev('TOOL_CALL', { toolCallId: 'd', title: 'sed' }),
    ]);
    const first = groupActivity(b.snapshot());
    b.apply([ev('TOOL_CALL_UPDATE', { toolCallId: 'd', status: 'completed' })]);
    const second = groupActivity(b.snapshot(), first);
    expect(second[0]).toBe(first[0]);
    expect(second[2]).not.toBe(first[2]);
    expect(second[2].id).toBe(first[2].id);
  });
});

describe('workEntries (the work of a turn in a thread)', () => {
  beforeEach(() => (seq = 0));

  const items = (events: RunEvent[]) => {
    const b = new TranscriptBuilder();
    b.apply(events);
    return b.snapshot();
  };

  const RUN = () => [
    ev('STATUS', { phase: 'cloning' }),
    ev('GIT', { action: 'cloned', branch: 'agent/SHOP-241', headSha: '62d0292abc' }),
    ev('STATUS', { phase: 'running' }),
    ev('TOOL_CALL', { toolCallId: 'a', title: 'git log', kind: 'execute' }),
    ev('LOG', { level: 'info', message: 'x' }),
    ev('AGENT_THOUGHT', { text: 'Hmm' }),
    ev('PLAN', { entries: [{ content: 'Look', status: 'completed' }] }),
    ev('TOOL_CALL', { toolCallId: 'b', title: 'Read a.rs', kind: 'read' }),
    ev('AGENT_MESSAGE', { text: 'Looking.' }),
    ev('PLAN', { entries: [{ content: 'Look', status: 'in_progress' }] }),
    ev('ERROR', { message: 'boom' }),
    ev('AGENT_MESSAGE', { text: 'Done.' }),
    ev('GIT', { action: 'pushed', branch: 'agent/SHOP-241' }),
  ];

  it('leaves out phases and git results but the checkout, and groups every stretch of calls', () => {
    const work = workEntries(items(RUN()), { live: true, dropLastMessage: false });
    expect(work.map((e) => e.kind)).toEqual([
      'git',
      'activity',
      'thought',
      'activity',
      'message',
      'plan',
      'error',
      'message',
    ]);
    const first = work[1];
    expect(first.kind === 'activity' && first.items.map((i) => i.kind)).toEqual(['tool', 'logs']);
    // Only the latest plan shows, once.
    expect(work.filter((e) => e.kind === 'plan')).toHaveLength(1);
  });

  it('drops the plan, the answer and the failure shown elsewhere once the run ended', () => {
    const work = workEntries(items(RUN()), {
      live: false,
      dropLastMessage: true,
      failureMessage: 'boom',
    });
    expect(work.map((e) => e.kind)).toEqual(['git', 'activity', 'thought', 'activity', 'message']);
    expect(work.at(-1)).toMatchObject({ kind: 'message', text: 'Looking.' });
  });

  it('keeps unchanged groups as they were', () => {
    const b = new TranscriptBuilder();
    b.apply([
      ev('TOOL_CALL', { toolCallId: 'a', title: 'ls', kind: 'execute' }),
      ev('AGENT_MESSAGE', { text: 'Next.' }),
      ev('TOOL_CALL', { toolCallId: 'c', title: 'grep', kind: 'search' }),
    ]);
    const first = workEntries(b.snapshot(), { live: true, dropLastMessage: false });
    b.apply([ev('TOOL_CALL_UPDATE', { toolCallId: 'c', status: 'completed' })]);
    const second = workEntries(b.snapshot(), { live: true, dropLastMessage: false }, first);
    expect(second[0]).toBe(first[0]);
    expect(second[2]).not.toBe(first[2]);
  });

  it('counts the work, without the answer', () => {
    const counts = workCounts(
      items([
        ev('TOOL_CALL', { toolCallId: 'a', title: 'ls' }),
        ev('AGENT_MESSAGE', { text: 'a' }),
        ev('AGENT_THOUGHT', { text: 'b' }),
        ev('TOOL_CALL', { toolCallId: 'b', title: 'ls' }),
        ev('AGENT_MESSAGE', { text: 'c' }),
      ]),
      true,
    );
    expect(countsText(counts)).toBe('· 2 tool calls · 1 message · 1 thought');
    expect(countsText({ toolCalls: 1, messages: 0, thoughts: 0 })).toBe('· 1 tool call');
  });
});

describe('agentMessages (what a folded turn said, from its messages alone)', () => {
  beforeEach(() => (seq = 0));

  it('joins the chunks of a message as the transcript does, and splits where other events were', () => {
    const events = [
      ev('AGENT_MESSAGE', { text: 'Hello ' }),
      ev('USAGE', { inputTokens: 10 }),
      ev('AGENT_MESSAGE', { text: 'world.' }),
      ev('TOOL_CALL', { toolCallId: 't1', title: 'Read a.ts' }),
      ev('AGENT_MESSAGE', { text: 'Done.' }),
    ];
    const all = new TranscriptBuilder();
    all.apply(events);
    const said = agentMessages(events.filter((e) => e.type !== 'TOOL_CALL'));
    expect(said.map((m) => [m.id, m.text])).toEqual([
      ['message-1', 'Hello world.'],
      ['message-5', 'Done.'],
    ]);
    expect(said).toEqual(all.snapshot().filter((item) => item.kind === 'message'));
  });

  it('splits at a gap even after usage, and says nothing without messages', () => {
    const events = [
      ev('AGENT_MESSAGE', { text: 'a' }),
      ev('USAGE', {}),
      ev('AGENT_THOUGHT', { text: 'hmm' }),
      ev('AGENT_MESSAGE', { text: 'b' }),
    ];
    expect(agentMessages([events[0], events[1], events[3]]).map((m) => m.text)).toEqual(['a', 'b']);
    expect(agentMessages([events[1]])).toEqual([]);
  });
});

describe('unifiedDiff', () => {
  it('produces hunks with context and line numbers', () => {
    const oldText = ['a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'i'].join('\n');
    const newText = ['a', 'b', 'c', 'd', 'E', 'f', 'g', 'h', 'i'].join('\n');
    const { lines, stats } = unifiedDiff(oldText, newText);
    expect(stats).toEqual({ added: 1, removed: 1 });
    expect(lines[0]).toMatchObject({ type: 'hunk', text: '@@ -2 +2 @@' });
    expect(lines.filter((l) => l.type === 'del')).toEqual([
      { type: 'del', text: 'e', oldNo: 5, newNo: null },
    ]);
    expect(lines.filter((l) => l.type === 'add')).toEqual([
      { type: 'add', text: 'E', oldNo: null, newNo: 5 },
    ]);
    // 3 lines of context on each side, not the whole file.
    expect(lines.filter((l) => l.type === 'context')).toHaveLength(6);
  });

  it('shows a new file as all additions', () => {
    const { lines, stats } = unifiedDiff(null, 'x\ny\n');
    expect(stats).toEqual({ added: 2, removed: 0 });
    expect(lines.slice(1).map((l) => l.type)).toEqual(['add', 'add']);
  });
});
