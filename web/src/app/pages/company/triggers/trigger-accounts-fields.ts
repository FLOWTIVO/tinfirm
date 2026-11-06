import { ChangeDetectionStrategy, Component, computed, input, model } from '@angular/core';
import { NgIcon, provideIcons } from '@ng-icons/core';
import { lucideCircleAlert, lucideLock } from '@ng-icons/lucide';
import { HlmLabel } from '@spartan-ng/helm/label';
import { HlmNativeSelectImports } from '@spartan-ng/helm/native-select';
import type {
  SystemKind,
  TriggerAccount,
  TriggerChoice,
  TriggerPreviewAccount,
} from '../../../api/generated/models';
import { formatDay } from '../../../shared/labels';
import {
  fillFromChoice,
  type RowOption,
  rowKey,
  rowOptions,
  type TriggerDraft,
  withRow,
} from './trigger-draft';
import { ACCOUNT_STATUS_LABELS, CHOICE_STATUS_LABELS } from './trigger-labels';

interface TableRow {
  readonly systemId: string;
  readonly name: string;
  readonly source: boolean;
  /** A Slack channel trigger's own system: its account reads the thread asked in (`history`). */
  readonly history: boolean;
  /** The row's account, or empty: none. */
  readonly accountId: string;
  /** Whom it acts through and with what, as the select's value (`rowKey`). */
  readonly key: string;
  readonly options: RowOption[];
  readonly status: TriggerPreviewAccount['status'] | null;
  readonly error: string | null;
  /** An MCP server that needs no account (ADR-0071): the runs call it without one, and it takes no row. */
  readonly noAccount: boolean;
  /** The row's credential is lent read-only (`READ`): its runs call no write tool there. */
  readonly readOnly: boolean;
}

/**
 * "Acts as" and "Paid by" of the trigger editor (plan §4.5, steps 3 and 4; the package 4
 * contract, §5.2): one choice, "Use the accounts of: You | svc-ba | svc-dev", from
 * `/triggers/choices`, each with its account and status per system (a service account's the
 * credential it borrows there, by name, ADR-0072), its budget and the systems it leaves out; it
 * fills the account table, whose rows the author may then change or remove, except the source's,
 * which must exist. Who pays is the author or a service account they may use. A
 * Slack channel trigger's own row is optional: its account, someone's own Slack sign-in, reads the
 * thread the agent is asked in and nothing else (the package 5 contract, open point 13). An MCP
 * server that needs no account (ADR-0071) takes no row: its runs call it without one.
 */
