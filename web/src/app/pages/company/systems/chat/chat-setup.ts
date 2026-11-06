import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import { form, FormField, maxLength, pattern, required, submit } from '@angular/forms/signals';
import { NgIcon, provideIcons } from '@ng-icons/core';
import { lucideCircleAlert, lucideDownload, lucideKeyRound, lucidePencil } from '@ng-icons/lucide';
import { HlmAlertImports } from '@spartan-ng/helm/alert';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmDialogImports } from '@spartan-ng/helm/dialog';
import { HlmFieldImports } from '@spartan-ng/helm/field';
import { HlmInput } from '@spartan-ng/helm/input';
import { HlmSpinner } from '@spartan-ng/helm/spinner';
import { Api } from '../../../../api/generated/api';
import { getChatManifest, setChatSecrets } from '../../../../api/generated/functions';
import type {
  ChatManifest,
  ChatSecretsRequest,
  ExternalSystem,
} from '../../../../api/generated/models';
import { mapSubmissionError } from '../../../../api/problem';
import { Notify } from '../../../../core/notify';
import { CopyButton } from '../../../../shared/copy-button';
import { firstError } from '../../../../shared/forms';
import { CHAT_SYSTEM_MESSAGES, chatPlatform } from './chat-words';

type SecretName = 'signingSecret' | 'appToken';

/** One of a Slack system's own secrets (package 5, §3.1): what it is, and which transport needs it. */
export interface SecretRow {
  readonly name: SecretName;
  readonly label: string;
  readonly set: boolean;
  readonly needed: boolean;
  /** What it is and where Slack shows it. */
  readonly hint: string;
}

/** The secrets of a Slack system, the one its transport needs first. */
export function secretRows(system: ExternalSystem): SecretRow[] {
  const chat = system.chat;
  const socket = chat?.transport === 'SOCKET_MODE';
  const rows: SecretRow[] = [
    {
      name: 'signingSecret',
      label: 'Signing secret',
      set: !!chat?.signingSecretSet,
      needed: !socket,
      hint: 'Basic Information › App Credentials › Signing Secret. Tinfirm checks every event Slack posts with it.',
    },
    {
      name: 'appToken',
      label: 'App-level token',
      set: !!chat?.appTokenSet,
      needed: socket,
      hint: 'Basic Information › App-Level Tokens: an xapp- token with connections:write. Tinfirm opens its connection to Slack with it.',
    },
  ];
  return socket ? rows.reverse() : rows;
}

/** One URL to set on the platform, with where it goes. */
export interface PlatformUrl {
  readonly label: string;
  readonly value: string;
  readonly where: string;
}

/** The URLs the admin sets on the platform: Slack's Events API's two, Teams' messaging endpoint. */
export function platformUrls(system: ExternalSystem): PlatformUrl[] {
  const chat = system.chat;
  if (!chat) return [];
  if (system.kind === 'TEAMS') {
    return chat.messagingUrl
      ? [
          {
            label: 'Messaging endpoint',
            value: chat.messagingUrl,
            where: 'The Azure Bot’s Configuration › Messaging endpoint.',
          },
        ]
      : [];
  }
  if (chat.transport === 'SOCKET_MODE') return [];
  const urls: PlatformUrl[] = [];
  if (chat.eventsUrl) {
    urls.push({
      label: 'Events request URL',
      value: chat.eventsUrl,
      where:
        'Event Subscriptions › Request URL. Store the signing secret first: Slack checks it at once.',
    });
  }
  if (chat.interactionsUrl) {
    urls.push({
      label: 'Interactivity request URL',
      value: chat.interactionsUrl,
      where: 'Interactivity & Shortcuts › Request URL, for the buttons of questions and approvals.',
    });
  }
  return urls;
}

/** The manifest as a file to save: Slack's JSON, or Teams' zip from its base64. */
export function manifestFile(manifest: ChatManifest): Blob {
  if (manifest.appPackage) {
    const bytes = Uint8Array.from(atob(manifest.appPackage), (c) => c.charCodeAt(0));
    return new Blob([bytes], { type: 'application/zip' });
  }
  return new Blob([JSON.stringify(manifest.manifest ?? {}, null, 2)], {
    type: 'application/json',
  });
}

