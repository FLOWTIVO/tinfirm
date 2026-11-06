import {
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  effect,
  inject,
  InjectionToken,
  input,
  resource,
  signal,
  untracked,
} from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { NgIcon, provideIcons } from '@ng-icons/core';
import {
  lucideArrowLeft,
  lucideArrowRight,
  lucideChevronLeft,
  lucideChevronRight,
  lucideCircleAlert,
  lucideCircleCheck,
  lucideCircleDashed,
  lucideInfo,
} from '@ng-icons/lucide';
import { HlmAlertImports } from '@spartan-ng/helm/alert';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmInput } from '@spartan-ng/helm/input';
import { HlmLabel } from '@spartan-ng/helm/label';
import { HlmNativeSelectImports } from '@spartan-ng/helm/native-select';
import { HlmSheetImports } from '@spartan-ng/helm/sheet';
import { HlmSpinner } from '@spartan-ng/helm/spinner';
import { Api } from '../../../api/generated/api';
import {
  createTrigger,
  getAgent,
  getCompanySettings,
  getTrigger,
  getTriggerChoices,
  getTriggerSettings,
  listAgents,
  listGatewayModels,
  listGroups,
  listSystems,
  listSystemTools,
  previewTrigger,
  replaceTriggerAccounts,
  saveTriggerSettings,
  updateTrigger,
} from '../../../api/generated/functions';
import type {
  Agent,
  AgentSettings,
  Trigger,
  TriggerChoice,
  TriggerPreview,
} from '../../../api/generated/models';
import { problemMessage, toProblem } from '../../../api/problem';
import { CallerPrincipal } from '../../../core/caller-principal';
import { loadAll } from '../../../core/company-data';
import { CurrentCompany } from '../../../core/current-company';
import { mediaQuery, WIDE_SCREEN } from '../../../core/media-query';
import { Notify } from '../../../core/notify';
import { AgentSettingsForm } from '../../../shared/agent-settings-form';
import {
  askOnEveryWrite,
  type OwnServerOption,
  type SettingsDraft,
  settingsRequest,
  settingsSystemRows,
  toolChoice,
} from '../../../shared/agent-settings-model';
import { browserTimeZone } from '../../../shared/cron';
import { ErrorState } from '../../../shared/error-state';
import { ListSkeleton } from '../../../shared/list-skeleton';
import { MarkdownEditor } from '../../../shared/markdown-editor';
import { WhoActsWhoPays } from '../../../shared/who-acts-who-pays';
import { NotFound } from '../../not-found';
import { runtimesText } from '../agents/agent-labels';
import { TriggerAccountsFields } from './trigger-accounts-fields';
import {
  allRowsOwn,
  changed,
  changeOf,
  createRequest,
  defaultSettingsDraft,
  draftErrors,
  draftOf,
  emptyDraft,
  fillFromChoice,
  previewable,
  previewRequest,
  type TriggerDraft,
  type TriggerSection,
  updateRequest,
} from './trigger-draft';
import { type TriggerFailure, triggerFailure } from './trigger-errors';
import { EVENT_SHORT } from './trigger-labels';
import { TriggerOpenEvents } from './trigger-open-events';
import { previewView, TriggerPreviewPanel } from './trigger-preview-panel';
import { TriggerResultsFields } from './trigger-results-fields';
import { previewSummary } from './trigger-view';
import { TriggerWhenFields } from './trigger-when-fields';

/** How long the editor waits after a change before it asks for the preview again (ms). */
export const TRIGGER_PREVIEW_DEBOUNCE = new InjectionToken<number>('TRIGGER_PREVIEW_DEBOUNCE', {
  factory: () => 400,
});

/** A section of the editor: the page shows one at a time. */
export type EditorSection = 'when' | 'agent' | 'accounts' | 'settings' | 'team' | 'results';

/** The sections of the page, in order: the tabs, and the steps of Back and Next. */
const SECTIONS: readonly { id: EditorSection; label: string; lede: string }[] = [
  { id: 'when', label: 'When', lede: 'What fires the trigger, where, and how often.' },
  {
    id: 'agent',
    label: 'The agent does',
    lede: 'Which agent works, and the job it does every time the trigger fires.',
  },
  {
    id: 'accounts',
    label: 'Acts as and pays',
    lede: 'An agent holds no accounts: the trigger lends it one per system. Its runs act and pay as chosen here; nothing switches later by itself.',
  },
  {
    id: 'settings',
    label: 'Narrowed for its runs',
    lede: 'The trigger’s settings of the agent: they narrow it, never widen it.',
  },
  { id: 'team', label: 'Team', lede: 'Who looks after the trigger with you.' },
  {
    id: 'results',
    label: 'Results',
    lede: 'What happens with a result, and how often the trigger may fire.',
  },
];

/** How a section stands, as its tab shows it. */
type SectionState = 'done' | 'todo' | 'error';

/** The section a refused save names, to open it; null: the column beside them, or the form. */
const FAILURE_SECTIONS: Record<NonNullable<TriggerFailure['at']>, EditorSection | null> = {
  name: 'when',
  when: 'when',
  agent: 'agent',
  accounts: 'accounts',
  payer: 'accounts',
  settings: 'settings',
  team: 'team',
  results: 'results',
  acknowledge: null,
  panel: null,
};

/** What a refused save brings into view and focuses, once its section is open. */
function failureTarget(failure: TriggerFailure): string {
  switch (failure.at) {
    case 'name':
      return '#trigger-name';
    case 'accounts':
      return failure.systemId ? `#trigger-row-${failure.systemId}` : '[data-error=accounts]';
    case 'payer':
      return '#trigger-payer';
    case 'results':
      return '#trigger-firings';
    case 'settings':
      return '[data-testid=settings-error]';
    case 'acknowledge':
      return '#trigger-acknowledge';
    case 'when':
    case 'agent':
    case 'team':
      return `[data-error=${failure.at}]`;
    default:
      return '[data-testid=editor-error]';
  }
}

/** The id one arrow key, Home or End moves to in a list of tabs, or null for another key. */
function tabKeyTarget(key: string, ids: readonly string[], current: string): string | null {
  const i = Math.max(0, ids.indexOf(current));
  switch (key) {
    case 'ArrowDown':
    case 'ArrowRight':
      return ids[(i + 1) % ids.length];
    case 'ArrowUp':
    case 'ArrowLeft':
      return ids[(i - 1 + ids.length) % ids.length];
    case 'Home':
      return ids[0];
    case 'End':
      return ids[ids.length - 1];
    default:
      return null;
  }
}

/** "1 action", "3 actions". */
function count(n: number, one: string, many: string): string {
  return `${n} ${n === 1 ? one : many}`;
}

function capitalised(text: string): string {
  return text ? text[0].toUpperCase() + text.slice(1) : text;
}

/**
 * The trigger editor (plan §4.5; the package 4 contract, §5.2, §5.8 and §6), `triggers/new` and
 * `triggers/:triggerId/edit`: a standing rule that reads as one sentence and says, before it is
 * saved, who acts, who pays, who can drive it and what runs without approval. One section at a
 * time (not a wizard: any tab opens any section): When, The agent does, Acts as and pays, Narrowed
 * for its runs, Team, Results, each tab with how it stands and a line of what it holds; Back and
 * Next step through them. Every section stays in the page, hidden when another is open, so nothing
 * typed is lost. The sentence reads under the title, and "Before you save", read again after every
 * change, sticks beside the sections: who acts and who pays, a schedule's next firings, and the
 * warning when anyone may drive it. On a phone the sections are the first screen and each opens on
 * its own; a sticky bar carries the summary and Create, and opens "Before you save" as a sheet. A
 * new trigger is one `createTrigger`; a change sends its definition (`updateTrigger`), its account
 * table and payer (`replaceTriggerAccounts`) and its settings (`saveTriggerSettings`), each only
 * when changed. Save waits for the preview to pass and, while anyone may drive it with the
 * author's own account and a write still runs, for "I understand" (V14). A refused save opens the
 * section, and the field, it names.
 */
