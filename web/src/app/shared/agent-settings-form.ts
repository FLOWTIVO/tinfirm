import { ChangeDetectionStrategy, Component, computed, input, model, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { NgIcon, provideIcons } from '@ng-icons/core';
import { lucideChevronDown, lucideClock, lucideInfo, lucideServer } from '@ng-icons/lucide';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmNativeSelectImports } from '@spartan-ng/helm/native-select';
import { HlmSwitch } from '@spartan-ng/helm/switch';
import { HlmTextarea } from '@spartan-ng/helm/textarea';
import { HlmInput } from '@spartan-ng/helm/input';
import type {
  Agent,
  AgentEffort,
  AgentRepository,
  AgentToolMode,
  AgentWidening,
  ModelSubscription,
  SystemKind,
} from '../api/generated/models';
import { SettingRow } from '../pages/company/settings/setting-row';
import {
  askOnEveryWrite,
  modelChoices,
  effectOfPick,
  type OwnServerOption,
  ownServerTools,
  ownToolMode,
  OWN_TOOL_CHOICES,
  payerOptions,
  payerValue,
  runtimeOf,
  runtimeOptions,
  type SettingsDraft,
  type SettingsErrorAt,
  settingsErrorAt,
  type SettingsErrors,
  type SettingsOwnServer,
  type SettingsOwnTool,
  type SettingsSystemRow,
  type SettingsToolChoice,
  type SettingsToolRow,
  settingsSystemRows,
  subscriptionGone,
  systemSummary,
  toolChoice,
  TOOL_CHOICES,
  TRIGGER_TOOL_CHOICES,
  withPayer,
  withToolChoice,
} from './agent-settings-model';
import {
  ACCESS_LABELS,
  EFFORT_LABELS,
  RUNTIME_LABELS,
  SYSTEM_KIND_LABELS,
  TOOL_EFFECT_LABELS,
} from './labels';
import { Segmented } from './segmented';

/** The parts of the agent the form needs. */
export type SettingsAgent = Pick<
  Agent,
  'name' | 'runtimes' | 'systems' | 'repositories' | 'memoryAllowed'
> &
  Partial<Pick<Agent, 'skills'>>;

const EFFORTS: readonly AgentEffort[] = ['DEFAULT', 'LOW', 'MEDIUM', 'HIGH', 'XHIGH', 'MAX'];

/** Trackers, whose projects a conversation may start in. */
const PROJECT_KINDS: ReadonlySet<SystemKind> = new Set<SystemKind>([
  'JIRA_DC',
  'JIRA_CLOUD',
  'GITHUB',
  'GITLAB',
]);

/** Whose settings these are: a person's own, or a trigger's (package 4). */
export type SettingsSubject = 'person' | 'trigger';

interface Words {
  readonly you: string;
  readonly yourBudget: string;
  readonly ownServers: string;
  readonly fixed: string;
  readonly nextTurn: string;
  readonly defaultsTitle: string;
  readonly payerHelp: string;
  readonly budgetNeedsModel: string;
  readonly systemOff: string;
  readonly asks: string;
  readonly askFirst: string;
  readonly openConversations: string;
  readonly budgetWas: string;
  readonly pending: (version: number | string) => string;
  /** What memory does, after the agent's name (ADR-0063). */
  readonly memory: string;
  readonly memoryOn: string;
  /** What automatic retries do, after "A turn of" (ADR-0073). */
  readonly autoRetry: string;
  readonly autoRetryOn: string;
  readonly skillOff: string;
  readonly skillOn: string;
}

const WORDS: Record<SettingsSubject, Words> = {
  person: {
    you: 'you',
    yourBudget: 'Your budget',
    ownServers: 'Your own MCP servers',
    fixed:
      'Fixed when a conversation starts: your open conversations keep the ones they started with.',
    nextTurn: 'Saved changes reach your open conversations too, from their next turn.',
    defaultsTitle: 'Model, instructions and defaults',
    payerHelp:
      'Your budget pays through the company’s model gateway. A subscription of yours pays only on its host.',
    budgetNeedsModel:
      'Your budget pays through the company’s model gateway, so a model is required.',
    systemOff: 'Off for your conversations',
    asks: 'asks you',
    askFirst: 'ask you first',
    openConversations: 'Open conversations',
    budgetWas: 'your budget',
    pending: (v) => `yours after you accept version ${v}`,
    memory:
      'keeps short notes of what it learns in your conversations: how you work, your repositories, your systems. Only you see them, and only your conversations read them.',
    memoryOn: 'Remember across your conversations',
    autoRetry: 'your conversations',
    autoRetryOn: 'Retry your failed turns by themselves',
    skillOff: 'Off for your conversations',
    skillOn: 'Read when a task fits',
  },
  trigger: {
    you: 'the trigger',
    yourBudget: 'The payer’s budget',
    ownServers: 'Your own MCP servers',
    fixed:
      'Fixed when a conversation starts: the trigger’s open conversations keep the ones they started with.',
    nextTurn: 'Saved changes reach the trigger’s open conversations too, from their next turn.',
    defaultsTitle: 'Model and defaults',
    payerHelp:
      'A budget pays through the company’s model gateway. A subscription pays only on its host.',
    budgetNeedsModel: 'A budget pays through the company’s model gateway, so a model is required.',
    systemOff: 'Off for the trigger’s conversations',
    asks: 'asks first',
    askFirst: 'ask first',
    openConversations: 'The trigger’s open conversations',
    budgetWas: 'the payer’s budget',
    pending: (v) => `after you accept version ${v}`,
    memory:
      'keeps short notes for the trigger’s later conversations. Each note waits for the trigger’s team to accept it before any conversation reads it.',
    memoryOn: 'Remember across the trigger’s conversations',
    autoRetry: 'the trigger’s conversations',
    autoRetryOn: 'Retry the trigger’s failed turns by themselves',
    skillOff: 'Off for the trigger’s conversations',
    skillOn: 'Read when a task fits',
  },
};

