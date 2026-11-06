import {
  afterNextRender,
  ChangeDetectionStrategy,
  Component,
  computed,
  DOCUMENT,
  effect,
  inject,
  Injector,
  input,
  model,
  output,
  signal,
  untracked,
} from '@angular/core';
import {
  form,
  FormField,
  maxLength,
  pattern,
  required,
  submit,
  validate,
} from '@angular/forms/signals';
import { NgIcon, provideIcons } from '@ng-icons/core';
import {
  lucideCheck,
  lucideChevronLeft,
  lucideChevronRight,
  lucideCircleAlert,
} from '@ng-icons/lucide';
import { HlmAlertImports } from '@spartan-ng/helm/alert';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmDialogImports } from '@spartan-ng/helm/dialog';
import { HlmFieldImports } from '@spartan-ng/helm/field';
import { HlmInput } from '@spartan-ng/helm/input';
import { HlmSpinner } from '@spartan-ng/helm/spinner';
import { Api } from '../../../api/generated/api';
import { createSystem, updateSystem } from '../../../api/generated/functions';
import type {
  ExternalSystem,
  SlackSystemSettings,
  SystemCreateRequest,
  SystemKind,
  SystemUpdateRequest,
  WebhookSystemSettings,
} from '../../../api/generated/models';
import { mapSubmissionError } from '../../../api/problem';
import { CurrentCompany } from '../../../core/current-company';
import { firstError } from '../../../shared/forms';
import { WebhookSecret } from './webhook/webhook-secret';
import {
  BASE_URL_PATTERN,
  HEADER_NAME_PATTERN,
  isChatKind,
  registrableKinds,
  POLL_INTERVAL,
  pollIntervalError,
  pollsIssues,
  systemKind,
} from './system-kinds';

type Transport = NonNullable<SlackSystemSettings['transport']>;

interface SystemModel {
  /** Empty until it is picked: the kind comes first (the web plan §5, Systems). */
  kind: SystemKind | '';
  name: string;
  baseUrl: string;
  authHeader: string;
  /** Slack: how its events arrive (package 5, §5.1). */
  transport: Transport;
  /** Teams: the tenant and the Azure Bot's Microsoft App ID, fixed once registered. */
  tenantId: string;
  appId: string;
  /** Webhook: how the sender proves a delivery is theirs, its header, and its deliveries a minute. */
  scheme: WebhookScheme;
  secretHeader: string;
  maxPerMinute: string;
  /** A tracker's poll interval in seconds, as typed; empty keeps the default. Named as the API names it. */
  pollIntervalSeconds: string;
  /** An MCP server's: false when it checks no credential, so runs need no account there (ADR-0071). */
  accountsRequired: boolean;
}

type WebhookScheme = 'STANDARD' | 'SECRET_HEADER';

const EMPTY: SystemModel = {
  kind: '',
  name: '',
  baseUrl: '',
  authHeader: '',
  transport: 'EVENTS_API',
  tenantId: '',
  appId: '',
  scheme: 'STANDARD',
  secretHeader: '',
  maxPerMinute: '',
  pollIntervalSeconds: '',
  accountsRequired: true,
};

/**
 * The steps of registering a system: its kind, its name and address, then the kind's own settings.
 * A change is one form.
 */
export type SystemStep = 'kind' | 'details' | 'settings';

const STEP_LABELS: Record<SystemStep, string> = {
  kind: 'Kind',
  details: 'Details',
  settings: 'Settings',
};

/** The fields a step can ask for: every field of the model but the choices made with radios. */
type SystemField = Exclude<keyof SystemModel, 'kind' | 'transport' | 'scheme' | 'accountsRequired'>;

/** Each field's input, which takes the focus when its step opens or its error is shown. */
const FIELD_IDS: Record<SystemField, string> = {
  name: 'system-name',
  baseUrl: 'system-base-url',
  authHeader: 'system-auth-header',
  tenantId: 'system-tenant-id',
  appId: 'system-app-id',
  secretHeader: 'webhook-header',
  maxPerMinute: 'webhook-rate',
  pollIntervalSeconds: 'system-poll-interval',
};

/** A kind with settings of its own is registered in three steps; GIT, which has none, in two. */
export function hasSettings(kind: SystemKind): boolean {
  return pollsIssues(kind) || systemKind(kind).authHeader || isChatKind(kind) || kind === 'WEBHOOK';
}

/** The fields a step asks for, in their order: Next checks these and no others. */
export function stepFields(
  step: SystemStep,
  m: Pick<SystemModel, 'kind' | 'scheme'>,
): SystemField[] {
  const kind = m.kind;
  if (!kind || step === 'kind') return [];
  const info = systemKind(kind);
  const fields: SystemField[] = [];
  if (step === 'details') {
    fields.push('name');
    if (info.baseUrl) fields.push('baseUrl');
    return fields;
  }
  if (kind === 'TEAMS') fields.push('tenantId', 'appId');
  if (kind === 'WEBHOOK') {
    if (m.scheme === 'SECRET_HEADER') fields.push('secretHeader');
    fields.push('maxPerMinute');
  }
  if (pollsIssues(kind)) fields.push('pollIntervalSeconds');
  if (info.authHeader) fields.push('authHeader');
  return fields;
}

