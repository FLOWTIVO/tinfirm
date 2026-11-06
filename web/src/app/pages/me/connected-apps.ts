import { ChangeDetectionStrategy, Component, inject, resource, signal } from '@angular/core';
import { NgIcon, provideIcons } from '@ng-icons/core';
import { lucideAppWindow, lucideCircleAlert, lucideUnplug } from '@ng-icons/lucide';
import { HlmAlertImports } from '@spartan-ng/helm/alert';
import { HlmAlertDialogImports } from '@spartan-ng/helm/alert-dialog';
import { HlmBadge } from '@spartan-ng/helm/badge';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmCardImports } from '@spartan-ng/helm/card';
import { HlmSkeleton } from '@spartan-ng/helm/skeleton';
import { HlmSpinner } from '@spartan-ng/helm/spinner';
import { Api } from '../../api/generated/api';
import { listOAuthGrants, revokeOAuthGrant } from '../../api/generated/functions';
import type { OAuthGrant, OAuthScopeName } from '../../api/generated/models';
import { problemMessage } from '../../api/problem';
import { Notify } from '../../core/notify';
import { ErrorState } from '../../shared/error-state';
import { Time } from '../../shared/time';

export const OAUTH_SCOPE_LABELS: Record<OAuthScopeName, string> = {
  'tinfirm:read': 'Read',
  'tinfirm:write': 'Write',
  'tinfirm:decide': 'Decide',
};

/** What a grant allows, as one label: its highest scope (each includes the lower ones). */
export function grantLevel(grant: OAuthGrant): string {
  const order: OAuthScopeName[] = ['tinfirm:decide', 'tinfirm:write', 'tinfirm:read'];
  const highest = order.find((scope) => grant.scopes.includes(scope));
  return highest ? OAUTH_SCOPE_LABELS[highest] : 'None';
}

/**
 * "Connected apps" on /me (ADR-0045): the apps the person authorized through Tinfirm's OAuth
 * authorization server, one per app and company, each with what it may do; disconnecting one stops
 * its tokens at once.
 */
