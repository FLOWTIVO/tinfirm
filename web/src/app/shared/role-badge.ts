import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { HlmBadge } from '@spartan-ng/helm/badge';
import type { Role } from '../api/generated/models';
import { ROLE_LABELS } from '../core/permissions';

/**
 * The calm chip of a role in a list (Members): owner green on its tint, admin on secondary, member
 * outlined, viewer outlined and muted (no seat, ADR-0077).
 */
const CHIP_CLASS: Record<Role, string> = {
  OWNER: 'bg-primary/10 text-primary',
  ADMIN: 'bg-secondary text-foreground',
  MEMBER: 'border-input text-foreground border',
  VIEWER: 'border-input text-muted-foreground border border-dashed',
};

@Component({
  selector: 'app-role-badge',
  imports: [HlmBadge],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (tone() === 'chip') {
      <span
        class="inline-flex h-[22px] items-center rounded-full px-2.5 text-xs leading-none font-semibold whitespace-nowrap"
        [class]="chipClass()"
        >{{ label() }}</span
      >
    } @else {
      <span hlmBadge [variant]="variant()">{{ label() }}</span>
    }
  `,
})
export class RoleBadge {
  readonly role = input.required<Role>();
  /** `chip`: the quieter chip of a list row; the badge otherwise. */
  readonly tone = input<'badge' | 'chip'>('badge');
  protected readonly label = computed(() => ROLE_LABELS[this.role()]);
  protected readonly chipClass = computed(() => CHIP_CLASS[this.role()]);
  protected readonly variant = computed(() => {
    switch (this.role()) {
      case 'OWNER':
        return 'default';
      case 'ADMIN':
        return 'secondary';
      default:
        return 'outline';
    }
  });
}
