import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import { NgIcon, provideIcons } from '@ng-icons/core';
import { lucideKeyRound, lucideLock } from '@ng-icons/lucide';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmInput } from '@spartan-ng/helm/input';
import { HlmSpinner } from '@spartan-ng/helm/spinner';
import { Api } from '../../../api/generated/api';
import { deleteMyModelKey, putMyModelKey } from '../../../api/generated/functions';
import type { ModelAccessView } from '../../../api/generated/models';
import { hasProblemCode, problemMessage } from '../../../api/problem';
import { Notify } from '../../../core/notify';
import { providedKeyState } from '../model-access/model-access-view';

const ERRORS = {
  'status:404': 'You have no model access in this company.',
};

/**
 * Accounts › Budget, at a gateway whose keys its team provides (ADR-0094): the reader's key there.
 * Where the company lets members enter theirs (`canEnterOwnKey`), they paste the key the gateway
 * team gave them, which the server checks with the gateway before it keeps it, or remove the one
 * they entered; a key an admin entered is shown, not changed. The key is never shown back, only
 * its last four characters.
 */
@Component({
  selector: 'app-my-model-key',
  imports: [NgIcon, HlmButton, HlmInput, HlmSpinner],
  providers: [provideIcons({ lucideKeyRound, lucideLock })],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section
      class="bg-card flex flex-col gap-3 rounded-xl border p-5"
      data-testid="my-model-key"
      aria-labelledby="my-model-key-heading"
    >
      <div class="flex flex-col gap-0.5">
        <h2 id="my-model-key-heading" class="flex items-center gap-2 text-base font-bold">
          <ng-icon name="lucideKeyRound" size="16" aria-hidden="true" />Your model key
        </h2>
        <p class="text-muted-foreground text-sm text-pretty">
          The key your company’s gateway team made for you pays for your work: its budget and models
          are set at the gateway.
        </p>
      </div>
      <p class="text-sm" data-testid="my-model-key-state">{{ state() }}</p>
      @if (access().canEnterOwnKey) {
        <div class="flex flex-wrap items-center gap-2">
          <input
            hlmInput
            id="my-model-key"
            type="password"
            class="min-w-0 flex-1 font-mono sm:max-w-md"
            autocomplete="new-password"
            spellcheck="false"
            aria-labelledby="my-model-key-heading"
            aria-describedby="my-model-key-error"
            [placeholder]="access().keyProvided ? 'Type to replace' : 'Paste your key'"
            [value]="key()"
            [attr.aria-invalid]="error() ? 'true' : null"
            (input)="key.set($any($event.target).value)"
          />
          <button
            hlmBtn
            type="button"
            data-testid="save-my-key"
            [disabled]="busy() || !key().trim()"
            (click)="save()"
          >
            @if (busy()) {
              <hlm-spinner aria-hidden="true" />
            }
            Save key
          </button>
          @if (access().keyProvided) {
            <button
              hlmBtn
              type="button"
              variant="ghost"
              data-testid="remove-my-key"
              [disabled]="busy()"
              (click)="remove()"
            >
              Remove
            </button>
          }
        </div>
        <p
          class="text-muted-foreground m-0 flex items-center gap-1.5 text-xs"
          data-testid="my-model-key-vault"
        >
          <ng-icon name="lucideLock" size="12" aria-hidden="true" />Kept in the company’s vault and
          never shown again.
        </p>
      } @else if (access().keyProvided && !access().keyProvidedBySelf) {
        <p class="text-muted-foreground m-0 text-xs">An admin entered it; ask them to change it.</p>
      }
      <p
        id="my-model-key-error"
        class="text-destructive m-0 text-xs font-medium empty:hidden"
        role="alert"
      >
        {{ error() ?? '' }}
      </p>
    </section>
  `,
})
export class MyModelKey {
  private readonly api = inject(Api);
  private readonly notify = inject(Notify);

  readonly companyId = input.required<string>();
  readonly access = input.required<ModelAccessView>();
  readonly changed = output<ModelAccessView>();

  protected readonly key = signal('');
  protected readonly error = signal<string | null>(null);
  protected readonly busy = signal(false);
  protected readonly state = computed(() => providedKeyState(this.access()));

  protected async save(): Promise<void> {
    const key = this.key().trim();
    if (!key || this.busy()) return;
    this.busy.set(true);
    this.error.set(null);
    try {
      const updated = await this.api.invoke(putMyModelKey, {
        companyId: this.companyId(),
        body: { key },
      });
      this.key.set('');
      this.changed.emit(updated);
      this.notify.success('Your key is saved', 'The gateway accepted it.');
    } catch (error) {
      this.error.set(
        hasProblemCode(error, 'MODEL_KEY_REFUSED')
          ? 'The gateway refused this key. Check it with your gateway team.'
          : problemMessage(error, ERRORS),
      );
    } finally {
      this.busy.set(false);
    }
  }

  protected async remove(): Promise<void> {
    if (this.busy()) return;
    this.busy.set(true);
    this.error.set(null);
    try {
      const updated = await this.api.invoke(deleteMyModelKey, { companyId: this.companyId() });
      this.changed.emit(updated);
      this.notify.success('Your key is removed', 'Your work waits until a key is entered again.');
    } catch (error) {
      this.error.set(problemMessage(error, ERRORS));
    } finally {
      this.busy.set(false);
    }
  }
}
