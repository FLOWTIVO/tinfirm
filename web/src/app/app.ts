import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { HlmToaster } from '@spartan-ng/helm/sonner';
import { mediaQuery, WIDE_SCREEN } from './core/media-query';
import { ThemeService } from './core/theme';
import { NavProgress } from './shared/nav-progress';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, HlmToaster, NavProgress],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <app-nav-progress />
    <router-outlet />
    <hlm-toaster [theme]="theme.theme()" [position]="toastPosition()" closeButton />
  `,
})
export class App {
  protected readonly theme = inject(ThemeService);
  private readonly wide = mediaQuery(WIDE_SCREEN, true);
  /**
   * Toasts sit at the bottom right of a wide screen; on a narrow one at the top, so they never
   * cover a thread's composer, which stays at the bottom of the screen.
   */
  protected readonly toastPosition = computed(() => (this.wide() ? 'bottom-right' : 'top-center'));
}
