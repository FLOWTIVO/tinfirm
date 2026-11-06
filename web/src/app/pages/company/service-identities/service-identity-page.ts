import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  input,
  linkedSignal,
  resource,
  signal,
} from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { NgIcon, provideIcons } from '@ng-icons/core';
import {
  lucideBot,
  lucideChevronLeft,
  lucideCircleAlert,
  lucideInfo,
  lucidePause,
  lucidePencil,
  lucidePlay,
  lucideTrash2,
  lucideUsers,
  lucideWallet,
} from '@ng-icons/lucide';
import { HlmAlertImports } from '@spartan-ng/helm/alert';
import { HlmAlertDialogImports } from '@spartan-ng/helm/alert-dialog';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmDialogImports } from '@spartan-ng/helm/dialog';
import { HlmFieldImports } from '@spartan-ng/helm/field';
import { HlmInput } from '@spartan-ng/helm/input';
import { HlmSkeleton } from '@spartan-ng/helm/skeleton';
import { HlmSpinner } from '@spartan-ng/helm/spinner';
import { Api } from '../../../api/generated/api';
import {
  deleteServiceIdentity,
  getPrincipalModelAccess,
  getServiceIdentity,
  listGroups,
  replaceServiceIdentityUsers,
  resumeServiceIdentity,
  suspendServiceIdentity,
} from '../../../api/generated/functions';
import type { ServiceIdentity, SystemRef } from '../../../api/generated/models';
import { problemMessage, toProblem } from '../../../api/problem';
import { CurrentCompany } from '../../../core/current-company';
import { Notify } from '../../../core/notify';
import { sameParams } from '../../../core/paged-resource';
import { ErrorState } from '../../../shared/error-state';
import { Time } from '../../../shared/time';
import { NotFound } from '../../not-found';
import { type BorrowedSummary, IdentityCredentials } from './identity-credentials';
import { IdentityDialog } from './identity-dialog';
import { budgetView, identityUsers, loadAll } from './identity-labels';
import { IdentityStatusChip } from './identity-status-chip';
import { IdentitySummary } from './identity-summary';
import { SYSTEM_KIND_ICONS, systemKindIcon } from '../systems/system-kinds';
import { IdentityTriggers } from '../triggers/identity-triggers';

const FORBIDDEN = { 'status:403': 'Only owners and admins can manage service accounts.' };

/** What a service account is, while its own description says nothing. */
const DEFAULT_DESCRIPTION =
  'A service account never signs in. It acts only with the credentials systems lend it, in the ' +
  'triggers of whoever may use it, and pays with its own budget.';

/**
 * One service account, as the web app calls a service identity (the web plan §5; ADR-0072): at a
 * glance, then the credentials it borrows, one per system, read-only and each linked to its
 * system's page where it is lent, beside a column of how it is used (the groups that may use it,
 * the triggers that do, a chat bot it borrows, its budget), and the kill switch: Suspend stops
 * everything that acts through it or on its budget until it is resumed. OWNER and ADMIN only; the
 * server answers 404 for anything else.
 */
