import { ChangeDetectionStrategy, Component, computed, input, model, signal } from '@angular/core';
import { NgIcon, provideIcons } from '@ng-icons/core';
import {
  lucideCheck,
  lucideChevronDown,
  lucideCircleDot,
  lucideClock,
  lucideHash,
  lucidePlus,
  lucideWebhook,
  lucideX,
} from '@ng-icons/lucide';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmDropdownMenuImports } from '@spartan-ng/helm/dropdown-menu';
import { HlmInput } from '@spartan-ng/helm/input';
import { HlmLabel } from '@spartan-ng/helm/label';
import { HlmNativeSelectImports } from '@spartan-ng/helm/native-select';
import { HlmSheetImports } from '@spartan-ng/helm/sheet';
import type { ExternalSystem, TriggerEvent, TriggerSource } from '../../../api/generated/models';
import { mediaQuery } from '../../../core/media-query';
import {
  formatInZone,
  knownTimeZone,
  nextFirings,
  parseCron,
  timeZones,
  tooOften,
} from '../../../shared/cron';
import { SYSTEM_KIND_LABELS } from '../../../shared/labels';
import { TriggerChannelsField } from './trigger-channels-field';
import {
  type DraftConditionGroup,
  type DraftWebhookCondition,
  MAX_WEBHOOK_CONDITIONS,
  emptyConditionGroup,
  MAX_CONDITION_GROUPS,
  MAX_EVENTS,
  subjectFor,
  type TriggerDraft,
} from './trigger-draft';
import {
  EVENT_LABELS,
  EVENT_VALUE_LABELS,
  EVENTS_WITH_VALUE,
  eventsFor,
  isJira,
  REPEAT_CHOICES,
  scopeHint,
  SOURCE_DETAILS,
  SOURCE_LABELS,
  sourceKinds,
  SUBJECT_LABELS,
  WEBHOOK_OP_LABELS,
  subjectsFor,
} from './trigger-labels';

/** One way a trigger starts, as the select offers it. */
export interface SourceOption {
  readonly value: TriggerSource;
  readonly label: string;
  readonly detail: string;
  readonly icon: string;
}

const SOURCE_ICONS: Record<TriggerSource, string> = {
  TRACKER: 'lucideCircleDot',
  CHANNEL: 'lucideHash',
  WEBHOOK: 'lucideWebhook',
  SCHEDULE: 'lucideClock',
};

export const SOURCE_OPTIONS: readonly SourceOption[] = (
  ['TRACKER', 'CHANNEL', 'WEBHOOK', 'SCHEDULE'] as const
).map((value) => ({
  value,
  label: SOURCE_LABELS[value],
  detail: SOURCE_DETAILS[value],
  icon: SOURCE_ICONS[value],
}));

/** Wide enough for the select's menu; narrower, the field opens a sheet. */
const MENU_SCREEN = '(min-width: 40rem)';

const NO_SYSTEM: Record<string, string> = {
  TRACKER:
    'No Jira, GitHub or GitLab system is set up in this company yet. An admin adds one under Systems.',
  CHANNEL:
    'No Slack or Teams system is set up in this company yet. An admin adds one under Systems.',
  WEBHOOK: 'No webhook is set up in this company yet. An admin registers one under Systems.',
};

export const OVERLAP_CHOICES = [
  {
    value: 'SKIP' as const,
    label: 'Skip while the last one is busy',
    detail: 'No new conversation while the previous firing’s is still working or waiting.',
  },
  {
    value: 'CREATE' as const,
    label: 'Start one anyway',
    detail: 'Every firing starts a conversation of its own.',
  },
];

export const CATCH_UP_CHOICES = [
  {
    value: 'NONE' as const,
    label: 'Skip missed firings',
    detail: 'A firing missed while Tinfirm was down starts nothing.',
  },
  {
    value: 'ONE' as const,
    label: 'Catch up once',
    detail: 'One conversation for the missed firings, then the schedule goes on.',
  },
];

type ConditionField = keyof Omit<DraftConditionGroup, 'authors'>;

const CONDITION_ROWS: readonly { field: ConditionField; label: string; jiraOnly?: boolean }[] = [
  { field: 'statuses', label: 'Status is one of', jiraOnly: true },
  { field: 'notStatuses', label: 'Status is none of', jiraOnly: true },
  { field: 'labels', label: 'Has a label of', jiraOnly: false },
  { field: 'notLabels', label: 'Has no label of', jiraOnly: false },
  { field: 'types', label: 'Type is one of', jiraOnly: true },
  { field: 'notTypes', label: 'Type is none of', jiraOnly: true },
];

