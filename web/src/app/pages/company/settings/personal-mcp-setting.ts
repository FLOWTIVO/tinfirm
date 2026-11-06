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
import { getSystemSettings, replaceSystemSettings } from '../../../api/generated/functions';
import { problemMessage } from '../../../api/problem';
import { CurrentCompany } from '../../../core/current-company';
import { Notify } from '../../../core/notify';
import { can } from '../../../core/permissions';
import { ErrorState } from '../../../shared/error-state';
import { SettingRow } from './setting-row';

/**
 * Whether members may register and use their own MCP servers (`getSystemSettings`,
 * `replaceSystemSettings`), in the settings' General section. The switch saves at once; turning it
 * off asks first, and the question says what off does: members' servers stay, but bind for nobody,
 * and registering one is refused.
 */
@Component({
  selector: 'app-personal-mcp-setting',
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
      aria-labelledby="members-title"
      data-testid="personal-mcp"
    >
      <div class="px-4 pt-4 pb-3.5 sm:px-5">
        <h2 id="members-title" class="text-base font-bold">Members</h2>
        <p class="text-muted-foreground mt-0.5 text-sm">
          What members may bring to their own conversations. Switches save at once.
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
        <app-setting-row rowId="personal-mcp" kind="switch" label="Their own MCP servers">
          <span help
            >A member registers a server under Accounts and uses its tools with the agents they talk
            to. Only they see it, and only their conversations use it.</span
          >
          <span
            class="text-muted-foreground text-[0.8125rem] font-semibold"
            aria-hidden="true"
            data-testid="personal-mcp-state"
            >{{ on() ? 'On' : 'Off' }}</span
          >
          <hlm-switch
            inputId="personal-mcp-servers"
            aria-label="Members’ own MCP servers"
            aria-describedby="personal-mcp-help"
            [checked]="on()"
            [disabled]="saving() || !canChange()"
            (checkedChange)="request($event)"
          />
        </app-setting-row>
        @if (error(); as message) {
          <div class="px-4 pb-4 sm:px-5">
            <div hlmAlert variant="destructive" data-testid="personal-mcp-error">
              <ng-icon name="lucideCircleAlert" aria-hidden="true" />
              <p hlmAlertDescription>{{ message }}</p>
            </div>
          </div>
        }
      }
    </section>

    <hlm-alert-dialog [state]="confirmOff() ? 'open' : 'closed'" (closed)="cancelOff()">
      <hlm-alert-dialog-content *hlmAlertDialogPortal="let ctx">
        <hlm-alert-dialog-header>
          <h2 hlmAlertDialogTitle>Turn off members’ own MCP servers?</h2>
          <p hlmAlertDialogDescription>
            The servers members registered stay, but no conversation uses them, and nobody can
            register a new one until you turn this on again.
          </p>
        </hlm-alert-dialog-header>
        <hlm-alert-dialog-footer>
          <button hlmAlertDialogCancel>Cancel</button>
          <button hlmAlertDialogAction (click)="turnOff()">Turn off</button>
        </hlm-alert-dialog-footer>
      </hlm-alert-dialog-content>
    </hlm-alert-dialog>
  `,
})
export class PersonalMcpSetting {
  private readonly api = inject(Api);
  private readonly notify = inject(Notify);
  private readonly current = inject(CurrentCompany);

  protected readonly canChange = computed(() => can.manageSystems(this.current.access()));
  protected readonly settings = resource({
    params: () => this.current.id(),
    loader: ({ params: companyId }) => this.api.invoke(getSystemSettings, { companyId }),
  });
  /** The value picked while it is asked about or saves, else the server's. */
  private readonly draft = signal<boolean | null>(null);
  protected readonly on = computed(
    () =>
      this.draft() ??
      (this.settings.hasValue() ? !!this.settings.value()?.personalMcpServers : true),
  );
  protected readonly saving = signal(false);
  protected readonly confirmOff = signal(false);
  protected readonly error = signal<string | null>(null);

  /** On saves at once; off asks first. */
  protected request(value: boolean): void {
    if (this.saving() || value === this.on()) return;
    if (value) {
      void this.save(true);
      return;
    }
    this.draft.set(false);
    this.confirmOff.set(true);
  }

  protected turnOff(): void {
    void this.save(false);
  }

  /** Closed without turning it off: the switch goes back on. */
  protected cancelOff(): void {
    this.confirmOff.set(false);
    if (!this.saving()) this.draft.set(null);
  }

  private async save(value: boolean): Promise<void> {
    const companyId = this.current.id();
    if (!companyId) return;
    this.draft.set(value);
    this.saving.set(true);
    this.confirmOff.set(false);
    this.error.set(null);
    try {
      const saved = await this.api.invoke(replaceSystemSettings, {
        companyId,
        body: { personalMcpServers: value },
      });
      this.settings.set(saved);
      this.notify.success(
        saved.personalMcpServers
          ? 'Members’ own MCP servers are on'
          : 'Members’ own MCP servers are off',
      );
    } catch (error) {
      this.error.set(
        problemMessage(error, { 'status:403': 'Only owners and admins change this setting.' }),
      );
    } finally {
      this.draft.set(null);
      this.saving.set(false);
    }
  }
}