let nextId = 0;

/**
 * The settings of one agent (plan §4.4), shared by the settings page and the trigger editor
 * (package 4, with `subject="trigger"`), as three sheets of setting rows, and a fourth for the
 * agent's skills when it has any. The runtime and who pays, which open conversations keep from
 * their start; the model, effort, added instructions (a
 * person's only: a trigger has its own instruction), default repositories and projects; and the
 * systems and tools, which apply from each conversation's next turn: each of the agent's systems
 * on or off, its tools behind a chevron, each "As the agent says", "Ask me" or "Off" (never
 * "Allowed": settings narrow, I4), and one's own MCP servers with each tool "Allowed" or "Needs
 * your approval"; and the agent's skills, each on or off (ADR-0064), which apply from each
 * conversation's next turn. What a version not accepted yet adds is shown, disabled. It edits
 * `draft` only; the page saves it and maps a failed save to `errors`.
 */
@Component({
  selector: 'app-agent-settings-form',
  imports: [
    RouterLink,
    NgIcon,
    HlmButton,
    HlmInput,
    HlmNativeSelectImports,
    HlmSwitch,
    HlmTextarea,
    Segmented,
    SettingRow,
  ],
  providers: [provideIcons({ lucideChevronDown, lucideClock, lucideInfo, lucideServer })],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'flex flex-col gap-5', 'data-testid': 'agent-settings-form' },
  template: `
    @let d = draft();
    @let w = words();
    @let a = agent();
    @let was = saved();
    <fieldset class="m-0 flex min-w-0 flex-col gap-5 border-0 p-0" [disabled]="disabled()">
      <!-- Runtime and who pays: fixed when a conversation starts. -->
      <section class="bg-card min-w-0 rounded-xl border" [attr.aria-labelledby]="id + '-start'">
        <div class="px-4 pt-4 pb-3.5 sm:px-5">
          <div
            role="heading"
            class="text-base font-bold"
            [attr.aria-level]="headingLevel()"
            [id]="id + '-start'"
          >
            Runtime and who pays
          </div>
          <p class="text-muted-foreground mt-0.5 text-sm text-pretty" data-testid="fixed-note">
            {{ w.fixed }}
          </p>
        </div>

        <app-setting-row [rowId]="id + '-runtime'" label="Runtime" data-field="runtime">
          <span help>What runs {{ a.name }}’s turns.</span>
          @if (a.runtimes.length > 1) {
            <app-segmented
              [inputId]="id + '-runtime-choice'"
              [labelledBy]="id + '-runtime-label'"
              [options]="runtimes()"
              [value]="runtime()"
              [disabled]="disabled()"
              (valueChange)="setRuntime($event)"
            />
          } @else {
            <p class="pt-px text-sm" data-testid="only-runtime">
              {{ runtimeLabels[runtime()] }}
              <span class="text-muted-foreground">· {{ a.name }}’s only runtime</span>
            </p>
          }
          @if (errors().runtime; as message) {
            <p class="text-destructive text-sm" role="alert" data-error="runtime">{{ message }}</p>
          }
        </app-setting-row>

        <app-setting-row [rowId]="id + '-payer'" label="Who pays" data-field="payer">
          <span help>{{ w.payerHelp }}</span>
          <div
            class="flex w-full min-w-0 flex-col gap-2"
            role="radiogroup"
            [attr.aria-labelledby]="id + '-payer-label'"
          >
            @for (option of payers(); track option.value) {
              <label
                class="has-checked:border-primary has-checked:bg-primary/10 has-focus-visible:ring-ring/50 flex min-h-11 cursor-pointer items-start gap-2.5 rounded-lg border px-3 py-2.5 has-disabled:cursor-not-allowed has-disabled:opacity-70 has-focus-visible:ring-3 sm:min-h-0"
                [attr.data-payer]="option.value"
              >
                <input
                  type="radio"
                  class="accent-primary mt-0.5 size-4 shrink-0"
                  [name]="id + '-payer'"
                  [value]="option.value"
                  [checked]="payer() === option.value"
                  [disabled]="disabled() || option.disabled"
                  (change)="setPayer(option.value)"
                />
                <span class="flex min-w-0 flex-col">
                  <span class="text-sm font-semibold">{{ option.label }}</span>
                  @if (option.detail) {
                    <span class="text-muted-foreground text-xs break-words">{{
                      option.detail
                    }}</span>
                  }
                </span>
              </label>
            }
            @if (payerWas(); as text) {
              <span class="text-muted-foreground text-xs" data-testid="payer-was"
                >was {{ text }}</span
              >
            }
          </div>
          @if (gone()) {
            <p
              class="text-warning flex items-start gap-1.5 text-xs"
              data-testid="subscription-gone"
            >
              <ng-icon name="lucideClock" size="14" class="mt-px shrink-0" aria-hidden="true" />
              The subscription in these settings cannot pay now: new conversations are refused until
              you pick another, or your budget.
            </p>
          }
          @if (!allowSubscriptions()) {
            <p class="text-muted-foreground text-xs" data-testid="subscriptions-note">
              {{ subscriptionsNote() ?? 'A subscription can pay only when you are the payer.' }}
            </p>
          }
          @if (errors().payer; as message) {
            <p class="text-destructive text-sm" role="alert" data-error="payer">{{ message }}</p>
          }
        </app-setting-row>
      </section>

      <!-- Model, instructions and defaults: from each open conversation's next turn. -->
      <section class="bg-card min-w-0 rounded-xl border" [attr.aria-labelledby]="id + '-turn'">
        <div class="px-4 pt-4 pb-3.5 sm:px-5">
          <div
            role="heading"
            class="text-base font-bold"
            [attr.aria-level]="headingLevel()"
            [id]="id + '-turn'"
          >
            {{ w.defaultsTitle }}
          </div>
          <p class="text-muted-foreground mt-0.5 text-sm text-pretty">{{ w.nextTurn }}</p>
        </div>

        <app-setting-row
          [rowId]="id + '-model-row'"
          label="Model"
          [for]="id + '-model'"
          data-field="model"
        >
          <span help>
            Among the models your model access allows.
            @if (d.modelAccess !== 'SUBSCRIPTION') {
              {{ w.budgetNeedsModel }}
            }
          </span>
          <div class="flex w-full min-w-0 flex-wrap items-center gap-x-2.5 gap-y-1">
            <hlm-native-select
              class="w-full sm:max-w-sm"
              selectClass="max-sm:h-11"
              [selectId]="id + '-model'"
              [value]="d.model"
              [disabled]="disabled() || modelsUnavailable() !== null"
              (valueChange)="patch({ model: $event ?? '' })"
            >
              @if (d.modelAccess === 'SUBSCRIPTION') {
                <option hlmNativeSelectOption value="">The runtime’s default</option>
              } @else {
                <option hlmNativeSelectOption value="" disabled>Choose a model</option>
              }
              @for (m of models(); track m.value) {
                <option hlmNativeSelectOption [value]="m.value">{{ m.label }}</option>
              }
            </hlm-native-select>
            @if (was && was.model !== d.model) {
              <span class="text-muted-foreground text-xs" data-testid="model-was"
                >was
                @if (was.model) {
                  <span class="font-mono">{{ was.model }}</span>
                } @else {
                  the runtime’s default
                }
              </span>
            }
          </div>
          @if (modelsUnavailable(); as reason) {
            <p
              class="text-muted-foreground flex items-start gap-1.5 text-[0.8125rem] leading-normal"
              data-testid="models-unavailable"
            >
              <ng-icon name="lucideInfo" size="14" class="mt-0.5 shrink-0" aria-hidden="true" />
              {{ reason }}
            </p>
          }
          @if (errors().model; as message) {
            <p class="text-destructive text-sm" role="alert" data-error="model">{{ message }}</p>
          }
        </app-setting-row>

        <app-setting-row
          [rowId]="id + '-effort-row'"
          label="Effort"
          [for]="id + '-effort'"
          data-field="effort"
        >
          <span help>How much the runtime thinks before it acts.</span>
          <div class="flex w-full min-w-0 flex-wrap items-center gap-x-2.5 gap-y-1">
            <hlm-native-select
              class="w-full sm:max-w-sm"
              selectClass="max-sm:h-11"
              [selectId]="id + '-effort'"
              [value]="d.effort"
              [disabled]="disabled()"
              (valueChange)="patch({ effort: $any($event) || 'DEFAULT' })"
            >
              @for (e of efforts; track e) {
                <option hlmNativeSelectOption [value]="e">{{ effortLabels[e] }}</option>
              }
            </hlm-native-select>
            @if (was && was.effort !== d.effort) {
              <span class="text-muted-foreground text-xs" data-testid="effort-was"
                >was
                {{
                  was.effort === 'DEFAULT' ? 'the runtime’s default' : effortLabels[was.effort]
                }}</span
              >
            }
          </div>
        </app-setting-row>

        @if (subject() === 'person') {
          <app-setting-row
            [rowId]="id + '-instructions-row'"
            label="Added instructions"
            [for]="id + '-instructions'"
            data-field="instructions"
          >
            <span help>Added after {{ a.name }}’s own instructions.</span>
            <textarea
              hlmTextarea
              rows="3"
              class="min-h-21 w-full [field-sizing:content]"
              [id]="id + '-instructions'"
              [value]="d.instructions"
              [attr.aria-describedby]="id + '-instructions-row-help'"
              placeholder="Answer in German. Keep briefs under a page."
              (input)="patch({ instructions: $any($event.target).value })"
            ></textarea>
          </app-setting-row>
        }

        <app-setting-row
          [rowId]="id + '-memory-row'"
          label="Memory"
          kind="switch"
          data-field="memory"
        >
          <span help data-testid="memory-hint">
            @if (!memoryAllowed()) {
              {{ a.name }}’s editor does not allow memory, so none is read or written.
              @if (d.memory) {
                The notes it kept stay until they are deleted.
              }
            } @else {
              {{ a.name }} {{ w.memory }} Off keeps the notes, unread and unwritten.
            }
          </span>
          <hlm-switch
            data-testid="memory-switch"
            [inputId]="id + '-memory'"
            [checked]="d.memory"
            [disabled]="disabled() || !memoryAllowed()"
            [aria-label]="w.memoryOn"
            [aria-describedby]="id + '-memory-row-help'"
            (checkedChange)="patch({ memory: $event })"
          />
        </app-setting-row>

        <app-setting-row
          [rowId]="id + '-auto-retry-row'"
          label="Automatic retries"
          kind="switch"
          data-field="autoRetry"
        >
          <span help data-testid="auto-retry-hint">
            A turn of {{ w.autoRetry }} that fails because the model’s provider is overloaded or
            unavailable, or its runner host was lost, is retried by itself up to three times, after
            1, 2 and 4 minutes. Each retry is paid as the turn was.
          </span>
          <hlm-switch
            data-testid="auto-retry-switch"
            [inputId]="id + '-auto-retry'"
            [checked]="d.autoRetry"
            [disabled]="disabled()"
            [aria-label]="w.autoRetryOn"
            [aria-describedby]="id + '-auto-retry-row-help'"
            (checkedChange)="patch({ autoRetry: $event })"
          />
        </app-setting-row>

        @if (a.repositories.length > 0) {
          <app-setting-row
            [rowId]="id + '-repositories'"
            label="Default repositories"
            data-field="repositories"
          >
            <span help
              >A new conversation’s workspace. None picked: every repository of {{ a.name }}.</span
            >
            <div
              class="flex w-full min-w-0 flex-col gap-0.5"
              role="group"
              [attr.aria-labelledby]="id + '-repositories-label'"
            >
              @for (r of a.repositories; track r.repositoryId) {
                @let pending = repositoryPending(r);
                <label
                  class="flex min-h-11 min-w-0 cursor-pointer items-center gap-2.5 text-sm has-disabled:cursor-not-allowed sm:min-h-8"
                  [attr.data-repository]="r.repositoryId"
                >
                  <input
                    type="checkbox"
                    class="accent-primary size-4 shrink-0"
                    [checked]="d.defaultRepositoryIds.includes(r.repositoryId)"
                    [disabled]="disabled() || pending === 'new'"
                    (change)="toggleRepository(r.repositoryId, $any($event.target).checked)"
                  />
                  <span class="truncate font-mono text-[0.8125rem]">{{ r.name }}</span>
                  <span class="text-muted-foreground min-w-0 text-[0.8125rem]">{{
                    repositoryAccess(r, pending)
                  }}</span>
                </label>
              }
            </div>
            @if (errors().repositories; as message) {
              <p class="text-destructive text-sm" role="alert" data-error="repositories">
                {{ message }}
              </p>
            }
          </app-setting-row>
        }

        @if (projectSystems().length > 0) {
          <app-setting-row
            [rowId]="id + '-projects'"
            label="Default projects"
            data-field="projects"
          >
            <span help>Where a new conversation looks first. Separate them with commas.</span>
            <div class="flex w-full min-w-0 flex-col gap-3">
              @for (s of projectSystems(); track s.systemId) {
                <div class="flex min-w-0 flex-col gap-1">
                  <label
                    class="text-muted-foreground text-xs font-semibold"
                    [for]="id + '-projects-' + s.systemId"
                    >{{ s.name }}</label
                  >
                  <input
                    hlmInput
                    class="font-mono max-sm:h-11 sm:max-w-sm"
                    autocomplete="off"
                    [id]="id + '-projects-' + s.systemId"
                    [value]="projectsText(s.systemId)"
                    [placeholder]="
                      s.kind === 'JIRA_DC' || s.kind === 'JIRA_CLOUD' ? 'SHOP, OPS' : 'acme/shop'
                    "
                    (change)="setProjects(s.systemId, $any($event.target).value)"
                  />
                </div>
              }
            </div>
          </app-setting-row>
        }
      </section>

      <!-- Systems and tools: what the agent may do, narrowed. -->
      <section class="bg-card min-w-0 rounded-xl border" [attr.aria-labelledby]="id + '-tools'">
        <div class="px-4 pt-4 pb-3.5 sm:px-5">
          <div
            role="heading"
            class="text-base font-bold"
            [attr.aria-level]="headingLevel()"
            [id]="id + '-tools'"
          >
            Systems and tools
          </div>
          <p class="text-muted-foreground mt-0.5 text-sm text-pretty">
            Narrow what {{ a.name }} may do for {{ w.you }}: switch a system off, or make a tool
            {{ w.askFirst }}. {{ w.openConversations }} take this at their next turn.
          </p>
        </div>

        <div class="flex flex-col gap-3 border-t px-4 pt-4 pb-5 sm:px-5" data-field="systems">
          @if (askEveryWriteShown() && systemRows().length > 0) {
            <div class="flex flex-wrap items-center gap-x-3 gap-y-1">
              <button
                hlmBtn
                variant="outline"
                size="sm"
                type="button"
                class="max-sm:h-11"
                data-testid="settings-ask-every-write"
                [disabled]="disabled() || !askEveryWriteChanges()"
                (click)="askEveryWrite()"
              >
                Ask before every write
              </button>
              <span class="text-muted-foreground text-xs">
                Settings never let a tool run that the agent asks for.
              </span>
            </div>
          }

          @for (s of systemRows(); track s.systemId) {
            @let off = d.systemsOff.includes(s.systemId);
            @let open = isOpen(s.systemId) && !off && s.tools.length > 0;
            <div
              class="flex min-w-0 flex-col rounded-lg border"
              role="group"
              [attr.aria-labelledby]="id + '-sys-' + s.systemId"
              [attr.data-system]="s.systemId"
            >
              <div class="flex min-h-13 items-center gap-1 py-1 ps-3.5 pe-1 sm:gap-2.5 sm:pe-2">
                <div
                  class="flex min-w-0 flex-1 flex-col sm:flex-row sm:flex-wrap sm:items-center sm:gap-x-2.5"
                >
                  <span class="min-w-0 text-sm leading-snug">
                    <span class="font-semibold" [id]="id + '-sys-' + s.systemId">{{ s.name }}</span>
                    @if (kindLabel(s); as kind) {
                      <span class="text-muted-foreground ms-1.5 text-[0.8125rem]">{{ kind }}</span>
                    }
                  </span>
                  <span
                    class="text-muted-foreground text-[0.8125rem] leading-snug sm:ms-auto"
                    data-summary
                    >{{ summaryOf(s, off) }}</span
                  >
                </div>
                <span
                  class="flex h-11 w-13 shrink-0 items-center justify-center sm:h-auto sm:w-auto [&_button]:relative [&_button]:after:absolute [&_button]:after:-inset-x-2.5 [&_button]:after:-inset-y-3.5 sm:[&_button]:after:-inset-2"
                >
                  <hlm-switch
                    [checked]="!off"
                    [disabled]="disabled() || s.pending"
                    [attr.data-testid]="'system-switch-' + s.systemId"
                    [aria-label]="(off ? 'Switch on ' : 'Switch off ') + s.name"
                    (checkedChange)="toggleSystem(s.systemId, $event)"
                  />
                </span>
                @if (s.tools.length > 0) {
                  <button
                    hlmBtn
                    variant="ghost"
                    size="icon-sm"
                    type="button"
                    class="text-muted-foreground max-sm:size-11"
                    [attr.aria-expanded]="open"
                    [attr.aria-controls]="open ? id + '-tools-' + s.systemId : null"
                    [attr.aria-label]="(open ? 'Hide ' : 'Show ') + s.name + ' tools'"
                    [attr.data-testid]="'system-expand-' + s.systemId"
                    [disabled]="off"
                    (click)="toggleOpen(s.systemId)"
                  >
                    <ng-icon
                      name="lucideChevronDown"
                      class="transition-transform motion-reduce:transition-none"
                      [class.rotate-180]="open"
                      aria-hidden="true"
                    />
                  </button>
                }
              </div>
              @if (open) {
                <ul
                  class="m-0 flex list-none flex-col divide-y border-t p-0"
                  [id]="id + '-tools-' + s.systemId"
                >
                  @for (t of s.tools; track t.name) {
                    @let choice = choiceOf(t);
                    <li
                      class="flex min-w-0 flex-col gap-2 py-2.5 ps-3.5 pe-3 sm:flex-row sm:flex-wrap sm:items-center sm:justify-between sm:gap-x-4"
                      [attr.data-tool]="t.name"
                      [attr.data-agent-mode]="t.agentMode"
                      [attr.data-choice]="choice"
                    >
                      <div class="flex min-w-0 flex-col">
                        <div class="flex flex-wrap items-center gap-x-2 gap-y-1">
                          <span class="text-sm">{{ t.title }}</span>
                          @if (choice !== '' && !t.pending) {
                            <span
                              class="bg-secondary text-muted-foreground inline-flex h-5 items-center rounded-full px-2 text-xs leading-none font-semibold"
                              data-testid="narrowed"
                              >Narrowed</span
                            >
                          }
                        </div>
                        <span class="text-muted-foreground text-xs">{{ toolLine(t) }}</span>
                      </div>
                      <app-segmented
                        class="max-sm:[&_label]:h-10"
                        [inputId]="id + '-tool-' + s.systemId + '-' + t.name + '-choice'"
                        [label]="t.title + ': how it goes for ' + w.you"
                        [options]="toolChoices()"
                        [value]="choice"
                        [disabled]="disabled() || t.pending"
                        (valueChange)="setTool(s.systemId, t.name, $any($event))"
                      />
                    </li>
                  }
                </ul>
              }
              @if (pointedAt('system', s.systemId); as message) {
                <p
                  class="text-destructive border-t px-3.5 py-2 text-sm"
                  role="alert"
                  data-error-at
                  data-error="systems"
                >
                  {{ message }}
                </p>
              }
            </div>
          } @empty {
            <p class="text-muted-foreground text-sm">{{ a.name }} uses no system.</p>
          }
          @if (errors().systems ?? pointedAt('section'); as message) {
            <p class="text-destructive text-sm" role="alert" data-error="systems">{{ message }}</p>
          }
        </div>

        <div class="flex flex-col gap-2.5 border-t px-4 pt-4 pb-5 sm:px-5" data-field="own-servers">
          <div>
            <div class="text-sm font-semibold">{{ w.ownServers }}</div>
            <p class="text-muted-foreground mt-0.5 text-[0.8125rem] leading-normal text-pretty">
              Add an MCP server of your own: only you see it, and its tools act with your account.
            </p>
          </div>
          @if (!ownServersAllowed()) {
            <p class="text-muted-foreground text-[0.8125rem]" data-testid="own-servers-not-allowed">
              {{ ownServersNote() ?? 'Your own MCP servers cannot be added here.' }}
            </p>
          } @else {
            @for (o of ownServers(); track o.system.id) {
              @let added = ownServerOf(o.system.id);
              <div
                class="flex min-w-0 flex-col rounded-lg border"
                role="group"
                [attr.aria-labelledby]="id + '-own-' + o.system.id"
                [attr.data-own-server]="o.system.id"
              >
                <div
                  class="flex min-h-13 items-center justify-between gap-3 py-1 ps-3.5 pe-1 sm:pe-3"
                >
                  <span
                    class="min-w-0 truncate text-sm font-semibold"
                    [id]="id + '-own-' + o.system.id"
                    >{{ o.system.name }}</span
                  >
                  <span
                    class="flex h-11 w-13 shrink-0 items-center justify-center sm:h-auto sm:w-auto [&_button]:relative [&_button]:after:absolute [&_button]:after:-inset-x-2.5 [&_button]:after:-inset-y-3.5 sm:[&_button]:after:-inset-2"
                  >
                    <hlm-switch
                      [checked]="!!added"
                      [disabled]="disabled()"
                      [aria-label]="(added ? 'Remove ' : 'Add ') + o.system.name"
                      (checkedChange)="toggleOwnServer(o, $event)"
                    />
                  </span>
                </div>
                @if (added?.tools; as tools) {
                  <ul class="m-0 flex list-none flex-col divide-y border-t p-0">
                    @for (t of tools; track t.name) {
                      <li
                        class="flex min-w-0 flex-col gap-2 py-2.5 ps-3.5 pe-3 sm:flex-row sm:flex-wrap sm:items-center sm:justify-between sm:gap-x-4"
                        [attr.data-tool]="t.name"
                      >
                        <div class="flex min-w-0 flex-col">
                          <span class="text-sm">{{ ownToolTitle(o, t.name) }}</span>
                          <span class="text-muted-foreground text-xs"
                            >{{ ownToolEffect(o, t.name) }}
                            @if (ownAsksSinceItWrites(o, t)) {
                              <span data-testid="own-asks-since-it-writes"
                                >· needs approval: it was allowed while it read, and now writes.
                                Pick “Allowed” to allow it for writes too; one save does it.</span
                              >
                            }
                          </span>
                        </div>
                        <app-segmented
                          class="max-sm:[&_label]:h-10"
                          [inputId]="id + '-own-' + o.system.id + '-' + t.name"
                          [label]="ownToolTitle(o, t.name) + ': allowed or needs your approval'"
                          [options]="ownChoices"
                          [value]="ownModeOf(o, t)"
                          [disabled]="disabled()"
                          (valueChange)="setOwnTool(o, t.name, $any($event))"
                        />
                      </li>
                    }
                  </ul>
                } @else if (added) {
                  <p class="text-muted-foreground border-t px-3.5 py-2 text-xs">
                    Its reads are allowed and its writes need your approval, as it serves them.
                  </p>
                }
                @if (pointedAt('own', o.system.id); as message) {
                  <p
                    class="text-destructive border-t px-3.5 py-2 text-sm"
                    role="alert"
                    data-error-at
                    data-error="ownServers"
                  >
                    {{ message }}
                  </p>
                }
              </div>
            } @empty {
              <p
                class="border-input text-muted-foreground flex flex-wrap items-center gap-x-2.5 gap-y-1 rounded-lg border border-dashed px-3.5 py-3 text-[0.8125rem]"
                data-testid="no-own-servers"
              >
                <ng-icon name="lucideServer" class="shrink-0" aria-hidden="true" />
                You have no MCP server of your own.
                @if (accountsLink(); as link) {
                  <a
                    class="text-primary inline-flex min-h-11 items-center font-semibold underline-offset-2 hover:underline sm:min-h-0"
                    [routerLink]="link"
                    >Register one under Accounts</a
                  >
                } @else {
                  Register one under Accounts.
                }
              </p>
            }
          }
          @if (errors().ownServers; as message) {
            <p class="text-destructive text-sm" role="alert" data-error="ownServers">
              {{ message }}
            </p>
          }
        </div>
      </section>

      <!-- Skills: the agent's know-how, each switched on or off (ADR-0064). -->
      @if (skills().length > 0) {
        <section
          class="bg-card min-w-0 rounded-xl border"
          data-testid="settings-skills"
          [attr.aria-labelledby]="id + '-skills'"
        >
          <div class="px-4 pt-4 pb-3.5 sm:px-5">
            <div
              role="heading"
              class="text-base font-bold"
              [attr.aria-level]="headingLevel()"
              [id]="id + '-skills'"
            >
              Skills
            </div>
            <p class="text-muted-foreground mt-0.5 text-sm text-pretty">
              What {{ a.name }} knows how to do, from the company’s library: it reads a skill when a
              task fits. Switch one off for {{ w.you }}. {{ w.openConversations }} take this at
              their next turn.
            </p>
          </div>
          <ul
            class="m-0 flex list-none flex-col divide-y border-t p-0"
            data-field="skills"
            [attr.aria-labelledby]="id + '-skills'"
          >
            @for (k of skills(); track k.skillId) {
              @let off = d.skillsOff.includes(k.skillId);
              <li
                class="flex min-h-13 min-w-0 items-center gap-2.5 py-2 ps-4 pe-2 sm:ps-5 sm:pe-4"
                [attr.data-skill]="k.skillId"
              >
                <div class="flex min-w-0 flex-1 flex-col">
                  <span class="min-w-0 text-sm leading-snug">
                    <span class="font-mono font-semibold" [id]="id + '-skill-' + k.skillId">{{
                      k.name
                    }}</span>
                    <span class="text-muted-foreground ms-1.5 font-mono text-xs"
                      >v{{ k.version }}</span
                    >
                  </span>
                  <span class="text-muted-foreground text-[0.8125rem] leading-snug text-pretty">{{
                    k.description
                  }}</span>
                  <span class="text-muted-foreground text-xs" data-summary>{{
                    off ? w.skillOff : w.skillOn
                  }}</span>
                </div>
                <span
                  class="flex h-11 w-13 shrink-0 items-center justify-center sm:h-auto sm:w-auto [&_button]:relative [&_button]:after:absolute [&_button]:after:-inset-x-2.5 [&_button]:after:-inset-y-3.5 sm:[&_button]:after:-inset-2"
                >
                  <hlm-switch
                    [checked]="!off"
                    [disabled]="disabled()"
                    [attr.data-testid]="'skill-switch-' + k.skillId"
                    [aria-label]="(off ? 'Switch on ' : 'Switch off ') + k.name"
                    (checkedChange)="toggleSkill(k.skillId, $event)"
                  />
                </span>
              </li>
            }
          </ul>
          @if (errors().skills; as message) {
            <p
              class="text-destructive border-t px-4 py-2 text-sm sm:px-5"
              role="alert"
              data-error="skills"
            >
              {{ message }}
            </p>
          }
        </section>
      }
    </fieldset>
  `,
})
export class AgentSettingsForm {
  readonly agent = input.required<SettingsAgent>();
  /** What a version the reader has not accepted adds: its tools and systems are not theirs yet. */
  readonly widening = input<AgentWidening | null>(null);
  readonly draft = model.required<SettingsDraft>();

