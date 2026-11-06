import type { Member, MemberAccount, Role } from '../../../api/generated/models';
import { aMember, aMemberAccount, aModelAccess, IDS } from '../../../testing/fixtures';
import {
  accountsLine,
  accountStatus,
  addedOn,
  budgetLine,
  emptyTitle,
  filterCounts,
  MEMBER_ACCOUNT_STATUS,
  matchesMember,
  onlyOwnerId,
  roleChange,
} from './members-view';

const owner = (userId: string): Member => aMember({ userId, role: 'OWNER' });

describe('the only owner', () => {
  it.each<[string, Member[], boolean, string | null]>([
    ['one owner, all loaded', [owner('u-1'), aMember()], true, 'u-1'],
    ['one owner, more to load', [owner('u-1'), aMember()], false, null],
    ['two owners', [owner('u-1'), owner('u-2')], true, null],
    ['no owner loaded', [aMember()], true, null],
  ])('%s', (_, members, complete, id) => {
    expect(onlyOwnerId(members, complete)).toBe(id);
  });
});

describe('the list’s search and filters', () => {
  const ALICE = aMember();
  const OLGA = aMember({
    userId: 'u-olga',
    displayName: 'Olga Owner',
    email: 'olga@acme.test',
    role: 'OWNER',
  });
  const DAVE = aMember({
    userId: 'u-dave',
    displayName: 'Dave',
    email: 'dave@acme.test',
    suspended: true,
  });

  it('counts each filter', () => {
    expect(filterCounts([ALICE, OLGA, DAVE])).toEqual({
      all: 3,
      OWNER: 1,
      ADMIN: 0,
      MEMBER: 2,
      VIEWER: 0,
      suspended: 1,
    });
  });

  it.each<[string, Member, Parameters<typeof matchesMember>[1], string, boolean]>([
    ['everyone', ALICE, 'all', '', true],
    ['by role', OLGA, 'MEMBER', '', false],
    ['the suspended', DAVE, 'suspended', '', true],
    ['by name, ignoring case', ALICE, 'all', 'NOVAK', true],
    ['by email', OLGA, 'all', 'olga@', true],
    ['neither', ALICE, 'all', 'bob', false],
  ])('matches %s', (_, member, filter, query, shown) => {
    expect(matchesMember(member, filter, query)).toBe(shown);
  });

  it('says who is missing', () => {
    expect(emptyTitle('suspended', '')).toBe('Nobody is suspended.');
    expect(emptyTitle('suspended', 'x')).toBe('No member matches.');
    expect(emptyTitle('ADMIN', '')).toBe('No member matches.');
  });
});

describe('a member’s accounts', () => {
  it.each<[MemberAccount['status'], string, string]>([
    ['OK', 'Connected', 'ok'],
    ['RECONNECT', 'Must sign in again', 'warning'],
    ['ERROR', 'Not working', 'destructive'],
  ])('%s reads %s', (status, label, tone) => {
    expect(MEMBER_ACCOUNT_STATUS[status]).toEqual({ label, tone });
  });

  it('calls a chat account in order linked', () => {
    const slack = aMemberAccount({ systemId: IDS.slack, systemName: 'Slack', systemKind: 'SLACK' });
    expect(accountStatus(slack)).toEqual({ label: 'Linked', tone: 'ok' });
    expect(accountStatus({ ...slack, status: 'RECONNECT' }).label).toBe('Must sign in again');
  });

  it.each<[string, MemberAccount[], string, string]>([
    ['none', [], 'No account yet', 'muted'],
    [
      'in order',
      [
        aMemberAccount(),
        aMemberAccount({ systemId: IDS.slack, systemName: 'Slack', systemKind: 'SLACK' }),
      ],
      'GitHub connected · Slack linked',
      'muted',
    ],
    [
      'one to sign in again, first',
      [aMemberAccount(), aMemberAccount({ systemName: 'Jira', status: 'RECONNECT' })],
      'Jira must sign in again · GitHub connected',
      'warning',
    ],
    [
      'one failing',
      [
        aMemberAccount({ status: 'RECONNECT' }),
        aMemberAccount({ systemName: 'CRM', status: 'ERROR' }),
      ],
      'CRM not working · GitHub must sign in again',
      'destructive',
    ],
  ])('sums up %s in one line', (_, accounts, text, tone) => {
    expect(accountsLine(accounts)).toEqual({ text, tone });
  });
});

