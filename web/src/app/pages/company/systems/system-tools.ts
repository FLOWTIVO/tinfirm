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
  linkedSignal,
  output,
  resource,
  signal,
  untracked,
} from '@angular/core';
import { NgTemplateOutlet } from '@angular/common';
import { NgIcon, provideIcons } from '@ng-icons/core';
import {
  lucideChevronDown,
  lucideChevronRight,
  lucideCircleAlert,
  lucideCircleCheck,
  lucideEye,
  lucideKeyRound,
  lucidePencil,
  lucidePlus,
  lucideRefreshCw,
} from '@ng-icons/lucide';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmNativeSelectImports } from '@spartan-ng/helm/native-select';
import { HlmSpinner } from '@spartan-ng/helm/spinner';
import { HlmSwitch } from '@spartan-ng/helm/switch';
import { Api } from '../../../api/generated/api';
import {
  listSystemTools,
  refreshSystemTools,
  updateSystemTool,
} from '../../../api/generated/functions';
import type {
  ExternalSystem,
  SystemCredential,
  SystemTool,
  SystemToolRefresh,
  ToolEffect,
} from '../../../api/generated/models';
import { sameParams } from '../../../core/paged-resource';
import { Notify } from '../../../core/notify';
import { ErrorState } from '../../../shared/error-state';
import { plural } from '../../../shared/labels';
import { CredentialDialog } from './credential-dialog';
import { credentialState, holdsCredentials } from './credential-labels';
import { systemKind } from './system-kinds';

/** Messages of a refresh or a tool's change, in this section's words. */
export const TOOL_MESSAGES: Record<string, string> = {
  SYSTEM_KIND_INVALID: 'Tinfirm’s own tools of a tracker are fixed; only an MCP server’s change.',
  'status:403': 'Only owners and admins can change the company’s systems.',
};

/**
 * Whether the server asked for a credential none was given for (`credentialRequired` without
 * `withAccount`): it was reached, and whoever manages it adds a credential and reads it again.
 */
export function asksForCredential(
  result: Pick<SystemToolRefresh, 'credentialRequired' | 'withAccount'> | null,
): boolean {
  return !!result?.credentialRequired && !result.withAccount;
}

/**
 * What the tools were read with, after the summary: the credential, by the name the server gives,
 * the reader's own account, or nothing.
 */
export function readWith(
  result: Pick<SystemToolRefresh, 'withAccount' | 'credentialId' | 'credentialName'>,
): string {
  if (result.credentialId) {
    return result.credentialName
      ? `Read with the credential ${result.credentialName}.`
      : 'Read with one of its credentials.';
  }
  return result.withAccount ? 'Read with your own account.' : 'Read without an account.';
}

/** What a refresh found, in one sentence; `ok: false` keeps the catalogue as it was. */
export function refreshSummary(result: SystemToolRefresh): string {
  if (asksForCredential(result)) {
    return 'The server asks for a credential, and none was given: you hold no account of your own on it, and it has no working credential to read it with. The catalogue stays as it was.';
  }
  if (!result.ok && result.credentialRequired) {
    return `The server refused the account it was read with: ${result.message ?? 'no reason given'}. Sign it in again, or store a new token. The catalogue stays as it was.`;
  }
  if (!result.ok) {
    return `Couldn't read the server's tools: ${result.message ?? 'no reason given'}. The catalogue stays as it was.`;
  }
  const parts: string[] = [];
  if (result.added.length) parts.push(`${plural(result.added.length, 'tool')} added`);
  if (result.removed.length) parts.push(`${plural(result.removed.length, 'tool')} removed`);
  const changes = parts.length ? parts.join(', ') : 'Nothing was added or removed';
  const lost = result.unconfirmed.length
    ? ` ${result.unconfirmed.join(', ')} changed (description, schema or hints), so ${result.unconfirmed.length === 1 ? 'its effect needs' : 'their effects need'} confirming again.`
    : '';
  return `Read the tools again. ${changes}.${lost}`;
}

/** What the hints suggest, as a short phrase; null without hints or where the effect is known. */
export function suggestion(tool: Pick<SystemTool, 'effect' | 'suggestedEffect'>): string | null {
  if (tool.effect !== 'UNKNOWN') return null;
  switch (tool.suggestedEffect) {
    case 'READ':
      return 'Its hints say it only reads.';
    case 'WRITE':
      return 'Its hints say it writes.';
    default:
      return 'It sends no hints.';
  }
}