  /** The reader's subscriptions on their own runner hosts (`getMyModelAccess`). */
  readonly subscriptions = input<readonly ModelSubscription[]>([]);
  /**
   * False where a subscription may not pay: a trigger whose payer is not its author, or one of a
   * company that lets a trigger pay only with a budget (ADR-0085, decision 7).
   */
  readonly allowSubscriptions = input(true);
  /** Why a subscription may not pay, in place of the default note. */
  readonly subscriptionsNote = input<string | null>(null);
  /** The gateway's models (`listGatewayModels`). */
  readonly gatewayModels = input<readonly string[]>([]);
  /** The models the reader's key allows; empty allows every one. */
  readonly allowedModels = input<readonly string[]>([]);
  /** Why no model can be picked (the gateway is not configured), which disables the field. */
  readonly modelsUnavailable = input<string | null>(null);

  /** The reader's own MCP servers, with their tools. */
  readonly ownServers = input<readonly OwnServerOption[]>([]);
  /** False where one's own servers may not be added (a trigger acting as someone else). */
  readonly ownServersAllowed = input(true);
  readonly ownServersNote = input<string | null>(null);

  readonly errors = input<SettingsErrors>({});
  /** What a refused save named (`settingsFailure(error).at`), said where it stands. */
  readonly errorAt = input<SettingsErrorAt | null>(null);
  readonly disabled = input(false);
  readonly subject = input<SettingsSubject>('person');

