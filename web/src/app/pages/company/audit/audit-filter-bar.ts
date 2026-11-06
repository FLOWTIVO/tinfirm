import { CdkTrapFocus } from '@angular/cdk/a11y';
import {
  CdkConnectedOverlay,
  CdkOverlayOrigin,
  type ConnectedPosition,
} from '@angular/cdk/overlay';
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  input,
  linkedSignal,
  output,
  signal,
  viewChild,
} from '@angular/core';
import { NgIcon, provideIcons } from '@ng-icons/core';
import { lucideCalendar, lucideSearch, lucideSlidersHorizontal, lucideX } from '@ng-icons/lucide';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmFieldImports } from '@spartan-ng/helm/field';
import { HlmInput } from '@spartan-ng/helm/input';
import { HlmNativeSelectImports } from '@spartan-ng/helm/native-select';
import { mediaQuery } from '../../../core/media-query';
import { shortId, targetNoun } from '../../../shared/audit-event-view';
import {
  ACTION_SUGGESTIONS,
  actorChoice,
  type AuditFilters,
  type AuditWhen,
  compactFilters,
  conversationFilter,
  conversationText,
  filterChips,
  filterErrors,
  type FilterChip,
  fromActorChoice,
  moreFilterCount,
  sameFilters,
  TARGET_TYPES,
  WHEN_OPTIONS,
} from './audit-filters';
import { DayPicker } from './day-picker';
import { dayKey, dayRangePill, shortDay } from './days';

export interface AuditPerson {
  readonly userId: string;
  readonly displayName: string;
  readonly principalId?: string | null;
}

export interface AuditPrincipal {
  readonly id: string;
  readonly name: string;
}

/** The text fields, which apply on Enter or when left; the conversation takes a key or an id. */
type TextField = 'action' | 'targetId' | 'conversation';

/** Under the Custom pill, or above it where there is no room below. */
const POPOVER_POSITIONS: ConnectedPosition[] = [
  { originX: 'start', originY: 'bottom', overlayX: 'start', overlayY: 'top', offsetY: 8 },
  { originX: 'end', originY: 'bottom', overlayX: 'end', overlayY: 'top', offsetY: 8 },
  { originX: 'start', originY: 'top', overlayX: 'start', overlayY: 'bottom', offsetY: -8 },
];

/**
 * The audit log's filters: an action search with the prefix hint, who acted, the
 * time as pills (the last hour, today, seven days, or days picked on a small calendar), and under
 * "More filters" the target, the conversation (by its key) and whom the events are about; the
 * filters in use as removable chips. Each change hands the filters over at once; the page keeps
 * them in its URL. On a phone the pills scroll sideways.
 */
