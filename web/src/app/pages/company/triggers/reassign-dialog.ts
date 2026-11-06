import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  model,
  output,
  resource,
  signal,
} from '@angular/core';
import { NgIcon, provideIcons } from '@ng-icons/core';
import { lucideCircleAlert } from '@ng-icons/lucide';
import { HlmAlertImports } from '@spartan-ng/helm/alert';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmDialogImports } from '@spartan-ng/helm/dialog';
import { HlmLabel } from '@spartan-ng/helm/label';
import { HlmNativeSelectImports } from '@spartan-ng/helm/native-select';
import { HlmSpinner } from '@spartan-ng/helm/spinner';
import { Api } from '../../../api/generated/api';
import { getTriggerChoices, listMembers, reassignTrigger } from '../../../api/generated/functions';
import type { Trigger, TriggerAccount, TriggerAccountInput } from '../../../api/generated/models';
import { problemMessage } from '../../../api/problem';
import { loadAll } from '../../../core/company-data';
import { CurrentCompany } from '../../../core/current-company';
import { type RowOption, rowOptions } from './trigger-draft';

/** `KEEP`: the row stays as it is when it passes for the new author. */
const KEEP = '';

/**
 * Reassign a trigger (plan §7's W5 row; the package 4 contract, §5.3 and §11, point 6): an admin
 * names a new author, a member who uses the agent, and for each row of the table and for the payer
 * either keeps what passes for that author or names a service account (with the credential it
 * borrows there) and its budget. Only a trigger whose author left or lost a right is reassigned;
 * it stays paused `TO_ACCEPT` until the new author accepts its accounts, its payer and its open
 * events, told by the inbox item "Reassigned to you: accept it" (the security review of
 * 2026-10-27, H2); its waiting conversations then take the new table. Every choice is checked for
 * the new author, never for the admin.
 */
