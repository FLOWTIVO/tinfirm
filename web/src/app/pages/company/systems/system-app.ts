import { NgTemplateOutlet } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import { form, FormField, maxLength, required, submit } from '@angular/forms/signals';
import { NgIcon, provideIcons } from '@ng-icons/core';
import {
  lucideCircleAlert,
  lucideCircleCheck,
  lucideKeyRound,
  lucidePencil,
  lucideTrash2,
} from '@ng-icons/lucide';
import { HlmAlertImports } from '@spartan-ng/helm/alert';
import { HlmAlertDialogImports } from '@spartan-ng/helm/alert-dialog';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmDialogImports } from '@spartan-ng/helm/dialog';
import { HlmFieldImports } from '@spartan-ng/helm/field';
import { HlmInput } from '@spartan-ng/helm/input';
import { HlmSpinner } from '@spartan-ng/helm/spinner';
import { Api } from '../../../api/generated/api';
import { removeSystemApp, setSystemApp } from '../../../api/generated/functions';
import type { ExternalSystem, SystemAppRequest } from '../../../api/generated/models';
import { mapSubmissionError, problemMessage, toProblem } from '../../../api/problem';
import { Notify } from '../../../core/notify';
import { CopyButton } from '../../../shared/copy-button';
import { firstError } from '../../../shared/forms';
import { SettingRow } from '../settings/setting-row';
import { callbackUrlFor, SLACK_HISTORY_SCOPES, systemKind } from './system-kinds';

const FORBIDDEN = { 'status:403': 'Only owners and admins can change the company’s systems.' };

interface AppModel {
  clientId: string;
  /** Write-only: empty keeps the stored one while the client ID stays. */
  clientSecret: string;
  scopes: string;
}

/**
 * What a save of the app sends: the client ID, a typed secret, the scopes when given; and, once the
 * server said that an MCP server's new endpoints remove the accounts that signed in, the
 * confirmation.
 */
export function appRequest(m: AppModel, confirmEndpointsChange = false): SystemAppRequest {
  const body: SystemAppRequest = {};
  if (m.clientId.trim()) body.clientId = m.clientId.trim();
  if (m.clientSecret) body.clientSecret = m.clientSecret;
  if (m.scopes.trim()) body.scopes = m.scopes.trim();
  if (confirmEndpointsChange) body.confirmEndpointsChange = true;
  return body;
}

/**
 * A system's app, its one OAuth client (the web plan §5, Systems): with it, people and service
 * identities connect their accounts by signing in at the system; without it, by token only. The
 * callback URL to register at the system, as the server says it, is there to copy before the app
 * exists. A tracker's endpoints are fixed per kind, so it takes a client ID and secret; an MCP
 * server's are discovered, and without a client ID Tinfirm uses its client metadata document or
 * registers itself, as the server is added: one without an app offered neither then, or was added
 * before. The secret is write-only. Git hosts have no app. Slack's app is the one its manifest
 * installed: its client ID and secret, for the sign-ins of `history` only, its user scopes fixed
 * and its callback URL already in the manifest (the package 5 contract, open point 13). One row a
 * setting, as on the settings pages; the page's health strip opens the dialog too (`openEdit`).
 */
