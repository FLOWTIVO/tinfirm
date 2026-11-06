import { ChangeDetectionStrategy, Component, computed, input, model } from '@angular/core';
import { NgIcon, provideIcons } from '@ng-icons/core';
import { lucidePlus, lucideX } from '@ng-icons/lucide';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmInput } from '@spartan-ng/helm/input';
import { HlmLabel } from '@spartan-ng/helm/label';
import { HlmNativeSelectImports } from '@spartan-ng/helm/native-select';
import { HlmSwitch } from '@spartan-ng/helm/switch';
import type { FiringLimits, SystemKind, TriggerAction } from '../../../api/generated/models';
import { MAX_ACTIONS, type TriggerDraft } from './trigger-draft';
import { ACTION_LABELS, ACTIONS_WITH_VALUE, actionsFor, isJira } from './trigger-labels';

type ActionList = 'onSuccess' | 'onFailure';

/**
 * "Results" of the trigger editor (plan §4.5, step 7; the package 4 contract, §5.8 and §5.9):
 * whether results and failures are written to the issue as comments, whether the team reviews a
 * result before anything of it is written, the actions on success and on failure (Jira: move to a
 * status, attach the documents; GitHub and GitLab: labels, close), and the firings per hour. A
 * schedule writes nothing outside Tinfirm: it keeps only the review and the limit.
 */
@Component({
  selector: 'app-trigger-results-fields',
  imports: [NgIcon, HlmButton, HlmInput, HlmLabel, HlmNativeSelectImports, HlmSwitch],
  providers: [provideIcons({ lucidePlus, lucideX })],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'flex flex-col gap-4' },
  template: `
    @let r = draft().results;
    @if (tracker()) {
      <label class="flex items-start gap-3 text-sm" for="trigger-comment">
        <hlm-switch
          inputId="trigger-comment"
          class="mt-px"
          [checked]="r.comment"
          (checkedChange)="patch({ comment: $event })"
        />
        <span class="flex flex-col gap-0.5">
          <span class="font-medium">Comment the result on the issue</span>
          <span class="text-muted-foreground text-xs"
            >The summary, the next steps and links to what it made; a failure is said too.</span
          >
        </span>
      </label>
    }
    <label class="flex items-start gap-3 text-sm" for="trigger-review">
      <hlm-switch
        inputId="trigger-review"
        class="mt-px"
        [checked]="r.review"
        (checkedChange)="patch({ review: $event })"
      />
      <span class="flex flex-col gap-0.5">
        <span class="font-medium">Review each result before it is written</span>
        <span class="text-muted-foreground text-xs">
          @if (tracker()) {
            Nothing reaches the issue until the team approves it; a return with a note sends the
            agent back to work.
          } @else {
            The team approves each result, or returns it with a note.
          }
        </span>
      </span>
    </label>

    @if (tracker() && kinds().length) {
      @for (list of lists; track list.key) {
        <fieldset
          class="m-0 flex min-w-0 flex-col gap-2 border-0 p-0"
          [attr.data-actions]="list.key"
        >
          <legend class="mb-1 text-sm font-medium">{{ list.label }}</legend>
          @for (a of r[list.key]; track $index; let i = $index) {
            <div class="flex min-w-0 flex-col gap-2 sm:flex-row sm:items-center">
              <label class="sr-only" [for]="'trigger-' + list.key + '-' + i"
                >{{ list.label }}, action {{ i + 1 }}</label
              >
              <hlm-native-select
                class="w-full sm:max-w-56"
                [selectId]="'trigger-' + list.key + '-' + i"
                [value]="a.kind"
                (valueChange)="setKind(list.key, i, $any($event))"
              >
                @for (k of kinds(); track k) {
                  <option hlmNativeSelectOption [value]="k">{{ actionLabels[k] }}</option>
                }
              </hlm-native-select>
              @if (withValue.has(a.kind)) {
                <input
                  hlmInput
                  class="w-full sm:max-w-48"
                  autocomplete="off"
                  maxlength="255"
                  [attr.aria-label]="actionLabels[a.kind] + ', ' + list.label.toLowerCase()"
                  [placeholder]="a.kind === 'TRANSITION' ? 'In review' : 'needs-review'"
                  [value]="a.value ?? ''"
                  (input)="setValue(list.key, i, $any($event.target).value)"
                />
              }
              <button
                hlmBtn
                variant="ghost"
                size="icon-sm"
                type="button"
                [attr.aria-label]="'Remove ' + list.label.toLowerCase() + ' action ' + (i + 1)"
                (click)="remove(list.key, i)"
              >
                <ng-icon name="lucideX" aria-hidden="true" />
              </button>
            </div>
          }
          @if (r[list.key].length < maxActions) {
            <button
              hlmBtn
              variant="outline"
              size="sm"
              type="button"
              class="self-start"
              [attr.data-testid]="'add-' + list.key"
              (click)="add(list.key)"
            >
              <ng-icon name="lucidePlus" aria-hidden="true" />
              Add an action
            </button>
          }
        </fieldset>
      }
    }

    <div class="flex min-w-0 flex-col gap-1.5">
      <label hlmLabel for="trigger-firings">Firings per hour, at most</label>
      <input
        hlmInput
        id="trigger-firings"
        class="w-full sm:max-w-32"
        type="number"
        min="1"
        inputmode="numeric"
        [max]="limits()?.max ?? null"
        [placeholder]="limits() ? String(limits()!.default) : ''"
        [value]="draft().maxFiringsPerHour"
        [attr.aria-invalid]="!!error() || null"
        aria-describedby="trigger-firings-hint"
        (input)="setFirings($any($event.target).value)"
      />
      @if (error(); as message) {
        <p class="text-destructive text-sm" role="alert" data-error="results">{{ message }}</p>
      } @else {
        <p class="text-muted-foreground text-xs" id="trigger-firings-hint">
          Past it, events are skipped and you are told once an hour.
          @if (limits(); as l) {
            The company allows up to {{ l.max }}; empty means {{ l.default }}.
          }
        </p>
      }
    </div>
  `,
})
export class TriggerResultsFields {
  readonly draft = model.required<TriggerDraft>();
  readonly kind = input<SystemKind | null>(null);
  readonly limits = input<FiringLimits | null>(null);
  readonly error = input<string | null>(null);

