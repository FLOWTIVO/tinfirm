import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { Router } from '@angular/router';
import { NgIcon, provideIcons } from '@ng-icons/core';
import { lucideBuilding2, lucideCheck, lucideChevronsUpDown } from '@ng-icons/lucide';
import { HlmDropdownMenuImports } from '@spartan-ng/helm/dropdown-menu';
import { HlmSidebarImports } from '@spartan-ng/helm/sidebar';
import type { Role } from '../api/generated/models';
import { CurrentCompany } from '../core/current-company';
import { type CompanyAccess, ROLE_LABELS } from '../core/permissions';
import { Session } from '../core/session';
import { navSectionOf } from './navigation';

/** Sidebar header: the current company and a menu to switch to another one. */
@Component({
  selector: 'app-company-switcher',
  imports: [HlmDropdownMenuImports, HlmSidebarImports, NgIcon],
  providers: [provideIcons({ lucideBuilding2, lucideCheck, lucideChevronsUpDown })],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <ul hlmSidebarMenu>
      <li hlmSidebarMenuItem>
        <button
          hlmSidebarMenuButton
          size="lg"
          type="button"
          class="data-open:bg-sidebar-accent"
          [hlmDropdownMenuTrigger]="menu"
          align="start"
        >
          <span
            class="bg-primary text-primary-foreground flex size-8 shrink-0 items-center justify-center rounded-md text-sm font-semibold"
            aria-hidden="true"
          >
            @if (current.navName(); as name) {
              {{ name.charAt(0).toUpperCase() }}
            } @else {
              <ng-icon name="lucideBuilding2" />
            }
          </span>
          <span class="grid min-w-0 flex-1 text-start leading-tight">
            <span class="truncate font-medium">{{ current.navName() ?? 'Tinfirm' }}</span>
            <span class="text-muted-foreground truncate text-xs">{{ subtitle() }}</span>
          </span>
          <span class="sr-only">&ngsp;Switch company</span>
          <ng-icon
            name="lucideChevronsUpDown"
            class="text-muted-foreground ms-auto"
            aria-hidden="true"
          />
        </button>
      </li>
    </ul>

    <ng-template #menu>
      <hlm-dropdown-menu class="w-64">
        <hlm-dropdown-menu-label>Your companies</hlm-dropdown-menu-label>
        @for (m of session.memberships(); track m.companyId) {
          <button hlmDropdownMenuItem type="button" (triggered)="switchTo(m.companyId, m.role)">
            <span
              class="bg-muted flex size-6 shrink-0 items-center justify-center rounded text-xs font-medium"
              aria-hidden="true"
              >{{ m.companyName.charAt(0).toUpperCase() }}</span
            >
            <span class="min-w-0 flex-1 truncate">{{ m.companyName }}</span>
            <span class="text-muted-foreground text-xs">{{ roleLabel(m.role) }}</span>
            @if (m.companyId === current.navTargetId()) {
              <ng-icon name="lucideCheck" aria-label="current" />
            }
          </button>
        } @empty {
          <p class="text-muted-foreground px-1.5 py-1 text-sm">
            You're not a member of any company.
          </p>
        }
        @if (session.isInstanceAdmin()) {
          <hlm-dropdown-menu-separator />
          <button hlmDropdownMenuItem type="button" (triggered)="openAllCompanies()">
            <ng-icon name="lucideBuilding2" aria-hidden="true" />
            All companies
          </button>
        }
      </hlm-dropdown-menu>
    </ng-template>
  `,
})
export class CompanySwitcher {
  protected readonly session = inject(Session);
  protected readonly current = inject(CurrentCompany);
  private readonly router = inject(Router);

  protected readonly subtitle = computed(() => {
    const access = this.current.navAccess();
    if (!this.current.navTargetId()) return 'No company';
    if (access.role) return ROLE_LABELS[access.role];
    return access.instanceAdmin ? 'Instance admin' : '';
  });

  protected roleLabel(role: Role): string {
    return ROLE_LABELS[role];
  }

  /** Opens the same section in the other company when the caller sees it there, else the inbox. */
  protected switchTo(companyId: string, role: Role): void {
    const access: CompanyAccess = { role, instanceAdmin: this.session.isInstanceAdmin() };
    const section = navSectionOf(this.router.url);
    const path = section?.visible(access) ? section.path : 'inbox';
    void this.router.navigate(['/c', companyId, path]);
  }

  protected openAllCompanies(): void {
    void this.router.navigate(['/admin/companies']);
  }
}
