import {
  ChangeDetectionStrategy,
  Component,
  computed,
  input,
  linkedSignal,
  output,
} from '@angular/core';
import { NgIcon, provideIcons } from '@ng-icons/core';
import { lucideCircleAlert } from '@ng-icons/lucide';
import { HlmAlertImports } from '@spartan-ng/helm/alert';
import { HlmAlertDialogImports } from '@spartan-ng/helm/alert-dialog';
import { HlmFieldImports } from '@spartan-ng/helm/field';
import { HlmSpinner } from '@spartan-ng/helm/spinner';
import { HlmTextarea } from '@spartan-ng/helm/textarea';

/**
 * A confirmation that asks why (the package 6 contract, §5.4): stopping the company's runs, pausing a
 * system, suspending a member, setting a legal hold. The reason stays on the switch's row and never
 * enters the audit chain, which the dialog says. Whatever else the choice needs (a checkbox, a list)
 * comes as content between the description and the reason.
 */
@Component({
  selector: 'app-reason-dialog',
  imports: [
    HlmAlertImports,
    HlmAlertDialogImports,
    HlmFieldImports,
    HlmSpinner,
    HlmTextarea,
    NgIcon,
  ],
  providers: [provideIcons({ lucideCircleAlert })],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <hlm-alert-dialog [state]="open() ? 'open' : 'closed'" (closed)="closed.emit()">
      <hlm-alert-dialog-content
        *hlmAlertDialogPortal="let ctx"
        class="sm:max-w-lg"
        [attr.data-testid]="testId()"
      >
        <hlm-alert-dialog-header>
          <h2 hlmAlertDialogTitle>{{ heading() }}</h2>
          @if (description()) {
            <p hlmAlertDialogDescription>{{ description() }}</p>
          }
        </hlm-alert-dialog-header>
        <ng-content />
        <div hlmField>
          <label hlmFieldLabel [for]="testId() + '-reason'">{{ label() }}</label>
          <textarea
            hlmTextarea
            rows="2"
            [attr.maxlength]="maxLength()"
            class="min-h-16"
            [id]="testId() + '-reason'"
            [attr.aria-describedby]="testId() + '-reason-note'"
            [value]="reason()"
            (input)="reason.set($any($event.target).value)"
          ></textarea>
          <p hlmFieldDescription [id]="testId() + '-reason-note'">
            {{ required() ? 'Required.' : 'Optional.' }} {{ note() }}
          </p>
        </div>
        @if (error(); as message) {
          <div hlmAlert variant="destructive" role="alert">
            <ng-icon name="lucideCircleAlert" aria-hidden="true" />
            <p hlmAlertDescription>{{ message }}</p>
          </div>
        }
        <hlm-alert-dialog-footer>
          <button hlmAlertDialogCancel [disabled]="busy()">Cancel</button>
          <button
            hlmAlertDialogAction
            [variant]="destructive() ? 'destructive' : 'default'"
            [attr.data-testid]="testId() + '-confirm'"
            [disabled]="busy() || !valid()"
            (click)="confirm.emit(reason().trim())"
          >
            @if (busy()) {
              <hlm-spinner aria-hidden="true" />
            }
            {{ confirmLabel() }}
          </button>
        </hlm-alert-dialog-footer>
      </hlm-alert-dialog-content>
    </hlm-alert-dialog>
  `,
})
export class ReasonDialog {
  readonly open = input(false);
  readonly heading = input.required<string>();
  readonly description = input<string>('');
  readonly label = input('Reason');
  readonly confirmLabel = input.required<string>();
  readonly destructive = input(true);
  readonly required = input(true);
  readonly busy = input(false);
  readonly error = input<string | null>(null);
  /** The dialog's `data-testid`, and the prefix of its controls' ids. */
  readonly testId = input('reason-dialog');
  /** Where the reason is kept, said under the field. */
  readonly note = input('Kept with the switch, never in the audit log.');
  readonly maxLength = input(500);

  readonly confirm = output<string>();
  readonly closed = output<void>();

  /** Empty again each time the dialog opens. */
  protected readonly reason = linkedSignal({ source: this.open, computation: () => '' });
  protected readonly valid = computed(() => !this.required() || this.reason().trim().length > 0);
}
