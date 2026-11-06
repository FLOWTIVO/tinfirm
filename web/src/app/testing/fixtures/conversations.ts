import type {
  Attachment,
  Conversation,
  ConversationBinding,
  ConversationMessage,
  ConversationSummary,
  Decision,
  Inbox,
  InboxItem,
  Output,
  QueueReason,
  Run,
  Subject,
} from '../../api/generated/models';
import { AT, IDS } from './ids';
import { aQueueReason, aRun } from './runs';

/**
 * Tags `Conversations`, `Decisions` and `Inbox`: Alice's conversation with the Business analyst
 * about SHOP-241, acting with her own GitHub account and paid by her budget.
 */

/** A binding of a conversation: by default Alice's own GitHub account, bound. */
export function aBinding(overrides: Partial<ConversationBinding> = {}): ConversationBinding {
  return {
    id: 'b-github',
    systemId: IDS.github,
    systemName: 'GitHub',
    systemKind: 'GITHUB',
    state: 'BOUND',
    how: 'OWN',
    reason: null,
    access: 'WRITE',
    text: 'GitHub: your own account',
    choices: [],
    boundAt: AT.started,
    ...overrides,
  };
}

/** Carol's binding through the grant of Analysts: GitHub's credential svc-reader (ADR-0072). */
export function aGrantedBinding(overrides: Partial<ConversationBinding> = {}): ConversationBinding {
  return aBinding({
    how: 'GRANTED',
    access: 'READ',
    grantId: IDS.analystsGrant,
    groupId: IDS.analysts,
    groupName: 'Analysts',
    holderPrincipalId: null,
    holderName: null,
    credentialName: 'svc-reader',
    text: 'GitHub: via Analysts, read-only',
    ...overrides,
  });
}

/**
 * A trigger's binding: its service account svc-reader with the credential reader-bot it borrows on
 * Jira, lent read-only; the server's sentence does not say the access.
 */
export function aFixedBinding(overrides: Partial<ConversationBinding> = {}): ConversationBinding {
  return aBinding({
    id: 'b-jira-fixed',
    systemId: IDS.jira,
    systemName: 'Jira',
    systemKind: 'JIRA_CLOUD',
    how: 'FIXED',
    access: 'READ',
    holderPrincipalId: IDS.svcReader,
    holderName: 'svc-reader',
    holderKind: 'SERVICE',
    credentialName: 'reader-bot',
    text: "Jira: svc-reader's reader-bot, chosen by the trigger",
    ...overrides,
  });
}

/** A binding that waits for its person to connect their own account (a Connect card). */
export function aWaitingBinding(overrides: Partial<ConversationBinding> = {}): ConversationBinding {
  return aBinding({
    id: 'b-jira',
    systemId: IDS.jira,
    systemName: 'Jira',
    systemKind: 'JIRA_CLOUD',
    state: 'WAITING',
    how: null,
    reason: 'NONE',
    access: null,
    text: 'Jira: connect your account to continue',
    boundAt: null,
    waitingSince: AT.finished,
    ...overrides,
  });
}

export function aMessage(overrides: Partial<ConversationMessage> = {}): ConversationMessage {
  return {
    id: 'm1',
    kind: 'PERSON',
    body: 'Prepare SHOP-241, please.',
    authorPrincipalId: IDS.alice,
    authorName: 'Alice Novak',
    createdAt: AT.start,
    answersOpenQuestions: false,
    waiting: false,
    ...overrides,
  };
}

export function aConversationSummary(
  overrides: Partial<ConversationSummary> = {},
): ConversationSummary {
  return {
    id: IDS.conversation,
    key: 'SHOP-241-1',
    title: 'Checkout fails for guest users',
    agentId: IDS.agent,
    agentName: 'Business analyst',
    personPrincipalId: IDS.alice,
    personName: 'Alice Novak',
    state: 'OPEN',
    subject: {
      key: 'SHOP-241',
      systemId: IDS.jira,
      systemName: 'Jira',
      title: 'Checkout fails for guest users',
      url: 'https://acme.atlassian.net/browse/SHOP-241',
    },
    turnCount: 1,
    turnStatus: 'SUCCEEDED',
    waitingOn: 'NONE',
    waitingReason: null,
    waitingSince: null,
    lastTurnAt: AT.finished,
    createdAt: AT.start,
    updatedAt: AT.finished,
    ...overrides,
  };
}