@Component({
  selector: 'app-audit-filter-bar',
  imports: [
    CdkConnectedOverlay,
    CdkOverlayOrigin,
    CdkTrapFocus,
    NgIcon,
    HlmButton,
    HlmFieldImports,
    HlmInput,
    HlmNativeSelectImports,
    DayPicker,
  ],
  providers: [provideIcons({ lucideCalendar, lucideSearch, lucideSlidersHorizontal, lucideX })],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'block', 'data-testid': 'audit-filters' },
  template: `
    <form
      role="search"
      aria-label="Filter the events"
      novalidate
      (submit)="$event.preventDefault(); commitText()"
    >
      <div
        class="flex flex-col gap-2.5 px-4 pt-3.5 pb-3 sm:flex-row sm:flex-wrap sm:items-start sm:gap-3"
      >
        <div class="min-w-0 sm:max-w-md sm:min-w-56 sm:flex-[1_1_20rem]">
          <label for="audit-action" class="sr-only">Action</label>
          <div
            class="border-input bg-card text-muted-foreground focus-within:border-ring focus-within:ring-ring/50 has-aria-invalid:border-destructive flex h-11 items-center gap-2 rounded-[10px] border px-3 focus-within:ring-3 sm:h-9"
          >
            <ng-icon name="lucideSearch" class="shrink-0" aria-hidden="true" />
            <input
              id="audit-action"
              type="search"
              class="text-foreground placeholder:text-faint h-full min-w-0 flex-1 border-0 bg-transparent font-mono text-base outline-none placeholder:font-sans sm:text-[0.8125rem]"
              list="audit-action-suggestions"
              autocomplete="off"
              spellcheck="false"
              [placeholder]="wide() ? 'Search actions, such as run.started' : 'Search actions'"
              [value]="text().action"
              [attr.aria-invalid]="errors().action ? 'true' : null"
              [attr.aria-describedby]="errors().action ? 'audit-action-error' : 'audit-action-hint'"
              (input)="type('action', $event)"
              (change)="commitText()"
            />
          </div>
          <datalist id="audit-action-suggestions">
            @for (s of actionSuggestions; track s.value) {
              <option [value]="s.value">{{ s.label }}</option>
            }
          </datalist>
          @if (errors().action; as error) {
            <p id="audit-action-error" class="text-destructive mt-1.5 text-xs" role="alert">
              {{ error }}
            </p>
          } @else {
            <p id="audit-action-hint" class="text-faint mt-1.5 ms-0.5 text-xs">
              <span class="max-sm:hidden">An action ending in a dot is a prefix: </span>
              <code class="text-muted-foreground font-mono">tool.</code> finds every tool call.
            </p>
          }
        </div>

        <div
          class="-mx-4 flex min-w-0 gap-1.5 overflow-x-auto px-4 pb-1 [scrollbar-width:none] sm:mx-0 sm:flex-wrap sm:gap-3 sm:overflow-visible sm:p-0 [&::-webkit-scrollbar]:hidden"
          data-testid="filter-pills"
        >
          <div [class]="pill">
            <label class="text-muted-foreground shrink-0" for="audit-actor">Who</label>
            <hlm-native-select
              selectId="audit-actor"
              [selectClass]="pillSelect"
              [value]="actor()"
              (valueChange)="setActor($event)"
            >
              <option value="" [selected]="actor() === ''">Anyone</option>
              <option value="USER" [selected]="actor() === 'USER'">Any person</option>
              <option value="SYSTEM" [selected]="actor() === 'SYSTEM'">Tinfirm itself</option>
              <option value="AGENT" [selected]="actor() === 'AGENT'">An agent</option>
              @if (people().length || unknownPerson()) {
                <optgroup label="A member">
                  @for (person of people(); track person.userId) {
                    <option
                      [value]="'USER:' + person.userId"
                      [selected]="actor() === 'USER:' + person.userId"
                    >
                      {{ person.displayName }}
                    </option>
                  }
                  @if (unknownPerson(); as id) {
                    <option [value]="'USER:' + id" selected>A person · {{ short(id) }}</option>
                  }
                </optgroup>
              }
            </hlm-native-select>
          </div>

          <div
            role="group"
            aria-label="When"
            class="flex shrink-0 gap-1.5 sm:gap-0.5 sm:rounded-full sm:border sm:bg-muted sm:p-[3px]"
            data-testid="when"
          >
            @for (w of whenOptions; track w.value) {
              <button
                type="button"
                [class]="timePill"
                [attr.aria-pressed]="filters().when === w.value"
                [attr.data-value]="w.value"
                (click)="setWhen(w.value)"
              >
                {{ w.label }}
              </button>
            }
            <button
              type="button"
              cdkOverlayOrigin
              #customOrigin="cdkOverlayOrigin"
              [class]="timePill"
              aria-haspopup="dialog"
              data-testid="custom-days"
              [attr.aria-expanded]="pickerOpen()"
              [attr.aria-pressed]="custom()"
              (click)="pickerOpen() ? closePicker() : openPicker()"
            >
              <ng-icon name="lucideCalendar" size="13" aria-hidden="true" />
              {{ customLabel() }}
            </button>
          </div>

          <button
            hlmBtn
            variant="outline"
            type="button"
            class="h-11 shrink-0 text-[0.8125rem] sm:h-9"
            aria-controls="audit-more-filters"
            data-testid="toggle-more-filters"
            [attr.aria-expanded]="more()"
            (click)="more.set(!more())"
          >
            <ng-icon
              name="lucideSlidersHorizontal"
              class="text-muted-foreground"
              aria-hidden="true"
            />
            More filters
            @if (moreCount()) {
              <span
                class="bg-primary/10 text-primary inline-flex h-4.5 min-w-4.5 items-center justify-center rounded-full px-1.5 font-mono text-[0.6875rem] font-medium"
                data-testid="more-filter-count"
                >{{ moreCount() }}</span
              >
            }
          </button>
        </div>
      </div>

      @if (more()) {
        <div
          id="audit-more-filters"
          class="bg-muted/60 grid grid-cols-1 gap-x-4 gap-y-3 border-t px-4 pt-3.5 pb-4 sm:grid-cols-2 lg:grid-cols-4"
        >
          <div hlmField>
            <label hlmFieldLabel for="audit-target-type">Target</label>
            <hlm-native-select
              class="w-full"
              selectId="audit-target-type"
              selectClass="max-sm:h-11 max-sm:text-base"
              [value]="filters().targetType ?? ''"
              (valueChange)="setTargetType($event)"
            >
              <option value="" [selected]="!filters().targetType">Any target</option>
              @for (t of targetTypes(); track t.value) {
                <option [value]="t.value" [selected]="filters().targetType === t.value">
                  {{ t.label }}
                </option>
              }
            </hlm-native-select>
          </div>
          <div hlmField>
            <label hlmFieldLabel for="audit-target-id">Target id</label>
            <input
              hlmInput
              id="audit-target-id"
              class="font-mono placeholder:font-sans max-sm:h-11 max-sm:text-base"
              autocomplete="off"
              spellcheck="false"
              placeholder="Its id"
              [value]="text().targetId"
              [attr.aria-invalid]="errors().targetId ? 'true' : null"
              aria-describedby="audit-target-id-error"
              (input)="type('targetId', $event)"
              (change)="commitText()"
            />
            <p
              id="audit-target-id-error"
              class="text-destructive text-xs empty:hidden"
              role="alert"
            >
              {{ errors().targetId }}
            </p>
          </div>
          <div hlmField>
            <label hlmFieldLabel for="audit-conversation">Conversation</label>
            <input
              hlmInput
              id="audit-conversation"
              class="font-mono placeholder:font-sans max-sm:h-11 max-sm:text-base"
              autocomplete="off"
              spellcheck="false"
              placeholder="Its key, such as shop-123-dev-1"
              [value]="text().conversation"
              [attr.aria-invalid]="errors().conversation ? 'true' : null"
              aria-describedby="audit-conversation-help audit-conversation-error"
              (input)="type('conversation', $event)"
              (change)="commitText()"
            />
            @if (errors().conversation; as error) {
              <p id="audit-conversation-error" class="text-destructive text-xs" role="alert">
                {{ error }}
              </p>
            } @else {
              <p hlmFieldDescription id="audit-conversation-help">
                The key it shows, or the id in its address.
              </p>
            }
          </div>
          <div hlmField>
            <label hlmFieldLabel for="audit-principal">About</label>
            <hlm-native-select
              class="w-full"
              selectId="audit-principal"
              selectClass="max-sm:h-11 max-sm:text-base"
              [value]="filters().principalId ?? ''"
              (valueChange)="setPrincipal($event)"
            >
              <option value="" [selected]="!filters().principalId">Anyone</option>
              @if (memberPrincipals().length) {
                <optgroup label="Members">
                  @for (m of memberPrincipals(); track m.id) {
                    <option [value]="m.id" [selected]="filters().principalId === m.id">
                      {{ m.name }}
                    </option>
                  }
                </optgroup>
              }
              @if (principals().length) {
                <optgroup label="Service accounts">
                  @for (p of principals(); track p.id) {
                    <option [value]="p.id" [selected]="filters().principalId === p.id">
                      {{ p.name }}
                    </option>
                  }
                </optgroup>
              }
              @if (unknownPrincipal(); as id) {
                <option [value]="id" selected>{{ names().get(id) ?? short(id) }}</option>
              }
            </hlm-native-select>
            <p hlmFieldDescription id="audit-principal-help">
              What they did, and what was done to or for them.
            </p>
          </div>
        </div>
      }

      @if (chips().length) {
        <div
          class="flex flex-wrap items-center gap-2 px-4 pb-3"
          [class.pt-3]="more()"
          role="group"
          aria-label="Filters in use"
        >
          @for (chip of chips(); track chip.key) {
            <span
              class="bg-primary/10 text-primary inline-flex h-7 max-w-full min-w-0 items-center gap-1.5 rounded-full ps-3 pe-0.5 text-[0.8125rem] font-semibold"
              data-testid="filter-chip"
              [attr.data-key]="chip.key"
            >
              <span class="truncate"
                >{{ chip.label }}
                @if (chip.value) {
                  <span class="font-mono font-medium"> {{ chip.value }}</span>
                }
              </span>
              <button
                type="button"
                class="hover:bg-primary/15 focus-visible:ring-ring/50 relative inline-flex size-6 shrink-0 items-center justify-center rounded-full outline-none focus-visible:ring-3 max-sm:after:absolute max-sm:after:-inset-2.5"
                [attr.aria-label]="chip.aria"
                (click)="remove(chip)"
              >
                <ng-icon name="lucideX" size="13" aria-hidden="true" />
              </button>
            </span>
          }
          <button
            hlmBtn
            variant="ghost"
            size="xs"
            type="button"
            class="text-muted-foreground max-sm:h-11 max-sm:px-3"
            data-testid="clear-filters"
            (click)="clear()"
          >
            Clear
          </button>
        </div>
      }
    </form>

    <ng-template
      cdkConnectedOverlay
      [cdkConnectedOverlayOrigin]="customOrigin"
      [cdkConnectedOverlayOpen]="pickerOpen()"
      [cdkConnectedOverlayPositions]="positions"
      [cdkConnectedOverlayViewportMargin]="16"
      [cdkConnectedOverlayPush]="true"
      (attach)="picker()?.focusDay()"
      (detach)="closePicker()"
      (overlayOutsideClick)="outsideClick($event)"
    >
      <div
        role="dialog"
        aria-label="Pick the days"
        cdkTrapFocus
        class="bg-popover text-popover-foreground w-[min(22rem,calc(100vw-2rem))] rounded-[14px] border p-3.5 shadow-[0_12px_32px_-8px_rgb(0_0_0/0.18)] sm:w-76"
        data-testid="day-range"
      >
        <app-day-picker
          mode="range"
          [max]="today"
          [(from)]="draftFrom"
          [(to)]="draftTo"
          [(picking)]="picking"
        />
        <p class="text-muted-foreground mt-2.5 text-[0.8125rem]" role="status">
          {{ pickSummary() }}
        </p>
        <div class="mt-3 flex justify-end gap-2">
          <button
            hlmBtn
            variant="ghost"
            size="sm"
            type="button"
            class="max-sm:h-11"
            (click)="closePicker()"
          >
            Cancel
          </button>
          <button
            hlmBtn
            size="sm"
            type="button"
            class="max-sm:h-11"
            data-testid="apply-days"
            [disabled]="!draftFrom() || picking()"
            (click)="applyDays()"
          >
            Apply
          </button>
        </div>
      </div>
    </ng-template>
  `,
})
export class AuditFilterBar {
  readonly filters = input<AuditFilters>({});
  readonly people = input<readonly AuditPerson[]>([]);
  readonly principals = input<readonly AuditPrincipal[]>([]);
  /** Names by id, for the chips: people, principals and the targets the events named. */
  readonly names = input<ReadonlyMap<string, string>>(new Map());
  readonly apply = output<AuditFilters>();

