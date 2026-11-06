import type { Inbox, InboxItem, InboxItemKind } from '../../api/generated/models';
import { anApproval, anInboxItem } from './conversations';
import { AT, IDS } from './ids';

/** Tag `Inbox`: one item of every kind the API has, as each reader would get it. */

const NO_CARD = { decision: null, conversation: null } as const;

/**
 * One item of each kind (plan §5, §8). A kind the API adds and this table lacks breaks the build.
 * The notices carry their `noticeKey`, and `dismissible` as the server says it: a notice that only
 * informs, not one the reader acts on (a departure, a silent chat subscription, a widening).
 */
export const INBOX_ITEMS: Record<InboxItemKind, () => InboxItem> = {
  DECISION: () => anInboxItem({ decision: anApproval() }),
  BINDING: () =>
    anInboxItem({
      kind: 'BINDING',
      decision: null,
      binding: {
        id: 'b-jira',
        reason: 'NONE',
        systemId: IDS.jira,
        systemName: 'Jira',
        choices: [],
      },
    }),
  FAILED: () =>
    anInboxItem({
      kind: 'FAILED',
      decision: null,
      turn: {
        status: 'FAILED',
        runId: IDS.run,
        failureClass: 'USAGE_LIMIT',
        failureCause: 'The budget of Alice Novak is spent until 1 Nov.',
      },
    }),
  WAITING: () =>
    anInboxItem({
      kind: 'WAITING',
      decision: null,
      turn: { status: 'QUEUED', runId: IDS.run, waitingReason: 'BUDGET_SPENT', retryAt: AT.resets },
    }),
  ACCOUNT: () =>
    anInboxItem({
      kind: 'ACCOUNT',
      ...NO_CARD,
      account: {
        systemId: IDS.github,
        systemName: 'GitHub',
        credential: {
          id: IDS.readerGithubAccount,
          name: 'svc-reader',
          kind: 'OAUTH',
          status: 'RECONNECT',
          enabled: true,
          login: 'svc-reader',
        },
        bot: false,
        holderPrincipalId: null,
        holderName: null,
        reason: 'RECONNECT_COMPANY',
        conversations: 3,
      },
    }),
  AUDIT_CHECK: () =>
    anInboxItem({
      kind: 'AUDIT_CHECK',
      ...NO_CARD,
      auditCheck: {
        id: 'chk1',
        checkedAt: AT.start,
        ok: false,
        failure: 'BROKEN',
        firstInvalidSeq: 412,
        fromSeq: 0,
        headSeq: 980,
        headHash: 'ab12',
        eventsChecked: 980,
      },
    }),
  RETENTION_OPEN: () => anInboxItem({ kind: 'RETENTION_OPEN', ...NO_CARD, openConversations: 4 }),
  BUDGET: () =>
    anInboxItem({
      kind: 'BUDGET',
      ...NO_CARD,
      noticeKey: `BUDGET:${IDS.alice}:80:1792051200000`,
      dismissible: true,
      budget: {
        principalId: IDS.alice,
        principalName: 'Alice Novak',
        principalKind: 'MEMBER',
        own: true,
        threshold: 80,
        spentUsd: 40,
        budgetUsd: 50,
        resetsAt: AT.resets,
      },
    }),
  COMPANY_SPEND: () =>
    anInboxItem({
      kind: 'COMPANY_SPEND',
      ...NO_CARD,
      noticeKey: 'COMPANY_SPEND:2026-10',
      dismissible: true,
      companySpend: { month: '2026-10', totalUsd: 1250, alertUsd: 1000 },
    }),
  SPEND_SYNC: () =>
    anInboxItem({
      kind: 'SPEND_SYNC',
      ...NO_CARD,
      noticeKey: 'SPEND_SYNC:1792022400000',
      dismissible: true,
      spendSync: { relayRunsToday: 14, failingSince: AT.start, lastError: 'HTTP 502' },
    }),
  TRIGGER: () =>
    anInboxItem({
      kind: 'TRIGGER',
      ...NO_CARD,
      noticeKey: 'TRIGGER:t-triage:WIDENING:6',
      dismissible: false,
      trigger: { triggerId: 't-triage', name: 'Triage new bugs', reason: 'WIDENING' },
    }),
  DEPARTURE: () =>
    anInboxItem({
      kind: 'DEPARTURE',
      ...NO_CARD,
      noticeKey: `DEPARTURE:${IDS.dave}:1791936000000`,
      dismissible: false,
      departure: { principalId: IDS.dave, userId: IDS.daveUser, name: 'Dave Lin' },
    }),
  CHAT_LINKED: () =>
    anInboxItem({
      kind: 'CHAT_LINKED',
      ...NO_CARD,
      noticeKey: 'CHAT_LINKED:n-slack-link',
      dismissible: true,
      chat: {
        systemId: 'sys-slack',
        systemName: 'Slack',
        text: 'The Slack user @anovak was linked to your account.',
        unlinkSystemId: 'sys-slack',
      },
    }),
  SEAT_FALLBACK: () =>
    anInboxItem({
      kind: 'SEAT_FALLBACK',
      ...NO_CARD,
      noticeKey: `SEAT_FALLBACK:${IDS.carolUser}`,
      dismissible: true,
      seatFallback: {
        userId: IDS.carolUser,
        name: 'Carol Dvořák',
        role: 'MEMBER',
        provider: 'entra',
      },
    }),
  LICENCE: () =>
    anInboxItem({
      kind: 'LICENCE',
      ...NO_CARD,
      noticeKey: 'LICENCE:EXPIRING:2026-11-03',
      dismissible: true,
      licence: {
        state: 'EXPIRING',
        edition: 'PRO',
        endsAt: '2026-11-03',
        graceEndsAt: '2026-12-03',
      },
    }),
  SCIM_LAST_OWNER: () =>
    anInboxItem({
      kind: 'SCIM_LAST_OWNER',
      ...NO_CARD,
      noticeKey: 'SCIM_LAST_OWNER:1793433600000',
      dismissible: true,
    }),
  CHAT_SUBSCRIPTION_SILENT: () =>
    anInboxItem({
      kind: 'CHAT_SUBSCRIPTION_SILENT',
      ...NO_CARD,
      noticeKey: 'CHAT_SUBSCRIPTION_SILENT:n-slack-silent',
      dismissible: false,
      chat: {
        systemId: 'sys-slack',
        systemName: 'Slack',
        text: 'Slack stopped sending events after failed deliveries. Re-enable event subscriptions in the app’s settings.',
      },
    }),
};

/** An admin's inbox with one item of every kind. */
export function aFullInbox(overrides: Partial<Inbox> = {}): Inbox {
  return {
    counts: { decisions: 1, bindings: 1, failed: 1, waiting: 1, accounts: 1, notices: 7 },
    items: Object.values(INBOX_ITEMS).map((make) => make()),
    ...overrides,
  };
}
