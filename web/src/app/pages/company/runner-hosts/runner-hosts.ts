import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { NgIcon, provideIcons } from '@ng-icons/core';
import {
  lucideCircleAlert,
  lucideInfo,
  lucidePlus,
  lucideServer,
  lucideTriangleAlert,
} from '@ng-icons/lucide';
import { HlmAlertImports } from '@spartan-ng/helm/alert';
import { HlmAlertDialogImports } from '@spartan-ng/helm/alert-dialog';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmEmptyImports } from '@spartan-ng/helm/empty';
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
import { can, isMember } from '../../../core/permissions';
import { pollWhileVisible } from '../../../core/polling';
import { ErrorState } from '../../../shared/error-state';
import { ListSkeleton } from '../../../shared/list-skeleton';
import { PageHeader } from '../../../shared/page-header';
import { PoolScheduleDialog } from '../../../shared/runner-hosts/pool-schedule-dialog';
import { RegisterRunnerHostDialog } from '../../../shared/runner-hosts/register-runner-host-dialog';
import { RunnerHostCard } from '../../../shared/runner-hosts/runner-host-card';
import { GATEWAY_RUNNER_VERSION, takesTurns } from '../../../shared/runner-hosts/runner-host-view';

const REMOVE_ERRORS = {
  'status:403': 'Only owners and admins remove company runner hosts.',
  'status:404': 'This runner host was removed already.',
};

const CAPACITY_ERRORS = {
  'status:403': 'Only owners and admins change a company runner host.',
  'status:404': 'This runner host no longer exists. Reload the page.',
  VALIDATION_FAILED: 'Choose a number from 1 to 1000.',
};

/**
 * The company's runner hosts (plan §5), the admin's page: each company host as the shared card
 * (online, offline and since when, draining, its version and whether it may run turns paid by a
 * budget, its slots and the number of runs set in Tinfirm, pending until the host confirms it);
 * register one with the company's scope, its token shown once; set the schedule of the ready
 * sandboxes of a host with a VM per run (ADR-0099); remove one, refused while it runs
 * (`RUNNER_HOST_HAS_ACTIVE_RUNS`). A member's own hosts are on Accounts. It follows the hosts every
 * 10 seconds while visible.
 */
