import { computed, inject, Injectable, resource } from '@angular/core';
import { Api } from '../api/generated/api';
import { getInbox } from '../api/generated/functions';
import type { InboxCounts as Counts } from '../api/generated/models';
import { CurrentCompany } from './current-company';

/**
 * What waits on the caller, from `getInbox`'s `counts`, for the sidebar's badge (plan §3). The
 * badge counts what a person must act on: open decisions and cards (a Connect card or a choice of
 * grant, and for admins a system's credential to sign in again). Failed and waiting turns
 * stay out of it: they are neutral (DESIGN.md, the scarce orange rule). The shell polls
 * {@link refresh}; the inbox page calls it after it acted.
 */
@Injectable({ providedIn: 'root' })
export class InboxCounts {
  private readonly api = inject(Api);
  private readonly current = inject(CurrentCompany);

  private readonly inbox = resource({
    params: () => this.current.navTargetId(),
    loader: ({ params: companyId }) => this.api.invoke(getInbox, { companyId }),
  });

  /** The counts of the sidebar's company, while known. */
  readonly counts = computed<Counts | undefined>(() =>
    this.inbox.hasValue() ? this.inbox.value()?.counts : undefined,
  );

  /** Open decisions and cards: what the badge shows; 0 while unknown. */
  readonly needsYou = computed(() => {
    const counts = this.counts();
    return counts ? counts.decisions + counts.bindings + counts.accounts : 0;
  });

  refresh(): void {
    if (this.inbox.isLoading()) return;
    this.inbox.reload();
  }
}
