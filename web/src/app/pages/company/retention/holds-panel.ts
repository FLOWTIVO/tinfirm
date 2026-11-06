import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import { RouterLink } from '@angular/router';
import { NgIcon, provideIcons } from '@ng-icons/core';
import {
  lucideCircleAlert,
  lucideEyeOff,
  lucideLock,
  lucideLockOpen,
  lucideMessageSquare,
  lucideUser,
} from '@ng-icons/lucide';
import { HlmAlertImports } from '@spartan-ng/helm/alert';
import { HlmAlertDialogImports } from '@spartan-ng/helm/alert-dialog';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmFieldImports } from '@spartan-ng/helm/field';
import { HlmInput } from '@spartan-ng/helm/input';
import { HlmNativeSelectImports } from '@spartan-ng/helm/native-select';
import { HlmSpinner } from '@spartan-ng/helm/spinner';
import { Api } from '../../../api/generated/api';
import { createHold, findHoldConversation, liftHold } from '../../../api/generated/functions';
import type { LegalHold } from '../../../api/generated/models';
import { problemMessage } from '../../../api/problem';
import { CurrentCompany } from '../../../core/current-company';
import { Notify } from '../../../core/notify';
import { conversationIdOf } from '../../../shared/governance-labels';
import { EditionBadge } from '../../../shared/edition-badge';
import { ReasonDialog } from '../../../shared/reason-dialog';
import { Segmented, type SegmentedOption } from '../../../shared/segmented';
import { formatDay } from '../../../shared/time';
import type { PersonChoice } from './person-choice';

/** What a hold's line says it holds. */
export function holdTarget(hold: LegalHold): string {
  if (hold.principalId) return hold.principalName ?? 'A person';
  return hold.conversationKey ?? 'A conversation';
}

/** What a hold keeps, by its kind (open point 22). */
export function holdCovers(hold: Pick<LegalHold, 'principalId'>): string {
  return hold.principalId
    ? 'Holds their personal conversations, and what they said, added and decided in triggers’ conversations.'
    : 'Holds all of it, its transcripts included.';
}

/** "Set by Olga Owner · since Oct 2, 2026", the setter left out when not a member now. */
export function holdSetPhrase(hold: LegalHold, names: ReadonlyMap<string, string>): string {
  const by = hold.setByUserId ? names.get(hold.setByUserId) : undefined;
  const since = `since ${formatDay(hold.setAt)}`;
  return by ? `Set by ${by} · ${since}` : `Set ${since}`;
}