/** Why a tool waits to be confirmed, as the last refresh here found it: new, or changed. */
export function unconfirmedBecause(
  name: string,
  refreshed: Pick<SystemToolRefresh, 'ok' | 'added' | 'unconfirmed'> | null,
): string | null {
  if (!refreshed?.ok) return null;
  if (refreshed.added.includes(name)) return 'New at the last refresh';
  if (refreshed.unconfirmed.includes(name)) {
    return 'Changed at the last refresh, so it needs confirming again';
  }
  return null;
}

/** How many tools there are, and how many wait to be confirmed: the page's tab says both. */
export interface ToolsSummary {
  readonly count: number;
  readonly toConfirm: number;
}

/**
 * A system's tools (the web plan §5, Systems), one compact row each: its name as a run calls it, its
 * label and whether it reads or writes, its description on expand. A tracker's are Tinfirm's own,
 * fixed, each with its known effect. An MCP server's are its catalogue as the last refresh read it:
 * each tool counts as a write until whoever manages the system says what it does (`UNKNOWN`, the one
 * thing here waiting on a person, so those come first, orange, under "To confirm"), and each can be
 * switched off for every agent. A refresh reads the catalogue again: new tools come unconfirmed, and
 * a tool whose description, schema or hints changed loses its confirmation (security review, row 13).
 */
