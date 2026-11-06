import type { KillSwitchCount, SpendKey } from '../api/generated/models';
import { anAmounts, anErasure } from '../testing/fixtures';
import {
  conversationIdOf,
  costPhrase,
  erasedPhrase,
  monthRange,
  parseThresholds,
  remainsLabel,
  spendKeyLabel,
  spendRangeError,
  stillOnPhrase,
  suspensionCause,
  switchCountPhrase,
  switchLink,
} from './governance-labels';

describe('governance labels', () => {
  it.each<[KillSwitchCount, string]>([
    [{ kind: 'RUNS_STOPPED', count: 1 }, 'The company’s runs are stopped'],
    [{ kind: 'SYSTEM_PAUSED', count: 1 }, '1 paused system'],
    [{ kind: 'SYSTEM_PAUSED', count: 2 }, '2 paused systems'],
    [{ kind: 'MEMBER_SUSPENDED', count: 3 }, '3 suspended members'],
    [{ kind: 'SERVICE_IDENTITY_SUSPENDED', count: 1 }, '1 suspended service account'],
    [{ kind: 'AGENT_PAUSED', count: 2 }, '2 paused agents'],
    [{ kind: 'TRIGGER_PAUSED', count: 1 }, '1 paused trigger'],
    [{ kind: 'PERSONAL_MCP_SERVERS_OFF', count: 1 }, 'Members’ own MCP servers are off'],
  ])('counts %o as “%s”', (count, text) => {
    expect(switchCountPhrase(count)).toBe(text);
  });

  it.each<[KillSwitchCount[], string | null]>([
    [[], null],
    [[{ kind: 'RUNS_STOPPED', count: 1 }], null],
    [[{ kind: 'SYSTEM_PAUSED', count: 0 }], null],
    [
      [
        { kind: 'SYSTEM_PAUSED', count: 1 },
        { kind: 'MEMBER_SUSPENDED', count: 2 },
      ],
      'Still on: 1 paused system and 2 suspended members.',
    ],
  ])('says what stays on after the lift: %j', (stillOn, text) => {
    expect(stillOnPhrase(stillOn)).toBe(text);
  });

  it.each<[string | null, string | null]>([
    ['ADMIN', null],
    [null, null],
    [
      'USER_DEACTIVATED',
      'Their Tinfirm account was deactivated; reactivating the user resumes them.',
    ],
  ])('names the cause %s of a suspension', (cause, text) => {
    expect(suspensionCause(cause)).toBe(text);
  });

  it.each<[Parameters<typeof switchLink>[0], string[] | null]>([
    [{ kind: 'SYSTEM_PAUSED', targetId: 's1' }, ['systems', 's1']],
    [{ kind: 'SERVICE_IDENTITY_SUSPENDED', targetId: 'p1' }, ['service-identities', 'p1']],
    [{ kind: 'MEMBER_SUSPENDED', targetId: 'p2' }, ['members']],
    [{ kind: 'AGENT_PAUSED', targetId: 'a1' }, ['agents', 'a1']],
    [{ kind: 'TRIGGER_PAUSED', targetId: 't1' }, ['triggers', 't1']],
    [{ kind: 'PERSONAL_MCP_SERVERS_OFF', targetId: 'c1' }, ['settings']],
    [{ kind: 'RUNS_STOPPED', targetId: 'c1' }, null],
  ])('links the switch %o', (kill, link) => {
    expect(switchLink(kill)).toEqual(link);
  });

  it.each<[SpendKey, string]>([
    [{ dimension: 'payer', label: 'Alice', folded: false }, 'Alice'],
    [{ dimension: 'actor', label: 'Dave (left)', folded: false }, 'Dave (left)'],
    [{ dimension: 'agent', label: null, folded: false }, '(unnamed)'],
    [
      { dimension: 'model', id: 'claude-sonnet-4-5', label: null, folded: false },
      'claude-sonnet-4-5',
    ],
    [{ dimension: 'period', id: '2026-10-01', folded: false }, '2026-10-01'],
    [
      { dimension: 'conversation', key: 'SHOP-1-1', title: 'Fix it', folded: false },
      'SHOP-1-1 · Fix it',
    ],
    // Another person's personal conversation, for the Owner: by key only.
    [{ dimension: 'conversation', key: 'BOB-3', title: null, folded: false }, 'BOB-3'],
    // ADMIN: another person's personal conversations folded into one row.
    [
      { dimension: 'conversation', folded: true, personPrincipalId: 'p-bob' },
      'Their personal conversations',
    ],
  ])('labels the spend key %o as “%s”', (key, text) => {
    expect(spendKeyLabel(key)).toBe(text);
  });

  it.each<[Partial<Parameters<typeof costPhrase>[0]>, string]>([
    [{}, '$4.20'],
    [{ relayCostUsd: 1.2 }, '$4.20 · $1.20 by Tinfirm’s count'],
    [{ costUsd: 0, subscriptionTokens: 12_400 }, '$0.00 · 12.4K subscription tokens'],
  ])('says what a row cost: %o', (amounts, text) => {
    expect(costPhrase(anAmounts(amounts))).toBe(text);
  });

  it.each<[string, string, string | null]>([
    ['2026-10-01', '2026-11-01', null],
    ['2026-10-01', '2026-10-01', 'The range must end after it starts.'],
    ['2026-10-02', '2026-10-01', 'The range must end after it starts.'],
    ['2025-01-01', '2026-01-03', 'A range covers at most 366 days.'],
    ['', '2026-10-01', 'Enter both days.'],
  ])('checks the range %s to %s', (from, to, error) => {
    expect(spendRangeError(from, to)).toBe(error);
  });

  it('starts at the month of today', () => {
    expect(monthRange(new Date(2026, 9, 13))).toEqual({ from: '2026-10-01', to: '2026-11-01' });
    expect(monthRange(new Date(2026, 11, 31))).toEqual({ from: '2026-12-01', to: '2027-01-01' });
  });

  it.each<[string, number[] | null]>([
    ['80, 100', [80, 100]],
    ['100 50', [50, 100]],
    ['90%', [90]],
    ['50, 80, 100', [50, 80, 100]],
    ['25, 50, 80, 100', null],
    ['', null],
    ['80, 80', null],
    ['0', null],
    ['101', null],
    ['eighty', null],
  ])('reads the thresholds “%s”', (text, values) => {
    expect(parseThresholds(text)).toEqual(values);
  });

  it('says what an erasure removed, and nothing when it removed nothing', () => {
    expect(erasedPhrase(anErasure().erased)).toBe(
      'Erased 2 conversations, 14 messages, 1 decision, 1 file, the texts of 3 turns and the transcripts of 3 turns.',
    );
    expect(
      erasedPhrase({
        conversations: 0,
        messages: 0,
        decisions: 0,
        outputs: 0,
        files: 0,
        runs: 0,
        runEvents: 0,
        other: { CHAT_USER: 1 },
      }),
    ).toBe('Erased 1 chat user.');
    // The chats' and the triggers' modules' kinds, by their keys; thousands marked.
    expect(
      erasedPhrase({
        conversations: 0,
        messages: 0,
        decisions: 0,
        outputs: 0,
        files: 0,
        runs: 0,
        runEvents: 0,
        other: { messages: 1204, chatUsers: 1, chatLinks: 2, firingActors: 3 },
      }),
    ).toBe('Erased 1,204 messages, 1 chat profile, 2 sign-in links and 3 firing actors.');
    expect(
      erasedPhrase({
        conversations: 1,
        messages: 0,
        decisions: 0,
        outputs: 0,
        files: 0,
        runs: 0,
        runEvents: 0,
        other: { memoryNotes: 3 },
      }),
    ).toBe('Erased 1 conversation and 3 memory notes.');
    expect(
      erasedPhrase({
        conversations: 0,
        messages: 0,
        decisions: 0,
        outputs: 0,
        files: 0,
        runs: 0,
        runEvents: 0,
      }),
    ).toBe('Nothing was left to erase.');
  });

  it.each<[string, string]>([
    ['AUDIT', 'The audit log'],
    ['RUNNER_SESSIONS', 'Runner hosts’ sessions'],
    ['SOURCE_SYSTEM', 'The source system'],
    ['SOMETHING_NEW', 'something new'],
  ])('names what remains: %s', (what, text) => {
    expect(remainsLabel({ what })).toBe(text);
  });

  it.each<[string, string | null]>([
    [
      'https://tinfirm.acme.test/c/c1/conversations/0192F0C4-0000-7C3A-9B4E-2F1A3C5D7E9F?x=1',
      '0192f0c4-0000-7c3a-9b4e-2f1a3c5d7e9f',
    ],
    [' 0192f0c4-0000-7c3a-9b4e-2f1a3c5d7e9f ', '0192f0c4-0000-7c3a-9b4e-2f1a3c5d7e9f'],
    ['SHOP-12-1', null],
    ['see 0192f0c4-0000-7c3a-9b4e-2f1a3c5d7e9f there', null],
  ])('finds the conversation in “%s”', (text, id) => {
    expect(conversationIdOf(text)).toBe(id);
  });
});
