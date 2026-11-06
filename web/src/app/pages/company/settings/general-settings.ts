import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  linkedSignal,
  signal,
} from '@angular/core';
import { disabled, form, FormField, maxLength, required, submit } from '@angular/forms/signals';
import { NgIcon, provideIcons } from '@ng-icons/core';
import { lucideCircleAlert } from '@ng-icons/lucide';
import { HlmAlertImports } from '@spartan-ng/helm/alert';
import { HlmFieldImports } from '@spartan-ng/helm/field';
import { HlmInput } from '@spartan-ng/helm/input';
import { HlmSkeleton } from '@spartan-ng/helm/skeleton';
import { Api } from '../../../api/generated/api';
import { updateCompany } from '../../../api/generated/functions';
import { mapSubmissionError } from '../../../api/problem';
import { CurrentCompany } from '../../../core/current-company';
import { Notify } from '../../../core/notify';
import { can } from '../../../core/permissions';
import { Session } from '../../../core/session';
import { CopyButton } from '../../../shared/copy-button';
import { firstError } from '../../../shared/forms';
import { Time } from '../../../shared/time';
import { GmailAddOnSetting } from './gmail-add-on-setting';
import { OutlookAddInSetting } from './outlook-add-in-setting';
import { PersonalMcpSetting } from './personal-mcp-setting';
import { SettingRow } from './setting-row';
import { TriggerSubscriptionsSetting } from './trigger-subscriptions-setting';
import { UnsavedChangesBar } from './unsaved-changes-bar';

const NAME_MAX = 100;

/**
 * The settings' General section: what the company is called and how the API knows it, and what
 * members may bring to their own conversations. A renamed company waits in the unsaved-changes bar;
 * the switch saves at once.
 */
@Component({
  selector: 'app-general-settings',
  imports: [
    FormField,
    NgIcon,
    HlmAlertImports,
    HlmFieldImports,
    HlmInput,
    HlmSkeleton,
    CopyButton,
    Time,
    OutlookAddInSetting,
    GmailAddOnSetting,
    PersonalMcpSetting,
    SettingRow,
    TriggerSubscriptionsSetting,
    UnsavedChangesBar,
  ],
  providers: [provideIcons({ lucideCircleAlert })],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="flex flex-col gap-5">
      <section class="bg-card rounded-xl border" aria-labelledby="company-title">
        <div class="px-4 pt-4 pb-3.5 sm:px-5">
          <h2 id="company-title" class="text-base font-bold">Company</h2>
          <p class="text-muted-foreground mt-0.5 text-sm">
            What members and the API know the company by.
          </p>
        </div>
        @if (company(); as c) {
          <form id="rename-form" novalidate (submit)="onSubmit($event)">
            <app-setting-row rowId="company-name" label="Name" for="company-name">
              <span help>Shown to members everywhere in Tinfirm and on the sign-in page.</span>
              @if (formError(); as message) {
                <div hlmAlert variant="destructive" class="w-full">
                  <ng-icon name="lucideCircleAlert" aria-hidden="true" />
                  <p hlmAlertDescription>{{ message }}</p>
                </div>
              }
              <div hlmField class="sm:max-w-md">
                <input
                  hlmInput
                  id="company-name"
                  autocomplete="organization"
                  aria-describedby="company-name-help company-name-count"
                  [formField]="renameForm.name"
                />
                <hlm-field-error>{{ firstError(renameForm.name) }}</hlm-field-error>
              </div>
              <span id="company-name-count" class="text-faint font-mono text-xs tabular-nums"
                >{{ model().name.length }} / {{ nameMax }}</span
              >
              @if (!canRename()) {
                <p class="text-muted-foreground text-xs">
                  Only owners and admins can rename the company.
                </p>
              }
            </app-setting-row>
          </form>
          <app-setting-row rowId="company-id" label="Company ID">
            <span help>How the API and the company’s links name it.</span>
            <div class="flex max-w-full min-w-0 items-center gap-1.5">
              <code
                class="bg-muted/60 block min-w-0 truncate rounded-md border px-2.5 py-1.5 text-xs"
                data-testid="company-id"
                >{{ c.id }}</code
              >
              <app-copy-button [value]="c.id" ariaLabel="Copy the company ID" />
            </div>
          </app-setting-row>
          <app-setting-row rowId="company-created" label="Created">
            <span class="text-sm"><app-time [value]="c.createdAt" mode="absolute" /></span>
          </app-setting-row>
        } @else {
          <div class="flex flex-col gap-3 border-t px-4 py-4 sm:px-5" aria-busy="true">
            <hlm-skeleton class="h-9 w-full max-w-md" />
            <hlm-skeleton class="h-4 w-80" />
            <hlm-skeleton class="h-4 w-48" />
          </div>
        }
      </section>

      <app-personal-mcp-setting />

      <app-trigger-subscriptions-setting />

      <app-outlook-add-in-setting />

      <app-gmail-add-on-setting />

      @if (renamed()) {
        <app-unsaved-changes-bar
          form="rename-form"
          [changes]="['Name']"
          [saving]="renameForm().submitting()"
          (discard)="discard()"
        />
      }
    </div>
  `,
})
export class GeneralSettings {
  private readonly api = inject(Api);
  private readonly notify = inject(Notify);
  private readonly session = inject(Session);
  private readonly current = inject(CurrentCompany);

  protected readonly firstError = firstError;
  protected readonly nameMax = NAME_MAX;
  protected readonly canRename = computed(() => can.renameCompany(this.current.access()));
  protected readonly company = computed(() => {
    const res = this.current.company;
    const value = res.hasValue() ? res.value() : undefined;
    return value?.id === this.current.id() ? value : undefined;
  });

  /** Follows the loaded company; edits stay local until saved. */
  protected readonly model = linkedSignal(() => ({ name: this.company()?.name ?? '' }));
  protected readonly renameForm = form(this.model, (path) => {
    required(path.name, { message: 'Enter a name.' });
    maxLength(path.name, NAME_MAX, { message: `At most ${NAME_MAX} characters.` });
    disabled(path.name, { when: () => !this.canRename() });
  });
  protected readonly formError = signal<string | null>(null);

  /** The name differs from the company's: the bar offers to save it. */
  protected readonly renamed = computed(() => {
    const company = this.company();
    return !!company && this.canRename() && this.model().name.trim() !== company.name;
  });

  protected discard(): void {
    this.model.set({ name: this.company()?.name ?? '' });
    this.renameForm().reset();
    this.formError.set(null);
  }

  protected onSubmit(event: Event): void {
    event.preventDefault();
    const companyId = this.current.id();
    if (!companyId || !this.canRename() || !this.renamed()) return;
    this.formError.set(null);
    void submit(this.renameForm, async () => {
      try {
        const updated = await this.api.invoke(updateCompany, {
          companyId,
          body: { name: this.model().name.trim() },
        });
        this.current.company.set(updated);
        this.renameForm().reset();
        this.notify.success('Company renamed', `It's now called ${updated.name}.`);
        void this.session.refresh();
        return undefined;
      } catch (error) {
        const failure = mapSubmissionError(this.renameForm, error, {
          overrides: { 'status:403': 'Only owners and admins can rename the company.' },
        });
        this.formError.set(failure.message);
        return failure.fieldErrors;
      }
    });
  }
}
