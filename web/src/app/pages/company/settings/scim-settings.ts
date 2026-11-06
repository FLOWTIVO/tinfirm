import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  resource,
  signal,
} from '@angular/core';
import { NgIcon, provideIcons } from '@ng-icons/core';
import {
  lucideCircleAlert,
  lucideExternalLink,
  lucideKeyRound,
  lucidePlus,
  lucideTrash2,
  lucideTriangleAlert,
} from '@ng-icons/lucide';
import { HlmAlertImports } from '@spartan-ng/helm/alert';
import { HlmAlertDialogImports } from '@spartan-ng/helm/alert-dialog';
import { HlmBadge } from '@spartan-ng/helm/badge';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmDialogImports } from '@spartan-ng/helm/dialog';
import { HlmFieldImports } from '@spartan-ng/helm/field';
import { HlmInput } from '@spartan-ng/helm/input';
import { HlmNativeSelectImports } from '@spartan-ng/helm/native-select';
import { HlmSkeleton } from '@spartan-ng/helm/skeleton';
import { HlmSpinner } from '@spartan-ng/helm/spinner';
import { Api } from '../../../api/generated/api';
import {
  createScimToken,
  getScimStatus,
  listScimTokens,
  revokeScimToken,
} from '../../../api/generated/functions';
import type { Role, ScimToken } from '../../../api/generated/models';
import { problemMessage } from '../../../api/problem';
import { CurrentCompany } from '../../../core/current-company';
import { Notify } from '../../../core/notify';
import { CopyButton } from '../../../shared/copy-button';
import { ErrorState } from '../../../shared/error-state';
import { Time } from '../../../shared/time';
import { expiresAtFor } from '../../me/create-token-dialog';
import { SettingRow } from './setting-row';

/** The guides for the identity providers SCIM is tested with (ADR-0087 decision 13). */
export const SCIM_GUIDES: readonly { label: string; href: string }[] = [
  { label: 'Microsoft Entra ID', href: 'https://docs.tinfirm.com/admin-guide/scim-entra-id/' },
  { label: 'Okta', href: 'https://docs.tinfirm.com/admin-guide/scim-okta/' },
];

const ROLE_WORDS: Record<Role, string> = {
  OWNER: 'Owner',
  ADMIN: 'Admin',
  MEMBER: 'Member',
  VIEWER: 'Viewer',
};

/** Expiry choices of a SCIM token, in days; `0` is no expiry, which identity providers expect. */
const SCIM_EXPIRY: readonly { days: number; label: string }[] = [
  { days: 0, label: 'No expiry' },
  { days: 365, label: '1 year' },
  { days: 90, label: '90 days' },
];

/** At most two active tokens a company: the next one is issued before the old one is revoked. */
export const MAX_ACTIVE_SCIM_TOKENS = 2;

/** Whether a token still works: not revoked and not expired. */
export function scimTokenActive(token: ScimToken, now: Date = new Date()): boolean {
  return (
    !token.revokedAt && (!token.expiresAt || new Date(token.expiresAt).getTime() > now.getTime())
  );
}

/**
 * The SCIM part of Settings → Sign-in (ADR-0087 decision 11): whether the deployment allows SCIM for
 * the company and the licence serves it, the base URL to paste at the identity provider, the role a
 * new member gets, who decides roles after, and the company's tokens. The OWNER issues a token,
 * shown once with a copy button, and revokes one; an ADMIN reads. Revoking works in every edition,
 * so a token can be stopped after the licence ended.
 */