/** The next three firings of a cron in a zone, as "Sun 2026-11-01 09:00"; none while invalid. */
export function schedulePreview(cron: string, zone: string, now = new Date()): string[] {
  const parsed = parseCron(cron);
  if ('error' in parsed || tooOften(parsed.cron) || !knownTimeZone(zone)) return [];
  return nextFirings(parsed.cron, zone.trim(), now, 3).map((date) =>
    formatInZone(date, zone.trim()),
  );
}

/**
 * "When" of the trigger editor (plan §4.5, step 1): how it starts, a select of the four kinds
 * (something on an issue, a mention in a channel, a webhook, a schedule; a sheet of them on a
 * phone), and the system of the company it belongs to; up to five events with their values; the
 * subject kind; the scope; groups of conditions; once or every time. A schedule takes its cron and
 * time zone, what happens while the last firing is busy and to missed firings. A change keeps the
 * source and its system (fixed).
 */
@Component({
  selector: 'app-trigger-when-fields',
  imports: [
    NgIcon,
    HlmButton,
    HlmDropdownMenuImports,
    HlmInput,
    HlmLabel,
    HlmNativeSelectImports,
    HlmSheetImports,
    TriggerChannelsField,
  ],
  providers: [
    provideIcons({
      lucideCheck,
      lucideChevronDown,
      lucideCircleDot,
      lucideClock,
      lucideHash,
      lucidePlus,
      lucideWebhook,
      lucideX,
    }),
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'flex flex-col gap-4' },
  template: `
    @let d = draft();
    @if (!fixed()) {
      @let chosen = sourceOf(d.source);
      <!-- A select: the chosen kind with its icon, its detail as the help line. On a phone the
           field opens a sheet of the four kinds instead of a menu. -->
      <div class="flex min-w-0 flex-col gap-1.5" data-field="source">
        <span class="text-sm font-medium" id="trigger-source-label">It starts when</span>
        @if (menu()) {
          <button
            type="button"
            id="trigger-source"
            [class]="fieldClass"
            [hlmDropdownMenuTrigger]="sourceMenu"
            align="start"
            aria-labelledby="trigger-source-label trigger-source-value"
            aria-describedby="trigger-source-hint"
          >
            <ng-icon [name]="chosen.icon" class="text-muted-foreground" aria-hidden="true" />
            <span class="min-w-0 flex-1 truncate" id="trigger-source-value">{{
              chosen.label
            }}</span>
            <ng-icon name="lucideChevronDown" class="text-muted-foreground" aria-hidden="true" />
          </button>
        } @else {
          <button
            type="button"
            id="trigger-source"
            [class]="fieldClass"
            aria-haspopup="dialog"
            [attr.aria-expanded]="sheetOpen()"
            aria-labelledby="trigger-source-label trigger-source-value"
            aria-describedby="trigger-source-hint"
            (click)="sheetOpen.set(true)"
          >
            <ng-icon [name]="chosen.icon" class="text-muted-foreground" aria-hidden="true" />
            <span class="min-w-0 flex-1 truncate" id="trigger-source-value">{{
              chosen.label
            }}</span>
            <ng-icon name="lucideChevronDown" class="text-muted-foreground" aria-hidden="true" />
          </button>
        }
        <p class="text-muted-foreground text-xs" id="trigger-source-hint">{{ chosen.detail }}.</p>
      </div>

      <ng-template #sourceMenu>
        <hlm-dropdown-menu class="w-[min(24rem,calc(100vw-2rem))]" aria-label="It starts when">
          @for (option of sources; track option.value) {
            <button
              type="button"
              hlmDropdownMenuRadio
              class="data-checked:bg-primary/10 items-start gap-2.5 py-2 ps-2.5 text-start"
              [checked]="option.value === draft().source"
              [keepOpen]="false"
              [attr.data-source]="option.value"
              (triggered)="setSource(option.value)"
            >
              <ng-icon
                [name]="option.icon"
                class="mt-0.5"
                [class]="option.value === draft().source ? 'text-primary' : 'text-muted-foreground'"
                aria-hidden="true"
              />
              <span class="flex min-w-0 flex-1 flex-col leading-snug">
                <span class="font-semibold">{{ option.label }}</span>
                <span class="text-muted-foreground text-xs">{{ option.detail }}</span>
              </span>
              <hlm-dropdown-menu-radio-indicator class="top-2.5" />
            </button>
          }
        </hlm-dropdown-menu>
      </ng-template>

      @if (!menu()) {
        <hlm-sheet
          side="bottom"
          [state]="sheetOpen() ? 'open' : 'closed'"
          (closed)="sheetOpen.set(false)"
        >
          <hlm-sheet-content
            *hlmSheetPortal="let ctx"
            class="max-h-[85dvh] gap-2 overflow-y-auto rounded-t-[18px] px-3 pt-3 pb-6"
          >
            <hlm-sheet-header class="px-1 py-1.5">
              <h2 hlmSheetTitle class="font-bold" id="trigger-source-sheet-title">
                It starts when
              </h2>
            </hlm-sheet-header>
            <div
              class="flex flex-col gap-1"
              role="radiogroup"
              aria-labelledby="trigger-source-sheet-title"
            >
              @for (option of sources; track option.value) {
                @let on = option.value === draft().source;
                <button
                  type="button"
                  role="radio"
                  (keydown)="onSheetKeys($event)"
                  class="hover:bg-accent focus-visible:ring-ring/50 flex min-h-14 w-full items-start gap-3 rounded-xl px-3 py-2.5 text-start outline-none focus-visible:ring-3"
                  [class]="on ? 'bg-primary/10' : ''"
                  [attr.aria-checked]="on"
                  [attr.data-source]="option.value"
                  (click)="pickFromSheet(option.value)"
                >
                  <ng-icon
                    [name]="option.icon"
                    size="18"
                    class="mt-0.5 shrink-0"
                    [class]="on ? 'text-primary' : 'text-muted-foreground'"
                    aria-hidden="true"
                  />
                  <span class="flex min-w-0 flex-1 flex-col leading-snug">
                    <span class="text-[0.9375rem] font-semibold">{{ option.label }}</span>
                    <span class="text-muted-foreground text-[0.8125rem]">{{ option.detail }}</span>
                  </span>
                  @if (on) {
                    <ng-icon
                      name="lucideCheck"
                      size="18"
                      class="text-primary mt-0.5 shrink-0"
                      aria-hidden="true"
                    />
                  }
                </button>
              }
            </div>
          </hlm-sheet-content>
        </hlm-sheet>
      }
    }

    @if (d.source !== 'SCHEDULE') {
      <div class="flex min-w-0 flex-col gap-1.5">
        <label hlmLabel for="trigger-system">{{
          d.source === 'CHANNEL' ? 'Chat' : d.source === 'WEBHOOK' ? 'Webhook' : 'System'
        }}</label>
        @if (fixed()) {
          <p class="text-sm" data-testid="fixed-system">
            {{ system()?.name ?? 'Its system' }}
            <span class="text-muted-foreground text-xs"
              >(fixed: a trigger belongs to its system)</span
            >
          </p>
        } @else {
          <hlm-native-select
            class="w-full sm:max-w-sm"
            selectId="trigger-system"
            [value]="d.systemId ?? ''"
            (valueChange)="setSystem($event ?? '')"
          >
            <option hlmNativeSelectOption value="">Pick a system</option>
            @for (s of sourceSystems(); track s.id) {
              <option hlmNativeSelectOption [value]="s.id" [selected]="s.id === d.systemId">
                {{ s.name }} · {{ kindLabels[s.kind] }}
              </option>
            }
          </hlm-native-select>
          @if (sourceSystems().length === 0) {
            <p class="text-muted-foreground text-xs" data-testid="no-trackers">
              {{ noSystem[d.source] }}
            </p>
          }
        }
      </div>
    }

    @if (d.source === 'CHANNEL' && d.systemId) {
      <app-trigger-channels-field
        [systemId]="d.systemId"
        [authorPrincipalId]="author()"
        [draft]="d.channels"
        (draftChange)="patch({ channels: $event })"
      />
    } @else if (d.source === 'WEBHOOK' && d.systemId) {
      <div class="flex min-w-0 flex-col gap-1.5">
        <label hlmLabel for="trigger-webhook-events">Its events</label>
        <input
          hlmInput
          id="trigger-webhook-events"
          class="font-mono"
          autocomplete="off"
          spellcheck="false"
          placeholder="issue.created, issue.updated"
          aria-describedby="trigger-webhook-events-hint"
          [value]="d.webhookEvents"
          (input)="patch({ webhookEvents: $any($event.target).value })"
        />
        <p class="text-muted-foreground text-xs" id="trigger-webhook-events-hint">
          The event names the webhook’s mapping reads from a delivery, comma-separated. Empty: every
          delivery.
        </p>
      </div>
      <fieldset
        class="m-0 flex min-w-0 flex-col gap-2 border-0 p-0"
        data-testid="trigger-webhook-conditions"
      >
        <legend class="mb-1 text-sm font-medium">
          Only when the body <span class="text-muted-foreground font-normal">(optional)</span>
        </legend>
        @for (c of d.webhookConditions; track $index; let i = $index) {
          <div
            class="flex min-w-0 flex-col gap-2 sm:flex-row sm:items-center"
            [attr.data-condition]="i"
          >
            <label class="sr-only" [for]="'trigger-wc-pointer-' + i">Pointer {{ i + 1 }}</label>
            <input
              hlmInput
              class="w-full font-mono sm:max-w-56"
              autocomplete="off"
              spellcheck="false"
              placeholder="/issue/fields/priority"
              [id]="'trigger-wc-pointer-' + i"
              [value]="c.pointer"
              (input)="setWebhookCondition(i, { pointer: $any($event.target).value })"
            />
            <label class="sr-only" [for]="'trigger-wc-op-' + i">Condition {{ i + 1 }}</label>
            <hlm-native-select
              class="w-full sm:max-w-36"
              [selectId]="'trigger-wc-op-' + i"
              [value]="c.op"
              (valueChange)="setWebhookCondition(i, { op: $any($event) })"
            >
              @for (op of webhookOps; track op) {
                <option hlmNativeSelectOption [value]="op" [selected]="op === c.op">
                  {{ opLabels[op] }}
                </option>
              }
            </hlm-native-select>
            @if (c.op === 'EQUALS' || c.op === 'IN') {
              <label class="sr-only" [for]="'trigger-wc-values-' + i">Values {{ i + 1 }}</label>
              <input
                hlmInput
                class="w-full sm:max-w-48"
                autocomplete="off"
                [id]="'trigger-wc-values-' + i"
                [placeholder]="c.op === 'IN' ? 'High, Highest' : 'High'"
                [value]="c.values"
                (input)="setWebhookCondition(i, { values: $any($event.target).value })"
              />
            }
            <button
              hlmBtn
              variant="ghost"
              size="icon-sm"
              type="button"
              [attr.aria-label]="'Remove condition ' + (i + 1)"
              (click)="removeWebhookCondition(i)"
            >
              <ng-icon name="lucideX" aria-hidden="true" />
            </button>
          </div>
        }
        @if (d.webhookConditions.length < maxWebhookConditions) {
          <button
            hlmBtn
            variant="outline"
            size="sm"
            type="button"
            class="self-start"
            data-testid="add-webhook-condition"
            (click)="addWebhookCondition()"
          >
            <ng-icon name="lucidePlus" aria-hidden="true" />
            Add a condition
          </button>
        }
      </fieldset>
    }

    @if (d.source === 'TRACKER') {
      @if (d.systemId) {
        <fieldset class="m-0 flex min-w-0 flex-col gap-2 border-0 p-0" data-testid="trigger-events">
          <legend class="mb-1 text-sm font-medium">Events (any of them fires it)</legend>
          @for (e of d.events; track $index; let i = $index) {
            <div
              class="flex min-w-0 flex-col gap-2 sm:flex-row sm:items-center"
              [attr.data-event]="i"
            >
              <label class="sr-only" [for]="'trigger-event-' + i">Event {{ i + 1 }}</label>
              <hlm-native-select
                class="w-full sm:max-w-xs"
                [selectId]="'trigger-event-' + i"
                [value]="e.event"
                (valueChange)="setEvent(i, $any($event))"
              >
                @for (option of events(); track option) {
                  <option hlmNativeSelectOption [value]="option">{{ eventLabels[option] }}</option>
                }
              </hlm-native-select>
              @if (withValue.has(e.event)) {
                <input
                  hlmInput
                  class="w-full sm:max-w-48"
                  autocomplete="off"
                  maxlength="255"
                  [id]="'trigger-event-value-' + i"
                  [attr.aria-label]="(valueLabels[e.event] ?? 'Value') + ' of event ' + (i + 1)"
                  [placeholder]="valueLabels[e.event] ?? ''"
                  [value]="e.value"
                  (input)="setEventValue(i, $any($event.target).value)"
                />
              }
              @if (d.events.length > 1) {
                <button
                  hlmBtn
                  variant="ghost"
                  size="icon-sm"
                  type="button"
                  [attr.aria-label]="'Remove event ' + (i + 1)"
                  (click)="removeEvent(i)"
                >
                  <ng-icon name="lucideX" aria-hidden="true" />
                </button>
              }
            </div>
          }
          @if (d.events.length < maxEvents) {
            <button
              hlmBtn
              variant="outline"
              size="sm"
              type="button"
              class="self-start"
              data-testid="add-event"
              (click)="addEvent()"
            >
              <ng-icon name="lucidePlus" aria-hidden="true" />
              Add an event
            </button>
          }
        </fieldset>

        <div class="grid min-w-0 gap-4 sm:grid-cols-2">
          @if (subjects().length > 1) {
            <div class="flex min-w-0 flex-col gap-1.5">
              <label hlmLabel for="trigger-subject">On</label>
              <hlm-native-select
                selectId="trigger-subject"
                [value]="d.subject"
                (valueChange)="patch({ subject: $any($event) })"
              >
                @for (s of subjects(); track s) {
                  <option hlmNativeSelectOption [value]="s">{{ subjectLabels[s] }}</option>
                }
              </hlm-native-select>
            </div>
          }
          <div class="flex min-w-0 flex-col gap-1.5">
            <label hlmLabel for="trigger-scope">{{ scope().label }}</label>
            <input
              hlmInput
              id="trigger-scope"
              class="font-mono"
              autocomplete="off"
              spellcheck="false"
              maxlength="140"
              [placeholder]="scope().placeholder"
              [value]="d.scope"
              aria-describedby="trigger-scope-hint"
              (input)="patch({ scope: $any($event.target).value })"
            />
            <p class="text-muted-foreground text-xs" id="trigger-scope-hint">{{ scope().hint }}</p>
          </div>
        </div>

        <fieldset
          class="m-0 flex min-w-0 flex-col gap-2 border-0 p-0"
          data-testid="trigger-conditions"
        >
          <legend class="mb-1 text-sm font-medium">
            Only when <span class="text-muted-foreground font-normal">(optional)</span>
          </legend>
          @if (d.conditions.length === 0) {
            <p class="text-muted-foreground text-xs">
              No conditions: every event in the scope fires it.
            </p>
          }
          @for (g of d.conditions; track $index; let gi = $index) {
            @if (gi > 0) {
              <p class="text-muted-foreground text-xs font-semibold">or</p>
            }
            <div class="flex min-w-0 flex-col gap-2 rounded-lg border p-3" [attr.data-group]="gi">
              <div class="grid min-w-0 gap-2 sm:grid-cols-2">
                @for (row of conditionRows(); track row.field) {
                  <div class="flex min-w-0 flex-col gap-1">
                    <label
                      class="text-muted-foreground text-xs"
                      [for]="'trigger-cond-' + gi + '-' + row.field"
                      >{{ row.label }}</label
                    >
                    <input
                      hlmInput
                      autocomplete="off"
                      [id]="'trigger-cond-' + gi + '-' + row.field"
                      placeholder="comma, separated"
                      [value]="g[row.field]"
                      (input)="setCondition(gi, row.field, $any($event.target).value)"
                    />
                  </div>
                }
                <div class="flex min-w-0 flex-col gap-1">
                  <label
                    class="text-muted-foreground text-xs"
                    [for]="'trigger-cond-' + gi + '-authors'"
                    >Made by</label
                  >
                  <hlm-native-select
                    [selectId]="'trigger-cond-' + gi + '-authors'"
                    [value]="g.authors"
                    (valueChange)="setAuthors(gi, $any($event ?? ''))"
                  >
                    <option hlmNativeSelectOption value="">Anyone</option>
                    <option hlmNativeSelectOption value="PEOPLE">People</option>
                    <option hlmNativeSelectOption value="TINFIRM">
                      Tinfirm (a conversation made it)
                    </option>
                  </hlm-native-select>
                </div>
              </div>
              <button
                hlmBtn
                variant="ghost"
                size="sm"
                type="button"
                class="self-end"
                [attr.aria-label]="'Remove condition group ' + (gi + 1)"
                (click)="removeGroup(gi)"
              >
                <ng-icon name="lucideX" aria-hidden="true" />
                Remove
              </button>
            </div>
          }
          @if (d.conditions.length < maxGroups) {
            <button
              hlmBtn
              variant="outline"
              size="sm"
              type="button"
              class="self-start"
              data-testid="add-condition-group"
              (click)="addGroup()"
            >
              <ng-icon name="lucidePlus" aria-hidden="true" />
              {{ d.conditions.length ? 'Or when' : 'Add conditions' }}
            </button>
          }
        </fieldset>

        <fieldset class="m-0 flex min-w-0 flex-col gap-2 border-0 p-0">
          <legend class="mb-1 text-sm font-medium">How often</legend>
          <div class="grid min-w-0 gap-2 sm:grid-cols-2">
            @for (choice of repeatChoices; track choice.value) {
              <label
                class="has-checked:border-primary has-checked:bg-primary/10 has-focus-visible:ring-ring/50 hover:bg-muted/50 flex min-w-0 cursor-pointer items-start gap-2.5 rounded-lg border px-3 py-2.5 text-sm has-focus-visible:ring-3"
              >
                <input
                  type="radio"
                  name="trigger-repeat"
                  class="accent-primary mt-0.5 size-4 shrink-0"
                  [id]="'trigger-repeat-' + choice.value"
                  [checked]="d.repeat === choice.value"
                  (change)="patch({ repeat: choice.value })"
                />
                <span class="flex min-w-0 flex-col gap-0.5">
                  <span class="font-semibold">{{ choice.label }}</span>
                  <span class="text-muted-foreground text-xs">{{ choice.detail }}</span>
                </span>
              </label>
            }
          </div>
        </fieldset>
      }
    } @else if (d.source === 'SCHEDULE') {
      <div class="grid min-w-0 gap-4 sm:grid-cols-2">
        <div class="flex min-w-0 flex-col gap-1.5">
          <label hlmLabel for="trigger-cron">Schedule</label>
          <input
            hlmInput
            id="trigger-cron"
            class="font-mono"
            autocomplete="off"
            spellcheck="false"
            placeholder="0 9 1 * *"
            aria-describedby="trigger-cron-hint"
            [value]="d.schedule.cron"
            (input)="patchSchedule({ cron: $any($event.target).value })"
          />
          <p class="text-muted-foreground text-xs" id="trigger-cron-hint">
            Cron: minute hour day month weekday, e.g. <code class="font-mono">0 9 1 * *</code> is
            09:00 on the 1st. At most once every 15 minutes.
          </p>
        </div>
        <div class="flex min-w-0 flex-col gap-1.5">
          <label hlmLabel for="trigger-zone">Time zone</label>
          <input
            hlmInput
            id="trigger-zone"
            autocomplete="off"
            spellcheck="false"
            list="trigger-zones"
            [value]="d.schedule.zone"
            (input)="patchSchedule({ zone: $any($event.target).value })"
          />
          <datalist id="trigger-zones">
            @for (zone of zones; track zone) {
              <option [value]="zone"></option>
            }
          </datalist>
        </div>
      </div>
      @if (firings().length) {
        <div class="text-muted-foreground -mt-2 text-xs" data-testid="schedule-preview">
          <span>Next firings:</span>
          <ul class="mt-1 flex flex-col gap-0.5 font-mono tabular-nums">
            @for (f of firings(); track f) {
              <li>{{ f }}</li>
            }
          </ul>
        </div>
      }
      <fieldset class="m-0 flex min-w-0 flex-col gap-2 border-0 p-0">
        <legend class="mb-1 text-sm font-medium">While the last one is busy</legend>
        <div class="grid min-w-0 gap-2 sm:grid-cols-2">
          @for (choice of overlapChoices; track choice.value) {
            <label
              class="has-checked:border-primary has-checked:bg-primary/10 has-focus-visible:ring-ring/50 hover:bg-muted/50 flex min-w-0 cursor-pointer items-start gap-2.5 rounded-lg border px-3 py-2.5 text-sm has-focus-visible:ring-3"
            >
              <input
                type="radio"
                name="trigger-overlap"
                class="accent-primary mt-0.5 size-4 shrink-0"
                [id]="'trigger-overlap-' + choice.value"
                [checked]="d.schedule.overlap === choice.value"
                (change)="patchSchedule({ overlap: choice.value })"
              />
              <span class="flex min-w-0 flex-col gap-0.5">
                <span class="font-semibold">{{ choice.label }}</span>
                <span class="text-muted-foreground text-xs">{{ choice.detail }}</span>
              </span>
            </label>
          }
        </div>
      </fieldset>
      <fieldset class="m-0 flex min-w-0 flex-col gap-2 border-0 p-0">
        <legend class="mb-1 text-sm font-medium">Missed firings</legend>
        <div class="grid min-w-0 gap-2 sm:grid-cols-2">
          @for (choice of catchUpChoices; track choice.value) {
            <label
              class="has-checked:border-primary has-checked:bg-primary/10 has-focus-visible:ring-ring/50 hover:bg-muted/50 flex min-w-0 cursor-pointer items-start gap-2.5 rounded-lg border px-3 py-2.5 text-sm has-focus-visible:ring-3"
            >
              <input
                type="radio"
                name="trigger-catch-up"
                class="accent-primary mt-0.5 size-4 shrink-0"
                [id]="'trigger-catch-up-' + choice.value"
                [checked]="d.schedule.catchUp === choice.value"
                (change)="patchSchedule({ catchUp: choice.value })"
              />
              <span class="flex min-w-0 flex-col gap-0.5">
                <span class="font-semibold">{{ choice.label }}</span>
                <span class="text-muted-foreground text-xs">{{ choice.detail }}</span>
              </span>
            </label>
          }
        </div>
      </fieldset>
    }
  `,
})
export class TriggerWhenFields {
  readonly draft = model.required<TriggerDraft>();
  /** The company's systems; the trackers among them are offered. */
  readonly systems = input<readonly ExternalSystem[]>([]);
  /** A change: the source and its system are fixed. */
  readonly fixed = input(false);
  /** The trigger's author when someone else (an admin) edits it: the channels they see. */
  readonly author = input<string | null>(null);

