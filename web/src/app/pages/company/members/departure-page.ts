import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  input,
  resource,
  signal,
} from '@angular/core';
import { RouterLink } from '@angular/router';
import { NgIcon, provideIcons } from '@ng-icons/core';
import {
  lucideArrowLeft,
  lucideCircleAlert,
  lucideCircleCheck,
  lucideExternalLink,
} from '@ng-icons/lucide';
import { HlmAlertImports } from '@spartan-ng/helm/alert';
import { HlmAlertDialogImports } from '@spartan-ng/helm/alert-dialog';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmCardImports } from '@spartan-ng/helm/card';
import { HlmNativeSelectImports } from '@spartan-ng/helm/native-select';
import { HlmSkeleton } from '@spartan-ng/helm/skeleton';
import { HlmSpinner } from '@spartan-ng/helm/spinner';
import { Api } from '../../../api/generated/api';
import {
  closeDeparture,
  getDeparture,
  listMembers,
  setAgentOwner,
} from '../../../api/generated/functions';
import type {
  Departure,
  DepartureAgent,
  DepartureCredential,
  Member,
} from '../../../api/generated/models';
import { hasProblemCode, problemMessage } from '../../../api/problem';
import { loadAll } from '../../../core/company-data';
import { CurrentCompany } from '../../../core/current-company';
import { Notify } from '../../../core/notify';
import { ErrorState } from '../../../shared/error-state';
import { DEPARTURE_CREDENTIAL_FIXES } from '../../../shared/governance-labels';
import { plural } from '../../../shared/labels';
import { PageHeader } from '../../../shared/page-header';
import { Time } from '../../../shared/time';

/**
 * The checklist of a member who left (the package 6 contract, §5.5; CONVENTIONS §15.13), computed
 * by the server from the state: their triggers paused until someone takes them, the groups left
 * without a member, the systems' credentials they connected (ADR-0072), the agents passed to the
 * admins, the change requests their conversations opened, and their ended personal conversations.
 * The first three keep it open; closing it anyway takes `force`. An agent gets a new owner here
 * (`PUT /agents/{id}/owner`).
 */
@Component({
  selector: 'app-departure-page',
  imports: [
    NgIcon,
    RouterLink,
    HlmAlertImports,
    HlmAlertDialogImports,
    HlmButton,
    HlmCardImports,
    HlmNativeSelectImports,
    HlmSkeleton,
    HlmSpinner,
    ErrorState,
    PageHeader,
    Time,
  ],
  providers: [
    provideIcons({ lucideArrowLeft, lucideCircleAlert, lucideCircleCheck, lucideExternalLink }),
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './departure-page.html',
})
export class DeparturePage {
  private readonly api = inject(Api);
  private readonly notify = inject(Notify);
  private readonly current = inject(CurrentCompany);

  /** From the route. */
  readonly principalId = input.required<string>();
  protected readonly companyId = this.current.id;
  protected readonly plural = plural;

  protected readonly departure = resource({
    params: () => {
      const companyId = this.current.id();
      return companyId ? { companyId, principalId: this.principalId() } : undefined;
    },
    loader: ({ params }) => this.api.invoke(getDeparture, params),
  });
  protected readonly loaded = computed<Departure | null>(() =>
    this.departure.hasValue() ? (this.departure.value() ?? null) : null,
  );
  protected readonly openItems = computed(() => {
    const d = this.loaded();
    return d ? d.triggers.length + d.emptyGroups.length + d.connectedCredentials.length : 0;
  });

  /** What to do to a credential they connected, by its kind: a token, or a sign-in. */
  protected credentialFix(credential: DepartureCredential): string {
    return DEPARTURE_CREDENTIAL_FIXES[credential.kind];
  }

  // --- Agents: a new owner -----------------------------------------------------------------------

  /** The active members, who may own an agent. */
  private readonly members = resource({
    params: () => this.current.id(),
    loader: ({ params: companyId }) =>
      loadAll((cursor) => this.api.invoke(listMembers, { companyId, limit: 100, cursor })),
  });
  protected readonly owners = computed<Member[]>(() =>
    (this.members.hasValue() ? (this.members.value() ?? []) : [])
      .filter((m) => !m.suspended && !!m.principalId)
      .sort((a, b) => a.displayName.localeCompare(b.displayName)),
  );
  /** The member picked per agent, by agent id. */
  protected readonly picked = signal<Record<string, string>>({});
  protected readonly ownerBusy = signal<string | null>(null);
  /** The agents given a new owner on this page, with that owner's name. */
  protected readonly given = signal<Record<string, string>>({});

  protected pick(agentId: string, userId: string | null | undefined): void {
    this.picked.update((p) => ({ ...p, [agentId]: userId ?? '' }));
  }

  protected async giveOwner(agent: DepartureAgent): Promise<void> {
    const companyId = this.current.id();
    const userId = this.picked()[agent.id];
    const member = this.owners().find((m) => m.userId === userId);
    if (!companyId || !member?.principalId || this.ownerBusy()) return;
    this.ownerBusy.set(agent.id);
    try {
      await this.api.invoke(setAgentOwner, {
        companyId,
        agentId: agent.id,
        body: { principalId: member.principalId! },
      });
      this.given.update((g) => ({ ...g, [agent.id]: member.displayName }));
      this.notify.success(`${member.displayName} owns ${agent.name} now`);
      this.departure.reload();
    } catch (error) {
      this.notify.error(`Couldn't give ${agent.name} a new owner`, error, {
        VALIDATION_FAILED: 'Only an active member of the company can own an agent.',
      });
    } finally {
      this.ownerBusy.set(null);
    }
  }

  // --- Closing -----------------------------------------------------------------------------------

  protected readonly closeBusy = signal(false);
  protected readonly closeError = signal<string | null>(null);
  protected readonly forceOpen = signal(false);

  protected async close(force: boolean): Promise<void> {
    const companyId = this.current.id();
    if (!companyId || this.closeBusy()) return;
    this.closeBusy.set(true);
    this.closeError.set(null);
    try {
      const closed = await this.api.invoke(closeDeparture, {
        companyId,
        principalId: this.principalId(),
        body: { force },
      });
      this.departure.set(closed);
      this.forceOpen.set(false);
      this.notify.success('The checklist is closed');
    } catch (error) {
      if (hasProblemCode(error, 'DEPARTURE_OPEN')) {
        this.forceOpen.set(true);
        this.departure.reload();
      } else {
        this.closeError.set(problemMessage(error));
      }
    } finally {
      this.closeBusy.set(false);
    }
  }
}
