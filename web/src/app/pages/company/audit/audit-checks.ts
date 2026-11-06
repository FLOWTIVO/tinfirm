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
  lucideChevronDown,
  lucideCircleCheck,
  lucideCircleX,
  lucideClock,
  lucideShieldAlert,
  lucideShieldCheck,
} from '@ng-icons/lucide';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmSkeleton } from '@spartan-ng/helm/skeleton';
import { Api } from '../../../api/generated/api';
import {
  acknowledgeCompanyAuditCheck,
  listCompanyAuditChecks,
} from '../../../api/generated/functions';
import type { AuditCheck } from '../../../api/generated/models';
import { problemMessage } from '../../../api/problem';
import { CurrentCompany } from '../../../core/current-company';
import { Notify } from '../../../core/notify';
import { dayHeading, dayPhrase, shortClockTime } from '../../../shared/audit-event-view';
import { CopyButton } from '../../../shared/copy-button';
import { ReasonDialog } from '../../../shared/reason-dialog';
import { formatRelative } from '../../../shared/time';

/** What a failed check found, as the rest of a sentence (the package 6 contract, §5.8). */
export function failurePhrase(check: AuditCheck): string {
  switch (check.failure) {
    case 'SHORTER':
      return 'the chain ends before the head the last check recorded. Its newest events were removed.';
    case 'HEAD_CHANGED':
      return 'the event at the head the last check recorded has another hash now. It was changed.';
    case 'BROKEN':
      return check.firstInvalidSeq
        ? `event ${check.firstInvalidSeq} does not chain from the one before. It, or one before it, was changed.`
        : 'an event after the last head does not chain from the one before.';
    default:
      return 'the chain does not verify.';
  }
}

/** A daily check's outcome, in words (the package 6 contract, §5.8; CONVENTIONS §15.11). */
export function checkPhrase(check: AuditCheck): string {
  if (check.ok) {
    return check.eventsChecked === 0
      ? 'Passed: no new events since the check before.'
      : `Passed: ${check.eventsChecked} new ${check.eventsChecked === 1 ? 'event chains' : 'events chain'} from the head before.`;
  }
  const phrase = failurePhrase(check);
  return `Failed: ${phrase}`;
}

/** Only the chain's latest check, if it failed and is not acknowledged yet, can be acknowledged. */
export function acknowledgeable(checks: readonly AuditCheck[]): AuditCheck | null {
  const latest = checks[0];
  return latest && !latest.ok && !latest.acknowledgedAt ? latest : null;
}

const numbers = new Intl.NumberFormat('en-GB');

/**
 * The chain at a glance, on one line: where the latest daily check left it, its head
 * to note elsewhere with Copy, and "Past checks", the last five, opened in place. Each check
 * verifies the events since the head the check before recorded, and so catches what a full
 * verification cannot, a chain whose newest events were removed. A failure stays in the owners' and
 * admins' inbox until a check passes or an Owner acknowledges it, with a note kept on the check.
 * The outcome of "Verify chain" comes in as content, under the line.
 */