@Component({
  selector: 'app-service-identity-page',
  imports: [
    IdentityTriggers,
    RouterLink,
    NgIcon,
    HlmAlertImports,
    HlmAlertDialogImports,
    HlmButton,
    HlmDialogImports,
    HlmFieldImports,
    HlmInput,
    HlmSkeleton,
    HlmSpinner,
    ErrorState,
    IdentityCredentials,
    IdentityDialog,
    IdentityStatusChip,
    IdentitySummary,
    NotFound,
    Time,
  ],
  providers: [
    provideIcons({
      ...SYSTEM_KIND_ICONS,
      lucideBot,
      lucideChevronLeft,
      lucideCircleAlert,
      lucideInfo,
      lucidePause,
      lucidePencil,
      lucidePlay,
      lucideTrash2,
      lucideUsers,
      lucideWallet,
    }),
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (notFound()) {
      <app-not-found />
    } @else if (identityRes.error() && !identity()) {
      <app-error-state
        [error]="identityRes.error()"
        heading="Couldn't open this service account"
        [messages]="forbidden"
        (retry)="identityRes.reload()"
      />
    } @else if (identity(); as si) {
      <div class="flex flex-col gap-5">
        <header class="flex flex-col gap-3">
          <a
            class="text-muted-foreground hover:text-foreground inline-flex w-fit items-center gap-1 text-sm font-medium"
            [routerLink]="['/c', companyId(), 'service-identities']"
          >
            <ng-icon name="lucideChevronLeft" aria-hidden="true" />
            Service accounts
          </a>
          <div class="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
            <div class="flex min-w-0 items-center gap-4">
              <span
                class="bg-secondary text-primary flex size-13 shrink-0 items-center justify-center rounded-[14px] border"
                aria-hidden="true"
              >
                <ng-icon name="lucideBot" size="24" />
              </span>
              <div class="flex min-w-0 flex-col gap-1">
                <div class="flex flex-wrap items-center gap-2.5">
                  <h1 class="text-2xl font-bold tracking-tight text-balance">{{ si.name }}</h1>
                  <app-identity-status-chip [status]="si.status" />
                </div>
                <p class="text-muted-foreground max-w-prose text-sm text-pretty">
                  {{ si.description || defaultDescription }}
                </p>
              </div>
            </div>
            <div class="flex shrink-0 flex-wrap items-center gap-2">
              <button
                hlmBtn
                variant="ghost"
                size="sm"
                type="button"
                data-testid="edit-identity"
                (click)="editOpen.set(true)"
              >
                <ng-icon name="lucidePencil" aria-hidden="true" />
                Edit details
              </button>
              @if (si.status === 'SUSPENDED') {
                <button
                  hlmBtn
                  variant="outline"
                  size="sm"
                  type="button"
                  data-testid="resume-identity"
                  [disabled]="statusBusy()"
                  (click)="resume()"
                >
                  @if (statusBusy()) {
                    <hlm-spinner aria-hidden="true" />
                  } @else {
                    <ng-icon name="lucidePlay" aria-hidden="true" />
                  }
                  Resume
                </button>
              } @else {
                <button
                  hlmBtn
                  variant="outline"
                  size="sm"
                  type="button"
                  data-testid="suspend-identity"
                  (click)="openSuspend()"
                >
                  <ng-icon name="lucidePause" aria-hidden="true" />
                  Suspend
                </button>
              }
              <button
                hlmBtn
                variant="destructive"
                size="sm"
                type="button"
                data-testid="delete-identity"
                (click)="openDelete()"
              >
                <ng-icon name="lucideTrash2" aria-hidden="true" />
                Delete
              </button>
            </div>
          </div>
        </header>

        @if (si.status === 'SUSPENDED') {
          <div
            class="bg-card text-muted-foreground flex items-start gap-2 rounded-xl border px-3.5 py-2.5 text-sm"
            data-testid="identity-suspended"
          >
            <ng-icon name="lucidePause" class="mt-0.5 shrink-0" aria-hidden="true" />
            <div class="flex min-w-0 flex-col gap-1">
              <p class="text-foreground font-semibold" data-testid="identity-suspended-at">
                Suspended
                @if (si.suspendedAt; as at) {
                  <app-time [value]="at" />
                }
              </p>
              <p>
                The credentials it borrows act for nothing through it and its budget pays for
                nothing, so whatever uses it waits, until you resume it. Groups that borrow the same
                credentials go on.
              </p>
              @if (si.suspensionReason; as reason) {
                <p class="text-foreground break-words" data-testid="identity-suspension-reason">
                  <span class="text-muted-foreground">Reason:</span> {{ reason }}
                </p>
              }
            </div>
          </div>
        }

        <app-identity-summary
          [borrowed]="borrowedSummary()"
          [triggers]="triggerCount()"
          [budget]="budgetParts()"
          [budgetError]="budget.error() ? budgetError() : ''"
        />

        <div class="grid items-start gap-5 lg:grid-cols-[minmax(0,1fr)_20rem]">
          <app-identity-credentials
            [companyId]="companyId()!"
            [identity]="si"
            (summary)="borrowedSummary.set($event)"
          />

          <aside class="flex min-w-0 flex-col gap-5" aria-label="How it is used">
            <section
              class="bg-card flex flex-col gap-3 rounded-xl border p-4 sm:p-5"
              aria-labelledby="users-title"
              data-testid="identity-users"
            >
              <div class="flex items-center justify-between gap-2">
                <h2 id="users-title" class="text-base font-bold">Who may use it</h2>
                <button
                  hlmBtn
                  variant="ghost"
                  size="sm"
                  type="button"
                  class="-me-2"
                  data-testid="edit-users"
                  (click)="openUsers()"
                >
                  <ng-icon name="lucideUsers" aria-hidden="true" />
                  Change
                </button>
              </div>
              <p class="text-muted-foreground text-[0.8125rem] text-pretty">
                Members of these groups may pick it, the credentials it borrows and its budget for
                what they set up, such as triggers. The Owner always may.
              </p>
              @if (si.userGroups?.length) {
                <ul class="flex flex-wrap gap-2" data-testid="identity-users-list">
                  @for (group of si.userGroups; track group.id) {
                    <li
                      class="bg-muted inline-flex h-7 max-w-full items-center gap-1.5 rounded-full border px-3 text-[0.8125rem] font-semibold"
                    >
                      <ng-icon
                        name="lucideUsers"
                        size="14"
                        class="text-muted-foreground shrink-0"
                        aria-hidden="true"
                      /><span class="truncate" [title]="group.name">{{ group.name }}</span>
                    </li>
                  }
                </ul>
              } @else {
                <p class="text-sm font-medium" data-testid="identity-users-list">{{ users() }}</p>
              }
              <p
                class="text-muted-foreground flex items-start gap-2 border-t pt-3 text-xs text-pretty"
              >
                <ng-icon name="lucideInfo" size="15" class="mt-px shrink-0" aria-hidden="true" />
                <span
                  >This is not lending: to let members act with a credential in their own
                  conversations, lend it to their group on its system’s page.</span
                >
              </p>
            </section>

            <app-identity-triggers
              [companyId]="companyId()!"
              [principalId]="si.id"
              [name]="si.name"
              (count)="triggerCount.set($event)"
            />

            @for (bot of borrowedSummary()?.bots ?? []; track bot.id) {
              <section
                class="flex items-start gap-3 rounded-xl border border-dashed px-4 py-3.5"
                [attr.aria-labelledby]="'identity-bot-' + bot.id"
                data-testid="identity-bot"
              >
                <span
                  class="bg-muted text-muted-foreground flex size-8 shrink-0 items-center justify-center rounded-lg"
                  aria-hidden="true"
                >
                  <ng-icon [name]="kindIcon(bot)" size="16" />
                </span>
                <div class="flex min-w-0 flex-col gap-1">
                  <h2 [id]="'identity-bot-' + bot.id" class="text-sm font-semibold">
                    Borrows {{ bot.name }}’s bot
                  </h2>
                  <p class="text-muted-foreground text-xs text-pretty">
                    The bot is set up on {{ bot.name }}’s page and acts for no group or trigger.
                    {{ si.name }} can’t be deleted while it borrows it.
                  </p>
                  <a
                    class="text-primary w-fit text-[0.8125rem] font-semibold underline-offset-2 hover:underline"
                    [routerLink]="['/c', companyId(), 'systems', bot.id]"
                    [queryParams]="{ tab: 'bot' }"
                    >Open {{ bot.name }}</a
                  >
                </div>
              </section>
            }

            <section
              class="bg-card flex flex-col gap-2.5 rounded-xl border p-4 sm:p-5"
              aria-labelledby="budget-title"
              data-testid="identity-budget"
            >
              <h2 id="budget-title" class="flex items-center gap-2 text-base font-bold">
                <ng-icon name="lucideWallet" class="text-muted-foreground" aria-hidden="true" />
                Budget
              </h2>
              @if (budgetParts(); as b) {
                @if (!b.configured) {
                  <p class="text-sm" data-testid="identity-budget-line">
                    The company’s model gateway is not set up yet, so no budget pays for anything.
                  </p>
                } @else {
                  <p
                    class="flex flex-wrap items-baseline gap-x-1.5 tabular-nums"
                    data-testid="identity-budget-line"
                  >
                    @if (b.spent) {
                      <span class="font-mono text-2xl font-medium">{{ b.spent }}</span>
                      {{ ' ' }}<span class="text-muted-foreground text-[0.8125rem]">spent of</span>
                      {{ ' ' }}
                    }
                    <span class="text-[0.8125rem]"
                      ><span class="font-mono">{{ b.budget }}</span> per {{ b.period }}</span
                    >
                    @if (b.isDefault) {
                      {{ ' '
                      }}<span class="text-muted-foreground text-xs">(the company’s default)</span>
                    }
                  </p>
                  @if (b.ratio !== null) {
                    <div
                      class="bg-accent h-2 overflow-hidden rounded-full"
                      role="meter"
                      aria-label="Share of the budget spent"
                      aria-valuemin="0"
                      aria-valuemax="100"
                      [attr.aria-valuenow]="budgetPercent()"
                    >
                      <div
                        class="bg-primary h-full min-w-2 rounded-full"
                        [style.width.%]="budgetPercent()"
                      ></div>
                    </div>
                  }
                  @if (b.resets) {
                    <p class="text-muted-foreground text-xs">Resets {{ b.resets }}</p>
                  }
                }
              } @else if (budget.error()) {
                <p class="text-muted-foreground text-sm">{{ budgetError() }}</p>
              } @else {
                <p class="text-muted-foreground text-sm" role="status">Loading the budget…</p>
              }
              <p class="text-muted-foreground text-xs">
                It pays for the work done as {{ si.name }}. Budgets are set under
                <a
                  class="text-primary underline underline-offset-2"
                  [routerLink]="['/c', companyId(), 'model-access']"
                  >Model access</a
                >.
              </p>
            </section>
          </aside>
        </div>
      </div>

      <app-identity-dialog [(open)]="editOpen" [identity]="si" (saved)="onSaved($event)" />

      <hlm-dialog [state]="usersOpen() ? 'open' : 'closed'" (closed)="usersOpen.set(false)">
        <hlm-dialog-content
          *hlmDialogPortal="let ctx"
          class="max-h-[92svh] overflow-y-auto sm:max-w-md"
        >
          <hlm-dialog-header>
            <h2 hlmDialogTitle>Who may use {{ si.name }}</h2>
            <p hlmDialogDescription>
              The members of the groups you tick; the Owner always may, and nobody else, admins
              included, without a group.
            </p>
          </hlm-dialog-header>
          @if (usersError(); as message) {
            <div hlmAlert variant="destructive">
              <ng-icon name="lucideCircleAlert" aria-hidden="true" />
              <p hlmAlertDescription>{{ message }}</p>
            </div>
          }
          <fieldset class="flex flex-col gap-2" data-testid="users-picker">
            <legend class="sr-only">Groups</legend>
            @if (groups.isLoading()) {
              <p class="text-muted-foreground text-sm" role="status">Loading the groups…</p>
            }
            @for (group of groupList(); track group.id) {
              <label class="flex items-center gap-2.5 text-sm">
                <input
                  type="checkbox"
                  class="accent-primary size-4"
                  [checked]="picked().has(group.id)"
                  (change)="togglePicked(group.id)"
                />
                {{ group.name }}
              </label>
            } @empty {
              @if (!groups.isLoading()) {
                <p class="text-muted-foreground text-sm">The company has no groups yet.</p>
              }
            }
          </fieldset>
          <hlm-dialog-footer>
            <button hlmBtn variant="outline" type="button" hlmDialogClose>Cancel</button>
            <button
              hlmBtn
              type="button"
              data-testid="users-save"
              [disabled]="usersBusy()"
              (click)="saveUsers()"
            >
              @if (usersBusy()) {
                <hlm-spinner aria-hidden="true" />
              }
              Save
            </button>
          </hlm-dialog-footer>
        </hlm-dialog-content>
      </hlm-dialog>

      <hlm-alert-dialog
        [state]="suspendOpen() ? 'open' : 'closed'"
        (closed)="suspendOpen.set(false)"
      >
        <hlm-alert-dialog-content *hlmAlertDialogPortal="let ctx" data-testid="suspend-dialog">
          <hlm-alert-dialog-header>
            <h2 hlmAlertDialogTitle>Suspend {{ si.name }}?</h2>
            <p hlmAlertDialogDescription>
              Everything that acts through it or on its budget stops: the credentials it borrows act
              for nothing through it, and the turns running as it or on its budget are cancelled,
              until you resume it. Groups that borrow the same credentials go on.
            </p>
          </hlm-alert-dialog-header>
          <div hlmField>
            <label hlmFieldLabel for="suspend-reason">Reason</label>
            <input
              #reasonInput
              hlmInput
              id="suspend-reason"
              autocomplete="off"
              maxlength="500"
              placeholder="Optional, kept with it"
            />
          </div>
          @if (suspendError(); as message) {
            <div hlmAlert variant="destructive">
              <ng-icon name="lucideCircleAlert" aria-hidden="true" />
              <p hlmAlertDescription>{{ message }}</p>
            </div>
          }
          <hlm-alert-dialog-footer>
            <button hlmAlertDialogCancel [disabled]="statusBusy()">Cancel</button>
            <button
              hlmAlertDialogAction
              variant="destructive"
              data-testid="confirm-suspend"
              [disabled]="statusBusy()"
              (click)="suspend(reasonInput.value)"
            >
              @if (statusBusy()) {
                <hlm-spinner aria-hidden="true" />
              }
              Suspend
            </button>
          </hlm-alert-dialog-footer>
        </hlm-alert-dialog-content>
      </hlm-alert-dialog>

      <hlm-alert-dialog [state]="deleteOpen() ? 'open' : 'closed'" (closed)="deleteOpen.set(false)">
        <hlm-alert-dialog-content *hlmAlertDialogPortal="let ctx">
          <hlm-alert-dialog-header>
            <h2 hlmAlertDialogTitle>Delete {{ si.name }}?</h2>
            <p hlmAlertDialogDescription>
              The credentials it borrows are withdrawn from it and stay with their systems, lent to
              whoever else they are lent to. This cannot be undone.
            </p>
          </hlm-alert-dialog-header>
          @if (deleteError(); as message) {
            <div hlmAlert variant="destructive">
              <ng-icon name="lucideCircleAlert" aria-hidden="true" />
              <p hlmAlertDescription>{{ message }}</p>
            </div>
          }
          <hlm-alert-dialog-footer>
            <button hlmAlertDialogCancel [disabled]="deleteBusy()">Cancel</button>
            <button
              hlmAlertDialogAction
              variant="destructive"
              data-testid="confirm-delete-identity"
              [disabled]="deleteBusy()"
              (click)="confirmDelete()"
            >
              @if (deleteBusy()) {
                <hlm-spinner aria-hidden="true" />
              }
              Delete {{ si.name }}
            </button>
          </hlm-alert-dialog-footer>
        </hlm-alert-dialog-content>
      </hlm-alert-dialog>
    } @else {
      <div class="flex flex-col gap-4" aria-busy="true">
        <span class="sr-only" role="status">Loading…</span>
        <hlm-skeleton class="h-4 w-32" />
        <hlm-skeleton class="h-8 w-56 max-w-full" />
        <hlm-skeleton class="h-48 w-full rounded-xl" />
      </div>
    }
  `,
})
export class ServiceIdentityPage {
  private readonly api = inject(Api);
  private readonly router = inject(Router);
  private readonly notify = inject(Notify);
  private readonly current = inject(CurrentCompany);

  /** The route's `:principalId`: a service account is a principal. */
  readonly principalId = input.required<string>();

  protected readonly companyId = this.current.id;
  protected readonly forbidden = FORBIDDEN;
  protected readonly defaultDescription = DEFAULT_DESCRIPTION;
  /**
   * What it borrows: the page's summary and its note on a chat bot. Both reset when the route
   * names another service account, so nothing of the one before shows while it reads.
   */
  protected readonly borrowedSummary = linkedSignal<string, BorrowedSummary | null>({
    source: this.principalId,
    computation: () => null,
  });
  /** How many triggers use it, once they are read. */
  protected readonly triggerCount = linkedSignal<string, number | null>({
    source: this.principalId,
    computation: () => null,
  });
  private readonly key = computed(
    () => {
      const companyId = this.current.id();
      return companyId ? { companyId, serviceIdentityId: this.principalId() } : undefined;
    },
    { equal: sameParams },
  );
  protected readonly identityRes = resource({
    params: () => this.key(),
    loader: ({ params }) => this.api.invoke(getServiceIdentity, params),
  });
  protected readonly identity = linkedSignal<ServiceIdentity | undefined>(() => {
    const value = this.identityRes.hasValue() ? this.identityRes.value() : undefined;
    return value?.id === this.principalId() ? value : undefined;
  });
  protected readonly notFound = computed(() => toProblem(this.identityRes.error()).status === 404);
  protected readonly users = computed(() => {
    const identity = this.identity();
    return identity ? identityUsers(identity) : '';
  });

  protected readonly budget = resource({
    params: () => {
      const key = this.key();
      return key ? { companyId: key.companyId, principalId: key.serviceIdentityId } : undefined;
    },
    loader: ({ params }) => this.api.invoke(getPrincipalModelAccess, params),
  });
  protected readonly budgetParts = computed(() =>
    this.budget.hasValue() ? budgetView(this.budget.value()) : null,
  );
  protected readonly budgetPercent = computed(() =>
    Math.round((this.budgetParts()?.ratio ?? 0) * 100),
  );
  protected readonly budgetError = computed(() =>
    problemMessage(this.budget.error(), {
      GATEWAY_NOT_CONFIGURED: 'The company’s model gateway is not set up yet.',
    }),
  );

  protected kindIcon(system: SystemRef): string {
    return systemKindIcon(system.kind);
  }

  // --- Change -------------------------------------------------------------------------------

  protected readonly editOpen = signal(false);

  protected onSaved(identity: ServiceIdentity): void {
    this.identity.set(identity);
    this.notify.success(`${identity.name} is saved`);
  }

  // --- Who may use it -----------------------------------------------------------------------

  protected readonly usersOpen = signal(false);
  protected readonly usersBusy = signal(false);
  protected readonly usersError = signal<string | null>(null);
  protected readonly picked = signal<ReadonlySet<string>>(new Set());
  private readonly groupsKey = signal<{ companyId: string; at: number } | undefined>(undefined);
  protected readonly groups = resource({
    params: () => this.groupsKey(),
    loader: ({ params }) =>
      loadAll((cursor) =>
        this.api.invoke(listGroups, { companyId: params.companyId, limit: 200, cursor }),
      ),
  });
  protected readonly groupList = computed(() =>
    this.groups.hasValue() ? this.groups.value() : [],
  );

  protected openUsers(): void {
    const companyId = this.current.id();
    if (!companyId) return;
    this.picked.set(new Set((this.identity()?.userGroups ?? []).map((group) => group.id)));
    this.usersError.set(null);
    this.groupsKey.set({ companyId, at: Date.now() });
    this.usersOpen.set(true);
  }

  protected togglePicked(groupId: string): void {
    this.picked.update((set) => {
      const next = new Set(set);
      if (next.has(groupId)) next.delete(groupId);
      else next.add(groupId);
      return next;
    });
  }

  protected async saveUsers(): Promise<void> {
    const key = this.key();
    if (!key || this.usersBusy()) return;
    this.usersBusy.set(true);
    this.usersError.set(null);
    try {
      const updated = await this.api.invoke(replaceServiceIdentityUsers, {
        ...key,
        body: { groupIds: [...this.picked()] },
      });
      this.identity.set(updated);
      this.usersOpen.set(false);
      this.notify.success(`${updated.name} may be used by ${identityUsers(updated)}`);
    } catch (error) {
      this.usersError.set(problemMessage(error, FORBIDDEN));
    } finally {
      this.usersBusy.set(false);
    }
  }

  // --- Suspend and resume -------------------------------------------------------------------

  protected readonly suspendOpen = signal(false);
  protected readonly suspendError = signal<string | null>(null);
  protected readonly statusBusy = signal(false);

  protected openSuspend(): void {
    this.suspendError.set(null);
    this.suspendOpen.set(true);
  }

  protected async suspend(reason: string): Promise<void> {
    const key = this.key();
    if (!key || this.statusBusy()) return;
    this.statusBusy.set(true);
    this.suspendError.set(null);
    try {
      const updated = await this.api.invoke(suspendServiceIdentity, {
        ...key,
        body: reason.trim() ? { reason: reason.trim() } : {},
      });
      this.identity.set(updated);
      this.suspendOpen.set(false);
      this.notify.success(
        `${updated.name} is suspended`,
        'Nothing acts through it or on its budget now.',
      );
    } catch (error) {
      this.suspendError.set(problemMessage(error, FORBIDDEN));
    } finally {
      this.statusBusy.set(false);
    }
  }

  protected async resume(): Promise<void> {
    const key = this.key();
    if (!key || this.statusBusy()) return;
    this.statusBusy.set(true);
    try {
      const updated = await this.api.invoke(resumeServiceIdentity, key);
      this.identity.set(updated);
      this.notify.success(`${updated.name} is active again`);
    } catch (error) {
      this.notify.error(`Couldn't resume ${this.identity()?.name ?? 'it'}`, error, FORBIDDEN);
    } finally {
      this.statusBusy.set(false);
    }
  }

  // --- Delete -------------------------------------------------------------------------------

  protected readonly deleteOpen = signal(false);
  protected readonly deleteBusy = signal(false);
  protected readonly deleteError = signal<string | null>(null);

  protected openDelete(): void {
    this.deleteError.set(null);
    this.deleteOpen.set(true);
  }

  protected async confirmDelete(): Promise<void> {
    const key = this.key();
    const identity = this.identity();
    if (!key || !identity || this.deleteBusy()) return;
    this.deleteBusy.set(true);
    this.deleteError.set(null);
    try {
      await this.api.invoke(deleteServiceIdentity, key);
      this.deleteOpen.set(false);
      this.notify.success(`${identity.name} is deleted`);
      await this.router.navigate(['/c', key.companyId, 'service-identities']);
    } catch (error) {
      this.deleteError.set(problemMessage(error, FORBIDDEN));
    } finally {
      this.deleteBusy.set(false);
    }
  }
}
