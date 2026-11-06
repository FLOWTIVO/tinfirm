import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { NgIcon, provideIcons } from '@ng-icons/core';
import { lucideCircleAlert, lucideInfo, lucideLaptop, lucidePlus } from '@ng-icons/lucide';
import { HlmAlertImports } from '@spartan-ng/helm/alert';
import { HlmAlertDialogImports } from '@spartan-ng/helm/alert-dialog';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmSpinner } from '@spartan-ng/helm/spinner';
import { Api } from '../../../api/generated/api';
import {
  deleteRunnerHost,
  listRunnerHosts,
  updateRunnerHost,
} from '../../../api/generated/functions';
import type { RunnerHost, RunnerHostUpdateRequest } from '../../../api/generated/models';
import { problemMessage } from '../../../api/problem';
import { CurrentCompany } from '../../../core/current-company';
import { focusFragmentWhenReady } from '../../../core/fragment-focus';
import { Notify } from '../../../core/notify';
import { pagedResource } from '../../../core/paged-resource';
import { pollWhileVisible } from '../../../core/polling';
import { ErrorState } from '../../../shared/error-state';
import { ListSkeleton } from '../../../shared/list-skeleton';
import { RegisterRunnerHostDialog } from '../../../shared/runner-hosts/register-runner-host-dialog';
import { RunnerHostCard } from '../../../shared/runner-hosts/runner-host-card';

const CAPACITY_ERRORS = {
  'status:404': 'This runner host no longer exists. Reload the page.',
  VALIDATION_FAILED: 'Choose a number from 1 to 16.',
};

/**
 * Accounts › Runner hosts (plan §5): the reader's own, personal runner hosts. Each runs only their
 * own conversations, paid by the subscriptions it reports, in every company the reader belongs
 * to (ADR-0092): registered here, it is theirs everywhere, with the same slots and subscriptions.
 * They register one (its token shown once, with the commands to set it up), set how many runs it
 * takes at a time, and remove it (`409 RUNNER_HOST_HAS_ACTIVE_RUNS` while it runs, in any
 * company). The list follows the hosts every 10 s while the page is visible. The company's hosts
 * are on Runner hosts, for owners and admins.
 */