@Component({
  selector: 'app-audit-checks',
  imports: [NgIcon, HlmButton, HlmSkeleton, CopyButton, ReasonDialog],
  providers: [
    provideIcons({
      lucideChevronDown,
      lucideCircleCheck,
      lucideCircleX,
      lucideClock,
      lucideShieldAlert,
      lucideShieldCheck,
    }),
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'block' },
  template: `
    <section
      class="bg-card overflow-hidden rounded-xl border"
      aria-labelledby="audit-chain-title"
      data-testid="audit-checks"
    >
      <h2 id="audit-chain-title" class="sr-only">The chain</h2>
      <div
        class="flex min-h-13 items-start gap-x-3 gap-y-1 py-1 ps-3.5 pe-1 sm:flex-wrap sm:items-center sm:py-2 sm:ps-4 sm:pe-2.5"
      >
        @if (checks.error()) {
          <ng-icon
            name="lucideShieldAlert"
            class="text-muted-foreground shrink-0"
            aria-hidden="true"
          />
          <p class="text-muted-foreground min-w-0 flex-[1_1_16rem] text-sm">
            The daily checks did not load: {{ problem(checks.error()) }}
          </p>
        } @else if (!checks.hasValue()) {
          <hlm-skeleton class="size-4 rounded-full" />
          <hlm-skeleton class="h-3.5 w-64 max-w-[60%]" />
          <span class="sr-only" role="status">Loading the daily checks…</span>
        } @else if (latest(); as check) {
          @if (check.ok) {
            <ng-icon
              name="lucideShieldCheck"
              class="text-success mt-3.5 shrink-0 sm:mt-0"
              aria-hidden="true"
            />
            <p
              class="min-w-0 flex-1 py-2.5 text-sm sm:flex-[1_1_20rem] sm:py-0"
              data-testid="chain-status"
            >
              Chain checked {{ when(check) }} at <span class="font-mono">{{ clock(check) }}</span
              ><span class="text-muted-foreground max-sm:block max-sm:text-[0.8125rem]"
                ><span class="max-sm:hidden"> · </span
                ><span class="font-mono">{{ count(check.headSeq) }}</span>
                {{ check.headSeq === 1 ? 'event' : 'events' }} · none removed</span
              >
            </p>
          } @else {
            <ng-icon
              name="lucideShieldAlert"
              class="mt-3.5 shrink-0 sm:mt-0"
              [class]="check.acknowledgedAt ? 'text-muted-foreground' : 'text-destructive'"
              aria-hidden="true"
            />
            <p
              class="min-w-0 flex-1 py-2.5 text-sm sm:flex-[1_1_20rem] sm:py-0"
              [class.text-destructive]="!check.acknowledgedAt"
              data-testid="chain-status"
            >
              <strong class="font-semibold"
                >Daily check failed {{ when(check) }} at
                <span class="font-mono">{{ clock(check) }}</span
                >:</strong
              >
              {{ failure(check) }}
            </p>
          }
          <span class="text-faint flex min-w-0 items-center gap-1.5 text-xs max-sm:hidden">
            <span class="shrink-0"
              >Head <span class="font-mono">{{ check.headSeq }}</span></span
            >
            <code
              class="max-w-44 min-w-0 truncate font-mono"
              data-testid="head-hash"
              [title]="check.headHash"
              >{{ check.headHash }}</code
            >
            <app-copy-button
              class="max-sm:[&_button]:size-11"
              [value]="check.headHash"
              ariaLabel="Copy the head’s hash"
            />
          </span>
          <button
            hlmBtn
            variant="ghost"
            size="sm"
            type="button"
            class="max-sm:size-11 max-sm:px-0"
            aria-controls="audit-past-checks"
            data-testid="toggle-past-checks"
            [attr.aria-expanded]="pastOpen()"
            (click)="pastOpen.set(!pastOpen())"
          >
            <span class="max-sm:sr-only">Past checks</span>
            <ng-icon
              name="lucideChevronDown"
              class="text-muted-foreground transition-transform motion-reduce:transition-none"
              aria-hidden="true"
              [class.rotate-180]="pastOpen()"
            />
          </button>
        } @else {
          <ng-icon name="lucideClock" class="text-muted-foreground shrink-0" aria-hidden="true" />
          <p class="text-muted-foreground min-w-0 text-sm" data-testid="no-checks">
            No check has run yet: the first runs within a day.
          </p>
        }
      </div>

      @if (pending()) {
        @if (canAcknowledge()) {
          <div class="-mt-1 ps-11 pe-4 pb-3">
            <button
              hlmBtn
              variant="outline"
              size="sm"
              type="button"
              class="max-sm:h-11"
              data-testid="acknowledge-check"
              (click)="openAck()"
            >
              Acknowledge the failure
            </button>
          </div>
        } @else {
          <p class="text-muted-foreground -mt-1 ps-11 pe-4 pb-3 text-[0.8125rem]">
            Only the Owner acknowledges a failure. Until a check passes or the Owner does, it stays
            in the owners’ and admins’ inbox.
          </p>
        }
      } @else if (acknowledged(); as check) {
        <p class="text-muted-foreground -mt-1 ps-11 pe-4 pb-3 text-[0.8125rem]">
          {{ acknowledgedText(check) }} The next check starts from the head it found.
        </p>
      }

      <ng-content />

      @if (pastOpen() && items().length) {
        <ul
          id="audit-past-checks"
          class="flex flex-col gap-1.5 border-t py-2.5 ps-4 pe-4 text-[0.8125rem] sm:ps-11"
          aria-label="The last five daily checks"
          data-testid="past-checks"
        >
          <li class="text-muted-foreground">
            Once a day the chain is checked from the head the check before recorded, which also
            catches its newest events removed.
          </li>
          @for (check of items(); track check.id) {
            <li class="flex items-start gap-2" [attr.data-ok]="check.ok">
              <ng-icon
                [name]="check.ok ? 'lucideCircleCheck' : 'lucideCircleX'"
                size="14"
                class="mt-1 shrink-0"
                [class]="check.ok ? 'text-success' : 'text-destructive'"
                aria-hidden="true"
              />
              <span class="min-w-0">
                <span class="text-muted-foreground inline-block min-w-30"
                  >{{ dayOf(check) }}, <span class="font-mono">{{ clock(check) }}</span></span
                >
                <span [class.text-destructive]="!check.ok">{{ phrase(check) }}</span>
                @if (check.acknowledgedAt) {
                  <span class="text-muted-foreground block text-xs">{{
                    acknowledgedText(check)
                  }}</span>
                }
              </span>
            </li>
          }
          @if (latest(); as check) {
            <li class="text-faint flex min-w-0 items-center gap-1.5 text-xs sm:hidden">
              <span class="shrink-0"
                >Head <span class="font-mono">{{ check.headSeq }}</span></span
              >
              <code class="min-w-0 truncate font-mono">{{ check.headHash }}</code>
              <app-copy-button
                class="[&_button]:size-11"
                [value]="check.headHash"
                ariaLabel="Copy the head’s hash"
              />
            </li>
          }
        </ul>
      }
    </section>

    <app-reason-dialog
      testId="ack-dialog"
      heading="Acknowledge the failed check?"
      description="The next check starts from the head this one found, so the failure stops being reported. Say why it is accepted."
      label="Note"
      note="Kept on the check; the audit log records only that it was acknowledged."
      confirmLabel="Acknowledge"
      [maxLength]="1000"
      [destructive]="false"
      [open]="ackOpen()"
      [busy]="ackBusy()"
      [error]="ackError()"
      (confirm)="acknowledge($event)"
      (closed)="ackOpen.set(false)"
    />
  `,
})
export class AuditChecks {
  private readonly api = inject(Api);
  private readonly notify = inject(Notify);
  private readonly current = inject(CurrentCompany);