@Component({
  selector: 'app-reassign-dialog',
  imports: [
    NgIcon,
    HlmAlertImports,
    HlmButton,
    HlmDialogImports,
    HlmLabel,
    HlmNativeSelectImports,
    HlmSpinner,
  ],
  providers: [provideIcons({ lucideCircleAlert })],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <hlm-dialog [state]="open() ? 'open' : 'closed'" (closed)="open.set(false)">
      <hlm-dialog-content
        *hlmDialogPortal="let ctx"
        class="max-h-[92svh] overflow-y-auto sm:max-w-lg"
        data-testid="reassign-dialog"
      >
        <hlm-dialog-header>
          <h2 hlmDialogTitle>Reassign {{ trigger().name }}</h2>
          <p hlmDialogDescription>
            The new author must use {{ trigger().agentName ?? 'its agent' }}. Every account must be
            a service account’s they may use, never their own (they add those themselves), and the
            payer theirs to use.
          </p>
          <p class="text-muted-foreground text-sm text-pretty" data-testid="reassign-to-accept">
            It stays paused until the new author accepts it: their inbox shows “Reassigned to you:
            accept it”, and accepting its accounts, its payer and its open events resumes it. Nobody
            else resumes it.
          </p>
        </hlm-dialog-header>

        <form id="reassign-form" class="flex flex-col gap-4" novalidate (submit)="submit($event)">
          <div class="flex min-w-0 flex-col gap-1.5">
            <label hlmLabel for="reassign-author">New author</label>
            <hlm-native-select
              selectId="reassign-author"
              [value]="userId()"
              (valueChange)="pickAuthor($event ?? '')"
            >
              <option hlmNativeSelectOption value="">Pick a member</option>
              @for (m of members(); track m.userId) {
                <option hlmNativeSelectOption [value]="m.userId">{{ m.displayName }}</option>
              }
            </hlm-native-select>
            @if (choicesError(); as message) {
              <p class="text-destructive text-xs" role="alert" data-testid="reassign-choices-error">
                {{ message }}
              </p>
            }
          </div>

          @if (trigger().accounts.length) {
            <fieldset class="m-0 flex min-w-0 flex-col gap-2 border-0 p-0">
              <legend class="mb-1 text-sm font-medium">The account per system</legend>
              @for (row of trigger().accounts; track row.systemId) {
                <div class="flex min-w-0 flex-col gap-1" [attr.data-reassign-row]="row.systemId">
                  <label
                    class="text-muted-foreground text-xs"
                    [for]="'reassign-row-' + row.systemId"
                  >
                    {{ row.systemName ?? 'A system' }}
                    @if (!row.accountId) {
                      (its account went with the former author)
                    }
                  </label>
                  <hlm-native-select
                    [selectId]="'reassign-row-' + row.systemId"
                    [value]="rows()[row.systemId] ?? keep"
                    (valueChange)="setRow(row.systemId, $event ?? keep)"
                  >
                    <option hlmNativeSelectOption [value]="keep">
                      {{
                        row.accountId ? 'Keep ' + keptLabel(row) + ', if it passes' : 'Leave it out'
                      }}
                    </option>
                    @for (a of accountsOn(row.systemId); track a.key) {
                      <option
                        hlmNativeSelectOption
                        [value]="a.key"
                        [selected]="rows()[row.systemId] === a.key"
                      >
                        {{ a.label }}
                      </option>
                    }
                  </hlm-native-select>
                </div>
              }
            </fieldset>
          }

          <div class="flex min-w-0 flex-col gap-1.5">
            <label hlmLabel for="reassign-payer">Paid by</label>
            <hlm-native-select
              selectId="reassign-payer"
              [value]="payer()"
              (valueChange)="payer.set($event ?? keep)"
            >
              <option hlmNativeSelectOption [value]="keep">
                Keep {{ trigger().payer?.name ?? 'the payer' }}, if it passes
              </option>
              <option hlmNativeSelectOption value="AUTHOR">The new author’s budget</option>
              @for (s of identities(); track s.id) {
                <option hlmNativeSelectOption [value]="s.id">{{ s.name }}’s budget</option>
              }
            </hlm-native-select>
          </div>

          @if (error(); as message) {
            <div hlmAlert variant="destructive" data-testid="reassign-error">
              <ng-icon name="lucideCircleAlert" aria-hidden="true" />
              <p hlmAlertDescription>{{ message }}</p>
            </div>
          }
        </form>

        <hlm-dialog-footer>
          <button hlmBtn variant="outline" type="button" hlmDialogClose [disabled]="busy()">
            Cancel
          </button>
          <button
            hlmBtn
            type="submit"
            form="reassign-form"
            data-testid="confirm-reassign"
            [disabled]="busy() || !userId()"
          >
            @if (busy()) {
              <hlm-spinner aria-hidden="true" />
            }
            Reassign
          </button>
        </hlm-dialog-footer>
      </hlm-dialog-content>
    </hlm-dialog>
  `,
})
export class ReassignDialog {
  private readonly api = inject(Api);
  private readonly current = inject(CurrentCompany);

  readonly open = model(false);
  readonly trigger = input.required<Trigger>();
  readonly reassigned = output<Trigger>();

  protected readonly keep = KEEP;
  protected readonly userId = signal('');
  /** The option named per system (`rowKey`); absent or `KEEP`: as it is. */
  protected readonly rows = signal<Record<string, string>>({});
  /** `KEEP`, `AUTHOR`, or a service account's principal. */
  protected readonly payer = signal(KEEP);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);

  private readonly membersRes = resource({
    params: () => (this.open() ? this.current.id() : undefined),
    loader: ({ params: companyId }) =>
      loadAll((cursor) => this.api.invoke(listMembers, { companyId, limit: 100, cursor })),
  });
  protected readonly members = computed(() =>
    (this.membersRes.hasValue() ? (this.membersRes.value() ?? []) : []).filter((m) => !m.suspended),
  );

  /** The new author's principal (`Member.principalId`). */
  private readonly principalId = computed(
    () => this.members().find((m) => m.userId === this.userId())?.principalId ?? null,
  );

  /** The new author's one choice: their own accounts and the service accounts they may use. */
  private readonly choicesRes = resource({
    params: () => {
      const companyId = this.current.id();
      const forPrincipalId = this.principalId();
      const t = this.trigger();
      if (!this.open() || !companyId || !forPrincipalId) return undefined;
      return {
        companyId,
        agentId: t.agentId,
        systemId: t.source === 'TRACKER' ? (t.systemId ?? undefined) : undefined,
        forPrincipalId,
        triggerId: t.id,
      };
    },
    loader: ({ params }) => this.api.invoke(getTriggerChoices, params),
  });
  protected readonly choices = computed(() =>
    this.choicesRes.hasValue() ? (this.choicesRes.value()?.items ?? []) : [],
  );
  /** Why the new author's choices could not be read: above all, the agent not theirs to use. */
  protected readonly choicesError = computed(() =>
    this.choicesRes.error()
      ? problemMessage(this.choicesRes.error(), {
          AGENT_NOT_USABLE:
            'This member may not use the trigger’s agent. Share the agent with them first, or pick another member.',
        })
      : null,
  );
  /** Service accounts the new author may use, to pay. */
  protected readonly identities = computed(() =>
    this.choices()
      .filter((c) => c.kind === 'SERVICE')
      .map((c) => ({ id: c.principalId, name: c.name ?? 'A service account' })),
  );

  constructor() {
    effect(() => {
      if (!this.open()) {
        this.userId.set('');
        this.rows.set({});
        this.payer.set(KEEP);
        this.error.set(null);
      }
    });
  }

  /** The accounts the new author may name on a system: their own, or a service account's. */
  protected accountsOn(systemId: string): RowOption[] {
    return rowOptions(this.choices(), systemId, this.principalId());
  }

  /** "svc-reader with CI bot", "Alice’s": what a kept row acts with, and whether read-only. */
  protected keptLabel(row: TriggerAccount): string {
    if (row.holderKind === 'SERVICE' && row.credentialName) {
      const readOnly = row.access === 'READ' ? ', read-only' : '';
      return `${row.holderName ?? 'its service account'} with ${row.credentialName}${readOnly}`;
    }
    return `${row.holderName ?? 'it'}’s`;
  }

  /** Another author: what was named for the former one no longer applies. */
  protected pickAuthor(userId: string): void {
    this.userId.set(userId);
    this.rows.set({});
    this.payer.set(KEEP);
    this.error.set(null);
  }

  protected setRow(systemId: string, key: string): void {
    this.rows.update((rows) => ({ ...rows, [systemId]: key }));
  }

  protected async submit(event: Event): Promise<void> {
    event.preventDefault();
    const companyId = this.current.id();
    if (!companyId || !this.userId() || this.busy()) return;
    this.busy.set(true);
    this.error.set(null);
    try {
      const principalId = this.principalId();
      if (!principalId) {
        this.error.set('This member has no principal in the company yet; pick another.');
        return;
      }
      const named = Object.entries(this.rows()).filter(([, id]) => id !== KEEP);
      let accounts: TriggerAccountInput[] | null = null;
      if (named.length) {
        const byRow = new Map(named);
        accounts = this.trigger()
          .accounts.map((row): TriggerAccountInput => {
            const key = byRow.get(row.systemId);
            const option = key
              ? this.accountsOn(row.systemId).find((o) => o.key === key)
              : undefined;
            if (option) {
              return {
                systemId: row.systemId,
                accountId: option.accountId,
                holderPrincipalId: option.principalId,
              };
            }
            // Kept as it is: a service account's row names it, an author's own row the new author.
            return row.holderKind === 'SERVICE'
              ? {
                  systemId: row.systemId,
                  accountId: row.accountId ?? '',
                  holderPrincipalId: row.holderPrincipalId,
                }
              : { systemId: row.systemId, accountId: row.accountId ?? '' };
          })
          .filter((row) => !!row.accountId);
      }
      const payer = this.payer();
      const trigger = await this.api.invoke(reassignTrigger, {
        companyId,
        triggerId: this.trigger().id,
        body: {
          authorPrincipalId: principalId,
          accounts,
          payerPrincipalId: payer === KEEP ? null : payer === 'AUTHOR' ? principalId : payer,
        },
      });
      this.reassigned.emit(trigger);
      this.open.set(false);
    } catch (error) {
      this.error.set(
        problemMessage(error, {
          AGENT_NOT_USABLE:
            'This member may not use the trigger’s agent. Share the agent with them first, or pick another member.',
          ACCOUNT_NOT_ENTITLED:
            'The new author may not use one of these accounts: name a service account they may use.',
          MODEL_ACCESS_NOT_ENTITLED:
            'The new author may not use this budget: pick their own, or a service account’s they may use.',
          SOURCE_ACCOUNT_REQUIRED:
            'The trigger needs an account on its own system: name one the new author may use.',
        }),
      );
    } finally {
      this.busy.set(false);
    }
  }
}