@Component({
  selector: 'app-trigger-accounts-fields',
  imports: [NgIcon, HlmLabel, HlmNativeSelectImports],
  providers: [provideIcons({ lucideCircleAlert, lucideLock })],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'flex flex-col gap-4' },
  template: `
    @let d = draft();
    <fieldset class="m-0 flex min-w-0 flex-col gap-2 border-0 p-0" data-testid="trigger-choices">
      <legend class="mb-1 text-sm font-medium">Use the accounts of</legend>
      <div class="grid min-w-0 gap-2 sm:grid-cols-2">
        @for (c of choices(); track c.principalId) {
          <label
            class="has-checked:border-primary has-checked:bg-primary/10 has-focus-visible:ring-ring/50 hover:bg-muted/50 flex min-w-0 cursor-pointer items-start gap-2.5 rounded-lg border px-3 py-2.5 has-focus-visible:ring-3"
            [attr.data-choice]="c.principalId"
          >
            <input
              type="radio"
              name="trigger-choice"
              class="accent-primary mt-1 size-4 shrink-0"
              [id]="'trigger-choice-' + c.principalId"
              [checked]="d.choice === c.principalId"
              (change)="choose(c)"
            />
            <span class="flex min-w-0 flex-col gap-1">
              <span class="font-semibold">
                {{ whoOf(c) }}
                @if (c.kind === 'SERVICE') {
                  <span class="text-muted-foreground text-xs font-normal">service account</span>
                }
              </span>
              <span class="text-muted-foreground flex flex-col text-xs">
                @for (s of c.systems; track s.systemId) {
                  <span [attr.data-choice-system]="s.systemId">
                    {{ s.systemName ?? 'A system' }}:
                    @if (s.status === 'NOT_NEEDED') {
                      no account needed
                    } @else if (s.status === 'NONE') {
                      no account, so not available
                    } @else {
                      @if (c.kind === 'SERVICE' && s.credentialName) {
                        {{ s.credentialName
                        }}{{ s.externalLogin ? ' (' + s.externalLogin + ')' : '' }}
                      } @else {
                        {{ s.externalLogin ?? 'an account' }}
                      }
                      @if (s.access === 'READ') {
                        · <span data-testid="choice-read-only">read-only</span>
                      }
                      @if (s.status !== 'OK') {
                        · {{ choiceStatus[s.status] }}
                      }
                    }
                  </span>
                }
                <span>Pays: {{ budgetText(c) }}</span>
              </span>
            </span>
          </label>
        } @empty {
          <p class="text-muted-foreground text-sm sm:col-span-2" data-testid="no-choices">
            Pick the agent{{ needsSystem() ? ' and the system' : '' }} first: the choices are the
            accounts you may lend it.
          </p>
        }
      </div>
      @if (choices().length && d.choice === null && d.accounts.length) {
        <p class="text-muted-foreground text-xs" data-testid="choice-changed">
          Rows changed by hand below.
        </p>
      }
    </fieldset>

    @if (rows().length) {
      <div class="flex min-w-0 flex-col gap-1.5" data-testid="trigger-account-table">
        <span class="text-sm font-medium" id="trigger-table-label">The account per system</span>
        <ul class="flex flex-col divide-y rounded-lg border" aria-labelledby="trigger-table-label">
          @for (row of rows(); track row.systemId) {
            <li
              class="flex min-w-0 flex-col gap-2 px-3 py-2.5 sm:flex-row sm:items-center sm:gap-3"
              [attr.data-row]="row.systemId"
            >
              <label
                hlmLabel
                class="min-w-0 sm:w-40 sm:shrink-0"
                [for]="'trigger-row-' + row.systemId"
              >
                {{ row.name }}
                @if (row.source) {
                  <ng-icon
                    name="lucideLock"
                    size="12"
                    class="text-muted-foreground"
                    aria-hidden="true"
                  />
                }
              </label>
              <div class="flex min-w-0 flex-1 flex-col gap-1">
                @if (row.noAccount && !row.accountId) {
                  <p class="text-sm" [attr.data-row-no-account]="row.systemId">
                    No account needed: its runs call it without one.
                  </p>
                } @else {
                  <hlm-native-select
                    class="w-full"
                    [selectId]="'trigger-row-' + row.systemId"
                    [forceInvalid]="!!row.error"
                    [value]="row.key"
                    (valueChange)="setRow(row.systemId, row.options, $event ?? '')"
                  >
                    @if (!row.source || !row.accountId) {
                      <option hlmNativeSelectOption value="">
                        {{
                          row.source
                            ? 'Pick an account'
                            : row.noAccount
                              ? 'No account: its runs call it without one'
                              : 'Not available to this trigger’s runs'
                        }}
                      </option>
                    }
                    @for (o of row.options; track o.key) {
                      <option hlmNativeSelectOption [value]="o.key" [selected]="o.key === row.key">
                        {{ o.label }}
                      </option>
                    }
                  </hlm-native-select>
                }
                @if (row.error) {
                  <p
                    class="text-destructive text-xs"
                    role="alert"
                    [attr.data-row-error]="row.systemId"
                  >
                    {{ row.error }}
                  </p>
                } @else if (row.source) {
                  <p class="text-muted-foreground text-xs">
                    Required: the trigger reads {{ row.name }} and writes its results back with it.
                  </p>
                } @else if (row.status && row.status !== 'OK') {
                  <p class="text-muted-foreground text-xs" [attr.data-row-status]="row.systemId">
                    {{ accountStatus[row.status] }}
                  </p>
                } @else if (row.readOnly) {
                  <p class="text-muted-foreground text-xs" [attr.data-row-read-only]="row.systemId">
                    Lent read-only: its runs call no write tool on {{ row.name }}.
                  </p>
                } @else if (row.history) {
                  <p class="text-muted-foreground text-xs" [attr.data-row-history]="row.systemId">
                    Optional: with a Slack sign-in here, the agent reads the thread it is asked in,
                    and nothing else in Slack, when it lists the history tool.
                  </p>
                }
              </div>
            </li>
          }
        </ul>
      </div>
    }

    @if (error(); as message) {
      <p
        class="text-destructive flex items-start gap-1.5 text-sm outline-none"
        role="alert"
        tabindex="-1"
        data-error="accounts"
      >
        <ng-icon name="lucideCircleAlert" class="mt-0.5 shrink-0" aria-hidden="true" />
        {{ message }}
      </p>
    }

    @if (choices().length) {
      <div class="flex min-w-0 flex-col gap-1.5" data-field="payer">
        <label hlmLabel for="trigger-payer">Paid by</label>
        <hlm-native-select
          class="w-full sm:max-w-sm"
          selectId="trigger-payer"
          [forceInvalid]="!!payerError()"
          [value]="d.payerPrincipalId ?? ''"
          (valueChange)="setPayer($event ?? '')"
        >
          @if (!d.payerPrincipalId) {
            <option hlmNativeSelectOption value="">Pick who pays</option>
          }
          @for (c of choices(); track c.principalId) {
            <option
              hlmNativeSelectOption
              [value]="c.principalId"
              [selected]="c.principalId === d.payerPrincipalId"
            >
              {{
                c.principalId === author()
                  ? 'Your budget'
                  : (c.name ?? 'A service account') + '’s budget'
              }}
            </option>
          }
        </hlm-native-select>
        @if (payerError(); as message) {
          <p class="text-destructive text-sm" role="alert" data-error="payer">{{ message }}</p>
        } @else {
          <p class="text-muted-foreground text-xs" data-testid="payer-state">
            {{ payerState() }}
          </p>
        }
      </div>
    }
  `,
})
export class TriggerAccountsFields {
  readonly draft = model.required<TriggerDraft>();
  readonly choices = input<readonly TriggerChoice[]>([]);
  /** The author's principal: "You" and "Yours". */
  readonly author = input<string | null>(null);
  /** The preview's rows, for each row's status. */
  readonly previewAccounts = input<readonly TriggerPreviewAccount[]>([]);
  /** A row the server refused (`ACCOUNT_NOT_ENTITLED` with `details.systemId`). */
  readonly rowErrors = input<Readonly<Record<string, string>>>({});
  readonly error = input<string | null>(null);
  /** A change's stored rows: kept as options, since the choices are the caller's (an admin's). */
  readonly kept = input<readonly TriggerAccount[]>([]);
  readonly payerError = input<string | null>(null);
  /** The source system's kind: a Slack channel trigger's row reads its threads. */
  readonly sourceKind = input<SystemKind | null>(null);

