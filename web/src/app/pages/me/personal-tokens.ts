import { ChangeDetectionStrategy, Component, inject, resource, signal } from '@angular/core';
import { NgIcon, provideIcons } from '@ng-icons/core';
import {
  lucideCircleAlert,
  lucideKeyRound,
  lucidePlus,
  lucideRefreshCw,
  lucideTrash2,
} from '@ng-icons/lucide';
import { HlmAlertImports } from '@spartan-ng/helm/alert';
import { HlmAlertDialogImports } from '@spartan-ng/helm/alert-dialog';
import { HlmBadge } from '@spartan-ng/helm/badge';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmCardImports } from '@spartan-ng/helm/card';
import { HlmSkeleton } from '@spartan-ng/helm/skeleton';
import { HlmSpinner } from '@spartan-ng/helm/spinner';
import { Api } from '../../api/generated/api';
import { listOwnTokens, renewOwnToken, revokeOwnToken } from '../../api/generated/functions';
import type { Membership, PersonalAccessToken } from '../../api/generated/models';
import { problemMessage } from '../../api/problem';
import { Notify } from '../../core/notify';
import { Session } from '../../core/session';
import { ErrorState } from '../../shared/error-state';
import { Time } from '../../shared/time';
import { CreateTokenDialog, SCOPE_LABELS } from './create-token-dialog';

/** Whether a token's expiry has passed. */
export function isExpired(token: PersonalAccessToken, now: Date = new Date()): boolean {
  return !!token.expiresAt && new Date(token.expiresAt).getTime() <= now.getTime();
}

/**
 * The companies a token reaches, in words: all of the user's for a token without a list, else the
 * names of those the user is still a member of (a company they left revoked the token).
 */
export function tokenCompanies(
  token: Pick<PersonalAccessToken, 'companyIds'>,
  memberships: readonly Membership[],
): string {
  if (!token.companyIds) return 'All your companies';
  const names = memberships
    .filter((m) => token.companyIds!.includes(m.companyId))
    .map((m) => m.companyName);
  return names.length ? names.join(', ') : 'No company of yours';
}

/**
 * The "Personal access tokens" section of /me: list, create (shown once), renew and revoke. Each
 * token says when it expires (90 days after its creation or its last renewal, the security review
 * of 2026-10-27, D9) and which companies it reaches; Renew gives it another 90 days from now, and
 * an expired token is not renewed (`409 TOKEN_EXPIRED`): it says to create a new one.
 */
