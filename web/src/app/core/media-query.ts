import { DestroyRef, DOCUMENT, inject, type Signal, signal } from '@angular/core';

/** A wide screen: Tailwind's `lg` breakpoint, where a page shows its side column beside it. */
export const WIDE_SCREEN = '(min-width: 64rem)';

/**
 * Whether a media query matches, as a signal kept current until the calling component is
 * destroyed; `fallback` where the window cannot tell (no `matchMedia`, as in unit tests). Must be
 * called in an injection context.
 */
export function mediaQuery(query: string, fallback = false): Signal<boolean> {
  const view = inject(DOCUMENT).defaultView;
  const list = typeof view?.matchMedia === 'function' ? view.matchMedia(query) : null;
  const matches = signal(list ? list.matches : fallback);
  if (list) {
    const onChange = (event: MediaQueryListEvent) => matches.set(event.matches);
    list.addEventListener('change', onChange);
    inject(DestroyRef).onDestroy(() => list.removeEventListener('change', onChange));
  }
  return matches.asReadonly();
}