  protected readonly actionSuggestions = ACTION_SUGGESTIONS;
  protected readonly whenOptions = WHEN_OPTIONS;
  protected readonly positions = POPOVER_POSITIONS;
  protected readonly today = dayKey(new Date());
  /** From `sm` up: room for the longer placeholder. */
  protected readonly wide = mediaQuery('(min-width: 40rem)', true);

  /** A filter pill: its name, then a borderless select, in one pill. */
  protected readonly pill =
    'border-input bg-card hover:bg-accent/40 has-[select:focus-visible]:border-ring has-[select:focus-visible]:ring-ring/50 flex h-11 max-w-full min-w-0 shrink-0 items-center rounded-full border ps-3.5 text-[0.8125rem] transition-colors has-[select:focus-visible]:ring-3 sm:h-9';
  protected readonly pillSelect =
    'field-sizing-content h-10 max-w-48 rounded-full border-0 bg-transparent py-0 ps-1.5 pe-7 text-base font-semibold hover:bg-transparent focus-visible:ring-0 sm:h-8 sm:text-[0.8125rem]';
  protected readonly timePill =
    'text-muted-foreground hover:text-foreground focus-visible:ring-ring/50 aria-pressed:bg-card aria-pressed:text-foreground aria-pressed:shadow-[0_1px_3px_rgb(0_0_0/0.08)] inline-flex h-11 shrink-0 items-center gap-1.5 rounded-full border px-4 text-sm font-semibold whitespace-nowrap outline-none transition-colors focus-visible:ring-3 sm:h-7 sm:border-0 sm:px-3 sm:text-[0.8125rem]';

