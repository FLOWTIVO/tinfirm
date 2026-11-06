import {
  EndFollower,
  HOLD_MS,
  PositionHold,
  prefersReducedMotion,
  revealAtEnd,
  visibleBottom,
} from './scroll-anchor';

/**
 * A document whose page is `scrollHeight` tall, scrolled to `scrollTop`, in a window 800 px high;
 * `visual` is its visual viewport, when the test gives one (an on-screen keyboard).
 */
function fakeDocument(options: {
  scrollHeight: number;
  scrollTop: number;
  reduce?: boolean;
  visual?: { offsetTop: number; height: number };
}) {
  const scrollBy = vi.fn();
  const view = {
    innerHeight: 800,
    visualViewport: options.visual ?? null,
    scrollBy,
    matchMedia: (query: string) => ({
      matches: query.includes('reduce') ? (options.reduce ?? false) : false,
    }),
  };
  const doc = {
    defaultView: view,
    documentElement: { scrollHeight: options.scrollHeight, scrollTop: options.scrollTop },
  } as unknown as Document;
  return { doc, scrollBy };
}

/** An element whose bottom edge sits at `bottom` px from the viewport's top. */
function element(bottom: number) {
  const rect = { bottom };
  return {
    rect,
    el: { getBoundingClientRect: () => rect, scrollIntoView: vi.fn() } as unknown as Element & {
      scrollIntoView: ReturnType<typeof vi.fn>;
    },
  };
}

