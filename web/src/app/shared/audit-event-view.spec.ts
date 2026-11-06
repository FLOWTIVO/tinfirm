import type { AuditActor } from '../api/generated/models';
import {
  actorView,
  dayGroups,
  dayHeading,
  dayPhrase,
  type PrincipalKind,
  targetNoun,
  targetView,
} from './audit-event-view';

const NOW = new Date(2026, 9, 14, 14, 20);
const DAVE = '0192f0c4-7d1e-7c3a-9b4e-2f1a3c5d7e9f';
const SVC = '5b1e0c4a-7d1e-7c3a-9b4e-2f1a3c5d7e9f';

describe('how the audit log reads an event', () => {
  it.each<[AuditActor, Partial<ReturnType<typeof actorView>>]>([
    [
      { type: 'USER', id: DAVE, display: 'Dave Malý' },
      { name: 'Dave Malý', initials: 'DM', kind: 'person', muted: false, noun: 'member' },
    ],
    [
      { type: 'AGENT', id: SVC, display: 'Analyst' },
      { name: 'Analyst', initials: 'AN', kind: 'agent', muted: false, noun: 'agent' },
    ],
    [
      { type: 'SYSTEM', id: null, display: 'system' },
      { name: 'Tinfirm', initials: 'T', kind: 'tinfirm', muted: true, noun: 'its own event' },
    ],
    [
      { type: 'USER', id: null, display: 'anonymous' },
      { name: 'Anonymous', initials: '?', muted: true, noun: 'unknown person' },
    ],
    [
      { type: 'USER', id: DAVE, display: 'deleted user' },
      { name: 'Deleted user', initials: '?', muted: true },
    ],
    [
      { type: 'USER', id: DAVE, display: 'erased person' },
      { name: 'Erased person', initials: '?', kind: 'person', muted: true, noun: 'member' },
    ],
  ])('names the actor %o', (actor, view) => {
    expect(actorView(actor)).toMatchObject(view);
  });

  it.each<[string, string]>([
    ['runner_host', 'Runner host'],
    ['user', 'Member'],
    ['principal_model_key', 'Model key'],
    ['conversation_binding', 'Binding'],
    ['something_new', 'Something new'],
  ])('calls the target type %s “%s”', (type, noun) => {
    expect(targetNoun(type)).toBe(noun);
  });

  it('calls the instance’s users users', () => {
    expect(targetNoun('user', true)).toBe('User');
  });

  describe('the target', () => {
    const kinds = new Map<string, PrincipalKind>([
      [DAVE, 'member'],
      [SVC, 'service account'],
    ]);

    it.each<
      [
        string,
        {
          action?: string;
          targetType?: string;
          targetId?: string;
          targetName?: string | null;
          details?: Record<string, unknown>;
        },
        object | null,
      ]
    >([
      ['none', {}, null],
      [
        'a service account the server named',
        { targetType: 'principal', targetId: SVC, targetName: 'svc-dev' },
        { noun: 'Service account', name: 'svc-dev', shortId: '5b1e0c4a' },
      ],
      [
        'a system’s credential its details name (ADR-0072)',
        {
          action: 'account.connected',
          targetType: 'account',
          targetId: 'acc-1',
          targetName: null,
          details: { credential: 'CI bot', systemId: 's-1' },
        },
        { noun: 'Credential', name: 'CI bot', shortId: 'acc-1' },
      ],
      [
        'a credential lent to a service account',
        {
          action: 'credential.lent',
          targetType: 'account',
          targetId: 'acc-1',
          targetName: 'CI bot',
          details: { serviceAccountId: SVC, access: 'WRITE' },
        },
        { noun: 'Credential', name: 'CI bot' },
      ],
      [
        'a member’s own account',
        { action: 'account.connected', targetType: 'account', targetId: 'acc-2' },
        { noun: 'Account', name: undefined },
      ],
      [
        'a member who left',
        { targetType: 'principal', targetId: DAVE, targetName: 'Dave Malý (left)' },
        { noun: 'Member', name: 'Dave Malý (left)' },
      ],
      [
        'a principal the page does not know',
        { targetType: 'principal', targetId: '9ca950af-1111-7c3a-9b4e-2f1a3c5d7e9f' },
        { noun: 'Principal', name: undefined, shortId: '9ca950af' },
      ],
      [
        'a member’s model key',
        { targetType: 'principal_model_key', targetId: DAVE, targetName: 'Dave Malý' },
        { noun: 'Model key of', name: 'Dave Malý', shortId: '0192f0c4' },
      ],
      [
        'a model key without a name (an erased person’s)',
        { targetType: 'principal_model_key', targetId: DAVE, targetName: null },
        { noun: 'Model key', name: undefined },
      ],
      [
        'one the server named over its details’ old name',
        {
          targetType: 'runner_host',
          targetId: 'h-1',
          targetName: 'build-02',
          details: { name: 'build-01' },
        },
        { noun: 'Runner host', name: 'build-02', shortId: 'h-1' },
      ],
      [
        'a gone one its details name',
        { targetType: 'system', targetId: 's-1', targetName: null, details: { name: 'Jira' } },
        { noun: 'System', name: 'Jira', shortId: 's-1' },
      ],
      [
        'an unknown one',
        { targetType: 'run', targetId: '9ca950af-0000-7c3a-9b4e-2f1a3c5d7e9f' },
        { noun: 'Run', name: undefined, shortId: '9ca950af' },
      ],
      ['a type alone', { targetType: 'company' }, { noun: 'Company', id: undefined }],
    ])('reads %s', (_, event, view) => {
      const result = targetView(event, kinds);
      if (view === null) expect(result).toBeNull();
      else expect(result).toMatchObject(view);
    });
  });

  it.each<[Date, string, string, string]>([
    [new Date(2026, 9, 14, 3, 12), 'Today', 'Wed 14 Oct', 'today'],
    [new Date(2026, 9, 13, 23, 59), 'Yesterday', 'Tue 13 Oct', 'yesterday'],
    [new Date(2026, 9, 12, 9), '12 Oct', 'Monday', 'on 12 Oct'],
    [new Date(2025, 9, 12, 9), '12 Oct 2025', 'Sunday', 'on 12 Oct 2025'],
  ])('heads the day %s as “%s”', (date, label, sub, phrase) => {
    expect(dayHeading(date, NOW)).toMatchObject({ label, date: sub });
    expect(dayPhrase(date, NOW)).toBe(phrase);
  });

  it('groups events, newest first, by the reader’s day', () => {
    const at = (d: number, h: number) => ({ occurredAt: new Date(2026, 9, d, h).toISOString() });
    const groups = dayGroups([at(14, 12), at(14, 9), at(13, 18), at(11, 16)], NOW);
    expect(groups.map((g) => [g.heading.label, g.events.length])).toEqual([
      ['Today', 2],
      ['Yesterday', 1],
      ['11 Oct', 1],
    ]);
    expect(groups[0].heading.long).toBe('Wednesday 14 October 2026');
  });
});
