import { booleanAttribute, ChangeDetectionStrategy, Component, input } from '@angular/core';

/**
 * Tinfirm's logo (DESIGN.md, Logo): the Ti mark, a T of two sheets and an i whose dot is human
 * orange, and with `wordmark` the monoline "tinfirm" beside it. Size it with a height on the host
 * (`class="h-12"`); both drawings scale to it. `quiet` turns both orange dots green and ink, for
 * the app shell, where orange appears only while a person is waited on (the Scarce Orange Rule).
 * Decorative: the surrounding link or heading carries the name.
 */
@Component({
  selector: 'app-logo',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'inline-flex shrink-0 items-center gap-[0.3em]' },
  template: `
    <svg
      class="h-full w-auto"
      viewBox="0 0 64 64"
      aria-hidden="true"
      focusable="false"
      data-testid="logo-mark"
    >
      <!-- The T's stem stops 2 units under the bar, so the bar reads as a sheet laid over it. -->
      <path class="fill-logo-stem" d="M16 24H30V51A7 7 0 0 1 23 58A7 7 0 0 1 16 51Z" />
      <rect class="fill-primary" x="5" y="8" width="36" height="14" rx="7" />
      <rect class="fill-logo-stem" x="45" y="26" width="14" height="32" rx="7" />
      <circle
        [class.fill-human]="!quiet()"
        [class.fill-primary]="quiet()"
        cx="52"
        cy="15"
        r="7"
        data-testid="logo-dot"
      />
    </svg>
    @if (wordmark()) {
      <svg
        class="h-[72%] w-auto"
        viewBox="0 0 166 44"
        aria-hidden="true"
        focusable="false"
        data-testid="logo-wordmark"
      >
        <g
          class="stroke-foreground"
          fill="none"
          stroke-width="6"
          stroke-linecap="round"
          stroke-linejoin="round"
        >
          <path d="M3 16H17M8 6V33Q8 40 15 40H18" />
          <path d="M28 16V40" />
          <path d="M40 40V16M40 25C40 19 44 16 49 16C54 16 58 19 58 25V40" />
          <path d="M73 40V14Q73 6 81 6H84M68 16H82" />
          <path d="M94 16V40" />
          <path d="M106 40V16M106 26C106 19 111 16 117 16H119" />
          <path
            d="M130 40V16M130 25C130 19 133 16 138 16C142 16 145 19 145 25V40M145 25C145 19 148 16 153 16C157 16 160 19 160 25V40"
          />
        </g>
        <circle class="fill-foreground" cx="28" cy="6" r="3.8" />
        <circle
          [class.fill-human]="!quiet()"
          [class.fill-foreground]="quiet()"
          cx="94"
          cy="6"
          r="3.8"
          data-testid="logo-wordmark-dot"
        />
      </svg>
    }
  `,
})
export class Logo {
  readonly wordmark = input(false, { transform: booleanAttribute });
  readonly quiet = input(false, { transform: booleanAttribute });
}
