import type {
  RetentionPeriods,
  RetentionPreview,
  RetentionSettings,
  RetentionSweepCounts,
} from '../../../api/generated/models';
import { countOf, joinWords } from '../../../shared/governance-labels';

/**
 * The words and checks of the retention page (the package 6 contract, §3.7, §5.9; CONVENTIONS
 * §15.11). Pure, so that every period and every answer is tested as a table.
 */

/** The periods as typed: a number of days, or empty to keep for good where allowed. */
export interface PeriodsForm {
  runEventsDays: string;
  chatContentDays: string;
  contentDays: string;
  filesDays: string;
  memoryDays: string;
}

export type PeriodField = keyof PeriodsForm;

interface PeriodRule {
  readonly label: string;
  readonly what: string;
  readonly min: number;
  readonly max: number;
  /** Empty keeps it for good. */
  readonly keepable: boolean;
  readonly from: string;
}

export const PERIOD_RULES: Record<PeriodField, PeriodRule> = {
  runEventsDays: {
    label: 'Transcripts',
    what: 'What the agent did in each turn: its steps, tool calls and their results.',
    min: 7,
    max: 3650,
    keepable: true,
    from: 'after the turn ended',
  },
  chatContentDays: {
    label: 'Chats’ and webhooks’ words',
    what: 'Messages and answers of conversations in a chat or from a webhook.',
    min: 1,
    max: 3650,
    // Kept for good only while conversations' content is (ADR-0077: Community keeps everything).
    keepable: true,
    from: 'after the conversation ended',
  },
  contentDays: {
    label: 'Conversations’ content',
    what: 'Messages, titles, questions and answers, results and outputs’ titles.',
    min: 30,
    max: 3650,
    keepable: true,
    from: 'after the conversation ended',
  },
  filesDays: {
    label: 'Files',
    what: 'Files people added and documents the agents made.',
    min: 7,
    max: 3650,
    keepable: true,
    from: 'after the conversation ended',
  },
  memoryDays: {
    label: 'Agents’ memory notes',
    what: 'Notes an agent keeps for a person or a trigger. Being shown to an agent counts as a use; tidying them up does not.',
    min: 30,
    max: 3650,
    keepable: true,
    from: 'after their last use',
  },
};

export const PERIOD_FIELDS: readonly PeriodField[] = [
  'contentDays',
  'filesDays',
  'chatContentDays',
  'runEventsDays',
  'memoryDays',
];

/**
 * A period as the server stores it: days, or null for kept for good. `memoryDays` says kept with 0
 * (CONVENTIONS §15.18), the others with null.
 */
export function storedDays(settings: RetentionPeriods, field: PeriodField): number | null {
  const days = settings[field];
  if (days === null || days === undefined) return null;
  return field === 'memoryDays' && days === 0 ? null : days;
}

export function periodsForm(settings: RetentionPeriods): PeriodsForm {
  const text = (field: PeriodField) => {
    const days = storedDays(settings, field);
    return days === null ? '' : String(days);
  };
  return {
    runEventsDays: text('runEventsDays'),
    chatContentDays: text('chatContentDays'),
    contentDays: text('contentDays'),
    filesDays: text('filesDays'),
    memoryDays: text('memoryDays'),
  };
}

/** The form's problems by field, as the server checks them (`400 RETENTION_INVALID`). */
export function periodErrors(form: PeriodsForm): Partial<Record<PeriodField, string>> {
  const errors: Partial<Record<PeriodField, string>> = {};
  for (const field of PERIOD_FIELDS) {
    const rule = PERIOD_RULES[field];
    const text = form[field].trim();
    if (!text) {
      if (!rule.keepable) errors[field] = `Enter a number of days from ${rule.min} to ${rule.max}.`;
      continue;
    }
    const n = /^\d{1,4}$/.test(text) ? Number(text) : NaN;
    if (!(n >= rule.min && n <= rule.max)) {
      errors[field] = rule.keepable
        ? `Enter ${rule.min} to ${rule.max} days, or leave it empty to keep it for good.`
        : `Enter a number of days from ${rule.min} to ${rule.max}.`;
    }
  }
  const chat = form.chatContentDays.trim() ? Number(form.chatContentDays.trim()) : null;
  const content = form.contentDays.trim() ? Number(form.contentDays.trim()) : null;
  if (
    !errors.chatContentDays &&
    !errors.contentDays &&
    content !== null &&
    (chat === null || chat > content)
  ) {
    errors.chatContentDays = 'Chats’ words cannot be kept longer than conversations’ content.';
  }
  return errors;
}

export function periodsRequest(form: PeriodsForm): RetentionPeriods {
  const days = (text: string) => (text.trim() ? Number(text.trim()) : null);
  return {
    runEventsDays: days(form.runEventsDays),
    chatContentDays: days(form.chatContentDays),
    contentDays: days(form.contentDays),
    filesDays: days(form.filesDays),
    // 0 keeps memory notes until they are deleted.
    memoryDays: days(form.memoryDays) ?? 0,
  };
}

