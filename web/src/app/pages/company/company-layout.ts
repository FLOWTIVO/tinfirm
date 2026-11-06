import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { toProblem } from '../../api/problem';
import { CurrentCompany } from '../../core/current-company';
import { KillSwitchState } from '../../core/kill-switch-state';
import { LicenceState } from '../../core/licence';
import { managesCompany } from '../../core/permissions';
import { pollWhileVisible } from '../../core/polling';
import { ErrorState } from '../../shared/error-state';
import { KillSwitchBanner } from '../../shared/kill-switch-banner';
import { LicenceBanner } from '../../shared/licence-banner';

/** How often every page asks whether the company's runs were stopped meanwhile. */
const SWITCHES_POLL_MS = 60_000;
/**
 * How often the licence is read again: its state moves by the day, and another instance admin may
 * add or remove a key meanwhile.
 */
const LICENCE_POLL_MS = 15 * 60_000;

/**
 * Wraps the company pages; shows one clear message when the company itself can't be opened, and
 * above every page the banner of the company's stop and of the caller's own suspension (the package
 * 6 contract, §5.4), and for its Owners and Admins the notice of a licence that ends (ADR-0077).
 */
@Component({
  selector: 'app-company-layout',
  imports: [RouterOutlet, ErrorState, KillSwitchBanner, LicenceBanner],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (companyError(); as error) {
      <app-error-state
        [error]="error"
        [heading]="notFound() ? 'Company not found' : 'Could not open this company'"
        [messages]="messages"
        (retry)="current.company.reload()"
      />
    } @else {
      <app-kill-switch-banner
        class="mb-5"
        [stop]="switches.runsStop()"
        [suspension]="switches.mySuspension()"
        [manages]="manages()"
        [companyId]="current.id()"
      />
      @if (manages()) {
        <app-licence-banner
          class="mb-5"
          [licence]="licence.value()"
          [instanceAdmin]="current.access().instanceAdmin"
        />
      }
      <router-outlet />
    }
  `,
})
export class CompanyLayout {
  protected readonly current = inject(CurrentCompany);
  protected readonly switches = inject(KillSwitchState);
  protected readonly licence = inject(LicenceState);
  protected readonly companyError = computed(() => this.current.company.error());
  protected readonly notFound = computed(() => toProblem(this.companyError()).status === 404);
  protected readonly manages = computed(() => managesCompany(this.current.access()));
  protected readonly messages = {
    'status:404': "This company doesn't exist, or you are not a member of it.",
  };

  constructor() {
    pollWhileVisible(() => this.switches.reload(), SWITCHES_POLL_MS);
    pollWhileVisible(() => this.licence.reload(), LICENCE_POLL_MS);
  }
}