@Component({
  selector: 'app-system-tools',
  imports: [
    NgTemplateOutlet,
    NgIcon,
    HlmButton,
    HlmNativeSelectImports,
    HlmSpinner,
    HlmSwitch,
    CredentialDialog,
    ErrorState,
  ],
  providers: [
    provideIcons({
      lucideChevronDown,
      lucideChevronRight,
      lucideCircleAlert,
      lucideCircleCheck,
      lucideEye,
      lucideKeyRound,
      lucidePencil,
      lucidePlus,
      lucideRefreshCw,
    }),
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'block' },
  template: `
    <section
      class="bg-card overflow-hidden rounded-xl border"
      aria-labelledby="tools-title"
      data-testid="system-tools"
    >
      <div class="flex flex-wrap items-start gap-3 px-4 py-4 sm:px-5 sm:pt-5">
        <div class="flex min-w-0 flex-1 basis-72 flex-col gap-0.5">
          <h2 id="tools-title" class="flex flex-wrap items-center gap-2 text-base font-bold">
            Tools
            @if (tools().length) {
              <span class="text-muted-foreground font-mono text-xs font-medium">{{
                tools().length
              }}</span>
            }
            @if (toConfirm() > 0) {
              <span
                class="inline-flex h-6 items-center rounded-full px-2.5 text-xs font-semibold"
                [class]="
                  canManage() ? 'bg-human-soft text-human' : 'bg-muted text-muted-foreground'
                "
                data-testid="tools-to-confirm"
                >{{ toConfirm() }} to confirm</span
              >
            }
          </h2>
          <p class="text-muted-foreground text-sm text-pretty">
            @if (catalogue()) {
              What the server lists, as the last refresh read it. An unconfirmed tool counts as a
              write everywhere, so agents ask before every call to it. The token stays with Tinfirm;
              agents never see it. In a run they are named
            } @else {
              Tinfirm's own tools for {{ kindLabel() }}, each with what it does. An agent gets the
              ones its definition names; in a run they are named
            }
            <code class="text-foreground font-mono text-[0.8125rem]" data-testid="tools-handle"
              >{{ system().handle }}_…</code
            >.
          </p>
        </div>
        @if (catalogue() && canManage()) {
          <div class="flex flex-wrap items-center gap-2">
            @if (pickable().length > 1) {
              <label class="sr-only" for="tools-credential">Read the tools with</label>
              <hlm-native-select
                size="sm"
                class="w-full sm:w-56"
                selectId="tools-credential"
                data-testid="tools-credential"
                [value]="credentialId()"
                (valueChange)="credentialId.set($event ?? '')"
              >
                <option value="" [title]="defaultTitle">With the usual account</option>
                @for (c of pickable(); track c.id) {
                  <option [value]="c.id">With {{ c.name }}{{ c.note }}</option>
                }
              </hlm-native-select>
            }
            <button
              hlmBtn
              variant="outline"
              size="sm"
              class="max-sm:h-11"
              type="button"
              data-testid="refresh-tools"
              [disabled]="refreshing()"
              (click)="refresh()"
            >
              @if (refreshing()) {
                <hlm-spinner aria-hidden="true" />
              } @else {
                <ng-icon name="lucideRefreshCw" aria-hidden="true" />
              }
              Refresh tools
            </button>
          </div>
        }
      </div>

      <div role="status" aria-live="polite" class="empty:hidden">
        @if (refreshed(); as result) {
          <p
            class="bg-muted/50 flex items-start gap-2.5 border-t px-4 py-2.5 text-[0.8125rem] text-pretty sm:px-5"
            data-testid="refresh-result"
            [attr.data-ok]="result.ok"
          >
            <ng-icon
              [name]="result.ok ? 'lucideCircleCheck' : 'lucideCircleAlert'"
              class="mt-0.5 shrink-0"
              [class]="result.ok ? 'text-success' : 'text-destructive'"
              aria-hidden="true"
            />
            <span class="flex min-w-0 flex-col gap-2">
              <span>{{ refreshText(result) }}</span>
              @if (asksForCredential(result)) {
                @if (offersCredential()) {
                  <span
                    class="flex flex-wrap items-center gap-2"
                    data-testid="tools-credential-required"
                  >
                    <span class="text-muted-foreground"
                      >Add one here, and the tools are read with it at once.</span
                    >
                    <button
                      hlmBtn
                      size="sm"
                      type="button"
                      class="max-sm:h-11"
                      data-testid="add-credential-for-tools"
                      (click)="addOpen.set(true)"
                    >
                      <ng-icon name="lucidePlus" aria-hidden="true" />
                      Add a credential
                    </button>
                  </span>
                } @else if (system().personal) {
                  <span class="text-muted-foreground" data-testid="tools-credential-required"
                    >Connect your own account on it under Accounts, then refresh again.</span
                  >
                }
              }
            </span>
          </p>
        }
      </div>

      @if (toolsRes.error() && !toolsRes.hasValue()) {
        <div class="px-4 pb-4 sm:px-5">
          <app-error-state
            [error]="toolsRes.error()"
            heading="Couldn't load the tools"
            (retry)="toolsRes.reload()"
          />
        </div>
      } @else if (toolsRes.isLoading() && !toolsRes.hasValue()) {
        <p class="text-muted-foreground border-t px-4 py-4 text-sm sm:px-5" role="status">
          Loading the tools…
        </p>
      } @else if (tools().length === 0) {
        <p
          class="text-muted-foreground mx-4 mb-4 rounded-lg border border-dashed px-3 py-4 text-center text-sm sm:mx-5 sm:mb-5"
          data-testid="tools-empty"
        >
          @if (catalogue()) {
            No tools read yet.
            @if (canManage()) {
              Refresh them once the server answers.
            }
          } @else {
            This kind of system has no tools of its own.
          }
        </p>
      } @else {
        @if (pending().length) {
          <div
            class="flex flex-wrap items-baseline gap-x-2.5 gap-y-1 border-t px-4 pt-4 pb-2.5 sm:px-5"
          >
            <h3 class="text-sm font-bold" id="tools-pending-title">
              To confirm
              <span class="text-human font-mono font-medium">{{ pending().length }}</span>
            </h3>
            <span class="text-muted-foreground text-[0.8125rem]"
              >Say whether each tool only reads or also writes.</span
            >
          </div>
          <ul aria-labelledby="tools-pending-title">
            @for (tool of pending(); track tool.name) {
              @let open = isOpen(tool.name);
              @let busy = busyTool() === tool.name;
              <li
                class="border-t"
                [attr.data-testid]="'tool-' + tool.name"
                [attr.data-effect]="tool.effect"
                [attr.data-enabled]="tool.enabled"
              >
                <div
                  class="flex min-h-15 flex-wrap items-center gap-x-3 gap-y-2 py-2.5 ps-4 pe-3 sm:ps-5 sm:pe-4"
                >
                  <span class="flex min-w-0 flex-1 basis-72 flex-col">
                    <span class="flex min-w-0 flex-wrap items-baseline gap-x-3">
                      <code class="font-mono text-[0.8125rem] font-medium break-all">{{
                        tool.name
                      }}</code>
                      @if (tool.title) {
                        <span class="text-sm font-semibold">{{ tool.title }}</span>
                      }
                    </span>
                    <span class="text-muted-foreground text-xs text-pretty">
                      <span class="text-human font-semibold" data-testid="tool-effect"
                        >Counts as a write until you say</span
                      >
                      @if (because(tool.name); as why) {
                        · {{ why }}
                      }
                      · <span data-testid="tool-suggestion">{{ suggestionOf(tool) }}</span>
                    </span>
                  </span>
                  <button
                    hlmBtn
                    variant="outline"
                    size="sm"
                    type="button"
                    class="border-human/40 bg-human-soft text-human hover:border-human hover:bg-human-soft hover:text-human aria-expanded:bg-human-soft aria-expanded:text-human max-sm:h-11"
                    data-testid="confirm-effect"
                    [id]="'tool-ask-' + tool.name"
                    [attr.aria-expanded]="open"
                    [attr.aria-controls]="'tool-choices-' + tool.name"
                    (click)="toggle(tool.name)"
                  >
                    Reads or writes?
                    <ng-icon
                      name="lucideChevronDown"
                      class="transition-transform motion-reduce:transition-none"
                      [class.rotate-180]="open"
                      aria-hidden="true"
                    />
                  </button>
                  <ng-container
                    *ngTemplateOutlet="offered; context: { $implicit: tool, busy: busy }"
                  />
                </div>
                @if (open) {
                  <div
                    class="border-human mx-4 mb-4 rounded-xl border p-3.5 sm:mx-5"
                    [id]="'tool-choices-' + tool.name"
                  >
                    @if (tool.description) {
                      <p class="text-muted-foreground text-[0.8125rem] text-pretty">
                        {{ tool.description }}
                      </p>
                    }
                    <p class="mt-3 mb-2 text-sm font-semibold first:mt-0">
                      What does <code class="font-mono text-[0.8125rem]">{{ tool.name }}</code> do?
                    </p>
                    <div
                      class="grid gap-2 sm:grid-cols-2"
                      role="group"
                      [attr.aria-label]="'What ' + tool.name + ' does'"
                    >
                      @for (choice of effectChoices; track choice.value) {
                        <button
                          type="button"
                          class="bg-card hover:bg-accent focus-visible:ring-ring/50 flex min-h-11 flex-col items-start gap-1 rounded-[10px] border px-3.5 py-3 text-start outline-none focus-visible:ring-3 disabled:opacity-50"
                          [attr.data-testid]="'confirm-' + choice.value"
                          [disabled]="busy"
                          (click)="confirm(tool, choice.value)"
                        >
                          <span class="flex flex-wrap items-center gap-2 text-sm font-semibold">
                            <ng-icon [name]="choice.icon" size="14" aria-hidden="true" />
                            {{ choice.ask }}
                            @if (tool.suggestedEffect === choice.value) {
                              <span
                                class="text-muted-foreground inline-flex h-5 items-center rounded-full border px-2 text-[0.6875rem] font-semibold"
                                >Its hints say so</span
                              >
                            }
                          </span>
                          <span class="text-muted-foreground text-xs text-pretty">{{
                            choice.means
                          }}</span>
                        </button>
                      }
                    </div>
                  </div>
                }
              </li>
            }
          </ul>
        } @else if (catalogue() && canManage()) {
          <p
            class="flex items-center gap-2.5 border-t px-4 py-3.5 text-sm sm:px-5"
            data-testid="tools-all-confirmed"
          >
            <ng-icon name="lucideCircleCheck" class="text-success" aria-hidden="true" />
            Every tool’s effect is confirmed.
          </p>
        }

        @if (catalogue() && canManage() && rest().length) {
          <div
            class="flex flex-wrap items-baseline gap-x-2.5 gap-y-1 border-t px-4 pt-4 pb-2.5 sm:px-5"
          >
            <h3 class="text-sm font-bold" id="tools-confirmed-title">
              Confirmed
              <span class="text-muted-foreground font-mono font-medium">{{ rest().length }}</span>
            </h3>
            <span class="text-muted-foreground text-[0.8125rem]"
              >Offered: an agent whose definition names it gets it. Off: no agent gets it.</span
            >
          </div>
        }
        <ul [attr.aria-labelledby]="restTitle()">
          @for (tool of rest(); track tool.name) {
            @let open = isOpen(tool.name);
            @let busy = busyTool() === tool.name;
            @let editable = !tool.fixed && canManage();
            <li
              class="border-t"
              [attr.data-testid]="'tool-' + tool.name"
              [attr.data-effect]="tool.effect"
              [attr.data-enabled]="tool.enabled"
            >
              <div class="flex items-center gap-2 pe-3 sm:pe-4">
                <button
                  type="button"
                  class="hover:bg-muted/50 focus-visible:ring-ring/50 flex min-h-13 min-w-0 flex-1 flex-wrap items-center gap-x-3 gap-y-0.5 py-2 ps-4 pe-2 text-start outline-none focus-visible:ring-3 focus-visible:ring-inset sm:ps-5"
                  [class.text-muted-foreground]="!tool.enabled"
                  [id]="'tool-row-' + tool.name"
                  [attr.aria-expanded]="open"
                  [attr.aria-controls]="'tool-details-' + tool.name"
                  (click)="toggle(tool.name)"
                >
                  <ng-icon
                    name="lucideChevronRight"
                    class="text-muted-foreground shrink-0 transition-transform motion-reduce:transition-none"
                    [class.rotate-90]="open"
                    aria-hidden="true"
                  />
                  <code class="shrink-0 font-mono text-[0.8125rem] font-medium break-all sm:w-52">{{
                    tool.name
                  }}</code>
                  @if (tool.title) {
                    <span class="min-w-0 flex-1 basis-40 text-sm font-semibold">{{
                      tool.title
                    }}</span>
                  }
                </button>
                <span
                  class="bg-card text-muted-foreground inline-flex h-6 shrink-0 items-center gap-1 rounded-full border px-2.5 text-xs font-semibold whitespace-nowrap"
                  data-testid="tool-effect"
                >
                  @if (tool.effect === 'READ') {
                    <ng-icon name="lucideEye" size="12" aria-hidden="true" />
                  } @else {
                    <ng-icon name="lucidePencil" size="12" aria-hidden="true" />
                  }
                  {{ effectLabel(tool.effect) }}
                </span>
                @if (editable) {
                  <ng-container
                    *ngTemplateOutlet="offered; context: { $implicit: tool, busy: busy }"
                  />
                } @else if (!tool.enabled) {
                  <span
                    class="bg-muted text-muted-foreground inline-flex h-6 shrink-0 items-center rounded-full px-2.5 text-xs font-semibold"
                    >Off</span
                  >
                }
              </div>
              @if (open) {
                <div
                  class="flex flex-wrap items-center gap-x-6 gap-y-2.5 ps-11 pe-4 pb-3.5 sm:ps-12 sm:pe-5"
                  [id]="'tool-details-' + tool.name"
                >
                  <p
                    class="text-muted-foreground max-w-[72ch] min-w-0 flex-1 basis-80 text-[0.8125rem] text-pretty"
                  >
                    {{ tool.description || 'It gives no description.' }}
                  </p>
                  @if (editable) {
                    <div
                      class="bg-muted inline-flex shrink-0 rounded-full p-[3px]"
                      role="group"
                      [attr.aria-label]="'What ' + tool.name + ' does'"
                    >
                      @for (choice of effectChoices; track choice.value) {
                        <button
                          type="button"
                          class="text-muted-foreground hover:text-foreground aria-pressed:bg-card aria-pressed:text-foreground focus-visible:ring-ring/50 h-7 rounded-full px-3 text-xs font-semibold outline-none focus-visible:ring-3 disabled:opacity-50 aria-pressed:shadow-[0_1px_3px_rgb(0_0_0/0.08)] max-sm:h-9"
                          [attr.aria-pressed]="tool.effect === choice.value"
                          [attr.data-testid]="'confirm-' + choice.value"
                          [disabled]="busy"
                          (click)="confirm(tool, choice.value)"
                        >
                          {{ choice.label }}
                        </button>
                      }
                    </div>
                  }
                </div>
              }
            </li>
          }
        </ul>
      }
    </section>

    @if (offersCredential()) {
      <app-credential-dialog
        [(open)]="addOpen"
        [companyId]="companyId()"
        [system]="system()"
        (saved)="onCredentialAdded($event)"
      />
    }

    <ng-template #offered let-tool let-busy="busy">
      <span class="flex shrink-0 items-center gap-2">
        @if (busy) {
          <hlm-spinner class="size-4" aria-hidden="true" />
        }
        <label
          class="text-muted-foreground w-[3.6em] text-end text-xs font-semibold"
          [for]="'tool-enabled-' + tool.name"
          >{{ tool.enabled ? 'Offered' : 'Off'
          }}<span class="sr-only"> : offer {{ tool.name }} to agents</span></label
        >
        <hlm-switch
          #enabledSwitch
          size="sm"
          [inputId]="'tool-enabled-' + tool.name"
          [checked]="tool.enabled"
          [disabled]="busy"
          (checkedChange)="setEnabled(tool, $event, enabledSwitch)"
        />
      </span>
    </ng-template>
  `,
})
export class SystemTools {
  private readonly api = inject(Api);
  private readonly notify = inject(Notify);
  private readonly injector = inject(Injector);
  private readonly document = inject(DOCUMENT);

