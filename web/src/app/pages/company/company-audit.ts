import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  input,
  resource,
  signal,
  viewChild,
} from '@angular/core';
import { Router } from '@angular/router';
import { NgIcon, provideIcons } from '@ng-icons/core';
import { lucideShieldCheck } from '@ng-icons/lucide';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmSpinner } from '@spartan-ng/helm/spinner';
import { Api } from '../../api/generated/api';
import {
  listMembers,
  listServiceIdentities,
  verifyCompanyAuditChain,
} from '../../api/generated/functions';
import { problemMessage } from '../../api/problem';
import { loadAll } from '../../core/company-data';
import { CurrentCompany } from '../../core/current-company';
import { LicenceState } from '../../core/licence';
import { can } from '../../core/permissions';
import type { PrincipalKind } from '../../shared/audit-event-view';
import { AuditLog, type AuditQuickFilter, type AuditScope } from '../../shared/audit-log';
import {
  AuditVerificationResult,
  type AuditVerificationOutcome,
} from '../../shared/audit-verification-result';
import { EditionBadge } from '../../shared/edition-badge';
import { daysUntil } from '../../shared/licence-labels';
import { PageHeader } from '../../shared/page-header';
import { AuditChecks } from './audit/audit-checks';
import { AuditExport } from './audit/audit-export';
import { AuditFilterBar } from './audit/audit-filter-bar';
import {
  activeFilterCount,
  type AuditFilters,
  auditQuery,
  filtersFromQuery,
  filtersToQuery,
  sameFilters,
} from './audit/audit-filters';

interface VerificationState extends AuditVerificationOutcome {
  readonly companyId: string;
}

/**
 * The company's audit log (plan §5): Verify chain and Export in the header; one status line for
 * the chain (the latest daily check, its head, and the past checks in place, with the outcome of a
 * verification under it); then the events in one sheet, led by the filters of the API (the
 * package 6 contract, §5.8; CONVENTIONS §15.11), kept in the URL so that a filtered log can be
 * linked to. The server names each event's target as the reader may see it; the page loads the
 * members and service accounts only for its pickers. Retention has its own page.
 */
