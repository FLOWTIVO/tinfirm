import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  output,
  resource,
  untracked,
} from '@angular/core';
import { RouterLink } from '@angular/router';
import { NgIcon, provideIcons } from '@ng-icons/core';
import { lucideZap } from '@ng-icons/lucide';
import { HlmButton } from '@spartan-ng/helm/button';
import { Api } from '../../../api/generated/api';
import { listSystemPolls, listTriggers } from '../../../api/generated/functions';
import type { TriggerPoll } from '../../../api/generated/models';
import { problemMessage } from '../../../api/problem';
import { loadAll } from '../../../core/company-data';
import { ListSkeleton } from '../../../shared/list-skeleton';
import { Time } from '../../../shared/time';
import { TriggerStateChips } from './trigger-state-chips';

const POLL_STATUS: Record<TriggerPoll['status'], string> = {
  ACTIVE: 'Read',
  IDLE: 'Idle: no active trigger',
  WAITING: 'Waiting',
};

/**
 * What a poll reads the system with (ADR-0072): a system's credential, whose one poll serves the
 * triggers of every service account that borrows it, by its name; else a member's own account.
 */
export function pollWith(poll: Pick<TriggerPoll, 'credentialName' | 'holderName'>): string {
  if (poll.credentialName) return `With the credential ${poll.credentialName}`;
  return poll.holderName ? `With ${poll.holderName}’s own account` : 'With an account';
}

/**
 * A system's triggers (the package 4 contract, §6: `listTriggers?systemId=` and `GET
 * /systems/{id}/polls`), for its page's Triggers tab (W2a): the triggers that watch a tracker, use a
 * webhook or answer in a chat's channels, with why one is paused or waits; for a tracker, how it
 * is read, one poll per account (a credential, or a member's own account), with its last pass,
 * its error, its backoff and the rate limit the system reported. Without any, the way to a new one.
 *
 * Inputs: `companyId`, `systemId`, `name`, `source`.
 */
