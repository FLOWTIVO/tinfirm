/**
 * Agent handles and mentions (ADR-0034): people mention an agent in a comment as `@handle`. The
 * server decides; these helpers mirror its rules so the web can show what it will do.
 */

/** The longest handle. */
export const MAX_HANDLE_LENGTH = 50;

/** The handle when the name leaves nothing. */
const FALLBACK = 'agent';

/**
 * A handle as a member may type it: letters, digits and hyphens, not starting or ending with a
 * hyphen. The server stores it in lower case.
 */
export const HANDLE_PATTERN = /^[A-Za-z0-9](?:[A-Za-z0-9-]{0,48}[A-Za-z0-9])?$/;

function trimHyphens(text: string): string {
  return text.replace(/^-+|-+$/g, '');
}

/**
 * The handle the server makes from an agent's name when a create has none: accents dropped
 * (Unicode NFD without combining marks, `Vývojář` → `vyvojar`), lower case, every run of other
 * characters one hyphen, hyphens trimmed, cut to 50 characters without a trailing hyphen, and
 * `agent` when nothing is left.
 */
export function handleFromName(name: string): string {
  const folded = name
    .normalize('NFD')
    .replace(/\p{M}+/gu, '')
    .toLowerCase();
  let handle = trimHyphens(folded.replace(/[^a-z0-9]+/g, '-'));
  if (handle.length > MAX_HANDLE_LENGTH) {
    handle = trimHyphens(handle.slice(0, MAX_HANDLE_LENGTH));
  }
  return handle || FALLBACK;
}

/**
 * The first of `base`, `base-2`, `base-3`… that no other agent has; the base is shortened so the
 * whole stays within 50 characters, as the server does.
 */
export function uniqueHandle(base: string, taken: readonly string[]): string {
  const used = new Set(taken.map((h) => h.toLowerCase()));
  if (!used.has(base)) return base;
  for (let n = 2; ; n++) {
    const suffix = `-${n}`;
    const candidate = trimHyphens(base.slice(0, MAX_HANDLE_LENGTH - suffix.length)) + suffix;
    if (!used.has(candidate)) return candidate;
  }
}

/** Characters after which `@` cannot start a mention: part of a word, an address or a path. */
const NOT_BEFORE = /[\p{L}\p{N}_\-/.@]/u;

/** Characters a handle is read from: letters, digits and hyphens. */
const HANDLE_CHAR = /[\p{L}\p{N}-]/u;

/** A mention found in a text: where its `@` is, and the handle as written. */
export interface MentionMatch {
  readonly index: number;
  readonly handle: string;
}

/**
 * Every `@word` that can be a mention, by the server's rule: `@` at the start or after a character
 * that is not a letter, digit, `_`, `-`, `/`, `.` or `@`, then the longest run of letters, digits
 * and hyphens. Code is not left out.
 */
export function mentionMatches(text: string): MentionMatch[] {
  const chars = [...text];
  const found: MentionMatch[] = [];
  let offset = 0;
  for (let i = 0; i < chars.length; offset += chars[i].length, i++) {
    if (chars[i] !== '@' || (i > 0 && NOT_BEFORE.test(chars[i - 1]))) continue;
    let end = i + 1;
    while (end < chars.length && HANDLE_CHAR.test(chars[end])) end++;
    if (end > i + 1) found.push({ index: offset, handle: chars.slice(i + 1, end).join('') });
  }
  return found;
}
