import type {
  AuditCheck,
  Departure,
  Erasure,
  ErasurePreview,
  KillSwitch,
  KillSwitches,
  LegalHold,
  RetentionPreview,
  RetentionSettings,
  RetentionSweepCounts,
  SpendAmounts,
  SpendReport,
  SpendRow,
} from '../../api/generated/models';
import { AT, IDS } from './ids';

/** Governance (package 6): kill switches, departures, spend, retention, holds, erasure, checks. */

/** Nothing switched off. */
export function noSwitches(overrides: Partial<KillSwitches> = {}): KillSwitches {
  return { runsStop: null, mySuspension: null, counts: [], switches: [], ...overrides };
}

/** The company's runs stopped by Adam, with a reason. */
export function aRunsStop(overrides: Partial<NonNullable<KillSwitches['runsStop']>> = {}) {
  return {
    stoppedAt: AT.start,
    byUserId: 'u-adam',
    byName: 'Adam Admin',
    reason: 'A leaked key',
    ...overrides,
  };
}

/** GitHub paused by Adam, its pause having paused one trigger of Alice's. */
export function aPausedSystem(overrides: Partial<KillSwitch> = {}): KillSwitch {
  return {
    kind: 'SYSTEM_PAUSED',
    targetId: IDS.github,
    name: 'GitHub',
    since: AT.start,
    byUserId: 'u-adam',
    byName: 'Adam Admin',
    reason: 'The app was leaked',
    pausedTriggers: [
      {
        id: 'tr-review',
        name: 'Review pull requests',
        authorPrincipalId: IDS.alice,
        authorName: 'Alice',
      },
    ],
    ...overrides,
  };
}

export function aSuspendedMember(overrides: Partial<KillSwitch> = {}): KillSwitch {
  return {
    kind: 'MEMBER_SUSPENDED',
    targetId: IDS.dave,
    name: 'Dave',
    since: AT.start,
    byName: 'Adam Admin',
    reason: 'Laptop stolen',
    cause: 'ADMIN',
    ...overrides,
  };
}

export function anAmounts(overrides: Partial<SpendAmounts> = {}): SpendAmounts {
  return {
    runs: 3,
    requests: 12,
    inputTokens: 10_000,
    outputTokens: 2_000,
    cachedInputTokens: 400,
    cacheWriteTokens: 0,
    costUsd: 4.2,
    relayCostUsd: 0,
    subscriptionTokens: 0,
    ...overrides,
  };
}

export function aSpendRow(label: string, overrides: Partial<SpendRow> = {}): SpendRow {
  return {
    keys: [{ dimension: 'payer', id: `p-${label.toLowerCase()}`, label, folded: false }],
    amounts: anAmounts(),
    ...overrides,
  };
}

export function aSpendReport(overrides: Partial<SpendReport> = {}): SpendReport {
  return {
    from: '2026-10-01',
    to: '2026-11-01',
    zone: 'UTC',
    groupBy: ['payer'],
    bucket: 'DAY',
    rows: [aSpendRow('Alice'), aSpendRow('Bob', { amounts: anAmounts({ runs: 1, costUsd: 0.5 }) })],
    totals: anAmounts({ runs: 4, costUsd: 4.7 }),
    ...overrides,
  };
}

export function aRetention(overrides: Partial<RetentionSettings> = {}): RetentionSettings {
  return {
    runEventsDays: 90,
    chatContentDays: 30,
    contentDays: 365,
    filesDays: 365,
    memoryDays: 730,
    defaults: true,
    mode: 'PERIODS',
    openOlder: 0,
    due: { conversations: 0, runs: 0, files: 0 },
    nextSweep: noSweep(),
    ...overrides,
  };
}

export function aHold(overrides: Partial<LegalHold> = {}): LegalHold {
  return {
    id: 'hold-1',
    principalId: IDS.alice,
    principalName: 'Alice',
    reason: 'Litigation with a customer',
    setAt: AT.earlier,
    ...overrides,
  };
}

