import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { NgIcon, provideIcons } from '@ng-icons/core';
import { lucideLock, lucideRotateCw, lucideTriangleAlert } from '@ng-icons/lucide';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmEmptyImports } from '@spartan-ng/helm/empty';
import { problemMessage, toProblem } from '../api/problem';

/** Full-width error for a failed load, with the problem-details message and a retry button. */
@Component({
  selector: 'app-error-state',
  imports: [HlmEmptyImports, HlmButton, NgIcon],
  providers: [provideIcons({ lucideLock, lucideRotateCw, lucideTriangleAlert })],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div hlmEmpty class="border" role="alert">
      <div hlmEmptyHeader>
        <div hlmEmptyMedia variant="icon">
          <ng-icon [name]="forbidden() ? 'lucideLock' : 'lucideTriangleAlert'" aria-hidden="true" />
        </div>
        <div hlmEmptyTitle>{{ heading() }}</div>
        <p hlmEmptyDescription>{{ message() }}</p>
      </div>
      @if (retryable()) {
        <div hlmEmptyContent>
          <button hlmBtn variant="outline" size="sm" type="button" (click)="retry.emit()">
            <ng-icon name="lucideRotateCw" aria-hidden="true" />
            Try again
          </button>
        </div>
      }
    </div>
  `,
})
export class ErrorState {
  readonly error = input.required<unknown>();
  readonly heading = input<string>("Couldn't load this");
  /** Messages keyed by problem code or `status:<n>`, e.g. `{ 'status:403': 'Only owners…' }`. */
  readonly messages = input<Record<string, string>>({});
  readonly retry = output<void>();

  private readonly status = computed(() => toProblem(this.error()).status);
  protected readonly forbidden = computed(() => this.status() === 403);
  protected readonly retryable = computed(() => ![403, 404].includes(this.status()));
  protected readonly message = computed(() => problemMessage(this.error(), this.messages()));
}
