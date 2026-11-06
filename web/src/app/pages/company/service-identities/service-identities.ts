import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { NgIcon, provideIcons } from '@ng-icons/core';
import { lucideChevronRight, lucideIdCard, lucidePlus } from '@ng-icons/lucide';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmEmptyImports } from '@spartan-ng/helm/empty';
import { HlmSpinner } from '@spartan-ng/helm/spinner';
import { Api } from '../../../api/generated/api';
import { listServiceIdentities } from '../../../api/generated/functions';
import type { ServiceIdentity } from '../../../api/generated/models';
import { CurrentCompany } from '../../../core/current-company';
import { Notify } from '../../../core/notify';
import { pagedResource } from '../../../core/paged-resource';
import { ErrorState } from '../../../shared/error-state';
import { ListSkeleton } from '../../../shared/list-skeleton';
import { PageHeader } from '../../../shared/page-header';
import { IdentityDialog } from './identity-dialog';
import { identityUsers } from './identity-labels';
import { IdentityStatusChip } from './identity-status-chip';

const PAGE_SIZE = 200;

/**
 * The company's service accounts, as the web app calls service identities (the web plan §3 and §5;
 * ADR-0072): accounts of the company rather than a person, which act with the credentials systems
 * lend them and pay with their own budget, and who may use each. Each opens its page. Lending a
 * credential is done on each system's page, under Credentials.
 */
@Component({
  selector: 'app-service-identities',
  imports: [
    RouterLink,
    NgIcon,
    HlmButton,
    HlmEmptyImports,
    HlmSpinner,
    ErrorState,
    IdentityDialog,
    IdentityStatusChip,
    ListSkeleton,
    PageHeader,
  ],
  providers: [provideIcons({ lucideChevronRight, lucideIdCard, lucidePlus })],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'flex flex-col gap-5' },
  template: `
    <app-page-header
      title="Service accounts"
      description="Accounts of the company rather than a person: they act with the credentials systems lend them, in the triggers of whoever may use them, and pay with their own budget. Credentials are lent on each system’s page."
    >
      <button hlmBtn type="button" data-testid="new-identity" (click)="dialogOpen.set(true)">
        <ng-icon name="lucidePlus" aria-hidden="true" />
        New service account
      </button>
    </app-page-header>

    @if (identities.firstLoad()) {
      <app-list-skeleton [rows]="3" />
    } @else if (identities.failed()) {
      <app-error-state
        [error]="identities.error()"
        heading="Couldn't load the service accounts"
        (retry)="identities.retry()"
      />
    } @else if (identities.empty()) {
      <div hlmEmpty class="bg-card rounded-xl border border-dashed" data-testid="identities-empty">
        <div hlmEmptyHeader>
          <div hlmEmptyMedia variant="icon">
            <ng-icon name="lucideIdCard" aria-hidden="true" />
          </div>
          <div hlmEmptyTitle>No service accounts yet</div>
          <p hlmEmptyDescription>
            Create one, lend it a credential on the page of each system it acts on, then let groups
            use it for their triggers.
          </p>
        </div>
      </div>
    } @else {
      <ul class="bg-card divide-y rounded-xl border" aria-label="Service accounts">
        @for (identity of identities.items(); track identity.id) {
          <li>
            <a
              class="hover:bg-accent/60 focus-visible:ring-ring/50 flex items-center gap-3 rounded-xl px-4 py-3 outline-none focus-visible:ring-3"
              [routerLink]="['/c', companyId(), 'service-identities', identity.id]"
              [attr.data-testid]="'identity-row-' + identity.name"
            >
              <span class="flex min-w-0 flex-1 flex-col gap-0.5">
                <span class="flex min-w-0 flex-wrap items-center gap-2">
                  <span class="truncate font-semibold">{{ identity.name }}</span>
                  <app-identity-status-chip [status]="identity.status" />
                </span>
                @if (identity.description) {
                  <span class="text-muted-foreground truncate text-xs">{{
                    identity.description
                  }}</span>
                }
                <span class="text-muted-foreground text-xs">Used by {{ usersOf(identity) }}</span>
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
      @if (identities.hasMore()) {
        <button
          hlmBtn
          variant="outline"
          size="sm"
          type="button"
          class="self-center"
          [disabled]="identities.loadingMore()"
          (click)="identities.loadMore()"
        >
          @if (identities.loadingMore()) {
            <hlm-spinner aria-hidden="true" />
          }
          Load more
        </button>
      }
    }

    <app-identity-dialog [(open)]="dialogOpen" (saved)="onCreated($event)" />
  `,
})
export class ServiceIdentities {
  private readonly api = inject(Api);
  private readonly router = inject(Router);
  private readonly notify = inject(Notify);
  private readonly current = inject(CurrentCompany);

  protected readonly companyId = this.current.id;
  protected readonly dialogOpen = signal(false);

  protected readonly identities = pagedResource<ServiceIdentity, string>({
    params: () => this.current.id(),
    load: (companyId, cursor) =>
      this.api.invoke(listServiceIdentities, { companyId, limit: PAGE_SIZE, cursor }),
  });

  protected usersOf(identity: ServiceIdentity): string {
    return identityUsers(identity);
  }

  protected onCreated(identity: ServiceIdentity): void {
    this.notify.success(
      `${identity.name} is created`,
      'Lend it a credential on the page of each system it acts on.',
    );
    void this.router.navigate(['/c', this.companyId(), 'service-identities', identity.id]);
  }
}
