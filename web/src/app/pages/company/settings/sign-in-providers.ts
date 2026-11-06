import { NgTemplateOutlet } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, resource } from '@angular/core';
import { NgIcon, provideIcons } from '@ng-icons/core';
import {
  lucideCircleAlert,
  lucideDownload,
  lucideExternalLink,
  lucideTriangleAlert,
} from '@ng-icons/lucide';
import { HlmAlertImports } from '@spartan-ng/helm/alert';
import { HlmBadge } from '@spartan-ng/helm/badge';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmSkeleton } from '@spartan-ng/helm/skeleton';
import { Api } from '../../../api/generated/api';
import { getCompanySignIn } from '../../../api/generated/functions';
import type {
  CertificateInfo,
  CompanySignInProvider,
  CompanySignInRoleMap,
  Role,
  SamlProviderDetails,
} from '../../../api/generated/models';
import { CurrentCompany } from '../../../core/current-company';
import { CopyButton } from '../../../shared/copy-button';
import { ErrorState } from '../../../shared/error-state';
import { Time } from '../../../shared/time';
import { SettingRow } from './setting-row';

/** The guides for the SAML identity providers (ADR-0086, decision 16). */
export const SAML_GUIDES: readonly { label: string; href: string }[] = [
  { label: 'Microsoft Entra ID', href: 'https://docs.tinfirm.com/admin-guide/saml-entra-id/' },
  { label: 'Okta', href: 'https://docs.tinfirm.com/admin-guide/saml-okta/' },
  { label: 'AD FS', href: 'https://docs.tinfirm.com/admin-guide/saml-adfs/' },
];

const ROLE_WORDS: Record<Role, string> = {
  OWNER: 'Owner',
  ADMIN: 'Admin',
  MEMBER: 'Member',
  VIEWER: 'Viewer',
};

/** What a failed read of the IdP's metadata means, in the page's words. */
const METADATA_ERRORS: Record<string, string> = {
  unreachable: 'The metadata could not be fetched or read.',
  invalid: 'The document is not SAML metadata.',
  no_idp: 'The document holds no usable identity provider.',
  expired: 'The document’s validUntil has passed.',
  too_large: 'The document is over 1 MiB.',
  entity_id_changed:
    'Metadata refused: the document names another identity provider than the one first read. Set _ENTITY_ID to accept it.',
  entity_id_in_use:
    'Metadata refused: the document names the identity provider of another sign-in provider.',
};

/** From this many days before a certificate ends, the page warns. */
const WARN_DAYS = 30;

/** Whether a certificate ends within {@link WARN_DAYS} of now, or has ended. */
export function certificateEndsSoon(certificate: CertificateInfo, now: Date = new Date()): boolean {
  return new Date(certificate.notAfter).getTime() - now.getTime() < WARN_DAYS * 24 * 3600 * 1000;
}

/**
 * The providers part of Settings → Sign-in (ADR-0086 decision 15): the OIDC and SAML providers
 * that fill the company, as their own company or through their role claim (ADR-0089), as the
 * deployment declares them, read-only; with the claim's values that give each role here. A SAML provider
 * shows what its identity provider's admin needs (the entity ID, the ACS URL, the metadata to
 * download, the start URL for a portal's tile), the state of the IdP's metadata and both sides'
 * certificates. A provider is changed by whoever runs the instance, never here.
 */
