import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { NgIcon, provideIcons } from '@ng-icons/core';
import { lucideClock, lucideTriangleAlert } from '@ng-icons/lucide';
import type { Licence } from '../api/generated/models';
import { licenceNotice } from './licence-labels';

/**
 * The notice of a licence that ends (ADR-0077 decision 7): from 30 days before its end, the days
 * left and the day; in the 30 days of grace, the day the paid features stop; then what stopped and
 * what stays. Nothing while the licence is active, absent or Community. An instance admin gets the
 * link to the licence page, where the renewal’s key is entered.
 */
@Component({
  selector: 'app-licence-banner',
  imports: [NgIcon, RouterLink],
  providers: [provideIcons({ lucideClock, lucideTriangleAlert })],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'block empty:hidden' },
  template: `
    @if (notice(); as n) {
      <div
        class="flex flex-col gap-2 rounded-xl border p-3 text-sm sm:flex-row sm:items-start sm:gap-3"
        [class]="
          n.state === 'EXPIRED'
            ? 'border-destructive/40 bg-destructive/5'
            : 'border-warning/40 bg-warning/5'
        "
        role="status"
        data-testid="licence-banner"
        [attr.data-state]="n.state"
      >
        <div class="flex min-w-0 flex-1 items-start gap-3">
          <ng-icon
            [name]="n.state === 'EXPIRING' ? 'lucideClock' : 'lucideTriangleAlert'"
            class="mt-0.5 shrink-0"
            [class]="n.state === 'EXPIRED' ? 'text-destructive' : 'text-warning'"
            aria-hidden="true"
          />
          <div class="flex min-w-0 flex-col gap-1">
            <p class="font-semibold" data-testid="licence-banner-title">{{ n.title }}</p>
            <p class="text-muted-foreground text-pretty">{{ n.text }}</p>
            @if (n.stopped.length > 0) {
              <div class="grid gap-x-6 gap-y-1 sm:grid-cols-2">
                <div>
                  <p class="text-xs font-semibold">Stopped</p>
                  <ul
                    class="text-muted-foreground m-0 list-disc ps-4"
                    data-testid="licence-stopped"
                  >
                    @for (line of n.stopped; track line) {
                      <li>{{ line }}</li>
                    }
                  </ul>
                </div>
                <div>
                  <p class="text-xs font-semibold">Kept</p>
                  <ul class="text-muted-foreground m-0 list-disc ps-4" data-testid="licence-kept">
                    @for (line of n.kept; track line) {
                      <li>{{ line }}</li>
                    }
                  </ul>
                </div>
              </div>
            }
          </div>
        </div>
        @if (instanceAdmin() && link()) {
          <a
            class="text-primary ms-7 shrink-0 font-semibold underline underline-offset-4 sm:ms-0 sm:self-center"
            routerLink="/admin/licence"
            >Open the licence</a
          >
        }
      </div>
    }
  `,
})
export class LicenceBanner {
  readonly licence = input<Licence | null>(null);
  /** An instance admin: the banner links to the licence page, where a new key is entered. */
  readonly instanceAdmin = input(false);
  /** False on the licence page itself. */
  readonly link = input(true);

  protected readonly notice = computed(() => {
    const licence = this.licence();
    return licence ? licenceNotice(licence) : null;
  });
}
