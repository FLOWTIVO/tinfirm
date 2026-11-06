import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  input,
  resource,
  signal,
} from '@angular/core';
import { RouterLink } from '@angular/router';
import { NgIcon, provideIcons } from '@ng-icons/core';
import { lucideChevronLeft, lucideClock, lucideSquare } from '@ng-icons/lucide';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmSkeleton } from '@spartan-ng/helm/skeleton';
import { HlmSpinner } from '@spartan-ng/helm/spinner';
import { Api } from '../../../api/generated/api';
import { cancelRun, getConversation, getRun } from '../../../api/generated/functions';
import type { Run } from '../../../api/generated/models';
import { toProblem } from '../../../api/problem';
import { CurrentCompany } from '../../../core/current-company';
import { Notify } from '../../../core/notify';
import { sameParams } from '../../../core/paged-resource';
import { managesCompany } from '../../../core/permissions';
import { ErrorState } from '../../../shared/error-state';
import { initials } from '../../../shared/forms';
import { Markdown } from '../../../shared/markdown';
import { QueueReasonText } from '../../../shared/queue-reason';
import { plural, RUNNER_HOST_SCOPE_LABELS } from '../../../shared/labels';
import {
  failureClassLabel,
  formatCost,
  formatUsage,
  isRunActive,
  resumeText,
  runDuration,
  RunStatusBadge,
  turnReasonPhrase,
  usageTitle,
} from '../../../shared/run-status';
import { formatDay, Time } from '../../../shared/time';
import { WhoActsWhoPays } from '../../../shared/who-acts-who-pays';
import { RunRepositories } from './run-repositories';
import { Transcript } from './transcript';

/**
 * A run on its own page (plan §5: a turn of a conversation, reached from its status line): the
 * agent, the turn and its conversation, who it acts for and who pays and on which runner host,
 * why it waits while queued, its workspace with the git result per repository, the full-height
 * live transcript, and the agent's result when it called `finish`. Whoever sees the run (its
 * person, the Owner) cancels it while it is queued or running.
 */
