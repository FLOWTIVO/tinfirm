import { ChangeDetectionStrategy, Component, inject, resource, signal } from '@angular/core';
import { NgIcon, provideIcons } from '@ng-icons/core';
import { lucideCircleAlert, lucideMail, lucideUnplug } from '@ng-icons/lucide';
import { HlmAlertImports } from '@spartan-ng/helm/alert';
import { HlmAlertDialogImports } from '@spartan-ng/helm/alert-dialog';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmCardImports } from '@spartan-ng/helm/card';
import { HlmSkeleton } from '@spartan-ng/helm/skeleton';
import { HlmSpinner } from '@spartan-ng/helm/spinner';
import { Api } from '../../api/generated/api';
import { deleteGmailLink, listMyGmailLinks } from '../../api/generated/functions';
import type { GmailLink } from '../../api/generated/models';
import { problemMessage } from '../../api/problem';
import { Notify } from '../../core/notify';
import { ErrorState } from '../../shared/error-state';
import { Time } from '../../shared/time';

/**
 * The Google accounts the person linked for the Gmail add-on (ADR-0083), on /me, one per account
 * and company; disconnecting one makes Gmail ask that account to sign in again. Nothing shows while
 * the person has none.
 */
@Component({
  selector: 'app-gmail-links',
  imports: [
    NgIcon,
    HlmAlertImports,
    HlmAlertDialogImports,
    HlmButton,
    HlmCardImports,
    HlmSkeleton,
    HlmSpinner,
    ErrorState,
    Time,
  ],
  providers: [provideIcons({ lucideCircleAlert, lucideMail, lucideUnplug })],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (links.error() || items().length > 0 || (links.isLoading() && !links.hasValue())) {
      <section hlmCard aria-labelledby="gmail-links-title" data-testid="gmail-links">
        <div hlmCardHeader>
          <h2 hlmCardTitle id="gmail-links-title">Gmail</h2>
          <p hlmCardDescription>
            Google accounts you linked to ask agents from Gmail’s side panel. Each acts as you in
            one company.
          </p>
        </div>
        <div hlmCardContent>
          @if (links.isLoading() && !links.hasValue()) {
            <div class="flex flex-col gap-3" aria-busy="true">
              <span class="sr-only" role="status">Loading…</span>
              <hlm-skeleton class="h-4 w-1/2" />
            </div>
          } @else if (links.error()) {
            <app-error-state
              [error]="links.error()"
              heading="Couldn't load your Gmail links"
              (retry)="links.reload()"
            />
          } @else {
            <ul class="-mx-2 flex flex-col" aria-label="Your linked Google accounts">
              @for (link of items(); track link.id) {
                <li
                  class="flex items-center gap-3 rounded-md px-2 py-2"
                  [attr.data-testid]="'gmail-link-' + link.id"
                >
                  <ng-icon
                    name="lucideMail"
                    class="text-muted-foreground shrink-0"
                    aria-hidden="true"
                  />
                  <div class="flex min-w-0 flex-1 flex-col gap-0.5">
                    <p class="truncate text-sm font-medium">
                      {{ link.googleEmail ?? 'A Google account' }}
                    </p>
                    <p class="text-muted-foreground text-xs" data-testid="gmail-link-meta">
                      In {{ link.companyName }} · linked <app-time [value]="link.linkedAt" /> · last
                      used <app-time [value]="link.lastUsedAt" />
                    </p>
                  </div>
                  <button
                    hlmBtn
                    variant="ghost"
                    size="icon-sm"
                    type="button"
                    [attr.aria-label]="
                      'Disconnect ' +
                      (link.googleEmail ?? 'the Google account') +
                      ' from ' +
                      link.companyName
                    "
                    [title]="'Disconnect ' + (link.googleEmail ?? 'the Google account')"
                    (click)="askDisconnect(link)"
                  >
                    <ng-icon name="lucideUnplug" aria-hidden="true" />
                  </button>
                </li>
              }
            </ul>
          }
        </div>
      </section>
    }

    <hlm-alert-dialog
      [state]="disconnectOpen() ? 'open' : 'closed'"
      (closed)="disconnectOpen.set(false)"
    >
      <hlm-alert-dialog-content *hlmAlertDialogPortal="let ctx">
        <hlm-alert-dialog-header>
          <h2 hlmAlertDialogTitle>
            Disconnect {{ disconnecting()?.googleEmail ?? 'this Google account' }}?
          </h2>
          <p hlmAlertDialogDescription>
            Gmail stops acting as you in {{ disconnecting()?.companyName }} at once, and asks you to
            sign in again there.
          </p>
        </hlm-alert-dialog-header>
        @if (disconnectError(); as message) {
          <div hlmAlert variant="destructive">
            <ng-icon name="lucideCircleAlert" aria-hidden="true" />
            <p hlmAlertDescription>{{ message }}</p>
          </div>
        }
        <hlm-alert-dialog-footer>
          <button hlmAlertDialogCancel [disabled]="disconnectBusy()">Cancel</button>
          <button
            hlmAlertDialogAction
            variant="destructive"
            [disabled]="disconnectBusy()"
            (click)="confirmDisconnect()"
            data-testid="gmail-link-disconnect"
          >
            @if (disconnectBusy()) {
              <hlm-spinner aria-hidden="true" />
            }
            Disconnect
          </button>
        </hlm-alert-dialog-footer>
      </hlm-alert-dialog-content>
    </hlm-alert-dialog>
  `,
})
export class GmailLinks {
  private readonly api = inject(Api);
  private readonly notify = inject(Notify);

  protected readonly links = resource({
    loader: () => this.api.invoke(listMyGmailLinks, {}),
  });

  protected items(): readonly GmailLink[] {
    return this.links.hasValue() ? this.links.value().items : [];
  }

  protected readonly disconnecting = signal<GmailLink | null>(null);
  protected readonly disconnectOpen = signal(false);
  protected readonly disconnectBusy = signal(false);
  protected readonly disconnectError = signal<string | null>(null);

  protected askDisconnect(link: GmailLink): void {
    this.disconnecting.set(link);
    this.disconnectError.set(null);
    this.disconnectOpen.set(true);
  }

  protected async confirmDisconnect(): Promise<void> {
    const link = this.disconnecting();
    if (!link || this.disconnectBusy()) return;
    this.disconnectBusy.set(true);
    this.disconnectError.set(null);
    try {
      await this.api.invoke(deleteGmailLink, { companyId: link.companyId, linkId: link.id });
      this.disconnectOpen.set(false);
      this.links.update((list) =>
        list ? { ...list, items: list.items.filter((l) => l.id !== link.id) } : list,
      );
      this.notify.success(
        `${link.googleEmail ?? 'The Google account'} disconnected`,
        `Gmail no longer acts as you in ${link.companyName}.`,
      );
    } catch (error) {
      this.disconnectError.set(problemMessage(error));
    } finally {
      this.disconnectBusy.set(false);
    }
  }
}
