import type {
  InboxTrigger,
  Trigger,
  TriggerAccount,
  TriggerChoice,
  TriggerChoices,
  TriggerFiring,
  TriggerOpenToAnyone,
  TriggerPayer,
  TriggerPoll,
  TriggerPreview,
  TriggerSummary,
} from '../../api/generated/models';
import { AT, IDS } from './ids';

/**
 * Tag `Triggers` (the package 4 contract, §6): "Prepare assigned issues", Alice's trigger on Jira
 * that fires when an issue is assigned to the source account, acting with svc-reader's accounts
 * and paid by its budget, with Analysts as its team.
 */

export const TRIGGER_IDS = {
  trigger: 't-prepare',
  firing: 'f1',
} as const;

export function aTriggerPayer(overrides: Partial<TriggerPayer> = {}): TriggerPayer {
  return {
    principalId: IDS.svcReader,
    name: 'svc-reader',
    kind: 'SERVICE',
    modelAccess: 'BUDGET',
    state: 'READY',
    reason: null,
    detail: null,
    until: null,
    ...overrides,
  };
}

export function aTriggerAccount(overrides: Partial<TriggerAccount> = {}): TriggerAccount {
  return {
    systemId: IDS.jira,
    systemName: 'Jira',
    accountId: 'acc-svc-reader-jira',
    holderPrincipalId: IDS.svcReader,
    holderName: 'svc-reader',
    holderKind: 'SERVICE',
    credentialName: 'reader-bot',
    externalLogin: 'svc-reader',
    access: 'WRITE',
    filledFrom: 'CHOICE',
    status: 'OK',
    ...overrides,
  };
}

export function aTriggerFiring(overrides: Partial<TriggerFiring> = {}): TriggerFiring {
  return {
    id: TRIGGER_IDS.firing,
    triggerId: TRIGGER_IDS.trigger,
    subjectKey: 'SHOP-241',
    event: 'ASSIGNED',
    value: null,
    changedAt: AT.start,
    dueAt: null,
    manualBy: null,
    once: false,
    outcome: 'STARTED',
    reason: null,
    chainDepth: 0,
    conversationId: IDS.conversation,
    runId: IDS.run,
    createdAt: AT.started,
    ...overrides,
  };
}

export function aTriggerSummary(overrides: Partial<TriggerSummary> = {}): TriggerSummary {
  return {
    id: TRIGGER_IDS.trigger,
    name: 'Prepare assigned issues',
    sentence: 'When an issue in SHOP is assigned to svc-reader, Business analyst prepares it.',
    source: 'TRACKER',
    systemId: IDS.jira,
    systemName: 'Jira',
    agentId: IDS.agent,
    agentName: 'Business analyst',
    status: 'ACTIVE',
    pausedReason: null,
    waitingReason: null,
    authorPrincipalId: IDS.alice,
    authorName: 'Alice Novak',
    teamGroupId: IDS.analysts,
    teamName: 'Analysts',
    payerPrincipalId: IDS.svcReader,
    payerName: 'svc-reader',
    lastFiring: aTriggerFiring(),
    createdAt: AT.earlier,
    updatedAt: AT.earlier,
    ...overrides,
  };
}

export function aTrigger(overrides: Partial<Trigger> = {}): Trigger {
  return {
    id: TRIGGER_IDS.trigger,
    name: 'Prepare assigned issues',
    sentence: 'When an issue in SHOP is assigned to svc-reader, Business analyst prepares it.',
    source: 'TRACKER',
    systemId: IDS.jira,
    systemName: 'Jira',
    events: [{ event: 'ASSIGNED', value: null }],
    subject: 'ISSUE',
    scope: 'SHOP',
    conditions: [],
    repeat: 'EVERY_TIME',
    schedule: null,
    agentId: IDS.agent,
    agentName: 'Business analyst',
    acceptedVersion: 4,
    currentVersion: 4,
    wideningPending: false,
    instruction: 'Prepare the issue: read it and the code, then write a brief as a comment.',
    authorPrincipalId: IDS.alice,
    authorName: 'Alice Novak',
    teamGroupId: IDS.analysts,
    teamName: 'Analysts',
    payer: aTriggerPayer(),
    results: { comment: true, review: false, onSuccess: [], onFailure: [] },
    maxFiringsPerHour: 30,
    status: 'ACTIVE',
    pausedReason: null,
    pausedAt: null,
    waitingReason: null,
    waitingSince: null,
    armedAt: AT.earlier,
    missedCount: 0,
    rateSkippedCount: 0,
    openEventsAcknowledgedAt: null,
    accounts: [aTriggerAccount()],
    unavailableSystems: [{ systemId: IDS.github, systemName: 'GitHub' }],
    noAccountSystems: [],
    openConversations: 1,
    lastFiring: aTriggerFiring(),
    rights: {
      edits: true,
      runs: true,
      pauses: true,
      resumes: true,
      deletes: true,
      reassigns: false,
    },
    deletedAt: null,
    createdAt: AT.earlier,
    updatedAt: AT.earlier,
    ...overrides,
  };
}