@Component({
  selector: 'app-accounts-hosts',
  imports: [
    NgIcon,
    HlmAlertImports,
    HlmAlertDialogImports,
    HlmButton,
    HlmSpinner,
    ErrorState,
    ListSkeleton,
    RegisterRunnerHostDialog,
    RunnerHostCard,
  ],
  providers: [provideIcons({ lucideCircleAlert, lucideInfo, lucideLaptop, lucidePlus })],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'flex flex-col gap-5' },
  template: `
    <div class="flex flex-col gap-3 sm:flex-row sm:items-end sm:justify-between">
      <div class="flex min-w-0 flex-col gap-0.5">
        <h2 id="hosts-heading" class="text-base font-bold">Your runner hosts</h2>
        <p class="text-muted-foreground text-sm text-pretty">
          Machines of yours that run your own conversations, paid by your subscriptions on them.
          Nobody else’s work runs there. A host of yours serves you in every company you belong
          to, with the same slots and subscriptions.
        </p>
      </div>
      <button
        hlmBtn
        size="sm"
        type="button"
        class="sm:shrink-0"
        data-testid="open-register-host"
        [variant]="!hosts.firstLoad() && mine().length === 0 ? 'default' : 'outline'"
        (click)="registerOpen.set(true)"
      >
        <ng-icon name="lucidePlus" aria-hidden="true" />
        Register a runner host
      </button>
    </div>

    @if (hosts.firstLoad()) {
      <app-list-skeleton [rows]="2" />
    } @else if (hosts.failed()) {
      <app-error-state
        [error]="hosts.error()"
        heading="Couldn't load your runner hosts"
        (retry)="hosts.retry()"
      />
    } @else if (mine().length === 0) {
      <div
        class="bg-card text-muted-foreground flex items-start gap-3 rounded-xl border border-dashed p-5 text-sm"
        data-testid="hosts-empty"
      >
        <ng-icon class="mt-px shrink-0" name="lucideLaptop" size="18" aria-hidden="true" />
        <div class="flex min-w-0 flex-col gap-1">
          <p class="text-foreground font-semibold">No runner host of yours yet</p>
          <p class="text-pretty">
            Register a machine with Docker and add your Claude or ChatGPT subscription on it. Your
            conversations can then run there, paid by the subscription instead of your budget.
          </p>
        </div>
      </div>
    } @else {
      <ul class="flex flex-col gap-4" aria-labelledby="hosts-heading">
        @for (host of mine(); track host.id) {
          <li>
            <app-runner-host-card
              [host]="host"
              [editable]="true"
              [desired]="desired(host)"
              [capacityError]="capacityErrors().get(host.id) ?? null"
              (capacityChange)="setCapacity(host, $event)"
              (remove)="askRemove(host)"
            />
          </li>
        }
      </ul>
      @if (hosts.hasMore()) {
        <div class="flex justify-center">
          <button
            hlmBtn
            variant="outline"
            size="sm"
            type="button"
            [disabled]="hosts.loadingMore()"
            (click)="hosts.loadMore()"
          >
            Load more
          </button>
        </div>
      }
      <p class="text-muted-foreground flex items-start gap-2 text-xs text-pretty">
        <ng-icon class="mt-0.5 shrink-0" name="lucideInfo" aria-hidden="true" />
        <span>
          The machine’s limit is a ceiling you set on the host with
          <code class="font-mono">--max-concurrent-runs</code>. Tinfirm can run fewer, never more; a
          change applies without a restart and never stops a running run.
        </span>
      </p>
    }

    @if (current.id(); as companyId) {
      <app-register-runner-host-dialog
        scope="PERSONAL"
        [(open)]="registerOpen"
        [companyId]="companyId"
        (registered)="hosts.refresh()"
      />
    }

    <hlm-alert-dialog [state]="removeOpen() ? 'open' : 'closed'" (closed)="removeOpen.set(false)">
      <hlm-alert-dialog-content *hlmAlertDialogPortal="let ctx">
        <hlm-alert-dialog-header>
          <h2 hlmAlertDialogTitle>Remove {{ removing()?.name }}?</h2>
          <p hlmAlertDialogDescription>
            Its token stops working at once, in every company you belong to, and your conversations
            paid by its subscriptions wait until another host of yours can run them. You can register
            it again later.
          </p>
        </hlm-alert-dialog-header>
        @if (removeError(); as message) {
          <div hlmAlert variant="destructive" data-testid="remove-host-error">
            <ng-icon name="lucideCircleAlert" aria-hidden="true" />
            <p hlmAlertDescription>{{ message }}</p>
          </div>
        }
        <hlm-alert-dialog-footer>
          <button hlmAlertDialogCancel [disabled]="removeBusy()">Cancel</button>
          <button
            hlmAlertDialogAction
            variant="destructive"
            data-testid="confirm-remove-host"
            [disabled]="removeBusy()"
            (click)="confirmRemove()"
          >
            @if (removeBusy()) {
              <hlm-spinner aria-hidden="true" />
            }
            Remove runner host
          </button>
        </hlm-alert-dialog-footer>
      </hlm-alert-dialog-content>
    </hlm-alert-dialog>
  `,
})
export class AccountsHosts {
  private readonly api = inject(Api);
  private readonly notify = inject(Notify);
  protected readonly current = inject(CurrentCompany);

  /** The reader's hosts and, for OWNER and ADMIN, the company's; this tab shows the personal ones. */
  protected readonly hosts = pagedResource<RunnerHost, string>({
    params: () => this.current.id(),
    load: (companyId, cursor) =>
      this.api.invoke(listRunnerHosts, { companyId, limit: 100, cursor }),
  });
  protected readonly mine = computed(() =>
    this.hosts.items().filter((host) => host.scope === 'PERSONAL'),
  );

  protected readonly registerOpen = signal(false);

