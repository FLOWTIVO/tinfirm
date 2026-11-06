import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import type { LicenceFeature } from '../api/generated/models';
import { EDITION_LABELS, editionRequiredMessage, FEATURE_EDITION } from './licence-labels';

/**
 * "Pro" or "Enterprise" beside a paid feature the licence does not hold now (ADR-0077 decision 4),
 * with the sentence of what it needs for a screen reader and on hover. The server refuses the
 * feature either way; the badge only says why it is off.
 */
@Component({
  selector: 'app-edition-badge',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'inline-flex' },
  template: `
    <span
      class="bg-secondary text-foreground inline-flex h-[22px] items-center rounded-full px-2.5 text-xs font-semibold whitespace-nowrap"
      [attr.title]="sentence()"
      [attr.data-testid]="'edition-badge-' + feature()"
      ><span aria-hidden="true">{{ label() }}</span
      ><span class="sr-only">{{ sentence() }}</span></span
    >
  `,
})
export class EditionBadge {
  readonly feature = input.required<LicenceFeature>();
  protected readonly label = computed(() => EDITION_LABELS[FEATURE_EDITION[this.feature()]]);
  protected readonly sentence = computed(() => editionRequiredMessage(this.feature(), undefined));
}
