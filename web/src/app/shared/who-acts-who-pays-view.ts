import type {
  AgentReadiness,
  AgentReadinessSystem,
  Conversation,
  ConversationBinding,
  ModelAccessView,
  MyAccount,
  Run,
} from '../api/generated/models';
import {
  ACCESS_LABELS,
  formatDay,
  formatUsd,
  RUNNER_HOST_SCOPE_LABELS,
  RUNTIME_LABELS,
  SUBSCRIPTION_KIND_LABELS,
} from './labels';

/**
 * The words of the who-acts / who-pays panel (plan §2, rule 2), drawn from what the server
 * answers: `AgentReadiness` before a conversation starts, `Conversation` while it runs, and `Run`
 * for one turn. A trigger's preview (package 4) builds the same view. The client never decides
 * who acts or pays; it only says what the server said.
 */

/**
 * How a line reads. `ok`: settled. `later`: settled when the agent first needs it. `you`: the
 * reader can act (orange, DESIGN.md's scarce orange). `blocked`: waits on someone else (an admin,
 * a budget's reset, a runner host). `gone`: can no longer be used.
 */
export type WapTone = 'ok' | 'later' | 'you' | 'blocked' | 'gone';

export interface WapLine {
  /** Stable per line, for tests and `track`. */
  readonly key: string;
  readonly text: string;
  readonly tone: WapTone;
  /** A second sentence: why, or since when. */
  readonly detail?: string | null;
}

export interface WhoActsWhoPaysView {
  /** One line per system: whose account the agent uses there. */
  readonly acts: readonly WapLine[];
  /** Said under the systems, e.g. that the others connect when first needed. */
  readonly actsNote: string | null;
  readonly pays: WapLine;
  /** Where it runs; null when that is not known. */
  readonly runs: WapLine | null;
}

/** Who reads the panel, so that it says "your" for the reader's own and names anyone else. */
export interface WapReader {
  /** The reader's principal in the company (`getMyModelAccess().principalId`, plan §9 gap 2). */
  readonly principalId?: string | null;
}

/** The budget of a payer, when its model access is known: "$38.20 of $50 left, resets 1 Nov". */
export function budgetLeft(access: ModelAccessView | null | undefined): string | null {
  if (!access) return null;
  const left = Math.max(0, access.budgetUsd - (access.spentUsd ?? 0));
  const resets = formatDay(access.budgetResetsAt);
  return `${formatUsd(left)} of ${formatUsd(access.budgetUsd)} left${resets ? `, resets ${resets}` : ''}`;
}

/** "Your Claude subscription on alice-macbook", from the reader's model access when it names it. */
function subscriptionText(
  label: string | null | undefined,
  runnerHostId: string | null | undefined,
  access: ModelAccessView | null | undefined,
  owner: string,
): string {
  const known = access?.subscriptions.find(
    (s) => s.label === label && (!runnerHostId || s.runnerHostId === runnerHostId),
  );
  if (known) return `${owner} ${SUBSCRIPTION_KIND_LABELS[known.kind]} on ${known.runnerHostName}`;
  return label ? `${owner} subscription “${label}”` : `${owner} subscription`;
}

// --- Before a conversation: AgentReadiness ---------------------------------------------------

/** The grant a readiness line names, from the reader's accounts (`listMyAccounts`). */
function grantOf(
  system: AgentReadinessSystem,
  accounts: readonly MyAccount[] | undefined,
): { groupName: string; credentialName: string } | undefined {
  const mine = accounts?.find((a) => a.system.id === system.systemId);
  return mine?.grants.find((g) => g.grantId === system.grantId) ?? mine?.grants[0];
}

/** One system of a readiness, by `binding`: what a conversation would bind at its first use. */
export function readinessSystemLine(
  system: AgentReadinessSystem,
  accounts?: readonly MyAccount[],
): WapLine {
  const name = system.own ? `${system.name} (your own server)` : system.name;
  const line = (text: string, tone: WapTone, detail: string | null = null): WapLine => ({
    key: system.systemId,
    text: `${name}: ${text}`,
    tone,
    detail,
  });
  const grant = grantOf(system, accounts);
  const via = grant ? `via ${grant.groupName}` : 'via a grant to your group';
  const access = system.access ? `, ${ACCESS_LABELS[system.access]}` : '';
  switch (system.binding) {
    case 'OWN':
      return line('your own account', 'ok');
    case 'GRANTED':
      return line(`${via}${access}`, 'ok');
    case 'CHOOSE':
      return line('you choose among your groups’ grants when it is first needed', 'later');
    case 'CONNECT':
      return line('connect when first needed', 'later');
    case 'RECONNECT_YOURS':
      return line('your account needs signing in again', 'you');
    case 'RECONNECT_OR_GRANT':
      return line(`sign in again, or act ${via}`, 'you');
    case 'NOT_NEEDED':
      return line('no account needed', 'ok');
    case 'RECONNECT_COMPANY':
      return line(
        'waits until an admin signs the lent credential in again',
        'blocked',
        grant ? `The credential ${grant.credentialName}, lent to ${grant.groupName}` : null,
      );
    default:
      return line('not available: personal MCP servers are switched off', 'blocked');
  }
}