@Component({
  selector: 'app-runner-hosts',
  imports: [
    NgIcon,
    RouterLink,
    HlmAlertImports,
    HlmAlertDialogImports,
    HlmButton,
    HlmEmptyImports,
    HlmSpinner,
    ErrorState,
    ListSkeleton,
    PageHeader,
    PoolScheduleDialog,
    RegisterRunnerHostDialog,
    RunnerHostCard,
  ],
  providers: [
    provideIcons({
      lucideCircleAlert,
      lucideInfo,
      lucidePlus,
      lucideServer,
      lucideTriangleAlert,
    }),
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './runner-hosts.html',
})
export class RunnerHosts {
  private readonly api = inject(Api);
  private readonly notify = inject(Notify);
  protected readonly current = inject(CurrentCompany);

  protected readonly gatewayVersion = GATEWAY_RUNNER_VERSION;
  protected readonly canManage = computed(() => can.manageRunnerHosts(this.current.access()));
  /** An admin who is also a member has hosts of their own, on Accounts. */
  protected readonly hasOwnHosts = computed(() => isMember(this.current.access()));

  /** The company's hosts and the reader's own; this page shows the company's. */
  protected readonly hosts = pagedResource<RunnerHost, string>({
    params: () => this.current.id(),
    load: (companyId, cursor) =>
      this.api.invoke(listRunnerHosts, { companyId, limit: 100, cursor }),
  });
  protected readonly companyHosts = computed(() =>
    this.hosts.items().filter((host) => host.scope === 'COMPANY'),
  );
  /** Hosts are registered, and none takes a turn paid by a budget now. */
  protected readonly noneTakesTurns = computed(
    () => this.companyHosts().length > 0 && !this.companyHosts().some(takesTurns),
  );

  protected readonly registerOpen = signal(false);

  constructor() {
    // Online state, last seen, active runs and confirmed limits change on their own.
    pollWhileVisible(() => {
      if (!this.hosts.firstLoad() && !this.hosts.loadingMore()) this.hosts.refresh();
    }, 10_000);
    // A queued turn links here, to the host that holds it.
    focusFragmentWhenReady(() => !this.hosts.firstLoad() && this.hosts.items().length > 0);
  }

  protected onRegistered(host: RunnerHost): void {
    this.hosts.refresh();
    this.notify.success(`${host.name} registered`, 'It shows online once it connects.');
  }

  // --- Capacity (archived v1 CONVENTIONS §14.8) -----------------------------------------------

  /** Values picked here that the server has not answered yet; a failure reverts to the server's. */
  private readonly drafts = signal<ReadonlyMap<string, number | null>>(new Map());
  protected readonly capacityErrors = signal<ReadonlyMap<string, string>>(new Map());
  /** Saves in flight, and the newest value picked meanwhile (sent when the current save ends). */
  private readonly inFlight = new Set<string>();
  private readonly queued = new Map<string, () => Promise<void>>();

  protected desired(host: RunnerHost): number | null {
    const drafts = this.drafts();
    return drafts.has(host.id)
      ? (drafts.get(host.id) ?? null)
      : (host.desiredMaxConcurrentRuns ?? null);
  }

  protected setCapacity(host: RunnerHost, value: number | null): void {
    const companyId = this.current.id();
    if (!companyId) return;
    const body: RunnerHostUpdateRequest =
      value === null
        ? { clearDesiredMaxConcurrentRuns: true }
        : { desiredMaxConcurrentRuns: value };
    const key = host.id;
    this.drafts.update((m) => new Map(m).set(key, value));
    this.capacityErrors.update((m) => without(m, key));

    const run = async (): Promise<void> => {
      this.inFlight.add(key);
      try {
        const updated = await this.api.invoke(updateRunnerHost, {
          companyId,
          runnerHostId: host.id,
          body,
        });
        this.hosts.update((items) => items.map((h) => (h.id === updated.id ? updated : h)));
        if (!this.queued.has(key)) this.drafts.update((m) => without(m, key));
      } catch (error) {
        // A newer pick is on its way; only its outcome matters.
        if (!this.queued.has(key)) {
          this.drafts.update((m) => without(m, key));
          this.capacityErrors.update((m) =>
            new Map(m).set(key, problemMessage(error, CAPACITY_ERRORS)),
          );
        }
      } finally {
        this.inFlight.delete(key);
        const next = this.queued.get(key);
        if (next) {
          this.queued.delete(key);
          void next();
        }
      }
    };

    if (this.inFlight.has(key)) this.queued.set(key, run);
    else void run();
  }

  // --- Ready sandboxes (ADR-0099) -------------------------------------------------------------

  protected readonly poolHost = signal<RunnerHost | null>(null);
  protected readonly poolOpen = signal(false);

  protected editPool(host: RunnerHost): void {
    this.poolHost.set(host);
    this.poolOpen.set(true);
  }

  protected onPoolSaved(updated: RunnerHost): void {
    this.hosts.update((items) => items.map((h) => (h.id === updated.id ? updated : h)));
    this.notify.success(
      updated.pool?.settings
        ? `Schedule of ${updated.name} saved`
        : `No sandbox waits on ${updated.name}`,
      updated.online
        ? 'The host follows it within a minute.'
        : 'The host follows it when it connects.',
    );
  }

  // --- Remove ---------------------------------------------------------------------------------

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
      this.notify.success(`${host.name} removed`, 'Its token no longer works.');
    } catch (error) {
      this.removeError.set(problemMessage(error, REMOVE_ERRORS));
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