describe('scroll anchoring around a live run', () => {
  it('sees to the bottom of the visual viewport when there is one, else of the window', () => {
    const plain = fakeDocument({ scrollHeight: 3000, scrollTop: 0 }).doc.defaultView!;
    expect(visibleBottom(plain)).toBe(800);
    const keyboard = fakeDocument({
      scrollHeight: 3000,
      scrollTop: 0,
      visual: { offsetTop: 40, height: 420 },
    }).doc.defaultView!;
    expect(visibleBottom(keyboard)).toBe(460);
  });

  it("follows an element's end while the reader sees the end it measures, never upward", () => {
    const { doc, scrollBy } = fakeDocument({ scrollHeight: 3000, scrollTop: 1000 });
    const thread = element(900);
    const turns = element(760);
    const follower = new EndFollower(
      doc,
      () => thread.el,
      () => turns.el,
    );
    expect(follower.active).toBe(false);
    follower.follow();
    expect(scrollBy).not.toHaveBeenCalled();

    // The reader sees the last turn; the composer under it is below the fold.
    follower.measure();
    expect(follower.active).toBe(true);
    follower.follow();
    expect(scrollBy).toHaveBeenLastCalledWith(0, 100);
    // The thread grows by 250 px: the page keeps its end at the bottom of the viewport.
    thread.rect.bottom = 1050;
    follower.follow();
    expect(scrollBy).toHaveBeenLastCalledWith(0, 250);
    // Its end is in view: nothing scrolls, and never up.
    thread.rect.bottom = 640;
    follower.follow();
    expect(scrollBy).toHaveBeenCalledTimes(2);

    // The reader scrolled up, the last turn far below: no more following until they come back.
    turns.rect.bottom = 1400;
    follower.measure();
    expect(follower.active).toBe(false);
    thread.rect.bottom = 1600;
    follower.follow();
    expect(scrollBy).toHaveBeenCalledTimes(2);
    // They wrote at the end: following again, whatever the last scroll said.
    follower.start();
    follower.follow();
    expect(scrollBy).toHaveBeenLastCalledWith(0, 800);
    follower.stop();
    follower.follow();
    expect(scrollBy).toHaveBeenCalledTimes(3);
  });

  it('holds the page still while the reader is in a menu or a dialog', () => {
    const { doc, scrollBy } = fakeDocument({ scrollHeight: 3000, scrollTop: 1000 });
    const thread = element(900);
    const follower = new EndFollower(doc, () => thread.el);
    follower.start();
    const inside = (selector: string | null) =>
      ({
        closest: (query: string) => (selector && query.includes(selector) ? {} : null),
      }) as Element;
    const focus = (active: Element | null) =>
      Object.defineProperty(doc, 'activeElement', { value: active, configurable: true });

    focus(inside('menu'));
    follower.follow();
    expect(scrollBy).not.toHaveBeenCalled();
    focus(inside('alertdialog'));
    follower.follow();
    expect(scrollBy).not.toHaveBeenCalled();
    // Back on the page, the end is followed again.
    focus(inside(null));
    follower.follow();
    expect(scrollBy).toHaveBeenLastCalledWith(0, 100);
  });

  it('stops following once the end is out of reach without a scroll, and never starts so', () => {
    const { doc, scrollBy } = fakeDocument({ scrollHeight: 3000, scrollTop: 1000 });
    const thread = element(900);
    const turns = element(760);
    const follower = new EndFollower(
      doc,
      () => thread.el,
      () => turns.el,
    );
    // Out of reach while not following: a recheck does not start it.
    turns.rect.bottom = 1200;
    follower.recheck();
    expect(follower.active).toBe(false);

    turns.rect.bottom = 760;
    follower.measure();
    // Within reach: it keeps following.
    turns.rect.bottom = 900;
    follower.recheck();
    expect(follower.active).toBe(true);
    // The reader opened an earlier turn's work: the end of the turns went 600 px further down.
    turns.rect.bottom = 1500;
    thread.rect.bottom = 1640;
    follower.recheck();
    expect(follower.active).toBe(false);
    follower.follow();
    expect(scrollBy).not.toHaveBeenCalled();
  });

  it('does not follow an end the reader scrolled past, above what they see', () => {
    const { doc, scrollBy } = fakeDocument({ scrollHeight: 3000, scrollTop: 1000 });
    const thread = element(-40);
    const turns = element(-180);
    const follower = new EndFollower(
      doc,
      () => thread.el,
      () => turns.el,
    );
    // A short thread beside a taller side column: the reader reads below the thread's end.
    follower.measure();
    expect(follower.active).toBe(false);
    thread.rect.bottom = 1100;
    follower.follow();
    expect(scrollBy).not.toHaveBeenCalled();

    // Following, then the end goes above the screen: a recheck stops it.
    turns.rect.bottom = 700;
    follower.measure();
    expect(follower.active).toBe(true);
    turns.rect.bottom = -10;
    follower.recheck();
    expect(follower.active).toBe(false);
  });

  it('keeps the end above an on-screen keyboard, and follows nothing without an element', () => {
    const { doc, scrollBy } = fakeDocument({
      scrollHeight: 3000,
      scrollTop: 1000,
      visual: { offsetTop: 0, height: 420 },
    });
    const thread = element(500);
    const follower = new EndFollower(doc, () => thread.el);
    follower.measure();
    follower.follow();
    expect(scrollBy).toHaveBeenLastCalledWith(0, 80);

    const none = new EndFollower(doc, () => null);
    none.measure();
    expect(none.active).toBe(false);
    none.start();
    none.follow();
    expect(scrollBy).toHaveBeenCalledTimes(1);
  });

  it('brings an element into view smoothly, at once when motion is reduced', () => {
    const smooth = element(900);
    revealAtEnd(fakeDocument({ scrollHeight: 0, scrollTop: 0 }).doc, smooth.el);
    expect(smooth.el.scrollIntoView).toHaveBeenCalledWith({ behavior: 'smooth', block: 'end' });

    const reduced = fakeDocument({ scrollHeight: 0, scrollTop: 0, reduce: true }).doc;
    expect(prefersReducedMotion(reduced)).toBe(true);
    const instant = element(900);
    revealAtEnd(reduced, instant.el);
    expect(instant.el.scrollIntoView).toHaveBeenCalledWith({ behavior: 'auto', block: 'end' });
  });

  it('holds an edge where the reader saw it while the content above it changes', () => {
    let now = 1000;
    const { doc, scrollBy } = fakeDocument({ scrollHeight: 3000, scrollTop: 1000 });
    const hold = new PositionHold(doc, () => now);
    const { el, rect } = element(600);

    hold.start(el);
    expect(hold.active).toBe(true);
    // The live work folds into one row: the end moved up by 420 px; the page scrolls up as much.
    rect.bottom = 180;
    hold.keep(el);
    expect(scrollBy).toHaveBeenLastCalledWith(0, -420);
    // The answer arrives a moment later and the end moves down again.
    rect.bottom = 760;
    hold.keep(el);
    expect(scrollBy).toHaveBeenLastCalledWith(0, 160);
    rect.bottom = 600;
    hold.keep(el);
    expect(scrollBy).toHaveBeenCalledTimes(2);

    // After a few seconds, or once the reader scrolls, nothing is held any more.
    now += HOLD_MS + 1;
    rect.bottom = 100;
    hold.keep(el);
    expect(hold.active).toBe(false);
    expect(scrollBy).toHaveBeenCalledTimes(2);
    now = 0;
    hold.start(el);
    hold.stop();
    rect.bottom = 700;
    hold.keep(el);
    expect(scrollBy).toHaveBeenCalledTimes(2);
  });

  it('holds nothing that is off screen', () => {
    const { doc, scrollBy } = fakeDocument({ scrollHeight: 3000, scrollTop: 0 });
    const hold = new PositionHold(doc);
    const below = element(1200);
    hold.start(below.el);
    expect(hold.active).toBe(false);
    below.rect.bottom = 900;
    hold.keep(below.el);
    const above = element(-20);
    hold.start(above.el);
    expect(hold.active).toBe(false);
    expect(scrollBy).not.toHaveBeenCalled();
  });
});