/** "AB" for "Alice Bauer", the tile of a person's hold. */
function initialsOf(name: string): string {
  return name
    .split(/\s+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((w) => w[0]!.toUpperCase())
    .join('');
}

/**
 * Legal holds (the package 6 contract, §5.9, open point 22): the Owner holds a person (their personal
 * conversations and what they said, added and decided in triggers' conversations) or a conversation
 * (all of it, its transcripts included) against retention and erasure, with a reason that stays on
 * the hold, and lifts it again. Each hold says who set it and since when. Nobody is told, and the
 * page says so where a hold is set.
 */
@Component({
  selector: 'app-holds-panel',
  imports: [
    NgIcon,
    RouterLink,
    HlmAlertImports,
    HlmAlertDialogImports,
    HlmButton,
    HlmFieldImports,
    HlmInput,
    HlmNativeSelectImports,
    HlmSpinner,
    EditionBadge,
    ReasonDialog,
    Segmented,
  ],
  providers: [
    provideIcons({
      lucideCircleAlert,
      lucideEyeOff,
      lucideLock,
      lucideLockOpen,
      lucideMessageSquare,
      lucideUser,
    }),
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="bg-card rounded-xl border" aria-labelledby="holds-title" data-testid="holds">
      <div class="flex flex-col gap-3 px-4 pt-4 pb-3.5 sm:flex-row sm:items-start sm:gap-4 sm:px-5">
        <div class="min-w-0 flex-1">
          <h2 class="text-base font-bold" id="holds-title">Legal holds</h2>
          <p class="text-muted-foreground mt-0.5 text-sm text-pretty">
            What a hold covers is neither removed by retention nor erased until it is lifted. The
            person is not told.
          </p>
        </div>
        @if (canSet()) {
          <button
            hlmBtn
            variant="outline"
            size="sm"
            type="button"
            class="self-start max-sm:h-11"
            data-testid="set-hold"
            (click)="openSet()"
          >
            <ng-icon name="lucideLock" aria-hidden="true" />
            Set a hold
          </button>
        } @else {
          <app-edition-badge class="self-start" feature="LEGAL_HOLDS" />
        }
      </div>
      @if (!canSet()) {
        <p
          class="text-muted-foreground m-0 border-t px-4 py-3 text-[0.8125rem] text-pretty sm:px-5"
          data-testid="holds-edition"
        >
          Setting a hold needs an Enterprise licence. The holds already set stay in force, and can
          be lifted.
        </p>
      }

      @if (rows().length === 0) {
        <p
          class="text-muted-foreground m-0 border-t px-4 py-4 text-sm sm:px-5"
          data-testid="no-holds"
        >
          No hold is set. Retention and erasure apply to everything.
        </p>
      } @else {
        <ul class="m-0 flex list-none flex-col p-0" aria-label="Open holds">
          @for (row of rows(); track row.hold.id) {
            <li
              class="flex items-start gap-3 border-t px-4 py-4 sm:px-5"
              [attr.data-hold]="row.target"
            >
              <span
                class="bg-secondary text-foreground flex size-8 shrink-0 items-center justify-center rounded-[10px] text-xs font-bold"
                aria-hidden="true"
              >
                @if (row.person) {
                  {{ row.initials }}
                } @else {
                  <ng-icon name="lucideMessageSquare" size="16" />
                }
              </span>
              <div class="min-w-0 flex-1 text-sm">
                <p class="m-0">
                  @if (row.hold.conversationId) {
                    <a
                      class="font-mono font-semibold underline-offset-4 hover:underline"
                      [routerLink]="['/c', companyId(), 'conversations', row.hold.conversationId]"
                      >{{ row.target }}</a
                    >
                  } @else {
                    <span class="font-semibold">{{ row.target }}</span>
                  }
                  <span class="text-muted-foreground">
                    · {{ row.person ? 'Person' : 'Conversation' }}</span
                  >
                </p>
                <p class="m-0 mt-0.5 text-pretty break-words">“{{ row.hold.reason }}”</p>
                <p
                  class="text-muted-foreground m-0 mt-1 text-xs text-pretty"
                  data-testid="hold-set"
                >
                  {{ row.set }} · {{ row.covers }}
                </p>
              </div>
              <button
                hlmBtn
                variant="ghost"
                size="sm"
                type="button"
                class="shrink-0 max-sm:h-11"
                [attr.aria-label]="'Lift the hold on ' + row.target"
                (click)="askLift(row.hold)"
              >
                <ng-icon name="lucideLockOpen" aria-hidden="true" />
                Lift
              </button>
            </li>
          }
        </ul>
      }

      <div class="grid gap-3 border-t px-4 py-4 sm:grid-cols-2 sm:px-5">
        <p
          class="text-muted-foreground m-0 flex items-start gap-2.5 text-[0.8125rem] leading-normal text-pretty"
        >
          <ng-icon name="lucideUser" size="16" class="mt-0.5 shrink-0" aria-hidden="true" />
          <span
            ><span class="text-foreground font-semibold">A person’s hold</span> keeps their personal
            conversations, and what they said, added and decided in triggers’ conversations.</span
          >
        </p>
        <p
          class="text-muted-foreground m-0 flex items-start gap-2.5 text-[0.8125rem] leading-normal text-pretty"
        >
          <ng-icon
            name="lucideMessageSquare"
            size="16"
            class="mt-0.5 shrink-0"
            aria-hidden="true"
          />
          <span
            ><span class="text-foreground font-semibold">A conversation’s hold</span> keeps all of
            it, its transcripts included, whoever spoke in it.</span
          >
        </p>
      </div>
      <div
        class="bg-muted/50 flex items-start gap-2.5 rounded-b-xl border-t px-4 py-3 sm:px-5"
        data-testid="holds-untold"
      >
        <ng-icon
          name="lucideEyeOff"
          size="16"
          class="text-muted-foreground mt-0.5 shrink-0"
          aria-hidden="true"
        />
        <p class="text-muted-foreground m-0 text-[0.8125rem] leading-normal text-pretty">
          Nobody is told about a hold: their conversations go on as before. A hold brings back
          nothing already removed. An empty period, “Keep”, holds a kind for the whole company.
        </p>
      </div>
    </section>

    <app-reason-dialog
      testId="hold-dialog"
      heading="Set a legal hold"
      description="Retention skips what it covers and erasure is refused, until the hold is lifted. A hold brings back nothing already removed."
      confirmLabel="Set the hold"
      note="Kept on the hold, never in the audit log."
      [destructive]="false"
      [open]="setOpen()"
      [busy]="setBusy()"
      [error]="setError()"
      (confirm)="set($event)"
      (closed)="setOpen.set(false)"
    >
      <div class="flex flex-col gap-1.5">
        <span class="text-sm font-semibold" id="hold-kind-label">What to hold</span>
        <app-segmented
          inputId="hold-kind"
          labelledBy="hold-kind-label"
          [options]="kinds"
          [value]="kind()"
          (valueChange)="kind.set($event === 'conversation' ? 'conversation' : 'person')"
        />
      </div>
      @if (kind() === 'conversation') {
        <div hlmField>
          <label hlmFieldLabel for="hold-conversation">The conversation’s key</label>
          <input
            hlmInput
            id="hold-conversation"
            class="font-mono"
            autocomplete="off"
            spellcheck="false"
            placeholder="SHOP-123-1"
            aria-describedby="hold-conversation-help"
            [value]="conversationText()"
            (input)="conversationText.set($any($event.target).value)"
          />
          <p hlmFieldDescription id="hold-conversation-help">
            As its page shows it; its link works too.
          </p>
        </div>
      } @else {
        <div hlmField>
          <label hlmFieldLabel for="hold-person">Person</label>
          <hlm-native-select
            class="w-full"
            selectId="hold-person"
            [value]="person()"
            (valueChange)="person.set($event ?? '')"
          >
            @for (p of people(); track p.value) {
              <option [value]="p.value">{{ p.label }}</option>
            }
          </hlm-native-select>
        </div>
      }
      <p
        class="bg-muted text-muted-foreground m-0 rounded-lg px-3 py-2.5 text-[0.8125rem] leading-normal"
      >
        {{ setCovers() }}
      </p>
      <p
        class="m-0 flex items-start gap-2 text-[0.8125rem] leading-normal"
        data-testid="hold-untold"
      >
        <ng-icon
          name="lucideEyeOff"
          size="16"
          class="text-muted-foreground mt-0.5 shrink-0"
          aria-hidden="true"
        />
        <span>{{ setUntold() }}</span>
      </p>
    </app-reason-dialog>

    <hlm-alert-dialog [state]="liftOpen() ? 'open' : 'closed'" (closed)="liftOpen.set(false)">
      <hlm-alert-dialog-content *hlmAlertDialogPortal="let ctx" data-testid="lift-hold-dialog">
        <hlm-alert-dialog-header>
          <h2 hlmAlertDialogTitle>Lift the hold on {{ lifting() ? target(lifting()!) : '' }}?</h2>
          <p hlmAlertDialogDescription>
            What it held is removed by retention and may be erased again, as anything else. Nobody
            is told.
          </p>
        </hlm-alert-dialog-header>
        @if (liftError(); as message) {
          <div hlmAlert variant="destructive" role="alert">
            <ng-icon name="lucideCircleAlert" aria-hidden="true" />
            <p hlmAlertDescription>{{ message }}</p>
          </div>
        }
        <hlm-alert-dialog-footer>
          <button hlmAlertDialogCancel [disabled]="liftBusy()">Cancel</button>
          <button
            hlmAlertDialogAction
            variant="destructive"
            data-testid="confirm-lift-hold"
            [disabled]="liftBusy()"
            (click)="lift()"
          >
            @if (liftBusy()) {
              <hlm-spinner aria-hidden="true" />
            }
            Lift the hold
          </button>
        </hlm-alert-dialog-footer>
      </hlm-alert-dialog-content>
    </hlm-alert-dialog>
  `,
})
export class HoldsPanel {
  private readonly api = inject(Api);
  private readonly notify = inject(Notify);
  private readonly current = inject(CurrentCompany);

  readonly holds = input.required<readonly LegalHold[]>();
  readonly people = input.required<readonly PersonChoice[]>();
  /** Members' names by their user id: who set each hold. */
  readonly names = input<ReadonlyMap<string, string>>(new Map());
  /** A hold was set or lifted: the page reads the holds again. */
  /** Whether a new hold may be set: Enterprise (ADR-0077); open holds are lifted in any edition. */
  readonly canSet = input(true);
  readonly changed = output<void>();

  protected readonly companyId = this.current.id;
  protected readonly target = holdTarget;
  protected readonly kinds: readonly SegmentedOption[] = [
    { value: 'person', label: 'A person' },
    { value: 'conversation', label: 'A conversation' },
  ];

  protected readonly rows = computed(() =>
    this.holds().map((hold) => {
      const target = holdTarget(hold);
      return {
        hold,
        target,
        person: !!hold.principalId,
        initials: initialsOf(target),
        set: holdSetPhrase(hold, this.names()),
        covers: holdCovers(hold),
      };
    }),
  );

  protected readonly setOpen = signal(false);
  protected readonly setBusy = signal(false);
  protected readonly setError = signal<string | null>(null);
  protected readonly kind = signal<'conversation' | 'person'>('person');
  protected readonly conversationText = signal('');
  protected readonly person = signal('');
  private readonly conversationId = computed(() => conversationIdOf(this.conversationText()));
  private readonly personName = computed(
    () => this.people().find((p) => p.value === this.person())?.name ?? 'The person',
  );
  protected readonly setCovers = computed(() =>
    this.kind() === 'person'
      ? `Covers ${this.personName()}’s personal conversations, and what they said, added and decided in triggers’ conversations.`
      : 'Covers all of the conversation, its transcripts included.',
  );
  protected readonly setUntold = computed(() =>
    this.kind() === 'person'
      ? `${this.personName()} is not told. Their conversations go on as before.`
      : 'Nobody in the conversation is told. It goes on as before.',
  );

  /** Opens the dialog; a person given (the erasure's choice, a checklist's) is preselected. */
  openSet(person?: string): void {
    this.setError.set(null);
    this.conversationText.set('');
    this.kind.set('person');
    this.person.set(person ?? this.people()[0]?.value ?? '');
    this.setOpen.set(true);
  }

  protected async set(reason: string): Promise<void> {
    const companyId = this.current.id();
    if (!companyId || this.setBusy()) return;
    this.setError.set(null);
    let body: { reason: string; conversationId?: string; principalId?: string };
    this.setBusy.set(true);
    try {
      if (this.kind() === 'conversation') {
        const text = this.conversationText().trim();
        if (!text) {
          this.setError.set('Enter the conversation’s key, as its page shows it.');
          return;
        }
        // A link or an id names the conversation already; a key is looked up, which reads nothing of it.
        const conversationId =
          this.conversationId() ??
          (await this.api.invoke(findHoldConversation, { companyId, key: text })).conversationId;
        body = { reason, conversationId };
      } else {
        const principalId = this.person();
        if (!principalId) {
          this.setError.set('Pick the person to hold.');
          return;
        }
        body = { reason, principalId };
      }
      const hold = await this.api.invoke(createHold, { companyId, body });
      this.setOpen.set(false);
      this.notify.success(`${holdTarget(hold)} is on hold`);
      this.changed.emit();
    } catch (error) {
      this.setError.set(
        problemMessage(error, {
          'status:404': 'No conversation of this company has this key.',
          VALIDATION_FAILED: 'Only a conversation or a member can be held.',
          'status:403': 'Only the Owner sets legal holds.',
        }),
      );
    } finally {
      this.setBusy.set(false);
    }
  }

  protected readonly lifting = signal<LegalHold | null>(null);
  protected readonly liftOpen = signal(false);
  protected readonly liftBusy = signal(false);
  protected readonly liftError = signal<string | null>(null);

  protected askLift(hold: LegalHold): void {
    this.lifting.set(hold);
    this.liftError.set(null);
    this.liftOpen.set(true);
  }

  protected async lift(): Promise<void> {
    const companyId = this.current.id();
    const hold = this.lifting();
    if (!companyId || !hold || this.liftBusy()) return;
    this.liftBusy.set(true);
    try {
      await this.api.invoke(liftHold, { companyId, holdId: hold.id });
      this.liftOpen.set(false);
      this.notify.success(`The hold on ${holdTarget(hold)} is lifted`);
      this.changed.emit();
    } catch (error) {
      this.liftError.set(problemMessage(error));
    } finally {
      this.liftBusy.set(false);
    }
  }
}