const PAYER_WAITING: Record<string, string> = {
  BUDGET_SPENT: 'Your budget is spent',
  KEY_ERROR: 'Your budget cannot be used right now',
  NO_MODEL_KEY: 'You have no key at the company’s model gateway yet',
  HOST_OFFLINE: 'Your subscription’s runner host is offline',
};

const PAYER_REFUSED: Record<string, string> = {
  GATEWAY_NOT_CONFIGURED: 'An admin must set up model access first',
  PRINCIPAL_INACTIVE: 'Your model access is inactive',
  SUBSCRIPTION_GONE: 'The subscription in your settings is gone',
  PERSONAL_HOST_CREDENTIAL_ONLY: 'Your personal host runs only work paid by your subscriptions',
  MODEL_REQUIRED: 'Choose a model in your settings first',
  MODEL_NOT_ALLOWED: 'Your model access does not allow the model in your settings',
};

/** Who pays for the reader's conversations with the agent, as package 2 answers it now. */
export function readinessPayerLine(
  readiness: AgentReadiness,
  access?: ModelAccessView | null,
): WapLine {
  const { payer, settings } = readiness;
  if (payer.state === 'REFUSED') {
    return {
      key: 'payer',
      text: PAYER_REFUSED[payer.reason ?? ''] ?? 'Nobody can pay for a conversation now',
      tone: 'blocked',
      detail: payer.detail ?? null,
    };
  }
  if (payer.state === 'WAITING') {
    const until = formatDay(payer.until);
    const waiting = PAYER_WAITING[payer.reason ?? ''] ?? 'Turns wait for model access';
    return {
      key: 'payer',
      text: until ? `${waiting} until ${until}` : waiting,
      tone: 'blocked',
      detail: until
        ? `You can start; the first turn waits until ${until}.`
        : (payer.detail ?? 'You can start; the first turn waits.'),
    };
  }
  if (payer.modelAccess === 'SUBSCRIPTION') {
    return {
      key: 'payer',
      text: subscriptionText(
        settings.subscription?.label,
        settings.subscription?.runnerHostId,
        access,
        'Your',
      ),
      tone: 'ok',
    };
  }
  const left = budgetLeft(access);
  return { key: 'payer', text: left ? `Your budget: ${left}` : 'Your budget', tone: 'ok' };
}

/** Where the reader's conversations with the agent would run. */
export function readinessRunsLine(readiness: AgentReadiness): WapLine {
  const { hosts, payer } = readiness;
  if (!hosts.runnable) {
    return {
      key: 'runs',
      text: 'No runner host can run it now',
      tone: 'blocked',
      detail: hosts.reason ?? null,
    };
  }
  return payer.modelAccess === 'SUBSCRIPTION'
    ? { key: 'runs', text: 'Your personal host only', tone: 'ok' }
    : { key: 'runs', text: 'A company host', tone: 'ok' };
}

export function fromReadiness(
  readiness: AgentReadiness,
  options: { access?: ModelAccessView | null; accounts?: readonly MyAccount[] } = {},
): WhoActsWhoPaysView {
  return {
    acts: readiness.systems.map((s) => readinessSystemLine(s, options.accounts)),
    actsNote: readiness.systems.length === 0 ? 'It uses no system.' : null,
    pays: readinessPayerLine(readiness, options.access),
    runs: readinessRunsLine(readiness),
  };
}

// --- A conversation: its bindings, payer and turns --------------------------------------------

/** Waiting bindings the conversation's person can act on: a Connect card or a choice. */
const PERSON_REASONS: ReadonlySet<ConversationBinding['reason']> = new Set([
  'NONE',
  'RECONNECT_YOURS',
  'CHOOSE',
  'PICK_GRANT',
]);

/**
 * A binding's sentence, read-only where it acts so. The server's sentence says a grant's access
 * ("GitHub: via Analysts, read-only"); a trigger's (`FIXED`) says it only by `access`, the access
 * its service account's credential is lent with now (ADR-0072), so it is added here.
 */
