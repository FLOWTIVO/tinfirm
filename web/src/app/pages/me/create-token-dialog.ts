import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  model,
  output,
  signal,
  untracked,
} from '@angular/core';
import { Router } from '@angular/router';
import { form, FormField, maxLength, required, submit, validate } from '@angular/forms/signals';
import { NgIcon, provideIcons } from '@ng-icons/core';
import { lucideCircleAlert, lucideEye, lucideTriangleAlert } from '@ng-icons/lucide';
import { HlmAlertImports } from '@spartan-ng/helm/alert';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmDialogImports } from '@spartan-ng/helm/dialog';
import { HlmFieldImports } from '@spartan-ng/helm/field';
import { HlmInput } from '@spartan-ng/helm/input';
import { HlmNativeSelectImports } from '@spartan-ng/helm/native-select';
import { HlmSpinner } from '@spartan-ng/helm/spinner';
import { Api } from '../../api/generated/api';
import { createOwnToken } from '../../api/generated/functions';
import type { PersonalAccessToken, TokenOwnerNotice, TokenScope } from '../../api/generated/models';
import { hasProblemCode, mapSubmissionError } from '../../api/problem';
import { Session } from '../../core/session';
import { CopyButton } from '../../shared/copy-button';
import { firstError } from '../../shared/forms';
import { ownerNames } from '../../shared/owner-notice';
import { Time } from '../../shared/time';

/** The three scopes, lowest first, each with what it allows in one line. */
export const TOKEN_SCOPES: readonly { value: TokenScope; label: string; explanation: string }[] = [
  {
    value: 'READ',
    label: 'Read',
    explanation: 'Read your conversations, decisions and inbox, and the agents you may use.',
  },
  {
    value: 'WRITE',
    label: 'Write',
    explanation: 'Also start conversations, send messages, cancel, retry and end. Never decide.',
  },
  {
    value: 'DECIDE',
    label: 'Decide',
    explanation:
      'Also answer, approve or deny your own decisions. Only for a person at the keyboard.',
  },
];

export const SCOPE_LABELS: Record<TokenScope, string> = {
  READ: 'Read',
  WRITE: 'Write',
  DECIDE: 'Decide',
};

/**
 * Expiry choices in days: a token expires 90 days after its creation at most, unless it is renewed
 * (the security review of 2026-10-27, D9).
 */
export const EXPIRY_CHOICES: readonly { days: number; label: string }[] = [
  { days: 7, label: '7 days' },
  { days: 30, label: '30 days' },
  { days: 90, label: '90 days' },
];

/** The server's default and longest expiry. */
export const MAX_EXPIRY_DAYS = 90;
const DAY_MS = 24 * 60 * 60 * 1000;

/** The `expiresAt` for that many days ahead, or null for `0` (no expiry: SCIM's tokens). */
export function expiresAtFor(days: number, now: Date = new Date()): string | null {
  return days > 0 ? new Date(now.getTime() + days * DAY_MS).toISOString() : null;
}

/**
 * A personal access token's `expiresAt` for a choice: null for the longest, which the server sets
 * from its own clock (a time computed here could land a little past its 90 days), else that many
 * days ahead.
 */
export function tokenExpiresAt(days: number, now: Date = new Date()): string | null {
  return days >= MAX_EXPIRY_DAYS ? null : expiresAtFor(days, now);
}

/** When a token made now with that choice expires, for the dialog to show. */
export function expiryDate(days: number, now: Date = new Date()): string {
  return new Date(now.getTime() + days * DAY_MS).toISOString();
}

const EMPTY = {
  name: '',
  scope: 'READ' as TokenScope,
  expiryDays: MAX_EXPIRY_DAYS,
  currentPassword: '',
};

/**
 * Creates a personal access token and shows it exactly once. The token lives only in this dialog's
 * memory and is dropped when the dialog closes. It asks a local user for the current password
 * (`currentPassword`), offers the companies the token reaches (`companyIds`: all of the user's by
 * default) and shows when it expires (90 days by default, at most 90, renewed from the list). A
 * user of an identity provider whose sign-in is older than the server allows (`403
 * RECENT_SIGN_IN_REQUIRED`) is asked to sign in again, and comes back to this page.
 */