/** What the settings step asks, under the title. */
function settingsDescription(kind: SystemKind): string {
  switch (kind) {
    case 'SLACK':
      return 'How Slack’s events reach Tinfirm.';
    case 'TEAMS':
      return 'The tenant and the bot it is registered with. Both are fixed once registered.';
    case 'WEBHOOK':
      return 'How the sender proves a delivery is theirs, and how many it may send.';
    case 'MCP':
      return 'How an account’s token reaches the server. Optional.';
    default:
      return 'How often Tinfirm checks it for changes. Optional: empty keeps the default.';
  }
}

/** The step indicator's pills, as the run page's phase chips (DESIGN.md): current, passed, to come. */
const STEP_PILL = {
  current: 'border-primary bg-primary text-primary-foreground pe-2.5',
  done: 'bg-muted text-primary pe-1 sm:pe-2.5',
  todo: 'border-input text-muted-foreground border-dashed pe-1 sm:pe-2.5',
} as const;

const STEP_DOT = {
  current: 'bg-primary-foreground/20',
  done: 'bg-primary/10',
  todo: 'border-input border',
} as const;

/** A webhook's two schemes (package 5, §5.12): the secret header is the weaker, and says so. */
export const SCHEMES: readonly { value: WebhookScheme; label: string; description: string }[] = [
  {
    value: 'STANDARD',
    label: 'Signed (Standard Webhooks)',
    description:
      'The sender signs each delivery with the secret, with its time. A captured delivery cannot be changed, and is refused after 5 minutes.',
  },
  {
    value: 'SECRET_HEADER',
    label: 'Secret in a header (weaker)',
    description:
      'For a sender that cannot sign: the secret itself goes in a header. Whoever captures one delivery can send any until the secret is rotated, and an old one can be sent again.',
  },
];

/** A GUID, as Entra and the Azure Bot write a tenant and an app id. */
const GUID_PATTERN =
  /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/;

/** Slack's two ways for events to arrive, with what each needs (package 5, §5.1). */
export const TRANSPORTS: readonly { value: Transport; label: string; description: string }[] = [
  {
    value: 'EVENTS_API',
    label: 'Events API',
    description:
      'Slack posts each event to Tinfirm, signed with the app’s signing secret. Tinfirm must be reachable from the internet.',
  },
  {
    value: 'SOCKET_MODE',
    label: 'Socket Mode',
    description:
      'Tinfirm opens a connection to Slack with an app-level token. Nothing reaches in: for a Tinfirm behind a firewall.',
  },
];

/** Messages for the problems of a create or a change, in the words of this dialog. */
export const SYSTEM_FORM_MESSAGES: Record<string, string> = {
  'status:403': 'Only owners and admins can register or change the company’s systems.',
};

/** What a change sends: only what differs, so an unchanged field is never audited. */
export function systemChanges(m: SystemModel, system: ExternalSystem): SystemUpdateRequest {
  const body: SystemUpdateRequest = {};
  if (m.name.trim() !== system.name) body.name = m.name.trim();
  if (system.kind === 'MCP' && movesEndpoint(m, system)) body.baseUrl = m.baseUrl.trim();
  if (system.kind === 'MCP' && m.authHeader.trim() !== (system.authHeader ?? '')) {
    body.authHeader = m.authHeader.trim();
  }
  if (system.kind === 'MCP' && m.accountsRequired !== system.accountsRequired) {
    body.accountsRequired = m.accountsRequired;
  }
  if (system.kind === 'SLACK' && m.transport !== system.chat?.transport) {
    body.slack = { transport: m.transport };
  }
  if (system.kind === 'WEBHOOK' && system.webhook) {
    const webhook: WebhookSystemSettings = {};
    if (m.scheme !== system.webhook.scheme) webhook.scheme = m.scheme;
    const header = m.scheme === 'SECRET_HEADER' ? m.secretHeader.trim() : '';
    if (header && header !== (system.webhook.secretHeader ?? '')) webhook.secretHeader = header;
    const max = m.maxPerMinute.trim() ? Number(m.maxPerMinute) : null;
    if (max !== null && max !== system.webhook.maxPerMinute) webhook.maxPerMinute = max;
    if (Object.keys(webhook).length) body.webhook = webhook;
  }
  const poll = m.pollIntervalSeconds.trim();
  if (pollsIssues(system.kind) && poll && Number(poll) !== system.pollIntervalSeconds) {
    body.pollIntervalSeconds = Number(poll);
  }
  return body;
}

/**
 * Whether the change gives an MCP server a new endpoint (ADR-0079): the address typed, without its
 * trailing slashes as the server stores it, differs from the one it has.
 */
export function movesEndpoint(m: SystemModel, system: ExternalSystem): boolean {
  const typed = m.baseUrl.trim().replace(/\/+$/, '');
  return system.kind === 'MCP' && typed !== '' && typed !== (system.baseUrl ?? '');
}