@Component({
  selector: 'app-trigger-editor',
  imports: [
    RouterLink,
    NgIcon,
    HlmAlertImports,
    HlmButton,
    HlmInput,
    HlmLabel,
    HlmNativeSelectImports,
    HlmSheetImports,
    HlmSpinner,
    AgentSettingsForm,
    ErrorState,
    ListSkeleton,
    MarkdownEditor,
    NotFound,
    TriggerAccountsFields,
    TriggerOpenEvents,
    TriggerPreviewPanel,
    TriggerResultsFields,
    TriggerWhenFields,
    WhoActsWhoPays,
  ],
  providers: [
    provideIcons({
      lucideArrowLeft,
      lucideArrowRight,
      lucideChevronLeft,
      lucideChevronRight,
      lucideCircleAlert,
      lucideCircleCheck,
      lucideCircleDashed,
      lucideInfo,
    }),
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @let companyId = current.id();
    @if (notFound()) {
      <app-not-found />
    } @else {
      <div class="flex flex-col gap-5">
        <div
          class="flex flex-col gap-4 sm:flex-row sm:flex-wrap sm:items-end sm:justify-between sm:gap-x-8"
          [class.max-lg:hidden]="phoneOnSection()"
        >
          <div class="flex min-w-0 flex-col gap-1 sm:flex-[1_1_30rem]">
            <a
              hlmBtn
              variant="ghost"
              size="sm"
              class="-ms-3 self-start max-sm:h-10"
              [routerLink]="
                triggerId()
                  ? ['/c', companyId, 'triggers', triggerId()]
                  : ['/c', companyId, 'triggers']
              "
            >
              <ng-icon name="lucideArrowLeft" aria-hidden="true" />
              {{ triggerId() ? (saved()?.name ?? 'Trigger') : 'Triggers' }}
            </a>
            <h1 class="text-xl font-bold tracking-tight text-balance sm:text-2xl">
              {{ triggerId() ? 'Edit ' + (saved()?.name ?? 'trigger') : 'New trigger' }}
            </h1>
            @if (sentence(); as text) {
              <p
                class="mt-0.5 max-w-[64ch] text-[0.9375rem] text-pretty"
                aria-live="polite"
                data-testid="trigger-sentence"
              >
                {{ text }}
              </p>
            } @else {
              <p
                class="text-muted-foreground mt-0.5 max-w-[64ch] text-sm text-pretty"
                data-testid="trigger-sentence"
              >
                A standing rule: when it fires, the agent does the instruction with the accounts and
                the budget chosen here. Before you save, it says who acts and who pays.
              </p>
            }
          </div>
          @if (editable() && wide()) {
            <div class="flex min-w-0 flex-col items-end gap-1.5" data-testid="trigger-save-bar">
              <div class="flex gap-2">
                <a
                  hlmBtn
                  variant="ghost"
                  [routerLink]="
                    triggerId()
                      ? ['/c', companyId, 'triggers', triggerId()]
                      : ['/c', companyId, 'triggers']
                  "
                  >Cancel</a
                >
                <button
                  hlmBtn
                  type="submit"
                  form="trigger-editor-form"
                  data-testid="save-trigger"
                  [disabled]="!canSave()"
                  aria-describedby="save-status"
                >
                  @if (busy()) {
                    <hlm-spinner aria-hidden="true" />
                  }
                  {{ triggerId() ? 'Save' : 'Create trigger' }}
                </button>
              </div>
              <p
                class="m-0 max-w-sm text-end text-xs text-pretty"
                id="save-status"
                aria-live="polite"
                data-testid="save-status"
                [class]="ackMissing() ? 'text-human font-semibold' : 'text-muted-foreground'"
              >
                {{ holdReason() ?? doneLine() }}
              </p>
            </div>
          }
        </div>

        @if (loading()) {
          <app-list-skeleton [rows]="5" />
        } @else if (loadError(); as error) {
          <app-error-state [error]="error" heading="Couldn't load the trigger" (retry)="retry()" />
        } @else if (saved() && !saved()!.rights.edits) {
          <div hlmAlert data-testid="not-editable">
            <ng-icon name="lucideInfo" aria-hidden="true" />
            <p hlmAlertDescription>
              Only the trigger’s author and the company’s admins change it. Its team runs and pauses
              it.
            </p>
          </div>
        } @else {
          @let d = draft();
          @if (saved() && saved()!.authorPrincipalId !== caller.id()) {
            <p
              class="bg-card text-muted-foreground flex items-start gap-2 rounded-xl border px-4 py-3 text-sm"
              [class.max-lg:hidden]="phoneOnSection()"
              data-testid="on-behalf"
            >
              <ng-icon name="lucideInfo" class="mt-0.5 shrink-0" aria-hidden="true" />
              You change {{ saved()!.authorName ?? 'its author' }}’s trigger on their behalf: every
              choice must be one they may use, and they are told what you changed.
            </p>
          }
          <form
            class="flex min-w-0 flex-col gap-5"
            id="trigger-editor-form"
            novalidate
            (submit)="save($event)"
            data-testid="trigger-editor"
          >
            @if (formError(); as message) {
              <div hlmAlert variant="destructive" tabindex="-1" data-testid="editor-error">
                <ng-icon name="lucideCircleAlert" aria-hidden="true" />
                <p hlmAlertDescription>{{ message }}</p>
              </div>
            }

            <div
              class="grid min-w-0 gap-5 lg:grid-cols-[15rem_minmax(0,1fr)] lg:items-start xl:grid-cols-[15rem_minmax(0,1fr)_18.5rem]"
            >
              <!-- The sections, each with what it holds; on a phone, the page's first screen. -->
              <nav
                class="flex min-w-0 flex-col gap-4 lg:sticky lg:top-18"
                [class.max-lg:hidden]="phoneOnSection()"
                aria-label="Sections"
                data-testid="section-index"
              >
                <div
                  class="flex flex-col gap-1"
                  role="tablist"
                  aria-orientation="vertical"
                  aria-label="Sections of the trigger"
                >
                  @for (row of sectionRows(); track row.id) {
                    @let open = row.id === section();
                    <button
                      type="button"
                      role="tab"
                      class="focus-visible:ring-ring/50 max-lg:bg-card max-lg:border-border flex min-h-16 w-full min-w-0 items-start gap-2.5 rounded-xl border px-3 py-2.5 text-start outline-none focus-visible:ring-3 lg:min-h-0"
                      [class]="
                        open
                          ? 'bg-card border-border shadow-[0_1px_3px_rgb(0_0_0/0.08)]'
                          : 'hover:bg-accent border-transparent'
                      "
                      [id]="'section-tab-' + row.id"
                      [attr.aria-selected]="open"
                      [attr.aria-controls]="'section-panel-' + row.id"
                      [tabIndex]="open ? 0 : -1"
                      [attr.data-section]="row.id"
                      [attr.data-state]="row.state"
                      (click)="openSection(row.id)"
                      (keydown)="onSectionKeys($event)"
                    >
                      @switch (row.state) {
                        @case ('done') {
                          <ng-icon
                            name="lucideCircleCheck"
                            size="16"
                            class="text-success mt-0.5 shrink-0"
                            aria-hidden="true"
                          />
                        }
                        @case ('error') {
                          <ng-icon
                            name="lucideCircleAlert"
                            size="16"
                            class="text-destructive mt-0.5 shrink-0"
                            aria-hidden="true"
                          />
                        }
                        @default {
                          <ng-icon
                            name="lucideCircleDashed"
                            size="16"
                            class="text-faint mt-0.5 shrink-0"
                            aria-hidden="true"
                          />
                        }
                      }
                      <span class="flex min-w-0 flex-1 flex-col">
                        <span
                          class="text-sm font-semibold max-lg:text-[0.9375rem]"
                          [id]="'section-tab-' + row.id + '-label'"
                          >{{ row.label }}</span
                        >
                        <span class="flex flex-col" [id]="'section-tab-' + row.id + '-details'">
                          @if (row.state === 'error') {
                            <span class="sr-only">Has a problem:</span>&ngsp;
                          } @else if (row.state === 'todo') {
                            <span class="sr-only">Not complete yet:</span>&ngsp;
                          }
                          <span
                            class="text-xs text-pretty max-lg:text-[0.8125rem]"
                            [class.text-destructive]="row.state === 'error'"
                            [class.text-muted-foreground]="row.state !== 'error'"
                            data-summary
                            >{{ row.summary }}</span
                          >
                          @if (row.dot) {
                            <span class="sr-only">, needs you</span>
                          }
                        </span>
                      </span>
                      @if (row.dot) {
                        <span
                          class="bg-human mt-1.5 size-2 shrink-0 rounded-full"
                          aria-hidden="true"
                          data-testid="needs-you-dot"
                        ></span>
                      }
                      <!-- An icon's own display wins over lg:hidden: its wrapper hides it. -->
                      <span class="text-muted-foreground mt-0.5 flex shrink-0 lg:hidden">
                        <ng-icon name="lucideChevronRight" size="16" aria-hidden="true" />
                      </span>
                    </button>
                  }
                </div>
              </nav>

              @if (!wide() && !phoneOnSection() && whoActs()) {
                <!-- The phone's first screen: who acts and who pays, under the sections. -->
                <app-who-acts-who-pays
                  heading="Who acts and who pays"
                  intro="As the trigger’s runs would be bound now. Nothing switches later by itself."
                  [view]="whoActs()"
                />
              }

              <!-- One section at a time; on a phone, its own screen. -->
              <div
                class="bg-card flex min-w-0 flex-col rounded-xl border lg:min-h-[32rem]"
                [class.max-lg:hidden]="!phoneOnSection()"
              >
                <div class="flex items-center justify-between border-b px-2 py-1.5 lg:hidden">
                  <button
                    hlmBtn
                    variant="ghost"
                    type="button"
                    class="h-11"
                    (click)="showSectionList()"
                  >
                    <ng-icon name="lucideChevronLeft" aria-hidden="true" />
                    All sections
                  </button>
                </div>
                @for (s of sectionList; track s.id; let i = $index) {
                  <section
                    class="flex min-w-0 flex-col"
                    role="tabpanel"
                    [id]="'section-panel-' + s.id"
                    [attr.aria-labelledby]="'section-tab-' + s.id + '-label'"
                    [attr.data-testid]="'editor-' + s.id"
                    [hidden]="section() !== s.id"
                  >
                    <div class="flex items-start justify-between gap-4 px-4 pt-4 sm:px-5 sm:pt-5">
                      <div class="flex min-w-0 flex-col gap-0.5">
                        <h2
                          class="text-base font-bold outline-none"
                          [id]="s.id + '-title'"
                          tabindex="-1"
                        >
                          {{ s.label }}
                        </h2>
                        <p class="text-muted-foreground text-sm text-pretty">
                          @if (s.id === 'settings') {
                            The trigger’s settings of {{ agentName() ?? 'the agent' }}: they narrow
                            it, never widen it.
                          } @else {
                            {{ s.lede }}
                          }
                        </p>
                      </div>
                      <span
                        class="text-faint mt-1 shrink-0 font-mono text-xs tabular-nums"
                        aria-hidden="true"
                        >{{ i + 1 }} of {{ sectionList.length }}</span
                      >
                    </div>
                    <div class="flex min-w-0 flex-col gap-5 px-4 pt-4 pb-5 sm:px-5">
                      @switch (s.id) {
                        @case ('when') {
                          <div class="flex min-w-0 flex-col gap-1.5">
                            <label hlmLabel for="trigger-name">Name</label>
                            <input
                              hlmInput
                              id="trigger-name"
                              autocomplete="off"
                              maxlength="100"
                              placeholder="Prepare assigned issues"
                              [value]="d.name"
                              [forceInvalid]="!!nameError()"
                              [attr.aria-invalid]="!!nameError() || null"
                              [attr.aria-describedby]="nameError() ? 'trigger-name-error' : null"
                              (input)="patch({ name: $any($event.target).value })"
                            />
                            @if (nameError(); as message) {
                              <p
                                class="text-destructive text-sm"
                                id="trigger-name-error"
                                role="alert"
                                data-error="name"
                              >
                                {{ message }}
                              </p>
                            }
                          </div>
                          <app-trigger-when-fields
                            [draft]="d"
                            (draftChange)="setDraft($event)"
                            [systems]="systems()"
                            [fixed]="!!triggerId()"
                            [author]="
                              saved() && saved()!.authorPrincipalId !== caller.id()
                                ? author()
                                : null
                            "
                          />
                          @if (sectionError('when'); as message) {
                            <p
                              class="text-destructive text-sm outline-none"
                              role="alert"
                              tabindex="-1"
                              data-error="when"
                            >
                              {{ message }}
                            </p>
                          }
                        }
                        @case ('agent') {
                          <div class="flex min-w-0 flex-col gap-1.5">
                            <label hlmLabel for="trigger-agent">Agent</label>
                            @if (triggerId()) {
                              <p class="text-sm" data-testid="fixed-agent">
                                {{ agentName() ?? 'Its agent' }}
                                <span class="text-muted-foreground text-xs"
                                  >(fixed: a trigger for another agent is another trigger)</span
                                >
                              </p>
                            } @else {
                              <hlm-native-select
                                class="w-full sm:max-w-sm"
                                selectId="trigger-agent"
                                [value]="d.agentId ?? ''"
                                (valueChange)="setAgent($event ?? '')"
                              >
                                <option hlmNativeSelectOption value="">Pick an agent</option>
                                @for (a of agents(); track a.id) {
                                  <option
                                    hlmNativeSelectOption
                                    [value]="a.id"
                                    [selected]="a.id === d.agentId"
                                  >
                                    {{ a.name }}
                                  </option>
                                }
                              </hlm-native-select>
                              @if (agentsLoaded() && agents().length === 0) {
                                <p class="text-muted-foreground text-xs" data-testid="no-agents">
                                  No agent is shared with you yet: a trigger runs an agent you may
                                  use.
                                </p>
                              } @else {
                                <p class="text-muted-foreground text-xs" data-testid="agent-detail">
                                  {{
                                    agentDetail() ??
                                      'No agent picked: a trigger runs an agent you may use.'
                                  }}
                                </p>
                              }
                            }
                          </div>
                          <div class="flex min-w-0 flex-col gap-1.5">
                            <span class="text-sm font-medium" id="trigger-instruction-label"
                              >Instruction</span
                            >
                            <app-markdown-editor
                              inputId="trigger-instruction"
                              labelId="trigger-instruction-label"
                              placeholder="Prepare the issue: read it and the code, then write a brief as a comment."
                              [minRows]="5"
                              [value]="d.instruction"
                              (valueChange)="patch({ instruction: $event })"
                            />
                            <p class="text-muted-foreground text-xs">
                              What the job is, every time it fires. It comes from you alone; what
                              people write on the issue is read as their words, never as this
                              instruction.
                            </p>
                          </div>
                          @if (sectionError('agent'); as message) {
                            <p
                              class="text-destructive text-sm outline-none"
                              role="alert"
                              tabindex="-1"
                              data-error="agent"
                            >
                              {{ message }}
                            </p>
                          }
                        }
                        @case ('accounts') {
                          @if (!wide() && preview()?.openToAnyone; as open) {
                            <app-trigger-open-events
                              [open]="open"
                              [agentName]="agentName()"
                              [acknowledgedAt]="saved()?.openEventsAcknowledgedAt ?? null"
                              [acknowledged]="d.acknowledgeOpenEvents"
                              (acknowledgedChange)="patch({ acknowledgeOpenEvents: $event })"
                              [ackError]="sectionError('acknowledge')"
                              (askEveryWrite)="askEveryWrite()"
                            />
                          }
                          @if (choicesRes.error()) {
                            <p class="text-destructive text-sm" role="alert">
                              {{ choicesError() }}
                            </p>
                          }
                          <app-trigger-accounts-fields
                            [draft]="d"
                            (draftChange)="setDraft($event)"
                            [sourceKind]="sourceKind()"
                            [choices]="choices()"
                            [author]="author()"
                            [kept]="saved()?.accounts ?? []"
                            [previewAccounts]="preview()?.accounts ?? []"
                            [rowErrors]="rowErrors()"
                            [error]="sectionError('accounts')"
                            [payerError]="sectionError('payer')"
                          />
                        }
                        @case ('settings') {
                          @if (askPrefilled()) {
                            <p
                              class="text-muted-foreground flex items-start gap-2 rounded-xl border px-4 py-3 text-sm"
                              data-testid="ask-prefilled"
                            >
                              <ng-icon
                                name="lucideInfo"
                                class="mt-0.5 shrink-0"
                                aria-hidden="true"
                              />
                              Anyone who may comment can drive this trigger, so every write asks the
                              team first. You may change that below.
                            </p>
                          }
                          @if (agent() && d.settings) {
                            @if (sectionError('settings'); as message) {
                              <div
                                hlmAlert
                                variant="destructive"
                                tabindex="-1"
                                data-testid="settings-error"
                              >
                                <ng-icon name="lucideCircleAlert" aria-hidden="true" />
                                <p hlmAlertDescription>{{ message }}</p>
                              </div>
                            }
                            <!-- The form draws itself as one card: here the section is the card. -->
                            <app-agent-settings-form
                              class="[&>fieldset]:rounded-none [&>fieldset]:border-0 [&>fieldset]:bg-transparent [&>fieldset]:p-0"
                              subject="trigger"
                              [agent]="agent()!"
                              [widening]="settingsRes.value()?.widening ?? null"
                              [draft]="d.settings!"
                              (draftChange)="setSettings($event)"
                              [subscriptions]="caller.modelAccess()?.subscriptions ?? []"
                              [allowSubscriptions]="allowSubscriptions()"
                              [subscriptionsNote]="subscriptionsNote()"
                              [allowedModels]="payerModels()"
                              [gatewayModels]="gatewayModels()"
                              [modelsUnavailable]="modelsUnavailable()"
                              [ownServers]="ownServers()"
                              [ownServersAllowed]="ownAllowed()"
                              ownServersNote="Only while every account of the trigger is your own: runs through a service account never reach your own servers."
                              [errors]="failure()?.settings ?? {}"
                              [errorAt]="failure()?.settingsAt ?? null"
                              [disabled]="busy()"
                            />
                          } @else {
                            <p class="text-muted-foreground text-sm">Pick the agent first.</p>
                          }
                        }
                        @case ('team') {
                          <div class="flex min-w-0 flex-col gap-1.5">
                            <label hlmLabel for="trigger-team"
                              >Who sees its conversations and decides</label
                            >
                            <hlm-native-select
                              class="w-full sm:max-w-sm"
                              selectId="trigger-team"
                              [value]="d.teamGroupId ?? ''"
                              (valueChange)="patch({ teamGroupId: $event || null })"
                            >
                              <option hlmNativeSelectOption value="">Only you</option>
                              @for (g of groups(); track g.id) {
                                <option
                                  hlmNativeSelectOption
                                  [value]="g.id"
                                  [selected]="g.id === d.teamGroupId"
                                >
                                  {{ g.name }}
                                </option>
                              }
                            </hlm-native-select>
                            <p class="text-muted-foreground text-xs">
                              The team sees the trigger and its conversations, decides their
                              approvals, runs it now and pauses it. Without one, you alone do.
                            </p>
                          </div>
                          @if (team(); as g) {
                            <p class="text-muted-foreground text-sm" data-testid="team-members">
                              {{ g.name }}:
                              {{ count(g.memberCount, 'member', 'members') }}
                            </p>
                          }
                        }
                        @case ('results') {
                          <app-trigger-results-fields
                            [draft]="d"
                            (draftChange)="setDraft($event)"
                            [kind]="sourceKind()"
                            [limits]="limits()"
                            [error]="sectionError('results')"
                          />
                        }
                      }
                    </div>
                  </section>
                }
                <!-- On a phone, Next on top, each the full width: a section's name can be long. -->
                <div
                  class="bg-muted/50 mt-auto flex flex-col-reverse gap-2 rounded-b-xl border-t px-4 py-3 sm:flex-row sm:items-center sm:justify-between sm:gap-3 sm:px-5"
                  data-testid="section-steps"
                >
                  @if (previousSection(); as previous) {
                    <button
                      hlmBtn
                      variant="ghost"
                      type="button"
                      class="max-sm:h-11 max-sm:w-full"
                      (click)="step(previous.id)"
                    >
                      <ng-icon name="lucideArrowLeft" aria-hidden="true" />
                      {{ previous.label }}
                    </button>
                  } @else {
                    <span class="max-sm:hidden"></span>
                  }
                  @if (nextSection(); as next) {
                    <button
                      hlmBtn
                      variant="outline"
                      type="button"
                      class="max-sm:h-11 max-sm:w-full"
                      (click)="step(next.id)"
                    >
                      Next: {{ next.label }}
                      <ng-icon name="lucideArrowRight" aria-hidden="true" />
                    </button>
                  }
                </div>
              </div>

              @if (wide()) {
                <!-- It scrolls on its own on a wide screen: the keyboard reaches it too (WCAG 2.1.1). -->
                <aside
                  class="focus-visible:ring-ring/50 flex min-w-0 flex-col gap-3 rounded-xl outline-none focus-visible:ring-3 lg:col-start-2 xl:sticky xl:top-18 xl:col-start-3 xl:row-start-1 xl:max-h-[calc(100dvh-5.5rem)] xl:overflow-y-auto xl:overscroll-contain"
                  aria-label="Before you save"
                  tabindex="0"
                >
                  <app-trigger-preview-panel
                    [preview]="preview()"
                    [loading]="previewing()"
                    [problem]="previewProblem()"
                    [hint]="previewHint()"
                    [reader]="author()"
                  />
                  @if (preview()?.openToAnyone; as open) {
                    <app-trigger-open-events
                      [open]="open"
                      [agentName]="agentName()"
                      [acknowledgedAt]="saved()?.openEventsAcknowledgedAt ?? null"
                      [acknowledged]="d.acknowledgeOpenEvents"
                      (acknowledgedChange)="patch({ acknowledgeOpenEvents: $event })"
                      [ackError]="sectionError('acknowledge')"
                      (askEveryWrite)="askEveryWrite()"
                    />
                  }
                </aside>
              }
            </div>

            @if (!wide()) {
              <!-- The phone's save bar: the summary of who acts and who pays, and Create. -->
              <div
                class="bg-card sticky bottom-3 z-10 flex items-center gap-1.5 rounded-[18px] border p-1.5 ps-3.5 shadow-[0_10px_30px_-12px_rgb(0_0_0/0.25)]"
                role="region"
                aria-label="Before you save"
                data-testid="trigger-save-bar"
              >
                <button
                  type="button"
                  class="focus-visible:ring-ring/50 flex min-h-11 min-w-0 flex-1 flex-col justify-center rounded-lg text-start leading-snug outline-none focus-visible:ring-3"
                  aria-haspopup="dialog"
                  [attr.aria-expanded]="sheetOpen()"
                  (click)="sheetOpen.set(true)"
                >
                  @if (holdReason(); as reason) {
                    <span
                      class="text-[0.8125rem] font-semibold"
                      id="save-status"
                      aria-live="polite"
                      data-testid="save-status"
                      [class]="ackMissing() ? 'text-human' : 'text-muted-foreground'"
                      >{{ reason }}</span
                    >
                  } @else {
                    <span
                      class="text-[0.8125rem] font-semibold"
                      id="save-status"
                      aria-live="polite"
                      data-testid="save-status"
                      ><span data-testid="preview-summary">{{
                        summary() ?? doneLine()
                      }}</span></span
                    >
                  }
                  <span class="text-muted-foreground text-xs">Tap for who acts and who pays</span>
                </button>
                <button
                  hlmBtn
                  type="submit"
                  class="h-11 px-[18px]"
                  data-testid="save-trigger"
                  [disabled]="!canSave()"
                  aria-describedby="save-status"
                >
                  @if (busy()) {
                    <hlm-spinner aria-hidden="true" />
                  }
                  {{ triggerId() ? 'Save' : 'Create' }}
                </button>
              </div>
            }
          </form>

          @if (!wide()) {
            <hlm-sheet
              side="bottom"
              [state]="sheetOpen() ? 'open' : 'closed'"
              (closed)="sheetOpen.set(false)"
            >
              <hlm-sheet-content
                *hlmSheetPortal="let ctx"
                class="max-h-[85dvh] gap-3 overflow-y-auto rounded-t-[18px] px-4 pt-4 pb-6"
              >
                <hlm-sheet-header class="p-0 pe-10">
                  <h2 hlmSheetTitle class="font-bold">Before you save</h2>
                </hlm-sheet-header>
                <app-trigger-preview-panel
                  [framed]="false"
                  [titled]="false"
                  [preview]="preview()"
                  [loading]="previewing()"
                  [problem]="previewProblem()"
                  [hint]="previewHint()"
                  [reader]="author()"
                />
                @if (preview()?.openToAnyone) {
                  <div class="flex flex-col gap-2 border-t pt-3 text-sm">
                    <p
                      [class]="ackMissing() ? 'text-human font-semibold' : ''"
                      data-testid="sheet-open-events"
                    >
                      @if (ackMissing()) {
                        Anyone can drive this agent as you: tick “I understand” in Acts as and pays,
                        or narrow it there.
                      } @else {
                        Anyone can drive this agent: Acts as and pays says with which accounts.
                      }
                    </p>
                    <button
                      hlmBtn
                      variant="outline"
                      type="button"
                      class="h-11"
                      (click)="openFromSheet('accounts')"
                    >
                      Open Acts as and pays
                    </button>
                  </div>
                }
                <button
                  hlmBtn
                  variant="outline"
                  type="button"
                  class="h-11"
                  (click)="sheetOpen.set(false)"
                >
                  Back to the trigger
                </button>
              </hlm-sheet-content>
            </hlm-sheet>
          }
        }
      </div>
    }
  `,
})
export class TriggerEditor {
  private readonly api = inject(Api);
  private readonly router = inject(Router);
  private readonly notify = inject(Notify);
  private readonly debounce = inject(TRIGGER_PREVIEW_DEBOUNCE);
  protected readonly current = inject(CurrentCompany);
  protected readonly caller = inject(CallerPrincipal);

  /** Set on `triggers/:triggerId/edit`; absent on `triggers/new`. */
  readonly triggerId = input<string | undefined>(undefined);
  /** `?agentId=` preselects the agent of a new trigger. */
  readonly agentId = input<string | undefined>(undefined);

  /** The form's state; see `trigger-draft.ts`. */
  protected readonly draft = signal<TriggerDraft>(emptyDraft(browserTimeZone()));

  protected readonly count = count;

  // --- What the editor reads ---------------------------------------------------------------------

  private readonly triggerRes = resource({
    params: () => {
      const companyId = this.current.id();
      const triggerId = this.triggerId();
      return companyId && triggerId ? { companyId, triggerId } : undefined;
    },
    loader: ({ params }) => this.api.invoke(getTrigger, params),
  });
  protected readonly settingsRes = resource({
    params: () => {
      const companyId = this.current.id();
      const triggerId = this.triggerId();
      return companyId && triggerId ? { companyId, triggerId } : undefined;
    },
    loader: ({ params }) => this.api.invoke(getTriggerSettings, params),
  });
  protected readonly saved = computed(() =>
    this.triggerRes.hasValue() ? (this.triggerRes.value() ?? null) : null,
  );

  private readonly agentsRes = resource({
    params: () => (this.triggerId() ? undefined : this.current.id()),
    loader: ({ params: companyId }) =>
      loadAll((cursor) =>
        this.api.invoke(listAgents, { companyId, usable: true, limit: 100, cursor }),
      ),
  });
  protected readonly agents = computed(() =>
    this.agentsRes.hasValue() ? (this.agentsRes.value() ?? []) : [],
  );
  protected readonly agentsLoaded = computed(() => this.agentsRes.hasValue());

  /** The draft's parts the reads depend on, so that a keystroke reads nothing again. */
  private readonly draftAgentId = computed(() => this.draft().agentId);
  private readonly draftSystemId = computed(() =>
    this.draft().source === 'TRACKER' ? this.draft().systemId : null,
  );
  private readonly draftSource = computed(() => this.draft().source);

  private readonly agentRes = resource({
    params: () => {
      const companyId = this.current.id();
      const agentId = this.draftAgentId();
      return companyId && agentId ? { companyId, agentId } : undefined;
    },
    loader: ({ params }) => this.api.invoke(getAgent, params),
  });
  protected readonly agent = computed<Agent | null>(() =>
    this.agentRes.hasValue() ? (this.agentRes.value() ?? null) : null,
  );
  protected readonly agentName = computed(
    () =>
      this.agent()?.name ??
      this.saved()?.agentName ??
      this.agents().find((a) => a.id === this.draft().agentId)?.name ??
      null,
  );
  /** The picked agent in a line: its runtimes and what it does. */
  protected readonly agentDetail = computed(() => {
    const picked = this.agents().find((a) => a.id === this.draft().agentId);
    if (!picked) return null;
    return [runtimesText(picked.runtimes), picked.description?.trim()].filter(Boolean).join(' · ');
  });

  private readonly systemsRes = resource({
    params: () => this.current.id(),
    loader: ({ params: companyId }) =>
      loadAll((cursor) => this.api.invoke(listSystems, { companyId, limit: 100, cursor })),
  });
  protected readonly systems = computed(() =>
    this.systemsRes.hasValue() ? (this.systemsRes.value() ?? []) : [],
  );
  protected readonly sourceKind = computed(
    () => this.systems().find((s) => s.id === this.draft().systemId)?.kind ?? null,
  );

  private readonly groupsRes = resource({
    params: () => this.current.id(),
    loader: ({ params: companyId }) =>
      loadAll((cursor) => this.api.invoke(listGroups, { companyId, limit: 100, cursor })),
  });
  protected readonly groups = computed(() =>
    this.groupsRes.hasValue() ? (this.groupsRes.value() ?? []) : [],
  );
  protected readonly team = computed(
    () => this.groups().find((g) => g.id === this.draft().teamGroupId) ?? null,
  );

  private readonly companySettingsRes = resource({
    params: () => this.current.id(),
    loader: ({ params: companyId }) => this.api.invoke(getCompanySettings, { companyId }),
  });
  protected readonly limits = computed(() =>
    this.companySettingsRes.hasValue()
      ? (this.companySettingsRes.value()?.triggerFiringsPerHour ?? null)
      : null,
  );
  /** Whether the company lets a trigger pay with its author's subscription (ADR-0085, decision 7). */
  protected readonly subscriptionsOn = computed(
    () =>
      this.companySettingsRes.hasValue() && !!this.companySettingsRes.value()?.triggerSubscriptions,
  );
  /**
   * A subscription pays only where the company allows it and the author pays, and only the
   * author can choose one: the form knows the reader's own subscriptions, and an admin editing
   * on the author's behalf has no sight of the author's runner hosts.
   */
  protected readonly allowSubscriptions = computed(
    () => this.subscriptionsOn() && this.payerIsAuthor() && this.author() === this.caller.id(),
  );
  protected readonly subscriptionsNote = computed(() =>
    !this.subscriptionsOn()
      ? 'This company lets a trigger pay only with a budget: a subscription pays for your own conversations.'
      : this.author() !== this.caller.id()
        ? 'A subscription is the author’s to choose: only they see their own runner hosts.'
        : 'A subscription can pay only when you are the payer.',
  );

  /** The one choice, for the agent and the source system. */
  protected readonly choicesRes = resource({
    params: () => {
      const companyId = this.current.id();
      const agentId = this.draftAgentId();
      const systemId = this.draftSystemId();
      if (!companyId || !agentId) return undefined;
      if (this.draftSource() === 'TRACKER' && !systemId) return undefined;
      return {
        companyId,
        agentId,
        systemId: systemId ?? undefined,
        forPrincipalId: this.forAuthor(),
        triggerId: this.forAuthor() ? this.saved()?.id : undefined,
      };
    },
    loader: ({ params }) => this.api.invoke(getTriggerChoices, params),
  });
  protected readonly choices = computed<readonly TriggerChoice[]>(() =>
    this.choicesRes.hasValue() ? (this.choicesRes.value()?.items ?? []) : [],
  );
  protected readonly choicesError = computed(() => problemMessage(this.choicesRes.error()));

  private readonly modelsRes = resource({
    params: () => this.current.id(),
    loader: ({ params: companyId }) => this.api.invoke(listGatewayModels, { companyId }),
  });
  protected readonly gatewayModels = computed(() =>
    this.modelsRes.hasValue() ? (this.modelsRes.value()?.models ?? []) : [],
  );
  protected readonly modelsUnavailable = computed(() =>
    this.modelsRes.error()
      ? toProblem(this.modelsRes.error()).code === 'GATEWAY_NOT_CONFIGURED'
        ? this.subscriptionsOn()
          ? 'The company’s model gateway is not set up yet, so no model can be picked: only a subscription can pay.'
          : 'The company’s model gateway is not set up yet, so no model can be picked: a trigger pays with a budget, which needs the gateway.'
        : `The gateway’s models could not be read: ${problemMessage(this.modelsRes.error())}`
      : null,
  );

  private readonly ownServersRes = resource({
    params: () => this.current.id(),
    loader: async ({ params: companyId }): Promise<OwnServerOption[]> => {
      const systems = await loadAll((cursor) =>
        this.api.invoke(listSystems, { companyId, limit: 100, cursor }),
      );
      return Promise.all(
        systems
          .filter((s) => s.personal && s.kind === 'MCP')
          .map(async (system) => ({
            system,
            tools: await this.api
              .invoke(listSystemTools, { companyId, systemId: system.id })
              .then((list) => list.items)
              .catch(() => null),
          })),
      );
    },
  });
  protected readonly ownServers = computed(() =>
    this.ownServersRes.hasValue() ? (this.ownServersRes.value() ?? []) : [],
  );

  // --- The draft ---------------------------------------------------------------------------------

  /** The draft as saved, for "what changed"; null for a new trigger. */
  private readonly baseline = signal<TriggerDraft | null>(null);
  protected readonly askPrefilled = signal(false);
  /** Set once the author changed the settings by hand: the prefill then stays away. */
  private settingsTouched = false;

  /** An admin edits on the author's behalf: the choices are the author's (`forPrincipalId`). */
  private readonly forAuthor = computed(() => {
    const trigger = this.saved();
    return trigger && trigger.authorPrincipalId !== this.caller.id()
      ? trigger.authorPrincipalId
      : undefined;
  });

  /** Whose trigger this is: the reader for a new one, its author for a change. */
  protected readonly author = computed(
    () => this.saved()?.authorPrincipalId ?? this.caller.id() ?? null,
  );
  protected readonly payerIsAuthor = computed(
    () => !this.draft().payerPrincipalId || this.draft().payerPrincipalId === this.author(),
  );
  /** The models the payer's key allows: from its choice's budget, else the reader's own. */
  protected readonly payerModels = computed(() => {
    const payer = this.draft().payerPrincipalId;
    const choice = this.choices().find((c) => c.principalId === payer);
    if (choice?.budget.models) return choice.budget.models;
    return this.payerIsAuthor() ? (this.caller.modelAccess()?.models ?? []) : [];
  });
  protected readonly ownAllowed = computed(() =>
    allRowsOwn(this.draft(), this.choices(), this.author()),
  );

  protected readonly loading = computed(
    () =>
      !!this.triggerId() &&
      ((this.triggerRes.isLoading() && !this.saved()) ||
        (this.settingsRes.isLoading() && !this.settingsRes.hasValue())),
  );
  protected readonly loadError = computed(() =>
    this.triggerId() ? (this.triggerRes.error() ?? this.settingsRes.error()) : undefined,
  );
  protected readonly notFound = computed(() => toProblem(this.triggerRes.error()).status === 404);
  /** The form shows: not while loading, refused, or for a reader who may not change it. */
  protected readonly editable = computed(
    () => !this.loading() && !this.loadError() && !(this.saved() && !this.saved()!.rights.edits),
  );

  // --- The preview -------------------------------------------------------------------------------

  protected readonly preview = signal<TriggerPreview | null>(null);
  protected readonly previewing = signal(false);
  private readonly previewError = signal<TriggerFailure | null>(null);
  private previewSeq = 0;
  private previewTimer: ReturnType<typeof setTimeout> | undefined;

  protected readonly previewProblem = computed(() => this.previewError()?.message ?? null);
  protected readonly previewHint = computed(() => {
    const errors = draftErrors(this.draft());
    return errors.name ?? errors.when ?? errors.agent ?? errors.payer ?? null;
  });
  protected readonly whoActs = computed(() => {
    const p = this.preview();
    return p ? previewView(p, this.author()) : null;
  });
  /** "Acts as svc-ba · paid by svc-ba · can run": the phone's save bar. */
  protected readonly summary = computed(() => {
    const p = this.preview();
    return p ? previewSummary(p, this.author()) : null;
  });
  /** The trigger as one sentence, under its title: the preview's, else the one saved. */
  protected readonly sentence = computed(
    () => this.preview()?.sentence ?? this.saved()?.sentence ?? null,
  );

  // --- Saving ------------------------------------------------------------------------------------

  protected readonly busy = signal(false);
  protected readonly failure = signal<TriggerFailure | null>(null);
  /** A refusal the form says as a whole, above the sections. */
  protected readonly formError = computed(() => {
    const f = this.failure();
    return f && (f.at === null || f.at === 'panel') ? f.message : null;
  });

  private readonly errors = computed(() => draftErrors(this.draft()));
  protected readonly nameError = computed(
    () =>
      (this.failure()?.at === 'name' ? this.failure()!.message : null) ?? this.shownError('name'),
  );
  protected readonly rowErrors = computed<Record<string, string>>(() => {
    const f = this.failure() ?? this.previewError();
    return f?.at === 'accounts' && f.systemId ? { [f.systemId]: f.message } : {};
  });

  /** Whether the author has tried to save, so that the client's own checks show. */
  private readonly tried = signal(false);

  /**
   * The warning needs "I understand": the preview says the save would refuse without it
   * (`acknowledgementMissing`, which counts one given for this trigger before), and the author has
   * not ticked it since.
   */
  protected readonly ackMissing = computed(
    () =>
      !!this.preview()?.openToAnyone?.acknowledgementMissing && !this.draft().acknowledgeOpenEvents,
  );

  private readonly change = computed(() => {
    const base = this.baseline();
    return base ? changeOf(base, this.draft()) : null;
  });
  protected readonly holdReason = computed(() => {
    if (this.busy()) return null;
    const errors = this.errors();
    const first =
      errors.name ??
      errors.when ??
      errors.agent ??
      errors.accounts ??
      errors.payer ??
      errors.results;
    if (first) return first;
    if (this.previewError())
      return 'The preview refuses this trigger as it stands: see Before you save.';
    if (!this.preview() || this.previewing()) return 'Waiting for the preview…';
    if (this.ackMissing()) {
      return `Tick “I understand” in the warning to ${this.triggerId() ? 'save' : 'create it'}.`;
    }
    const change = this.change();
    if (change && !changed(change)) return 'No changes';
    return null;
  });
  protected readonly canSave = computed(() => !this.busy() && !this.holdReason());
  /** Under Create while nothing holds it: what the save does. */
  protected readonly doneLine = computed(() =>
    this.triggerId()
      ? 'Its open conversations keep the accounts they started with.'
      : 'It fires from now on; nothing before counts.',
  );

  // --- Sections ----------------------------------------------------------------------------------

  protected readonly sectionList = SECTIONS;
  /** The open section. */
  protected readonly section = signal<EditorSection>('when');
  /** On a phone: whether the open section shows, rather than the list of sections. */
  protected readonly phoneOnSection = signal(false);
  /** Whether the sections stand beside their tabs and "Before you save", rather than one screen each. */
  protected readonly wide = mediaQuery(WIDE_SCREEN, true);
  /** On a phone: "Before you save" as a sheet. */
  protected readonly sheetOpen = signal(false);
  protected readonly previousSection = computed(
    () => SECTIONS[SECTIONS.findIndex((s) => s.id === this.section()) - 1] ?? null,
  );
  protected readonly nextSection = computed(
    () => SECTIONS[SECTIONS.findIndex((s) => s.id === this.section()) + 1] ?? null,
  );

  /** Each section's tab: how it stands, a line of what it holds, and whether it needs the reader. */
  protected readonly sectionRows = computed(() =>
    SECTIONS.map((s) => ({ ...s, ...this.summaryOf(s.id, this.draft()) })),
  );

  constructor() {
    const destroyRef = inject(DestroyRef);
    destroyRef.onDestroy(() => clearTimeout(this.previewTimer));

    // A change: its draft, once the trigger and its settings are read.
    effect(() => {
      const trigger = this.saved();
      const settings = this.settingsRes.hasValue() ? (this.settingsRes.value() ?? null) : null;
      if (!trigger || !settings) return;
      untracked(() => this.adopt(trigger, settings));
    });

    // A new trigger: the agent in the address.
    effect(() => {
      const agentId = this.agentId();
      if (!agentId || this.triggerId()) return;
      untracked(() => {
        if (!this.draft().agentId) this.setAgent(agentId);
      });
    });

    // A new trigger: its agent's version and default settings, once the agent is read.
    effect(() => {
      const agent = this.agent();
      if (!agent || this.triggerId()) return;
      untracked(() =>
        this.draft.update((d) =>
          d.agentId === agent.id && d.settings === null
            ? { ...d, agentVersion: agent.version, settings: defaultSettingsDraft() }
            : d,
        ),
      );
    });

    // The one choice: the first offered fills a new trigger's table (service accounts come
    // first while anyone can drive it); a picked choice fills it again when the source changes.
    effect(() => {
      const choices = this.choices();
      if (!choices.length || this.triggerId()) return;
      untracked(() => {
        const d = this.draft();
        const picked = choices.find((c) => c.principalId === d.choice);
        if (picked) this.draft.set(fillFromChoice(d, picked, this.author()));
        else if (!d.accounts.length) this.draft.set(fillFromChoice(d, choices[0], this.author()));
      });
    });

    // The preview, after every change.
    effect(() => {
      const d = this.draft();
      const companyId = this.current.id();
      untracked(() => this.schedulePreview(companyId, d));
    });

    // Open events: every write asks, once, on a new trigger whose settings were not touched.
    effect(() => {
      const open = this.preview()?.openToAnyone;
      const agent = this.agent();
      if (!open || !agent || this.triggerId()) return;
      untracked(() => {
        if (this.askPrefilled() || this.settingsTouched || !this.draft().settings) return;
        this.askPrefilled.set(true);
        this.draft.update((d) => ({
          ...d,
          settings: askOnEveryWrite(agent, d.settings!),
        }));
      });
    });

    // A wide screen shows the section beside its tabs; the sheet is the phone's.
    effect(() => {
      if (this.wide()) untracked(() => this.sheetOpen.set(false));
    });
  }

  private adopt(trigger: Trigger, settings: AgentSettings): void {
    const draft = draftOf(trigger, settings);
    this.baseline.set(draft);
    this.draft.set(draft);
  }

  protected retry(): void {
    this.triggerRes.reload();
    this.settingsRes.reload();
  }

  protected sectionError(section: TriggerSection | 'acknowledge'): string | null {
    const f = this.failure();
    if (f?.at === section && !(section === 'accounts' && f.systemId)) return f.message;
    if (section === 'accounts' || section === 'payer') {
      const p = this.previewError();
      if (p?.at === section && !(section === 'accounts' && p.systemId)) return p.message;
    }
    if (section === 'acknowledge' && this.tried() && this.ackMissing()) {
      return 'Tick “I understand” to save, or set the write tools to Ask.';
    }
    return this.shownError(section);
  }

  private shownError(section: TriggerSection | 'name' | 'acknowledge'): string | null {
    if (!this.tried() || section === 'acknowledge') return null;
    return this.errors()[section] ?? null;
  }

  // --- The tabs' summaries -----------------------------------------------------------------------

  private summaryOf(
    id: EditorSection,
    d: TriggerDraft,
  ): { state: SectionState; summary: string; dot: boolean } {
    const errors = this.errors();
    const state = (problem: boolean, done: boolean): SectionState =>
      problem ? 'error' : done ? 'done' : 'todo';
    switch (id) {
      case 'when':
        return {
          state: state(
            !!this.nameError() || !!this.sectionError('when'),
            !errors.name && !errors.when,
          ),
          summary: this.whenText(d),
          dot: false,
        };
      case 'agent': {
        const name = this.agentName();
        return {
          state: state(!!this.sectionError('agent'), !errors.agent),
          summary: d.agentId
            ? `${name ?? 'Its agent'} · ${d.instruction.trim() ? 'instruction written' : 'no instruction yet'}`
            : 'Pick an agent',
          dot: false,
        };
      }
      case 'accounts':
        return {
          state: state(
            !!this.sectionError('accounts') ||
              !!this.sectionError('payer') ||
              Object.keys(this.rowErrors()).length > 0,
            d.accounts.length > 0 && !errors.accounts && !errors.payer,
          ),
          summary: this.actsText(d),
          dot: this.ackMissing(),
        };
      case 'settings':
        return this.settingsSummary(d);
      case 'team':
        return {
          state: 'done',
          summary: d.teamGroupId
            ? (this.team()?.name ?? this.saved()?.teamName ?? 'A team')
            : 'Only you',
          dot: false,
        };
      case 'results':
        return {
          state: state(!!this.sectionError('results'), !errors.results),
          summary: this.resultsText(d),
          dot: false,
        };
    }
  }

  /** "Jira · assigned · SHOP", "Slack · #support", "Schedule · 0 9 * * 1 · Europe/Prague". */
  private whenText(d: TriggerDraft): string {
    const system =
      this.systems().find((s) => s.id === d.systemId)?.name ??
      (d.systemId ? (this.saved()?.systemName ?? null) : null);
    switch (d.source) {
      case 'TRACKER': {
        if (!system) return 'Pick the system it watches';
        const first = d.events[0];
        const events = first
          ? `${EVENT_SHORT[first.event]}${d.events.length > 1 ? ` +${d.events.length - 1}` : ''}`
          : 'no event yet';
        return `${system} · ${events} · ${d.scope.trim() || 'all it sees'}`;
      }
      case 'CHANNEL': {
        if (!system) return 'Pick the chat';
        const channels = d.channels.map((c) => `#${c.name ?? c.channelId}`).join(', ');
        return `${system} · ${channels || 'no channel yet'}`;
      }
      case 'WEBHOOK':
        return system
          ? `${system} · ${d.webhookEvents.trim() || 'every delivery'}`
          : 'Pick the webhook';
      case 'SCHEDULE':
        return `Schedule · ${d.schedule.cron.trim() || 'no schedule yet'} · ${d.schedule.zone.trim()}`;
    }
  }

  /** "svc-reader · svc-reader’s budget", "You · your budget". */
  private actsText(d: TriggerDraft): string {
    const author = this.author();
    const holders: string[] = [];
    for (const row of d.accounts) {
      const choice = this.choices().find((c) =>
        c.systems.some((s) => s.systemId === row.systemId && s.accountId === row.accountId),
      );
      const kept = this.saved()?.accounts.find((a) => a.accountId === row.accountId);
      const principal = choice?.principalId ?? kept?.holderPrincipalId ?? null;
      const who =
        principal && principal === author
          ? 'you'
          : (choice?.name ?? kept?.holderName ?? 'an account');
      if (!holders.includes(who)) holders.push(who);
    }
    const payerId = d.payerPrincipalId;
    let pays = 'nobody pays yet';
    if (d.settings?.modelAccess === 'SUBSCRIPTION') pays = 'your subscription';
    else if (payerId && payerId === author) pays = 'your budget';
    else if (payerId) {
      const name =
        this.choices().find((c) => c.principalId === payerId)?.name ??
        (this.saved()?.payer?.principalId === payerId ? this.saved()?.payer?.name : null);
      pays = `${name ?? 'its payer'}’s budget`;
    }
    // Capitalised only where it starts with the editor's own words: a name stays as it is.
    const acts = holders.join(' and ');
    return `${acts.startsWith('you') ? capitalised(acts) : acts || 'No account yet'} · ${pays}`;
  }

  /** "2 writes ask · claude-sonnet": what asks, and the model. */
  private settingsSummary(d: TriggerDraft): { state: SectionState; summary: string; dot: boolean } {
    const agent = this.agent();
    const settings = d.settings;
    if (!agent || !settings) {
      return { state: 'todo', summary: 'Pick the agent first', dot: false };
    }
    let writes = 0;
    let asks = 0;
    const widening = this.settingsRes.hasValue()
      ? (this.settingsRes.value()?.widening ?? null)
      : null;
    for (const system of settingsSystemRows(agent, widening)) {
      if (settings.systemsOff.includes(system.systemId)) continue;
      for (const tool of system.tools) {
        if (tool.effect === 'READ') continue;
        const choice = toolChoice(settings, system.systemId, tool.name);
        if (choice === 'OFF') continue;
        writes++;
        if (choice === 'ASK' || tool.agentMode === 'ASK') asks++;
      }
    }
    const subscription = settings.modelAccess === 'SUBSCRIPTION';
    const model = settings.model || (subscription ? 'the runtime’s model' : 'no model yet');
    const problem =
      !!this.sectionError('settings') || Object.keys(this.failure()?.settings ?? {}).length > 0;
    return {
      state: problem ? 'error' : settings.model || subscription ? 'done' : 'todo',
      summary: `${writes ? count(asks, 'write asks', 'writes ask') : 'Reads only'} · ${model}`,
      dot: false,
    };
  }

  /** "Comment on the issue · reviewed first · 2 actions · at most 30 an hour". */
  private resultsText(d: TriggerDraft): string {
    const tracker = d.source === 'TRACKER';
    const parts: string[] = [];
    if (tracker && d.results.comment) parts.push('Comment on the issue');
    if (d.results.review) parts.push('reviewed first');
    const actions = d.results.onSuccess.length + d.results.onFailure.length;
    if (tracker && actions) parts.push(count(actions, 'action', 'actions'));
    const limit = d.maxFiringsPerHour.trim() || String(this.limits()?.default ?? 30);
    parts.push(`at most ${limit} an hour`);
    return capitalised(parts.join(' · '));
  }

  // --- Moving between the sections ---------------------------------------------------------------

  /**
   * Opens a section; on a phone, on its own screen. There the tabs hide as it opens, so the focus
   * goes to its heading rather than staying on a hidden tab; beside the tabs it stays on the tab.
   */
  protected openSection(id: EditorSection, focusHeading = !this.wide()): void {
    this.section.set(id);
    this.phoneOnSection.set(true);
    if (focusHeading) setTimeout(() => document.getElementById(`${id}-title`)?.focus());
  }

  /** Back and Next: opens the section and brings its heading to the reader. */
  protected step(id: EditorSection): void {
    this.openSection(id, true);
  }

  protected showSectionList(): void {
    this.phoneOnSection.set(false);
    setTimeout(() => document.getElementById(`section-tab-${this.section()}`)?.focus());
  }

  /** The phone's sheet sends the reader to a section: it closes, and the section opens. */
  protected openFromSheet(id: EditorSection): void {
    this.sheetOpen.set(false);
    this.openSection(id, true);
  }

  /** The arrow keys, Home and End move along the tabs of the sections, opening each. */
  protected onSectionKeys(event: KeyboardEvent): void {
    const to = tabKeyTarget(
      event.key,
      SECTIONS.map((s) => s.id),
      this.section(),
    ) as EditorSection | null;
    if (!to) return;
    event.preventDefault();
    this.section.set(to);
    document.getElementById(`section-tab-${to}`)?.focus();
  }

  // --- Changing the draft ------------------------------------------------------------------------

  protected setDraft(draft: TriggerDraft): void {
    this.draft.set(draft);
    this.failure.set(null);
  }

  protected patch(change: Partial<TriggerDraft>): void {
    this.setDraft({ ...this.draft(), ...change });
  }

  protected setSettings(settings: SettingsDraft): void {
    // Only what the prefill sets keeps it away: the model comes first, as the preview refuses a budget without one.
    const before = this.draft().settings;
    if (
      !before ||
      before.systemsOff !== settings.systemsOff ||
      before.toolModes !== settings.toolModes ||
      before.ownServers !== settings.ownServers
    ) {
      this.settingsTouched = true;
    }
    this.patch({ settings });
  }

  protected setAgent(agentId: string): void {
    const agent = this.agents().find((a) => a.id === agentId);
    this.setDraft({
      ...this.draft(),
      agentId: agentId || null,
      agentVersion: agent?.version ?? null,
      settings: null,
      choice: null,
      accounts: [],
      payerPrincipalId: null,
    });
    this.askPrefilled.set(false);
    this.settingsTouched = false;
  }

  protected askEveryWrite(): void {
    const agent = this.agent();
    const d = this.draft();
    if (!agent || !d.settings) return;
    this.settingsTouched = true;
    this.patch({ settings: askOnEveryWrite(agent, d.settings) });
  }

  private schedulePreview(companyId: string | undefined, draft: TriggerDraft): void {
    clearTimeout(this.previewTimer);
    const seq = ++this.previewSeq;
    if (!companyId || !previewable(draft) || !draft.settings) {
      this.previewing.set(false);
      this.preview.set(null);
      this.previewError.set(null);
      return;
    }
    this.previewing.set(true);
    this.previewTimer = setTimeout(async () => {
      try {
        const preview = await this.api.invoke(previewTrigger, {
          companyId,
          body: previewRequest(draft, this.triggerId() ?? null),
        });
        if (seq !== this.previewSeq) return;
        this.preview.set(preview);
        this.previewError.set(null);
      } catch (error) {
        if (seq !== this.previewSeq) return;
        this.preview.set(null);
        this.previewError.set(triggerFailure(error));
      } finally {
        if (seq === this.previewSeq) this.previewing.set(false);
      }
    }, this.debounce);
  }

  // --- Saving ------------------------------------------------------------------------------------

  protected async save(event: Event): Promise<void> {
    event.preventDefault();
    this.tried.set(true);
    const companyId = this.current.id();
    if (!companyId || !this.canSave()) return;
    const draft = this.draft();
    this.busy.set(true);
    this.failure.set(null);
    try {
      const triggerId = this.triggerId();
      if (!triggerId) {
        const trigger = await this.api.invoke(createTrigger, {
          companyId,
          body: createRequest(draft),
        });
        this.notify.success('Trigger created', 'It fires from now on; nothing before counts.');
        void this.router.navigate(['/c', companyId, 'triggers', trigger.id]);
        return;
      }
      const change = this.change();
      if (change?.definition) {
        await this.api.invoke(updateTrigger, { companyId, triggerId, body: updateRequest(draft) });
      }
      if (change?.accounts) {
        await this.api.invoke(replaceTriggerAccounts, {
          companyId,
          triggerId,
          body: {
            accounts: draft.accounts.map((a) => ({ ...a })),
            payerPrincipalId: draft.payerPrincipalId ?? '',
            acknowledgeOpenEvents: draft.acknowledgeOpenEvents,
          },
        });
      }
      if (change?.settings && draft.settings) {
        await this.api.invoke(saveTriggerSettings, {
          companyId,
          triggerId,
          body: settingsRequest(draft.settings),
        });
      }
      this.notify.success(
        'Trigger saved',
        'Its open conversations keep the accounts they started with.',
      );
      void this.router.navigate(['/c', companyId, 'triggers', triggerId]);
    } catch (error) {
      const failure = triggerFailure(error);
      this.failure.set(failure);
      this.showFailure(failure);
    } finally {
      this.busy.set(false);
    }
  }

  /**
   * Opens the section a refused save names and brings its field, or its message, to the reader.
   * "I understand" stands beside the sections; on a phone, in Acts as and pays.
   */
  private showFailure(failure: TriggerFailure): void {
    const section =
      failure.at === 'acknowledge'
        ? this.wide()
          ? null
          : 'accounts'
        : failure.at
          ? FAILURE_SECTIONS[failure.at]
          : null;
    if (section) this.openSection(section, false);
    this.sheetOpen.set(false);
    setTimeout(() => {
      const target =
        document.querySelector<HTMLElement>(failureTarget(failure)) ??
        (section ? document.getElementById(`${section}-title`) : null);
      target?.scrollIntoView?.({ block: 'center' });
      target?.focus({ preventScroll: true });
    });
  }
}
