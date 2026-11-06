import { ChangeDetectionStrategy, Component, computed, inject, resource } from '@angular/core';
import { RouterLink } from '@angular/router';
import { NgIcon, provideIcons } from '@ng-icons/core';
import { lucideClock, lucideCreditCard, lucideTriangleAlert } from '@ng-icons/lucide';
import { Api } from '../../../api/generated/api';
import { getMyModelAccess } from '../../../api/generated/functions';
import type { ModelSubscription } from '../../../api/generated/models';
import { CurrentCompany } from '../../../core/current-company';
import { managesCompany } from '../../../core/permissions';
import { ErrorState } from '../../../shared/error-state';
import {
  RUNTIME_LABELS,
  SUBSCRIPTION_KIND_LABELS,
  SUBSCRIPTION_STATUS_LABELS,
  SUBSCRIPTION_STATUS_TONES,
} from '../../../shared/labels';
import { ListSkeleton } from '../../../shared/list-skeleton';
import { Time } from '../../../shared/time';
import { budgetView } from './budget-view';

/**
 * Accounts › Budget (plan §5): who pays for the reader's conversations. Their budget at the
 * company's model gateway (what is left, what was spent, when it resets, the models it may use) and
 * its state, then the subscriptions on their personal runner hosts, which pay instead of the budget
 * where their settings pick one. Admins set budgets under Model access; nothing here changes them.
 */
