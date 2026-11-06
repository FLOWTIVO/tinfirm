import { DestroyRef, DOCUMENT, inject, type Injector } from '@angular/core';

/**
 * Calls `tick` every `intervalMs` while the page is visible, and once right away, with `shown`,
 * when a hidden tab becomes visible again. Stops when the calling component (or `injector`) is
 * destroyed. Must be called in an injection context unless an injector is given.
 */
export function pollWhileVisible(
  tick: (shown?: boolean) => void,
  intervalMs: number,
  options: { injector?: Injector } = {},
): void {
  const doc = options.injector ? options.injector.get(DOCUMENT) : inject(DOCUMENT);
  const destroyRef = options.injector ? options.injector.get(DestroyRef) : inject(DestroyRef);

  let timer: ReturnType<typeof setInterval> | null = null;
  const visible = () => doc.visibilityState !== 'hidden';
  const start = () => {
    timer ??= setInterval(() => {
      if (visible()) tick();
    }, intervalMs);
  };
  const stop = () => {
    if (timer !== null) clearInterval(timer);
    timer = null;
  };
  const onVisibility = () => {
    if (visible()) {
      tick(true);
      start();
    } else {
      stop();
    }
  };

  doc.addEventListener('visibilitychange', onVisibility);
  if (visible()) start();
  destroyRef.onDestroy(() => {
    stop();
    doc.removeEventListener('visibilitychange', onVisibility);
  });
}