  constructor() {
    // Online, last seen, runs in use and a confirmed capacity change on their own.
    pollWhileVisible(() => {
      if (!this.hosts.firstLoad() && !this.hosts.loadingMore()) this.hosts.refresh();
    }, 10_000);
    // A queued run links here, to the host that holds it (`#host-…`).
    focusFragmentWhenReady(() => !this.hosts.firstLoad() && this.mine().length > 0);
  }

  // --- Capacity --------------------------------------------------------------------------------

  /** A value picked and not answered yet, by host: shown at once, dropped when the save ends. */
  private readonly drafts = signal<ReadonlyMap<string, number | null>>(new Map());
  protected readonly capacityErrors = signal<ReadonlyMap<string, string>>(new Map());
  /** The newest value picked while a save of the same host is on its way. */
  private readonly queued = new Map<string, number | null>();
  private readonly inFlight = new Set<string>();

  protected desired(host: RunnerHost): number | null {
    const drafts = this.drafts();
    return drafts.has(host.id)
      ? (drafts.get(host.id) ?? null)
      : (host.desiredMaxConcurrentRuns ?? null);
  }

  protected setCapacity(host: RunnerHost, value: number | null): void {
    this.drafts.update((m) => new Map(m).set(host.id, value));
    this.capacityErrors.update((m) => without(m, host.id));
    if (this.inFlight.has(host.id)) {
      this.queued.set(host.id, value);
      return;
    }
    void this.saveCapacity(host.id, value);
  }

  private async saveCapacity(hostId: string, value: number | null): Promise<void> {
    const companyId = this.current.id();
    if (!companyId) return;
    const body: RunnerHostUpdateRequest =
      value === null
        ? { clearDesiredMaxConcurrentRuns: true }
        : { desiredMaxConcurrentRuns: value };
    this.inFlight.add(hostId);
    try {
      const updated = await this.api.invoke(updateRunnerHost, {
        companyId,
        runnerHostId: hostId,
        body,
      });
      if (!this.queued.has(hostId)) {
        this.hosts.update((items) => items.map((h) => (h.id === updated.id ? updated : h)));
        this.drafts.update((m) => without(m, hostId));
      }
    } catch (error) {
      if (!this.queued.has(hostId)) {
        this.drafts.update((m) => without(m, hostId));
        this.capacityErrors.update((m) =>
          new Map(m).set(hostId, problemMessage(error, CAPACITY_ERRORS)),
        );
      }
    } finally {
      this.inFlight.delete(hostId);
      if (this.queued.has(hostId)) {
        const next = this.queued.get(hostId) ?? null;
        this.queued.delete(hostId);
        void this.saveCapacity(hostId, next);
      }
    }
  }

  // --- Remove ----------------------------------------------------------------------------------

  protected readonly removing = signal<RunnerHost | null>(null);
  protected readonly removeOpen = signal(false);
  protected readonly removeBusy = signal(false);
  protected readonly removeError = signal<string | null>(null);

  protected askRemove(host: RunnerHost): void {
    this.removing.set(host);
    this.removeError.set(null);
    this.removeOpen.set(true);
  }

  protected async confirmRemove(): Promise<void> {
    const companyId = this.current.id();
    const host = this.removing();
    if (!companyId || !host || this.removeBusy()) return;
    this.removeBusy.set(true);
    this.removeError.set(null);
    try {
      await this.api.invoke(deleteRunnerHost, { companyId, runnerHostId: host.id });
      this.removeOpen.set(false);
      this.hosts.update((items) => items.filter((h) => h.id !== host.id));
      this.notify.success(`${host.name} is removed`, 'Its token no longer works.');
    } catch (error) {
      this.removeError.set(problemMessage(error));
    } finally {
      this.removeBusy.set(false);
    }
  }
}

function without<V>(map: ReadonlyMap<string, V>, key: string): ReadonlyMap<string, V> {
  if (!map.has(key)) return map;
  const copy = new Map(map);
  copy.delete(key);
  return copy;
}