  /** The settings as saved, to say what a changed field "was"; none, nothing is said. */
  readonly saved = input<SettingsDraft | null>(null);
  /** What the budget has left ("$38.20 of $50.00 left, resets 1 Nov"), under its choice. */
  readonly budgetDetail = input<string | null>(null);
  /** Why the budget cannot pay (the gateway is not set up), which disables its choice. */
  readonly budgetUnavailable = input<string | null>(null);
  /** Where one's own MCP servers are registered, linked when there is none. */
  readonly accountsLink = input<readonly string[] | null>(null);
  /** Whether the systems start open; by default a person's start closed and a trigger's open. */
  readonly systemsExpanded = input<boolean | null>(null);
  /** "Ask before every write" above the systems; by default on a person's settings only. */
  readonly offerAskEveryWrite = input<boolean | null>(null);

  protected readonly id = `settings-${nextId++}`;
  protected readonly efforts = EFFORTS;
  protected readonly runtimeLabels = RUNTIME_LABELS;
  protected readonly effortLabels = EFFORT_LABELS;
  protected readonly ownChoices = OWN_TOOL_CHOICES;

  protected readonly words = computed(() => WORDS[this.subject()]);
  /** Whether the agent's editor lets its people and triggers turn memory on (absent: allowed). */
  protected readonly memoryAllowed = computed(() => this.agent().memoryAllowed !== false);
  /** A person's page names its sheets under its h1; the trigger editor, under its own h2. */
  protected readonly headingLevel = computed(() => (this.subject() === 'trigger' ? 3 : 2));
  protected readonly toolChoices = computed(() =>
    this.subject() === 'trigger' ? TRIGGER_TOOL_CHOICES : TOOL_CHOICES,
  );
  protected readonly askEveryWriteShown = computed(
    () => this.offerAskEveryWrite() ?? this.subject() === 'person',
  );