@Component({
  selector: 'app-system-app',
  imports: [
    FormField,
    NgTemplateOutlet,
    NgIcon,
    HlmAlertImports,
    HlmAlertDialogImports,
    HlmButton,
    HlmDialogImports,
    HlmFieldImports,
    HlmInput,
    HlmSpinner,
    CopyButton,
    SettingRow,
  ],
  providers: [
    provideIcons({
      lucideCircleAlert,
      lucideCircleCheck,
      lucideKeyRound,
      lucidePencil,
      lucideTrash2,
    }),
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'block' },
  template: `
    @let s = system();
    <section
      class="bg-card overflow-hidden rounded-xl border"
      aria-labelledby="app-title"
      data-testid="system-app"
      [attr.data-has-app]="hasApp()"
    >
      <div class="flex flex-wrap items-start gap-3 px-4 py-4 sm:px-5 sm:pt-5">
        <div class="flex min-w-0 flex-1 basis-64 flex-col gap-0.5">
          <h2 id="app-title" class="text-base font-bold">Sign-in app</h2>
          <p class="text-muted-foreground text-sm text-pretty" data-testid="app-state">
            @if (slack()) {
              @if (hasApp()) {
                People, and the company’s credentials, sign in to {{ s.name }} with this app, so
                that agents read its channels as them.
              } @else {
                No app yet: nobody signs in to {{ s.name }}, so agents cannot read its channels.
              }
            } @else if (hasApp()) {
              Accounts on {{ s.name }} connect by signing in with this app, or with a token.
            } @else {
              No app yet: accounts on {{ s.name }} connect by token only.
            }
          </p>
        </div>
        @if (canManage()) {
          @if (s.app) {
            <span class="-me-2 flex flex-wrap gap-1">
              <button
                hlmBtn
                variant="ghost"
                size="sm"
                class="max-sm:h-11"
                type="button"
                (click)="openEdit()"
              >
                <ng-icon name="lucidePencil" aria-hidden="true" />
                Change
              </button>
              <button
                hlmBtn
                variant="ghost"
                size="sm"
                class="max-sm:h-11"
                type="button"
                data-testid="remove-app"
                (click)="removeOpen.set(true)"
              >
                <ng-icon name="lucideTrash2" aria-hidden="true" />
                Remove the app
              </button>
            </span>
          } @else {
            <button
              hlmBtn
              variant="outline"
              size="sm"
              class="max-sm:h-11"
              type="button"
              data-testid="set-app"
              (click)="openEdit()"
            >
              <ng-icon name="lucideKeyRound" aria-hidden="true" />
              Set up the app
            </button>
          }
        }
      </div>

      @if (canManage()) {
        @if (callbackUrl(); as callback) {
          <app-setting-row rowId="app-callback" label="Callback URL">
            <span help>
              @if (s.app) {
                Registered at {{ appWhere() }} with the app.
              } @else {
                <span data-testid="app-how"><ng-container *ngTemplateOutlet="how" /></span>
              }
            </span>
            <div class="flex w-full min-w-0 items-center gap-1.5">
              <code
                class="bg-muted/50 flex h-8 min-w-0 flex-1 items-center truncate rounded-lg border px-2.5 font-mono text-xs leading-8"
                aria-labelledby="app-callback-label"
                data-testid="app-callback"
                [title]="callback"
                >{{ callback }}</code
              >
              <app-copy-button [value]="callback" ariaLabel="Copy the callback URL" />
            </div>
          </app-setting-row>
        } @else if (!s.app) {
          <p class="border-t px-4 py-4 text-sm text-pretty sm:px-5" data-testid="app-how">
            <ng-container *ngTemplateOutlet="how" />
          </p>
        }

        @if (s.app; as app) {
          <app-setting-row rowId="app-client-id" label="Client ID" data-testid="app-client-id">
            <code class="font-mono text-[0.8125rem] break-all">{{ app.clientId }}</code>
          </app-setting-row>
          <app-setting-row
            rowId="app-client-secret"
            label="Client secret"
            data-testid="app-client-secret"
          >
            <span class="text-sm">
              @if (app.clientSecretSet) {
                Stored <span class="text-muted-foreground">· never shown again</span>
              } @else {
                None (a public client)
              }
            </span>
          </app-setting-row>
          <app-setting-row rowId="app-scopes" label="Scopes" data-testid="app-scopes">
            <span class="text-sm">
              @if (slack()) {
                <code class="font-mono break-all">{{ slackScopes }}</code
                >, as the manifest asks
              } @else if (app.scopes) {
                <code class="font-mono break-all">{{ app.scopes }}</code>
              } @else {
                The kind's defaults
              }
            </span>
          </app-setting-row>
          @if (app.authorizationEndpoint) {
            <app-setting-row rowId="app-endpoint" label="Sign-in at" data-testid="app-endpoint">
              <code class="font-mono text-[0.8125rem] break-all">{{
                app.authorizationEndpoint
              }}</code>
            </app-setting-row>
          }
        }
      }
    </section>

    <ng-template #how>
      @if (s.kind === 'MCP') {
        Tinfirm sets up the server's sign-in itself when it is added, where the server takes
        Tinfirm's client metadata document or lets it register. None is set up yet: set up the app
        with the client ID empty to look again, and it says what is missing. A server that needs a
        client of its own takes one registered at {{ appWhere() }} with this callback URL.
      } @else if (slack()) {
        The app installed from Tinfirm's manifest already has this callback URL. Copy its client ID
        and secret from {{ appWhere() }}, so that people can sign in to Slack for agents'
        <code class="font-mono">history</code> tool.
      } @else {
        Register an OAuth app at {{ appWhere() }} with this callback URL, then set its client ID and
        secret here.
      }
    </ng-template>

    <hlm-dialog [state]="editOpen() ? 'open' : 'closed'" (closed)="editOpen.set(false)">
      <hlm-dialog-content
        *hlmDialogPortal="let ctx"
        class="max-h-[92svh] overflow-y-auto sm:max-w-md"
      >
        <hlm-dialog-header>
          <h2 hlmDialogTitle>{{ s.app ? 'Change the app' : 'Set up the app' }}</h2>
          <p hlmDialogDescription>
            @if (callbackUrl(); as callback) {
              The callback URL to register at {{ appWhere() }} is
              <code class="font-mono break-all">{{ callback }}</code
              >.
            } @else {
              The client Tinfirm has at {{ appWhere() }}.
            }
          </p>
        </hlm-dialog-header>
        <form id="app-form" class="flex flex-col gap-4" novalidate (submit)="onSubmit($event)">
          @if (formError(); as message) {
            <div hlmAlert variant="destructive" data-testid="app-form-error">
              <ng-icon name="lucideCircleAlert" aria-hidden="true" />
              <p hlmAlertDescription>{{ message }}</p>
            </div>
          }
          <div hlmField>
            <label hlmFieldLabel for="app-client-id">Client ID</label>
            <input
              hlmInput
              id="app-client-id"
              class="font-mono"
              autocomplete="off"
              spellcheck="false"
              [formField]="appForm.clientId"
            />
            @if (s.kind === 'MCP') {
              <p hlmFieldDescription>Empty: Tinfirm's client metadata document, or it registers.</p>
            }
            <hlm-field-error>{{ firstError(appForm.clientId) }}</hlm-field-error>
          </div>
          <div hlmField>
            <label hlmFieldLabel for="app-client-secret">Client secret</label>
            <input
              hlmInput
              id="app-client-secret"
              type="password"
              autocomplete="new-password"
              [formField]="appForm.clientSecret"
            />
            <p hlmFieldDescription>
              @if (s.app?.clientSecretSet) {
                Empty keeps the stored secret while the client ID stays. It is never shown again.
              } @else if (slack()) {
                Slack's sign-in needs it. It is never shown again.
              } @else {
                Empty for a public client. It is never shown again.
              }
            </p>
            <hlm-field-error>{{ firstError(appForm.clientSecret) }}</hlm-field-error>
          </div>
          @if (!slack()) {
            <div hlmField>
              <label hlmFieldLabel for="app-scopes">Scopes</label>
              <input
                hlmInput
                id="app-scopes"
                class="font-mono"
                autocomplete="off"
                spellcheck="false"
                [formField]="appForm.scopes"
              />
              <p hlmFieldDescription>Space-separated. Empty: the kind's defaults.</p>
              <hlm-field-error>{{ firstError(appForm.scopes) }}</hlm-field-error>
            </div>
          }
        </form>
        <hlm-dialog-footer>
          <button hlmBtn variant="outline" type="button" hlmDialogClose>Cancel</button>
          <button
            hlmBtn
            type="submit"
            form="app-form"
            data-testid="app-save"
            [disabled]="appForm().submitting()"
          >
            @if (appForm().submitting()) {
              <hlm-spinner aria-hidden="true" />
            }
            @if (endpointsChanged()) {
              Remove them and save
            } @else {
              Save the app
            }
          </button>
        </hlm-dialog-footer>
      </hlm-dialog-content>
    </hlm-dialog>

    <hlm-alert-dialog [state]="removeOpen() ? 'open' : 'closed'" (closed)="removeOpen.set(false)">
      <hlm-alert-dialog-content *hlmAlertDialogPortal="let ctx">
        <hlm-alert-dialog-header>
          <h2 hlmAlertDialogTitle>Remove the app of {{ s.name }}?</h2>
          <p hlmAlertDialogDescription>
            @if (slack()) {
              Nobody signs in to {{ s.name }} with it any more. Accounts that signed in stay until
              Slack ends their sign-in, then ask to be connected again.
            } @else {
              Nobody signs in with it any more; accounts connect by token. Accounts that signed in
              stay until their sign-in expires, then ask to be connected again.
            }
          </p>
        </hlm-alert-dialog-header>
        @if (removeError(); as message) {
          <div hlmAlert variant="destructive">
            <ng-icon name="lucideCircleAlert" aria-hidden="true" />
            <p hlmAlertDescription>{{ message }}</p>
          </div>
        }
        <hlm-alert-dialog-footer>
          <button hlmAlertDialogCancel [disabled]="removeBusy()">Cancel</button>
          <button
            hlmAlertDialogAction
            variant="destructive"
            [disabled]="removeBusy()"
            (click)="confirmRemove()"
          >
            @if (removeBusy()) {
              <hlm-spinner aria-hidden="true" />
            }
            Remove the app
          </button>
        </hlm-alert-dialog-footer>
      </hlm-alert-dialog-content>
    </hlm-alert-dialog>
  `,
})
export class SystemApp {
  private readonly api = inject(Api);
  private readonly notify = inject(Notify);