export function aConversation(overrides: Partial<Conversation> = {}): Conversation {
  const summary = aConversationSummary();
  return {
    id: summary.id,
    key: summary.key,
    title: summary.title,
    agentId: summary.agentId,
    agentName: summary.agentName,
    personPrincipalId: summary.personPrincipalId,
    personName: summary.personName,
    state: summary.state,
    subject: summary.subject,
    turnCount: summary.turnCount,
    turnStatus: summary.turnStatus,
    waitingOn: summary.waitingOn,
    lastTurnAt: summary.lastTurnAt,
    createdAt: summary.createdAt,
    updatedAt: summary.updatedAt,
    origin: 'APP',
    place: 'TINFIRM_THREAD',
    runtime: 'CLAUDE_CODE',
    branch: 'agent/SHOP-241-1',
    seenByOwner: true,
    activeRunId: null,
    payer: { principalId: IDS.alice, name: 'Alice Novak', modelAccess: 'BUDGET' },
    bindings: [aBinding()],
    messages: [
      aMessage(),
      aMessage({
        id: 'm2',
        kind: 'AGENT',
        body: 'The brief is ready: the guest flow skips the address check.',
        authorPrincipalId: null,
        authorName: 'Business analyst',
        runId: IDS.run,
        createdAt: AT.finished,
      }),
    ],
    turns: [aRun()],
    decisions: [],
    outputs: [],
    attachments: [],
    workspace: [
      {
        repositoryId: IDS.shopRepo,
        systemId: IDS.github,
        name: 'acme/shop',
        url: 'https://github.com/acme/shop',
        readOnly: true,
        readOnlyCause: 'AGENT',
        source: 'AGENT',
      },
    ],
    hosts: ['registry.npmjs.org'],
    grantedHosts: [],
    ...overrides,
  };
}

/** An open question the agent asked Alice. */
export function aDecision(overrides: Partial<Decision> = {}): Decision {
  return {
    id: 'd1',
    kind: 'QUESTION',
    status: 'OPEN',
    conversationId: IDS.conversation,
    conversationKey: 'SHOP-241-1',
    conversationTitle: 'Checkout fails for guest users',
    agentId: IDS.agent,
    agentName: 'Business analyst',
    approverPrincipalId: IDS.alice,
    approverName: 'Alice Novak',
    mayDecide: true,
    question: 'Should the brief cover the mobile app too?',
    options: ['Yes', 'No, the web shop only'],
    runId: IDS.run,
    createdAt: AT.finished,
    ...overrides,
  };
}

/** An approval of a tool call that writes. */
export function anApproval(overrides: Partial<Decision> = {}): Decision {
  return aDecision({
    id: 'd2',
    kind: 'APPROVAL',
    question: null,
    options: [],
    subject: {
      type: 'TOOL_CALL',
      systemId: IDS.jira,
      systemName: 'Jira',
      tool: 'add_comment',
      name: 'jira_add_comment',
      what: 'comment on SHOP-241',
      arguments: { issue: 'SHOP-241', body: 'The brief is attached.' },
    },
    ...overrides,
  });
}

export function anAttachment(overrides: Partial<Attachment> = {}): Attachment {
  return {
    id: 'att1',
    conversationId: IDS.conversation,
    fileName: 'export.csv',
    contentType: 'text/csv',
    sizeBytes: 18_432,
    sha256: 'abc',
    note: null,
    uploadedBy: 'Alice Novak',
    createdAt: AT.start,
    ...overrides,
  };
}