@Component({
  selector: 'app-scim-settings',
  imports: [
    NgIcon,
    HlmAlertImports,
    HlmAlertDialogImports,
    HlmBadge,
    HlmButton,
    HlmDialogImports,
    HlmFieldImports,
    HlmInput,
    HlmNativeSelectImports,
    HlmSkeleton,
    HlmSpinner,
    CopyButton,
    ErrorState,
    SettingRow,
    Time,
  ],
  providers: [
    provideIcons({
      lucideCircleAlert,
      lucideExternalLink,
      lucideKeyRound,
      lucidePlus,
      lucideTrash2,
      lucideTriangleAlert,
    }),
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="bg-card rounded-xl border" aria-labelledby="scim-title" data-testid="scim">
      <div class="flex flex-wrap items-start justify-between gap-3 px-4 pt-4 pb-3.5 sm:px-5">
        <div class="min-w-0">
          <h2 id="scim-title" class="flex items-center gap-2 text-base font-bold">
            SCIM provisioning
            <span hlmBadge variant="outline">Enterprise</span>
          </h2>
          <p class="text-muted-foreground mt-0.5 text-sm text-pretty">
            Your identity provider creates the company’s members, changes their roles and removes
            access at once when someone leaves. Their conversations and files stay; erasing them
            stays an Owner’s act.
          </p>
        </div>
        @if (canManage()) {
          <button
            hlmBtn
            size="sm"
            variant="outline"
            type="button"
            data-testid="scim-new-token"
            [disabled]="!canIssue()"
            (click)="openCreate()"
          >
            <ng-icon name="lucidePlus" aria-hidden="true" />
            New token
          </button>
        }
      </div>

      @if (status.error() && !status.hasValue()) {
        <div class="border-t px-4 py-4 sm:px-5">
          <app-error-state
            [error]="status.error()"
            heading="Couldn't load SCIM"
            (retry)="status.reload()"
          />
        </div>
      } @else if (status.hasValue()) {
        @let s = status.value();
        @if (!s.enabled) {
          <div class="border-t px-4 py-4 sm:px-5">
            <div hlmAlert data-testid="scim-not-enabled">
              <ng-icon name="lucideCircleAlert" aria-hidden="true" />
              <h3 hlmAlertTitle>Not allowed for this company</h3>
              <p hlmAlertDescription>
                Whoever runs this instance allows SCIM for a company in its deployment:
                <code class="text-xs">companies[].scim.enabled</code> in the Helm chart, or
                <code class="text-xs">TINFIRM_COMPANIES_&lt;KEY&gt;_SCIM_ENABLED=true</code>.
              </p>
            </div>
          </div>
        } @else if (!s.licensed) {
          <div class="border-t px-4 py-4 sm:px-5">
            <div hlmAlert data-testid="scim-not-licensed">
              <ng-icon name="lucideCircleAlert" aria-hidden="true" />
              <h3 hlmAlertTitle>Needs an Enterprise licence</h3>
              <p hlmAlertDescription>
                SCIM serves while the instance’s licence is Enterprise. Until then your identity
                provider’s requests are refused, and a person who leaves is removed under Members.
                Revoking a token still works.
              </p>
            </div>
          </div>
        }
        <app-setting-row rowId="scim-url" label="Base URL">
          <span help>Paste it at your identity provider, as its SCIM tenant or base URL.</span>
          <div class="bg-muted/50 flex max-w-full items-center gap-2 rounded-lg border px-3 py-1.5">
            <code class="min-w-0 text-xs break-all select-all" data-testid="scim-base-url">{{
              s.baseUrl
            }}</code>
            <app-copy-button
              [value]="s.baseUrl"
              ariaLabel="Copy the base URL"
              label="Copy"
              variant="ghost"
            />
          </div>
        </app-setting-row>
        <app-setting-row rowId="scim-role" label="New members">
          <span help
            >The role of a person whose request names none. A role that needs a seat becomes Viewer
            while every seat is taken.</span
          >
          <span class="text-sm" data-testid="scim-default-role">{{
            roleWords[s.defaultRole]
          }}</span>
        </app-setting-row>
        <app-setting-row rowId="scim-role-source" label="Roles">
          <span class="text-sm" data-testid="scim-role-source">{{
            s.roleSource === 'IDP'
              ? 'Your identity provider decides them; they are not changed here'
              : 'Given once at creation; changed here after'
          }}</span>
        </app-setting-row>
        <div class="border-t px-4 py-4 sm:px-5">
          <h3 class="text-sm font-semibold">Tokens</h3>
          <p class="text-muted-foreground mt-0.5 text-[0.8125rem]">
            At most two work at once: issue the next one, paste it at the identity provider, then
            revoke the old one.
          </p>
          @if (tokens.error() && !tokens.hasValue()) {
            <app-error-state
              class="mt-3 block"
              [error]="tokens.error()"
              heading="Couldn't load the tokens"
              (retry)="tokens.reload()"
            />
          } @else if (tokens.hasValue()) {
            @if (tokenItems().length === 0) {
              <p class="text-muted-foreground mt-3 text-sm" data-testid="scim-no-tokens">
                No token yet.
              </p>
            } @else {
              <ul class="-mx-2 mt-2 flex flex-col" aria-label="SCIM tokens">
                @for (token of tokenItems(); track token.id) {
                  @let active = isActive(token);
                  <li
                    class="flex items-center gap-3 rounded-md px-2 py-2"
                    [class.opacity-70]="!active"
                    [attr.data-testid]="'scim-token-' + token.id"
                  >
                    <ng-icon
                      name="lucideKeyRound"
                      class="text-muted-foreground shrink-0"
                      aria-hidden="true"
                    />
                    <div class="flex min-w-0 flex-1 flex-col gap-0.5">
                      <p class="flex flex-wrap items-center gap-2 text-sm">
                        <span class="truncate font-medium">{{ token.name }}</span>
                        @if (token.revokedAt) {
                          <span hlmBadge variant="outline">Revoked</span>
                        } @else if (!active) {
                          <span hlmBadge variant="destructive">Expired</span>
                        }
                      </p>
                      <p class="text-muted-foreground text-xs" data-testid="scim-token-meta">
                        Issued <app-time [value]="token.createdAt" />
                        @if (token.createdByName) {
                          by {{ token.createdByName }}
                        }
                        ·
                        @if (token.lastUsedAt; as lastUsedAt) {
                          last used <app-time [value]="lastUsedAt" />
                        } @else {
                          never used
                        }
                      </p>
                    </div>
                    @if (canManage() && !token.revokedAt) {
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
                    }
                  </li>
                }
              </ul>
            }
          } @else {
            <div class="mt-3 flex flex-col gap-2" aria-busy="true">
              <hlm-skeleton class="h-4 w-48" />
            </div>
          }
        </div>
        <div class="border-t px-4 py-4 sm:px-5" data-testid="scim-guides">
          <h3 class="text-sm font-semibold">Guides</h3>
          <ul class="mt-1.5 flex flex-wrap gap-x-5 gap-y-1.5">
            @for (guide of guides; track guide.href) {
              <li>
                <a
                  class="text-primary inline-flex items-center gap-1 text-sm font-semibold underline-offset-4 hover:underline"
                  [href]="guide.href"
                  target="_blank"
                  rel="noopener"
                  >{{ guide.label }}
                  <ng-icon name="lucideExternalLink" size="14" aria-hidden="true" />
                </a>
              </li>
            }
          </ul>
        </div>
      } @else {
        <div class="flex flex-col gap-3 border-t px-4 py-4 sm:px-5" aria-busy="true">
          <hlm-skeleton class="h-4 w-40" />
          <hlm-skeleton class="h-4 w-56" />
        </div>
      }
    </section>

    <hlm-dialog [state]="createOpen() ? 'open' : 'closed'" (closed)="closeCreate()">
      <hlm-dialog-content *hlmDialogPortal="let ctx" class="sm:max-w-lg">
        @if (issued(); as secret) {
          <hlm-dialog-header>
            <h2 hlmDialogTitle>{{ issuedName() }}</h2>
            <p hlmDialogDescription>
              Paste it at your identity provider as its secret token, the Bearer token of its SCIM
              connection.
            </p>
          </hlm-dialog-header>
          <div hlmAlert class="border-warning/50" data-testid="scim-token-once">
            <ng-icon name="lucideTriangleAlert" class="text-warning" aria-hidden="true" />
            <h3 hlmAlertTitle>Copy the token now</h3>
            <p hlmAlertDescription>
              It is not shown again; Tinfirm keeps only a hash. Whoever holds it can add, change and
              remove the company’s members.
            </p>
          </div>
          <div class="bg-muted/50 flex items-center gap-2 rounded-lg border px-3 py-2">
            <code class="min-w-0 flex-1 text-xs break-all select-all" data-testid="scim-secret">{{
              secret
            }}</code>
            <app-copy-button
              [value]="secret"
              ariaLabel="Copy token"
              label="Copy"
              variant="outline"
            />
          </div>
          <hlm-dialog-footer>
            <button hlmBtn type="button" hlmDialogClose>Done</button>
          </hlm-dialog-footer>
        } @else {
          <hlm-dialog-header>
            <h2 hlmDialogTitle>New SCIM token</h2>
            <p hlmDialogDescription>
              For your identity provider’s SCIM connection to this company.
            </p>
          </hlm-dialog-header>
          <form
            id="scim-token-form"
            class="flex flex-col gap-4"
            novalidate
            (submit)="issue($event)"
          >
            @if (createError(); as message) {
              <div hlmAlert variant="destructive">
                <ng-icon name="lucideCircleAlert" aria-hidden="true" />
                <p hlmAlertDescription>{{ message }}</p>
              </div>
            }
            <div hlmField>
              <label hlmFieldLabel for="scim-token-name">Name</label>
              <input
                hlmInput
                id="scim-token-name"
                autocomplete="off"
                maxlength="100"
                placeholder="Entra ID"
                [value]="name()"
                (input)="name.set($any($event.target).value)"
              />
            </div>
            <div hlmField>
              <label hlmFieldLabel for="scim-token-expiry">Expires</label>
              <hlm-native-select
                class="w-full"
                selectId="scim-token-expiry"
                [value]="'' + expiryDays()"
                (valueChange)="expiryDays.set(+($event ?? 0))"
              >
                @for (choice of expiryChoices; track choice.days) {
                  <option [value]="'' + choice.days">{{ choice.label }}</option>
                }
              </hlm-native-select>
              <p hlmFieldDescription>
                An expired token stops the provider’s sync until you issue the next.
              </p>
            </div>
          </form>
          <hlm-dialog-footer>
            <button hlmBtn variant="outline" type="button" hlmDialogClose>Cancel</button>
            <button
              hlmBtn
              type="submit"
              form="scim-token-form"
              [disabled]="creating() || !name().trim()"
            >
              @if (creating()) {
                <hlm-spinner aria-hidden="true" />
              }
              Issue token
            </button>
          </hlm-dialog-footer>
        }
      </hlm-dialog-content>
    </hlm-dialog>

    <hlm-alert-dialog [state]="revokeOpen() ? 'open' : 'closed'" (closed)="revokeOpen.set(false)">
      <hlm-alert-dialog-content *hlmAlertDialogPortal="let ctx">
        <hlm-alert-dialog-header>
          <h2 hlmAlertDialogTitle>Revoke {{ revoking()?.name }}?</h2>
          <p hlmAlertDialogDescription>
            Your identity provider’s requests with it are refused at once. You can't undo this.
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
export class ScimSettings {
  private readonly api = inject(Api);
  private readonly current = inject(CurrentCompany);
  private readonly notify = inject(Notify);

  protected readonly roleWords = ROLE_WORDS;
  protected readonly guides = SCIM_GUIDES;
  protected readonly expiryChoices = SCIM_EXPIRY;

  private readonly companyId = computed(() => this.current.id() ?? '');

  protected readonly status = resource({
    params: () => this.companyId() || undefined,
    loader: ({ params }) => this.api.invoke(getScimStatus, { companyId: params }),
  });

  protected readonly tokens = resource({
    params: () => this.companyId() || undefined,
    loader: ({ params }) => this.api.invoke(listScimTokens, { companyId: params }),
  });

  /** Only the OWNER issues and revokes (`MANAGE_PROVISIONING`); an ADMIN reads. */
  protected readonly canManage = computed(() => this.current.access().role === 'OWNER');

  protected readonly tokenItems = computed<readonly ScimToken[]>(() =>
    this.tokens.hasValue() ? this.tokens.value().items : [],
  );

  protected readonly canIssue = computed(() => {
    if (!this.status.hasValue()) return false;
    const s = this.status.value();
    const active = this.tokenItems().filter((t) => scimTokenActive(t)).length;
    return s.enabled && s.licensed && active < MAX_ACTIVE_SCIM_TOKENS;
  });

  protected isActive(token: ScimToken): boolean {
    return scimTokenActive(token);
  }

  // --- Issue ----------------------------------------------------------------------------------

  protected readonly createOpen = signal(false);
  protected readonly name = signal('');
  protected readonly expiryDays = signal(0);
  protected readonly creating = signal(false);
  protected readonly createError = signal<string | null>(null);
  /** The token, shown once; dropped when the dialog closes. */
  protected readonly issued = signal<string | null>(null);
  protected readonly issuedName = signal('');

  protected openCreate(): void {
    this.name.set('');
    this.expiryDays.set(0);
    this.createError.set(null);
    this.issued.set(null);
    this.createOpen.set(true);
  }

  protected closeCreate(): void {
    this.createOpen.set(false);
    this.issued.set(null);
  }

  protected async issue(event: Event): Promise<void> {
    event.preventDefault();
    const name = this.name().trim();
    if (!name || this.creating()) return;
    this.creating.set(true);
    this.createError.set(null);
    try {
      const created = await this.api.invoke(createScimToken, {
        companyId: this.companyId(),
        body: { name, expiresAt: expiresAtFor(this.expiryDays()) },
      });
      this.issued.set(created.secret);
      this.issuedName.set(created.token.name);
      this.tokens.update((list) => ({ items: [created.token, ...(list?.items ?? [])] }));
    } catch (error) {
      this.createError.set(problemMessage(error));
    } finally {
      this.creating.set(false);
    }
  }

  // --- Revoke ---------------------------------------------------------------------------------

  protected readonly revoking = signal<ScimToken | null>(null);
  protected readonly revokeOpen = signal(false);
  protected readonly revokeBusy = signal(false);
  protected readonly revokeError = signal<string | null>(null);

  protected askRevoke(token: ScimToken): void {
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
      await this.api.invoke(revokeScimToken, { companyId: this.companyId(), tokenId: token.id });
      this.revokeOpen.set(false);
      const now = new Date().toISOString();
      this.tokens.update((list) => ({
        items: (list?.items ?? []).map((t) => (t.id === token.id ? { ...t, revokedAt: now } : t)),
      }));
      this.notify.success(`${token.name} revoked`, 'Requests with it are refused.');
    } catch (error) {
      this.revokeError.set(
        problemMessage(error, { 'status:404': 'This token no longer exists. Reload the page.' }),
      );
    } finally {
      this.revokeBusy.set(false);
    }
  }
}
