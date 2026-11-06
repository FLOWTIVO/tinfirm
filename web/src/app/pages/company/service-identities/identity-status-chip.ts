import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import type { PrincipalStatus } from '../../../api/generated/models';
import { IDENTITY_STATUS_LABELS } from './identity-labels';

/**
 * A service account's status as a pill: "Active" in success, "Suspended" neutral (an admin's own
 * kill switch, nobody waits on a person for it). Never orange.
 */
@Component({
  selector: 'app-identity-status-chip',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <span
      class="inline-flex h-6 items-center rounded-full border px-2.5 text-xs font-semibold whitespace-nowrap"
      [class]="
        status() === 'ACTIVE'
          ? 'bg-card text-success border-success/40'
          : 'bg-muted text-muted-foreground'
      "
      data-testid="identity-status"
      [attr.data-status]="status()"
      >{{ label() }}</span
    >
  `,
})
export class IdentityStatusChip {
  readonly status = input.required<PrincipalStatus>();
  protected readonly label = computed(() => IDENTITY_STATUS_LABELS[this.status()]);
}