  protected readonly choiceStatus = CHOICE_STATUS_LABELS;
  protected readonly accountStatus = ACCOUNT_STATUS_LABELS;

  protected readonly needsSystem = computed(
    () => this.draft().source === 'TRACKER' && !this.draft().systemId,
  );

  /** One row per system the trigger needs: every system the choices list, the source first. */
  protected readonly rows = computed<TableRow[]>(() => {
    const d = this.draft();
    const names = new Map<string, string>();
    for (const c of this.choices()) {
      for (const s of c.systems) {
        if (!names.has(s.systemId)) names.set(s.systemId, s.systemName ?? 'A system');
      }
    }
    for (const k of this.kept()) {
      if (!names.has(k.systemId)) names.set(k.systemId, k.systemName ?? 'A system');
    }
    const ids = [...names.keys()].sort((a, b) =>
      a === d.systemId ? -1 : b === d.systemId ? 1 : 0,
    );
    return ids.map((systemId) => {
      const current = d.accounts.find((a) => a.systemId === systemId);
      const options = this.optionsOf(systemId, current?.accountId);
      const key = current ? this.keyOf(current, options) : '';
      const preview = this.previewAccounts().find((a) => a.systemId === systemId);
      return {
        systemId,
        name: names.get(systemId)!,
        source: d.source === 'TRACKER' && systemId === d.systemId,
        history: d.source === 'CHANNEL' && systemId === d.systemId && this.sourceKind() === 'SLACK',
        accountId: current?.accountId ?? '',
        key,
        options,
        status: preview?.status ?? null,
        error: this.rowErrors()[systemId] ?? null,
        noAccount: this.choices().some((c) =>
          c.systems.some((s) => s.systemId === systemId && s.status === 'NOT_NEEDED'),
        ),
        // The preview's access, where it read this row; else what the option picked says.
        readOnly:
          !!current && (preview?.access ?? options.find((o) => o.key === key)?.access) === 'READ',
      };
    });
  });

