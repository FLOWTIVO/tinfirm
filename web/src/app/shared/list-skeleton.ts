import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { HlmSkeleton } from '@spartan-ng/helm/skeleton';

/** Placeholder rows while a list loads. */
@Component({
  selector: 'app-list-skeleton',
  imports: [HlmSkeleton],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="divide-y bg-card rounded-xl border" aria-busy="true">
      <span class="sr-only" role="status">Loading…</span>
      @for (row of rowList(); track row) {
        <div class="flex items-center gap-4 px-3 py-3">
          <hlm-skeleton class="size-7 shrink-0 rounded-full" />
          <div class="flex min-w-0 flex-1 flex-col gap-1.5">
            <hlm-skeleton class="h-3.5 w-2/5 max-w-60" />
            <hlm-skeleton class="h-3 w-1/4 max-w-40" />
          </div>
          <hlm-skeleton class="hidden h-3.5 w-20 sm:block" />
        </div>
      }
    </div>
  `,
})
export class ListSkeleton {
  readonly rows = input(4);
  protected readonly rowList = computed(() => Array.from({ length: this.rows() }, (_, i) => i));
}
