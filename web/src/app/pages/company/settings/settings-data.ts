import { computed, inject, Injectable, resource } from '@angular/core';
import { Api } from '../../../api/generated/api';
import { getMemorySettings, listSecrets } from '../../../api/generated/functions';
import type { SecretMeta } from '../../../api/generated/models';
import { CurrentCompany } from '../../../core/current-company';
import { pagedResource } from '../../../core/paged-resource';
import { can } from '../../../core/permissions';

const SECRETS_PAGE_SIZE = 100;

/**
 * What the settings' sections and their index both read, loaded once per visit to the settings:
 * the secrets in the company's vault and the memory settings (owners and admins). The index says
 * each section's state from them; a section that changes one sets it here, and the index follows.
 * Provided by the settings' layout.
 */
@Injectable()
export class SettingsData {
  private readonly api = inject(Api);
  private readonly current = inject(CurrentCompany);

  readonly canListSecrets = computed(() => can.viewSecrets(this.current.access()));
  readonly canManageMemory = computed(() => can.manageModelAccess(this.current.access()));

  /** The model that tidies agents' memories (ADR-0065); idle for those who may not see it. */
  readonly memory = resource({
    params: () => (this.canManageMemory() ? this.current.id() : undefined),
    loader: ({ params: companyId }) => this.api.invoke(getMemorySettings, { companyId }),
  });

  readonly secrets = pagedResource<SecretMeta, string>({
    params: () => (this.canListSecrets() ? this.current.id() : undefined),
    load: (companyId, cursor) =>
      this.api.invoke(listSecrets, { companyId, limit: SECRETS_PAGE_SIZE, cursor }),
  });
}