  private readonly pointed = computed(() => {
    const at = this.errorAt();
    return at ? { at, ...settingsErrorAt(at, this.agent(), this.ownServers()) } : null;
  });

  /** The sentence of a refused save that names this system or own server, or the section. */
  protected pointedAt(place: 'system' | 'own' | 'section', systemId?: string): string | null {
    const p = this.pointed();
    if (!p || p.place !== place) return null;
    return place === 'section' || p.at.systemId === systemId ? p.message : null;
  }
  protected readonly runtimes = computed(() => runtimeOptions(this.agent()));
  protected readonly runtime = computed(() => runtimeOf(this.draft(), this.agent()));
  protected readonly payer = computed(() => payerValue(this.draft()));
  private readonly offeredSubscriptions = computed(() =>
    this.allowSubscriptions() ? this.subscriptions() : [],
  );
  protected readonly payers = computed(() =>
    payerOptions(
      this.offeredSubscriptions(),
      this.runtime(),
      this.draft(),
      this.words().yourBudget,
    ).map((option) => {
      if (option.value !== 'BUDGET') return option;
      const unavailable = this.budgetUnavailable();
      return unavailable
        ? { ...option, detail: unavailable, disabled: true }
        : { ...option, detail: this.budgetDetail() };
    }),
  );
  /** Who paid as saved, while the draft picks another. */
  protected readonly payerWas = computed(() => {
    const saved = this.saved();
    if (!saved || payerValue(saved) === this.payer()) return null;
    return saved.modelAccess === 'SUBSCRIPTION'
      ? `“${saved.subscription?.label ?? 'a subscription'}”`
      : this.words().budgetWas;
  });
  protected readonly gone = computed(() =>
    subscriptionGone(this.offeredSubscriptions(), this.runtime(), this.draft()),
  );
  protected readonly models = computed(() =>
    modelChoices(this.gatewayModels(), this.allowedModels(), this.draft().model),
  );
  protected readonly systemRows = computed(() => settingsSystemRows(this.agent(), this.widening()));
  /** The agent's skills, each switched on or off for the reader. */
  protected readonly skills = computed(() => this.agent().skills ?? []);
  protected readonly projectSystems = computed(() =>
    this.agent().systems.filter((s) => PROJECT_KINDS.has(s.kind)),
  );
  /** The version whose additions wait, as the rows name it. */
  private readonly pendingVersion = computed(() => this.widening()?.currentVersion ?? null);
  /** Whether "Ask before every write" would change anything. */
  protected readonly askEveryWriteChanges = computed(
    () =>
      askOnEveryWrite(this.agent(), this.draft(), this.widening()).toolModes.length !==
      this.draft().toolModes.length,
  );

