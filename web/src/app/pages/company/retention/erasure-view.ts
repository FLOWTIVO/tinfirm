import type { ErasureCounts, ErasureRemains, LegalHold } from '../../../api/generated/models';
import {
  erasureWords,
  formatCount,
  joinWords,
  otherKindLabel,
  remainsLabel,
} from '../../../shared/governance-labels';

/**
 * The words of an erasure before it is made (the package 6 contract, §5.9; open points 17, 22;
 * CONVENTIONS §15.11): what goes, counted by the erasure's dry run, what stays and what is not
 * Tinfirm's to erase, and the holds that stop it, for a member who left, a member still here, or
 * someone outside Tinfirm. Until the dry run answers, or when it fails, the review says it in
 * words. Pure, so that every case is tested as a table.
 */

/** Whose content: a member (who left, or is still here) or an identity at a system. */
export type EraseTarget =
  | {
      readonly kind: 'person';
      readonly principalId: string;
      readonly name: string;
      readonly left: boolean;
    }
  | {
      readonly kind: 'identity';
      readonly systemId: string;
      readonly systemName: string;
      readonly externalId: string;
    };

export interface ReviewItem {
  readonly what: string;
  readonly detail: string;
}

/** One kind of what goes, and how the confirmation names it in a sentence. */
export interface GoesItem extends ReviewItem {
  readonly phrase: string;
}

/** One kind of what goes, counted by the dry run. */
export interface CountItem extends ReviewItem {
  readonly count: number;
}

export interface Review {
  readonly goes: readonly GoesItem[];
  readonly stays: readonly ReviewItem[];
  /** Not Tinfirm's to erase here: who can remove it, and where. */
  readonly elsewhere: readonly ReviewItem[];
}

const TOMBSTONE: ReviewItem = {
  what: 'What each conversation was',
  detail: 'Its key, agent, place and dates, without its words.',
};
const SPEND: ReviewItem = { what: 'Its spend', detail: 'The tokens and cost of every turn.' };
const AUDIT: ReviewItem = { what: 'The audit log', detail: 'Ids only, kept for good.' };
const STAYS: readonly ReviewItem[] = [TOMBSTONE, SPEND, AUDIT];

const RUNNER: ReviewItem = {
  what: 'Runner hosts’ sessions',
  detail: 'On the hosts the turns ran on, outside Tinfirm’s reach.',
};

const OPEN_KEPT: ReviewItem = {
  what: 'Their open personal conversations',
  detail: 'A member still here keeps open ones. End them on their page, then erase again.',
};

const ACCOUNT: ReviewItem = {
  what: 'Their Tinfirm account',
  detail: 'Name and email are the instance’s: an instance admin deletes the user.',
};

function agentWords(target: EraseTarget): ReviewItem {
  return {
    what: 'The agent’s own words',
    detail:
      target.kind === 'identity'
        ? 'Which may quote theirs, until conversations’ content goes under retention.'
        : 'In others’ conversations, which may quote theirs, until conversations’ content goes under retention.',
  };
}

function sourceSystem(target: EraseTarget): ReviewItem {
  return {
    what: 'The source system',
    detail:
      target.kind === 'identity'
        ? `Their messages stay in ${target.systemName}: remove them there.`
        : 'What they wrote on an issue or in a chat stays there: remove it there.',
  };
}

/** What an erasure takes, keeps and leaves to others, in words: before the dry run answers. */
export function reviewOf(target: EraseTarget): Review {
  if (target.kind === 'identity') {
    return {
      goes: [
        {
          what: 'Their words in every conversation',
          detail: 'Open ones included: their messages are replaced by the removal marker at once.',
          phrase: 'their words in every conversation',
        },
        {
          what: 'The transcripts that quoted them',
          detail: 'The turns whose prompts quoted their words.',
          phrase: 'the transcripts that quoted them',
        },
        {
          what: 'Their name',
          detail: 'On the questions they answered and the conversations they started.',
          phrase: 'their name where it was kept',
        },
      ],
      stays: STAYS,
      elsewhere: [agentWords(target), RUNNER, sourceSystem(target)],
    };
  }
  if (target.left) {
    return {
      goes: [
        {
          what: 'Their personal conversations',
          detail: 'All their content, the transcripts of their turns included.',
          phrase: 'their personal conversations',
        },
        {
          what: 'What they said in triggers’ conversations',
          detail: 'Their messages there, open conversations included.',
          phrase: 'what they said in triggers’ conversations',
        },
        {
          what: 'The files they added',
          detail: 'In their own conversations and in others’.',
          phrase: 'the files they added',
        },
        {
          what: 'Their answers',
          detail: 'The texts of the questions and approvals they decided.',
          phrase: 'their answers',
        },
      ],
      stays: STAYS,
      elsewhere: [agentWords(target), RUNNER, sourceSystem(target), ACCOUNT],
    };
  }
  return {
    goes: [
      {
        what: 'Their ended personal conversations',
        detail: 'All their content, the transcripts of their turns included.',
        phrase: 'their ended personal conversations',
      },
      {
        what: 'The files in them',
        detail: 'Files they added and documents the agents made there.',
        phrase: 'the files in them',
      },
    ],
    stays: [OPEN_KEPT, ...STAYS],
    elsewhere: [RUNNER],
  };
}