/**
 * How a chat system is installed (package 5, §5.1): the app made from Tinfirm's manifest (Slack) or
 * its app package (Teams), the URLs to set on the platform, how Slack's events arrive, and Slack's
 * own secrets, written and never shown again. The bot is its own card (`app-chat-bot`).
 */
@Component({
  selector: 'app-chat-setup',
  imports: [
    FormField,
    NgIcon,
    HlmAlertImports,
    HlmButton,
    HlmDialogImports,
    HlmFieldImports,
    HlmInput,
    HlmSpinner,
    CopyButton,
  ],
  providers: [provideIcons({ lucideCircleAlert, lucideDownload, lucideKeyRound, lucidePencil })],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'block' },
  template: `
    @let s = system();
    <section
      class="bg-card overflow-hidden rounded-xl border"
      aria-labelledby="chat-setup-title"
      data-testid="chat-setup"
    >
      <div class="flex flex-wrap items-start gap-3 px-4 py-4 sm:px-5 sm:pt-5">
        <div class="flex min-w-0 flex-1 basis-64 flex-col gap-0.5">
          <h2 id="chat-setup-title" class="text-base font-bold">{{ platform() }} app</h2>
          <p class="text-muted-foreground text-sm text-pretty">
            @if (s.kind === 'TEAMS') {
              Upload Tinfirm's app package to Teams, and point the Azure Bot at Tinfirm. Teams
              always reaches in: Tinfirm must be reachable from the internet.
            } @else {
              Create the Slack app from Tinfirm's manifest, then install it in your workspace.
            }
          </p>
        </div>
        @if (canManage()) {
          <button
            hlmBtn
            variant="outline"
            size="sm"
            class="max-sm:h-11"
            type="button"
            data-testid="download-manifest"
            [disabled]="downloading()"
            (click)="download()"
          >
            @if (downloading()) {
              <hlm-spinner aria-hidden="true" />
            } @else {
              <ng-icon name="lucideDownload" aria-hidden="true" />
            }
            {{ s.kind === 'TEAMS' ? 'Download the app package' : 'Download the manifest' }}
          </button>
        }
      </div>

      @if (s.kind === 'SLACK') {
        <div
          class="flex flex-wrap items-center gap-x-3 gap-y-1 border-t px-4 py-3.5 text-sm sm:px-5"
          data-testid="chat-transport"
        >
          <p class="text-muted-foreground">
            Events arrive by
            <strong class="text-foreground font-semibold">{{ transportLabel() }}</strong>
          </p>
          @if (canManage()) {
            <button
              hlmBtn
              variant="ghost"
              size="xs"
              class="max-sm:h-11"
              type="button"
              data-testid="change-transport"
              (click)="changeTransport.emit()"
            >
              <ng-icon name="lucidePencil" aria-hidden="true" />
              Change
            </button>
          }
          <p class="text-muted-foreground basis-full text-[0.8125rem] text-pretty">
            @if (s.chat?.transport === 'SOCKET_MODE') {
              Tinfirm opens the connection to Slack with the app-level token: no URL to set, nothing
              reaches in. Turn Socket Mode on in the app's settings.
            } @else {
              Slack posts each event to the URLs below, signed with the app's signing secret.
            }
          </p>
        </div>
      }

      @if (canManage() && urls().length) {
        <div data-testid="chat-urls">
          @for (url of urls(); track url.label; let i = $index) {
            <div
              class="grid gap-x-8 gap-y-2 border-t px-4 py-3.5 sm:grid-cols-[minmax(0,15rem)_minmax(0,1fr)] sm:px-5"
            >
              <div class="min-w-0">
                <span class="block text-sm font-semibold" [id]="'chat-url-' + i">{{
                  url.label
                }}</span>
                <p class="text-muted-foreground mt-0.5 text-[0.8125rem] text-pretty">
                  {{ url.where }}
                </p>
              </div>
              <div class="flex min-w-0 items-center gap-1.5">
                <code
                  class="bg-muted/50 min-w-0 flex-1 rounded-lg border px-2.5 py-1.5 font-mono text-xs break-all select-all"
                  [attr.aria-labelledby]="'chat-url-' + i"
                  [attr.data-testid]="'chat-url-' + i"
                  >{{ url.value }}</code
                >
                <app-copy-button [value]="url.value" [ariaLabel]="'Copy the ' + url.label" />
              </div>
            </div>
          }
        </div>
      }

      @if (s.kind === 'SLACK') {
        <div class="border-t" data-testid="chat-secrets">
          <h3 class="px-4 pt-4 pb-2.5 text-sm font-bold sm:px-5" id="chat-secrets-title">
            Secrets
          </h3>
          <ul aria-labelledby="chat-secrets-title">
            @for (row of secrets(); track row.name) {
              <li
                class="flex flex-wrap items-start gap-x-3 gap-y-1 border-t px-4 py-3 sm:px-5"
                [attr.data-testid]="'secret-' + row.name"
                [attr.data-set]="row.set"
              >
                <div class="flex min-w-0 flex-1 basis-64 flex-col gap-0.5">
                  <span class="flex flex-wrap items-center gap-2 text-sm">
                    <span class="font-semibold">{{ row.label }}</span>
                    <span
                      class="inline-flex h-5 items-center rounded-full border px-2 text-xs font-semibold"
                      [class]="
                        row.set
                          ? 'text-success border-success/40'
                          : 'text-muted-foreground bg-muted'
                      "
                      >{{ row.set ? 'Stored' : 'Not stored' }}</span
                    >
                    @if (!row.needed) {
                      <span class="text-muted-foreground text-xs">
                        Not needed with {{ transportLabel() }}
                      </span>
                    }
                  </span>
                  <span class="text-muted-foreground text-xs text-pretty">{{ row.hint }}</span>
                </div>
                @if (canManage()) {
                  <button
                    hlmBtn
                    size="sm"
                    class="max-sm:h-11"
                    type="button"
                    [variant]="row.needed && !row.set ? 'default' : 'ghost'"
                    [attr.data-testid]="'set-' + row.name"
                    (click)="openSecret(row)"
                  >
                    <ng-icon name="lucideKeyRound" aria-hidden="true" />
                    {{ row.set ? 'Replace' : 'Store' }}
                  </button>
                }
              </li>
            }
          </ul>
        </div>
      }
    </section>

    <hlm-dialog [state]="secretOpen() ? 'open' : 'closed'" (closed)="closeSecret()">
      <hlm-dialog-content *hlmDialogPortal="let ctx" class="sm:max-w-md">
        <hlm-dialog-header>
          <h2 hlmDialogTitle>
            {{ secretFor()?.set ? 'Replace' : 'Store' }} the {{ secretFor()?.label?.toLowerCase() }}
          </h2>
          <p hlmDialogDescription>
            {{ secretFor()?.hint }} Tinfirm keeps it in its vault and never shows it again.
          </p>
        </hlm-dialog-header>
        <form id="secret-form" class="flex flex-col gap-4" novalidate (submit)="onSecret($event)">
          @if (secretError(); as message) {
            <div hlmAlert variant="destructive" data-testid="secret-error">
              <ng-icon name="lucideCircleAlert" aria-hidden="true" />
              <p hlmAlertDescription>{{ message }}</p>
            </div>
          }
          <div hlmField>
            <label hlmFieldLabel for="chat-secret">{{ secretFor()?.label }}</label>
            <input
              hlmInput
              id="chat-secret"
              type="password"
              class="font-mono"
              autocomplete="off"
              spellcheck="false"
              [placeholder]="secretFor()?.name === 'appToken' ? 'xapp-…' : ''"
              [formField]="secretForm.value"
            />
            <hlm-field-error>{{ firstError(secretForm.value) }}</hlm-field-error>
          </div>
        </form>
        <hlm-dialog-footer>
          <button hlmBtn variant="outline" type="button" hlmDialogClose>Cancel</button>
          <button
            hlmBtn
            type="submit"
            form="secret-form"
            data-testid="secret-save"
            [disabled]="secretForm().submitting()"
          >
            @if (secretForm().submitting()) {
              <hlm-spinner aria-hidden="true" />
            }
            Store
          </button>
        </hlm-dialog-footer>
      </hlm-dialog-content>
    </hlm-dialog>
  `,
})
export class ChatSetup {
  private readonly api = inject(Api);
  private readonly notify = inject(Notify);

