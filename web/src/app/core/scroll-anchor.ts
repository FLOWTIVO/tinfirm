/**
 * Scrolling the page around a live run (DESIGN.md, The Thread): the page keeps
 * the end of the thread, its composer included, in view while the thread grows and the reader is
 * at that end, and keeps the answer where the reader sees it when the run's work folds above it.
 * It follows the thread's end, not the page's: what lies beside or below the thread (the work
 * item's side column on a narrow screen) never stops it, so it behaves alike at every width. The
 * page scrolls the document (the shell has no scrolling container). Adjustments that keep
 * something in place are instant jumps; a scroll that brings something into view is smooth unless
 * the reader prefers reduced motion.
 */

/** Distance from an end (px) that still counts as being at the end. */
export const PAGE_END_THRESHOLD = 160;

/** How long (ms) a position is held while the page re-reads the turn that replaces a live run. */
export const HOLD_MS = 4000;

export function prefersReducedMotion(doc: Document): boolean {
  return doc.defaultView?.matchMedia?.('(prefers-reduced-motion: reduce)').matches ?? true;
}

/**
 * The bottom of what the reader sees, in viewport coordinates: the visual viewport's, which an
 * on-screen keyboard or a pinch zoom makes shorter than the window, else the window's.
 */
export function visibleBottom(view: Window): number {
  const visual = view.visualViewport;
  return visual ? visual.offsetTop + visual.height : view.innerHeight;
}

/** The top of what the reader sees, in viewport coordinates (a pinch zoom moves it down). */
export function visibleTop(view: Window): number {
  return view.visualViewport?.offsetTop ?? 0;
}

/**
 * Follows the end of an element (a thread with its composer) while it grows: once following, each
 * `follow` jumps the page (instantly: following must never animate) so the element's end sits at
 * the bottom of what the reader sees, and never scrolls up. The reader follows while they see the
 * end of `reached` (the thread's turns, above its composer) or it lies just below; not when it is
 * above them (they scrolled past a short thread, to a taller side column). `measure` on every
 * scroll notes it, `start` says they wrote at the end, `stop` that they turned to something else
 * or the element is another one now, and `recheck` after every render stops following once that
 * end is out of reach without a scroll (an earlier turn's work opened), so a later change does not
 * pull them away from it. While the reader is in a menu or a dialog, `follow` holds the page still.
 */
export class EndFollower {
  private following = false;

  constructor(
    private readonly doc: Document,
    /** The element whose end stays in view. */
    private readonly end: () => Element | null | undefined,
    /** The element whose end the reader sees while following; by default the same. */
    private readonly reached: () => Element | null | undefined = end,
  ) {}

  get active(): boolean {
    return this.following;
  }

  /** Notes whether the reader is at the end of `reached`: in view, or less than the threshold below. */
  measure(): void {
    this.following = this.reachable();
  }

  start(): void {
    this.following = true;
  }

  /** Stops following when the end of `reached` lies out of reach; never starts it. */
  recheck(): void {
    if (this.following && !this.reachable()) this.following = false;
  }

  stop(): void {
    this.following = false;
  }

  follow(): void {
    if (!this.following || this.inOverlay()) return;
    const below = this.below(this.end());
    if (below > 0 && Number.isFinite(below)) this.doc.defaultView?.scrollBy?.(0, below);
  }

  /**
   * Whether the reader is in a menu or a dialog they opened: the page holds still under it, so that
   * a growing turn does not carry the menu away with its trigger.
   */
  private inOverlay(): boolean {
    return !!this.doc.activeElement?.closest?.(
      '[role="menu"], [role="dialog"], [role="alertdialog"]',
    );
  }

  private reachable(): boolean {
    const element = this.reached();
    const view = this.doc.defaultView;
    if (!element || !view) return false;
    const bottom = element.getBoundingClientRect().bottom;
    return bottom > visibleTop(view) && bottom - visibleBottom(view) < PAGE_END_THRESHOLD;
  }

  /** How far (px) the element's end lies below what the reader sees; infinite without one. */
  private below(element: Element | null | undefined): number {
    const view = this.doc.defaultView;
    if (!element || !view) return Number.POSITIVE_INFINITY;
    return element.getBoundingClientRect().bottom - visibleBottom(view);
  }
}

/** Brings the element into view at the end of the viewport, smoothly unless motion is reduced. */
export function revealAtEnd(doc: Document, element: Element | null | undefined): void {
  element?.scrollIntoView?.({
    behavior: prefersReducedMotion(doc) ? 'auto' : 'smooth',
    block: 'end',
  });
}

/**
 * Holds the bottom edge of an element at its place in the viewport while the content inside or
 * above it changes: `start` notes where the edge is (only when it is on screen), `keep` scrolls by
 * however far it moved since, until `HOLD_MS` passed or the reader scrolls on their own (`stop`).
 */
export class PositionHold {
  private edge: number | null = null;
  private until = 0;

  constructor(
    private readonly doc: Document,
    private readonly now: () => number = () => Date.now(),
  ) {}

  get active(): boolean {
    return this.edge !== null && this.now() <= this.until;
  }

  start(element: Element | null | undefined): void {
    const view = this.doc.defaultView;
    if (!element || !view) return;
    const edge = element.getBoundingClientRect().bottom;
    if (edge <= 0 || edge > view.innerHeight) {
      this.stop();
      return;
    }
    this.edge = edge;
    this.until = this.now() + HOLD_MS;
  }

  keep(element: Element | null | undefined): void {
    if (!element || this.edge === null) return;
    if (!this.active) {
      this.stop();
      return;
    }
    const delta = element.getBoundingClientRect().bottom - this.edge;
    if (delta !== 0) this.doc.defaultView?.scrollBy?.(0, delta);
  }

  stop(): void {
    this.edge = null;
  }
}
