import { ChangeDetectionStrategy, Component, inject, resource } from '@angular/core';
import { Router } from '@angular/router';
import { NgIcon, provideIcons } from '@ng-icons/core';
import {
  lucideChevronsUpDown,
  lucideLogOut,
  lucideMoon,
  lucideSun,
  lucideUser,
} from '@ng-icons/lucide';
import { HlmDropdownMenuImports } from '@spartan-ng/helm/dropdown-menu';
import { HlmSidebarImports } from '@spartan-ng/helm/sidebar';
import { Api } from '../api/generated/api';
import { getSystemInfo } from '../api/generated/functions';
import { Notify } from '../core/notify';
import { Session } from '../core/session';
import { ThemeService } from '../core/theme';
import { initials } from '../shared/forms';

/** Sidebar footer: who is signed in, plus profile, theme and logout. */
@Component({
  selector: 'app-user-menu',
  imports: [HlmDropdownMenuImports, HlmSidebarImports, NgIcon],
  providers: [
    provideIcons({ lucideChevronsUpDown, lucideLogOut, lucideMoon, lucideSun, lucideUser }),
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (session.user(); as user) {
      <ul hlmSidebarMenu>
        <li hlmSidebarMenuItem>
          <button
            hlmSidebarMenuButton
            size="lg"
            type="button"
            class="data-open:bg-sidebar-accent"
            [hlmDropdownMenuTrigger]="menu"
            side="top"
            align="start"
          >
            <span
              class="bg-muted text-foreground flex size-8 shrink-0 items-center justify-center rounded-full text-xs font-medium"
              aria-hidden="true"
              >{{ initialsOf(user.displayName) }}</span
            >
            <span class="grid min-w-0 flex-1 text-start leading-tight">
              <span class="truncate font-medium">{{ user.displayName }}</span>
              <span class="text-muted-foreground truncate text-xs">{{ user.email }}</span>
            </span>
            <span class="sr-only">&ngsp;Account menu</span>
            <ng-icon
              name="lucideChevronsUpDown"
              class="text-muted-foreground ms-auto"
              aria-hidden="true"
            />
          </button>
        </li>
      </ul>

      <ng-template #menu>
        <hlm-dropdown-menu class="w-60">
          <hlm-dropdown-menu-label>
            <span class="block truncate">{{ user.email }}</span>
          </hlm-dropdown-menu-label>
          <button hlmDropdownMenuItem type="button" (triggered)="openProfile()">
            <ng-icon name="lucideUser" aria-hidden="true" />
            Profile
          </button>
          <button hlmDropdownMenuItem type="button" (triggered)="theme.toggle()">
            <ng-icon [name]="theme.isDark() ? 'lucideSun' : 'lucideMoon'" aria-hidden="true" />
            {{ theme.isDark() ? 'Light theme' : 'Dark theme' }}
          </button>
          <hlm-dropdown-menu-separator />
          <button hlmDropdownMenuItem type="button" (triggered)="logout()">
            <ng-icon name="lucideLogOut" aria-hidden="true" />
            Log out
          </button>
          @if (systemInfo.hasValue()) {
            <hlm-dropdown-menu-separator />
            <p class="text-muted-foreground px-1.5 py-1 text-xs">
              Tinfirm {{ systemInfo.value().version }}
            </p>
          }
        </hlm-dropdown-menu>
      </ng-template>
    }
  `,
})
export class UserMenu {
  private readonly api = inject(Api);
  private readonly router = inject(Router);
  private readonly notify = inject(Notify);
  protected readonly session = inject(Session);
  protected readonly theme = inject(ThemeService);

  protected readonly systemInfo = resource({
    loader: () => this.api.invoke(getSystemInfo, {}),
  });

  protected initialsOf(name: string): string {
    return initials(name);
  }

  protected openProfile(): void {
    void this.router.navigate(['/me']);
  }

  protected async logout(): Promise<void> {
    try {
      await this.session.logout();
    } catch (error) {
      // The local session is gone either way; tell the user the server may still have it.
      this.notify.error('Logged out on this device only', error);
    }
    await this.router.navigate(['/login']);
  }
}