  /** The systems the reader opened or closed; the rest follow `systemsExpanded`. */
  private readonly toggled = signal<Readonly<Record<string, boolean>>>({});

  protected isOpen(systemId: string): boolean {
    return this.toggled()[systemId] ?? this.systemsExpanded() ?? this.subject() === 'trigger';
  }

  protected toggleOpen(systemId: string): void {
    const open = this.isOpen(systemId);
    this.toggled.update((t) => ({ ...t, [systemId]: !open }));
  }

  protected kindLabel(row: SettingsSystemRow): string | null {
    const label = SYSTEM_KIND_LABELS[row.kind];
    return label && label !== row.name ? label : null;
  }

  protected summaryOf(row: SettingsSystemRow, off: boolean): string {
    const w = this.words();
    if (row.pending) {
      const pending = w.pending(this.pendingVersion() ?? 'not accepted yet');
      return pending.charAt(0).toUpperCase() + pending.slice(1);
    }
    if (off) return w.systemOff;
    if (row.tools.length === 0) return 'Offers no tool';
    return systemSummary(row, this.draft(), this.pendingVersion());
  }

  /** "Writes · Analyst asks you", and when a version not accepted yet brings it. */
  protected toolLine(row: SettingsToolRow): string {
    const w = this.words();
    return [
      row.effectLabel,
      `${this.agent().name} ${row.agentMode === 'ASK' ? w.asks : 'runs it'}`,
      row.pending ? w.pending(this.pendingVersion() ?? 'not accepted yet') : '',
    ]
      .filter(Boolean)
      .join(' · ');
  }

