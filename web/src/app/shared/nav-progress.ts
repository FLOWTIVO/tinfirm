import { ChangeDetectionStrategy, Component, effect, inject, signal } from '@angular/core';
import { Router } from '@angular/router';

/** Navigations faster than this show nothing, so quick ones don't flicker. */
export const NAV_PROGRESS_DELAY_MS = 150;

/**
 * A thin bar along the top of the window while a navigation is pending (a page's code loading, a guard).
 * A menu click that waits on a slow server then shows that it was taken.
 */
@Component({
  selector: 'app-nav-progress',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (visible()) {
      <div class="nav-progress" role="progressbar" aria-label="Loading the page"></div>
    }
  `,
  styles: `
    :host {
      position: fixed;
      inset-inline: 0;
      top: 0;
      z-index: 60;
      pointer-events: none;
    }
    .nav-progress {
      position: relative;
      height: 2px;
      overflow: hidden;
      background: color-mix(in oklab, var(--primary) 22%, transparent);
    }
    .nav-progress::after {
      content: '';
      position: absolute;
      inset-block: 0;
      width: 40%;
      background: var(--primary);
      animation: nav-progress 1.1s ease-in-out infinite;
    }
    @keyframes nav-progress {
      from {
        transform: translateX(-100%);
      }
      to {
        transform: translateX(250%);
      }
    }
    @media (prefers-reduced-motion: reduce) {
      .nav-progress::after {
        width: 100%;
        opacity: 0.6;
        animation: none;
      }
    }
  `,
})
export class NavProgress {
  protected readonly visible = signal(false);

  constructor() {
    const router = inject(Router);
    effect((onCleanup) => {
      if (router.currentNavigation() === null) {
        this.visible.set(false);
        return;
      }
      const timer = setTimeout(() => this.visible.set(true), NAV_PROGRESS_DELAY_MS);
      onCleanup(() => clearTimeout(timer));
    });
  }
}
