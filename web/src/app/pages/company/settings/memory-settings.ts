import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  linkedSignal,
  resource,
  signal,
} from '@angular/core';
import { RouterLink } from '@angular/router';
import { NgIcon, provideIcons } from '@ng-icons/core';
import { lucideCircleAlert } from '@ng-icons/lucide';
import { HlmAlertImports } from '@spartan-ng/helm/alert';
import { HlmNativeSelectImports } from '@spartan-ng/helm/native-select';
import { HlmSkeleton } from '@spartan-ng/helm/skeleton';
import { Api } from '../../../api/generated/api';
import {
  getCompanyKeyModelAccess,
  listGatewayModels,
  putMemorySettings,
} from '../../../api/generated/functions';
import type { ModelAccessView } from '../../../api/generated/models';
import { isStatus, problemMessage, toProblem } from '../../../api/problem';
import { CurrentCompany } from '../../../core/current-company';
import { Notify } from '../../../core/notify';
import { modelChoices } from '../../../shared/agent-settings-model';
import { ErrorState } from '../../../shared/error-state';
import { SettingRow } from './setting-row';
import { SettingsData } from './settings-data';
import { UnsavedChangesBar } from './unsaved-changes-bar';

const KEY_NOTES: Record<NonNullable<ModelAccessView['keyStatus']>, string | null> = {
  OK: null,
  BUDGET_SPENT:
    'The company key’s budget is spent: memories are only cleared of duplicates until it resets.',
  BLOCKED: 'The company key is blocked: memories are only cleared of duplicates.',
  ERROR: 'The gateway refuses the company key, or cannot be reached.',
};

/** The server's own words for a 400 without field errors (it names the model in `detail`). */
function saveMessage(error: unknown): string {
  const problem = toProblem(error);
  if (problem.code === 'VALIDATION_FAILED' && problem.detail) return problem.detail;
  return problemMessage(error, {
    MODEL_NOT_ALLOWED: 'The company key does not allow this model. Widen its models first.',
  });
}

/**
 * The settings' Memory section (ADR-0063, ADR-0065): the model that tidies agents' memories once
 * every 30 days, among those the company key allows. The company key pays for it, never a person
 * or a trigger; its budget and models are under Model access › Members' budgets. Without a model,
 * only duplicates are removed. A change waits in the unsaved-changes bar.
 */
