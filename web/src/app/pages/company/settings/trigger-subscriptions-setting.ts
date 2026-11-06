import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  resource,
  signal,
} from '@angular/core';
import { NgIcon, provideIcons } from '@ng-icons/core';
import { lucideCircleAlert } from '@ng-icons/lucide';
import { HlmAlertImports } from '@spartan-ng/helm/alert';
import { HlmAlertDialogImports } from '@spartan-ng/helm/alert-dialog';
import { HlmSkeleton } from '@spartan-ng/helm/skeleton';
import { HlmSwitchImports } from '@spartan-ng/helm/switch';
import { Api } from '../../../api/generated/api';
import { getCompanySettings, updateCompanySettings } from '../../../api/generated/functions';
import { problemMessage, toProblem } from '../../../api/problem';
import { CurrentCompany } from '../../../core/current-company';
import { Notify } from '../../../core/notify';
import { can } from '../../../core/permissions';
import { ErrorState } from '../../../shared/error-state';
import { SettingRow } from './setting-row';

/**
 * Whether a trigger may pay with its author's Claude or ChatGPT subscription (`getCompanySettings`,
 * `updateCompanySettings`' `triggerSubscriptions`; ADR-0085, decision 7), in the settings' General
 * section. Off by default: a trigger pays with its payer's budget. Turning it on asks first, and
 * the question says what the company takes on: a trigger fires with nobody signed in and its work
 * goes to its audience, which the vendors' terms for a personal plan do not cover. Off saves at
 * once; a trigger that still pays with a subscription pauses at its next firing.
 */