/** A schedule: the monthly report, `0 9 1 * *` in Europe/Prague (V15). */
export function aScheduleTrigger(overrides: Partial<Trigger> = {}): Trigger {
  return aTrigger({
    id: 't-monthly',
    name: 'Monthly report',
    sentence:
      'At 09:00 on the 1st of every month (Europe/Prague), Business analyst writes the report.',
    source: 'SCHEDULE',
    systemId: null,
    systemName: null,
    events: [{ event: 'SCHEDULE', value: null }],
    subject: 'ANY',
    scope: null,
    schedule: {
      cron: '0 9 1 * *',
      zone: 'Europe/Prague',
      overlap: 'SKIP',
      catchUp: 'NONE',
      nextRunAt: AT.resets,
      lastRunAt: null,
      lastOutcome: null,
      nextFirings: ['2026-11-01T08:00:00Z', '2026-12-01T08:00:00Z', '2027-01-01T08:00:00Z'],
    },
    accounts: [],
    unavailableSystems: [
      { systemId: IDS.github, systemName: 'GitHub' },
      { systemId: IDS.jira, systemName: 'Jira' },
    ],
    noAccountSystems: [],
    ...overrides,
  });
}

export function anOpenToAnyone(overrides: Partial<TriggerOpenToAnyone> = {}): TriggerOpenToAnyone {
  return {
    who: 'anyone who may comment on issues in SHOP',
    writes: [
      {
        systemId: IDS.jira,
        systemName: 'Jira',
        tool: 'comment',
        holderName: 'Alice Novak',
        own: true,
      },
    ],
    acknowledgementNeeded: true,
    acknowledgementMissing: true,
    ...overrides,
  };
}

export function aTriggerPreview(overrides: Partial<TriggerPreview> = {}): TriggerPreview {
  return {
    accounts: [
      {
        systemId: IDS.jira,
        systemName: 'Jira',
        accountId: 'acc-svc-reader-jira',
        holderPrincipalId: IDS.svcReader,
        holderName: 'svc-reader',
        holderKind: 'SERVICE',
        credentialName: 'reader-bot',
        externalLogin: 'svc-reader',
        access: 'WRITE',
        status: 'OK',
      },
    ],
    unavailableSystems: [{ systemId: IDS.github, systemName: 'GitHub' }],
    noAccountSystems: [],
    payer: aTriggerPayer(),
    hosts: { runnable: true, eligible: 1, reason: null },
    sentence: 'When an issue in SHOP is assigned to svc-reader, Business analyst prepares it.',
    openToAnyone: null,
    nextFirings: [],
    openConversationsKeepingAccounts: null,
    ...overrides,
  };
}

/** The one choice: Alice herself (Jira to connect, GitHub her own), and svc-reader (both). */
export function aTriggerChoice(overrides: Partial<TriggerChoice> = {}): TriggerChoice {
  return {
    principalId: IDS.svcReader,
    name: 'svc-reader',
    kind: 'SERVICE',
    systems: [
      {
        systemId: IDS.jira,
        systemName: 'Jira',
        accountId: 'acc-svc-reader-jira',
        credentialName: 'reader-bot',
        externalLogin: 'svc-reader',
        access: 'WRITE',
        status: 'OK',
      },
      {
        systemId: IDS.github,
        systemName: 'GitHub',
        accountId: IDS.readerGithubAccount,
        credentialName: 'reader-bot',
        externalLogin: 'svc-reader',
        access: 'WRITE',
        status: 'OK',
      },
    ],
    budget: aTriggerPayer(),
    ...overrides,
  };
}