export function boundText(binding: ConversationBinding): string {
  const readOnly =
    binding.state === 'BOUND' && binding.how === 'FIXED' && binding.access === 'READ';
  return readOnly ? `${binding.text}, read-only` : binding.text;
}

/**
 * One binding by its ready sentence (`text`) and its state. The sentence speaks to the
 * conversation's person ("GitHub: your own account"): for anyone else reading it (the Owner), a
 * person's own account is named as theirs. A binding that acts read-only says so.
 */
export function bindingLine(
  binding: ConversationBinding,
  mine: boolean,
  personName?: string | null,
): WapLine {
  const text =
    !mine && binding.state === 'BOUND' && binding.how === 'OWN'
      ? `${binding.systemName ?? 'A system'}: ${personName ?? 'its person'}’s own account`
      : boundText(binding);
  const base = { key: binding.systemId, text };
  switch (binding.state) {
    case 'BOUND':
      return { ...base, tone: 'ok' };
    case 'GONE':
      return { ...base, tone: 'gone', detail: 'It can no longer be used in this conversation.' };
    default:
      return PERSON_REASONS.has(binding.reason)
        ? { ...base, tone: mine ? 'you' : 'blocked' }
        : { ...base, tone: 'blocked' };
  }
}

export function conversationPayerLine(
  conversation: Pick<Conversation, 'payer'>,
  reader: WapReader,
  access?: ModelAccessView | null,
): WapLine {
  const { payer } = conversation;
  const mine = !!reader.principalId && payer.principalId === reader.principalId;
  const owner = mine ? 'Your' : `${payer.name ?? 'Its payer'}’s`;
  if (payer.modelAccess === 'SUBSCRIPTION') {
    if (!payer.subscriptionRunnerHostId) {
      return {
        key: 'payer',
        text: `${owner} subscription “${payer.subscriptionLabel ?? ''}”`,
        tone: 'gone',
        detail: 'Its runner host was removed, so turns cannot be paid.',
      };
    }
    return {
      key: 'payer',
      text: subscriptionText(
        payer.subscriptionLabel,
        payer.subscriptionRunnerHostId,
        mine ? access : null,
        owner,
      ),
      tone: 'ok',
    };
  }
  const known = access && access.principalId === payer.principalId ? access : null;
  const left = budgetLeft(known);
  return {
    key: 'payer',
    text: left ? `${owner} budget: ${left}` : `${owner} budget`,
    // Spent: its turns wait for the reset, which nobody here can hurry (neutral, not orange).
    tone: known?.keyStatus === 'BUDGET_SPENT' ? 'blocked' : 'ok',
  };
}

export function conversationRunsLine(
  conversation: Pick<Conversation, 'runtime' | 'turns'>,
): WapLine {
  const runtime = RUNTIME_LABELS[conversation.runtime] ?? conversation.runtime;
  const last = conversation.turns.at(-1);
  if (!last?.runnerHostName) return { key: 'runs', text: runtime, tone: 'ok' };
  const kind = last.runnerHostKind ? `, ${RUNNER_HOST_SCOPE_LABELS[last.runnerHostKind]}` : '';
  return {
    key: 'runs',
    text: `${runtime} · last turn on ${last.runnerHostName}${kind}`,
    tone: 'ok',
  };
}

export function fromConversation(
  conversation: Conversation,
  reader: WapReader = {},
  access?: ModelAccessView | null,
): WhoActsWhoPaysView {
  const mine = !!reader.principalId && conversation.personPrincipalId === reader.principalId;
  return {
    acts: conversation.bindings.map((b) => bindingLine(b, mine, conversation.personName)),
    actsNote:
      conversation.state === 'ENDED'
        ? null
        : conversation.bindings.length === 0
          ? 'Systems connect when the agent first needs them.'
          : 'Other systems connect when the agent first needs them.',
    pays: conversationPayerLine(conversation, reader, access),
    runs: conversationRunsLine(conversation),
  };
}

// --- A conversation in one line: the head of its page ---------------------------------------------

/** One part of the line: "Acts as" and what follows it in ink. */
export interface WapLinePart {
  readonly key: 'acts' | 'pays' | 'runs';
  readonly label: string;
  readonly lines: readonly WapLine[];
}

/** "Acts as GitHub: your own account · Pays from your budget, $38.20 left · Runs on build-01". */
export interface WapLineView {
  readonly parts: readonly WapLinePart[];
  /** The part that most needs a look decides the line's icon: the reader's own act first. */
  readonly tone: WapTone;
}

