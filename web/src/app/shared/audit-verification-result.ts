import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { NgIcon, provideIcons } from '@ng-icons/core';
import {
  lucideCircleCheck,
  lucideCircleX,
  lucideShieldAlert,
  lucideShieldCheck,
} from '@ng-icons/lucide';
import { HlmAlertImports } from '@spartan-ng/helm/alert';
import type { AuditCheckpointHead, AuditVerification } from '../api/generated/models';
import { clockTime } from './audit-event-view';

export interface AuditVerificationOutcome {
  readonly result?: AuditVerification;
  readonly error?: string;
  /** When it ran, for "Verified at 14:20:05". */
  readonly at?: string;
}

const numbers = new Intl.NumberFormat('en-GB');

/**
 * The checkpoint outside the database that the chain was compared with (ADR-0111), as a
 * sentence an admin may note elsewhere: "Anchored outside the database at event 88123:
 * checkpoint 1234 of 2026-11-07 10:00 UTC, key k2026."
 */
export function anchorSentence(checkpoint: AuditCheckpointHead): string {
  const at = checkpoint.issuedAt.slice(0, 16).replace('T', ' ');
  return `Anchored outside the database at event ${numbers.format(checkpoint.seq)}: checkpoint ${numbers.format(checkpoint.number)} of ${at} UTC, key ${checkpoint.keyId}.`;
}

/**
 * Shows the outcome of an audit hash-chain verification (company or instance chain): as an alert
 * on its own, or as a `row` under the company chain's status line.
 */
@Component({
  selector: 'app-audit-verification-result',
  imports: [NgIcon, HlmAlertImports],
  providers: [
    provideIcons({ lucideCircleCheck, lucideCircleX, lucideShieldAlert, lucideShieldCheck }),
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (outcome(); as v) {
      @if (variant() === 'row') {
        @if (v.error) {
          <div class="flex items-start gap-3 border-t px-4 py-3 text-sm" data-testid="verification">
            <ng-icon
              name="lucideCircleX"
              class="text-destructive mt-0.5 shrink-0"
              aria-hidden="true"
            />
            <p class="text-destructive min-w-0">
              <strong class="font-semibold">Verification failed to run.</strong> {{ v.error }}
            </p>
          </div>
        } @else if (v.result?.valid) {
          <div class="flex items-start gap-3 border-t px-4 py-3 text-sm" data-testid="verification">
            <ng-icon
              name="lucideCircleCheck"
              class="text-success mt-0.5 shrink-0"
              aria-hidden="true"
            />
            <p class="min-w-0">
              <strong class="font-semibold">Chain intact.</strong>
              <span class="text-muted-foreground">
                @if (v.at) {
                  Verified at <span class="font-mono">{{ clock(v.at) }}</span
                  >: recomputed
                } @else {
                  Verified: recomputed
                }
                <span class="font-mono">{{ count(v.result!.checkedEvents) }}</span>
                {{ v.result!.checkedEvents === 1 ? 'event' : 'events' }}. Every hash matches, so no
                event was changed or removed.</span
              >
              @if (v.result!.checkpoint; as c) {
                <span class="text-muted-foreground block" data-testid="verification-anchor">{{
                  anchor(c)
                }}</span>
              }
            </p>
          </div>
        } @else if (v.result) {
          <div
            class="bg-destructive/10 flex items-start gap-3 border-t px-4 py-3 text-sm"
            data-testid="verification"
          >
            <ng-icon
              name="lucideCircleX"
              class="text-destructive mt-0.5 shrink-0"
              aria-hidden="true"
            />
            <p class="text-destructive min-w-0">
              <strong class="font-semibold">Chain broken.</strong>
              @if (v.result.firstInvalidSeq !== null && v.result.firstInvalidSeq !== undefined) {
                The hash of event
                <span class="font-mono">{{ v.result.firstInvalidSeq }}</span> does not match. That
                event, or one before it, was changed or removed outside Tinfirm.
              } @else {
                The recomputed hashes do not match the stored ones.
              }
              Checked <span class="font-mono">{{ count(v.result.checkedEvents) }}</span> events.
              @if (v.result.checkpoint; as c) {
                <span class="block" data-testid="verification-anchor">{{ anchor(c) }}</span>
              }
            </p>
          </div>
        }
      } @else if (v.error) {
        <div hlmAlert variant="destructive">
          <ng-icon name="lucideShieldAlert" aria-hidden="true" />
          <h2 hlmAlertTitle>Verification failed to run</h2>
          <p hlmAlertDescription>{{ v.error }}</p>
        </div>
      } @else if (v.result?.valid) {
        <div hlmAlert class="border-success/40">
          <ng-icon name="lucideShieldCheck" class="text-success" aria-hidden="true" />
          <h2 hlmAlertTitle>Chain intact</h2>
          <p hlmAlertDescription>
            Recomputed {{ v.result.checkedEvents }}
            {{ v.result.checkedEvents === 1 ? 'event' : 'events' }}. Every hash matches, so no event
            was changed or removed.
            @if (v.result.checkpoint; as c) {
              <span class="block" data-testid="verification-anchor">{{ anchor(c) }}</span>
            }
          </p>
        </div>
      } @else if (v.result) {
        <div hlmAlert variant="destructive">
          <ng-icon name="lucideShieldAlert" aria-hidden="true" />
          <h2 hlmAlertTitle>Chain broken</h2>
          <p hlmAlertDescription>
            @if (v.result.firstInvalidSeq !== null && v.result.firstInvalidSeq !== undefined) {
              The hash of event #{{ v.result.firstInvalidSeq }} does not match. That event, or one
              before it, was changed or removed outside Tinfirm.
            } @else {
              The recomputed hashes do not match the stored ones.
            }
            Checked {{ v.result.checkedEvents }} events.
            @if (v.result.checkpoint; as c) {
              <span class="block" data-testid="verification-anchor">{{ anchor(c) }}</span>
            }
          </p>
        </div>
      }
    }
  `,
})
export class AuditVerificationResult {
  readonly outcome = input<AuditVerificationOutcome | null>(null);
  /** An alert of its own (the default), or a row under the company chain's status line. */
  readonly variant = input<'alert' | 'row'>('alert');

  protected clock(at: string): string {
    return clockTime(at);
  }

  protected count(n: number): string {
    return numbers.format(n);
  }

  protected anchor(checkpoint: AuditCheckpointHead): string {
    return anchorSentence(checkpoint);
  }
}
