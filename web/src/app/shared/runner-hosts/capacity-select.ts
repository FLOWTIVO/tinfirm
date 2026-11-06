import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { NgIcon, provideIcons } from '@ng-icons/core';
import { lucideChevronDown } from '@ng-icons/lucide';

/**
 * The most runs at a time Tinfirm sets (archived v1 CONVENTIONS §14.8; D12 of the Kubernetes
 * runner analysis).
 */
export const MAX_CAPACITY = 1000;

/**
 * Desired values to offer for a host with this ceiling: 1–16, as a host with Docker allows, and up
 * to the ceiling of a host with a VM per run, which may have more.
 */
export function capacityValues(ceiling: number): readonly number[] {
  const top = Math.min(MAX_CAPACITY, Math.max(16, ceiling));
  return Array.from({ length: top }, (_, i) => i + 1);
}

/**
 * Picks the desired number of runs at a time for a runner host: 1–16 (more on a host whose
 * machine allows more), or none to use the machine's limit (the ceiling, `--max-concurrent-runs`).
 * A value above the ceiling stays possible, since it applies up to the ceiling, and says so. Emits
 * on every change; the parent saves it.
 */
@Component({
  selector: 'app-capacity-select',
  imports: [NgIcon],
  providers: [provideIcons({ lucideChevronDown })],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'flex flex-col gap-1' },
  template: `
    <label class="text-muted-foreground text-xs font-semibold" [for]="selectId()"
      >{{ label() }}<span class="sr-only">{{ labelDetail() }}</span></label
    >
    <div class="relative w-full sm:w-60">
      <select
        class="border-input bg-card hover:bg-accent/40 focus-visible:border-ring focus-visible:ring-ring/50 aria-invalid:border-destructive aria-invalid:ring-destructive/20 dark:aria-invalid:ring-destructive/40 h-8 w-full min-w-0 appearance-none rounded-lg border py-0.5 ps-3 pe-8 text-base outline-none md:text-sm focus-visible:ring-3 aria-invalid:ring-3 disabled:cursor-not-allowed disabled:opacity-50"
        [id]="selectId()"
        [disabled]="disabled()"
        [attr.aria-describedby]="describedBy()"
        [attr.aria-invalid]="invalid() ? 'true' : null"
        (change)="onChange($event)"
      >
        <option value="" [selected]="value() === null">{{ clearLabel() }}</option>
        @for (n of values(); track n) {
          <option [value]="n" [selected]="value() === n">{{ optionLabel(n) }}</option>
        }
      </select>
      <ng-icon
        name="lucideChevronDown"
        class="text-muted-foreground pointer-events-none absolute end-2.5 top-1/2 -translate-y-1/2 text-base"
        aria-hidden="true"
      />
    </div>
  `,
})
export class CapacitySelect {
  readonly selectId = input.required<string>();
  /** The visible label, e.g. "Runs at a time". */
  readonly label = input.required<string>();
  /** Read after the label by screen readers only, e.g. " on office". */
  readonly labelDetail = input('');
  /** The machine's limit. */
  readonly ceiling = input.required<number>();
  /** The desired value; null uses the machine's limit. */
  readonly value = input<number | null>(null);
  readonly describedBy = input<string | null>(null);
  readonly invalid = input(false);
  readonly disabled = input(false);
  readonly valueChange = output<number | null>();

  protected readonly values = computed(() => capacityValues(this.ceiling()));
  protected readonly clearLabel = computed(() => `Use the machine's limit (${this.ceiling()})`);

  protected optionLabel(n: number): string {
    const ceiling = this.ceiling();
    return n > ceiling ? `${n} (${ceiling} on this machine)` : String(n);
  }

  protected onChange(event: Event): void {
    const raw = (event.target as HTMLSelectElement).value;
    const value = raw === '' ? null : Number(raw);
    if (value !== null && !this.values().includes(value)) return;
    if (value === this.value()) return;
    this.valueChange.emit(value);
  }
}