@Component({
  selector: 'app-sign-in-providers',
  imports: [
    NgIcon,
    NgTemplateOutlet,
    HlmAlertImports,
    HlmBadge,
    HlmButton,
    HlmSkeleton,
    CopyButton,
    ErrorState,
    SettingRow,
    Time,
  ],
  providers: [
    provideIcons({ lucideCircleAlert, lucideDownload, lucideExternalLink, lucideTriangleAlert }),
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section
      class="bg-card rounded-xl border"
      aria-labelledby="sign-in-providers-title"
      data-testid="sign-in-providers"
    >
      <div class="px-4 pt-4 pb-3.5 sm:px-5">
        <h2 id="sign-in-providers-title" class="text-base font-bold">Identity providers</h2>
        <p class="text-muted-foreground mt-0.5 text-sm text-pretty">
          The single sign-on your people use, as whoever runs this instance set it up. Ask them to
          add or change a provider.
        </p>
      </div>

      @if (signIn.error() && !signIn.hasValue()) {
        <div class="border-t px-4 py-4 sm:px-5">
          <app-error-state
            [error]="signIn.error()"
            heading="Couldn't load the identity providers"
            (retry)="signIn.reload()"
          />
        </div>
      } @else if (signIn.hasValue()) {
        @if (providers().length === 0) {
          <p
            class="text-muted-foreground border-t px-4 py-4 text-sm sm:px-5"
            data-testid="sign-in-no-providers"
          >
            No identity provider makes members of this company. People sign in with a password, or
            with a provider of the instance once an admin adds them.
          </p>
        }
        @if (anySaml() && !signIn.value().samlServed) {
          <div class="border-t px-4 py-4 sm:px-5">
            <div hlmAlert data-testid="saml-not-licensed">
              <ng-icon name="lucideCircleAlert" aria-hidden="true" />
              <h3 hlmAlertTitle>SAML needs an Enterprise licence</h3>
              <p hlmAlertDescription>
                Until the instance has one, its SAML sign-in ends on an error. A licence that
                expired keeps SAML working.
              </p>
            </div>
          </div>
        }
        @for (provider of providers(); track provider.id) {
          <div class="border-t" [attr.data-testid]="'sign-in-provider-' + provider.id">
            <div class="flex flex-wrap items-center gap-2 px-4 pt-4 sm:px-5">
              <h3 class="text-sm font-semibold">{{ provider.name }}</h3>
              <span hlmBadge variant="outline">{{ provider.kind }}</span>
              @if (provider.through === 'CLAIM') {
                <span
                  hlmBadge
                  variant="secondary"
                  [attr.data-testid]="'sign-in-through-' + provider.id"
                  >Through its claim</span
                >
              }
            </div>
            @if (provider.through === 'CLAIM') {
              <p class="text-muted-foreground px-4 pt-1.5 text-sm text-pretty sm:px-5">
                This provider’s own company is another one; its claim
                {{ provider.roleClaim ? '“' + provider.roleClaim + '”' : '' }} names this company’s
                people, with their roles.
              </p>
            }
            <app-setting-row [rowId]="'link-' + provider.id" label="Direct link">
              <span help>The login page with this provider only, for your people.</span>
              <ng-container
                *ngTemplateOutlet="copyable; context: { value: provider.directLink, what: 'link' }"
              />
            </app-setting-row>
            <app-setting-row [rowId]="'roles-' + provider.id" label="Roles">
              <div
                class="flex flex-col gap-1 text-sm"
                [attr.data-testid]="'sign-in-roles-' + provider.id"
              >
                <span>{{ rolesText(provider) }}</span>
                @if (provider.roleMap; as map) {
                  <ul class="text-muted-foreground flex flex-col gap-0.5 text-xs">
                    @for (entry of roleMapEntries(map); track entry.role) {
                      <li>
                        <span class="font-medium">{{ entry.role }}</span>
                        <span class="text-muted-foreground"> · </span>
                        <code class="break-all">{{ entry.values.join(', ') }}</code>
                      </li>
                    }
                  </ul>
                }
              </div>
            </app-setting-row>
            @if (provider.emailDomains.length > 0) {
              <app-setting-row [rowId]="'domains-' + provider.id" label="Email domains">
                <span class="text-sm">{{ provider.emailDomains.join(', ') }}</span>
              </app-setting-row>
            }
            @if (provider.saml; as saml) {
              <ng-container
                *ngTemplateOutlet="samlRows; context: { $implicit: saml, id: provider.id }"
              />
            }
          </div>
        }
        @if (anySaml()) {
          <div class="border-t px-4 py-4 sm:px-5" data-testid="saml-guides">
            <h3 class="text-sm font-semibold">SAML guides</h3>
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
        }
      } @else {
        <div class="flex flex-col gap-3 border-t px-4 py-4 sm:px-5" aria-busy="true">
          <hlm-skeleton class="h-4 w-40" />
          <hlm-skeleton class="h-4 w-56" />
        </div>
      }
    </section>

    <ng-template #samlRows let-saml let-id="id">
      @if (saml.lastError) {
        <div class="px-4 pt-4 sm:px-5">
          <div hlmAlert variant="destructive" [attr.data-testid]="'saml-metadata-error-' + id">
            <ng-icon name="lucideTriangleAlert" aria-hidden="true" />
            <h3 hlmAlertTitle>The last read of the IdP's metadata failed</h3>
            <p hlmAlertDescription>
              {{ metadataError(saml) }} The last good metadata stays in use.
              @if (saml.lastErrorAt) {
                Failed <app-time [value]="saml.lastErrorAt" />.
              }
            </p>
          </div>
        </div>
      }
      <app-setting-row [rowId]="'entity-' + id" label="Entity ID">
        <span help>The service provider's identifier, also called the audience.</span>
        <ng-container
          *ngTemplateOutlet="copyable; context: { value: saml.spEntityId, what: 'entity ID' }"
        />
      </app-setting-row>
      <app-setting-row [rowId]="'acs-' + id" label="Reply URL">
        <span help>The assertion consumer service, HTTP-POST.</span>
        <ng-container
          *ngTemplateOutlet="copyable; context: { value: saml.acsUrl, what: 'reply URL' }"
        />
      </app-setting-row>
      <app-setting-row [rowId]="'start-' + id" label="Sign-on URL">
        <span help>Make a portal's tile open it: sign-in always starts at Tinfirm.</span>
        <ng-container
          *ngTemplateOutlet="copyable; context: { value: saml.startUrl, what: 'sign-on URL' }"
        />
      </app-setting-row>
      <app-setting-row [rowId]="'metadata-' + id" label="Service provider metadata">
        <span help>Upload it at your identity provider, or give it this URL.</span>
        <div class="flex flex-wrap items-center gap-2">
          <a
            hlmBtn
            size="sm"
            variant="outline"
            [href]="saml.spMetadataUrl"
            download
            [attr.data-testid]="'saml-download-' + id"
          >
            <ng-icon name="lucideDownload" aria-hidden="true" />
            Download
          </a>
          <app-copy-button
            [value]="saml.spMetadataUrl"
            ariaLabel="Copy URL of the metadata"
            label="Copy URL"
            variant="ghost"
          />
        </div>
      </app-setting-row>
      <app-setting-row [rowId]="'idp-' + id" label="Identity provider">
        <div class="flex flex-col gap-0.5 text-sm" [attr.data-testid]="'saml-idp-' + id">
          <span class="break-all">{{ saml.idpEntityId ?? 'Not read yet' }}</span>
          <span class="text-muted-foreground text-xs">
            Metadata from
            {{ saml.metadataSource === 'file' ? 'a file of the deployment' : saml.metadataSource }}
            @if (saml.metadataReadAt) {
              · read <app-time [value]="saml.metadataReadAt" />
            }
            @if (saml.validUntil) {
              · valid until <app-time [value]="saml.validUntil" />
            }
          </span>
        </div>
      </app-setting-row>
      <app-setting-row [rowId]="'certificates-' + id" label="Certificates">
        <ul class="flex flex-col gap-1.5 text-xs" [attr.data-testid]="'saml-certificates-' + id">
          @for (certificate of saml.idpCertificates; track certificate.sha256) {
            <ng-container
              *ngTemplateOutlet="certificateItem; context: { $implicit: certificate, side: 'IdP' }"
            />
          }
          @for (certificate of saml.spCertificates; track certificate.sha256) {
            <ng-container
              *ngTemplateOutlet="
                certificateItem;
                context: { $implicit: certificate, side: 'Tinfirm' }
              "
            />
          }
        </ul>
      </app-setting-row>
    </ng-template>

    <ng-template #certificateItem let-certificate let-side="side">
      <li class="flex flex-col">
        <span class="flex flex-wrap items-center gap-1.5">
          <span class="font-medium">{{ side }}</span>
          <span class="text-muted-foreground break-all">{{ certificate.subject }}</span>
          @if (endsSoon(certificate)) {
            <span hlmBadge variant="destructive">Ends soon</span>
          }
        </span>
        <span class="text-muted-foreground">
          Ends <app-time [value]="certificate.notAfter" /> ·
          <code class="break-all">{{ certificate.sha256.slice(0, 16) }}…</code>
        </span>
      </li>
    </ng-template>

    <ng-template #copyable let-value="value" let-what="what">
      <div class="bg-muted/50 flex max-w-full items-center gap-2 rounded-lg border px-3 py-1.5">
        <code class="min-w-0 text-xs break-all select-all">{{ value }}</code>
        <app-copy-button
          [value]="value"
          [ariaLabel]="'Copy the ' + what"
          label="Copy"
          variant="ghost"
        />
      </div>
    </ng-template>
  `,
})
export class SignInProviders {
  private readonly api = inject(Api);
  private readonly current = inject(CurrentCompany);
  protected readonly guides = SAML_GUIDES;
  private readonly companyId = computed(() => this.current.id() ?? '');
  protected readonly signIn = resource({
    params: () => this.companyId() || undefined,
    loader: ({ params }) => this.api.invoke(getCompanySignIn, { companyId: params }),
  });
  protected readonly providers = computed<readonly CompanySignInProvider[]>(() =>
    this.signIn.hasValue() ? this.signIn.value().providers : [],
  );
  protected readonly anySaml = computed(() => this.providers().some((p) => p.kind === 'SAML'));

  protected rolesText(provider: CompanySignInProvider): string {
    if (provider.roleSource === 'IDP') {
      return `Your identity provider decides them at every sign-in, from ${provider.roleClaim ?? 'its claim'}; they are not changed here`;
    }
    if (provider.through === 'CLAIM') {
      return 'New members get the role its claim maps here, once; changed here after';
    }
    return `New members are ${ROLE_WORDS[provider.role]}s; changed here after`;
  }

  /** The claim's values that give each role here, the roles that have any, highest first. */
  protected roleMapEntries(map: CompanySignInRoleMap): { role: string; values: string[] }[] {
    return [
      { role: 'Admin', values: map.admin },
      { role: 'Member', values: map.member },
      { role: 'Viewer', values: map.viewer },
    ].filter((entry) => entry.values.length > 0);
  }

  protected metadataError(saml: SamlProviderDetails): string {
    return METADATA_ERRORS[saml.lastError ?? ''] ?? 'The metadata could not be read.';
  }

  protected endsSoon(certificate: CertificateInfo): boolean {
    return certificateEndsSoon(certificate);
  }
}