@Component({
  selector: 'app-create-token-dialog',
  imports: [
    FormField,
    NgIcon,
    HlmAlertImports,
    HlmButton,
    HlmDialogImports,
    HlmFieldImports,
    HlmInput,
    HlmNativeSelectImports,
    HlmSpinner,
    CopyButton,
    Time,
  ],
  providers: [provideIcons({ lucideCircleAlert, lucideEye, lucideTriangleAlert })],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <hlm-dialog [state]="open() ? 'open' : 'closed'" (closed)="close()">
      <hlm-dialog-content
        *hlmDialogPortal="let ctx"
        class="max-h-[92svh] overflow-y-auto sm:max-w-lg"
      >
        @if (result(); as c) {
          <hlm-dialog-header>
            <h2 hlmDialogTitle>{{ c.token.name }}</h2>
            <p hlmDialogDescription>
              Send it as <code class="text-xs">Authorization: Bearer</code> to a company's MCP
              endpoint. Keep it in an environment variable, never in a file you commit.
            </p>
          </hlm-dialog-header>

          <div hlmAlert class="border-warning/50" data-testid="token-once">
            <ng-icon name="lucideTriangleAlert" class="text-warning" aria-hidden="true" />
            <h3 hlmAlertTitle>Copy the token now</h3>
            <p hlmAlertDescription>
              It is not shown again; Tinfirm keeps only a hash. If you lose it, revoke it and create
              another.
            </p>
          </div>

          <div hlmAlert data-testid="owner-notice">
            <ng-icon name="lucideEye" aria-hidden="true" />
            <h3 hlmAlertTitle>Conversations started with it are personal</h3>
            <div hlmAlertDescription class="flex flex-col gap-1.5">
              <p>
                Conversations you start with this token are personal: agents act with your own
                accounts and budget. The Owners of each company you start them in can read them in
                full; each read is recorded and shown to you.
              </p>
              @if (c.ownerNotice.companies.length) {
                <ul class="list-disc pl-5" data-testid="owner-notice-companies">
                  @for (company of c.ownerNotice.companies; track company.companyId) {
                    <li>
                      <span class="font-medium">{{ company.companyName }}</span>
                      @if (company.owners.length) {
                        : {{ ownerNames(company.owners) }}
                      }
                    </li>
                  }
                </ul>
              }
            </div>
          </div>

          <div class="flex flex-col gap-1.5">
            <span class="text-sm font-medium" id="new-token-label">Token</span>
            <div class="bg-muted/50 flex items-center gap-2 rounded-lg border px-3 py-2">
              <code
                class="min-w-0 flex-1 text-xs break-all select-all"
                aria-labelledby="new-token-label"
                data-testid="new-token"
                >{{ c.secret }}</code
              >
              <app-copy-button
                [value]="c.secret"
                ariaLabel="Copy token"
                label="Copy"
                variant="outline"
              />
            </div>
          </div>

          <hlm-dialog-footer>
            <button hlmBtn type="button" hlmDialogClose>Done</button>
          </hlm-dialog-footer>
        } @else {
          <hlm-dialog-header>
            <h2 hlmDialogTitle>New personal access token</h2>
            <p hlmDialogDescription>
              For scripts, CI and AI clients such as Claude Code on a company's MCP endpoint. A
              token acts as you, with your role in each company, and never more than its scope
              allows.
            </p>
          </hlm-dialog-header>

          <form
            id="create-token-form"
            class="flex flex-col gap-4"
            novalidate
            (submit)="onSubmit($event)"
          >
            @if (formError(); as message) {
              <div hlmAlert variant="destructive">
                <ng-icon name="lucideCircleAlert" aria-hidden="true" />
                <p hlmAlertDescription>{{ message }}</p>
              </div>
            }
            <div hlmField>
              <label hlmFieldLabel for="token-name">Name</label>
              <input
                hlmInput
                id="token-name"
                autocomplete="off"
                placeholder="Laptop, Claude Code"
                [formField]="tokenForm.name"
              />
              <p hlmFieldDescription>So you recognise it in the list later.</p>
              <hlm-field-error>{{ firstError(tokenForm.name) }}</hlm-field-error>
            </div>

            @if (memberships().length > 1) {
              <fieldset class="flex flex-col gap-2" data-testid="company-picker">
                <legend class="mb-1 text-sm font-medium">Companies</legend>
                <label class="flex items-start gap-2 text-sm">
                  <input
                    type="radio"
                    name="token-companies"
                    class="accent-primary mt-0.5 size-4 shrink-0"
                    value="all"
                    [checked]="allCompanies()"
                    (change)="allCompanies.set(true)"
                  />
                  <span class="flex flex-col">
                    <span class="font-medium">All your companies</span>
                    <span class="text-muted-foreground text-xs"
                      >And any company you join later.</span
                    >
                  </span>
                </label>
                <label class="flex items-start gap-2 text-sm">
                  <input
                    type="radio"
                    name="token-companies"
                    class="accent-primary mt-0.5 size-4 shrink-0"
                    value="some"
                    [checked]="!allCompanies()"
                    (change)="allCompanies.set(false)"
                  />
                  <span class="font-medium">Only these</span>
                </label>
                @if (!allCompanies()) {
                  <div class="flex flex-col gap-1.5 ps-6">
                    @for (m of memberships(); track m.companyId) {
                      <label class="flex items-center gap-2 text-sm">
                        <input
                          type="checkbox"
                          class="accent-primary size-4 shrink-0"
                          [attr.data-company]="m.companyId"
                          [checked]="chosen().includes(m.companyId)"
                          (change)="toggleCompany(m.companyId, $any($event.target).checked)"
                        />
                        {{ m.companyName }}
                      </label>
                    }
                    <p class="text-muted-foreground text-xs">
                      Leaving one of them revokes the token.
                    </p>
                    @if (companiesError()) {
                      <p class="text-destructive text-xs" role="alert" data-error="companies">
                        Choose at least one company.
                      </p>
                    }
                  </div>
                }
              </fieldset>
            }

            <fieldset class="flex flex-col gap-2" data-testid="scope-picker">
              <legend class="mb-1 text-sm font-medium">Scope</legend>
              @for (scope of scopes; track scope.value) {
                <label class="flex items-start gap-2 text-sm">
                  <input
                    type="radio"
                    name="token-scope"
                    class="accent-primary mt-0.5 size-4 shrink-0"
                    [value]="scope.value"
                    [checked]="model().scope === scope.value"
                    (change)="setScope(scope.value)"
                  />
                  <span class="flex flex-col">
                    <span class="font-medium">{{ scope.label }}</span>
                    <span class="text-muted-foreground text-xs">{{ scope.explanation }}</span>
                  </span>
                </label>
              }
            </fieldset>

            <div hlmField>
              <label hlmFieldLabel for="token-expiry">Expires</label>
              <hlm-native-select
                class="w-full"
                selectId="token-expiry"
                [value]="'' + model().expiryDays"
                (valueChange)="setExpiry($event)"
              >
                @for (choice of expiryChoices; track choice.days) {
                  <option [value]="'' + choice.days">{{ choice.label }}</option>
                }
              </hlm-native-select>
              <p hlmFieldDescription data-testid="token-expiry-date">
                It stops working on <app-time [value]="expiresOn()" mode="day" /> unless you renew
                it; 90 days is the most. You can revoke it at any time.
              </p>
            </div>

            @if (isLocal()) {
              <div hlmField>
                <label hlmFieldLabel for="token-current-password">Current password</label>
                <input
                  hlmInput
                  id="token-current-password"
                  type="password"
                  autocomplete="current-password"
                  [formField]="tokenForm.currentPassword"
                />
                <p hlmFieldDescription>A token acts as you, so creating one asks for it.</p>
                <hlm-field-error>{{ firstError(tokenForm.currentPassword) }}</hlm-field-error>
              </div>
            } @else if (signInAgain()) {
              <div hlmAlert data-testid="sign-in-again">
                <ng-icon name="lucideCircleAlert" aria-hidden="true" />
                <h3 hlmAlertTitle>Sign in again first</h3>
                <div hlmAlertDescription class="flex flex-col items-start gap-2">
                  <p>
                    A token acts as you, so creating one needs a sign-in of the last 10 minutes.
                    Sign in again, then create it: you come back to this page.
                  </p>
                  <button
                    hlmBtn
                    size="sm"
                    type="button"
                    data-testid="sign-in-again-button"
                    (click)="reSignIn()"
                  >
                    Sign in again
                  </button>
                </div>
              </div>
            }

            <p
              class="text-muted-foreground flex items-start gap-2 text-xs"
              data-testid="owner-notice-before"
            >
              <ng-icon name="lucideEye" class="mt-0.5 shrink-0" aria-hidden="true" />
              <span>
                Conversations you start with this token are personal: agents act with your own
                accounts and budget. The Owners of each company you start them in can read them in
                full; each read is recorded and shown to you.
              </span>
            </p>
          </form>

          <hlm-dialog-footer>
            <button hlmBtn variant="outline" type="button" hlmDialogClose>Cancel</button>
            <button
              hlmBtn
              type="submit"
              form="create-token-form"
              [disabled]="tokenForm().submitting()"
            >
              @if (tokenForm().submitting()) {
                <hlm-spinner aria-hidden="true" />
              }
              Create token
            </button>
          </hlm-dialog-footer>
        }
      </hlm-dialog-content>
    </hlm-dialog>
  `,
})
export class CreateTokenDialog {
  private readonly api = inject(Api);
  private readonly session = inject(Session);
  private readonly router = inject(Router);

  readonly open = model(false);
  readonly tokenCreated = output<PersonalAccessToken>();

  protected readonly firstError = firstError;
  protected readonly scopes = TOKEN_SCOPES;
  protected readonly expiryChoices = EXPIRY_CHOICES;

  protected readonly model = signal({ ...EMPTY });
  protected readonly tokenForm = form(this.model, (path) => {
    required(path.name, { message: 'Enter a name.' });
    maxLength(path.name, 100, { message: 'At most 100 characters.' });
    validate(path.name, ({ value }) =>
      value() && !value().trim() ? { kind: 'blank', message: 'Enter a name.' } : undefined,
    );
    maxLength(path.currentPassword, 1024);
    validate(path.currentPassword, ({ value }) =>
      this.isLocal() && !value()
        ? { kind: 'required', message: 'Enter your current password.' }
        : undefined,
    );
  });
  protected readonly formError = signal<string | null>(null);

  /** A local user gives their password; an identity provider's user a recent sign-in. */
  protected readonly isLocal = computed(() => this.session.user()?.authSource === 'LOCAL');
  protected readonly memberships = this.session.memberships;
  /** Every company of the user (`companyIds` absent), or only those chosen. */
  protected readonly allCompanies = signal(true);
  protected readonly chosen = signal<readonly string[]>([]);
  protected readonly companiesError = signal(false);
  /** `403 RECENT_SIGN_IN_REQUIRED`: the sign-in is too old to create a token. */
  protected readonly signInAgain = signal(false);
  /** When a token made now expires, by the choice. */
  protected readonly expiresOn = computed(() => expiryDate(this.model().expiryDays));

  /** Set after a successful create; cleared when the dialog closes. */
  protected readonly result = signal<{
    token: PersonalAccessToken;
    secret: string;
    ownerNotice: TokenOwnerNotice;
  } | null>(null);

  protected readonly ownerNames = ownerNames;

  constructor() {
    effect(() => {
      if (this.open()) {
        untracked(() => {
          this.result.set(null);
          this.model.set({ ...EMPTY });
          this.tokenForm().reset();
          this.formError.set(null);
          this.allCompanies.set(true);
          this.chosen.set(this.memberships().map((m) => m.companyId));
          this.companiesError.set(false);
          this.signInAgain.set(false);
        });
      }
    });
  }

  protected setScope(scope: TokenScope): void {
    this.model.update((m) => ({ ...m, scope }));
  }

  protected setExpiry(value: string | null | undefined): void {
    const days = Number(value);
    this.model.update((m) => ({
      ...m,
      expiryDays:
        Number.isFinite(days) && days > 0 ? Math.min(days, MAX_EXPIRY_DAYS) : MAX_EXPIRY_DAYS,
    }));
  }

  protected toggleCompany(companyId: string, on: boolean): void {
    this.chosen.update((ids) =>
      on
        ? [...ids.filter((id) => id !== companyId), companyId]
        : ids.filter((id) => id !== companyId),
    );
    this.companiesError.set(false);
  }

  /**
   * Signs out and opens the sign-in page with `returnUrl=/me`: a local sign-in navigates back, and
   * a sign-in with an identity provider names `/me` to the server, which returns there (Q44).
   */
  protected async reSignIn(): Promise<void> {
    await this.session.logout();
    this.open.set(false);
    await this.router.navigateByUrl('/login?returnUrl=%2Fme');
  }

  protected close(): void {
    this.open.set(false);
    // Forget the token: it is never shown again.
    this.result.set(null);
  }

  protected onSubmit(event: Event): void {
    event.preventDefault();
    this.formError.set(null);
    const companyIds =
      this.allCompanies() || this.memberships().length < 2
        ? null
        : this.memberships()
            .map((m) => m.companyId)
            .filter((id) => this.chosen().includes(id));
    if (companyIds && !companyIds.length) {
      this.companiesError.set(true);
      return;
    }
    void submit(this.tokenForm, async () => {
      const { name, scope, expiryDays, currentPassword } = this.model();
      try {
        const result = await this.api.invoke(createOwnToken, {
          body: {
            name: name.trim(),
            scope,
            expiresAt: tokenExpiresAt(expiryDays),
            ...(companyIds ? { companyIds } : {}),
            ...(this.isLocal() ? { currentPassword } : {}),
          },
        });
        this.result.set({
          token: result.personalAccessToken,
          secret: result.token,
          ownerNotice: result.ownerNotice,
        });
        this.tokenCreated.emit(result.personalAccessToken);
        return undefined;
      } catch (error) {
        if (hasProblemCode(error, 'RECENT_SIGN_IN_REQUIRED')) {
          this.signInAgain.set(true);
          return undefined;
        }
        const failure = mapSubmissionError(this.tokenForm, error, {
          codeFields: { WRONG_PASSWORD: 'currentPassword' },
        });
        this.formError.set(failure.message);
        return failure.fieldErrors;
      }
    });
  }
}
