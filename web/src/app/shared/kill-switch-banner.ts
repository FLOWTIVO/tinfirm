import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { NgIcon, provideIcons } from '@ng-icons/core';
import { lucideOctagonPause, lucideUserX } from '@ng-icons/lucide';
import type { KillSwitch, RunsStop } from '../api/generated/models';
import { suspensionCause } from './governance-labels';
import { formatAbsolute } from './time';

/** "Stopped 13 Oct 2026, 10:04 by Adam Admin: “a leaked key”." */
export function pulledSentence(
  verb: string,
  since: string | null | undefined,
  by: string | null | undefined,
  reason: string | null | undefined,
): string {
  let text = verb;
  if (since) text += ` ${formatAbsolute(since)}`;
  if (by) text += ` by ${by}`;
  return reason ? `${text}: “${reason}”.` : `${text}.`;
}

/**
 * The banner every company page shows while the company's runs are stopped, with who stopped them
 * and why (the package 6 contract, §5.4), and the caller's own suspension. Neutral, not orange: the
 * reader can do nothing about either but wait; an admin gets the link to the switches' page.
 */
@Component({
  selector: 'app-kill-switch-banner',
  imports: [NgIcon, RouterLink],
  providers: [provideIcons({ lucideOctagonPause, lucideUserX })],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'flex flex-col gap-2 empty:hidden' },
  template: `
    @if (suspension(); as s) {
      <div
        class="bg-card flex items-start gap-3 rounded-xl border p-3 text-sm"
        role="status"
        data-testid="my-suspension"
      >
        <ng-icon
          name="lucideUserX"
          class="text-muted-foreground mt-0.5 shrink-0"
          aria-hidden="true"
        />
        <div class="min-w-0">
          <p class="font-semibold">You are suspended in this company</p>
          <p class="text-muted-foreground text-pretty">
            Nothing acts as you, with your accounts or on your budget here, and you can open nothing
            but the company’s kill switches until an admin resumes you.
            {{ suspendedText() }}
          </p>
          @if (cause(); as c) {
            <p class="text-muted-foreground">{{ c }}</p>
          }
        </div>
      </div>
    }
    @if (stop(); as st) {
      <div
        class="bg-card flex flex-col gap-2 rounded-xl border p-3 text-sm sm:flex-row sm:items-start sm:gap-3"
        role="status"
        data-testid="runs-stopped-banner"
      >
        <div class="flex min-w-0 flex-1 items-start gap-3">
          <ng-icon
            name="lucideOctagonPause"
            class="text-muted-foreground mt-0.5 shrink-0"
            aria-hidden="true"
          />
          <div class="min-w-0">
            <p class="font-semibold">The company’s runs are stopped</p>
            <p class="text-muted-foreground text-pretty">
              No turn runs and no conversation starts; messages and answers are kept, and their
              turns wait. {{ stoppedText() }}
            </p>
          </div>
        </div>
        @if (manages() && companyId()) {
          <a
            class="text-primary ms-7 shrink-0 font-semibold underline underline-offset-4 sm:ms-0 sm:self-center"
            [routerLink]="['/c', companyId(), 'kill-switches']"
            >Open the kill switches</a
          >
        }
      </div>
    }
  `,
})
export class KillSwitchBanner {
  readonly stop = input<RunsStop | null>(null);
  readonly suspension = input<KillSwitch | null>(null);
  /** OWNER or ADMIN: the banner links to the switches' page, where the stop is lifted. */
  readonly manages = input(false);
  readonly companyId = input<string | undefined>();

  protected readonly cause = computed(() => suspensionCause(this.suspension()?.cause));
  protected readonly stoppedText = computed(() => {
    const st = this.stop();
    return st ? pulledSentence('Stopped', st.stoppedAt, st.byName, st.reason) : '';
  });
  protected readonly suspendedText = computed(() => {
    const s = this.suspension();
    return s ? pulledSentence('Suspended', s.since, s.byName, s.reason) : '';
  });
}