/**
 * Register a system, or change one (the web plan §5, Systems). A new system is registered in steps:
 * its kind (trackers, code hosts, MCP servers, chats, webhooks), then its name and the base URL
 * where the kind takes one, then the kind's own settings, a step GIT skips. Nobody types a handle,
 * the prefix of its tools in a run: the server makes it from the name and never changes it (the
 * rule of CONVENTIONS §15.2), so a rename keeps every tool's name. Next checks only the
 * step's fields; a refusal from the server opens the step of its field. A change is one form that
 * keeps the kind and the base URL, which never change (a token is sent only where it was
 * connected), except an MCP server's endpoint (ADR-0079), whose change removes every account and
 * credential on it, as the form warns. The parent owns `open` and gets the saved system.
 */
@Component({
  selector: 'app-system-dialog',
  imports: [
    FormField,
    NgIcon,
    HlmAlertImports,
    HlmButton,
    HlmDialogImports,
    HlmFieldImports,
    HlmInput,
    HlmSpinner,
    WebhookSecret,
  ],
  providers: [
    provideIcons({ lucideCheck, lucideChevronLeft, lucideChevronRight, lucideCircleAlert }),
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <hlm-dialog [state]="open() ? 'open' : 'closed'" (closed)="open.set(false)">
      <hlm-dialog-content
        *hlmDialogPortal="let ctx"
        class="max-h-[92svh] grid-rows-[auto_minmax(0,1fr)_auto] overflow-y-auto sm:max-w-lg"
        data-testid="system-dialog"
      >
        <hlm-dialog-header>
          <h2 hlmDialogTitle>
            {{
              created()
                ? created()!.name + ' is registered'
                : system()
                  ? 'Change ' + system()!.name
                  : 'Register a system'
            }}
          </h2>
          <p hlmDialogDescription>
            @if (created()) {
              Registered. The sender needs the URL and the secret.
            } @else if (system(); as s) {
              Its kind and address stay as they are: an account's token is sent only where it was
              connected.
            } @else {
              {{ stepDescription() }}
            }
          </p>
          @if (!created() && !system()) {
            <div class="mt-1 flex flex-wrap items-center gap-x-3 gap-y-1">
              <ol class="flex items-center gap-1.5" aria-label="Steps" data-testid="system-steps">
                @for (item of stepItems(); track item.key; let first = $first) {
                  <li
                    class="flex items-center gap-1.5"
                    [attr.aria-current]="item.state === 'current' ? 'step' : null"
                  >
                    @if (!first) {
                      <span class="bg-input h-px w-4" aria-hidden="true"></span>
                    }
                    <span
                      class="inline-flex h-6 items-center gap-1.5 rounded-full border ps-1 text-xs font-semibold"
                      [class]="item.pill"
                    >
                      <span
                        class="inline-flex size-4 items-center justify-center rounded-full font-mono text-[10px]"
                        [class]="item.dot"
                        aria-hidden="true"
                      >
                        @if (item.state === 'done') {
                          <ng-icon name="lucideCheck" size="10" />
                        } @else {
                          {{ item.number }}
                        }
                      </span>
                      <span [class]="item.labelClass">{{ item.label }}</span>
                      @if (item.state === 'done') {
                        <span class="sr-only">, done</span>
                      }
                    </span>
                  </li>
                }
              </ol>
              <span
                class="text-muted-foreground ms-auto text-xs font-semibold tabular-nums"
                aria-live="polite"
                data-testid="system-step-count"
                >{{ stepCount() }}</span
              >
            </div>
          }
        </hlm-dialog-header>

        @if (created(); as c) {
          <app-webhook-secret
            class="min-h-0 overflow-y-auto"
            [secret]="c.webhook?.secret ?? ''"
            [url]="c.webhook?.url ?? null"
            [scheme]="c.webhook?.scheme ?? 'STANDARD'"
            [header]="c.webhook?.secretHeader ?? null"
          />
        } @else {
          <form
            id="system-form"
            class="-mx-6 flex min-h-0 flex-col gap-4 overflow-y-auto px-6 py-1"
            novalidate
            (submit)="onSubmit($event)"
          >
            @if (formError(); as message) {
              <div hlmAlert variant="destructive" data-testid="system-form-error">
                <ng-icon name="lucideCircleAlert" aria-hidden="true" />
                <p hlmAlertDescription>{{ message }}</p>
              </div>
            }

            @if (shows().kinds) {
              <fieldset class="flex flex-col gap-3" data-testid="kind-picker">
                <legend class="sr-only">Kind</legend>
                @for (group of groups; track group.group) {
                  <div class="flex flex-col gap-1.5">
                    <span class="text-muted-foreground text-xs font-semibold">{{
                      group.label
                    }}</span>
                    @for (choice of group.kinds; track choice.value) {
                      <label
                        class="has-checked:border-primary has-checked:bg-primary/5 has-focus-visible:ring-ring/50 hover:bg-accent flex cursor-pointer items-start gap-2.5 rounded-lg border px-3 py-2 text-sm has-focus-visible:ring-3"
                      >
                        <input
                          type="radio"
                          name="system-kind"
                          class="accent-primary mt-0.5 size-4 shrink-0"
                          [id]="'system-kind-' + choice.value"
                          [value]="choice.value"
                          [checked]="model().kind === choice.value"
                          (change)="setKind(choice.value)"
                        />
                        <span class="flex min-w-0 flex-col gap-0.5">
                          <span class="font-semibold">{{ choice.label }}</span>
                          <span class="text-muted-foreground text-xs text-pretty">{{
                            choice.description
                          }}</span>
                        </span>
                      </label>
                    }
                  </div>
                }
              </fieldset>
              @if (model().kind && reached() > 0) {
                <p class="text-muted-foreground text-xs" data-testid="kind-change-hint">
                  Changing the kind clears its address and settings. The name stays.
                </p>
              }
            }

            @if (info(); as i) {
              @if (!system() && !shows().kinds) {
                <div
                  class="bg-muted flex items-center gap-2.5 rounded-lg py-1 ps-3 pe-1 text-sm"
                  data-testid="system-kind-chosen"
                >
                  <span class="text-muted-foreground text-xs font-semibold">Kind</span>
                  <span class="font-semibold">{{ i.label }}</span>
                  <button
                    hlmBtn
                    variant="ghost"
                    size="sm"
                    type="button"
                    class="text-primary ms-auto"
                    (click)="goTo('kind')"
                  >
                    Change<span class="sr-only"> the kind</span>
                  </button>
                </div>
              }

              @if (shows().details) {
                <div hlmField>
                  <label hlmFieldLabel for="system-name">Name</label>
                  <input
                    hlmInput
                    id="system-name"
                    autocomplete="off"
                    [placeholder]="i.label === 'MCP server' ? 'Notes' : i.label"
                    [formField]="systemForm.name"
                  />
                  @if (system(); as s) {
                    @if (i.tools) {
                      <p hlmFieldDescription data-testid="system-tools-prefix">
                        Its tools keep their names in a run,
                        <code class="font-mono">{{ s.handle }}_…</code>, whatever it is called.
                      </p>
                    }
                  }
                  <hlm-field-error>{{ firstError(systemForm.name) }}</hlm-field-error>
                </div>

                @if ((!system() || i.value === 'MCP') && i.baseUrl; as baseUrl) {
                  <div hlmField>
                    <label hlmFieldLabel for="system-base-url">{{ baseUrl.label }}</label>
                    <input
                      hlmInput
                      id="system-base-url"
                      class="font-mono"
                      type="url"
                      autocomplete="off"
                      spellcheck="false"
                      [placeholder]="baseUrl.placeholder"
                      [formField]="systemForm.baseUrl"
                    />
                    <p hlmFieldDescription>
                      {{ baseUrl.hint }}
                      @if (i.value === 'MCP') {
                        A new one later removes the server’s accounts and credentials.
                      } @else {
                        It never changes later.
                      }
                    </p>
                    @if (moves()) {
                      <p
                        class="text-sm text-warning"
                        role="status"
                        data-testid="system-endpoint-warning"
                      >
                        Saving removes every account and credential on this server, and its app: no
                        token is sent to the new address. Add a credential for it afterwards, lend
                        it again, and refresh the tools. Its tools, agents and triggers stay.
                      </p>
                    }
                    <hlm-field-error>{{ firstError(systemForm.baseUrl) }}</hlm-field-error>
                  </div>
                }
              }

              @if (shows().settings) {
                @if (i.value === 'SLACK') {
                  <fieldset class="flex flex-col gap-1.5" data-testid="slack-transport">
                    <legend class="mb-1 text-sm font-semibold">How events arrive</legend>
                    @for (choice of transports; track choice.value) {
                      <label
                        class="has-checked:border-primary has-checked:bg-primary/5 has-focus-visible:ring-ring/50 hover:bg-accent flex cursor-pointer items-start gap-2.5 rounded-lg border px-3 py-2 text-sm has-focus-visible:ring-3"
                      >
                        <input
                          type="radio"
                          name="slack-transport"
                          class="accent-primary mt-0.5 size-4 shrink-0"
                          [id]="'slack-transport-' + choice.value"
                          [value]="choice.value"
                          [checked]="model().transport === choice.value"
                          (change)="setTransport(choice.value)"
                        />
                        <span class="flex min-w-0 flex-col gap-0.5">
                          <span class="font-semibold">{{ choice.label }}</span>
                          <span class="text-muted-foreground text-xs text-pretty">{{
                            choice.description
                          }}</span>
                        </span>
                      </label>
                    }
                  </fieldset>
                }

                @if (i.value === 'TEAMS' && !system()) {
                  <div hlmField>
                    <label hlmFieldLabel for="system-tenant-id">Tenant ID</label>
                    <input
                      hlmInput
                      id="system-tenant-id"
                      class="font-mono"
                      autocomplete="off"
                      spellcheck="false"
                      placeholder="00000000-0000-0000-0000-000000000000"
                      [formField]="systemForm.tenantId"
                    />
                    <p hlmFieldDescription>
                      Your Microsoft Entra tenant, where the single-tenant Azure Bot is registered.
                      It never changes later.
                    </p>
                    <hlm-field-error>{{ firstError(systemForm.tenantId) }}</hlm-field-error>
                  </div>
                  <div hlmField>
                    <label hlmFieldLabel for="system-app-id">Microsoft App ID</label>
                    <input
                      hlmInput
                      id="system-app-id"
                      class="font-mono"
                      autocomplete="off"
                      spellcheck="false"
                      placeholder="00000000-0000-0000-0000-000000000000"
                      [formField]="systemForm.appId"
                    />
                    <p hlmFieldDescription>
                      The Azure Bot's app, with the Teams channel on. Its password is set with the
                      bot, on the system's page.
                    </p>
                    <hlm-field-error>{{ firstError(systemForm.appId) }}</hlm-field-error>
                  </div>
                }

                @if (i.value === 'WEBHOOK') {
                  <fieldset class="flex flex-col gap-1.5" data-testid="webhook-scheme">
                    <legend class="mb-1 text-sm font-semibold">
                      How the sender proves it is them
                    </legend>
                    @for (choice of schemes; track choice.value) {
                      <label
                        class="has-checked:border-primary has-checked:bg-primary/5 has-focus-visible:ring-ring/50 hover:bg-accent flex cursor-pointer items-start gap-2.5 rounded-lg border px-3 py-2 text-sm has-focus-visible:ring-3"
                      >
                        <input
                          type="radio"
                          name="webhook-scheme"
                          class="accent-primary mt-0.5 size-4 shrink-0"
                          [id]="'webhook-scheme-' + choice.value"
                          [value]="choice.value"
                          [checked]="model().scheme === choice.value"
                          (change)="setScheme(choice.value)"
                        />
                        <span class="flex min-w-0 flex-col gap-0.5">
                          <span class="font-semibold">{{ choice.label }}</span>
                          <span class="text-muted-foreground text-xs text-pretty">{{
                            choice.description
                          }}</span>
                        </span>
                      </label>
                    }
                  </fieldset>
                  @if (model().scheme === 'SECRET_HEADER') {
                    <div hlmField>
                      <label hlmFieldLabel class="items-baseline" for="webhook-header"
                        >Header
                        <span class="text-muted-foreground text-xs font-medium"
                          >Optional</span
                        ></label
                      >
                      <input
                        hlmInput
                        id="webhook-header"
                        class="font-mono"
                        autocomplete="off"
                        spellcheck="false"
                        placeholder="X-Tinfirm-Secret"
                        [formField]="systemForm.secretHeader"
                      />
                      <p hlmFieldDescription>Empty: X-Tinfirm-Secret.</p>
                      <hlm-field-error>{{ firstError(systemForm.secretHeader) }}</hlm-field-error>
                    </div>
                  }
                  <div hlmField>
                    <label hlmFieldLabel class="items-baseline" for="webhook-rate"
                      >Deliveries a minute
                      <span class="text-muted-foreground text-xs font-medium">Optional</span></label
                    >
                    <input
                      hlmInput
                      id="webhook-rate"
                      inputmode="numeric"
                      class="w-32"
                      [placeholder]="system() ? '' : 'default'"
                      [formField]="systemForm.maxPerMinute"
                    />
                    <p hlmFieldDescription>
                      More are refused (429), and the sender tries again. Empty: the company's
                      default, set in Settings.
                    </p>
                    <hlm-field-error>{{ firstError(systemForm.maxPerMinute) }}</hlm-field-error>
                  </div>
                }

                @if (polls()) {
                  <div hlmField>
                    <label hlmFieldLabel class="items-baseline" for="system-poll-interval"
                      >Check for changes every
                      <span class="text-muted-foreground text-xs font-medium">Optional</span></label
                    >
                    <div class="flex items-center gap-2">
                      <input
                        hlmInput
                        id="system-poll-interval"
                        inputmode="numeric"
                        class="w-28 font-mono"
                        autocomplete="off"
                        [placeholder]="'' + pollDefault"
                        [formField]="systemForm.pollIntervalSeconds"
                      />
                      <span class="text-muted-foreground text-sm">seconds</span>
                    </div>
                    <p hlmFieldDescription>
                      How often Tinfirm asks this system what changed, for the triggers that watch
                      it: from 30 seconds to an hour. Empty: every minute. More often reacts sooner
                      and uses more of the system’s rate limit.
                    </p>
                    <hlm-field-error>{{
                      firstError(systemForm.pollIntervalSeconds)
                    }}</hlm-field-error>
                  </div>
                }

                @if (i.authHeader) {
                  <div hlmField>
                    <label hlmFieldLabel class="items-baseline" for="system-auth-header"
                      >Token header
                      <span class="text-muted-foreground text-xs font-medium">Optional</span></label
                    >
                    <input
                      hlmInput
                      id="system-auth-header"
                      class="font-mono"
                      autocomplete="off"
                      spellcheck="false"
                      placeholder="Authorization"
                      [formField]="systemForm.authHeader"
                    />
                    <p hlmFieldDescription>
                      The header an account's token goes in. Empty: Authorization: Bearer.
                    </p>
                    <hlm-field-error>{{ firstError(systemForm.authHeader) }}</hlm-field-error>
                  </div>
                }

                @if (i.authHeader && system()) {
                  <label class="flex items-start gap-2.5" for="system-no-account">
                    <input
                      type="checkbox"
                      id="system-no-account"
                      class="accent-primary mt-1 size-4 shrink-0"
                      data-testid="system-no-account"
                      [checked]="!model().accountsRequired"
                      (change)="setNoAccount($event)"
                    />
                    <span class="flex flex-col gap-0.5">
                      <span class="text-sm font-medium">Needs no account</span>
                      <span class="text-muted-foreground text-xs">
                        The server checks no credential, as one reachable only from Tinfirm’s
                        network. Runs call it without one, and nobody connects an account or names
                        one in a trigger. Tinfirm checks that it answers first.
                      </span>
                    </span>
                  </label>
                }
              }
            }
          </form>
        }

        <hlm-dialog-footer>
          @if (created()) {
            <button hlmBtn type="button" data-testid="webhook-secret-done" (click)="done()">
              I have copied the secret
            </button>
          } @else {
            @if (!system() && step() !== 'kind') {
              <button
                hlmBtn
                variant="ghost"
                type="button"
                class="sm:me-auto"
                data-testid="system-back"
                (click)="back()"
              >
                <ng-icon name="lucideChevronLeft" aria-hidden="true" />
                Back
              </button>
            }
            <button hlmBtn variant="outline" type="button" hlmDialogClose>Cancel</button>
            @if (submits()) {
              <button
                hlmBtn
                type="submit"
                form="system-form"
                data-testid="system-save"
                [disabled]="systemForm().submitting()"
              >
                @if (systemForm().submitting()) {
                  <hlm-spinner aria-hidden="true" />
                }
                {{ system() ? 'Save' : 'Register' }}
              </button>
            } @else {
              <button
                hlmBtn
                type="submit"
                form="system-form"
                data-testid="system-next"
                [disabled]="!model().kind"
              >
                Next
                <ng-icon name="lucideChevronRight" aria-hidden="true" />
              </button>
            }
          }
        </hlm-dialog-footer>
      </hlm-dialog-content>
    </hlm-dialog>
  `,
})
export class SystemDialog {
  private readonly api = inject(Api);
  private readonly current = inject(CurrentCompany);
  private readonly document = inject(DOCUMENT);
  private readonly injector = inject(Injector);

  readonly open = model(false);
  /** The system to change; null registers a new one. */
  readonly system = input<ExternalSystem | null>(null);
  readonly saved = output<ExternalSystem>();

  protected readonly groups = registrableKinds();
  protected readonly firstError = firstError;
  protected readonly model = signal<SystemModel>({ ...EMPTY });
  protected readonly formError = signal<string | null>(null);
  protected readonly transports = TRANSPORTS;
  protected readonly schemes = SCHEMES;
  /** A webhook just registered: its secret, shown once before the dialog closes. */
  protected readonly created = signal<ExternalSystem | null>(null);
  /** A tracker or code host whose issues triggers watch: its poll interval is asked for. */
  protected readonly polls = computed(() => pollsIssues(this.model().kind));
  protected readonly pollDefault = POLL_INTERVAL.default;
  protected readonly info = computed(() => {
    const kind = this.model().kind;
    return kind ? systemKind(kind) : null;
  });

  /** The step of a registration on screen; a change has none. */
  protected readonly step = signal<SystemStep>('kind');
  /** The furthest step reached since the kind was picked, by index: the steps up to it are done. */
  protected readonly reached = signal(0);
  /** The server sent the person back to a step before the last: Register is offered there. */
  private readonly bounced = signal(false);
  protected readonly steps = computed<SystemStep[]>(() => {
    const kind = this.model().kind;
    return kind && !hasSettings(kind) ? ['kind', 'details'] : ['kind', 'details', 'settings'];
  });
  private readonly stepIndex = computed(() => this.steps().indexOf(this.step()));
  protected readonly stepItems = computed(() => {
    const current = this.stepIndex();
    const reached = this.reached();
    return this.steps().map((key, index) => {
      const state = index === current ? 'current' : index <= reached ? 'done' : 'todo';
      return {
        key,
        label: STEP_LABELS[key],
        number: index + 1,
        state,
        pill: STEP_PILL[state],
        dot: STEP_DOT[state],
        // On a phone only the current step keeps its name on screen; readers hear them all.
        labelClass: state === 'current' ? '' : 'max-sm:sr-only',
      };
    });
  });
  protected readonly stepCount = computed(
    () => `Step ${this.stepIndex() + 1} of ${this.steps().length}`,
  );
  protected readonly stepDescription = computed(() => {
    const kind = this.model().kind;
    switch (this.step()) {
      case 'kind':
        return 'A service agents work with, or a chat where people talk to them. Pick its kind first; each kind asks only for what it needs.';
      case 'details':
        return kind && systemKind(kind).baseUrl
          ? 'What it is called, and where Tinfirm reaches it.'
          : 'What it is called in Tinfirm.';
      default:
        return kind ? settingsDescription(kind) : '';
    }
  });
  /** What the form shows: a change all its fields at once, a registration its step's. */
  protected readonly shows = computed(() => {
    const editing = !!this.system();
    const step = this.step();
    return {
      kinds: !editing && step === 'kind',
      details: editing || step === 'details',
      settings: editing || step === 'settings',
    };
  });
  /** A change that gives the MCP server a new endpoint: the form warns what goes with the old one. */
  protected readonly moves = computed(() => {
    const system = this.system();
    return !!system && movesEndpoint(this.model(), system);
  });
  /** The form submits: a change, the last step, or the step the server sent the person back to. */
  protected readonly submits = computed(
    () => !!this.system() || this.bounced() || this.stepIndex() === this.steps().length - 1,
  );

  protected readonly systemForm = form(this.model, (path) => {
    required(path.name, { message: 'Enter a name.' });
    maxLength(path.name, 100, { message: 'At most 100 characters.' });
    // A registration's address, and a change's of an MCP server: the only one that changes.
    required(path.baseUrl, {
      message: 'Enter the address.',
      when: ({ valueOf }) => this.asksBaseUrl() && !!kindTakesBaseUrl(valueOf(path.kind)),
    });
    maxLength(path.baseUrl, 500, { message: 'At most 500 characters.' });
    pattern(path.baseUrl, BASE_URL_PATTERN, {
      message: 'Enter an https address, such as https://jira.example.com.',
      when: ({ value }) => this.asksBaseUrl() && value().trim() !== '',
    });
    required(path.tenantId, {
      message: 'Enter the tenant ID.',
      when: ({ valueOf }) => !this.system() && valueOf(path.kind) === 'TEAMS',
    });
    pattern(path.tenantId, GUID_PATTERN, {
      message: 'A GUID, such as 72f988bf-86f1-41af-91ab-2d7cd011db47.',
      when: ({ value }) => !this.system() && value().trim() !== '',
    });
    required(path.appId, {
      message: 'Enter the Microsoft App ID.',
      when: ({ valueOf }) => !this.system() && valueOf(path.kind) === 'TEAMS',
    });
    pattern(path.appId, GUID_PATTERN, {
      message: 'A GUID, as the Azure Bot’s configuration shows it.',
      when: ({ value }) => !this.system() && value().trim() !== '',
    });
    // Only the header scheme sends a header: a header typed before the scheme changed blocks nothing.
    pattern(path.secretHeader, HEADER_NAME_PATTERN, {
      message: 'A header name: letters, digits and dashes, no colon or spaces.',
      when: ({ value, valueOf }) =>
        valueOf(path.scheme) === 'SECRET_HEADER' && value().trim() !== '',
    });
    validate(path.maxPerMinute, ({ value }) => {
      const text = value().trim();
      if (!text) return undefined;
      const n = Number(text);
      return Number.isInteger(n) && n >= 1 && n <= 6000
        ? undefined
        : { kind: 'range', message: 'A whole number from 1 to 6000.' };
    });
    validate(path.pollIntervalSeconds, ({ value, valueOf }) => {
      if (!pollsIssues(valueOf(path.kind))) return undefined;
      const message = pollIntervalError(value());
      return message ? { kind: 'range', message } : undefined;
    });
    pattern(path.authHeader, HEADER_NAME_PATTERN, {
      message: 'A header name: letters, digits and dashes, no colon or spaces.',
      when: ({ value }) => value().trim() !== '',
    });
  });

  /** The form asks for the base URL: a registration's, or an MCP server's on a change. */
  private asksBaseUrl(): boolean {
    const system = this.system();
    return !system || system.kind === 'MCP';
  }

  constructor() {
    // Every opening starts from the system to change, or from nothing at the first step.
    effect(() => {
      if (!this.open()) return;
      const system = this.system();
      untracked(() => {
        this.model.set(
          system
            ? {
                kind: system.kind,
                name: system.name,
                baseUrl: system.baseUrl ?? '',
                authHeader: system.authHeader ?? '',
                transport: system.chat?.transport ?? 'EVENTS_API',
                tenantId: system.chat?.tenantId ?? '',
                appId: system.chat?.appId ?? '',
                scheme: system.webhook?.scheme ?? 'STANDARD',
                secretHeader: system.webhook?.secretHeader ?? '',
                maxPerMinute:
                  system.webhook?.maxPerMinute != null ? String(system.webhook.maxPerMinute) : '',
                pollIntervalSeconds:
                  system.pollIntervalSeconds != null ? String(system.pollIntervalSeconds) : '',
                accountsRequired: system.accountsRequired,
              }
            : { ...EMPTY },
        );
        this.systemForm().reset();
        this.formError.set(null);
        this.created.set(null);
        this.step.set('kind');
        this.reached.set(0);
        this.bounced.set(false);
      });
    });
  }

  /** Another kind: its address and settings start over; the name stays. */
  protected setKind(kind: SystemKind): void {
    const m = this.model();
    if (m.kind === kind) return;
    this.model.set({ ...EMPTY, kind, name: m.name });
    this.systemForm().reset();
    this.reached.set(0);
    this.bounced.set(false);
  }

  protected setNoAccount(event: Event): void {
    const noAccount = (event.target as HTMLInputElement).checked;
    this.model.update((m) => ({ ...m, accountsRequired: !noAccount }));
  }

  protected setTransport(transport: Transport): void {
    this.model.update((m) => ({ ...m, transport }));
  }

  protected setScheme(scheme: WebhookScheme): void {
    this.model.update((m) => ({ ...m, scheme }));
  }

  /** Next: the step's own fields are checked, and only valid ones move on. */
  protected next(): void {
    if (!this.model().kind) return;
    const fields = stepFields(this.step(), this.model());
    for (const name of fields) this.systemForm[name]().markAsTouched();
    const invalid = fields.find((name) => this.systemForm[name]().invalid());
    if (invalid) {
      this.focus(FIELD_IDS[invalid]);
      return;
    }
    const following = this.steps()[this.stepIndex() + 1];
    if (following) this.goTo(following);
  }

  /** Back keeps everything typed. */
  protected back(): void {
    const previous = this.steps()[this.stepIndex() - 1];
    if (previous) this.goTo(previous);
  }

  protected goTo(step: SystemStep): void {
    this.step.set(step);
    this.reached.update((reached) => Math.max(reached, this.steps().indexOf(step)));
    this.bounced.set(false);
    this.formError.set(null);
    this.focus(this.firstFieldId(step));
  }

  /** The secret was copied: the dialog closes and hands the system over, without its secret. */
  protected done(): void {
    const system = this.created();
    if (!system) return;
    this.created.set(null);
    this.open.set(false);
    this.saved.emit(
      system.webhook ? { ...system, webhook: { ...system.webhook, secret: null } } : system,
    );
  }

  /** Enter goes to Next; only the last step, or the one the server sent the person back to, submits. */
  protected onSubmit(event: Event): void {
    event.preventDefault();
    if (this.submits()) void this.save();
    else this.next();
  }

  private async save(): Promise<void> {
    const companyId = this.current.id();
    const kind = this.model().kind;
    if (!companyId || !kind) return;
    this.formError.set(null);
    const saved = await submit(this.systemForm, async () => {
      try {
        const existing = this.system();
        const saved = existing
          ? await this.api.invoke(updateSystem, {
              companyId,
              systemId: existing.id,
              body: systemChanges(this.model(), existing),
            })
          : await this.api.invoke(createSystem, {
              companyId,
              body: createBody(this.model(), kind),
            });
        if (!existing && saved.webhook?.secret) {
          // Shown once: the dialog stays open on it until the person has copied it.
          this.created.set(saved);
          return undefined;
        }
        this.open.set(false);
        this.saved.emit(saved);
        return undefined;
      } catch (error) {
        const failure = mapSubmissionError(this.systemForm, error, {
          codeFields: {
            DUPLICATE_SYSTEM_NAME: 'name',
            INVALID_BASE_URL: 'baseUrl',
            CHAT_WORKSPACE_TAKEN: 'tenantId',
            WEBHOOK_LIMIT_TOO_HIGH: 'maxPerMinute',
          },
          overrides: SYSTEM_FORM_MESSAGES,
        });
        this.formError.set(failure.message);
        return failure.fieldErrors;
      }
    });
    if (!saved && !this.system() && this.open() && !this.created()) this.showFailedStep();
  }

  /**
   * After a refused submit, the first step with a field in error opens and that field takes the
   * focus. Before the last step, Register is offered there, so a fixed name needs no second walk.
   */
  private showFailedStep(): void {
    const steps = this.steps();
    for (const step of steps) {
      const failed = stepFields(step, this.model()).find((name) =>
        this.systemForm[name]().invalid(),
      );
      if (!failed) continue;
      this.step.set(step);
      this.bounced.set(step !== steps[steps.length - 1]);
      this.focus(FIELD_IDS[failed]);
      return;
    }
  }

  /** The control a step's focus lands on: the picked kind, a radio of the settings, else the first field. */
  private firstFieldId(step: SystemStep): string {
    const m = this.model();
    if (step === 'kind') return `system-kind-${m.kind || this.groups[0].kinds[0].value}`;
    if (step === 'settings' && m.kind === 'SLACK') return `slack-transport-${m.transport}`;
    if (step === 'settings' && m.kind === 'WEBHOOK') return `webhook-scheme-${m.scheme}`;
    const first = stepFields(step, m)[0];
    return first ? FIELD_IDS[first] : '';
  }

  private focus(id: string): void {
    afterNextRender(() => this.document.getElementById(id)?.focus(), {
      injector: this.injector,
    });
  }
}

function kindTakesBaseUrl(kind: SystemKind | ''): boolean {
  return kind !== '' && systemKind(kind).baseUrl !== null;
}

/** A create's body: only the fields the kind takes; never a handle, which the server makes. */
export function createBody(m: SystemModel, kind: SystemKind): SystemCreateRequest {
  const info = systemKind(kind);
  const body: SystemCreateRequest = { name: m.name.trim(), kind };
  if (info.baseUrl) body.baseUrl = m.baseUrl.trim();
  if (info.authHeader && m.authHeader.trim()) body.authHeader = m.authHeader.trim();
  if (kind === 'SLACK') body.slack = { transport: m.transport };
  if (kind === 'TEAMS') body.teams = { tenantId: m.tenantId.trim(), appId: m.appId.trim() };
  if (kind === 'WEBHOOK') {
    // The mapping is set on the system's page, against a sample delivery.
    body.webhook = { scheme: m.scheme, mapping: {} };
    if (m.scheme === 'SECRET_HEADER' && m.secretHeader.trim()) {
      body.webhook.secretHeader = m.secretHeader.trim();
    }
    if (m.maxPerMinute.trim()) body.webhook.maxPerMinute = Number(m.maxPerMinute);
  }
  if (pollsIssues(kind) && m.pollIntervalSeconds.trim()) {
    body.pollIntervalSeconds = Number(m.pollIntervalSeconds.trim());
  }
  return body;
}
