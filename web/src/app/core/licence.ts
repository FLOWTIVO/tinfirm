import { computed, inject, Injectable, resource } from '@angular/core';
import { Api } from '../api/generated/api';
import { getLicence } from '../api/generated/functions';
import type { Licence, LicenceFeature } from '../api/generated/models';
import { Session } from './session';

/**
 * The instance's licence as `GET /api/licence` answers it (ADR-0077): the edition, its state, the
 * seats and the features in force now. One key covers the instance, so it is read once per session
 * and read again after a key is added or removed. The web app only hides what the server refuses:
 * every gate is the server's (decision 1), and a page that calls a gated endpoint still words its
 * `403 EDITION_REQUIRED`.
 */
@Injectable({ providedIn: 'root' })
export class LicenceState {
  private readonly api = inject(Api);
  private readonly session = inject(Session);

  readonly licence = resource({
    params: () => this.session.user()?.id,
    loader: () => this.api.invoke(getLicence, {}),
  });

  /** The licence once read; null while it loads, or when it could not be read. */
  readonly value = computed<Licence | null>(() =>
    this.licence.hasValue() ? (this.licence.value() ?? null) : null,
  );

  /**
   * Whether a feature is in force now. Unknown (the licence not read yet, or not readable) counts
   * as in force: the page shows the feature, and the server refuses it if it must.
   */
  has(feature: LicenceFeature): boolean {
    const licence = this.value();
    return !licence || licence.features.includes(feature);
  }

  reload(): void {
    this.licence.reload();
  }

  /** Replaces what is known, e.g. with what a page read itself. */
  set(value: Licence): void {
    this.licence.set(value);
  }
}