export function anOutput(overrides: Partial<Output> = {}): Output {
  return {
    id: 'out1',
    conversationId: IDS.conversation,
    kind: 'CHANGE_REQUEST',
    title: 'Validate the guest address',
    url: 'https://github.com/acme/docs/pull/12',
    repositoryId: IDS.docsRepo,
    repositoryName: 'acme/docs',
    externalNumber: '12',
    changeRequestState: 'OPEN',
    runId: IDS.run,
    createdAt: AT.finished,
    ...overrides,
  };
}

export function aSubject(overrides: Partial<Subject> = {}): Subject {
  return {
    key: 'SHOP-241',
    systemId: IDS.jira,
    systemName: 'Jira',
    title: 'Checkout fails for guest users',
    url: 'https://acme.atlassian.net/browse/SHOP-241',
    conversations: 1,
    open: 1,
    lastActivityAt: AT.finished,
    ...overrides,
  };
}

export function anInboxItem(overrides: Partial<InboxItem> = {}): InboxItem {
  return {
    kind: 'DECISION',
    since: AT.finished,
    conversation: {
      id: IDS.conversation,
      key: 'SHOP-241-1',
      title: 'Checkout fails for guest users',
      agentId: IDS.agent,
      agentName: 'Business analyst',
    },
    decision: aDecision(),
    ...overrides,
  };
}

/** Alice's inbox: one question, one Connect card. */
export function anInbox(overrides: Partial<Inbox> = {}): Inbox {
  return {
    counts: { decisions: 1, bindings: 1, failed: 0, waiting: 0, accounts: 0 },
    items: [
      anInboxItem(),
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
    ],
    ...overrides,
  };
}

// --- The conversation page's states (plan §4.1, the table of states) ----------------------------

/** The rows of plan §4.1's states, for table-driven specs and the screenshots. */
export type ConversationStateName =
  | 'running'
  | 'queued'
  | 'budget-spent'
  | 'question'
  | 'account'
  | 'failed'
  | 'usage-limit'
  | 'time-limit'
  | 'cancelled'
  | 'paused'
  | 'ended'
  | 'idle';

/** Alice's second message, which started (or waits for) her second turn. */
function secondMessage(overrides: Partial<ConversationMessage> = {}): ConversationMessage {
  return aMessage({
    id: 'm3',
    body: 'Add the mobile app too.',
    runId: 'run2',
    createdAt: '2026-10-12T09:00:00Z',
    ...overrides,
  });
}

/**
 * Alice's conversation in one of the states of plan §4.1: its first turn done, and the second one
 * running, queued, waiting on her, failed, stopped at its time limit, cancelled, held by a paused
 * agent, or ended.
 */