@Component({
  selector: 'app-accounts-budget',
  imports: [RouterLink, NgIcon, ErrorState, ListSkeleton, Time],
  providers: [provideIcons({ lucideClock, lucideCreditCard, lucideTriangleAlert })],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'flex flex-col gap-5' },
  template: `
    @if (access.isLoading() && !access.hasValue()) {
      <app-list-skeleton [rows]="2" />
    } @else if (access.error() && !access.hasValue()) {
      <app-error-state
        [error]="access.error()"
        heading="Couldn't load your budget"
        (retry)="access.reload()"
      />
    } @else if (view(); as v) {
      <section
        class="bg-card flex flex-col gap-4 rounded-xl border p-5"
        data-testid="budget"
        aria-labelledby="budget-heading"
        [attr.data-status]="access.value()?.keyStatus ?? 'NONE'"
      >
        <div class="flex flex-wrap items-baseline justify-between gap-x-3 gap-y-1">
          <h2 id="budget-heading" class="text-base font-bold">Your budget</h2>
          @if (v.numbers) {
            <span class="text-muted-foreground text-xs" data-testid="budget-whose">{{
              v.whose
            }}</span>
          }
        </div>

        @if (v.notice; as notice) {
          <div
            class="bg-muted/60 flex items-start gap-2.5 rounded-xl px-3.5 py-3 text-sm"
            role="status"
            data-testid="budget-notice"
            [attr.data-notice]="notice.key"
          >
            <ng-icon
              class="mt-0.5 shrink-0"
              aria-hidden="true"
              [name]="notice.key === 'error' ? 'lucideTriangleAlert' : 'lucideClock'"
              [class]="notice.key === 'pending' ? 'text-muted-foreground' : 'text-warning'"
            />
            <div class="flex min-w-0 flex-col gap-0.5">
              <p class="font-semibold text-pretty">{{ notice.title }}</p>
              <p class="text-muted-foreground text-pretty break-words">{{ notice.body }}</p>
              @if (notice.key === 'unconfigured' && admin()) {
                <a
                  class="text-primary mt-1 self-start text-sm font-semibold underline-offset-2 hover:underline"
                  [routerLink]="['/c', current.id(), 'model-access']"
                  >Set up model access</a
                >
              }
            </div>
          </div>
        }

        @if (v.numbers) {
          <div class="flex flex-wrap items-baseline gap-x-2 gap-y-1">
            <span
              class="font-mono text-3xl font-medium tracking-tight tabular-nums"
              data-testid="budget-left"
              >{{ v.left }}</span
            >
            <span class="text-muted-foreground text-sm" data-testid="budget-of">
              left of <span class="text-foreground font-mono tabular-nums">{{ v.budget }}</span>
              {{ v.period }}
            </span>
          </div>
          <div
            class="bg-muted h-2 overflow-hidden rounded-full"
            role="meter"
            aria-label="Budget spent"
            aria-valuemin="0"
            [attr.aria-valuemax]="access.value()?.budgetUsd"
            [attr.aria-valuenow]="access.value()?.spentUsd ?? 0"
            [attr.aria-valuetext]="v.spent + ' of ' + v.budget + ' spent'"
          >
            <div
              class="h-full rounded-full"
              [class]="v.percent >= 100 ? 'bg-warning' : 'bg-muted-foreground'"
              [style.width.%]="v.percent"
            ></div>
          </div>
          <dl class="grid grid-cols-2 gap-x-4 gap-y-3 text-sm sm:grid-cols-3">
            <div class="flex min-w-0 flex-col gap-0.5">
              <dt class="text-muted-foreground text-xs font-semibold">Spent</dt>
              <dd class="font-mono tabular-nums" data-testid="budget-spent">{{ v.spent }}</dd>
            </div>
            <div class="flex min-w-0 flex-col gap-0.5">
              <dt class="text-muted-foreground text-xs font-semibold">Period</dt>
              <dd data-testid="budget-resets">{{ v.resets ?? 'Resets with its first spend' }}</dd>
            </div>
            <div class="col-span-2 flex min-w-0 flex-col gap-0.5 sm:col-span-1">
              <dt class="text-muted-foreground text-xs font-semibold">Models</dt>
              <dd data-testid="budget-models">
                @if (v.models; as models) {
                  <span class="font-mono text-xs break-words">{{ models.join(', ') }}</span>
                  <span class="text-muted-foreground text-xs"> · {{ v.modelsWhose }}</span>
                } @else {
                  Every model the gateway serves
                }
              </dd>
            </div>
          </dl>
          @if (access.value()?.syncedAt; as synced) {
            <p class="text-muted-foreground text-xs">
              Spend as the model gateway last reported it, <app-time [value]="synced" />.
            </p>
          }
        }
      </section>

      <section class="flex flex-col gap-3" aria-labelledby="subscriptions-heading">
        <div class="flex flex-col gap-0.5">
          <h2 id="subscriptions-heading" class="text-base font-bold">Your subscriptions</h2>
          <p class="text-muted-foreground text-sm text-pretty">
            A Claude or ChatGPT subscription on a runner host of yours pays for your own
            conversations instead of your budget, where your settings for an agent pick it.
          </p>
        </div>
        @if (subscriptions().length) {
          <ul class="bg-card divide-y rounded-xl border" aria-labelledby="subscriptions-heading">
            @for (s of subscriptions(); track s.runnerHostId + s.label) {
              <li
                class="flex flex-col gap-1 px-4 py-3 sm:flex-row sm:items-center sm:gap-3"
                data-testid="subscription"
                [attr.data-status]="s.status"
              >
                <div class="flex min-w-0 flex-1 flex-col gap-0.5">
                  <span class="font-mono text-sm font-medium break-all">{{ s.label }}</span>
                  <p class="text-muted-foreground text-xs">
                    {{ kindLabels[s.kind] }} on
                    <span class="font-mono">{{ s.runnerHostName }}</span> · {{ runtimesOf(s) }}
                    @if (!s.reported) {
                      · not reported when the host last connected, so runs that name it are refused
                    }
                  </p>
                </div>
                <span
                  class="inline-flex h-6 items-center self-start rounded-full border px-2.5 text-xs font-semibold whitespace-nowrap sm:self-center"
                  [class]="statusTones[s.status]"
                  >{{ statusLabels[s.status] }}</span
                >
              </li>
            }
          </ul>
        } @else {
          <div
            class="text-muted-foreground flex items-start gap-3 rounded-xl border border-dashed p-4 text-sm"
            data-testid="subscriptions-empty"
          >
            <ng-icon class="mt-0.5 shrink-0" name="lucideCreditCard" aria-hidden="true" />
            <p class="text-pretty">
              None. Register a
              <a
                class="text-primary font-semibold underline-offset-2 hover:underline"
                [routerLink]="[]"
                [queryParams]="{ tab: 'hosts' }"
                >runner host of your own</a
              >
              and add your subscription on it; it shows here once the host reports it.
            </p>
          </div>
        }
      </section>
    }
  `,
})
export class AccountsBudget {
  private readonly api = inject(Api);
  protected readonly current = inject(CurrentCompany);

  protected readonly admin = computed(() => managesCompany(this.current.access()));
  protected readonly kindLabels = SUBSCRIPTION_KIND_LABELS;
  protected readonly statusLabels = SUBSCRIPTION_STATUS_LABELS;
  protected readonly statusTones = SUBSCRIPTION_STATUS_TONES;

  protected readonly access = resource({
    params: () => this.current.id(),
    loader: ({ params: companyId }) => this.api.invoke(getMyModelAccess, { companyId }),
  });
  protected readonly view = computed(() =>
    this.access.hasValue() ? budgetView(this.access.value()) : null,
  );
  protected readonly subscriptions = computed<readonly ModelSubscription[]>(() =>
    this.access.hasValue() ? this.access.value().subscriptions : [],
  );

  protected runtimesOf(subscription: ModelSubscription): string {
    return subscription.runtimes.map((r) => RUNTIME_LABELS[r]).join(', ');
  }
}