  protected readonly actor = computed(() => actorChoice(this.filters()));
  /** A person in the URL who is not a member any more. */
  protected readonly unknownPerson = computed(() => {
    const { actorType, actorId } = this.filters();
    return actorType === 'USER' && actorId && !this.people().some((p) => p.userId === actorId)
      ? actorId
      : null;
  });
  protected readonly memberPrincipals = computed(() =>
    this.people()
      .filter((p) => !!p.principalId)
      .map((p) => ({ id: p.principalId!, name: p.displayName })),
  );
  protected readonly unknownPrincipal = computed(() => {
    const id = this.filters().principalId;
    return id &&
      !this.memberPrincipals().some((m) => m.id === id) &&
      !this.principals().some((p) => p.id === id)
      ? id
      : null;
  });
  protected readonly targetTypes = computed(() => {
    const current = this.filters().targetType;
    const types =
      current && !TARGET_TYPES.includes(current) ? [...TARGET_TYPES, current] : TARGET_TYPES;
    return types
      .map((value) => ({ value, label: targetNoun(value) }))
      .sort((a, b) => a.label.localeCompare(b.label));
  });

  protected readonly chips = computed(() => filterChips(this.filters(), this.names()));
  protected readonly moreCount = computed(() => moreFilterCount(this.filters()));
  /** Open while one of its filters is set, until the reader folds it. */
  protected readonly more = linkedSignal(() => this.moreCount() > 0);