  readonly companyId = input.required<string>();
  readonly system = input.required<ExternalSystem>();
  /** Whoever manages the system: OWNER and ADMIN, or a personal server's owner. */
  readonly canManage = input(false);
  /** The system's credentials, as its credentials section read them (OWNER, ADMIN). */
  readonly credentials = input<readonly SystemCredential[]>([]);
  /** How many tools, and how many to confirm, once read: the page's tab shows them. */
  readonly summary = output<ToolsSummary>();
  /** A credential added here, when the server asked for one: the page reads them again. */
  readonly credentialAdded = output<SystemCredential>();

  protected readonly asksForCredential = asksForCredential;
  protected readonly defaultTitle = 'Your own account, else the only working credential, else none';

  protected readonly effectChoices: readonly {
    value: Exclude<ToolEffect, 'UNKNOWN'>;
    label: string;
    ask: string;
    means: string;
    icon: string;
  }[] = [
    {
      value: 'READ',
      label: 'Reads',
      ask: 'It only reads',
      means: 'Agents may run it without asking, where their definition says so.',
      icon: 'lucideEye',
    },
    {
      value: 'WRITE',
      label: 'Writes',
      ask: 'It writes',
      means: 'It counts as a write: a read-only grant never calls it.',
      icon: 'lucidePencil',
    },
  ];
  protected readonly catalogue = computed(
    () => systemKind(this.system().kind).tools === 'catalogue',
  );
  protected readonly kindLabel = computed(() => systemKind(this.system().kind).label);

