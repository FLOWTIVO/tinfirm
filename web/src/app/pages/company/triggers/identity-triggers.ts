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
import { lucideChevronRight, lucideZap } from '@ng-icons/lucide';
import { Api } from '../../../api/generated/api';
import { listTriggers } from '../../../api/generated/functions';
import type { TriggerSummary } from '../../../api/generated/models';
import { problemMessage } from '../../../api/problem';
import { loadAll } from '../../../core/company-data';
import { ListSkeleton } from '../../../shared/list-skeleton';
import { TriggerStateChips } from './trigger-state-chips';

/** A trigger that names a service account, and how. */
export interface IdentityTrigger {
  readonly trigger: TriggerSummary;
  /** Its account table acts through the service account, with a credential it borrows. */
  readonly acts: boolean;
  readonly pays: boolean;
}

/**
 * The triggers that act through a service account (with the credentials it borrows, ADR-0072) or
 * are paid by its budget (the package 4 contract, §5.11: "the service identity's page lists them
 * first"), for its page (W2a): what withdrawing a credential from it pauses, and why it cannot be
 * deleted while one is live (`409 SERVICE_IDENTITY_IN_USE`): the triggers whose table acts through
 * it (`accountHolderPrincipalId`), and those it pays for.
 *
 * Inputs: `companyId`, `principalId` (the service account), `name`; it tells the page how many
 * use it (`count`).
 */
@Component({
  selector: 'app-identity-triggers',
  imports: [RouterLink, NgIcon, ListSkeleton, TriggerStateChips],
  providers: [provideIcons({ lucideChevronRight, lucideZap })],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'block' },
  template: `
    <section
      class="bg-card flex flex-col gap-3 rounded-xl border p-4 sm:p-5"
      aria-labelledby="identity-triggers-title"
      data-testid="identity-triggers"
    >
      <div class="flex items-center gap-2">
        <ng-icon name="lucideZap" class="text-muted-foreground shrink-0" aria-hidden="true" />
        <h2 id="identity-triggers-title" class="text-base font-bold">Triggers that use it</h2>
        @if (items().length) {
          <span class="text-muted-foreground font-mono text-xs tabular-nums">{{
            items().length
          }}</span>
        }
      </div>
      @if (found.isLoading() && !found.hasValue()) {
        <app-list-skeleton [rows]="1" />
      } @else if (found.error()) {
        <p class="text-muted-foreground text-sm" role="alert">{{ error() }}</p>
      } @else if (items().length === 0) {
        <p class="text-muted-foreground text-sm" data-testid="identity-triggers-empty">
          No trigger acts through {{ name() }} or pays with its budget.
        </p>
      } @else {
        <ul class="-mx-2 flex flex-col gap-0.5">
          @for (i of items(); track i.trigger.id) {
            <li [attr.data-trigger]="i.trigger.id">
              <a
                class="hover:bg-accent focus-visible:ring-ring/50 flex min-w-0 items-center gap-2 rounded-[10px] px-2 py-2 text-sm outline-none focus-visible:ring-3"
                [routerLink]="['/c', companyId(), 'triggers', i.trigger.id]"
              >
                <span class="flex min-w-0 flex-1 flex-col gap-0.5">
                  <span class="flex min-w-0 flex-wrap items-center gap-2">
                    <span class="font-semibold break-words">{{ i.trigger.name }}</span>
                    <app-trigger-state-chips
                      [status]="i.trigger.status"
                      [pausedReason]="i.trigger.pausedReason ?? null"
                      [waitingReason]="i.trigger.waitingReason ?? null"
                    />
                  </span>
                  <span class="text-muted-foreground text-xs">{{ how(i) }}</span>
                </span>
                <ng-icon
                  name="lucideChevronRight"
                  class="text-muted-foreground shrink-0"
                  aria-hidden="true"
                />
              </a>
            </li>
          }
        </ul>
      }
      <p class="text-muted-foreground border-t pt-3 text-xs text-pretty">
        Withdrawing a credential it borrows, or lending it another on that system, pauses these; it
        cannot be deleted while any is live.
      </p>
    </section>
  `,
})
export class IdentityTriggers {
  private readonly api = inject(Api);

  readonly companyId = input.required<string>();
  readonly principalId = input.required<string>();
  readonly name = input('it');
  /** How many triggers use it, each time they are read. */
  readonly count = output<number>();

  protected readonly found = resource({
    params: () => ({ companyId: this.companyId(), principalId: this.principalId() }),
    loader: async ({ params: { companyId, principalId } }): Promise<IdentityTrigger[]> => {
      // What acts through it, by the holder filter; its budget, by each trigger's payer.
      const [holding, all] = await Promise.all([
        loadAll((cursor) =>
          this.api.invoke(listTriggers, {
            companyId,
            accountHolderPrincipalId: principalId,
            limit: 100,
            cursor,
          }),
        ),
        loadAll((cursor) => this.api.invoke(listTriggers, { companyId, limit: 100, cursor })),
      ]);
      const acts = new Set(holding.map((t) => t.id));
      return [...holding, ...all.filter((t) => !acts.has(t.id))]
        .map((trigger) => ({
          trigger,
          acts: acts.has(trigger.id),
          pays: trigger.payerPrincipalId === principalId,
        }))
        .filter((i) => i.acts || i.pays)
        .sort((a, b) => a.trigger.name.localeCompare(b.trigger.name));
    },
  });

  protected readonly items = computed(() =>
    this.found.hasValue() ? (this.found.value() ?? []) : [],
  );
  protected readonly error = computed(() => problemMessage(this.found.error()));

  constructor() {
    effect(() => {
      if (!this.found.hasValue()) return;
      const count = this.items().length;
      untracked(() => this.count.emit(count));
    });
  }

  protected how(i: IdentityTrigger): string {
    const parts: string[] = [];
    if (i.acts) parts.push('acts with what it borrows');
    if (i.pays) parts.push('paid by its budget');
    const by = i.trigger.authorName ? ` · by ${i.trigger.authorName}` : '';
    return `${parts.join(', ')}${by}`.replace(/^./, (c) => c.toUpperCase());
  }
}