  protected readonly sources = SOURCE_OPTIONS;
  /** The field opens a menu; on a phone, a sheet. */
  protected readonly menu = mediaQuery(MENU_SCREEN, true);
  protected readonly sheetOpen = signal(false);
  /** The closed select, drawn as the app's fields are; 44px tall on a phone. */
  protected readonly fieldClass =
    'border-input bg-card hover:bg-accent/40 focus-visible:border-ring focus-visible:ring-ring/50 aria-expanded:border-ring flex h-11 w-full min-w-0 items-center gap-2 rounded-lg border ps-3 pe-2.5 text-start text-base outline-none focus-visible:ring-3 sm:h-9 sm:max-w-sm sm:text-sm';
  protected readonly kindLabels = SYSTEM_KIND_LABELS;
  protected readonly eventLabels = EVENT_LABELS;
  protected readonly valueLabels = EVENT_VALUE_LABELS;
  protected readonly withValue = EVENTS_WITH_VALUE;
  protected readonly subjectLabels = SUBJECT_LABELS;
  protected readonly repeatChoices = REPEAT_CHOICES;
  protected readonly overlapChoices = OVERLAP_CHOICES;
  protected readonly catchUpChoices = CATCH_UP_CHOICES;
  protected readonly maxEvents = MAX_EVENTS;
  protected readonly maxGroups = MAX_CONDITION_GROUPS;
  protected readonly zones = timeZones();

