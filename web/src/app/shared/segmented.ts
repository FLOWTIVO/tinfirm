import { ChangeDetectionStrategy, Component, input, model } from '@angular/core';

/** One choice of a segmented control. */
export interface SegmentedOption {
  readonly value: string;
  readonly label: string;
  /** This choice alone cannot be picked now; `title` says why, as a tooltip. */
  readonly disabled?: boolean;
  readonly title?: string;
}

/**
 * A segmented pill control (DESIGN.md: pills for anything that is a choice of state): one of a few
 * values, picked from a row of pills on a muted track, the picked one a white pill. Each pill is a
 * real radio input of one group, so the arrow keys move between them and screen readers announce
 * a radio group. It stretches to the full width on a phone; with `scroll`, its pills keep their
 * width and the row scrolls sideways where it does not fit, for more pills than a phone holds.
 *
 * `inputId` names the group: each radio's id is `{inputId}-{value}` (`none` for an empty value).
 */
@Component({
  selector: 'app-segmented',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {
    role: 'radiogroup',
    class:
      'bg-muted relative flex w-full min-w-0 gap-0.5 rounded-full p-[3px] sm:inline-flex sm:w-auto sm:shrink-0',
    '[attr.aria-label]': 'label() || null',
    '[attr.aria-labelledby]': 'labelledBy() || null',
    '[attr.aria-disabled]': 'disabled() || null',
    '[attr.data-value]': 'value()',
    '[class.max-w-full]': 'scroll()',
    '[class.overflow-x-auto]': 'scroll()',
  },
  template: `
    @for (option of options(); track option.value) {
      <label
        class="text-muted-foreground has-checked:bg-card has-checked:text-foreground has-focus-visible:ring-ring/50 hover:text-foreground flex h-7 min-w-0 cursor-pointer items-center justify-center rounded-full px-2 text-[0.8125rem] font-semibold whitespace-nowrap transition-colors has-checked:shadow-[0_1px_3px_rgb(0_0_0/0.08)] has-disabled:cursor-not-allowed has-disabled:opacity-50 has-focus-visible:ring-3 sm:flex-none sm:px-3"
        [class.flex-auto]="!scroll()"
        [class.flex-none]="scroll()"
        [attr.data-value]="option.value"
        [attr.title]="option.title || null"
      >
        <input
          type="radio"
          class="sr-only"
          [id]="inputId() + '-' + (option.value || 'none')"
          [name]="inputId()"
          [value]="option.value"
          [checked]="value() === option.value"
          [disabled]="disabled() || !!option.disabled"
          (change)="value.set(option.value)"
        />
        <span class="truncate">{{ option.label }}</span>
      </label>
    }
  `,
})
export class Segmented {
  readonly options = input.required<readonly SegmentedOption[]>();
  readonly value = model<string>('');
  /** The radio group's name, and the prefix of each radio's id. */
  readonly inputId = input.required<string>();
  /** The accessible name, when no visible label names the group. */
  readonly label = input('');
  /** The id of a visible label that names the group. */
  readonly labelledBy = input('');
  readonly disabled = input(false);
  /** Keep each pill's width and scroll the row sideways, rather than squeeze the pills. */
  readonly scroll = input(false);
}