  /** How a version not accepted yet changes a repository: `new`, `widened`, or not at all. */
  protected repositoryPending(repo: AgentRepository): 'new' | 'widened' | null {
    const widened = this.widening()?.repositories.find((r) => r.repositoryId === repo.repositoryId);
    if (!widened) return null;
    return widened.was ? 'widened' : 'new';
  }

  protected repositoryAccess(repo: AgentRepository, pending: 'new' | 'widened' | null): string {
    const version = this.pendingVersion() ?? 'not accepted yet';
    const widened = this.widening()?.repositories.find((r) => r.repositoryId === repo.repositoryId);
    if (pending === 'new') {
      return `${ACCESS_LABELS[repo.access]} · ${this.words().pending(version)}`;
    }
    if (pending === 'widened' && widened?.was) {
      return `${ACCESS_LABELS[widened.was]} · ${ACCESS_LABELS[widened.access]} after you accept version ${version}`;
    }
    return ACCESS_LABELS[repo.access];
  }

  protected patch(change: Partial<SettingsDraft>): void {
    this.draft.update((d) => ({ ...d, ...change }));
  }

  protected setRuntime(value: string): void {
    if (value === this.runtime()) return;
    this.patch({ runtime: value as SettingsDraft['runtime'] });
  }

  protected setPayer(value: string): void {
    this.draft.update((d) => withPayer(d, value, this.offeredSubscriptions()));
  }

