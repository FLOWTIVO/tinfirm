import { computed, inject, Injectable, resource } from '@angular/core';
import { Api } from '../api/generated/api';
import { getKillSwitches } from '../api/generated/functions';
import type { KillSwitches } from '../api/generated/models';
import { CurrentCompany } from './current-company';

/**
 * The company's kill switches as `GET /kill-switches` answers them (the package 6 contract, §5.4):
 * every page's banner while its runs are stopped, and the caller's own suspension. The switches'
 * page reloads it after each switch it pulls, so the banner follows at once.
 */
@Injectable({ providedIn: 'root' })
export class KillSwitchState {
  private readonly api = inject(Api);
  private readonly current = inject(CurrentCompany);

  readonly switches = resource({
    params: () => this.current.id(),
    loader: ({ params: companyId }) => this.api.invoke(getKillSwitches, { companyId }),
  });

  private readonly loaded = computed<KillSwitches | null>(() =>
    this.switches.hasValue() ? (this.switches.value() ?? null) : null,
  );

  /** The company's stop, while its runs are stopped. */
  readonly runsStop = computed(() => this.loaded()?.runsStop ?? null);
  /** The caller's own suspension in the company. */
  readonly mySuspension = computed(() => this.loaded()?.mySuspension ?? null);

  reload(): void {
    this.switches.reload();
  }

  /** Replaces what is known with what a switch's call answered, without asking again. */
  set(value: KillSwitches): void {
    this.switches.set(value);
  }
}
