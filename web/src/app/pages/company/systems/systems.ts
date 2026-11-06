import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { NgIcon, provideIcons } from '@ng-icons/core';
import { lucideChevronRight, lucidePlug, lucidePlus } from '@ng-icons/lucide';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmEmptyImports } from '@spartan-ng/helm/empty';
import { HlmSpinner } from '@spartan-ng/helm/spinner';
import { Api } from '../../../api/generated/api';
import { listSystems } from '../../../api/generated/functions';
import type { ExternalSystem } from '../../../api/generated/models';
import { CurrentCompany } from '../../../core/current-company';
import { Notify } from '../../../core/notify';
import { pagedResource } from '../../../core/paged-resource';
import { can } from '../../../core/permissions';
import { ErrorState } from '../../../shared/error-state';
import { ListSkeleton } from '../../../shared/list-skeleton';
import { PageHeader } from '../../../shared/page-header';
import { SystemDialog } from './system-dialog';
import { SYSTEM_GROUP_LABELS, type SystemGroup, systemKind } from './system-kinds';
import { SystemStatusChip } from './system-status-chip';

const PAGE_SIZE = 200;

/** One section of the page: a group of the company's systems, or the caller's own MCP servers. */
export interface SystemSection {
  readonly key: SystemGroup | 'personal';
  readonly label: string;
  readonly systems: readonly ExternalSystem[];
}

const ORDER: readonly SystemGroup[] = ['tracker', 'code', 'mcp', 'chat', 'webhook', 'other'];

/** The systems by section, in the page's order; an empty section is left out. */
export function systemSections(systems: readonly ExternalSystem[]): SystemSection[] {
  const company = ORDER.map((group) => ({
    key: group,
    label: SYSTEM_GROUP_LABELS[group],
    systems: systems.filter((s) => !s.personal && systemKind(s.kind).group === group),
  }));
  const personal: SystemSection = {
    key: 'personal',
    label: 'Your own MCP servers',
    systems: systems.filter((s) => s.personal),
  };
  return [...company, personal].filter((section) => section.systems.length > 0);
}

/**
 * The company's systems (the web plan §5, Systems): trackers, code hosts and MCP servers, each with
 * its kind, status and how accounts on it connect; the caller's own MCP servers after them.
 * Register one by its kind first. Each row opens the system's page.
 */
