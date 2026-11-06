import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { NgIcon, provideIcons } from '@ng-icons/core';
import { lucideBuilding2 } from '@ng-icons/lucide';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmEmptyImports } from '@spartan-ng/helm/empty';
import { Session } from '../../core/session';

/** Shown on `/` when the user is not a member of any company. */
@Component({
  selector: 'app-no-company',
  imports: [RouterLink, NgIcon, HlmButton, HlmEmptyImports],
  providers: [provideIcons({ lucideBuilding2 })],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div hlmEmpty class="min-h-[60svh]">
      <div hlmEmptyHeader>
        <div hlmEmptyMedia variant="icon">
          <ng-icon name="lucideBuilding2" aria-hidden="true" />
        </div>
        <h1 hlmEmptyTitle class="text-base">No company yet</h1>
        @if (session.isInstanceAdmin()) {
          <p hlmEmptyDescription>
            You're not a member of any company. Companies are declared in the deployment's
            configuration; open one from the list of all companies.
          </p>
        } @else {
          <p hlmEmptyDescription>
            You're not a member of any company yet. Ask a company owner or admin to add you with the
            email <strong class="text-foreground">{{ session.user()?.email }}</strong
            >.
          </p>
        }
      </div>
      @if (session.isInstanceAdmin()) {
        <div hlmEmptyContent class="flex-row justify-center">
          <a hlmBtn routerLink="/admin/companies">All companies</a>
        </div>
      }
    </div>
  `,
})
export class NoCompany {
  protected readonly session = inject(Session);
}
