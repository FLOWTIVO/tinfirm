import type { InboxTone } from './inbox-words';

/**
 * The classes the inbox's rows share. A row of what needs the reader and of a turn that stopped is
 * one grid: the kind's pill, the agent's tile, what it is with its meta line, and its actions. On a
 * phone the pill heads the row with the time beside it, and the actions wrap under the text.
 */
export const ROW_GRID =
  'grid grid-cols-[2rem_minmax(0,1fr)_auto] items-center gap-x-3.5 gap-y-2 md:grid-cols-[8rem_2rem_minmax(0,1fr)_auto]';
export const CELL_KIND = 'col-span-2 flex min-w-0 md:col-span-1';
/** Beside the pill on a phone only: how long it waits, or when it stopped. */
export const CELL_ASIDE =
  'text-muted-foreground col-start-3 row-start-1 text-xs whitespace-nowrap md:hidden';
export const CELL_TILE = 'row-start-2 md:col-start-2 md:row-start-1';
export const CELL_TEXT =
  'col-span-2 col-start-2 row-start-2 min-w-0 md:col-span-1 md:col-start-3 md:row-start-1';
export const CELL_ACTS =
  'col-span-full flex flex-wrap items-center gap-1.5 md:col-span-1 md:col-start-4 md:row-start-1 md:max-w-sm md:justify-end';

export const KIND_PILL =
  'inline-flex h-6 max-w-full min-w-0 items-center gap-1.5 rounded-full px-2.5 text-xs font-semibold whitespace-nowrap';
export const KIND_TONES: Record<InboxTone, string> = {
  human: 'bg-human-soft text-human',
  destructive: 'bg-destructive/10 text-destructive',
  muted: 'bg-secondary text-muted-foreground',
};

/** The agent's initials tile; neutral for what no agent asked (an admin's item). */
export const TILE =
  'flex size-8 shrink-0 items-center justify-center rounded-[10px] text-xs font-bold';
export const TILE_AGENT = 'bg-primary text-primary-foreground';
export const TILE_NEUTRAL = 'bg-secondary text-muted-foreground';

/** What it is, in one line on a desktop; wrapped on a phone. */
export const ROW_TITLE =
  'block max-w-full text-start text-sm leading-snug font-semibold break-words md:truncate';
export const META =
  'text-muted-foreground mt-0.5 flex flex-wrap items-baseline gap-x-1.5 gap-y-2 text-xs leading-normal';
export const META_LINK =
  '-my-1 py-1 hover:text-foreground focus-visible:ring-ring/50 rounded-sm font-mono underline-offset-2 outline-none hover:underline focus-visible:ring-3';

/** A small action of a row: 32px, 44px on a phone. */
export const ROW_BUTTON = 'max-md:h-11 max-md:px-4';