const TONE_WEIGHT: Record<WapTone, number> = { ok: 0, later: 1, gone: 2, blocked: 3, you: 4 };

/** Who pays, in a few words after "Pays from": "your budget, $38.20 left". */
export function conversationPayerShort(
  conversation: Pick<Conversation, 'payer'>,
  reader: WapReader,
  access?: ModelAccessView | null,
): WapLine {
  const { payer } = conversation;
  const mine = !!reader.principalId && payer.principalId === reader.principalId;
  const owner = mine ? 'your' : `${payer.name ?? 'its payer'}’s`;
  if (payer.modelAccess === 'SUBSCRIPTION') {
    return payer.subscriptionRunnerHostId
      ? {
          key: 'payer',
          text: subscriptionText(
            payer.subscriptionLabel,
            payer.subscriptionRunnerHostId,
            mine ? access : null,
            owner,
          ),
          tone: 'ok',
        }
      : {
          key: 'payer',
          text: `${owner} subscription “${payer.subscriptionLabel ?? ''}”, whose host was removed`,
          tone: 'gone',
        };
  }
  const known = access && access.principalId === payer.principalId ? access : null;
  const left = known ? formatUsd(Math.max(0, known.budgetUsd - (known.spentUsd ?? 0))) : null;
  return {
    key: 'payer',
    text: left ? `${owner} budget, ${left} left` : `${owner} budget`,
    tone: known?.keyStatus === 'BUDGET_SPENT' ? 'blocked' : 'ok',
  };
}

/**
 * The panel's three parts in one line, for the head of the conversation page: each system by its
 * binding's sentence (as the panel says it), who pays in a few words, and the host of the last
 * turn (or the runtime before any). The panel itself stays one click away.
 */
export function conversationLineView(
  conversation: Conversation,
  reader: WapReader = {},
  access?: ModelAccessView | null,
): WapLineView {
  const full = fromConversation(conversation, reader, access);
  const host = conversation.turns.at(-1)?.runnerHostName;
  const acts: WapLinePart =
    full.acts.length > 0
      ? { key: 'acts', label: 'Acts as', lines: full.acts }
      : {
          key: 'acts',
          label: '',
          lines: [
            conversation.state === 'ENDED'
              ? { key: 'none', text: 'Acted on no system', tone: 'ok' }
              : { key: 'none', text: 'Systems connect when first needed', tone: 'later' },
          ],
        };
  const parts: WapLinePart[] = [
    acts,
    {
      key: 'pays',
      label: 'Pays from',
      lines: [conversationPayerShort(conversation, reader, access)],
    },
    host
      ? { key: 'runs', label: 'Runs on', lines: [{ key: 'runs', text: host, tone: 'ok' }] }
      : {
          key: 'runs',
          label: 'Runs',
          lines: [
            {
              key: 'runs',
              text: RUNTIME_LABELS[conversation.runtime] ?? conversation.runtime,
              tone: 'ok',
            },
          ],
        },
  ];
  const tone = parts
    .flatMap((p) => p.lines)
    .reduce<WapTone>(
      (worst, l) => (TONE_WEIGHT[l.tone] > TONE_WEIGHT[worst] ? l.tone : worst),
      'ok',
    );
  return { parts, tone };
}

// --- One turn: Run ------------------------------------------------------------------------------

export function fromRun(run: Run, reader: WapReader = {}): WhoActsWhoPaysView {
  const person =
    reader.principalId && run.personPrincipalId === reader.principalId
      ? 'you'
      : (run.personName ?? 'its person');
  const payerMine = !!reader.principalId && run.payerPrincipalId === reader.principalId;
  const owner = payerMine ? 'Your' : `${run.payerName ?? 'Its payer'}’s`;
  const runtime = RUNTIME_LABELS[run.runtime] ?? run.runtime;
  return {
    acts: [{ key: 'person', text: `For ${person}`, tone: 'ok' }],
    actsNote: 'With the accounts its conversation bound to each system.',
    pays: {
      key: 'payer',
      text:
        run.modelAccess === 'SUBSCRIPTION'
          ? `${owner} subscription${run.subscriptionLabel ? ` “${run.subscriptionLabel}”` : ''}`
          : `${owner} budget`,
      tone: 'ok',
    },
    runs: run.runnerHostName
      ? {
          key: 'runs',
          text: `${runtime} on ${run.runnerHostName}${
            run.runnerHostKind ? `, ${RUNNER_HOST_SCOPE_LABELS[run.runnerHostKind]}` : ''
          }`,
          tone: 'ok',
        }
      : { key: 'runs', text: `${runtime}, not on a runner host yet`, tone: 'later' },
  };
}