@Component({
  selector: 'app-systems',
  imports: [
    RouterLink,
    NgIcon,
    HlmButton,
    HlmEmptyImports,
    HlmSpinner,
    ErrorState,
    ListSkeleton,
    PageHeader,
    SystemDialog,
    SystemStatusChip,
  ],
  providers: [provideIcons({ lucideChevronRight, lucidePlug, lucidePlus })],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'flex flex-col gap-5' },
  template: `
    <app-page-header
      title="Systems"
      description="The services agents work with: trackers, code hosts and MCP servers, the chats people talk to them in, and webhooks other software posts to. People connect their own accounts; each system holds the company’s credentials on its page."
    >
      @if (canManage()) {
        <button hlmBtn type="button" data-testid="register-system" (click)="dialogOpen.set(true)">
          <ng-icon name="lucidePlus" aria-hidden="true" />
          Register a system
        </button>
      }
    </app-page-header>

    @if (systems.firstLoad()) {
      <app-list-skeleton [rows]="3" />
    } @else if (systems.failed()) {
      <app-error-state
        [error]="systems.error()"
        heading="Couldn't load the systems"
        (retry)="systems.retry()"
      />
    } @else if (systems.empty()) {
      <div hlmEmpty class="bg-card rounded-xl border border-dashed" data-testid="systems-empty">
        <div hlmEmptyHeader>
          <div hlmEmptyMedia variant="icon"><ng-icon name="lucidePlug" aria-hidden="true" /></div>
          <div hlmEmptyTitle>No systems yet</div>
          <p hlmEmptyDescription>
            Register the company's tracker, code host or MCP server, so that agents can work with it
            and people can connect their accounts.
          </p>
        </div>
      </div>
    } @else {
      @for (section of sections(); track section.key) {
        <section
          class="flex flex-col gap-2"
          [attr.aria-labelledby]="'systems-' + section.key"
          [attr.data-testid]="'systems-' + section.key"
        >
          <h2 [id]="'systems-' + section.key" class="text-base font-bold">{{ section.label }}</h2>
          <ul class="bg-card divide-y rounded-xl border">
            @for (system of section.systems; track system.id) {
              <li>
                <a
                  class="hover:bg-accent/60 focus-visible:ring-ring/50 flex items-center gap-3 rounded-xl px-4 py-3 outline-none focus-visible:ring-3"
                  [routerLink]="['/c', companyId(), 'systems', system.id]"
                  [attr.data-testid]="'system-row-' + system.handle"
                >
                  <span class="flex min-w-0 flex-1 flex-col gap-1">
                    <span class="flex min-w-0 flex-wrap items-baseline gap-x-2 gap-y-0.5">
                      <span class="truncate font-semibold">{{ system.name }}</span>
                      <span class="text-muted-foreground font-mono text-xs">{{
                        system.handle
                      }}</span>
                    </span>
                    <span class="text-muted-foreground flex flex-wrap gap-x-2 text-xs">
                      <span>{{ kindLabel(system) }}</span>
                      @if (system.baseUrl) {
                        <span class="truncate font-mono">{{ system.baseUrl }}</span>
                      }
                    </span>
                  </span>
                  <span class="hidden flex-col items-end gap-1 sm:flex">
                    <app-system-status-chip [system]="system" />
                    <span class="text-muted-foreground text-xs">{{ signInLabel(system) }}</span>
                  </span>
                  <span class="sm:hidden"><app-system-status-chip [system]="system" /></span>
                  <ng-icon
                    name="lucideChevronRight"
                    class="text-muted-foreground shrink-0"
                    aria-hidden="true"
                  />
                </a>
              </li>
            }
          </ul>
        </section>
      }

      @if (systems.hasMore()) {
        <div class="flex justify-center">
          <button
            hlmBtn
            variant="outline"
            size="sm"
            type="button"
            [disabled]="systems.loadingMore()"
            (click)="systems.loadMore()"
          >
            @if (systems.loadingMore()) {
              <hlm-spinner aria-hidden="true" />
            }
            Load more
          </button>
        </div>
      }
    }

    <app-system-dialog [(open)]="dialogOpen" (saved)="onRegistered($event)" />
  `,
})
export class Systems {
  private readonly api = inject(Api);
  private readonly router = inject(Router);
  private readonly notify = inject(Notify);
  private readonly current = inject(CurrentCompany);

  protected readonly companyId = this.current.id;
  protected readonly canManage = computed(() => can.manageSystems(this.current.access()));
  protected readonly dialogOpen = signal(false);

  protected readonly systems = pagedResource<ExternalSystem, string>({
    params: () => this.current.id(),
    load: (companyId, cursor) =>
      this.api.invoke(listSystems, { companyId, limit: PAGE_SIZE, cursor }),
  });
  protected readonly sections = computed(() => systemSections(this.systems.items()));

  protected kindLabel(system: ExternalSystem): string {
    return systemKind(system.kind).label;
  }

  /** How accounts on it connect: with its app, or by token only. */
  protected signInLabel(system: ExternalSystem): string {
    if (system.kind === 'WEBHOOK') return 'Signed deliveries';
    if (system.chat) return system.chat.botSet ? 'Bot set' : 'No bot yet';
    if (!systemKind(system.kind).app) return 'Tokens only';
    return system.oauthAvailable ? 'Sign-in or token' : 'Tokens only, no app yet';
  }

  protected onRegistered(system: ExternalSystem): void {
    this.notify.success(`${system.name} is registered`);
    void this.router.navigate(['/c', this.companyId(), 'systems', system.id]);
  }
}