@Component({
  selector: 'app-personal-tokens',
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
    CreateTokenDialog,
  ],
  providers: [
    provideIcons({ lucideCircleAlert, lucideKeyRound, lucidePlus, lucideRefreshCw, lucideTrash2 }),
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section hlmCard aria-labelledby="tokens-title">
      <div
        hlmCardHeader
        class="flex flex-wrap items-start justify-between gap-3 [&>button]:-my-[5px]"
      >
        <div class="flex min-w-0 flex-col gap-1.5">
          <h2 hlmCardTitle id="tokens-title">Personal access tokens</h2>
          <p hlmCardDescription>
            For scripts, CI and AI clients on a company's MCP endpoint. A token acts as you; the
            rest of Tinfirm still needs you signed in here.
          </p>
        </div>
        <button hlmBtn size="sm" variant="outline" type="button" (click)="createOpen.set(true)">
          <ng-icon name="lucidePlus" aria-hidden="true" />
          New token
        </button>
      </div>
      <div hlmCardContent>
        @if (tokens.isLoading() && !tokens.hasValue()) {
          <div class="flex flex-col gap-3" aria-busy="true">
            <span class="sr-only" role="status">Loading…</span>
            <hlm-skeleton class="h-4 w-1/2" />
            <hlm-skeleton class="h-4 w-1/3" />
          </div>
        } @else if (tokens.error()) {
          <app-error-state
            [error]="tokens.error()"
            heading="Couldn't load your tokens"
            (retry)="tokens.reload()"
          />
        } @else if (items().length === 0) {
          <p class="text-muted-foreground text-sm" data-testid="no-tokens">
            You have no tokens. Create one for a script or an AI client.
          </p>
        } @else {
          <ul class="-mx-2 flex flex-col" aria-label="Your tokens">
            @for (token of items(); track token.id) {
              @let expired = expiredToken(token);
              <li
                class="flex items-center gap-3 rounded-md px-2 py-2"
                [class.opacity-70]="expired"
                [attr.data-testid]="'token-' + token.id"
              >
                <ng-icon
                  name="lucideKeyRound"
                  class="text-muted-foreground shrink-0"
                  aria-hidden="true"
                />
                <div class="flex min-w-0 flex-1 flex-col gap-0.5">
                  <p class="flex flex-wrap items-center gap-2 text-sm">
                    <span class="truncate font-medium">{{ token.name }}</span>
                    <span hlmBadge variant="outline" data-testid="token-scope">{{
                      scopeLabels[token.scope]
                    }}</span>
                    @if (expired) {
                      <span hlmBadge variant="destructive">Expired</span>
                    }
                  </p>
                  <p class="text-muted-foreground text-xs" data-testid="token-meta">
                    Created <app-time [value]="token.createdAt" />
                    ·
                    @if (token.expiresAt; as expiresAt) {
                      {{ expired ? 'expired' : 'expires' }} <app-time [value]="expiresAt" />
                    } @else {
                      never expires
                    }
                    ·
                    @if (token.lastUsedAt; as lastUsedAt) {
                      last used <app-time [value]="lastUsedAt" />
                    } @else {
                      never used
                    }
                  </p>
                  <p class="text-muted-foreground text-xs" data-testid="token-companies">
                    Reaches: {{ companiesOf(token) }}
                  </p>
                  @if (expired) {
                    <p class="text-muted-foreground text-xs" data-testid="token-expired-note">
                      An expired token is not renewed: create a new one, and revoke this one.
                    </p>
                  }
                  @if (renewError()?.id === token.id) {
                    <p class="text-destructive text-xs" role="alert" data-testid="renew-error">
                      {{ renewError()!.message }}
                    </p>
                  }
                </div>
                @if (!expired && token.expiresAt) {
                  <button
                    hlmBtn
                    variant="ghost"
                    size="sm"
                    type="button"
                    data-testid="renew-token"
                    [attr.aria-label]="'Renew ' + token.name + ' for 90 days'"
                    [title]="'Renew ' + token.name + ' for 90 days from now'"
                    [disabled]="renewing() === token.id"
                    (click)="renew(token)"
                  >
                    @if (renewing() === token.id) {
                      <hlm-spinner aria-hidden="true" />
                    } @else {
                      <ng-icon name="lucideRefreshCw" aria-hidden="true" />
                    }
                    Renew
                  </button>
                }
                <button
                  hlmBtn
                  variant="ghost"
                  size="icon-sm"
                  type="button"
                  [attr.aria-label]="'Revoke ' + token.name"
                  [title]="'Revoke ' + token.name"
                  (click)="askRevoke(token)"
                >
                  <ng-icon name="lucideTrash2" aria-hidden="true" />
                </button>
              </li>
            }
          </ul>
        }
      </div>
    </section>

    <app-create-token-dialog [(open)]="createOpen" (tokenCreated)="onCreated($event)" />

    <hlm-alert-dialog [state]="revokeOpen() ? 'open' : 'closed'" (closed)="revokeOpen.set(false)">
      <hlm-alert-dialog-content *hlmAlertDialogPortal="let ctx">
        <hlm-alert-dialog-header>
          <h2 hlmAlertDialogTitle>Revoke {{ revoking()?.name }}?</h2>
          <p hlmAlertDialogDescription>
            Scripts and clients that use it stop working at once. You can't undo this.
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
            Revoke token
          </button>
        </hlm-alert-dialog-footer>
      </hlm-alert-dialog-content>
    </hlm-alert-dialog>
  `,
})
export class PersonalTokens {
  private readonly api = inject(Api);
  private readonly notify = inject(Notify);
  private readonly session = inject(Session);

  protected readonly scopeLabels = SCOPE_LABELS;

  protected readonly tokens = resource({
    loader: () => this.api.invoke(listOwnTokens, {}),
  });

  protected items(): readonly PersonalAccessToken[] {
    return this.tokens.hasValue() ? this.tokens.value() : [];
  }

  protected expiredToken(token: PersonalAccessToken): boolean {
    return isExpired(token);
  }

  protected companiesOf(token: PersonalAccessToken): string {
    return tokenCompanies(token, this.session.memberships());
  }

  protected readonly createOpen = signal(false);

  // --- Renew ----------------------------------------------------------------------------------

  protected readonly renewing = signal<string | null>(null);
  protected readonly renewError = signal<{ id: string; message: string } | null>(null);

  /** Another 90 days from now (`renewOwnToken`); an expired one is refused (`TOKEN_EXPIRED`). */
  protected async renew(token: PersonalAccessToken): Promise<void> {
    if (this.renewing()) return;
    this.renewing.set(token.id);
    this.renewError.set(null);
    try {
      const renewed = await this.api.invoke(renewOwnToken, { tokenId: token.id });
      this.tokens.update((list) => (list ?? []).map((t) => (t.id === renewed.id ? renewed : t)));
      this.notify.success(`${token.name} renewed`, 'It works for another 90 days.');
    } catch (error) {
      this.renewError.set({
        id: token.id,
        message: problemMessage(error, {
          'status:404': 'This token no longer exists. Reload the page.',
        }),
      });
    } finally {
      this.renewing.set(null);
    }
  }

  protected onCreated(token: PersonalAccessToken): void {
    this.tokens.update((list) => [token, ...(list ?? []).filter((t) => t.id !== token.id)]);
  }

  // --- Revoke ---------------------------------------------------------------------------------

  protected readonly revoking = signal<PersonalAccessToken | null>(null);
  protected readonly revokeOpen = signal(false);
  protected readonly revokeBusy = signal(false);
  protected readonly revokeError = signal<string | null>(null);

  protected askRevoke(token: PersonalAccessToken): void {
    this.revoking.set(token);
    this.revokeError.set(null);
    this.revokeOpen.set(true);
  }

  protected async confirmRevoke(): Promise<void> {
    const token = this.revoking();
    if (!token || this.revokeBusy()) return;
    this.revokeBusy.set(true);
    this.revokeError.set(null);
    try {
      await this.api.invoke(revokeOwnToken, { tokenId: token.id });
      this.revokeOpen.set(false);
      this.tokens.update((list) => (list ?? []).filter((t) => t.id !== token.id));
      this.notify.success(`${token.name} revoked`, 'It no longer works.');
    } catch (error) {
      this.revokeError.set(
        problemMessage(error, { 'status:404': 'This token no longer exists. Reload the page.' }),
      );
    } finally {
      this.revokeBusy.set(false);
    }
  }
}