describe('a change of role', () => {
  it('says what making a member an admin grants', () => {
    const change = roleChange('MEMBER', 'ADMIN', 'Alice Analyst', false);
    expect(change.title).toBe('Make Alice Analyst an admin?');
    expect(change.lead).toBe('What it grants Alice Analyst');
    expect(change.confirm).toBe('Make admin');
    expect(change.lowering).toBe(false);
    expect(change.lines.every((l) => l.keep)).toBe(true);
    expect(change.lines).toHaveLength(3);
  });

  it.each<[Role, Role, boolean, string, string, boolean]>([
    ['ADMIN', 'MEMBER', false, 'Make Bob a member?', 'Make member', true],
    ['OWNER', 'ADMIN', false, 'Make Bob an admin?', 'Make admin', true],
    ['MEMBER', 'OWNER', false, 'Make Bob an owner?', 'Make owner', false],
    ['ADMIN', 'MEMBER', true, 'Make yourself a member?', 'Make me a member', true],
    ['OWNER', 'ADMIN', true, 'Make yourself an admin?', 'Make me an admin', true],
  ])('%s to %s (yourself: %s)', (from, to, self, title, confirm, lowering) => {
    const change = roleChange(from, to, 'Bob', self);
    expect(change.title).toBe(title);
    expect(change.confirm).toBe(confirm);
    expect(change.lowering).toBe(lowering);
    if (lowering) {
      expect(change.lead).toBe(self ? 'What changes for you' : 'What changes');
      expect(change.lines.map((l) => l.keep)).toEqual([true, false]);
    }
  });

  it('says what a viewer keeps and loses, and that the seat is freed (ADR-0077)', () => {
    const change = roleChange('MEMBER', 'VIEWER', 'Bob', false);
    expect(change.title).toBe('Make Bob a viewer?');
    expect(change.confirm).toBe('Make viewer');
    expect(change.lowering).toBe(true);
    expect(change.lines).toEqual([
      {
        text: 'Bob keeps reading the conversations and results shared with their groups, and their inbox. A viewer holds no seat.',
        keep: true,
      },
      {
        text: 'Bob no longer starts or continues conversations, owns or edits agents, authors triggers, or answers and decides requests.',
        keep: false,
      },
    ]);
    // An admin made a viewer also stops setting the company up.
    expect(roleChange('ADMIN', 'VIEWER', 'Bob', false).lines.map((l) => l.keep)).toEqual([
      true,
      false,
      false,
    ]);
  });

  it('says that a viewer made a member takes a seat', () => {
    const change = roleChange('VIEWER', 'MEMBER', 'Bob', false);
    expect(change.lowering).toBe(false);
    expect(change.lead).toBe('What it grants Bob');
    expect(change.lines.map((l) => l.text)).toEqual([
      'Takes one of the licence’s seats.',
      'Talks to agents, defines agents and triggers, answers and decides what is asked of them, and connects their own accounts and runner host.',
    ]);
    expect(roleChange('VIEWER', 'OWNER', 'Bob', false).lines[0].text).toBe(
      'Takes one of the licence’s seats.',
    );
  });

  it('tells you that lowering yourself closes this page', () => {
    const lines = roleChange('ADMIN', 'MEMBER', 'Me', true).lines.map((l) => l.text);
    expect(lines).toEqual([
      'You keep talking to agents, defining agents and triggers, and connecting your own accounts and runner host.',
      'You no longer set the company up: the Company pages close for you at once, this one too.',
    ]);
  });
});

describe('a member’s budget and when they were added', () => {
  it('reads the default or their own', () => {
    expect(
      budgetLine(
        aModelAccess({
          budgetUsd: 50,
          budgetPeriod: '30d',
          budgetOverridden: false,
          budgetResetsAt: '2026-11-12T00:00:00Z',
        }),
      ),
    ).toEqual({ whose: 'The default', amount: '$50.00', period: '30 days', resets: '12 Nov' });
    expect(budgetLine(aModelAccess({ budgetOverridden: true, budgetResetsAt: null }))).toEqual(
      expect.objectContaining({ whose: 'Their own', resets: null }),
    );
  });

  it('gives the year only for another year', () => {
    const now = new Date('2026-10-14T12:00:00Z');
    expect(addedOn('2026-10-02T10:00:00Z', now)).toBe('2 Oct');
    expect(addedOn('2026-09-21T10:00:00Z', now)).toBe('21 Sep');
    expect(addedOn('2025-10-02T10:00:00Z', now)).toBe('2 Oct 2025');
  });
});