/** The words of each kind the dry run counts, by the erasure's target. */
function countWords(target: EraseTarget): Record<keyof Omit<ErasureCounts, 'other'>, ReviewItem> {
  const left = target.kind === 'person' && target.left;
  return {
    conversations: {
      what: 'Conversations',
      detail: left
        ? 'Their personal conversations, erased whole.'
        : 'Their ended personal conversations, erased whole.',
    },
    messages: {
      what: 'Messages',
      detail: left
        ? 'Theirs and the agent’s in their conversations, and what they said in others’.'
        : 'Theirs and the agent’s, in those conversations.',
    },
    decisions: { what: 'Questions and approvals', detail: 'What was asked, answered and noted.' },
    outputs: { what: 'Outputs’ titles', detail: 'Each output’s kind and link stay.' },
    files: {
      what: 'Files',
      detail: left
        ? 'In their conversations, and those they added to others’. Names and sizes stay.'
        : 'Files they added and documents the agents made there. Names and sizes stay.',
    },
    runs: { what: 'Turns’ summaries', detail: 'What each turn reported at its end.' },
    runEvents:
      target.kind === 'identity'
        ? {
            what: 'Transcripts that quoted them',
            detail: 'The turns whose prompts quoted their words.',
          }
        : { what: 'Transcripts', detail: 'What the agent did in each turn.' },
  };
}

/** The words of the kinds the chats' and the triggers' modules count, for an identity. */
const OTHER_WORDS: Record<string, ReviewItem> = {
  messages: {
    what: 'Their messages',
    detail: 'Replaced by the removal marker, open conversations included.',
  },
  decisions: {
    what: 'Their answers',
    detail: 'The questions they answered lose their texts and their name.',
  },
  messageAuthors: {
    what: 'Their name on members’ messages',
    detail: 'Where a member linked to them wrote in the chat.',
  },
  titles: { what: 'Titles made from their words', detail: 'Of the conversations they started.' },
  chatUsers: { what: 'Their chat profile', detail: 'Their name and direct message at the system.' },
  chatEvents: { what: 'Chat events they sent', detail: 'Waiting or handled, with their words.' },
  chatLinks: { what: 'Their sign-in links', detail: 'Pending or used.' },
};

const ORDER: readonly (keyof Omit<ErasureCounts, 'other'>)[] = [
  'conversations',
  'messages',
  'decisions',
  'outputs',
  'files',
  'runs',
];

/** What goes, counted by the dry run, each kind that has any; the transcripts last. */
export function goesCounts(target: EraseTarget, counts: ErasureCounts): CountItem[] {
  const words = countWords(target);
  const items: CountItem[] = ORDER.filter((kind) => counts[kind] > 0).map((kind) => ({
    ...words[kind],
    count: counts[kind],
  }));
  for (const [kind, count] of Object.entries(counts.other ?? {})) {
    if (count <= 0) continue;
    const label = otherKindLabel(kind);
    items.push({
      ...(OTHER_WORDS[kind] ?? {
        what: label.charAt(0).toUpperCase() + label.slice(1),
        detail: '',
      }),
      count,
    });
  }
  if (counts.runEvents > 0) items.push({ ...words.runEvents, count: counts.runEvents });
  return items;
}

/** The kinds of what remains that stay in Tinfirm; the others are another's to remove. */
const STAYS_KINDS = ['OPEN_CONVERSATIONS', 'TOMBSTONES', 'SPEND', 'AUDIT'];