  /** The text fields as typed, until they apply. */
  protected readonly text = linkedSignal<Record<TextField, string>>(() => ({
    action: this.filters().action ?? '',
    targetId: this.filters().targetId ?? '',
    conversation: conversationText(this.filters()),
  }));
  /** Why a typed value cannot apply, shown once the reader tried. */
  protected readonly errors = linkedSignal<Partial<Record<TextField, string>>>(() => {
    this.filters();
    return {};
  });

  protected type(field: TextField, event: Event): void {
    const value = (event.target as HTMLInputElement).value;
    this.text.update((t) => ({ ...t, [field]: value }));
  }

  /** Applies the text fields, unless one holds what cannot be a filter. */
  protected commitText(): void {
    const { action, targetId, conversation } = this.text();
    const next = compactFilters({
      ...this.filters(),
      action,
      targetId,
      ...conversationFilter(conversation),
    });
    const all = filterErrors(next);
    const errors: Partial<Record<TextField, string>> = {};
    if (all.action) errors.action = all.action;
    if (all.targetId) errors.targetId = all.targetId;
    const conversationError = all.conversationKey ?? all.conversationId;
    if (conversationError) errors.conversation = conversationError;
    this.errors.set(errors);
    if (Object.keys(errors).length) return;
    this.emit(next);
  }