  protected toggleRepository(repositoryId: string, on: boolean): void {
    this.draft.update((d) => ({
      ...d,
      defaultRepositoryIds: on
        ? [...d.defaultRepositoryIds.filter((id) => id !== repositoryId), repositoryId]
        : d.defaultRepositoryIds.filter((id) => id !== repositoryId),
    }));
  }

  protected projectsText(systemId: string): string {
    return this.draft()
      .defaultProjects.filter((p) => p.systemId === systemId)
      .map((p) => p.key)
      .join(', ');
  }

  protected setProjects(systemId: string, text: string): void {
    const keys = [
      ...new Set(
        text
          .split(/[\s,]+/)
          .map((k) => k.trim())
          .filter(Boolean),
      ),
    ];
    this.draft.update((d) => ({
      ...d,
      defaultProjects: [
        ...d.defaultProjects.filter((p) => p.systemId !== systemId),
        ...keys.map((key) => ({ systemId, key })),
      ],
    }));
  }

  protected toggleSystem(systemId: string, on: boolean): void {
    this.draft.update((d) => ({
      ...d,
      systemsOff: on
        ? d.systemsOff.filter((id) => id !== systemId)
        : [...d.systemsOff.filter((id) => id !== systemId), systemId],
    }));
  }

  protected toggleSkill(skillId: string, on: boolean): void {
    this.draft.update((d) => ({
      ...d,
      skillsOff: on
        ? d.skillsOff.filter((id) => id !== skillId)
        : [...d.skillsOff.filter((id) => id !== skillId), skillId],
    }));
  }

  protected choiceOf(row: SettingsToolRow): SettingsToolChoice {
    return toolChoice(this.draft(), row.systemId, row.name);
  }

  protected setTool(systemId: string, tool: string, choice: SettingsToolChoice): void {
    // Only the three choices of TOOL_CHOICES reach here; anything else is never stored.
    if (choice !== '' && choice !== 'ASK' && choice !== 'OFF') return;
    this.draft.update((d) => withToolChoice(d, systemId, tool, choice));
  }

  protected askEveryWrite(): void {
    this.draft.update((d) => askOnEveryWrite(this.agent(), d, this.widening()));
  }

  protected ownServerOf(systemId: string): SettingsOwnServer | undefined {
    return this.draft().ownServers.find((s) => s.systemId === systemId);
  }

  protected toggleOwnServer(option: OwnServerOption, on: boolean): void {
    this.draft.update((d) => ({
      ...d,
      ownServers: on
        ? [
            ...d.ownServers.filter((s) => s.systemId !== option.system.id),
            {
              systemId: option.system.id,
              name: option.system.name,
              tools: ownServerTools(option.tools),
            },
          ]
        : d.ownServers.filter((s) => s.systemId !== option.system.id),
    }));
  }

  protected ownToolTitle(option: OwnServerOption, name: string): string {
    return option.tools?.find((t) => t.name === name)?.title || name;
  }

  protected ownToolEffect(option: OwnServerOption, name: string): string {
    const effect = option.tools?.find((t) => t.name === name)?.effect;
    return effect ? TOOL_EFFECT_LABELS[effect] : 'No longer served';
  }

  private effectOf(option: OwnServerOption, name: string) {
    return option.tools?.find((t) => t.name === name)?.effect ?? null;
  }

  /**
   * What the switch shows: what the tool does now, an "Allowed" that asks since it writes as
   * "Needs approval".
   */
  protected ownModeOf(option: OwnServerOption, tool: SettingsOwnTool): AgentToolMode {
    return ownToolMode(tool, this.effectOf(option, tool.name));
  }

  protected ownAsksSinceItWrites(option: OwnServerOption, tool: SettingsOwnTool): boolean {
    return !tool.picked && this.ownModeOf(option, tool) !== tool.mode;
  }

  /** The reader's pick, for the effect the row shows: the server chooses the mode anew for it. */
  protected setOwnTool(option: OwnServerOption, tool: string, mode: AgentToolMode): void {
    if (mode !== 'ALLOW' && mode !== 'ASK') return;
    const picked = effectOfPick(this.effectOf(option, tool));
    this.draft.update((d) => ({
      ...d,
      ownServers: d.ownServers.map((s) =>
        s.systemId === option.system.id
          ? {
              ...s,
              tools: (s.tools ?? []).map((t) => (t.name === tool ? { ...t, mode, picked } : t)),
            }
          : s,
      ),
    }));
  }
}
