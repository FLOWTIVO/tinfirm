import { computed, inject, Injectable, resource } from '@angular/core';
import { Api } from '../api/generated/api';
import { getMyModelAccess } from '../api/generated/functions';
import type { ModelAccessView } from '../api/generated/models';
import { CurrentCompany } from './current-company';

/**
 * The caller's principal in the current company, and their model access (their budget and
 * subscriptions). The API has no `Company.myPrincipalId` yet (plan §9, gap 2), so it is read from
 * `getMyModelAccess().principalId`. Only a member has one: an instance admin who is not a member
 * gets none, and nothing is asked. Read once per company, when a page first injects it.
 */
@Injectable({ providedIn: 'root' })
export class CallerPrincipal {
  private readonly api = inject(Api);
  private readonly current = inject(CurrentCompany);

  private readonly access = resource({
    params: () => {
      const companyId = this.current.id();
      return companyId && this.current.access().role ? companyId : undefined;
    },
    loader: ({ params: companyId }) => this.api.invoke(getMyModelAccess, { companyId }),
  });

  /** The caller's model access in the current company, while known. */
  readonly modelAccess = computed<ModelAccessView | null>(() =>
    this.access.hasValue() ? (this.access.value() ?? null) : null,
  );

  /** The caller's principal in the current company, while known; null for a non-member. */
  readonly id = computed(() => this.modelAccess()?.principalId ?? null);

  /** Reads the model access again, e.g. after the budget changed. */
  reload(): void {
    this.access.reload();
  }
}
