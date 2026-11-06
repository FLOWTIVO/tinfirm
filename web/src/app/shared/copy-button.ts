import { ChangeDetectionStrategy, Component, inject, input, signal } from '@angular/core';
import { NgIcon, provideIcons } from '@ng-icons/core';
import { lucideCheck, lucideCopy } from '@ng-icons/lucide';
import { HlmButton } from '@spartan-ng/helm/button';
import { Notify } from '../core/notify';

/** Copies a value to the clipboard; the icon turns into a check for a moment. */
@Component({
  selector: 'app-copy-button',
  imports: [NgIcon, HlmButton],
  providers: [provideIcons({ lucideCheck, lucideCopy })],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <button
      hlmBtn
      [variant]="variant()"
      [size]="label() ? 'sm' : 'icon-xs'"
      type="button"
      [attr.aria-label]="copied() ? 'Copied' : ariaLabel()"
      [title]="ariaLabel()"
      (click)="copy()"
    >
      <ng-icon [name]="copied() ? 'lucideCheck' : 'lucideCopy'" aria-hidden="true" />
      @if (label(); as text) {
        {{ copied() ? 'Copied' : text }}
      }
    </button>
  `,
})
export class CopyButton {
  private readonly notify = inject(Notify);

  readonly value = input.required<string>();
  readonly ariaLabel = input('Copy');
  /** Visible text; icon-only when empty. */
  readonly label = input('');
  readonly variant = input<'ghost' | 'outline'>('ghost');

  protected readonly copied = signal(false);
  private timer: ReturnType<typeof setTimeout> | undefined;

  protected async copy(): Promise<void> {
    try {
      await navigator.clipboard.writeText(this.value());
      this.copied.set(true);
      clearTimeout(this.timer);
      this.timer = setTimeout(() => this.copied.set(false), 1500);
    } catch {
      this.notify.error('Could not copy', new Error('The browser blocked clipboard access.'));
    }
  }
}