  protected readonly noSystem = NO_SYSTEM;
  protected readonly webhookOps = ['EQUALS', 'IN', 'PRESENT', 'ABSENT'] as const;
  protected readonly opLabels = WEBHOOK_OP_LABELS;
  protected readonly maxWebhookConditions = MAX_WEBHOOK_CONDITIONS;

  /** The company systems the source may belong to. */
  protected readonly sourceSystems = computed(() => {
    const kinds = sourceKinds(this.draft().source);
    return this.systems().filter((s) => !s.personal && kinds.includes(s.kind));
  });
  protected readonly system = computed(() =>
    this.systems().find((s) => s.id === this.draft().systemId),
  );
  protected readonly events = computed(() => eventsFor(this.system()?.kind));
  protected readonly subjects = computed(() => subjectsFor(this.system()?.kind));
  protected readonly scope = computed(() => scopeHint(this.system()?.kind));
  protected readonly conditionRows = computed(() =>
    CONDITION_ROWS.filter((r) => !r.jiraOnly || isJira(this.system()?.kind)),
  );
  protected readonly firings = computed(() =>
    schedulePreview(this.draft().schedule.cron, this.draft().schedule.zone),
  );

  protected patch(change: Partial<TriggerDraft>): void {
    this.draft.update((d) => ({ ...d, ...change }));
  }

