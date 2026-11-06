import { ChangeDetectionStrategy, Component, computed, inject, resource } from '@angular/core';
import { NgIcon, provideIcons } from '@ng-icons/core';
import { lucideDownload, lucideTriangleAlert } from '@ng-icons/lucide';
import { HlmButton } from '@spartan-ng/helm/button';
import { Api } from '../../../api/generated/api';
import { getGmailDeployment } from '../../../api/generated/functions';
import { CurrentCompany } from '../../../core/current-company';
import { LicenceState } from '../../../core/licence';
import { Notify } from '../../../core/notify';
import { EditionBadge } from '../../../shared/edition-badge';
import { SettingRow } from './setting-row';

/**
 * The Gmail add-on (ADR-0083), in the settings' General section: its deployment file for this
 * company, which a Google Workspace administrator creates in the Google Cloud project the operator
 * named, and whether the operator configured that project. Pro and Enterprise (ADR-0077), as the Outlook
 * add-in: without the feature the section says so and offers no file, which the server refuses with
 * `403 EDITION_REQUIRED`.
 */
@Component({
  selector: 'app-gmail-add-on-setting',
  imports: [NgIcon, HlmButton, EditionBadge, SettingRow],
  providers: [provideIcons({ lucideDownload, lucideTriangleAlert })],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="bg-card rounded-xl border" aria-labelledby="gmail-title" data-testid="gmail">
      <div class="px-4 pt-4 pb-3.5 sm:px-5">
        <h2 id="gmail-title" class="flex flex-wrap items-center gap-2 text-base font-bold">
          Gmail
          @if (!licensed()) {
            <app-edition-badge feature="GMAIL_ADDON" />
          }
        </h2>
        <p class="text-muted-foreground mt-0.5 text-sm">
          Members ask agents about an email from Gmail’s side panel.
        </p>
      </div>
      <app-setting-row rowId="gmail-deployment" label="Gmail add-on">
        <span help
          >Create the deployment in your Google Cloud project with
          <code class="font-mono text-xs">gcloud workspace-add-ons deployments create</code>,
          install it for yourself to try it, then publish it to your domain from the Google
          Workspace Marketplace SDK. Each person signs in once and links their Google account.</span
        >
        @if (!licensed()) {
          <p class="text-muted-foreground m-0 text-sm text-pretty" data-testid="gmail-edition">
            The Gmail add-on needs a Pro licence. An instance admin adds a licence key under
            Licence.
          </p>
        } @else {
          <div class="flex flex-col items-start gap-2">
            <button
              hlmBtn
              variant="outline"
              type="button"
              [disabled]="!deployment.hasValue()"
              (click)="download()"
              data-testid="gmail-deployment"
            >
              <ng-icon name="lucideDownload" aria-hidden="true" />
              Download deployment file
            </button>
            @if (deployment.hasValue() && !deployment.value().configured) {
              <p
                class="text-muted-foreground flex items-start gap-1.5 text-sm"
                data-testid="gmail-not-configured"
              >
                <ng-icon
                  name="lucideTriangleAlert"
                  class="text-human mt-0.5 shrink-0"
                  aria-hidden="true"
                />
                <span
                  >Tinfirm refuses Gmail’s requests until the operator sets
                  <code class="font-mono text-xs">TINFIRM_GMAIL_SERVICE_ACCOUNT</code> and
                  <code class="font-mono text-xs">TINFIRM_GMAIL_CLIENT_ID</code> to the project’s
                  values.</span
                >
              </p>
            }
            @if (deployment.error()) {
              <p class="text-destructive text-sm" data-testid="gmail-error">
                Couldn’t load the deployment file.
              </p>
            }
          </div>
        }
      </app-setting-row>
    </section>
  `,
})
export class GmailAddOnSetting {
  private readonly api = inject(Api);
  private readonly notify = inject(Notify);
  private readonly current = inject(CurrentCompany);
  private readonly licence = inject(LicenceState);

  protected readonly licensed = computed(() => this.licence.has('GMAIL_ADDON'));

  protected readonly deployment = resource({
    params: () => (this.licensed() ? (this.current.id() ?? undefined) : undefined),
    loader: ({ params: companyId }) => this.api.invoke(getGmailDeployment, { companyId }),
  });

  protected download(): void {
    if (!this.deployment.hasValue()) return;
    const deployment = this.deployment.value();
    try {
      const url = URL.createObjectURL(
        new Blob([deployment.deployment], { type: 'application/json' }),
      );
      const link = document.createElement('a');
      link.href = url;
      link.download = deployment.fileName;
      link.click();
      setTimeout(() => URL.revokeObjectURL(url), 1000);
    } catch (error) {
      this.notify.error('Couldn’t download the deployment file', error);
    }
  }
}
