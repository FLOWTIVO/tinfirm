import { NgTemplateOutlet } from '@angular/common';
import {
  afterRenderEffect,
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  type ElementRef,
  inject,
  InjectionToken,
  input,
  linkedSignal,
  resource,
  signal,
  viewChild,
} from '@angular/core';
import { RouterLink } from '@angular/router';
import { NgIcon, provideIcons } from '@ng-icons/core';
import {
  lucideCheck,
  lucideCircleAlert,
  lucideClock,
  lucideEraser,
  lucideInfo,
  lucideLock,
  lucideTriangleAlert,
} from '@ng-icons/lucide';
import { HlmAlertImports } from '@spartan-ng/helm/alert';
import { HlmAlertDialogImports } from '@spartan-ng/helm/alert-dialog';
import { HlmInput } from '@spartan-ng/helm/input';
import { HlmSkeleton } from '@spartan-ng/helm/skeleton';
import { HlmSpinner } from '@spartan-ng/helm/spinner';
import { Api } from '../../../api/generated/api';
import {
  getRetention,
  listHolds,
  listMembers,
  listRetentionOpenConversations,
  previewRetention,
  updateRetention,
} from '../../../api/generated/functions';
import type {
  RetentionOpenConversation,
  RetentionPeriods,
  RetentionSettings,
} from '../../../api/generated/models';
import { problemMessage } from '../../../api/problem';
import { loadAll } from '../../../core/company-data';
import { CurrentCompany } from '../../../core/current-company';
import { LicenceState } from '../../../core/licence';
import { Notify } from '../../../core/notify';
import { EditionBadge } from '../../../shared/edition-badge';
import { ErrorState } from '../../../shared/error-state';
import { PLACE_LABELS, retentionPhrase } from '../../../shared/governance-labels';
import { plural } from '../../../shared/labels';
import { PageHeader } from '../../../shared/page-header';
import { Time } from '../../../shared/time';
import { SettingRow } from '../settings/setting-row';
import { UnsavedChangesBar } from '../settings/unsaved-changes-bar';
import { ErasurePanel } from './erasure-panel';
import { HoldsPanel } from './holds-panel';
import { personChoices } from './person-choice';
import {
  changedPeriods,
  duePhrase,
  forecastOf,
  PERIOD_FIELDS,
  PERIOD_RULES,
  type PeriodField,
  periodErrors,
  periodsForm,
  type PeriodsForm,
  periodsRequest,
  periodsSummary,
  samePeriods,
  shortenedPeriods,
  shorteningPhrase,
  storedDays,
  sweepsSome,
} from './retention-view';

/** How long the page waits after the periods were typed before it counts what they delete (ms). */
export const RETENTION_PREVIEW_DEBOUNCE = new InjectionToken<number>('RETENTION_PREVIEW_DEBOUNCE', {
  factory: () => 400,
});

type SectionId = 'periods' | 'holds' | 'erase';

const SECTIONS: readonly { id: SectionId; label: string; icon: string }[] = [
  { id: 'periods', label: 'Periods', icon: 'lucideClock' },
  { id: 'holds', label: 'Legal holds', icon: 'lucideLock' },
  { id: 'erase', label: 'Erase on request', icon: 'lucideEraser' },
];

/** The section one arrow key, Home or End moves to in the rail, or null for another key. */
function tabKeyTarget(key: string, current: SectionId): SectionId | null {
  const ids = SECTIONS.map((s) => s.id);
  const i = Math.max(0, ids.indexOf(current));
  switch (key) {
    case 'ArrowDown':
    case 'ArrowRight':
      return ids[(i + 1) % ids.length]!;
    case 'ArrowUp':
    case 'ArrowLeft':
      return ids[(i - 1 + ids.length) % ids.length]!;
    case 'Home':
      return ids[0]!;
    case 'End':
      return ids[ids.length - 1]!;
    default:
      return null;
  }
}

/**
 * Retention (the package 6 contract, §5.9; CONVENTIONS §15.11), the Owner's, one section at a time
 * with a rail that says each one's state: Periods (how long the company keeps its conversations'
 * content, files, chats' words and transcripts, and agents' memory notes from their last use
 * (ADR-0063); what the next daily sweep removes, and the open conversations it never removes, by
 * key and age, never by title), Legal holds, and Erase on request. While the Owner changes the
 * periods, a pause in the typing asks what the next sweep would delete under them
 * (`POST /retention/preview`), beside the saved ones and what the legal holds keep: the line under
 * the periods and the question before shorter ones are saved say it. On a phone the rail is a row
 * of pills. An admin reads the periods only.
 */
