import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { NgIcon, provideIcons } from '@ng-icons/core';
import { lucidePause } from '@ng-icons/lucide';
import type { ExternalSystem } from '../../../api/generated/models';
import { systemStatus } from './system-kinds';

/** The status as a system's own page names it: one state, paused above the rest. */
const PAGE_LABELS: Record<ExternalSystem['status'], string> = {
  OK: 'Ready',
  ERROR: 'Error',
  NEW: 'Not tested yet',
};

/**
 * A system's status as a pill (DESIGN.md, status chips): "Not tested yet" neutral, "Reachable" in
 * success, "Unreachable" in destructive with the last error as its title; "Paused" neutral beside it
 * while an admin paused it (package 6). Never orange: nobody is waited on. A webhook, which nothing
 * tests or reaches, has no reachability.
 *
 * On the system's own page (`page`) it is one chip: Paused while paused, else Ready, Not tested yet
 * or Error; a system without a base URL (a git host) has nothing to reach, so no chip either.
 */
@Component({
  selector: 'app-system-status-chip',
  imports: [NgIcon],
  providers: [provideIcons({ lucidePause })],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'inline-flex flex-wrap items-center gap-1.5 empty:hidden' },
  template: `
    @if (page()) {
      @if (system().paused) {
        <span
          class="bg-muted text-muted-foreground inline-flex h-6 items-center gap-1 rounded-full border px-2.5 text-xs font-semibold whitespace-nowrap"
          data-testid="system-paused"
          ><ng-icon name="lucidePause" size="12" aria-hidden="true" />Paused</span
        >
      } @else if (reachable()) {
        <span
          class="inline-flex h-6 items-center rounded-full border px-2.5 text-xs font-semibold whitespace-nowrap"
          [class]="toneClass()"
          [attr.title]="system().status === 'ERROR' ? system().lastError : null"
          data-testid="system-status"
          [attr.data-status]="system().status"
          >{{ pageLabels[system().status] }}</span
        >
      }
    } @else {
      @if (system().kind !== 'WEBHOOK') {
        <span
          class="inline-flex h-6 items-center rounded-full border px-2.5 text-xs font-semibold whitespace-nowrap"
          [class]="toneClass()"
          [attr.title]="system().status === 'ERROR' ? system().lastError : null"
          data-testid="system-status"
          [attr.data-status]="system().status"
          >{{ status().label }}</span
        >
      }
      @if (system().paused) {
        <span
          class="bg-muted text-muted-foreground inline-flex h-6 items-center rounded-full border px-2.5 text-xs font-semibold"
          data-testid="system-paused"
          >Paused</span
        >
      }
    }
  `,
})
export class SystemStatusChip {
  readonly system = input.required<
    Pick<ExternalSystem, 'status' | 'lastError' | 'paused'> & {
      kind?: ExternalSystem['kind'];
      baseUrl?: string | null;
    }
  >();
  /** The system's own page: one chip, in that page's words. */
  readonly page = input(false);

  protected readonly pageLabels = PAGE_LABELS;
  protected readonly status = computed(() => systemStatus(this.system()));
  /** Something to reach: a base URL, and not a webhook, which is reached instead. */
  protected readonly reachable = computed(
    () => this.system().kind !== 'WEBHOOK' && !!this.system().baseUrl,
  );
  /** DESIGN.md, status chip: the white ground, the tone's text and a border of that tone at 40%, on every page. */
  protected readonly toneClass = computed(() => {
    switch (this.status().tone) {
      case 'success':
        return 'bg-card text-success border-success/40';
      case 'destructive':
        return 'bg-card text-destructive border-destructive/40';
      default:
        return 'bg-card text-muted-foreground';
    }
  });
}
