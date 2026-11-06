import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  resource,
  signal,
  untracked,
} from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { NgIcon, provideIcons } from '@ng-icons/core';
import {
  lucideArrowLeft,
  lucideCircleAlert,
  lucideClock,
  lucidePause,
  lucidePencil,
  lucidePlay,
  lucideTrash2,
  lucideUserRoundCog,
  lucideZap,
} from '@ng-icons/lucide';
import { HlmAlertImports } from '@spartan-ng/helm/alert';
import { HlmAlertDialogImports } from '@spartan-ng/helm/alert-dialog';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmSpinner } from '@spartan-ng/helm/spinner';
import { HlmSwitch } from '@spartan-ng/helm/switch';
import { Api } from '../../../api/generated/api';
import {
  acceptTriggerWidening,
  deleteTrigger,
  getAgent,
  getTrigger,
  getTriggerSettings,
  pauseTrigger,
  resumeTrigger,
  updateTrigger,
} from '../../../api/generated/functions';
import type { Trigger, TriggerFiring } from '../../../api/generated/models';
import { hasProblemCode, problemMessage, toProblem } from '../../../api/problem';
import { CallerPrincipal } from '../../../core/caller-principal';
import { CurrentCompany } from '../../../core/current-company';
import { focusFragmentWhenReady } from '../../../core/fragment-focus';
import { Notify } from '../../../core/notify';
import { ErrorState } from '../../../shared/error-state';
import { ListSkeleton } from '../../../shared/list-skeleton';
import { Markdown } from '../../../shared/markdown';
import { MemoryNotesPanel, type MemoryOwner } from '../../../shared/memory-notes';
import { Time } from '../../../shared/time';
import { WhoActsWhoPays } from '../../../shared/who-acts-who-pays';
import { NotFound } from '../../not-found';
import { ReassignDialog } from './reassign-dialog';
import { RunNowDialog } from './run-now-dialog';
import { TriggerFirings } from './trigger-firings';
import {
  actionText,
  EVENT_LABELS,
  OUTCOME_LABELS,
  PAUSED_REASON_FIX,
  PAUSED_REASON_LABELS,
  sourceText,
  SUBJECT_LABELS,
  WAITING_REASON_LABELS,
  WEBHOOK_OP_LABELS,
} from './trigger-labels';
import { TriggerStateChips } from './trigger-state-chips';
import { fromTrigger } from './trigger-view';
import { TriggerWideningCard } from './trigger-widening-card';

/**
 * A trigger (plan §7's W5 row; the package 4 contract, §5.1, §5.3, §5.6 and §5.11),
 * `triggers/:triggerId`, for its author, its team and admins (anyone else gets the not-found
 * page): its sentence and state, why it is paused or waits and what fixes it, who acts and who
 * pays, a new version of its agent to accept, its results and whether the team reviews them, its
 * memory's notes and the proposals to review (ADR-0063), and its firings. The buttons follow
 * `rights`: Run now (its 409s worded), Pause and Resume, Edit, Reassign (an admin acting for an
 * author who left) and Delete. A trigger reassigned to the reader (paused `TO_ACCEPT`) offers its
 * acceptance (`acceptTriggerWidening`): its accounts, its payer and, where its open events would
 * write without approval, the reader's acknowledgement, which resumes it (H2).
 */