  protected patchSchedule(change: Partial<TriggerDraft['schedule']>): void {
    this.draft.update((d) => ({ ...d, schedule: { ...d.schedule, ...change } }));
  }

  protected sourceOf(source: TriggerSource): SourceOption {
    return SOURCE_OPTIONS.find((o) => o.value === source) ?? SOURCE_OPTIONS[0];
  }

  protected setSource(value: string): void {
    const source = (['TRACKER', 'CHANNEL', 'WEBHOOK', 'SCHEDULE'] as const).find(
      (v) => v === value,
    );
    if (!source || source === this.draft().source) return;
    // A system belongs to one kind of source: another source starts without one.
    this.draft.update((d) => ({ ...d, source, systemId: null, channels: [] }));
  }

  /** A kind picked in the phone's sheet: the sheet closes, and the focus goes back to the field. */
  protected pickFromSheet(value: TriggerSource): void {
    this.setSource(value);
    this.sheetOpen.set(false);
  }

  /** Up and down move along the sheet's kinds, Home and End to the ends. */
  protected onSheetKeys(event: KeyboardEvent): void {
    const group = (event.currentTarget as HTMLElement).closest('[role=radiogroup]');
    const options = [...(group?.querySelectorAll<HTMLElement>('[role=radio]') ?? [])];
    const i = options.indexOf(document.activeElement as HTMLElement);
    const to =
      event.key === 'ArrowDown' || event.key === 'ArrowRight'
        ? (i + 1) % options.length
        : event.key === 'ArrowUp' || event.key === 'ArrowLeft'
          ? (i - 1 + options.length) % options.length
          : event.key === 'Home'
            ? 0
            : event.key === 'End'
              ? options.length - 1
              : -1;
    if (to < 0 || !options.length) return;
    event.preventDefault();
    options[to].focus();
  }

