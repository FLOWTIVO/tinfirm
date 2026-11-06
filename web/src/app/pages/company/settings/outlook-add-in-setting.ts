import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { NgIcon, provideIcons } from '@ng-icons/core';
import { lucideDownload } from '@ng-icons/lucide';
import { HlmButton } from '@spartan-ng/helm/button';
import { Api } from '../../../api/generated/api';
import { getOutlookManifest } from '../../../api/generated/functions';
import { CurrentCompany } from '../../../core/current-company';
import { LicenceState } from '../../../core/licence';
import { Notify } from '../../../core/notify';
import { EditionBadge } from '../../../shared/edition-badge';
import { SettingRow } from './setting-row';

/**
 * The Outlook add-in (ADR-0074), in the settings' General section: its manifest for this company,
 * which an Exchange administrator uploads in the Microsoft 365 admin center, or a person adds from
 * a file under "My add-ins" in Outlook. Pro and Enterprise (ADR-0077): without the feature the
 * section says so and offers no manifest, which the server refuses with `403 EDITION_REQUIRED`.
 */
@Component({
  selector: 'app-outlook-add-in-setting',
  imports: [NgIcon, HlmButton, EditionBadge, SettingRow],
  providers: [provideIcons({ lucideDownload })],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section
      class="bg-card rounded-xl border"
      aria-labelledby="outlook-title"
      data-testid="outlook"
    >
      <div class="px-4 pt-4 pb-3.5 sm:px-5">
        <h2 id="outlook-title" class="flex flex-wrap items-center gap-2 text-base font-bold">
          Outlook
          @if (!licensed()) {
            <app-edition-badge feature="OUTLOOK_ADDIN" />
          }
        </h2>
        <p class="text-muted-foreground mt-0.5 text-sm">
          Members ask agents about an email without leaving Outlook.
        </p>
      </div>
      <app-setting-row rowId="outlook-manifest" label="Outlook add-in">
        <span help
          >Upload the manifest in the Microsoft 365 admin center under Integrated apps to give the
          add-in to your organisation, or add it from a file under My add-ins in Outlook. It works
          in Outlook for Windows, Mac and the web. Each person signs in once and allows it to act as
          them.</span
        >
        @if (licensed()) {
          <button
            hlmBtn
            variant="outline"
            type="button"
            [disabled]="downloading()"
            (click)="download()"
            data-testid="outlook-manifest"
          >
            <ng-icon name="lucideDownload" aria-hidden="true" />
            Download manifest
          </button>
        } @else {
          <p class="text-muted-foreground m-0 text-sm text-pretty" data-testid="outlook-edition">
            The Outlook add-in needs a Pro licence. An instance admin adds a licence key under
            Licence.
          </p>
        }
      </app-setting-row>
    </section>
  `,
})
export class OutlookAddInSetting {
  private readonly api = inject(Api);
  private readonly notify = inject(Notify);
  private readonly current = inject(CurrentCompany);

  private readonly licence = inject(LicenceState);
  protected readonly licensed = computed(() => this.licence.has('OUTLOOK_ADDIN'));
  protected readonly downloading = signal(false);

  protected async download(): Promise<void> {
    const companyId = this.current.id();
    if (!companyId || this.downloading()) return;
    this.downloading.set(true);
    try {
      const manifest = await this.api.invoke(getOutlookManifest, { companyId });
      const url = URL.createObjectURL(new Blob([manifest.manifest], { type: 'application/xml' }));
      const link = document.createElement('a');
      link.href = url;
      link.download = manifest.fileName;
      link.click();
      setTimeout(() => URL.revokeObjectURL(url), 1000);
    } catch (error) {
      this.notify.error('Couldn’t download the manifest', error);
    } finally {
      this.downloading.set(false);
    }
  }
}
