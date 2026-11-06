import { NgTemplateOutlet } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  resource,
  signal,
} from '@angular/core';
import { NgIcon, provideIcons } from '@ng-icons/core';
import { lucideCircleAlert, lucideKeyRound } from '@ng-icons/lucide';
import { HlmAlertImports } from '@spartan-ng/helm/alert';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmCardImports } from '@spartan-ng/helm/card';
import { HlmEmptyImports } from '@spartan-ng/helm/empty';
import { HlmFieldImports } from '@spartan-ng/helm/field';
import { HlmSkeleton } from '@spartan-ng/helm/skeleton';
import { HlmSpinner } from '@spartan-ng/helm/spinner';
import { HlmTextarea } from '@spartan-ng/helm/textarea';
import { Api } from '../../../api/generated/api';
import { getAdminLicence, putLicenceKey } from '../../../api/generated/functions';
import type { AdminLicence, LicenceKeyView } from '../../../api/generated/models';
import { problemMessage } from '../../../api/problem';
import { LicenceState } from '../../../core/licence';
import { Notify } from '../../../core/notify';
import { ErrorState } from '../../../shared/error-state';
import { LicenceBanner } from '../../../shared/licence-banner';
import {
  cleanKey,
  EDITION_LABELS,
  FEATURE_LABELS,
  KEY_PREFIX,
  LICENCE_STATE_LABELS,
  LICENCE_STATE_TONES,
  licenceDay,
  seatsLine,
} from '../../../shared/licence-labels';
import { PageHeader } from '../../../shared/page-header';
import { Time } from '../../../shared/time';

/**
 * `/admin/licence` (ADR-0077 decision 6): the instance's licence for its admins. The edition it
 * runs as, the state, the customer, the end and the grace's end, the seats used of those licensed;
 * the key in force and the next one, which takes over on its start; and a key pasted to enter it.
 * One key is in force at a time: seats added during the year come as the licence's next revision,
 * which replaces it, and a renewal's key waits as the next one. The key itself is never shown again.
 */
@Component({
  selector: 'app-licence',
  imports: [
    NgIcon,
    NgTemplateOutlet,
    HlmAlertImports,
    HlmButton,
    HlmCardImports,
    HlmEmptyImports,
    HlmFieldImports,
    HlmSkeleton,
    HlmSpinner,
    HlmTextarea,
    ErrorState,
    LicenceBanner,
    PageHeader,
    Time,
  ],
  providers: [provideIcons({ lucideCircleAlert, lucideKeyRound })],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './licence.html',
})
export class LicencePage {
  private readonly api = inject(Api);
  private readonly notify = inject(Notify);
  protected readonly state = inject(LicenceState);

  protected readonly editionLabels = EDITION_LABELS;
  protected readonly featureLabels = FEATURE_LABELS;
  protected readonly stateLabels = LICENCE_STATE_LABELS;
  protected readonly stateTones = LICENCE_STATE_TONES;
  protected readonly day = licenceDay;
  protected readonly keyPrefix = KEY_PREFIX;

  protected readonly licence = this.state.value;
  protected readonly seats = computed(() => {
    const licence = this.licence();
    return licence ? seatsLine(licence.seats) : '';
  });

  /** The key in force and the next one, as the server keeps them. */
  protected readonly keys = resource({
    loader: () => this.api.invoke(getAdminLicence, {}),
  });
  protected readonly current = computed<LicenceKeyView | null>(() =>
    this.keys.hasValue() ? (this.keys.value()?.current ?? null) : null,
  );
  protected readonly next = computed<LicenceKeyView | null>(() =>
    this.keys.hasValue() ? (this.keys.value()?.next ?? null) : null,
  );

  /** "Pro": a key's edition (the key's template context is untyped). */
  protected editionOf(key: LicenceKeyView): string {
    return EDITION_LABELS[key.edition];
  }

  constructor() {
    // The page reads the licence again: an admin comes here to see it as it is now.
    this.state.reload();
  }

  // --- Paste a new key ------------------------------------------------------------------------

  protected readonly pasted = signal('');
  protected readonly entering = signal(false);
  protected readonly enterError = signal<string | null>(null);

  protected onPaste(event: Event): void {
    this.pasted.set((event.target as HTMLTextAreaElement).value);
    this.enterError.set(null);
  }

  protected async enter(event: Event): Promise<void> {
    event.preventDefault();
    // Only once the key in force is known: what the new key did is said against it.
    if (this.entering() || !this.keys.hasValue()) return;
    const key = cleanKey(this.pasted());
    if (!key) {
      this.enterError.set('Paste the licence key first.');
      return;
    }
    if (!key.startsWith(KEY_PREFIX)) {
      this.enterError.set(
        `A licence key starts with ${KEY_PREFIX} Copy the whole key from your vendor’s email and paste it again.`,
      );
      return;
    }
    this.entering.set(true);
    this.enterError.set(null);
    try {
      const before = this.current();
      const keys = await this.api.invoke(putLicenceKey, { body: { key } });
      this.keys.set(keys);
      this.pasted.set('');
      this.state.reload();
      this.notify.success(...enteredMessage(before, keys));
    } catch (error) {
      this.enterError.set(problemMessage(error));
    } finally {
      this.entering.set(false);
    }
  }
}

/**
 * What entering a key did, from the keys before and after: a first key, a key that replaces the
 * one in force (more seats, or another licence), or the next key, which waits for its start.
 */
export function enteredMessage(
  before: LicenceKeyView | null,
  after: AdminLicence,
): [string, string] {
  const { current, next } = after;
  const unchanged =
    !!before && !!current && before.licenceId === current.licenceId && before.rev === current.rev;
  if (next && (unchanged || !current)) {
    return [
      'Next key entered',
      `${EDITION_LABELS[next.edition]} for ${next.customer} takes over on ${licenceDay(next.startsAt)}.`,
    ];
  }
  if (!current) return ['Key entered', 'The instance runs as Community until a key is in force.'];
  const seats = current.seats == null ? '' : `, ${current.seats} seats`;
  return [
    before ? 'Key replaced' : 'Key entered',
    `${EDITION_LABELS[current.edition]} for ${current.customer}${seats}, until ${licenceDay(current.endsAt)}.`,
  ];
}