@Component({
  selector: 'app-connected-apps',
  imports: [
    NgIcon,
    HlmAlertImports,
    HlmAlertDialogImports,
    HlmBadge,
    HlmButton,
    HlmCardImports,
    HlmSkeleton,
    HlmSpinner,
    ErrorState,
    Time,
  ],
  providers: [provideIcons({ lucideAppWindow, lucideCircleAlert, lucideUnplug })],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section hlmCard aria-labelledby="apps-title">
      <div hlmCardHeader>
        <h2 hlmCardTitle id="apps-title">Connected apps</h2>
        <p hlmCardDescription>
          AI clients you signed in to Tinfirm, such as Claude Code. Each acts as you in one company,
          with what you allowed.
        </p>
      </div>
      <div hlmCardContent>
        @if (grants.isLoading() && !grants.hasValue()) {
          <div class="flex flex-col gap-3" aria-busy="true">
            <span class="sr-only" role="status">Loading…</span>
            <hlm-skeleton class="h-4 w-1/2" />
            <hlm-skeleton class="h-4 w-1/3" />
          </div>
        } @else if (grants.error()) {
          <app-error-state
            [error]="grants.error()"
            heading="Couldn't load your connected apps"
            (retry)="grants.reload()"
          />
        } @else if (items().length === 0) {
          <p class="text-muted-foreground text-sm" data-testid="no-apps">
            No app is connected. An MCP client asks you here the first time it connects.
          </p>
        } @else {
          <ul class="-mx-2 flex flex-col" aria-label="Your connected apps">
            @for (grant of items(); track grant.id) {
              <li
                class="flex items-center gap-3 rounded-md px-2 py-2"
                [attr.data-testid]="'grant-' + grant.id"
              >
                <ng-icon
                  name="lucideAppWindow"
                  class="text-muted-foreground shrink-0"
                  aria-hidden="true"
                />
                <div class="flex min-w-0 flex-1 flex-col gap-0.5">
                  <p class="flex flex-wrap items-center gap-2 text-sm">
                    <span class="truncate font-medium">{{ grant.client.name }}</span>
                    @if (grant.client.domain; as domain) {
                      <span class="text-muted-foreground font-mono text-xs">{{ domain }}</span>
                    }
                    <span hlmBadge variant="outline" data-testid="grant-level">{{
                      level(grant)
                    }}</span>
                  </p>
                  <p class="text-muted-foreground text-xs" data-testid="grant-meta">
                    In {{ grant.companyName }} · allowed <app-time [value]="grant.grantedAt" />
                    ·
                    @if (grant.lastUsedAt; as lastUsedAt) {
                      last used <app-time [value]="lastUsedAt" />
                    } @else {
                      never used
                    }
                  </p>
                </div>
                <button
                  hlmBtn
                  variant="ghost"
                  size="icon-sm"
                  type="button"
                  [attr.aria-label]="
                    'Disconnect ' + grant.client.name + ' from ' + grant.companyName
                  "
                  [title]="'Disconnect ' + grant.client.name"
                  (click)="askRevoke(grant)"
                >
                  <ng-icon name="lucideUnplug" aria-hidden="true" />
                </button>
              </li>
            }
          </ul>
        }
      </div>
    </section>

    <hlm-alert-dialog [state]="revokeOpen() ? 'open' : 'closed'" (closed)="revokeOpen.set(false)">
      <hlm-alert-dialog-content *hlmAlertDialogPortal="let ctx">
        <hlm-alert-dialog-header>
          <h2 hlmAlertDialogTitle>
            Disconnect {{ revoking()?.client?.name }} from {{ revoking()?.companyName }}?
          </h2>
          <p hlmAlertDialogDescription>
            It stops working there at once. To use it again, connect it again from the app.
          </p>
        </hlm-alert-dialog-header>
        @if (revokeError(); as message) {
          <div hlmAlert variant="destructive">
            <ng-icon name="lucideCircleAlert" aria-hidden="true" />
            <p hlmAlertDescription>{{ message }}</p>
          </div>
        }
        <hlm-alert-dialog-footer>
          <button hlmAlertDialogCancel [disabled]="revokeBusy()">Cancel</button>
          <button
            hlmAlertDialogAction
            variant="destructive"
            [disabled]="revokeBusy()"
            (click)="confirmRevoke()"
          >
            @if (revokeBusy()) {
              <hlm-spinner aria-hidden="true" />
            }
            Disconnect
          </button>
        </hlm-alert-dialog-footer>
      </hlm-alert-dialog-content>
    </hlm-alert-dialog>
  `,
})
export class ConnectedApps {
  private readonly api = inject(Api);
  private readonly notify = inject(Notify);

  protected readonly grants = resource({
    loader: () => this.api.invoke(listOAuthGrants, {}),
  });

  protected items(): readonly OAuthGrant[] {
    return this.grants.hasValue() ? this.grants.value() : [];
  }

  protected level(grant: OAuthGrant): string {
    return grantLevel(grant);
  }

  protected readonly revoking = signal<OAuthGrant | null>(null);
  protected readonly revokeOpen = signal(false);
  protected readonly revokeBusy = signal(false);
  protected readonly revokeError = signal<string | null>(null);

  protected askRevoke(grant: OAuthGrant): void {
    this.revoking.set(grant);
    this.revokeError.set(null);
    this.revokeOpen.set(true);
  }

  protected async confirmRevoke(): Promise<void> {
    const grant = this.revoking();
    if (!grant || this.revokeBusy()) return;
    this.revokeBusy.set(true);
    this.revokeError.set(null);
    try {
      await this.api.invoke(revokeOAuthGrant, { grantId: grant.id });
      this.revokeOpen.set(false);
      this.grants.update((list) => (list ?? []).filter((g) => g.id !== grant.id));
      this.notify.success(
        `${grant.client.name} disconnected`,
        `It no longer works in ${grant.companyName}.`,
      );
    } catch (error) {
      this.revokeError.set(problemMessage(error));
    } finally {
      this.revokeBusy.set(false);
    }
  }
}
