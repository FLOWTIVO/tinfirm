import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmSpinner } from '@spartan-ng/helm/spinner';

/**
 * What a settings section has not saved yet, floating at the foot of the page while there is
 * something (DESIGN.md: the action bar float): how many changes and which, Discard and Save. Fields
 * collect here; switches save at once and never do. With `form`, Save submits that form.
 */
@Component({
  selector: 'app-unsaved-changes-bar',
  imports: [HlmButton, HlmSpinner],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {
    class: 'sticky bottom-4 z-10 block',
    role: 'region',
    'aria-label': 'Unsaved changes',
  },
  template: `
    <div
      class="bg-card flex flex-wrap items-center gap-x-3 gap-y-2 rounded-[18px] border py-2.5 pr-2.5 pl-4 shadow-[0_10px_30px_-12px_rgb(0_0_0/0.25)] sm:pl-5"
    >
      <p
        class="m-0 flex min-w-0 flex-1 basis-48 flex-col text-sm sm:flex-row sm:items-baseline sm:gap-3"
        aria-live="polite"
      >
        <span class="font-semibold" data-testid="unsaved-count">{{ count() }}</span>
        <span
          class="truncate text-[0.8125rem]"
          [class.text-muted-foreground]="!blocked()"
          [class.text-destructive]="!!blocked()"
          data-testid="unsaved-names"
          >{{ blocked() || changes().join(' · ') }}</span
        >
      </p>
      <div class="ml-auto flex gap-2">
        <button hlmBtn variant="ghost" type="button" [disabled]="saving()" (click)="discard.emit()">
          Discard
        </button>
        <button
          hlmBtn
          [type]="form() ? 'submit' : 'button'"
          [attr.form]="form() || null"
          [attr.data-testid]="saveTestId()"
          [disabled]="saving() || !!blocked()"
          (click)="form() || save.emit()"
        >
          @if (saving()) {
            <hlm-spinner aria-hidden="true" />
          }
          {{ saveLabel() }}
        </button>
      </div>
    </div>
  `,
})
export class UnsavedChangesBar {
  /** What changed, by the names of their settings. */
  readonly changes = input.required<readonly string[]>();
  readonly saving = input(false);
  /** Why it cannot be saved as it is; Save waits until it is gone. */
  readonly blocked = input<string | null>(null);
  /** The id of the form Save submits; without one, Save emits `save`. */
  readonly form = input('');
  readonly saveLabel = input('Save');
  readonly saveTestId = input('save-changes');

  readonly discard = output<void>();
  readonly save = output<void>();

  protected readonly count = computed(() => {
    const n = this.changes().length;
    return n === 1 ? '1 unsaved change' : `${n} unsaved changes`;
  });
}