  protected setWebhookCondition(i: number, change: Partial<DraftWebhookCondition>): void {
    this.draft.update((d) => ({
      ...d,
      webhookConditions: d.webhookConditions.map((c, j) => (j === i ? { ...c, ...change } : c)),
    }));
  }

  protected addWebhookCondition(): void {
    this.draft.update((d) => ({
      ...d,
      webhookConditions: [...d.webhookConditions, { pointer: '', op: 'EQUALS', values: '' }],
    }));
  }

  protected removeWebhookCondition(i: number): void {
    this.draft.update((d) => ({
      ...d,
      webhookConditions: d.webhookConditions.filter((_, j) => j !== i),
    }));
  }

  protected setSystem(id: string): void {
    const kind = this.systems().find((s) => s.id === id)?.kind;
    const allowed = eventsFor(kind);
    this.draft.update((d) => ({
      ...d,
      systemId: id || null,
      subject: subjectFor(kind, d.subject),
      events: d.events
        .filter((e) => allowed.includes(e.event))
        .concat(
          d.events.some((e) => allowed.includes(e.event)) ? [] : [{ event: allowed[0], value: '' }],
        ),
    }));
  }

  protected setEvent(i: number, event: TriggerEvent): void {
    this.draft.update((d) => ({
      ...d,
      events: d.events.map((e, j) => (j === i ? { event, value: e.value } : e)),
    }));
  }