@Component({
  selector: 'app-company-audit',
  imports: [
    NgIcon,
    HlmButton,
    HlmSpinner,
    AuditChecks,
    AuditExport,
    AuditFilterBar,
    AuditLog,
    AuditVerificationResult,
    EditionBadge,
    PageHeader,
  ],
  providers: [provideIcons({ lucideShieldCheck })],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="flex flex-col gap-4">
      <app-page-header
        title="Audit log"
        description="Every change in this company, newest first. Each event is chained to the one before it."
      >
        @if (canView() && tools()) {
          <button
            hlmBtn
            variant="outline"
            type="button"
            class="max-sm:h-11 max-sm:flex-1"
            data-testid="verify-chain"
            [disabled]="verifying()"
            (click)="verify()"
          >
            @if (verifying()) {
              <hlm-spinner aria-hidden="true" />
            } @else {
              <ng-icon name="lucideShieldCheck" aria-hidden="true" />
            }
            Verify chain
          </button>
        }
        @if (canView() && exportable()) {
          <app-audit-export class="max-sm:flex-1" (exported)="log()?.refresh()" />
        }
      </app-page-header>

      @if (canView() && !tools()) {
        <p
          class="text-muted-foreground flex flex-wrap items-center gap-x-2 gap-y-1 text-sm text-pretty"
          data-testid="audit-community"
        >
          <app-edition-badge feature="AUDIT_TOOLS" />
          <span class="min-w-0 flex-1"
            >Every change is still recorded and chained.
            @if (!fullHistory()) {
              Community shows the last 90 days.
            }
            Filters, chain verification and the daily checks need a Pro licence{{
              exportable() ? '; the export stays while the licence’s grace for it lasts' : ''
            }}.</span
          >
        </p>
      }

      @if (canView() && tools()) {
        <app-audit-checks>
          <div aria-live="polite">
            <app-audit-verification-result variant="row" [outcome]="verification()" />
          </div>
        </app-audit-checks>
      }

      <app-audit-log
        [scope]="scope()"
        [query]="tools() ? query() : {}"
        [filtered]="tools() && filtered()"
        [filterable]="canView() && tools()"
        [principalKinds]="principalKinds()"
        (filterBy)="narrow($event)"
        (clear)="applyFilters({})"
      >
        @if (canView() && tools()) {
          <app-audit-filter-bar
            [filters]="filters()"
            [people]="people()"
            [principals]="principals()"
            [names]="names()"
            (apply)="applyFilters($event)"
          />
        }
      </app-audit-log>
    </div>
  `,
})
export class CompanyAudit {
  private readonly api = inject(Api);
  private readonly router = inject(Router);
  private readonly current = inject(CurrentCompany);

  protected readonly log = viewChild(AuditLog);

  // The filters, from the URL's query (component input binding).
  readonly action = input<string>();
  readonly actorType = input<string>();
  readonly actorId = input<string>();
  readonly targetType = input<string>();
  readonly targetId = input<string>();
  readonly when = input<string>();
  readonly from = input<string>();
  readonly to = input<string>();
  readonly conversationId = input<string>();
  readonly conversationKey = input<string>();
  readonly principalId = input<string>();

  protected readonly filters = computed<AuditFilters>(
    () =>
      filtersFromQuery({
        action: this.action(),
        actorType: this.actorType(),
        actorId: this.actorId(),
        targetType: this.targetType(),
        targetId: this.targetId(),
        when: this.when(),
        from: this.from(),
        to: this.to(),
        conversationId: this.conversationId(),
        conversationKey: this.conversationKey(),
        principalId: this.principalId(),
      }),
    { equal: sameFilters },
  );
  /** A time relative to now ("Last hour") starts when the filters change, then holds. */
  protected readonly query = computed(() => auditQuery(this.filters()), {
    equal: (a, b) => JSON.stringify(a) === JSON.stringify(b),
  });
  protected readonly filtered = computed(() => activeFilterCount(this.filters()) > 0);

  protected readonly canView = computed(() => can.viewAudit(this.current.access()));

  private readonly licence = inject(LicenceState);
  /** The filters, export, verification and daily checks: Pro and Enterprise (ADR-0077). */
  protected readonly tools = computed(() => this.licence.has('AUDIT_TOOLS'));
  protected readonly fullHistory = computed(() => this.licence.has('AUDIT_FULL_HISTORY'));
  /** The export stays 90 days after a licence ran out (`auditExportUntil`, decision 7). */
  protected readonly exportable = computed(() => {
    if (this.tools()) return true;
    const until = this.licence.value()?.auditExportUntil;
    return !!until && daysUntil(until) >= 0;
  });
  protected readonly scope = computed<AuditScope | undefined>(() => {
    const companyId = this.current.id();
    return companyId ? { kind: 'company', companyId } : undefined;
  });

  /** The members, for "Who" and "About"; the service accounts, for "About". */
  private readonly members = resource({
    params: () => (this.canView() ? this.current.id() : undefined),
    loader: ({ params: companyId }) =>
      loadAll((cursor) => this.api.invoke(listMembers, { companyId, limit: 100, cursor })),
  });
  private readonly identities = resource({
    params: () => (this.canView() ? this.current.id() : undefined),
    loader: ({ params: companyId }) =>
      loadAll((cursor) =>
        this.api.invoke(listServiceIdentities, { companyId, limit: 100, cursor }),
      ),
  });
  protected readonly people = computed(() =>
    (this.members.hasValue() ? (this.members.value() ?? []) : [])
      .map(({ userId, displayName, principalId }) => ({ userId, displayName, principalId }))
      .sort((a, b) => a.displayName.localeCompare(b.displayName)),
  );
  protected readonly principals = computed(() =>
    (this.identities.hasValue() ? (this.identities.value() ?? []) : [])
      .map(({ id, name }) => ({ id, name }))
      .sort((a, b) => a.name.localeCompare(b.name)),
  );
  /** Which principals are members and which service accounts, for the targets' nouns. */
  protected readonly principalKinds = computed(() => {
    const kinds = new Map<string, PrincipalKind>();
    for (const identity of this.principals()) kinds.set(identity.id, 'service account');
    for (const person of this.people()) {
      if (person.principalId) kinds.set(person.principalId, 'member');
    }
    return kinds as ReadonlyMap<string, PrincipalKind>;
  });

  /**
   * Names by id, for the chips: what the loaded events named their targets (the server's names,
   * as the reader may see them), then the pickers' people and service accounts.
   */
  protected readonly names = computed(() => {
    const names = new Map<string, string>(this.log()?.targetNames() ?? []);
    for (const identity of this.principals()) names.set(identity.id, identity.name);
    for (const person of this.people()) {
      names.set(person.userId, person.displayName);
      if (person.principalId) names.set(person.principalId, person.displayName);
    }
    return names as ReadonlyMap<string, string>;
  });

  protected applyFilters(filters: AuditFilters): void {
    void this.router.navigate([], {
      queryParams: filtersToQuery(filters),
      queryParamsHandling: 'merge',
    });
  }

  /** "Show only" on an event: its filter on top of those set. */
  protected narrow(filter: AuditQuickFilter): void {
    this.applyFilters({ ...this.filters(), ...filter });
  }

  protected readonly verifying = signal(false);
  private readonly verificationState = signal<VerificationState | null>(null);
  /** Only the result for the company on screen. */
  protected readonly verification = computed(() => {
    const state = this.verificationState();
    return state && state.companyId === this.current.id() ? state : null;
  });

  protected async verify(): Promise<void> {
    const companyId = this.current.id();
    if (!companyId || this.verifying()) return;
    this.verifying.set(true);
    try {
      const result = await this.api.invoke(verifyCompanyAuditChain, { companyId });
      this.verificationState.set({ companyId, result, at: new Date().toISOString() });
    } catch (error) {
      this.verificationState.set({
        companyId,
        error: problemMessage(error, {
          'status:403': 'Only owners and admins can verify the audit log.',
        }),
      });
    } finally {
      this.verifying.set(false);
    }
  }
}
