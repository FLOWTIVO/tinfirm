import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { NgIcon, provideIcons } from '@ng-icons/core';
import { lucideShieldCheck } from '@ng-icons/lucide';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmSpinner } from '@spartan-ng/helm/spinner';
import { Api } from '../../api/generated/api';
import { verifyInstanceAuditChain } from '../../api/generated/functions';
import { problemMessage } from '../../api/problem';
import { LicenceState } from '../../core/licence';
import { AuditLog, type AuditScope } from '../../shared/audit-log';
import {
  AuditVerificationResult,
  type AuditVerificationOutcome,
} from '../../shared/audit-verification-result';
import { EditionBadge } from '../../shared/edition-badge';
import { PageHeader } from '../../shared/page-header';

@Component({
  selector: 'app-instance-audit',
  imports: [
    NgIcon,
    HlmButton,
    HlmSpinner,
    AuditLog,
    AuditVerificationResult,
    EditionBadge,
    PageHeader,
  ],
  providers: [provideIcons({ lucideShieldCheck })],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="flex flex-col gap-6">
      <app-page-header
        title="Instance audit log"
        description="Events outside any company: sign-ins, users, new companies and the vault. Newest first."
      >
        @if (tools()) {
          <button
            hlmBtn
            variant="outline"
            type="button"
            data-testid="verify-chain"
            [disabled]="verifying()"
            (click)="verify()"
          >
            @if (verifying()) {
              <hlm-spinner aria-hidden="true" />
            } @else {
              <ng-icon name="lucideShieldCheck" aria-hidden="true" />
            }
            Verify chain
          </button>
        }
      </app-page-header>

      @if (!tools()) {
        <p
          class="text-muted-foreground flex flex-wrap items-center gap-x-2 gap-y-1 text-sm text-pretty"
          data-testid="audit-community"
        >
          <app-edition-badge feature="AUDIT_TOOLS" />
          <span class="min-w-0 flex-1"
            >Every event is still recorded and chained. Verifying the chain needs a Pro
            licence.</span
          >
        </p>
      }

      <div aria-live="polite" class="empty:-mt-6">
        <app-audit-verification-result [outcome]="verification()" />
      </div>

      <app-audit-log [scope]="scope" />
    </div>
  `,
})
export class InstanceAudit {
  private readonly api = inject(Api);

  private readonly licence = inject(LicenceState);

  protected readonly scope: AuditScope = { kind: 'instance' };
  /** Chain verification: Pro and Enterprise (ADR-0077). */
  protected readonly tools = computed(() => this.licence.has('AUDIT_TOOLS'));
  protected readonly verifying = signal(false);
  protected readonly verification = signal<AuditVerificationOutcome | null>(null);

  protected async verify(): Promise<void> {
    if (this.verifying()) return;
    this.verifying.set(true);
    try {
      const result = await this.api.invoke(verifyInstanceAuditChain);
      this.verification.set({ result });
    } catch (error) {
      this.verification.set({
        error: problemMessage(error, {
          'status:403': 'Only instance admins can verify the instance audit log.',
        }),
      });
    } finally {
      this.verifying.set(false);
    }
  }
}