  readonly companyId = input.required<string>();
  readonly system = input.required<ExternalSystem>();
  readonly canManage = input(false);
  /** The system after a secret was stored. */
  readonly changed = output<ExternalSystem>();
  /** "Change" beside the transport: the page opens its change dialog, which holds it. */
  readonly changeTransport = output<void>();

  protected readonly firstError = firstError;
  protected readonly platform = computed(() => chatPlatform(this.system().kind));
  protected readonly urls = computed(() => platformUrls(this.system()));
  protected readonly secrets = computed(() => secretRows(this.system()));
  protected readonly transportLabel = computed(() =>
    this.system().chat?.transport === 'SOCKET_MODE' ? 'Socket Mode' : 'Events API',
  );

  // --- The manifest -------------------------------------------------------------------------

  protected readonly downloading = signal(false);

  protected async download(): Promise<void> {
    if (this.downloading()) return;
    this.downloading.set(true);
    try {
      const manifest = await this.api.invoke(getChatManifest, {
        companyId: this.companyId(),
        systemId: this.system().id,
      });
      const url = URL.createObjectURL(manifestFile(manifest));
      const link = document.createElement('a');
      link.href = url;
      link.download = manifest.fileName;
      link.click();
      setTimeout(() => URL.revokeObjectURL(url), 1000);
    } catch (error) {
      this.notify.error('Couldn’t download it', error, CHAT_SYSTEM_MESSAGES);
    } finally {
      this.downloading.set(false);
    }
  }