  private readonly key = computed(
    () => ({ companyId: this.companyId(), systemId: this.system().id }),
    { equal: sameParams },
  );
  protected readonly toolsRes = resource({
    params: () => this.key(),
    loader: ({ params }) => this.api.invoke(listSystemTools, params),
  });
  protected readonly tools = linkedSignal<readonly SystemTool[]>(() =>
    this.toolsRes.hasValue() ? this.toolsRes.value().items : [],
  );
  protected readonly toConfirm = computed(
    () => this.tools().filter((tool) => tool.effect === 'UNKNOWN').length,
  );
  /** The tools whoever manages the server is to confirm, first; for anyone else none are. */
  protected readonly pending = computed(() =>
    this.catalogue() && this.canManage()
      ? this.tools().filter((tool) => tool.effect === 'UNKNOWN')
      : [],
  );
  protected readonly rest = computed(() => {
    const pending = new Set(this.pending());
    return this.tools().filter((tool) => !pending.has(tool));
  });

  /** What names the list of the rest: "Confirmed" beside those to confirm, else the section. */
  protected readonly restTitle = computed(() =>
    this.catalogue() && this.canManage() && this.rest().length
      ? 'tools-confirmed-title'
      : 'tools-title',
  );

  /** A company system's credentials are added here too; a personal server holds none. */
  protected readonly offersCredential = computed(
    () => this.canManage() && holdsCredentials(this.system()),
  );
  /** The credentials to read the tools with, when there is a choice: not the bot's. */
  protected readonly pickable = computed(() =>
    this.offersCredential()
      ? this.credentials()
          .filter((c) => !c.bot)
          .map((c) => {
            const state = credentialState(c);
            return {
              id: c.id,
              name: c.name,
              note: state.tone === 'success' ? '' : ` (${state.label.toLowerCase()})`,
            };
          })
      : [],
  );
  /** The credential the next refresh names; empty: the server's choice. */
  protected readonly credentialId = linkedSignal<readonly { id: string }[], string>({
    source: this.pickable,
    computation: (pickable, previous) => {
      const kept = previous?.value ?? '';
      return pickable.some((c) => c.id === kept) ? kept : '';
    },
  });
  protected readonly addOpen = signal(false);

