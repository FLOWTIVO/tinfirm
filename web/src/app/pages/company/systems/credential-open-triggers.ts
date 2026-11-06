import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { NgIcon, provideIcons } from '@ng-icons/core';
import { lucideShieldAlert } from '@ng-icons/lucide';
import type { CredentialOpenTrigger, TriggerSource } from '../../../api/generated/models';

/** Where anyone may write to drive a trigger, by its source. */
const OPEN_SOURCE: Record<TriggerSource, string> = {
  TRACKER: 'its issues',
  CHANNEL: 'its channels',
  WEBHOOK: 'its webhook',
  SCHEDULE: 'its schedule',
};

/**
 * The live triggers open to anyone that act with a credential through a service account
 * (`openTriggers`, the security review of 2026-10-27, D2, the owner's decision Q3): whoever may
 * write at their source drives their agent with it. On a credential's lending on its system's page
 * and on a borrowed credential of a service account's page, each linking to the trigger (which the
 * reader may not see: its page then says so). Nothing when there are none.
 */
@Component({
  selector: 'app-credential-open-triggers',
  imports: [RouterLink, NgIcon],
  providers: [provideIcons({ lucideShieldAlert })],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'block min-w-0' },
  template: `
    @if (triggers().length) {
      <div class="flex min-w-0 flex-col gap-1 text-xs" data-testid="credential-open-triggers">
        <span class="text-warning flex items-center gap-1.5 font-semibold">
          <ng-icon name="lucideShieldAlert" class="shrink-0" aria-hidden="true" />
          {{
            triggers().length === 1
              ? 'A trigger anyone may drive acts with it'
              : triggers().length + ' triggers anyone may drive act with it'
          }}
        </span>
        <ul class="flex min-w-0 flex-col gap-0.5">
          @for (t of triggers(); track t.id) {
            <li class="text-muted-foreground min-w-0 break-words" data-testid="open-trigger">
              <a
                class="text-foreground font-semibold underline-offset-2 hover:underline"
                [routerLink]="['/c', companyId(), 'triggers', t.id]"
                >{{ t.name }}</a
              >: anyone who may write to {{ openSource[t.source] }} drives it{{
                showIdentity() ? ', through ' + (t.serviceIdentityName ?? 'a service account') : ''
              }}
            </li>
          }
        </ul>
      </div>
    }
  `,
})
export class CredentialOpenTriggers {
  readonly companyId = input.required<string>();
  readonly triggers = input<readonly CredentialOpenTrigger[]>([]);
  /** Name the service account each acts through: on the credential's page, not on its own. */
  readonly showIdentity = input(true);

  protected readonly openSource = OPEN_SOURCE;
}