@Component({
  selector: 'app-retention',
  imports: [
    NgIcon,
    NgTemplateOutlet,
    RouterLink,
    HlmAlertImports,
    HlmAlertDialogImports,
    HlmInput,
    HlmSkeleton,
    HlmSpinner,
    EditionBadge,
    ErasurePanel,
    ErrorState,
    HoldsPanel,
    PageHeader,
    SettingRow,
    Time,
    UnsavedChangesBar,
  ],
  providers: [
    provideIcons({
      lucideCheck,
      lucideCircleAlert,
      lucideClock,
      lucideEraser,
      lucideInfo,
      lucideLock,
      lucideTriangleAlert,
    }),
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './retention.html',
})
export class RetentionPage {
  private readonly api = inject(Api);
  private readonly notify = inject(Notify);
  private readonly current = inject(CurrentCompany);
  private readonly debounce = inject(RETENTION_PREVIEW_DEBOUNCE);

  /** From a leaver's checklist: the principal to erase or hold, and their name. */
  readonly erase = input<string | undefined>();
  readonly name = input<string | undefined>();

  protected readonly companyId = this.current.id;
  protected readonly fields = PERIOD_FIELDS;
  protected readonly rules = PERIOD_RULES;
  protected readonly phrase = retentionPhrase;
  protected readonly stored = storedDays;
  protected readonly plural = plural;
  /** `MANAGE_RETENTION`: the Owner's alone (CONVENTIONS §6). */
  protected readonly isOwner = computed(() => this.current.access().role === 'OWNER');

  private readonly licence = inject(LicenceState);
  /**
   * Periods of the company's own: Pro and Enterprise (ADR-0077 decisions 4 and 8). The server says
   * which periods are in force (`mode`); before it does, the licence's features say it.
   */
  protected readonly periodsLicensed = computed(() => {
    const mode = this.loaded()?.mode;
    return mode ? mode === 'PERIODS' : this.licence.has('RETENTION_SETTINGS');
  });
  /** Setting a legal hold: Enterprise; an open hold stays in force and is lifted in any edition. */
  protected readonly holdsLicensed = computed(() => this.licence.has('LEGAL_HOLDS'));
  protected readonly editsPeriods = computed(() => this.isOwner() && this.periodsLicensed());
  /** Why the periods cannot be changed under the licence in force. */
  protected readonly periodsEditionText = computed(() =>
    (this.loaded()?.mode ?? (this.licence.value()?.state === 'EXPIRED' ? 'FROZEN' : null)) ===
    'FROZEN'
      ? 'The licence ran out: the periods in force keep running, and changing them needs a Pro licence again.'
      : 'Under Community nothing is removed by retention: everything is kept. Periods of your own need a Pro licence.',
  );

  protected readonly settings = resource({
    params: () => this.current.id(),
    loader: ({ params: companyId }) => this.api.invoke(getRetention, { companyId }),
  });
  protected readonly loaded = computed<RetentionSettings | null>(() =>
    this.settings.hasValue() ? (this.settings.value() ?? null) : null,
  );
  protected readonly due = computed(() => {
    const s = this.loaded();
    return s ? duePhrase(s) : '';
  });
  protected readonly dueSome = computed(() => {
    const s = this.loaded();
    return !!s && sweepsSome(s);
  });

  // --- The sections -----------------------------------------------------------------------------

  /** The section open; a checklist's `?erase=` opens the erasure. */
  protected readonly section = linkedSignal<SectionId>(() => (this.erase() ? 'erase' : 'periods'));

  private readonly erasure = viewChild(ErasurePanel);

  protected readonly tabs = computed(() => {
    const s = this.loaded();
    const holds = this.holds.hasValue() ? (this.holds.value() ?? []).length : null;
    const details: Record<SectionId, { mono: string; text: string }> = {
      periods: {
        mono: s ? periodsSummary(s) : '',
        text: s ? `days${this.dirty() ? ' · unsaved' : ''}` : '',
      },
      holds: {
        mono: '',
        text: holds === null ? '' : holds ? `${holds} active` : 'None',
      },
      erase: { mono: '', text: this.erasure()?.summary() ?? 'Removes content now' },
    };
    return SECTIONS.map((section) => ({
      ...section,
      open: section.id === this.section(),
      detail: details[section.id],
      count: section.id === 'holds' && holds ? holds : null,
    }));
  });

  protected openSection(id: SectionId): void {
    this.section.set(id);
  }

  protected onTabKeys(event: KeyboardEvent): void {
    const to = tabKeyTarget(event.key, this.section());
    if (!to) return;
    event.preventDefault();
    this.section.set(to);
    document.getElementById(`retention-tab-${to}`)?.focus();
  }

  /** From the erasure, blocked by a hold: the holds, with their tab in focus. */
  protected showHolds(): void {
    this.section.set('holds');
    document.getElementById('retention-tab-holds')?.focus();
  }

  private readonly rail = viewChild<ElementRef<HTMLElement>>('rail');

  constructor() {
    // The periods as typed are counted once the typing pauses, not at every key.
    effect((onCleanup) => {
      const proposal = this.proposal();
      if (!proposal) {
        this.settled.set(null);
        return;
      }
      const timer = setTimeout(() => this.settled.set(proposal), this.debounce);
      onCleanup(() => clearTimeout(timer));
    });

    // On a phone the rail is a row of pills: the open section's is kept in view.
    afterRenderEffect(() => {
      this.section();
      const nav = this.rail()?.nativeElement;
      const open = nav?.querySelector<HTMLElement>('[aria-selected=true]');
      if (!nav || !open || nav.scrollWidth <= nav.clientWidth) return;
      const row = nav.getBoundingClientRect();
      const pill = open.getBoundingClientRect();
      if (pill.left < row.left || pill.right > row.right) {
        nav.scrollLeft += pill.left - row.left - 16;
      }
    });
  }

  // --- The periods ------------------------------------------------------------------------------

  private readonly savedForm = computed(() => {
    const s = this.loaded();
    return s ? periodsForm(s) : null;
  });
  /** The periods as typed; unsaved changes outlive the settings read again after a hold. */
  protected readonly form = linkedSignal<PeriodsForm | null, PeriodsForm | null>({
    source: this.savedForm,
    computation: (saved, previous) => {
      const typed = previous?.value;
      const before = previous?.source;
      return saved && typed && before && changedPeriods(typed, before).length ? typed : saved;
    },
  });
  protected readonly errors = computed(() => {
    const form = this.form();
    return form ? periodErrors(form) : {};
  });
  protected readonly invalid = computed(() => Object.keys(this.errors()).length > 0);
  private readonly changed = computed(() => {
    const form = this.form();
    const saved = this.savedForm();
    return form && saved ? changedPeriods(form, saved) : [];
  });
  protected readonly dirty = computed(() => this.changed().length > 0);
  protected readonly changes = computed(() => this.changed().map((f) => PERIOD_RULES[f].label));
  protected readonly saving = signal(false);
  protected readonly saveError = signal<string | null>(null);
  /** "Saved" in the sweep's line, until the next change. */
  protected readonly savedNote = signal(false);

  /** The value a changed period replaces, "keep" for one kept for good; null when unchanged. */
  protected wasOf(field: PeriodField): string | null {
    const form = this.form();
    const saved = this.savedForm();
    if (!form || !saved || form[field].trim() === saved[field]) return null;
    return saved[field] || 'keep';
  }

  protected unitOf(field: PeriodField): string {
    const rule = PERIOD_RULES[field];
    return rule.keepable && !this.form()?.[field].trim() ? 'kept for good' : `days ${rule.from}`;
  }

  protected patch(field: PeriodField, value: string): void {
    this.form.update((f) => (f ? { ...f, [field]: value } : f));
    this.savedNote.set(false);
  }

  protected discard(): void {
    this.form.set(this.savedForm());
    this.saveError.set(null);
  }

  // --- What the changed periods delete (`POST /retention/preview`) -------------------------------

  /** The Owner's valid, unsaved periods; null while nothing is changed or a period is marked. */
  private readonly proposal = computed<RetentionPeriods | null>(
    () => {
      const form = this.form();
      return this.isOwner() && form && this.dirty() && !this.invalid()
        ? periodsRequest(form)
        : null;
    },
    { equal: samePeriods },
  );
  /** The proposal once the typing paused. */
  private readonly settled = signal<RetentionPeriods | null>(null, { equal: samePeriods });
  protected readonly preview = resource({
    params: () => {
      const companyId = this.current.id();
      const body = this.settled();
      return companyId && body ? { companyId, body } : undefined;
    },
    loader: ({ params }) => this.api.invoke(previewRetention, params),
  });
  /** The preview's sentences, for the periods as typed now; null while counting or without one. */
  protected readonly forecast = computed(() => {
    const proposal = this.proposal();
    if (!proposal || !samePeriods(proposal, this.settled()) || !this.preview.hasValue()) {
      return null;
    }
    const preview = this.preview.value();
    return preview ? forecastOf(preview) : null;
  });
  /** Counting what the periods as typed delete: waiting for a pause, or for the answer. */
  protected readonly counting = computed(() => {
    const proposal = this.proposal();
    return !!proposal && (!samePeriods(proposal, this.settled()) || this.preview.isLoading());
  });
  protected readonly previewFailed = computed(
    () => !!this.proposal() && !this.counting() && !!this.preview.error(),
  );
  protected readonly dueWarns = computed(() => {
    const forecast = this.forecast();
    return forecast ? forecast.some : this.dueSome();
  });

  /** Shorter periods ask first: the next sweep may remove more, which cannot be brought back. */
  protected readonly confirmOpen = signal(false);
  protected readonly shortenings = computed(() => {
    const form = this.form();
    const saved = this.savedForm();
    if (!form || !saved) return [];
    return shortenedPeriods(form, saved).map((f) => shorteningPhrase(f, form, saved));
  });

  protected save(event: Event): void {
    event.preventDefault();
    if (!this.form() || this.saving() || this.invalid() || !this.dirty()) return;
    if (this.shortenings().length) {
      this.confirmOpen.set(true);
      return;
    }
    void this.commit();
  }

  protected async commit(): Promise<void> {
    const companyId = this.current.id();
    const form = this.form();
    if (!companyId || !form || this.saving()) return;
    this.saving.set(true);
    this.saveError.set(null);
    try {
      const saved = await this.api.invoke(updateRetention, {
        companyId,
        body: periodsRequest(form),
      });
      this.settings.set(saved);
      this.savedNote.set(true);
      this.notify.success('Retention saved', duePhrase(saved));
    } catch (error) {
      this.saveError.set(
        problemMessage(error, { 'status:403': 'Only the Owner changes retention.' }),
      );
    } finally {
      this.saving.set(false);
      this.confirmOpen.set(false);
    }
  }

  // --- Open conversations older than their period (the Owner's) ---------------------------------

  protected readonly open = resource({
    params: () => (this.isOwner() ? this.current.id() : undefined),
    loader: ({ params: companyId }) =>
      this.api.invoke(listRetentionOpenConversations, { companyId, limit: 50 }),
  });
  protected readonly openItems = computed(() =>
    this.open.hasValue() ? (this.open.value()?.items ?? []) : [],
  );

  /** "in a chat", "on an issue". */
  protected placeOf(c: RetentionOpenConversation): string {
    const label = PLACE_LABELS[c.place];
    return label.charAt(0).toLowerCase() + label.slice(1);
  }

  // --- Holds and erasure (the Owner's) ----------------------------------------------------------

  protected readonly holds = resource({
    params: () => (this.isOwner() ? this.current.id() : undefined),
    loader: ({ params: companyId }) =>
      loadAll((cursor) => this.api.invoke(listHolds, { companyId, limit: 100, cursor })),
  });
  protected readonly holdItems = computed(() =>
    this.holds.hasValue() ? (this.holds.value() ?? []) : [],
  );

  private readonly members = resource({
    params: () => (this.isOwner() ? this.current.id() : undefined),
    loader: ({ params: companyId }) =>
      loadAll((cursor) => this.api.invoke(listMembers, { companyId, limit: 100, cursor })),
  });
  private readonly memberList = computed(() =>
    this.members.hasValue() ? (this.members.value() ?? []) : [],
  );
  /** Members' names by their user id: who set a hold. */
  protected readonly names = computed(
    () => new Map(this.memberList().map((m) => [m.userId, m.displayName])),
  );
  protected readonly people = computed(() => {
    const erase = this.erase();
    const left = erase ? { principalId: erase, name: this.name() ?? 'The member who left' } : null;
    return personChoices(this.memberList(), left);
  });
  protected readonly preselect = computed(() => this.erase() ?? null);

  protected holdsChanged(): void {
    this.holds.reload();
    this.settings.reload();
    // What the holds keep changed with them.
    if (this.settled()) this.preview.reload();
  }
}
