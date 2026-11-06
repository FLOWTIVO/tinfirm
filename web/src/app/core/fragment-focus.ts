import { afterNextRender, DOCUMENT, effect, inject, Injector } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute } from '@angular/router';

/**
 * Brings the element named by the URL fragment (`#host-…`) into view and focuses it, once `ready`
 * says the list it lives in has loaded. Links from a queued run to the runner host or agent that
 * holds it land this way. The element needs `tabindex="-1"` to take focus.
 * Must be called in an injection context.
 */
export function focusFragmentWhenReady(ready: () => boolean): void {
  const route = inject(ActivatedRoute);
  const doc = inject(DOCUMENT);
  const injector = inject(Injector);
  const fragment = toSignal(route.fragment, { initialValue: route.snapshot.fragment });
  let handled: string | null = null;

  effect(() => {
    const id = fragment();
    if (!id || id === handled || !ready()) return;
    handled = id;
    afterNextRender(
      () => {
        const element = doc.getElementById(id);
        if (!element) return;
        const reduce =
          doc.defaultView?.matchMedia?.('(prefers-reduced-motion: reduce)').matches ?? true;
        element.scrollIntoView?.({ behavior: reduce ? 'auto' : 'smooth', block: 'center' });
        element.focus({ preventScroll: true });
      },
      { injector },
    );
  });
}