@Component({
  selector: 'app-memory-settings',
  imports: [
    RouterLink,
    NgIcon,
    HlmAlertImports,
    HlmNativeSelectImports,
    HlmSkeleton,
    ErrorState,
    SettingRow,
    UnsavedChangesBar,
  ],
  providers: [provideIcons({ lucideCircleAlert })],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="flex flex-col gap-5" data-testid="memory-settings">
      <p class="text-muted-foreground max-w-3xl px-1 text-sm text-pretty">
        Agents keep short notes for the people and triggers that turned memory on. Once every 30
        days, Tinfirm tidies each memory that changed: a small model merges related notes and
        settles contradictions, and its answer is applied only when it passes every check.
      </p>
      @if (loading()) {
        <hlm-skeleton class="h-44 w-full rounded-xl" aria-busy="true" />
      } @else if (data.memory.error()) {
        <app-error-state
          [error]="data.memory.error()"
          heading="Couldn’t load the memory settings"
          (retry)="data.memory.reload()"
        />
      } @else {
        <section class="bg-card rounded-xl border" aria-labelledby="memory-tidying-title">
          <div class="px-4 pt-4 pb-3.5 sm:px-5">
            <h2 id="memory-tidying-title" class="text-base font-bold">Tidying</h2>
            <p class="text-muted-foreground mt-0.5 text-sm">
              The company key pays for it, never a person or a trigger. Its budget and models are
              under
              <a
                class="text-primary font-semibold underline underline-offset-4"
                [routerLink]="['/c', companyId(), 'model-access']"
                [queryParams]="{ tab: 'budgets' }"
                >Model access</a
              >.
            </p>
          </div>
          <form id="memory-settings-form" novalidate (submit)="save($event)">
            <app-setting-row rowId="memory-model" label="Model" for="memory-model">
              <span help
                >Among the models the company key allows; a small one suffices, about 5,000 tokens a
                month for a busy memory. None: only duplicates are removed.</span
              >
              <hlm-native-select
                class="w-full sm:max-w-sm"
                selectId="memory-model"
                [value]="model()"
                [disabled]="saving() || !gatewayReady()"
                (valueChange)="model.set($event ?? '')"
              >
                <option hlmNativeSelectOption value="" [selected]="model() === ''">
                  None: duplicates only
                </option>
                @for (m of choices(); track m.value) {
                  <option hlmNativeSelectOption [value]="m.value" [selected]="m.value === model()">
                    {{ m.label }}
                  </option>
                }
              </hlm-native-select>
              @if (servedNote(); as note) {
                <p class="text-muted-foreground text-xs" data-testid="memory-served-note">
                  {{ note }}
                </p>
              }
              @for (note of warnings(); track note) {
                <p class="text-warning text-xs" data-testid="memory-warning">{{ note }}</p>
              }
            </app-setting-row>
          </form>
        </section>
        @if (error(); as message) {
          <div hlmAlert variant="destructive" data-testid="memory-error">
            <ng-icon name="lucideCircleAlert" aria-hidden="true" />
            <p hlmAlertDescription>{{ message }}</p>
          </div>
        }
        @if (dirty()) {
          <app-unsaved-changes-bar
            form="memory-settings-form"
            saveTestId="memory-save"
            [changes]="['Model']"
            [saving]="saving()"
            (discard)="discard()"
          />
        }
      }
    </div>
  `,
})
export class MemorySettingsSection {
  private readonly api = inject(Api);
  private readonly notify = inject(Notify);
  private readonly current = inject(CurrentCompany);
  protected readonly data = inject(SettingsData);

  protected readonly companyId = computed(() => this.current.id() ?? '');

  /** The company key: the models it allows, and its state. */
  protected readonly key = resource({
    params: () => this.current.id(),
    loader: ({ params: companyId }) => this.api.invoke(getCompanyKeyModelAccess, { companyId }),
  });
  /** The models the gateway serves; empty with why while it has not passed its test. */
  protected readonly served = resource({
    params: () => this.current.id(),
    loader: async ({ params: companyId }) => {
      try {
        return {
          models: (await this.api.invoke(listGatewayModels, { companyId })).models,
          note: null,
        };
      } catch (error) {
        if (isStatus(error, 409)) {
          return {
            models: [] as string[],
            note: 'Set up the model gateway under Model access first.',
          };
        }
        return { models: [] as string[], note: problemMessage(error) };
      }
    },
  });

  protected readonly loading = computed(
    () =>
      (this.data.memory.isLoading() && !this.data.memory.hasValue()) ||
      (this.key.isLoading() && !this.key.hasValue()) ||
      (this.served.isLoading() && !this.served.hasValue()),
  );
  private readonly saved = computed(() =>
    this.data.memory.hasValue() ? (this.data.memory.value()?.compactionModel ?? '') : '',
  );
  protected readonly model = linkedSignal(() => this.saved());
  protected readonly dirty = computed(() => this.model() !== this.saved());
  protected readonly saving = signal(false);
  protected readonly error = signal<string | null>(null);

  protected readonly gatewayReady = computed(
    () => this.key.hasValue() && !!this.key.value()?.gatewayConfigured,
  );
  protected readonly servedNote = computed(() =>
    this.served.hasValue() ? (this.served.value()?.note ?? null) : null,
  );
  protected readonly choices = computed(() =>
    modelChoices(
      this.served.hasValue() ? (this.served.value()?.models ?? []) : [],
      this.key.hasValue() ? (this.key.value()?.models ?? []) : [],
      this.model(),
    ),
  );
  /** Why memories are only cleared of duplicates now, though a model is chosen. */
  protected readonly warnings = computed(() => {
    const settings = this.data.memory.hasValue() ? this.data.memory.value() : null;
    const key = this.key.hasValue() ? this.key.value() : null;
    const notes: string[] = [];
    if (settings?.compactionModel && !settings.compactionModelAllowed && !this.dirty()) {
      notes.push(
        `The company key no longer allows ${settings.compactionModel}: memories are only cleared of duplicates until you choose another model or widen the key’s models.`,
      );
    }
    const keyNote = key?.keyStatus ? KEY_NOTES[key.keyStatus] : null;
    if (this.model() && keyNote) notes.push(keyNote);
    return notes;
  });

  protected discard(): void {
    this.model.set(this.saved());
    this.error.set(null);
  }

  protected async save(event: Event): Promise<void> {
    event.preventDefault();
    const companyId = this.current.id();
    if (!companyId || !this.dirty() || this.saving()) return;
    this.saving.set(true);
    this.error.set(null);
    try {
      const saved = await this.api.invoke(putMemorySettings, {
        companyId,
        body: { compactionModel: this.model() || null },
      });
      this.data.memory.set(saved);
      this.notify.success(
        'Saved',
        saved.compactionModel
          ? `${saved.compactionModel} tidies agents’ memories.`
          : 'Agents’ memories are only cleared of duplicates.',
      );
    } catch (error) {
      this.error.set(saveMessage(error));
    } finally {
      this.saving.set(false);
    }
  }
}