  protected setEventValue(i: number, value: string): void {
    this.draft.update((d) => ({
      ...d,
      events: d.events.map((e, j) => (j === i ? { ...e, value } : e)),
    }));
  }

  protected addEvent(): void {
    const used = new Set(this.draft().events.map((e) => e.event));
    const next = this.events().find((e) => !used.has(e)) ?? this.events()[0];
    this.draft.update((d) => ({ ...d, events: [...d.events, { event: next, value: '' }] }));
  }

  protected removeEvent(i: number): void {
    this.draft.update((d) => ({ ...d, events: d.events.filter((_, j) => j !== i) }));
  }

  protected addGroup(): void {
    this.draft.update((d) => ({ ...d, conditions: [...d.conditions, emptyConditionGroup()] }));
  }

  protected removeGroup(i: number): void {
    this.draft.update((d) => ({ ...d, conditions: d.conditions.filter((_, j) => j !== i) }));
  }

  protected setCondition(i: number, field: ConditionField, value: string): void {
    this.draft.update((d) => ({
      ...d,
      conditions: d.conditions.map((g, j) => (j === i ? { ...g, [field]: value } : g)),
    }));
  }

  protected setAuthors(i: number, authors: DraftConditionGroup['authors']): void {
    this.draft.update((d) => ({
      ...d,
      conditions: d.conditions.map((g, j) => (j === i ? { ...g, authors } : g)),
    }));
  }
}
