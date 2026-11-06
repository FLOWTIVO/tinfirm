import { ChangeDetectionStrategy, Component, input } from '@angular/core';

/** Title, one-line description and actions at the top of a page. */
@Component({
  selector: 'app-page-header',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'flex flex-col gap-3 sm:flex-row sm:items-end sm:justify-between' },
  template: `
    <div class="min-w-0">
      <h1 class="text-2xl font-bold tracking-tight text-balance">{{ title() }}</h1>
      @if (description()) {
        <p class="text-muted-foreground mt-1 text-sm text-pretty">{{ description() }}</p>
      }
    </div>
    <div class="flex shrink-0 flex-wrap items-center gap-2 empty:hidden">
      <ng-content />
    </div>
  `,
})
export class PageHeader {
  readonly title = input.required<string>();
  readonly description = input<string>();
}