  private emit(next: AuditFilters): void {
    const filters = compactFilters(next);
    if (!sameFilters(filters, this.filters())) this.apply.emit(filters);
  }

  protected setActor(value: string | null | undefined): void {
    this.emit({ ...this.filters(), ...fromActorChoice(value ?? '') });
  }

  protected setWhen(when: AuditWhen): void {
    const same = this.filters().when === when;
    this.emit({ ...this.filters(), when: same ? undefined : when, from: undefined, to: undefined });
  }

  protected setTargetType(value: string | null | undefined): void {
    this.emit({ ...this.filters(), targetType: value || undefined });
  }

  protected setPrincipal(value: string | null | undefined): void {
    this.emit({ ...this.filters(), principalId: value || undefined });
  }

  protected remove(chip: FilterChip): void {
    this.emit({ ...this.filters(), ...chip.clears });
  }

  protected clear(): void {
    this.text.set({ action: '', targetId: '', conversation: '' });
    this.errors.set({});
    this.emit({});
  }

  protected short(id: string): string {
    return shortId(id);
  }

  // The days, picked on the calendar under "Custom".
  protected readonly custom = computed(() => !!(this.filters().from || this.filters().to));
  protected readonly customLabel = computed(() =>
    this.custom() ? dayRangePill(this.filters().from, this.filters().to) : 'Custom',
  );
  protected readonly pickerOpen = signal(false);
  protected readonly draftFrom = signal<string | null>(null);
  protected readonly draftTo = signal<string | null>(null);
  protected readonly picking = signal(false);
  protected readonly picker = viewChild(DayPicker);
  private readonly origin = viewChild.required<CdkOverlayOrigin>('customOrigin');

  protected readonly pickSummary = computed(() => {
    const from = this.draftFrom();
    const to = this.draftTo();
    if (!from) return 'Pick the first day.';
    if (this.picking()) return `From ${shortDay(from)}. Pick the last day.`;
    if (!to || from === to) return `${shortDay(from)} only.`;
    return `${shortDay(from)} to ${shortDay(to)}, both days included.`;
  });

  protected openPicker(): void {
    this.draftFrom.set(this.filters().from ?? this.filters().to ?? null);
    this.draftTo.set(this.filters().to ?? this.filters().from ?? null);
    this.picking.set(false);
    this.pickerOpen.set(true);
  }

  /** Closes the calendar, the focus back on the Custom pill unless the reader clicked elsewhere. */
  protected closePicker(refocus = true): void {
    if (!this.pickerOpen()) return;
    this.pickerOpen.set(false);
    if (refocus) this.origin().elementRef.nativeElement.focus();
  }

  protected outsideClick(event: MouseEvent): void {
    // The Custom pill toggles the calendar itself.
    const origin = this.origin().elementRef.nativeElement as HTMLElement;
    if (!origin.contains(event.target as Node)) this.closePicker(false);
  }

  protected applyDays(): void {
    const from = this.draftFrom();
    if (!from || this.picking()) return;
    const to = this.draftTo() ?? from;
    this.emit({ ...this.filters(), when: undefined, from, to });
    this.closePicker();
  }
}
