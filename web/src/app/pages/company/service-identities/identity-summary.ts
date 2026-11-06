import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import type { BorrowedSummary } from './identity-credentials';
import type { BudgetView } from './identity-labels';

/**
 * A service account at a glance, above its sections: the systems it borrows a credential on, the
 * triggers that use it and its budget this period. One sheet of cells split by hairlines, the
 * numbers in mono; a cell whose section is still reading says so.
 */
@Component({
  selector: 'app-identity-summary',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'block' },
  template: `
    <dl
      class="bg-border grid gap-px overflow-hidden rounded-xl border sm:grid-cols-3"
      aria-label="At a glance"
      data-testid="identity-summary"
    >
      <div class="bg-card flex min-w-0 flex-col gap-0.5 px-4 py-3.5 sm:px-5">
        <dt class="text-muted-foreground text-xs font-semibold">Borrows</dt>
        @if (borrowed(); as b) {
          <dd
            class="font-mono text-[1.375rem] font-medium tabular-nums"
            data-testid="summary-borrowed"
          >
            {{ b.systems.length }}
          </dd>
          <dd class="text-muted-foreground truncate text-[0.8125rem]">
            {{ borrowsOn() }}
          </dd>
        } @else {
          <dd class="text-muted-foreground text-[0.8125rem]">Reading…</dd>
        }
      </div>
      <div class="bg-card flex min-w-0 flex-col gap-0.5 px-4 py-3.5 sm:px-5">
        <dt class="text-muted-foreground text-xs font-semibold">Triggers</dt>
        @if (triggers() !== null) {
          <dd
            class="font-mono text-[1.375rem] font-medium tabular-nums"
            data-testid="summary-triggers"
          >
            {{ triggers() }}
          </dd>
          <dd class="text-muted-foreground truncate text-[0.8125rem]">
            {{ triggers() ? 'Act through it or pay with it' : 'None uses it' }}
          </dd>
        } @else {
          <dd class="text-muted-foreground text-[0.8125rem]">Reading…</dd>
        }
      </div>
      <div class="bg-card flex min-w-0 flex-col gap-1 px-4 py-3.5 sm:px-5">
        <dt class="text-muted-foreground text-xs font-semibold">Budget this period</dt>
        @if (budget(); as b) {
          @if (!b.configured) {
            <dd class="text-muted-foreground text-[0.8125rem]">No model gateway yet</dd>
          } @else {
            <dd class="flex flex-wrap items-baseline gap-x-1.5" data-testid="summary-budget">
              <span class="font-mono text-[1.375rem] font-medium tabular-nums">{{
                b.spent ?? b.budget
              }}</span>
              <span class="text-muted-foreground text-[0.8125rem]">
                @if (b.spent) {
                  of <span class="font-mono">{{ b.budget }}</span>
                } @else {
                  per {{ b.period }}
                }
              </span>
            </dd>
            @if (b.ratio !== null) {
              <dd>
                <div
                  class="bg-accent h-1.5 overflow-hidden rounded-full"
                  role="meter"
                  aria-label="Share of the budget spent"
                  aria-valuemin="0"
                  aria-valuemax="100"
                  [attr.aria-valuenow]="percent()"
                >
                  <div
                    class="bg-primary h-full min-w-1.5 rounded-full"
                    [style.width.%]="percent()"
                  ></div>
                </div>
              </dd>
            }
          }
        } @else {
          <dd class="text-muted-foreground text-[0.8125rem]">{{ budgetError() || 'Reading…' }}</dd>
        }
      </div>
    </dl>
  `,
})
export class IdentitySummary {
  /** What it borrows; null while it is read. */
  readonly borrowed = input<BorrowedSummary | null>(null);
  /** How many triggers use it; null while they are read. */
  readonly triggers = input<number | null>(null);
  /** Its budget; null while it is read, or when it could not be. */
  readonly budget = input<BudgetView | null>(null);
  /** Why the budget could not be read. */
  readonly budgetError = input('');

  /** "credentials, on GitHub, Jira", or that it borrows none yet. */
  protected readonly borrowsOn = computed(() => {
    const systems = this.borrowed()?.systems ?? [];
    if (systems.length === 0) return 'No credential yet';
    return `${systems.length === 1 ? 'credential' : 'credentials'}, on ${systems.join(', ')}`;
  });
  protected readonly percent = computed(() => Math.round((this.budget()?.ratio ?? 0) * 100));
}