@Component({
  selector: 'app-run-page',
  imports: [
    RouterLink,
    NgIcon,
    HlmButton,
    HlmSkeleton,
    HlmSpinner,
    ErrorState,
    Markdown,
    QueueReasonText,
    RunRepositories,
    RunStatusBadge,
    Time,
    Transcript,
    WhoActsWhoPays,
  ],
  providers: [provideIcons({ lucideChevronLeft, lucideClock, lucideSquare })],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (runRes.error() && !run()) {
      <app-error-state
        [error]="runRes.error()"
        [heading]="notFound() ? 'Run not found' : 'Could not open this run'"
        [messages]="{ 'status:404': 'This run does not exist, or it belongs to another company.' }"
        (retry)="runRes.reload()"
      />
    } @else if (run(); as r) {
      <div class="flex flex-col gap-4">
        <header class="flex flex-col gap-2">
          <a
            class="text-muted-foreground hover:text-foreground inline-flex w-fit items-center gap-1 text-sm font-medium"
            data-testid="run-back"
            [routerLink]="['/c', companyId(), 'conversations', r.conversationId]"
          >
            <ng-icon name="lucideChevronLeft" aria-hidden="true" />
            <span class="font-mono text-xs">{{ r.conversationKey }}</span>
          </a>
          <div class="flex flex-wrap items-center gap-2">
            <span
              class="bg-primary text-primary-foreground inline-flex size-7 shrink-0 items-center justify-center rounded-md text-[0.6875rem] font-bold"
              aria-hidden="true"
              >{{ initials() }}</span
            >
            <h1 class="text-xl font-bold tracking-tight text-balance sm:text-2xl">
              {{ agentName() ?? 'Turn ' + r.turnNumber }}
            </h1>
            <app-run-status-badge [status]="r.status" />
            @if (canCancel()) {
              <button
                hlmBtn
                variant="destructive"
                size="sm"
                type="button"
                class="ms-auto"
                data-testid="cancel-run"
                [disabled]="cancelBusy()"
                (click)="cancel()"
              >
                @if (cancelBusy()) {
                  <hlm-spinner aria-hidden="true" />
                } @else {
                  <ng-icon name="lucideSquare" aria-hidden="true" />
                }
                Cancel run
              </button>
            }
          </div>
          <p class="text-muted-foreground text-sm" data-testid="run-turn">
            Turn {{ r.turnNumber }}
            @if (reason(); as why) {
              · {{ why }}
            }
            · in
            <a
              class="text-foreground hover:decoration-primary focus-visible:ring-ring/50 rounded-sm underline decoration-1 underline-offset-2 outline-none focus-visible:ring-3"
              data-testid="run-conversation"
              [routerLink]="['/c', companyId(), 'conversations', r.conversationId]"
              ><span class="font-mono text-xs">{{ r.conversationKey }}</span>
              @if (conversationTitle(); as title) {
                {{ title }}
              }
            </a>
          </p>
          @if (r.status === 'QUEUED') {
            <p
              class="bg-card text-muted-foreground flex items-start gap-2 rounded-xl border px-3.5 py-2 text-sm"
              data-testid="run-queue-reason"
            >
              <ng-icon name="lucideClock" class="mt-0.5 shrink-0" aria-hidden="true" />
              <span
                ><app-queue-reason [run]="r" [companyId]="companyId()" [reader]="reader()"
              /></span>
            </p>
          }
          <dl
            class="text-muted-foreground flex flex-wrap gap-x-5 gap-y-1 text-xs [&_dd]:text-foreground [&_div]:flex [&_div]:gap-1"
          >
            <div>
              <dt>Queued</dt>
              <dd><app-time [value]="r.createdAt" /></dd>
            </div>
            @if (duration(); as d) {
              <div>
                <dt>{{ active() ? 'Running for' : 'Duration' }}</dt>
                <dd class="font-mono tabular-nums">{{ d }}</dd>
              </div>
            }
            @if (r.runnerHostName; as host) {
              <div data-testid="run-host">
                <dt>Runner host</dt>
                <dd>{{ host }}{{ r.runnerHostKind ? ', ' + hostKinds[r.runnerHostKind] : '' }}</dd>
              </div>
            }
            @if (session(); as s) {
              <div data-testid="run-session">
                <dt>Session</dt>
                <dd>{{ s }}</dd>
              </div>
            }
            @if (usage(); as u) {
              <div data-testid="run-tokens">
                <dt>Tokens</dt>
                <dd class="font-mono tabular-nums" [title]="u.title">{{ u.text }}</dd>
              </div>
            }
            @if (r.branch) {
              <div>
                <dt>Branch</dt>
                <dd>
                  <code class="font-mono">{{ r.branch }}</code>
                </dd>
              </div>
            }
          </dl>
          <app-who-acts-who-pays [run]="r" heading="For whom, paid by whom" [columns]="true" />
          @if (r.repositories.length > 0) {
            <app-run-repositories
              class="bg-card rounded-xl border px-4 py-3"
              [repositories]="r.repositories"
              [active]="active()"
            />
          }
          @if (r.failureClass || r.failureMessage) {
            <p
              class="border-destructive/40 bg-destructive/5 text-destructive rounded-lg border px-3 py-2 text-sm"
              role="note"
              data-testid="run-failure"
            >
              @if (failureLabel(); as label) {
                <strong [title]="r.failureClass ?? ''">{{ label }}.</strong>
              }
              {{ r.failureMessage }}
            </p>
          }
          @if (r.status === 'CANCELLED') {
            <p
              class="bg-card text-muted-foreground rounded-lg border px-3 py-2 text-sm"
              role="note"
              data-testid="run-cancelled"
            >
              This turn was cancelled{{ r.startedAt ? '' : ' before a runner host took it' }}.
              Messages that waited for it stay unanswered; retry it from
              <a
                class="text-foreground underline underline-offset-2"
                [routerLink]="['/c', companyId(), 'conversations', r.conversationId]"
                >the conversation</a
              >.
            </p>
          }
          @if (r.textsPurgedAt; as at) {
            <p
              class="bg-card text-muted-foreground rounded-lg border px-3 py-2 text-sm italic"
              role="note"
              data-testid="run-texts-purged"
            >
              Its summary, result and failure message were removed on {{ day(at) }} under the
              company’s retention.
            </p>
          }
        </header>

        @if (r.eventsPurgedAt; as at) {
          <section
            class="bg-card text-muted-foreground flex flex-col items-center gap-1 rounded-[20px] border border-dashed px-4 py-10 text-center text-sm"
            aria-label="Run events"
            data-testid="run-events-purged"
          >
            <p class="text-foreground font-medium">
              The transcript was removed on {{ day(at) }} under the company’s retention.
            </p>
            @if (r.counts; as c) {
              <p>
                It held {{ count(c.messages, 'message') }},
                {{ count(c.toolCalls, 'tool call') }} and {{ count(c.thoughts, 'thought') }}.
              </p>
            }
          </section>
        } @else {
          <app-transcript
            class="bg-card h-[max(24rem,calc(100svh-18rem))] rounded-[20px] px-2 pt-3 pb-1 sm:px-4"
            [companyId]="companyId()"
            [runId]="runId()"
            [run]="r"
            (runChange)="runRes.set($event)"
          />
        }

        @if (r.result; as result) {
          <!-- The agent's finish: outcome, summary, next steps, open questions. -->
          <section
            aria-labelledby="result-title"
            class="bg-card flex flex-col gap-3 rounded-xl border p-4"
            data-testid="run-result"
            [attr.data-outcome]="result.outcome"
          >
            <div class="flex flex-wrap items-center gap-2">
              <h2 id="result-title" class="text-sm font-semibold">Result</h2>
              <span
                class="inline-flex h-6 items-center rounded-full px-2.5 text-xs font-semibold"
                [class]="
                  result.outcome === 'BLOCKED'
                    ? 'bg-human-soft text-human'
                    : 'bg-success/10 text-success'
                "
                >{{ result.outcome === 'BLOCKED' ? 'Blocked' : 'Completed' }}</span
              >
            </div>
            <app-markdown [text]="result.summary" />
            @if (result.nextSteps.length > 0) {
              <div data-testid="run-result-next-steps">
                <h3 class="text-muted-foreground mb-1 text-xs font-semibold">Next steps</h3>
                <ul class="list-disc space-y-0.5 ps-5 text-sm">
                  @for (step of result.nextSteps; track $index) {
                    <li class="break-words">{{ step }}</li>
                  }
                </ul>
              </div>
            }
            @if (result.openQuestions.length > 0) {
              <div data-testid="run-result-open-questions">
                <h3 class="text-muted-foreground mb-1 text-xs font-semibold">Open questions</h3>
                <ul class="list-disc space-y-0.5 ps-5 text-sm">
                  @for (question of result.openQuestions; track $index) {
                    <li class="break-words">{{ question }}</li>
                  }
                </ul>
                @if (result.outcome === 'BLOCKED') {
                  <p class="text-muted-foreground mt-1.5 text-xs">
                    Each one is a question in
                    <a
                      class="hover:text-foreground underline underline-offset-2"
                      [routerLink]="['/c', companyId(), 'conversations', r.conversationId]"
                      >the conversation {{ r.conversationKey }}</a
                    >, answered there.
                  </p>
                }
              </div>
            }
          </section>
        } @else if (r.summary && !active()) {
          <section aria-labelledby="summary-title" class="bg-card rounded-xl border p-4">
            <h2 id="summary-title" class="mb-2 text-sm font-semibold">Summary</h2>
            <app-markdown [text]="r.summary" />
          </section>
        }
      </div>
    } @else {
      <div class="flex flex-col gap-3" aria-busy="true">
        <span class="sr-only" role="status">Loading…</span>
        <hlm-skeleton class="h-4 w-24" />
        <hlm-skeleton class="h-7 w-1/2" />
        <hlm-skeleton class="h-96 w-full" />
      </div>
    }
  `,
})
export class RunPage {
  private readonly api = inject(Api);
  private readonly notify = inject(Notify);
  protected readonly current = inject(CurrentCompany);

  /** From the route (`/c/:companyId/runs/:runId`). */
  readonly runId = input.required<string>();

  protected readonly companyId = computed(() => this.current.id() ?? '');

  protected readonly runRes = resource({
    params: () => {
      const companyId = this.current.id();
      return companyId ? { companyId, runId: this.runId() } : undefined;
    },
    loader: ({ params }) => this.api.invoke(getRun, params),
  });
  protected readonly run = computed<Run | undefined>(() => {
    const value = this.runRes.hasValue() ? this.runRes.value() : undefined;
    return value?.id === this.runId() ? value : undefined;
  });

  /** The conversation, for its title and its agent's name (the run names only their ids). */
  private readonly conversationKey = computed(
    () => {
      const companyId = this.current.id();
      const conversationId = this.run()?.conversationId;
      return companyId && conversationId ? { companyId, conversationId } : undefined;
    },
    { equal: sameParams },
  );
  private readonly conversation = resource({
    params: () => this.conversationKey(),
    loader: ({ params }) => this.api.invoke(getConversation, params),
  });
  protected readonly conversationTitle = computed(() =>
    this.conversation.hasValue() ? (this.conversation.value()?.title ?? null) : null,
  );
  protected readonly agentName = computed(() =>
    this.conversation.hasValue() ? (this.conversation.value()?.agentName ?? null) : null,
  );

  /** Who reads the queue reason: a member is sent to their Accounts, an admin to the hosts. */
  protected readonly reader = computed(() => {
    const access = this.current.access();
    return { member: access.role !== null, admin: managesCompany(access) };
  });
  protected readonly hostKinds = RUNNER_HOST_SCOPE_LABELS;

  protected readonly notFound = computed(() => toProblem(this.runRes.error()).status === 404);
  protected readonly active = computed(() => isRunActive(this.run()?.status));
  /** Whoever sees a run may cancel it (CONVENTIONS §6: `mayCancel` is `maySee`). */
  protected readonly canCancel = this.active;
  protected readonly initials = computed(() => initials(this.agentName()));
  protected readonly reason = computed(() => turnReasonPhrase(this.run()?.turnReason));
  protected readonly duration = computed(() => {
    const r = this.run();
    return r ? runDuration(r.startedAt, r.finishedAt) : null;
  });
  protected readonly failureLabel = computed(() => failureClassLabel(this.run()?.failureClass));
  protected readonly session = computed(() => {
    const r = this.run();
    return r ? resumeText(r) : null;
  });
  protected readonly cancelBusy = signal(false);

  /** The run's tokens and cost on one line; the tooltip spells out each count. */
  protected readonly usage = computed(() => {
    const u = this.run()?.usage;
    if (!u) return null;
    return {
      text: [formatUsage(u), formatCost(u.costUsd)].filter(Boolean).join(' · '),
      title: usageTitle(u),
    };
  });

  protected readonly day = formatDay;
  protected readonly count = plural;

  protected async cancel(): Promise<void> {
    const companyId = this.current.id();
    if (!companyId || this.cancelBusy()) return;
    this.cancelBusy.set(true);
    try {
      this.runRes.set(await this.api.invoke(cancelRun, { companyId, runId: this.runId() }));
      this.notify.success('Cancelling the run');
    } catch (error) {
      this.notify.error("Couldn't cancel the run", error);
    } finally {
      this.cancelBusy.set(false);
    }
  }
}