@Component({
  selector: 'app-system-triggers',
  imports: [RouterLink, NgIcon, HlmButton, ListSkeleton, Time, TriggerStateChips],
  providers: [provideIcons({ lucideZap })],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'block' },
  template: `
    <section
      class="bg-card overflow-hidden rounded-xl border"
      aria-labelledby="system-triggers-title"
      data-testid="system-triggers"
    >
      <div class="flex flex-col gap-0.5 px-4 py-4 sm:px-5 sm:pt-5">
        <h2 id="system-triggers-title" class="text-base font-bold">Triggers</h2>
        <p class="text-muted-foreground text-sm text-pretty">
          @switch (source()) {
            @case ('webhook') {
              The webhook triggers its deliveries fire, each picking them by event and conditions.
            }
            @case ('chat') {
              The channel triggers that answer mentions of the bot in {{ name() }}’s channels.
            }
            @default {
              The triggers that watch {{ name() }}, and how it is read for them. Each reads it with
              its own account, never more than that account sees.
            }
          }
        </p>
      </div>
      @if (triggers.isLoading() && !triggers.hasValue()) {
        <div class="px-4 pb-4 sm:px-5"><app-list-skeleton [rows]="1" /></div>
      } @else if (triggers.error()) {
        <p class="text-muted-foreground border-t px-4 py-4 text-sm sm:px-5" role="alert">
          {{ triggersError() }}
        </p>
      } @else if (list().length === 0) {
        <div
          class="mx-4 mb-4 flex flex-col items-center gap-2.5 rounded-lg border border-dashed px-4 py-6 text-center sm:mx-5 sm:mb-5"
        >
          <ng-icon name="lucideZap" size="20" class="text-faint" aria-hidden="true" />
          <p class="text-muted-foreground text-sm" data-testid="system-triggers-empty">
            @switch (source()) {
              @case ('webhook') {
                No webhook trigger uses {{ name() }}, so a delivery fires nothing yet.
              }
              @case ('chat') {
                No channel trigger answers in {{ name() }} yet.
              }
              @default {
                No trigger watches {{ name() }}, so nothing reads it for changes yet.
              }
            }
          </p>
          <a
            hlmBtn
            variant="outline"
            size="sm"
            class="max-sm:h-11"
            data-testid="system-new-trigger"
            [routerLink]="['/c', companyId(), 'triggers', 'new']"
            >New trigger</a
          >
        </div>
      } @else {
        <ul aria-labelledby="system-triggers-title">
          @for (t of list(); track t.id) {
            <li
              class="flex min-w-0 flex-col gap-1 border-t px-4 py-3 text-sm sm:px-5"
              [attr.data-trigger]="t.id"
            >
              <span class="flex min-w-0 flex-wrap items-center gap-2">
                <a
                  class="font-semibold break-words underline-offset-2 hover:underline"
                  [routerLink]="['/c', companyId(), 'triggers', t.id]"
                  >{{ t.name }}</a
                >
                <app-trigger-state-chips
                  [status]="t.status"
                  [pausedReason]="t.pausedReason ?? null"
                  [waitingReason]="t.waitingReason ?? null"
                />
              </span>
              <span class="text-muted-foreground text-xs break-words">{{ t.sentence }}</span>
            </li>
          }
        </ul>
      }

      @if (polls().length) {
        <div class="flex flex-col gap-1.5 border-t px-4 py-4 sm:px-5" data-testid="system-polls">
          <h3 class="text-sm font-semibold">How it is read</h3>
          <ul class="flex flex-col gap-2">
            @for (p of polls(); track p.id) {
              <li class="flex min-w-0 flex-col gap-0.5 text-xs" [attr.data-poll]="p.id">
                <span class="text-sm">
                  {{ pollWith(p) }} · {{ pollStatus[p.status] }} · {{ p.triggerIds.length }}
                  {{ p.triggerIds.length === 1 ? 'trigger' : 'triggers' }}
                </span>
                <span class="text-muted-foreground">
                  @if (p.lastPolledAt) {
                    Last read <app-time [value]="p.lastPolledAt" />
                  } @else {
                    Not read yet
                  }
                  @if (p.rateRemaining !== null && p.rateRemaining !== undefined && p.rateLimit) {
                    · {{ p.rateRemaining }} of {{ p.rateLimit }} requests left
                  }
                  @if (p.backoffUntil) {
                    · backing off until <app-time [value]="p.backoffUntil" mode="clock" />
                  }
                </span>
                @if (p.lastError) {
                  <span class="text-destructive break-words">{{ p.lastError }}</span>
                }
              </li>
            }
          </ul>
        </div>
      }
    </section>
  `,
})
export class SystemTriggers {
  private readonly api = inject(Api);

  readonly companyId = input.required<string>();
  readonly systemId = input.required<string>();
  readonly name = input('this system');
  /** Whether the reader may see the polls (OWNER, ADMIN); a tracker's only. */
  readonly showPolls = input(true);
  /** What the system is to its triggers: a tracker watched, a webhook's deliveries, a chat's channels. */
  readonly source = input<'tracker' | 'webhook' | 'chat'>('tracker');
  /** How many triggers use it, once read: the page's tab shows it. */
  readonly count = output<number>();

  protected readonly pollStatus = POLL_STATUS;
  protected readonly pollWith = pollWith;

  protected readonly triggers = resource({
    params: () => ({ companyId: this.companyId(), systemId: this.systemId() }),
    loader: ({ params }) =>
      loadAll((cursor) => this.api.invoke(listTriggers, { ...params, limit: 100, cursor })),
  });
  protected readonly list = computed(() =>
    this.triggers.hasValue() ? (this.triggers.value() ?? []) : [],
  );
  protected readonly triggersError = computed(() => problemMessage(this.triggers.error()));

  constructor() {
    effect(() => {
      if (!this.triggers.hasValue()) return;
      const count = this.list().length;
      untracked(() => this.count.emit(count));
    });
  }

  private readonly pollsRes = resource({
    params: () =>
      this.showPolls() ? { companyId: this.companyId(), systemId: this.systemId() } : undefined,
    loader: ({ params }) => this.api.invoke(listSystemPolls, params),
  });
  protected readonly polls = computed(() =>
    this.pollsRes.hasValue() ? (this.pollsRes.value()?.items ?? []) : [],
  );
}