  readonly companyId = input.required<string>();
  readonly system = input.required<ExternalSystem>();
  readonly canManage = input(false);
  /** The system after a change of its app. */
  readonly changed = output<ExternalSystem>();

  protected readonly firstError = firstError;
  /** Whoever manages it sees the app; anyone else sees whether there is one. */
  protected readonly hasApp = computed(() => !!this.system().app || this.system().oauthAvailable);
  protected readonly appWhere = computed(() => systemKind(this.system().kind).appWhere);
  /** Slack's app: a client ID and a secret, its scopes fixed by the manifest. */
  protected readonly slack = computed(() => this.system().kind === 'SLACK');
  protected readonly slackScopes = SLACK_HISTORY_SCOPES;
  protected readonly callbackUrl = computed(() => callbackUrlFor(this.system()));

  // --- Set or change ------------------------------------------------------------------------

  protected readonly editOpen = signal(false);
  protected readonly model = signal<AppModel>({ clientId: '', clientSecret: '', scopes: '' });
  protected readonly formError = signal<string | null>(null);
  /**
   * The server said that the MCP server's new token or revocation endpoint removes the accounts and
   * credentials that signed in (409 SYSTEM_APP_ENDPOINTS_CHANGED): the next save confirms it.
   */
  protected readonly endpointsChanged = signal(false);
  protected readonly appForm = form(this.model, (path) => {
    required(path.clientId, {
      message: 'Enter the client ID the system gave the app.',
      when: () => this.system().kind !== 'MCP',
    });
    // Slack's token endpoint takes a confidential client only: a secret, unless one is stored for
    // this client ID.
    required(path.clientSecret, {
      message: 'Enter the client secret from the Slack app’s Basic Information page.',
      when: ({ valueOf }) => {
        const app = this.system().app;
        return (
          this.slack() && !(app?.clientSecretSet && app.clientId === valueOf(path.clientId).trim())
        );
      },
    });
    maxLength(path.clientId, 500, { message: 'At most 500 characters.' });
    maxLength(path.clientSecret, 2000, { message: 'At most 2000 characters.' });
    maxLength(path.scopes, 2000, { message: 'At most 2000 characters.' });
  });