/** The saved periods in the rail's order, "365 · 365 · 30 · 90", "keep" for one kept for good. */
export function periodsSummary(settings: RetentionPeriods): string {
  const form = periodsForm(settings);
  return PERIOD_FIELDS.map((field) => form[field] || 'keep').join(' · ');
}

/** The periods whose value differs from the saved one, in the form's order. */
export function changedPeriods(form: PeriodsForm, saved: PeriodsForm): PeriodField[] {
  return PERIOD_FIELDS.filter((field) => form[field].trim() !== saved[field]);
}

/**
 * The periods made shorter (a number for "keep" too): those that can make the next sweep remove
 * more. Lengthening one never does, so saving those alone needs no question.
 */
export function shortenedPeriods(form: PeriodsForm, saved: PeriodsForm): PeriodField[] {
  const days = (text: string) => (text.trim() ? Number(text.trim()) : Infinity);
  return PERIOD_FIELDS.filter((field) => days(form[field]) < days(saved[field]));
}

/** "Conversations’ content: 180 days, was 365", "Files: 30 days, was kept for good". */
export function shorteningPhrase(
  field: PeriodField,
  form: PeriodsForm,
  saved: PeriodsForm,
): string {
  const was = saved[field] || 'kept for good';
  return `${PERIOD_RULES[field].label}: ${form[field].trim()} days, was ${was}`;
}

/**
 * What the next sweep removes under the saved periods, in one sentence: the server's `nextSweep`,
 * counted as the preview counts its `current`, in the preview's words.
 */
export function duePhrase(settings: Pick<RetentionSettings, 'nextSweep'>): string {
  const words = sweepWords(settings.nextSweep);
  return words ? `The next daily sweep removes ${words}.` : 'The next daily sweep removes nothing.';
}

/** Whether the next sweep under the saved periods removes anything. */
export function sweepsSome(settings: Pick<RetentionSettings, 'nextSweep'>): boolean {
  return sweepWords(settings.nextSweep) !== null;
}

/** The same periods, as a request: the preview asks again only when they differ. */
export function samePeriods(a: RetentionPeriods | null, b: RetentionPeriods | null): boolean {
  if (a === b) return true;
  if (!a || !b) return false;
  return (
    a.runEventsDays === b.runEventsDays &&
    a.chatContentDays === b.chatContentDays &&
    (a.contentDays ?? null) === (b.contentDays ?? null) &&
    (a.filesDays ?? null) === (b.filesDays ?? null) &&
    (a.memoryDays ?? 0) === (b.memoryDays ?? 0)
  );
}

/**
 * What a sweep deletes, in words: "1,204 messages, 12 files and the content of 31 conversations";
 * null when nothing. Outputs' titles and turns' summaries go with a conversation's content, and
 * are named only when nothing else is.
 */
export function sweepWords(c: RetentionSweepCounts): string | null {
  const parts = [
    c.messages && countOf(c.messages, 'message'),
    c.decisions && countOf(c.decisions, 'question or approval', 'questions and approvals'),
    c.files && countOf(c.files, 'file'),
    c.conversations && `the content of ${countOf(c.conversations, 'conversation')}`,
    c.runEvents && `the transcripts of ${countOf(c.runEvents, 'turn')}`,
    c.memoryNotes && countOf(c.memoryNotes, 'memory note'),
  ].filter((p): p is string => !!p);
  if (!parts.length) {
    if (c.outputs) parts.push(countOf(c.outputs, 'output’s title', 'outputs’ titles'));
    if (c.runs) parts.push(countOf(c.runs, 'turn’s summary', 'turns’ summaries'));
  }
  return parts.length ? joinWords(parts) : null;
}

/** The preview of changed periods, in the sentences of the line under them and of the dialog. */
export interface Forecast {
  /** What the next sweep deletes under the periods as typed. */
  readonly proposed: string;
  /** The same under the saved periods. */
  readonly current: string;
  /** What the open legal holds keep from it; null when they keep nothing. */
  readonly held: string | null;
  /** Whether the periods as typed delete anything. */
  readonly some: boolean;
}

export function forecastOf(preview: RetentionPreview): Forecast {
  const proposed = sweepWords(preview.proposed);
  const current = sweepWords(preview.current);
  const held = sweepWords(preview.held);
  return {
    proposed: proposed
      ? `With these periods, the next sweep deletes ${proposed}.`
      : 'With these periods, the next sweep deletes nothing.',
    current: current ? `With the saved ones, ${current}.` : 'With the saved ones, nothing.',
    held: held ? `Legal holds keep ${held} from it.` : null,
    some: !!proposed,
  };
}