@Component({
  selector: 'app-trigger-page',
  imports: [
    RouterLink,
    NgIcon,
    HlmAlertImports,
    HlmAlertDialogImports,
    HlmButton,
    HlmSpinner,
    HlmSwitch,
    ErrorState,
    ListSkeleton,
    Markdown,
    MemoryNotesPanel,
    NotFound,
    ReassignDialog,
    RunNowDialog,
    Time,
    TriggerFirings,
    TriggerStateChips,
    TriggerWideningCard,
    WhoActsWhoPays,
  ],
  providers: [
    provideIcons({
      lucideArrowLeft,
      lucideCircleAlert,
      lucideClock,
      lucidePause,
      lucidePencil,
      lucidePlay,
      lucideTrash2,
      lucideUserRoundCog,
      lucideZap,
    }),
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @let companyId = current.id();
    @if (notFound()) {
      <app-not-found />
    } @else {
      <div class="flex flex-col gap-5">
        <a
          hlmBtn
          variant="ghost"
          size="sm"
          class="-ms-3 self-start"
          [routerLink]="['/c', companyId, 'triggers']"
        >
          <ng-icon name="lucideArrowLeft" aria-hidden="true" />
          Triggers
        </a>

        @if (loading()) {
          <app-list-skeleton [rows]="4" />
        } @else if (loadError(); as error) {
          <app-error-state [error]="error" heading="Couldn't load the trigger" (retry)="reload()" />
        } @else if (trigger(); as t) {
          <header class="flex min-w-0 flex-col gap-3">
            <div class="flex min-w-0 flex-col gap-2 sm:flex-row sm:items-start sm:justify-between">
              <div class="flex min-w-0 flex-col gap-1.5">
                <div class="flex min-w-0 flex-wrap items-center gap-2">
                  <h1 class="text-xl font-bold tracking-tight break-words sm:text-2xl">
                    {{ t.name }}
                  </h1>
                  <app-trigger-state-chips
                    [status]="t.status"
                    [pausedReason]="t.pausedReason ?? null"
                    [waitingReason]="t.waitingReason ?? null"
                  />
                </div>
                <p class="text-sm text-pretty" data-testid="trigger-sentence">{{ t.sentence }}</p>
                <p class="text-muted-foreground text-xs break-words" data-testid="trigger-meta">
                  {{ sourceText(t.source, t.systemName) }} ·
                  <a
                    class="underline-offset-2 hover:underline"
                    [routerLink]="['/c', companyId, 'agents', t.agentId]"
                    >{{ t.agentName ?? 'its agent' }}</a
                  >, version {{ t.acceptedVersion }} · by {{ t.authorName ?? 'a former member' }}
                  @if (t.teamName) {
                    · team {{ t.teamName }}
                  } @else {
                    · no team: its author decides
                  }
                </p>
              </div>
              <div
                class="flex flex-wrap gap-2 sm:shrink-0 sm:justify-end"
                data-testid="trigger-actions"
              >
                @if (t.rights.runs && runsNow(t)) {
                  <button
                    hlmBtn
                    size="sm"
                    type="button"
                    data-testid="run-now"
                    [disabled]="t.status === 'PAUSED'"
                    [attr.title]="t.status === 'PAUSED' ? 'A paused trigger does not run' : null"
                    (click)="runOpen.set(true)"
                  >
                    <ng-icon name="lucideZap" aria-hidden="true" />
                    Run now
                  </button>
                }
                @if (t.status === 'ACTIVE' && t.rights.pauses) {
                  <button
                    hlmBtn
                    variant="outline"
                    size="sm"
                    type="button"
                    data-testid="pause-trigger"
                    (click)="openPause()"
                  >
                    <ng-icon name="lucidePause" aria-hidden="true" />
                    Pause
                  </button>
                }
                @if (t.status === 'PAUSED' && t.rights.resumes && t.pausedReason !== 'TO_ACCEPT') {
                  <button
                    hlmBtn
                    variant="outline"
                    size="sm"
                    type="button"
                    data-testid="resume-trigger"
                    [disabled]="busy()"
                    (click)="resume()"
                  >
                    @if (busy() && pending() === 'resume') {
                      <hlm-spinner aria-hidden="true" />
                    } @else {
                      <ng-icon name="lucidePlay" aria-hidden="true" />
                    }
                    Resume
                  </button>
                }
                @if (t.rights.edits) {
                  <a
                    hlmBtn
                    variant="outline"
                    size="sm"
                    data-testid="edit-trigger"
                    [routerLink]="['/c', companyId, 'triggers', t.id, 'edit']"
                  >
                    <ng-icon name="lucidePencil" aria-hidden="true" />
                    Edit
                  </a>
                }
                @if (t.rights.reassigns) {
                  <button
                    hlmBtn
                    variant="outline"
                    size="sm"
                    type="button"
                    data-testid="reassign-trigger"
                    (click)="reassignOpen.set(true)"
                  >
                    <ng-icon name="lucideUserRoundCog" aria-hidden="true" />
                    Reassign
                  </button>
                }
                @if (t.rights.deletes) {
                  <button
                    hlmBtn
                    variant="ghost"
                    size="sm"
                    type="button"
                    class="text-destructive"
                    data-testid="delete-trigger"
                    (click)="openDelete()"
                  >
                    <ng-icon name="lucideTrash2" aria-hidden="true" />
                    Delete
                  </button>
                }
              </div>
            </div>
          </header>

          @if (actionError(); as message) {
            <div hlmAlert variant="destructive" data-testid="trigger-action-error">
              <ng-icon name="lucideCircleAlert" aria-hidden="true" />
              <p hlmAlertDescription>{{ message }}</p>
            </div>
          }

          @if (lastRun(); as f) {
            <p
              class="bg-card flex flex-wrap items-center gap-x-1.5 rounded-xl border px-4 py-3 text-sm"
              role="status"
              data-testid="run-now-result"
            >
              Run now{{ f.subjectKey ? ' on ' + f.subjectKey : '' }}: {{ outcomeLabels[f.outcome] }}
              @if (f.conversationId) {
                ·
                <a
                  class="text-primary font-semibold underline-offset-2 hover:underline"
                  [routerLink]="['/c', companyId, 'conversations', f.conversationId]"
                  >Open its conversation</a
                >
              }
            </p>
          }

          @if (t.status === 'PAUSED' && t.pausedReason) {
            <section
              class="bg-card flex flex-col gap-1.5 rounded-xl border px-4 py-3 text-sm"
              [class.border-human]="pausedNeedsMe()"
              data-testid="paused-notice"
              aria-labelledby="paused-title"
            >
              <h2 class="font-semibold" [class.text-human]="pausedNeedsMe()" id="paused-title">
                Paused: {{ pausedLabels[t.pausedReason] }}
              </h2>
              <p class="text-muted-foreground text-pretty">{{ pausedFix[t.pausedReason] }}</p>
              @if (t.pausedAt) {
                <p class="text-muted-foreground text-xs">Since <app-time [value]="t.pausedAt" /></p>
              }
              @if (t.pausedReason === 'AUTHOR_LEFT' && t.rights.reassigns) {
                <button
                  hlmBtn
                  size="sm"
                  type="button"
                  class="mt-1 self-start"
                  (click)="reassignOpen.set(true)"
                >
                  Reassign it
                </button>
              }
              @if (t.pausedReason === 'TO_ACCEPT') {
                @if (isAuthor()) {
                  <div class="mt-1 flex flex-col gap-2" data-testid="accept-reassigned">
                    <p class="text-pretty">
                      It was reassigned to you. Accept the accounts and the payer below, as Who acts
                      and who pays shows them{{
                        t.wideningPending
                          ? ', and version ' +
                            t.currentVersion +
                            ' of ' +
                            (t.agentName ?? 'its agent')
                          : ''
                      }}, to resume it, or change them first.
                    </p>
                    @if (ackNeeded(); as message) {
                      <p class="text-pretty" role="alert" data-testid="accept-ack-needed">
                        {{ message }}
                      </p>
                      <label class="flex min-h-11 items-start gap-2 sm:min-h-0">
                        <input
                          type="checkbox"
                          class="accent-primary mt-0.5 size-4 shrink-0"
                          id="trigger-accept-acknowledge"
                          [checked]="acceptAck()"
                          (change)="acceptAck.set($any($event.target).checked)"
                        />
                        <span
                          ><span class="font-semibold">I understand</span
                          ><span class="text-muted-foreground"
                            >: anyone who may write at its source drives the agent, which then
                            writes without asking with these accounts.</span
                          ></span
                        >
                      </label>
                    }
                    <div class="flex flex-wrap gap-2">
                      <button
                        hlmBtn
                        size="sm"
                        type="button"
                        data-testid="accept-trigger"
                        [disabled]="busy() || (!!ackNeeded() && !acceptAck())"
                        (click)="acceptReassigned()"
                      >
                        @if (busy() && pending() === 'accept') {
                          <hlm-spinner aria-hidden="true" />
                        }
                        Accept and resume
                      </button>
                      @if (t.rights.edits) {
                        <a
                          hlmBtn
                          variant="outline"
                          size="sm"
                          [routerLink]="['/c', companyId, 'triggers', t.id, 'edit']"
                          >Change it first</a
                        >
                      }
                    </div>
                  </div>
                } @else {
                  <p class="text-muted-foreground text-xs" data-testid="to-accept-waits">
                    Waiting for {{ t.authorName ?? 'its new author' }} to accept it.
                  </p>
                }
              }
            </section>
          } @else if (t.waitingReason; as reason) {
            <section
              class="bg-card flex flex-col gap-1 rounded-xl border px-4 py-3 text-sm"
              data-testid="waiting-notice"
            >
              <p class="flex items-center gap-1.5 font-semibold">
                <ng-icon name="lucideClock" aria-hidden="true" />
                {{ waitingLabels[reason] }}
              </p>
              <p class="text-muted-foreground">
                It fires nothing meanwhile, and goes on by itself once that is fixed.
                @if (t.waitingSince) {
                  Since <app-time [value]="t.waitingSince" />.
                }
              </p>
            </section>
          }

          @if (t.wideningPending && companyId && t.pausedReason !== 'TO_ACCEPT') {
            <app-trigger-widening-card
              [companyId]="companyId"
              [trigger]="t"
              [widening]="settings()?.widening ?? null"
              [agent]="agent()"
              (accepted)="onAccepted($event)"
            />
          }

          <div class="grid min-w-0 gap-5 lg:grid-cols-2">
            <app-who-acts-who-pays
              heading="Who acts and who pays"
              intro="Each new conversation of the trigger binds these accounts at its start, and keeps them."
              [view]="view()"
            />

            <section
              class="bg-card flex flex-col gap-3 rounded-xl border p-4 text-sm"
              aria-labelledby="rule-title"
              data-testid="trigger-rule"
            >
              <h2 class="text-sm font-semibold" id="rule-title">The rule</h2>
              <dl class="grid gap-2">
                @if (t.source === 'TRACKER') {
                  <div class="flex flex-col gap-0.5">
                    <dt class="text-muted-foreground text-xs font-semibold">When</dt>
                    <dd>
                      @for (e of t.events; track $index) {
                        <span class="block"
                          >{{ eventLabels[e.event] }}{{ e.value ? ': ' + e.value : '' }}</span
                        >
                      }
                    </dd>
                  </div>
                  <div class="flex flex-col gap-0.5">
                    <dt class="text-muted-foreground text-xs font-semibold">On</dt>
                    <dd>
                      {{ subjectLabels[t.subject] }}
                      @if (t.scope) {
                        in <span class="font-mono text-xs">{{ t.scope }}</span>
                      } @else {
                        anywhere its account sees
                      }
                      · {{ t.repeat === 'ONCE' ? 'once per issue' : 'every time' }}
                    </dd>
                  </div>
                } @else if (t.source === 'CHANNEL') {
                  <div class="flex flex-col gap-0.5">
                    <dt class="text-muted-foreground text-xs font-semibold">When</dt>
                    <dd>Someone mentions it in a channel of {{ t.systemName ?? 'its chat' }}</dd>
                  </div>
                  <div class="flex flex-col gap-0.5">
                    <dt class="text-muted-foreground text-xs font-semibold">Channels</dt>
                    <dd data-testid="trigger-channels-list">
                      @for (c of t.channels ?? []; track c.channelId) {
                        <span class="block" [attr.data-channel]="c.channelId">
                          #{{ c.name ?? c.channelId }}
                          @if (c.default) {
                            <span class="text-muted-foreground">· its default</span>
                          }
                          @if (c.state === 'UNAVAILABLE') {
                            <span class="text-muted-foreground">· the bot cannot use it now</span>
                          }
                        </span>
                      }
                    </dd>
                  </div>
                } @else if (t.source === 'WEBHOOK') {
                  <div class="flex flex-col gap-0.5">
                    <dt class="text-muted-foreground text-xs font-semibold">When</dt>
                    <dd data-testid="trigger-webhook-events">
                      {{ t.systemName ?? 'Its webhook' }} delivers
                      @if (webhookEvents(t); as events) {
                        <span class="font-mono text-xs">{{ events }}</span>
                      } @else {
                        anything
                      }
                    </dd>
                  </div>
                  @if (t.webhookConditions?.length) {
                    <div class="flex flex-col gap-0.5">
                      <dt class="text-muted-foreground text-xs font-semibold">Only when</dt>
                      <dd>
                        @for (c of t.webhookConditions; track $index) {
                          <span class="block">
                            <span class="font-mono text-xs">{{ c.pointer }}</span>
                            {{ opLabels[c.op] }} {{ (c.values ?? []).join(', ') }}
                          </span>
                        }
                      </dd>
                    </div>
                  }
                } @else if (t.schedule; as s) {
                  <div class="flex flex-col gap-0.5">
                    <dt class="text-muted-foreground text-xs font-semibold">Schedule</dt>
                    <dd>
                      <span class="font-mono text-xs">{{ s.cron }}</span> in {{ s.zone }}
                      @if (s.nextRunAt) {
                        · next <app-time [value]="s.nextRunAt" mode="absolute" />
                      }
                    </dd>
                  </div>
                }
                <div class="flex flex-col gap-0.5">
                  <dt class="text-muted-foreground text-xs font-semibold">Instruction</dt>
                  <dd class="max-h-48 overflow-y-auto">
                    <app-markdown [text]="t.instruction" />
                  </dd>
                </div>
                <div class="flex flex-col gap-0.5">
                  <dt class="text-muted-foreground text-xs font-semibold">Limits</dt>
                  <dd>
                    At most {{ t.maxFiringsPerHour }} firings an hour
                    @if (t.rateSkippedCount) {
                      · {{ t.rateSkippedCount }} skipped over it
                    }
                    @if (t.missedCount) {
                      · {{ t.missedCount }} changes read too late
                    }
                  </dd>
                </div>
              </dl>
            </section>
          </div>

          <section
            class="bg-card flex flex-col gap-3 rounded-xl border p-4 text-sm"
            aria-labelledby="results-title"
            data-testid="trigger-results"
          >
            <h2 class="text-sm font-semibold" id="results-title">Results</h2>
            @if (t.source === 'TRACKER') {
              <p>
                {{
                  t.results.comment
                    ? 'Each result and failure is commented on the issue'
                    : 'Nothing is commented on the issue'
                }}, as {{ sourceHolder() }}.
              </p>
              @if (t.results.onSuccess.length) {
                <p>
                  When it succeeds:
                  {{ actions(t.results.onSuccess) }}.
                </p>
              }
              @if (t.results.onFailure.length) {
                <p>When it fails: {{ actions(t.results.onFailure) }}.</p>
              }
            } @else {
              <p>
                @if (t.source === 'CHANNEL') {
                  Each answer is posted in its thread by the chat’s bot.
                } @else {
                  Results stay in Tinfirm, with its team.
                }
              </p>
            }
            <label class="flex items-start gap-3" for="trigger-page-review">
              <hlm-switch
                inputId="trigger-page-review"
                class="mt-px"
                [checked]="t.results.review"
                [disabled]="!t.rights.edits || busy()"
                (checkedChange)="setReview($event)"
              />
              <span class="flex flex-col gap-0.5">
                <span class="font-medium">The team reviews each result before it is written</span>
                <span class="text-muted-foreground text-xs">
                  @if (t.teamName) {
                    {{ t.teamName }} approves it, or returns it with a note.
                  } @else {
                    Without a team, {{ t.authorName ?? 'its author' }} reviews it.
                  }
                </span>
              </span>
            </label>
          </section>

          @if (companyId) {
            <app-memory-notes [companyId]="companyId" [owner]="memoryOwner()" [agent]="agent()" />
          }

          <section class="flex flex-col gap-3" aria-labelledby="firings-title">
            <div class="flex flex-wrap items-baseline justify-between gap-2 px-1">
              <h2 class="text-base font-bold" id="firings-title">Firings</h2>
              @if (t.openConversations) {
                <a
                  class="text-primary text-sm font-semibold underline-offset-2 hover:underline"
                  [routerLink]="['/c', companyId, 'conversations']"
                  [queryParams]="{ triggerId: t.id }"
                  data-testid="trigger-conversations"
                  >{{ t.openConversations }} open
                  {{ t.openConversations === 1 ? 'conversation' : 'conversations' }}</a
                >
              }
            </div>
            @if (companyId) {
              <app-trigger-firings
                [companyId]="companyId"
                [triggerId]="t.id"
                [revision]="firingsRevision()"
              />
            }
          </section>

          <app-run-now-dialog [(open)]="runOpen" [trigger]="t" (fired)="onFired($event)" />
          @if (t.rights.reassigns) {
            <app-reassign-dialog
              [(open)]="reassignOpen"
              [trigger]="t"
              (reassigned)="onReassigned($event)"
            />
          }

          <hlm-alert-dialog
            [state]="pauseOpen() ? 'open' : 'closed'"
            (closed)="pauseOpen.set(false)"
          >
            <hlm-alert-dialog-content *hlmAlertDialogPortal="let ctx">
              <hlm-alert-dialog-header>
                <h2 hlmAlertDialogTitle>Pause {{ t.name }}?</h2>
                <p hlmAlertDialogDescription>
                  It fires nothing, its conversations take no new turn and nothing is written back
                  until
                  {{ t.rights.resumes ? 'it is resumed' : 'its author or an admin resumes it' }}.
                  What happens meanwhile never fires it.
                </p>
              </hlm-alert-dialog-header>
              <label class="flex items-start gap-2 text-sm">
                <input
                  type="checkbox"
                  class="accent-primary mt-0.5 size-4 shrink-0"
                  data-testid="stop-running"
                  [checked]="stopRunning()"
                  (change)="stopRunning.set($any($event.target).checked)"
                />
                <span>
                  Also stop the turns running now
                  <span class="text-muted-foreground block text-xs"
                    >For a trigger that misbehaves: its conversations’ running turns are
                    cancelled.</span
                  >
                </span>
              </label>
              <hlm-alert-dialog-footer>
                <button hlmAlertDialogCancel [disabled]="busy()">Cancel</button>
                <button
                  hlmAlertDialogAction
                  data-testid="confirm-pause"
                  [disabled]="busy()"
                  (click)="pause()"
                >
                  @if (busy()) {
                    <hlm-spinner aria-hidden="true" />
                  }
                  Pause trigger
                </button>
              </hlm-alert-dialog-footer>
            </hlm-alert-dialog-content>
          </hlm-alert-dialog>

          <hlm-alert-dialog
            [state]="deleteOpen() ? 'open' : 'closed'"
            (closed)="deleteOpen.set(false)"
          >
            <hlm-alert-dialog-content *hlmAlertDialogPortal="let ctx">
              <hlm-alert-dialog-header>
                <h2 hlmAlertDialogTitle>Delete {{ t.name }}?</h2>
                <p hlmAlertDialogDescription>
                  Its open conversations end and what they have not written back is dropped; their
                  history stays. Its name is free again. You can’t undo this.
                </p>
              </hlm-alert-dialog-header>
              <hlm-alert-dialog-footer>
                <button hlmAlertDialogCancel [disabled]="busy()">Cancel</button>
                <button
                  hlmAlertDialogAction
                  variant="destructive"
                  data-testid="confirm-delete"
                  [disabled]="busy()"
                  (click)="remove()"
                >
                  @if (busy()) {
                    <hlm-spinner aria-hidden="true" />
                  }
                  Delete trigger
                </button>
              </hlm-alert-dialog-footer>
            </hlm-alert-dialog-content>
          </hlm-alert-dialog>
        }
      </div>
    }
  `,
})
export class TriggerPage {
  private readonly api = inject(Api);
  private readonly router = inject(Router);
  private readonly notify = inject(Notify);
  protected readonly current = inject(CurrentCompany);
  private readonly caller = inject(CallerPrincipal);

  readonly triggerId = input.required<string>();

  protected readonly eventLabels = EVENT_LABELS;
  protected readonly subjectLabels = SUBJECT_LABELS;
  protected readonly outcomeLabels = OUTCOME_LABELS;
  protected readonly pausedLabels = PAUSED_REASON_LABELS;
  protected readonly pausedFix = PAUSED_REASON_FIX;
  protected readonly waitingLabels = WAITING_REASON_LABELS;
  protected readonly opLabels = WEBHOOK_OP_LABELS;
  protected readonly sourceText = sourceText;

  private readonly params = computed(() => {
    const companyId = this.current.id();
    return companyId ? { companyId, triggerId: this.triggerId() } : undefined;
  });

  private readonly triggerRes = resource({
    params: () => this.params(),
    loader: ({ params }) => this.api.invoke(getTrigger, params),
  });
  /** The trigger as last answered: a read, or the answer of an action. */
  protected readonly trigger = signal<Trigger | null>(null);

  private readonly settingsRes = resource({
    params: () => {
      const t = this.trigger();
      const params = this.params();
      return params && t?.wideningPending ? params : undefined;
    },
    loader: ({ params }) => this.api.invoke(getTriggerSettings, params),
  });
  protected readonly settings = computed(() =>
    this.settingsRes.hasValue() ? (this.settingsRes.value() ?? null) : null,
  );
  /** The agent: what a new version adds, and the names of what its memory's notes are about. */
  private readonly agentId = computed(() => this.trigger()?.agentId);
  private readonly agentRes = resource({
    params: () => {
      const companyId = this.current.id();
      const agentId = this.agentId();
      return companyId && agentId ? { companyId, agentId } : undefined;
    },
    loader: ({ params }) => this.api.invoke(getAgent, params),
  });
  protected readonly agent = computed(() =>
    this.agentRes.hasValue() ? (this.agentRes.value() ?? null) : null,
  );

  protected readonly memoryOwner = computed<MemoryOwner>(() => ({
    kind: 'trigger',
    triggerId: this.triggerId(),
  }));

  protected readonly loading = computed(() => this.triggerRes.isLoading() && !this.trigger());
  protected readonly loadError = computed(() =>
    this.trigger() ? undefined : this.triggerRes.error(),
  );
  protected readonly notFound = computed(() => toProblem(this.triggerRes.error()).status === 404);

  protected readonly view = computed(() => {
    const t = this.trigger();
    return t ? fromTrigger(t, this.caller.id()) : null;
  });
  protected readonly sourceHolder = computed(() => {
    const t = this.trigger();
    const row = t?.accounts.find((a) => a.systemId === t.systemId);
    if (!row) return 'its account';
    if (row.holderPrincipalId === this.caller.id()) return 'your own account';
    if (row.credentialName)
      return `${row.holderName ?? 'its service account'} with ${row.credentialName}`;
    return `${row.holderName ?? 'its holder'}’s account`;
  });
  /** Whether the reader is the trigger's author: only they accept it (H2). */
  protected readonly isAuthor = computed(() => {
    const me = this.caller.id();
    return me !== null && me === this.trigger()?.authorPrincipalId;
  });
  /**
   * Orange where the reader is the one to act: their own broken account, an author to reassign, a
   * trigger reassigned to them.
   */
  protected readonly pausedNeedsMe = computed(() => {
    const t = this.trigger();
    if (!t) return false;
    if (t.pausedReason === 'AUTHOR_LEFT') return t.rights.reassigns;
    if (t.pausedReason === 'TO_ACCEPT') return this.isAuthor();
    if (t.pausedReason === 'USER' || t.pausedReason === 'SYSTEM_PAUSED') return false;
    return t.rights.edits;
  });

  protected readonly runOpen = signal(false);
  protected readonly reassignOpen = signal(false);
  protected readonly pauseOpen = signal(false);
  protected readonly deleteOpen = signal(false);
  protected readonly stopRunning = signal(false);
  protected readonly busy = signal(false);
  protected readonly pending = signal<string | null>(null);
  protected readonly actionError = signal<string | null>(null);
  protected readonly lastRun = signal<TriggerFiring | null>(null);
  protected readonly firingsRevision = signal(0);
  /** A reassigned trigger's acceptance refused for its open events: the message, and the tick. */
  protected readonly ackNeeded = signal<string | null>(null);
  protected readonly acceptAck = signal(false);

  constructor() {
    effect(() => {
      const t = this.triggerRes.hasValue() ? this.triggerRes.value() : undefined;
      if (t) untracked(() => this.trigger.set(t));
    });
    // The inbox's notes to review land on the memory (`#memory`).
    focusFragmentWhenReady(() => !!this.trigger());
  }

  protected reload(): void {
    this.triggerRes.reload();
  }

  /** Run now serves a tracker's issue or a schedule; a mention or a delivery fires the others. */
  protected runsNow(t: Trigger): boolean {
    return t.source === 'TRACKER' || t.source === 'SCHEDULE';
  }

  protected webhookEvents(t: Trigger): string {
    return t.events
      .map((e) => e.value)
      .filter(Boolean)
      .join(', ');
  }

  protected actions(list: Trigger['results']['onSuccess']): string {
    return list.map(actionText).join(', ');
  }

  protected openPause(): void {
    this.stopRunning.set(false);
    this.actionError.set(null);
    this.pauseOpen.set(true);
  }

  protected openDelete(): void {
    this.actionError.set(null);
    this.deleteOpen.set(true);
  }

  private async act(name: string, fn: () => Promise<Trigger | void>): Promise<boolean> {
    if (this.busy()) return false;
    this.busy.set(true);
    this.pending.set(name);
    this.actionError.set(null);
    try {
      const t = await fn();
      if (t) this.trigger.set(t);
      return true;
    } catch (error) {
      this.actionError.set(
        problemMessage(error, {
          TRIGGER_PAUSED: stillPaused(toProblem(error).detail),
        }),
      );
      // The server may have paused it again for its reason; show what it is now.
      this.triggerRes.reload();
      return false;
    } finally {
      this.busy.set(false);
      this.pending.set(null);
    }
  }

  protected async pause(): Promise<void> {
    const params = this.params();
    if (!params) return;
    const ok = await this.act('pause', () =>
      this.api.invoke(pauseTrigger, { ...params, body: { stopRunning: this.stopRunning() } }),
    );
    this.pauseOpen.set(false);
    if (ok) this.notify.success('Trigger paused', 'It fires nothing until it is resumed.');
  }

  protected async resume(): Promise<void> {
    const params = this.params();
    if (!params) return;
    const ok = await this.act('resume', () => this.api.invoke(resumeTrigger, params));
    if (ok) {
      this.notify.success('Trigger resumed', 'What changed while it was paused fires nothing.');
    }
  }

  protected async setReview(review: boolean): Promise<void> {
    const params = this.params();
    const t = this.trigger();
    if (!params || !t) return;
    await this.act('review', () =>
      this.api.invoke(updateTrigger, { ...params, body: { results: { ...t.results, review } } }),
    );
  }

  protected async remove(): Promise<void> {
    const params = this.params();
    if (!params) return;
    const ok = await this.act('delete', () => this.api.invoke(deleteTrigger, params));
    this.deleteOpen.set(false);
    if (ok) {
      this.notify.success('Trigger deleted', 'Its open conversations ended.');
      void this.router.navigate(['/c', params.companyId, 'triggers']);
    }
  }

  protected onFired(firing: TriggerFiring): void {
    this.lastRun.set(firing);
    this.firingsRevision.update((n) => n + 1);
    this.triggerRes.reload();
  }

  /**
   * The new author accepts a trigger reassigned to them (H2): its accounts, its payer, the agent's
   * version shown and, once asked for, the open events' acknowledgement; it then resumes through
   * the gate, which may still refuse (nothing is accepted then).
   */
  protected async acceptReassigned(): Promise<void> {
    const params = this.params();
    const t = this.trigger();
    if (!params || !t || this.busy()) return;
    this.busy.set(true);
    this.pending.set('accept');
    this.actionError.set(null);
    try {
      const accepted = await this.api.invoke(acceptTriggerWidening, {
        ...params,
        body: { version: t.currentVersion, acknowledgeOpenEvents: this.acceptAck() },
      });
      this.trigger.set(accepted);
      this.ackNeeded.set(null);
      this.acceptAck.set(false);
      this.notify.success('Trigger accepted', 'It is yours now, and it runs again.');
    } catch (error) {
      if (hasProblemCode(error, 'OPEN_EVENTS_NOT_ACKNOWLEDGED')) {
        this.ackNeeded.set(problemMessage(error));
      } else {
        this.actionError.set(
          problemMessage(error, { TRIGGER_PAUSED: stillPaused(toProblem(error).detail) }),
        );
        this.triggerRes.reload();
      }
    } finally {
      this.busy.set(false);
      this.pending.set(null);
    }
  }

  protected onAccepted(t: Trigger): void {
    this.trigger.set(t);
    this.notify.success('Version accepted', 'The trigger’s runs use it from their next turn.');
  }

  protected onReassigned(t: Trigger): void {
    this.trigger.set(t);
    this.notify.success(
      'Trigger reassigned',
      `${t.authorName ?? 'The new author'} is its author now. It stays paused until they accept it.`,
    );
  }
}

/** A refused resume: what still holds, as the gate said it. */
function stillPaused(detail: string | undefined): string {
  const text = detail?.trim();
  return text
    ? `It stays paused: ${text.replace(/^./, (c) => c.toLowerCase())}`
    : 'It stays paused: what paused it still holds. Fix that first.';
}