  protected readonly refreshing = signal(false);
  protected readonly refreshed = signal<SystemToolRefresh | null>(null);
  protected readonly busyTool = signal<string | null>(null);
  private readonly opened = signal<ReadonlySet<string>>(new Set());

  constructor() {
    effect(() => {
      if (!this.toolsRes.hasValue()) return;
      const summary = { count: this.tools().length, toConfirm: this.toConfirm() };
      untracked(() => this.summary.emit(summary));
    });
  }

  protected isOpen(name: string): boolean {
    return this.opened().has(name);
  }

  protected toggle(name: string): void {
    this.opened.update((opened) => {
      const next = new Set(opened);
      if (!next.delete(name)) next.add(name);
      return next;
    });
  }

  protected refreshText(result: SystemToolRefresh): string {
    const summary = refreshSummary(result);
    return asksForCredential(result) ? summary : `${summary} ${readWith(result)}`;
  }

  protected suggestionOf(tool: SystemTool): string | null {
    return suggestion(tool);
  }

  protected because(name: string): string | null {
    return unconfirmedBecause(name, this.refreshed());
  }

  protected effectLabel(effect: ToolEffect): string {
    switch (effect) {
      case 'READ':
        return 'Reads';
      case 'WRITE':
        return 'Writes';
      default:
        return 'Counts as a write';
    }
  }