export function aConversationIn(
  state: ConversationStateName,
  overrides: Partial<Conversation> = {},
): Conversation {
  const base = aConversation();
  const second = (run: Partial<Run>): Run =>
    aRun({
      id: 'run2',
      turnNumber: 2,
      turnReason: 'MESSAGE',
      createdAt: '2026-10-12T09:00:01Z',
      startedAt: '2026-10-12T09:00:05Z',
      finishedAt: null,
      status: 'RUNNING',
      summary: null,
      result: null,
      usage: null,
      ...run,
    });
  const queued = (reason: Partial<QueueReason>): Run =>
    second({
      status: 'QUEUED',
      startedAt: null,
      runnerHostId: null,
      runnerHostName: null,
      runnerHostKind: null,
      queueReason: aQueueReason(reason),
    });
  const withTurn = (run: Run, more: Partial<Conversation>): Conversation => ({
    ...base,
    messages: [...base.messages, secondMessage()],
    turns: [...base.turns, run],
    turnCount: 2,
    lastTurnAt: run.createdAt,
    ...more,
    ...overrides,
  });
  switch (state) {
    case 'running':
      return withTurn(second({}), {
        activeRunId: 'run2',
        turnStatus: 'RUNNING',
        waitingOn: 'AGENT',
        waitingReason: 'RUNNING',
        waitingSince: '2026-10-12T09:00:05Z',
      });
    case 'queued':
      return withTurn(queued({}), {
        activeRunId: 'run2',
        turnStatus: 'QUEUED',
        waitingOn: 'RUNNER',
        waitingReason: 'RUNNER_HOST_FULL',
      });
    case 'budget-spent':
      return withTurn(
        queued({
          code: 'BUDGET_SPENT',
          text: 'The budget that pays for this conversation is spent until 2026-11-01T00:00:00Z.',
          retryAt: AT.resets,
          runnerHostId: null,
          runnerHostName: null,
          used: null,
          limit: null,
        }),
        {
          activeRunId: 'run2',
          turnStatus: 'QUEUED',
          waitingOn: 'RUNNER',
          waitingReason: 'BUDGET_SPENT',
        },
      );
    case 'paused':
      return withTurn(
        queued({
          code: 'AGENT_PAUSED',
          text: 'The agent is paused.',
          runnerHostId: null,
          runnerHostName: null,
          used: null,
          limit: null,
        }),
        {
          activeRunId: 'run2',
          turnStatus: 'QUEUED',
          waitingOn: 'RUNNER',
          waitingReason: 'AGENT_PAUSED',
        },
      );
    case 'question':
      return withTurn(second({ status: 'SUCCEEDED', finishedAt: '2026-10-12T09:03:00Z' }), {
        turnStatus: 'WAITING',
        waitingOn: 'PERSON',
        waitingReason: 'QUESTION',
        waitingSince: '2026-10-12T09:03:00Z',
        decisions: [aDecision({ runId: 'run2', createdAt: '2026-10-12T09:02:00Z' })],
      });
    case 'account':
      return withTurn(second({ status: 'SUCCEEDED', finishedAt: '2026-10-12T09:03:00Z' }), {
        turnStatus: 'WAITING',
        waitingOn: 'PERSON',
        waitingReason: 'ACCOUNT',
        waitingSince: '2026-10-12T09:03:00Z',
        bindings: [aBinding(), aWaitingBinding()],
      });
    case 'failed':
      return withTurn(
        second({
          status: 'FAILED',
          finishedAt: '2026-10-12T09:01:00Z',
          failureClass: 'INFRA',
          failureMessage: 'The sandbox could not start: no space left on the device.',
        }),
        {
          turnStatus: 'FAILED',
          waitingOn: 'PERSON',
          waitingReason: 'FAILED',
          failureClass: 'INFRA',
          failureCause: 'The sandbox could not start: no space left on the device.',
        },
      );
    case 'usage-limit':
      return withTurn(
        second({
          status: 'FAILED',
          finishedAt: '2026-10-12T09:01:00Z',
          failureClass: 'USAGE_LIMIT',
          failureMessage: 'The model gateway refused the request: budget exceeded.',
        }),
        {
          turnStatus: 'FAILED',
          waitingOn: 'PERSON',
          waitingReason: 'FAILED',
          failureClass: 'USAGE_LIMIT',
          failureCause: 'The model gateway refused the request: budget exceeded.',
        },
      );
    case 'time-limit':
      return withTurn(
        second({
          status: 'FAILED',
          finishedAt: '2026-10-12T10:00:01Z',
          failureClass: 'TIME_LIMIT',
          failureMessage: 'the run reached its time limit (60 min)',
        }),
        {
          turnStatus: 'FAILED',
          waitingOn: 'PERSON',
          waitingReason: 'FAILED',
          failureClass: 'TIME_LIMIT',
          failureCause: 'the run reached its time limit (60 min)',
        },
      );
    case 'cancelled':
      return withTurn(second({ status: 'CANCELLED', finishedAt: '2026-10-12T09:01:00Z' }), {
        turnStatus: 'CANCELLED',
        waitingOn: 'NONE',
        waitingReason: null,
        messages: [
          ...base.messages,
          secondMessage(),
          aMessage({
            id: 'm4',
            body: 'And the tablet layout.',
            runId: null,
            unanswered: true,
            createdAt: '2026-10-12T09:00:30Z',
          }),
        ],
      });
    case 'ended':
      return {
        ...base,
        state: 'ENDED',
        endedReason: 'USER',
        endedAt: '2026-10-12T10:00:00Z',
        waitingOn: 'NONE',
        ...overrides,
      };
    default:
      return { ...base, ...overrides };
  }
}