  protected readonly payerState = computed(() => {
    const payer = this.choices().find((c) => c.principalId === this.draft().payerPrincipalId);
    const text = payer ? this.budgetText(payer) : '';
    return text.charAt(0).toUpperCase() + text.slice(1);
  });

  private optionsOf(systemId: string, current: string | undefined): RowOption[] {
    const options = rowOptions(this.choices(), systemId, this.author());
    const kept = this.kept().find((k) => k.systemId === systemId && k.accountId === current);
    const key = kept?.accountId ? rowKey(kept.holderPrincipalId, kept.accountId) : '';
    if (kept?.accountId && !options.some((o) => o.key === key)) {
      const login = kept.externalLogin ? ` (${kept.externalLogin})` : '';
      const what =
        kept.holderPrincipalId === this.author()
          ? 'The author’s account'
          : kept.credentialName
            ? `${kept.holderName ?? 'A service account'} with ${kept.credentialName}`
            : `${kept.holderName ?? 'An'}’s account`;
      const readOnly = kept.access === 'READ' ? ', read-only' : '';
      options.unshift({
        key,
        accountId: kept.accountId,
        principalId: kept.holderPrincipalId,
        label: `${what}${login}${readOnly}, as saved`,
        reconnect: false,
        access: kept.access ?? null,
      });
    }
    return options;
  }

  /** The option a row holds: by whom it acts through, else the first with its account. */
  private keyOf(
    row: { accountId: string; holderPrincipalId?: string | null },
    options: readonly RowOption[],
  ): string {
    const exact = row.holderPrincipalId ? rowKey(row.holderPrincipalId, row.accountId) : null;
    if (exact && options.some((o) => o.key === exact)) return exact;
    return options.find((o) => o.accountId === row.accountId)?.key ?? exact ?? '';
  }

  protected whoOf(c: TriggerChoice): string {
    return c.principalId === this.author() ? 'You' : (c.name ?? 'A service account');
  }

  protected budgetText(c: TriggerChoice): string {
    const owner = c.principalId === this.author() ? 'your budget' : `${c.name ?? 'its'}’s budget`;
    const b = c.budget;
    if (b.state === 'READY') return b.detail ? `${owner}, ${b.detail}` : `${owner}, ready`;
    if (b.state === 'WAITING') {
      const until = formatDay(b.until);
      return `${owner}, spent${until ? ` until ${until}` : ''}: turns wait`;
    }
    return `${owner} cannot pay${b.detail ? `: ${b.detail}` : ''}`;
  }

  protected choose(c: TriggerChoice): void {
    this.draft.update((d) => fillFromChoice(d, c, this.author()));
  }

  protected setRow(systemId: string, options: readonly RowOption[], key: string): void {
    const option = options.find((o) => o.key === key) ?? null;
    this.draft.update((d) => withRow(d, systemId, option));
  }

  protected setPayer(principalId: string): void {
    // Someone else's budget pays with the budget only, never the author's subscription (§3.3).
    const author = this.author();
    this.draft.update((d) => ({
      ...d,
      payerPrincipalId: principalId || null,
      settings:
        d.settings && principalId !== author
          ? { ...d.settings, modelAccess: 'BUDGET', subscription: null }
          : d.settings,
    }));
  }
}