/** What remains, as the dry run says it, in the review's words: what stays, and what is elsewhere. */
export function remainsReview(
  target: EraseTarget,
  remains: readonly ErasureRemains[],
): Pick<Review, 'stays' | 'elsewhere'> {
  const known: Record<string, ReviewItem> = {
    AUDIT,
    TOMBSTONES: {
      what: TOMBSTONE.what,
      detail: 'Its key, agent, place and dates, and the spend of every turn, without its words.',
    },
    SPEND,
    OPEN_CONVERSATIONS: OPEN_KEPT,
    AGENT_TEXTS: agentWords(target),
    RUNNER_SESSIONS: RUNNER,
    SOURCE_SYSTEM: sourceSystem(target),
    USER_ACCOUNT: ACCOUNT,
  };
  const item = (r: ErasureRemains): ReviewItem => {
    const words = known[r.what] ?? { what: remainsLabel(r), detail: `${r.where}. ${r.removedBy}.` };
    const count = r.count === null || r.count === undefined ? '' : ` · ${formatCount(r.count)}`;
    return { what: `${words.what}${count}`, detail: words.detail };
  };
  const rank = (r: ErasureRemains) => STAYS_KINDS.indexOf(r.what);
  return {
    stays: remains
      .filter((r) => rank(r) >= 0)
      .sort((a, b) => rank(a) - rank(b))
      .map(item),
    elsewhere: remains.filter((r) => rank(r) < 0).map(item),
  };
}

/** The target's name on the page: the member's, or "jdoe on Slack". */
export function targetLabel(target: EraseTarget): string {
  return target.kind === 'person' ? target.name : `${target.externalId} on ${target.systemName}`;
}

/** Under the name: who they are and how much of theirs an erasure reaches. */
export function targetNote(target: EraseTarget): string {
  if (target.kind === 'identity') {
    return 'Someone outside Tinfirm · their words in every conversation';
  }
  return target.left
    ? 'Left the company · everything of theirs'
    : 'A member still here · their ended personal conversations';
}

/** What the person types to confirm: the member's name, or the identity's id at the system. */
export function confirmPhrase(target: EraseTarget): string {
  return target.kind === 'person' ? target.name : target.externalId;
}

/** Typed as asked, whatever the case and the spaces around it. */
export function confirmMatches(target: EraseTarget, typed: string): boolean {
  return typed.trim().toLowerCase() === confirmPhrase(target).trim().toLowerCase();
}

/**
 * The sentence over the confirmation: whose content goes, and what of it, counted when the dry
 * run answered, else in words.
 */
export function confirmLead(target: EraseTarget, counts?: ErasureCounts | null): string {
  const counted = counts ? erasureWords(counts) : undefined;
  const goes =
    counted === null
      ? 'nothing of it is left'
      : (counted ?? joinWords(reviewOf(target).goes.map((g) => g.phrase)));
  return target.kind === 'person'
    ? `${target.name}’s content goes now: ${goes}.`
    : `The words of ${targetLabel(target)} go now: ${goes}.`;
}

/**
 * The open hold known to cover the target before the dry run answers: a hold on the person. One
 * on a conversation the erasure reaches is the dry run's to name.
 */
export function holdOn(holds: readonly LegalHold[], target: EraseTarget): LegalHold | null {
  if (target.kind !== 'person') return null;
  return holds.find((h) => !h.liftedAt && h.principalId === target.principalId) ?? null;
}

/** The heading of the holds that stop the erasure. */
export function heldTitle(target: EraseTarget, holds: readonly LegalHold[]): string {
  const onPerson =
    target.kind === 'person' && holds.length === 1 && holds[0]!.principalId === target.principalId;
  if (onPerson) return `A legal hold covers ${targetLabel(target)}’s content`;
  return holds.length === 1
    ? 'A legal hold covers some of it'
    : `${holds.length} legal holds cover some of it`;
}

/**
 * Who can let the erasure go on: the one who set a hold decides whether it is lifted. Names the
 * setters the page knows (members by user id), the caller themselves as "you".
 */
export function askPhrase(
  holds: readonly LegalHold[],
  names: ReadonlyMap<string, string>,
  me: string | null | undefined,
): string {
  const one = holds.length === 1;
  const setters = [...new Set(holds.map((h) => h.setByUserId).filter((id): id is string => !!id))];
  const others = setters.filter((id) => id !== me);
  const known = others.map((id) => names.get(id)).filter((n): n is string => !!n);
  if (setters.length > 0 && others.length === 0) {
    return `You set ${one ? 'it' : 'them'}: lift ${one ? 'it' : 'them'} under Legal holds, then erase.`;
  }
  if (known.length > 0 && known.length === others.length) {
    return `Ask ${joinWords(known)}, who set ${one ? 'it' : 'them'}, whether ${one ? 'it' : 'they'} can be lifted.`;
  }
  return `Ask whoever asked for ${one ? 'it' : 'them'} whether ${one ? 'it' : 'they'} can be lifted.`;
}