@Component({
  selector: 'app-trigger-subscriptions-setting',
  imports: [
    NgIcon,
    HlmAlertImports,
    HlmAlertDialogImports,
    HlmSkeleton,
    HlmSwitchImports,
    ErrorState,
    SettingRow,
  ],
  providers: [provideIcons({ lucideCircleAlert })],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section
      class="bg-card rounded-xl border"
      aria-labelledby="triggers-title"
      data-testid="trigger-subscriptions"
    >
      <div class="px-4 pt-4 pb-3.5 sm:px-5">
        <h2 id="triggers-title" class="text-base font-bold">Triggers</h2>
        <p class="text-muted-foreground mt-0.5 text-sm">
          What a trigger may pay with. The switch saves at once.
        </p>
      </div>
      @if (settings.isLoading() && !settings.hasValue()) {
        <div class="border-t px-4 py-4 sm:px-5" aria-busy="true">
          <hlm-skeleton class="h-10 w-full max-w-xl" />
        </div>
      } @else if (settings.error()) {
        <div class="border-t px-4 py-4 sm:px-5">
          <app-error-state
            [error]="settings.error()"
            heading="Couldn’t load the setting"
            (retry)="settings.reload()"
          />
        </div>
      } @else {
        <app-setting-row
          rowId="trigger-subscriptions"
          kind="switch"
          label="Its author’s subscription"
        >
          <span help
            >Off, a trigger pays with its payer’s budget on the company’s gateway. On, an author may
            pay for their own triggers with their Claude or ChatGPT subscription on their personal
            runner host. A trigger fires with nobody signed in and works for its audience, which the
            vendors’ terms for a personal plan may not cover: the company that turns this on takes
            that on itself.</span
          >
          <span
            class="text-muted-foreground text-[0.8125rem] font-semibold"
            aria-hidden="true"
            data-testid="trigger-subscriptions-state"
            >{{ on() ? 'On' : 'Off' }}</span
          >
          <hlm-switch
            inputId="trigger-subscriptions-switch"
            aria-label="Triggers paid with their author’s subscription"
            aria-describedby="trigger-subscriptions-help"
            [checked]="on()"
            [disabled]="saving() || !canChange()"
            (checkedChange)="request($event)"
          />
        </app-setting-row>
        @if (error(); as message) {
          <div class="px-4 pb-4 sm:px-5">
            <div hlmAlert variant="destructive" data-testid="trigger-subscriptions-error">
              <ng-icon name="lucideCircleAlert" aria-hidden="true" />
              <p hlmAlertDescription>{{ message }}</p>
            </div>
          </div>
        }
      }
    </section>

    <hlm-alert-dialog [state]="confirmOn() ? 'open' : 'closed'" (closed)="cancelOn()">
      <hlm-alert-dialog-content *hlmAlertDialogPortal="let ctx">
        <hlm-alert-dialog-header>
          <h2 hlmAlertDialogTitle>Let triggers pay with their author’s subscription?</h2>
          <p hlmAlertDialogDescription>
            A trigger fires with nobody signed in and its work goes to its audience. Anthropic’s and
            OpenAI’s terms for a personal plan cover a person’s own use; whether they cover this is
            the company’s call, not Tinfirm’s. The author still chooses it per trigger, and only for
            triggers they pay for themselves.
          </p>
        </hlm-alert-dialog-header>
        <hlm-alert-dialog-footer>
          <button hlmAlertDialogCancel>Cancel</button>
          <button hlmAlertDialogAction (click)="turnOn()">Turn on</button>
        </hlm-alert-dialog-footer>
      </hlm-alert-dialog-content>
    </hlm-alert-dialog>
  `,
})
export class TriggerSubscriptionsSetting {
  private readonly api = inject(Api);
  private readonly notify = inject(Notify);
  private readonly current = inject(CurrentCompany);

  protected readonly canChange = computed(() => can.manageModelAccess(this.current.access()));
  protected readonly settings = resource({
    params: () => this.current.id(),
    loader: ({ params: companyId }) => this.api.invoke(getCompanySettings, { companyId }),
  });
  /** The value picked while it is asked about or saves, else the server's. */
  private readonly draft = signal<boolean | null>(null);
  protected readonly on = computed(
    () =>
      this.draft() ??
      (this.settings.hasValue() ? !!this.settings.value()?.triggerSubscriptions : false),
  );
  protected readonly saving = signal(false);
  protected readonly confirmOn = signal(false);
  protected readonly error = signal<string | null>(null);

  /** Off saves at once; on asks first. */
  protected request(value: boolean): void {
    if (this.saving() || value === this.on()) return;
    if (!value) {
      void this.save(false);
      return;
    }
    this.draft.set(true);
    this.confirmOn.set(true);
  }

  protected turnOn(): void {
    void this.save(true);
  }

  /** Closed without turning it on: the switch goes back off. */
  protected cancelOn(): void {
    this.confirmOn.set(false);
    if (!this.saving()) this.draft.set(null);
  }

  private async save(value: boolean): Promise<void> {
    const companyId = this.current.id();
    const company = this.settings.hasValue() ? this.settings.value() : null;
    if (!companyId || !company) return;
    this.draft.set(value);
    this.saving.set(true);
    this.confirmOn.set(false);
    this.error.set(null);
    try {
      // The request carries the firing limits too: read again just before, so that another
      // admin's change of them since this page loaded is not written over. The version read
      // with them makes sure of it: a change between this read and the save is refused.
      const fresh = await this.api.invoke(getCompanySettings, { companyId });
      const saved = await this.api.invoke(updateCompanySettings, {
        companyId,
        body: {
          triggerFiringsPerHour: fresh.triggerFiringsPerHour ?? company.triggerFiringsPerHour,
          triggerSubscriptions: value,
          version: fresh.version,
        },
      });
      this.settings.set(saved);
      this.notify.success(
        saved.triggerSubscriptions
          ? 'Triggers may pay with their author’s subscription'
          : 'Triggers pay with a budget',
      );
    } catch (error) {
      this.error.set(
        problemMessage(error, { 'status:403': 'Only owners and admins change this setting.' }),
      );
      // Someone else saved first: show the switch as they left it.
      if (toProblem(error).code === 'SETTINGS_CHANGED') this.settings.reload();
    } finally {
      this.draft.set(null);
      this.saving.set(false);
    }
  }
}