  /** A credential the server asked for is added: the page reads them again, and the tools too. */
  protected onCredentialAdded(credential: SystemCredential): void {
    this.credentialAdded.emit(credential);
    this.notify.success(
      `${credential.name} is added to ${this.system().name}`,
      'Its tools are read with it now. Lend it to groups and service accounts under Overview.',
    );
    void this.refresh(credential.id);
  }

  protected async refresh(credentialId: string = this.credentialId()): Promise<void> {
    if (this.refreshing()) return;
    this.refreshing.set(true);
    this.refreshed.set(null);
    try {
      const result = await this.api.invoke(refreshSystemTools, {
        ...this.key(),
        ...(credentialId ? { credentialId } : {}),
      });
      this.refreshed.set(result);
      this.toolsRes.reload();
    } catch (error) {
      this.notify.error("Couldn't read the tools", error, TOOL_MESSAGES);
    } finally {
      this.refreshing.set(false);
    }
  }

  protected async confirm(tool: SystemTool, effect: Exclude<ToolEffect, 'UNKNOWN'>): Promise<void> {
    const wasPending = tool.effect === 'UNKNOWN';
    // Where the focus goes once a confirmed tool leaves "To confirm": the next one to confirm.
    const pending = this.pending();
    const next = pending[pending.indexOf(tool) + 1] ?? pending[pending.indexOf(tool) - 1];
    await this.change(tool, { effect }, () => {
      this.notify.success(
        `${tool.name} ${effect === 'READ' ? 'reads' : 'writes'}`,
        effect === 'READ'
          ? 'Agents may run it without asking where their definition says so.'
          : 'It counts as a write: a grant that is read-only never calls it.',
      );
      if (!wasPending) return;
      this.opened.update((opened) => {
        const rest = new Set(opened);
        rest.delete(tool.name);
        return rest;
      });
      const focus = next ? `tool-ask-${next.name}` : `tool-row-${tool.name}`;
      afterNextRender(() => this.document.getElementById(focus)?.focus(), {
        injector: this.injector,
      });
    });
  }

  protected async setEnabled(
    tool: SystemTool,
    enabled: boolean,
    toggle?: { readonly checked: { set(value: boolean): void } },
  ): Promise<void> {
    if (enabled === tool.enabled) return;
    const ok = await this.change(tool, { enabled });
    // A refused change shows what the server holds again.
    if (!ok) toggle?.checked.set(tool.enabled);
  }

  private async change(
    tool: SystemTool,
    body: { effect?: Exclude<ToolEffect, 'UNKNOWN'>; enabled?: boolean },
    done?: () => void,
  ): Promise<boolean> {
    if (this.busyTool()) return false;
    this.busyTool.set(tool.name);
    try {
      const updated = await this.api.invoke(updateSystemTool, {
        ...this.key(),
        toolName: tool.name,
        body,
      });
      this.tools.update((tools) => tools.map((t) => (t.name === updated.name ? updated : t)));
      done?.();
      return true;
    } catch (error) {
      this.notify.error(`Couldn't change ${tool.name}`, error, TOOL_MESSAGES);
      return false;
    } finally {
      this.busyTool.set(null);
    }
  }
}