export function anAuthorChoice(overrides: Partial<TriggerChoice> = {}): TriggerChoice {
  return {
    principalId: IDS.alice,
    name: 'Alice Novak',
    kind: 'MEMBER',
    systems: [
      {
        systemId: IDS.jira,
        systemName: 'Jira',
        accountId: 'acc-alice-jira',
        externalLogin: 'alice',
        access: 'WRITE',
        status: 'OK',
      },
      {
        systemId: IDS.github,
        systemName: 'GitHub',
        accountId: 'acc-alice-github',
        externalLogin: 'alice',
        access: 'WRITE',
        status: 'OK',
      },
    ],
    budget: aTriggerPayer({ principalId: IDS.alice, name: 'Alice Novak', kind: 'MEMBER' }),
    ...overrides,
  };
}

export function someTriggerChoices(items: TriggerChoice[] = []): TriggerChoices {
  return { items: items.length ? items : [aTriggerChoice(), anAuthorChoice()] };
}

/**
 * Jira read for the triggers of svc-reader, with the credential reader-bot it borrows: a
 * credential's poll serves every service account that borrows it, so it names no holder.
 */
export function aTriggerPoll(overrides: Partial<TriggerPoll> = {}): TriggerPoll {
  return {
    id: 'poll1',
    accountId: 'acc-svc-reader-jira',
    holderPrincipalId: null,
    holderName: null,
    credentialName: 'reader-bot',
    status: 'ACTIVE',
    triggerIds: [TRIGGER_IDS.trigger],
    lastPolledAt: AT.started,
    nextPollAt: AT.finished,
    failures: 0,
    lastError: null,
    backoffUntil: null,
    failingSince: null,
    rateLimit: null,
    rateRemaining: null,
    rateResetsAt: null,
    ...overrides,
  };
}

export function anInboxTrigger(overrides: Partial<InboxTrigger> = {}): InboxTrigger {
  return {
    triggerId: TRIGGER_IDS.trigger,
    name: 'Prepare assigned issues',
    reason: 'PAUSED',
    detail: 'ACCOUNT_BROKEN',
    count: null,
    ...overrides,
  };
}

/** A channel trigger (package 5b): Business analyst answers mentions in #support on Slack. */
export function aChannelTrigger(overrides: Partial<Trigger> = {}): Trigger {
  return aTrigger({
    id: 't-support',
    name: 'Support answers',
    sentence:
      'When someone mentions @ba in #support on Slack, Business analyst answers in the thread.',
    source: 'CHANNEL',
    systemId: 'sys-slack',
    systemName: 'Slack',
    events: [{ event: 'MENTIONED', value: null }],
    subject: 'ANY',
    scope: null,
    channels: [
      { channelId: 'C1', name: 'support', default: true, state: 'ACTIVE' },
      { channelId: 'C2', name: 'sales', default: false, state: 'UNAVAILABLE' },
    ],
    results: { comment: false, review: false, onSuccess: [], onFailure: [] },
    ...overrides,
  });
}

/** A webhook trigger (package 5b): the CRM's deliveries of new issues with a high priority. */
export function aWebhookTrigger(overrides: Partial<Trigger> = {}): Trigger {
  return aTrigger({
    id: 't-crm',
    name: 'Triage CRM tickets',
    sentence: 'When CRM hooks delivers issue.created, Business analyst triages it.',
    source: 'WEBHOOK',
    systemId: 'sys-hooks',
    systemName: 'CRM hooks',
    events: [
      { event: 'DELIVERED', value: 'issue.created' },
      { event: 'DELIVERED', value: 'issue.updated' },
    ],
    subject: 'ANY',
    scope: null,
    webhookConditions: [{ pointer: '/issue/priority', op: 'IN', values: ['High', 'Highest'] }],
    results: { comment: false, review: true, onSuccess: [], onFailure: [] },
    ...overrides,
  });
}