  // --- A secret -----------------------------------------------------------------------------

  protected readonly secretOpen = signal(false);
  /** The secret in the dialog; kept after closing so that its text does not blank out. */
  protected readonly secretFor = signal<SecretRow | null>(null);
  protected readonly secretModel = signal({ value: '' });
  protected readonly secretError = signal<string | null>(null);
  protected readonly secretForm = form(this.secretModel, (path) => {
    required(path.value, { message: 'Paste it.' });
    maxLength(path.value, 500, { message: 'At most 500 characters.' });
    pattern(path.value, /^xapp-\S+$/, {
      message: 'An app-level token starts with xapp-.',
      when: () => this.secretFor()?.name === 'appToken',
    });
  });

  protected openSecret(row: SecretRow): void {
    this.secretFor.set(row);
    this.secretModel.set({ value: '' });
    this.secretForm().reset();
    this.secretError.set(null);
    this.secretOpen.set(true);
  }

  protected closeSecret(): void {
    this.secretOpen.set(false);
    this.secretModel.set({ value: '' });
  }

  protected onSecret(event: Event): void {
    event.preventDefault();
    const row = this.secretFor();
    if (!row) return;
    this.secretError.set(null);
    void submit(this.secretForm, async () => {
      const body: ChatSecretsRequest = { [row.name]: this.secretModel().value.trim() };
      try {
        const saved = await this.api.invoke(setChatSecrets, {
          companyId: this.companyId(),
          systemId: this.system().id,
          body,
        });
        this.closeSecret();
        this.changed.emit(saved);
        this.notify.success(`The ${row.label.toLowerCase()} is stored`);
        return undefined;
      } catch (error) {
        const failure = mapSubmissionError(this.secretForm, error, {
          overrides: CHAT_SYSTEM_MESSAGES,
        });
        this.secretError.set(failure.message);
        return failure.fieldErrors;
      }
    });
  }
}