  protected readonly String = String;
  protected readonly actionLabels = ACTION_LABELS;
  protected readonly withValue = ACTIONS_WITH_VALUE;
  protected readonly maxActions = MAX_ACTIONS;
  protected readonly lists: readonly { key: ActionList; label: string }[] = [
    { key: 'onSuccess', label: 'When it succeeds' },
    { key: 'onFailure', label: 'When it fails' },
  ];

  protected readonly tracker = computed(() => this.draft().source === 'TRACKER');
  protected readonly kinds = computed(() => actionsFor(this.kind()));
  protected readonly jira = computed(() => isJira(this.kind()));

  protected patch(change: Partial<TriggerDraft['results']>): void {
    this.draft.update((d) => ({ ...d, results: { ...d.results, ...change } }));
  }

  private setList(list: ActionList, fn: (actions: TriggerAction[]) => TriggerAction[]): void {
    this.draft.update((d) => ({
      ...d,
      results: { ...d.results, [list]: fn([...d.results[list]]) },
    }));
  }

  protected add(list: ActionList): void {
    this.setList(list, (actions) => [...actions, { kind: this.kinds()[0], value: null }]);
  }

  protected remove(list: ActionList, i: number): void {
    this.setList(list, (actions) => actions.filter((_, j) => j !== i));
  }

  protected setKind(list: ActionList, i: number, kind: TriggerAction['kind']): void {
    this.setList(list, (actions) =>
      actions.map((a, j) =>
        j === i ? { kind, value: ACTIONS_WITH_VALUE.has(kind) ? a.value : null } : a,
      ),
    );
  }

  protected setValue(list: ActionList, i: number, value: string): void {
    this.setList(list, (actions) => actions.map((a, j) => (j === i ? { ...a, value } : a)));
  }

  protected setFirings(value: string): void {
    this.draft.update((d) => ({ ...d, maxFiringsPerHour: value }));
  }
}