export function anErasure(overrides: Partial<Erasure> = {}): Erasure {
  return {
    erasedAt: AT.start,
    erased: {
      conversations: 2,
      messages: 14,
      decisions: 1,
      outputs: 0,
      files: 1,
      runs: 3,
      runEvents: 3,
    },
    remains: [
      {
        what: 'AUDIT',
        where: 'The company’s audit log, ids only',
        removedBy: 'Nobody: the audit log is kept for good',
      },
      {
        what: 'SOURCE_SYSTEM',
        where: 'The issue’s comments on Jira',
        removedBy: 'The company, at the system',
      },
    ],
    ...overrides,
  };
}

/** Nothing a sweep would purge. */
export function noSweep(overrides: Partial<RetentionSweepCounts> = {}): RetentionSweepCounts {
  return {
    conversations: 0,
    messages: 0,
    decisions: 0,
    outputs: 0,
    files: 0,
    runs: 0,
    runEvents: 0,
    memoryNotes: 0,
    ...overrides,
  };
}

/** Shorter content: the next sweep would take 31 conversations' content; a hold keeps some. */
export function aRetentionPreview(overrides: Partial<RetentionPreview> = {}): RetentionPreview {
  return {
    proposed: noSweep({
      conversations: 31,
      messages: 1204,
      decisions: 40,
      outputs: 12,
      files: 9,
      runs: 31,
    }),
    current: noSweep(),
    held: noSweep({ conversations: 1, messages: 40 }),
    ...overrides,
  };
}

/** What erasing Dave, who left, would do: no hold stands in the way. */
export function anErasurePreview(overrides: Partial<ErasurePreview> = {}): ErasurePreview {
  return {
    erases: {
      conversations: 2,
      messages: 14,
      decisions: 1,
      outputs: 0,
      files: 1,
      runs: 3,
      runEvents: 3,
    },
    remains: [
      {
        what: 'AUDIT',
        where: 'The audit log, which holds ids only, never content',
        removedBy: 'Nobody: it is kept for the company’s life',
      },
      {
        what: 'TOMBSTONES',
        where: 'Each conversation’s tombstone',
        removedBy: 'Nobody: they hold no text',
      },
      {
        what: 'RUNNER_SESSIONS',
        where: 'The runtime’s session home on the runner hosts',
        removedBy: 'The hosts’ operators, on the hosts',
      },
      {
        what: 'SOURCE_SYSTEM',
        where: 'What the conversations posted or read at systems',
        removedBy: 'The company, at each system',
      },
      {
        what: 'AGENT_TEXTS',
        where: 'The agents’ texts in the other conversations they took part in',
        removedBy: 'Retention, once those conversations ended',
        count: 2,
      },
      {
        what: 'USER_ACCOUNT',
        where: 'Their name and email: the instance’s user account',
        removedBy: 'An instance admin, by deleting the user',
      },
    ],
    holds: [],
    ...overrides,
  };
}

/** Dave left: one trigger to reassign, one agent passed to the admins. */
export function aDeparture(overrides: Partial<Departure> = {}): Departure {
  return {
    principalId: IDS.dave,
    userId: IDS.daveUser,
    name: 'Dave',
    leftAt: AT.start,
    closedAt: null,
    open: true,
    triggers: [{ id: 'tr-triage', name: 'Triage new issues', waitingConversations: 2 }],
    emptyGroups: [],
    connectedCredentials: [
      {
        credentialId: 'acc-svc-reader-jira',
        systemId: IDS.jira,
        systemName: 'Jira',
        credentialName: 'svc-reader',
        kind: 'TOKEN',
      },
    ],
    agents: [{ id: 'a-triager', name: 'Triager' }],
    changeRequests: [
      {
        conversationId: IDS.conversation,
        url: 'https://github.test/acme/shop/pull/7',
        state: 'OPEN',
      },
    ],
    endedConversations: 4,
    ...overrides,
  };
}

export function anAuditCheck(overrides: Partial<AuditCheck> = {}): AuditCheck {
  return {
    id: 'check-1',
    checkedAt: AT.start,
    ok: true,
    fromSeq: 100,
    headSeq: 140,
    headHash: 'c'.repeat(64),
    eventsChecked: 40,
    ...overrides,
  };
}
