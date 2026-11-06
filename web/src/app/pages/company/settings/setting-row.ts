import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

/**
 * One setting in a settings card, under a hairline: its name and what it does, then its control.
 * A field's row puts the words in a column at the left and the control beside them, stacked when
 * the row itself is narrow (a phone, or a card beside the navigation and a section list), so the
 * control never gets squeezed out of the card; a switch's row gives the words the width and puts
 * the switch at its end. What the setting does is the `[help]` content, `{rowId}-help`, for the
 * control's `aria-describedby`.
 */
@Component({
  selector: 'app-setting-row',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {
    '[class]': 'hostClass()',
    '[attr.data-setting]': 'rowId()',
  },
  template: `
    <div [class]="layoutClass()">
      <div class="min-w-0" [class.flex-1]="kind() === 'switch'">
        @if (for(); as control) {
          <label class="block text-sm font-semibold" [for]="control" [id]="rowId() + '-label'">{{
            label()
          }}</label>
        } @else {
          <div class="text-sm font-semibold" [id]="rowId() + '-label'">{{ label() }}</div>
        }
        <div
          class="text-muted-foreground mt-0.5 max-w-2xl text-[0.8125rem] leading-normal text-pretty empty:hidden"
          [id]="rowId() + '-help'"
        >
          <ng-content select="[help]" />
        </div>
      </div>
      <div
        [class]="
          kind() === 'switch'
            ? 'flex shrink-0 items-center gap-2.5'
            : 'flex min-w-0 flex-col items-start gap-1.5'
        "
      >
        <ng-content />
      </div>
    </div>
  `,
})
export class SettingRow {
  /** Prefixes the ids of the row's label (`-label`) and help (`-help`). */
  readonly rowId = input.required<string>();
  readonly label = input.required<string>();
  /** The id of the field the label names; a switch or a value names itself. */
  readonly for = input<string>('');
  readonly kind = input<'field' | 'switch'>('field');

  protected readonly hostClass = computed(() =>
    this.kind() === 'switch'
      ? 'block border-t px-4 py-4 sm:px-5'
      : '@container block border-t px-4 py-4 sm:px-5',
  );
  /** Side by side once the row has room for the words' 15rem and a control of 19rem or more. */
  protected readonly layoutClass = computed(() =>
    this.kind() === 'switch'
      ? 'flex items-baseline gap-4 sm:gap-6'
      : 'grid gap-x-8 gap-y-2 @xl:grid-cols-[minmax(0,15rem)_minmax(0,1fr)] @xl:items-baseline',
  );
}