  protected readonly phrase = checkPhrase;
  protected readonly failure = failurePhrase;
  /** `MANAGE_RETENTION`: the Owner's. */
  protected readonly canAcknowledge = computed(() => this.current.access().role === 'OWNER');

  protected readonly checks = resource({
    params: () => this.current.id(),
    loader: ({ params: companyId }) =>
      this.api.invoke(listCompanyAuditChecks, { companyId, limit: 5 }),
  });
  protected readonly items = computed(() =>
    this.checks.hasValue() ? (this.checks.value()?.items ?? []) : [],
  );
  protected readonly latest = computed<AuditCheck | null>(() => this.items()[0] ?? null);
  /** The latest check, a failure the Owner acknowledged. */
  protected readonly acknowledged = computed(() => {
    const latest = this.latest();
    return latest && !latest.ok && latest.acknowledgedAt ? latest : null;
  });
  protected readonly pending = computed(() => acknowledgeable(this.items()));
  protected readonly pastOpen = signal(false);

  protected when(check: AuditCheck): string {
    return dayPhrase(new Date(check.checkedAt));
  }

  protected dayOf(check: AuditCheck): string {
    return dayHeading(new Date(check.checkedAt)).label;
  }

  protected clock(check: AuditCheck): string {
    return shortClockTime(check.checkedAt);
  }

  /** "Acknowledged 2 hours ago by Olga: “Restored from a backup”." */
  protected acknowledgedText(check: AuditCheck): string {
    if (!check.acknowledgedAt) return '';
    const by = check.acknowledgedBy ? ` by ${check.acknowledgedBy}` : '';
    const note = check.acknowledgedNote ? `: “${check.acknowledgedNote}”` : '';
    return `Acknowledged ${formatRelative(new Date(check.acknowledgedAt))}${by}${note}.`;
  }

  protected count(n: number): string {
    return numbers.format(n);
  }

  protected problem(error: unknown): string {
    return problemMessage(error);
  }

  protected readonly ackOpen = signal(false);
  protected readonly ackBusy = signal(false);
  protected readonly ackError = signal<string | null>(null);

  protected openAck(): void {
    this.ackError.set(null);
    this.ackOpen.set(true);
  }

  protected async acknowledge(note: string): Promise<void> {
    const companyId = this.current.id();
    const check = this.pending();
    if (!companyId || !check || this.ackBusy()) return;
    this.ackBusy.set(true);
    try {
      await this.api.invoke(acknowledgeCompanyAuditCheck, {
        companyId,
        checkId: check.id,
        body: { note },
      });
      this.ackOpen.set(false);
      this.notify.success('The failure is acknowledged', 'The next check starts from its head.');
      this.checks.reload();
    } catch (error) {
      this.ackError.set(problemMessage(error, { 'status:403': 'Only the Owner acknowledges.' }));
    } finally {
      this.ackBusy.set(false);
    }
  }
}