  /** Opens the app's dialog: to set it up, or to change it. */
  openEdit(): void {
    const app = this.system().app;
    this.model.set({ clientId: app?.clientId ?? '', clientSecret: '', scopes: app?.scopes ?? '' });
    this.appForm().reset();
    this.formError.set(null);
    this.endpointsChanged.set(false);
    this.editOpen.set(true);
  }

  protected onSubmit(event: Event): void {
    event.preventDefault();
    this.formError.set(null);
    void submit(this.appForm, async () => {
      try {
        const saved = await this.api.invoke(setSystemApp, {
          companyId: this.companyId(),
          systemId: this.system().id,
          body: appRequest(
            this.slack() ? { ...this.model(), scopes: '' } : this.model(),
            this.endpointsChanged(),
          ),
        });
        this.editOpen.set(false);
        this.changed.emit(saved);
        this.notify.success(`The app of ${saved.name} is set`, 'Accounts on it can sign in now.');
        return undefined;
      } catch (error) {
        const problem = toProblem(error);
        // The discovery's or the registration's own reason, or what new endpoints remove, says more
        // than the code.
        const code = problem.code;
        const overrides =
          (code === 'OAUTH_NOT_AVAILABLE' || code === 'SYSTEM_APP_ENDPOINTS_CHANGED') &&
          problem.detail
            ? { ...FORBIDDEN, [code]: problem.detail }
            : FORBIDDEN;
        this.endpointsChanged.set(code === 'SYSTEM_APP_ENDPOINTS_CHANGED');
        const failure = mapSubmissionError(this.appForm, error, { overrides });
        this.formError.set(failure.message);
        return failure.fieldErrors;
      }
    });
  }

  // --- Remove -------------------------------------------------------------------------------

  protected readonly removeOpen = signal(false);
  protected readonly removeBusy = signal(false);
  protected readonly removeError = signal<string | null>(null);

  protected async confirmRemove(): Promise<void> {
    if (this.removeBusy()) return;
    const system = this.system();
    this.removeBusy.set(true);
    this.removeError.set(null);
    try {
      await this.api.invoke(removeSystemApp, { companyId: this.companyId(), systemId: system.id });
      this.removeOpen.set(false);
      this.changed.emit({ ...system, app: null, oauthAvailable: false });
      this.notify.success(`The app of ${system.name} is removed`);
    } catch (error) {
      this.removeError.set(problemMessage(error, FORBIDDEN));
    } finally {
      this.removeBusy.set(false);
    }
  }
}
