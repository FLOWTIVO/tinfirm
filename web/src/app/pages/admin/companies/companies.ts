import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { NgIcon, provideIcons } from '@ng-icons/core';
import { lucideBuilding2 } from '@ng-icons/lucide';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmEmptyImports } from '@spartan-ng/helm/empty';
import { HlmSpinner } from '@spartan-ng/helm/spinner';
import { HlmTableImports } from '@spartan-ng/helm/table';
import { Api } from '../../../api/generated/api';
import { listCompanies } from '../../../api/generated/functions';
import type { Company } from '../../../api/generated/models';
import { LicenceState } from '../../../core/licence';
import { pagedResource } from '../../../core/paged-resource';
import { EditionBadge } from '../../../shared/edition-badge';
import { ErrorState } from '../../../shared/error-state';
import { ListSkeleton } from '../../../shared/list-skeleton';
import { PageHeader } from '../../../shared/page-header';
import { RoleBadge } from '../../../shared/role-badge';
import { Time } from '../../../shared/time';

const PAGE_SIZE = 100;

/**
 * Every company on the instance, for its admins. Companies are declared in the deployment's
 * configuration, each by its key (ADR-0075): the page lists them and creates none. Several
 * companies are Enterprise's (ADR-0077): without it the page says that the first one is licensed.
 */
@Component({
  selector: 'app-companies',
  imports: [
    RouterLink,
    NgIcon,
    HlmButton,
    HlmEmptyImports,
    HlmSpinner,
    HlmTableImports,
    EditionBadge,
    ErrorState,
    ListSkeleton,
    PageHeader,
    RoleBadge,
    Time,
  ],
  providers: [provideIcons({ lucideBuilding2 })],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './companies.html',
})
export class Companies {
  private readonly api = inject(Api);

  private readonly licence = inject(LicenceState);
  /** Several companies in one instance (Enterprise), and how many its key allows, if it caps them. */
  protected readonly multiCompany = computed(() => this.licence.has('MULTI_COMPANY'));
  protected readonly companiesLicensed = computed(
    () => this.licence.value()?.companiesLicensed ?? null,
  );

  protected readonly companies = pagedResource<Company, true>({
    params: () => true,
    load: (_, cursor) => this.api.invoke(listCompanies, { limit: PAGE_SIZE, cursor }),
  });
}
