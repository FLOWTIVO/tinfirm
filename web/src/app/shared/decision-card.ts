import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  input,
  linkedSignal,
  output,
  signal,
} from '@angular/core';
import { RouterLink } from '@angular/router';
import { NgIcon, provideIcons } from '@ng-icons/core';
import { lucideChevronRight } from '@ng-icons/lucide';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmSpinner } from '@spartan-ng/helm/spinner';
import { HlmTextarea } from '@spartan-ng/helm/textarea';
import { Api } from '../api/generated/api';
import { decideDecision } from '../api/generated/functions';
import type {
  AgentRepositoryAccess,
  DecideRequest,
  Decision,
  DecisionScope,
} from '../api/generated/models';
import { hasProblemCode } from '../api/problem';
import { Notify } from '../core/notify';
import { SubmissionKey } from '../core/submission-key';
import { Markdown } from './markdown';
import { Segmented, type SegmentedOption } from './segmented';
import { Time } from './time';

/** A button of an open decision: what it sends. */
export interface DecisionAction {
  /** Unique per button, and the suffix of its test id. */
  readonly id: string;
  readonly outcome: 'ANSWERED' | 'APPROVED' | 'DENIED';
  readonly scope?: DecisionScope;
  readonly label: string;
  readonly primary: boolean;
  /** Sent only with a note: a result sent back says why (the package 4 contract, §5.8). */
  readonly needsText?: boolean;
}

/** The longest answer or note the card sends. */
const MAX_TEXT = 20000;

export const DECISION_KIND_LABELS: Record<Decision['kind'], string> = {
  QUESTION: 'Question',
  APPROVAL: 'Approval',
};

/** The card's label: a question, an approval, or the review of a trigger's result. */
export function decisionKindLabel(d: Decision): string {
  return d.subject?.type === 'RESULT' ? 'Review' : DECISION_KIND_LABELS[d.kind];
}

/**
 * Who decides it, as the server says (never derived here): a trigger's team, the person, or for a
 * trigger's question its author and team in Tinfirm and anyone on the issue (package 4, §5.8).
 */
export function approverText(d: Decision): string {
  if (d.approverGroupName) return `the team ${d.approverGroupName}`;
  if (d.approverName) return d.approverName;
  if (d.kind === 'QUESTION' && !d.approverPrincipalId && !d.approverGroupId) {
    return 'an answer from the trigger’s author or team, or on the issue';
  }
  return 'its approver';
}

/** What the agent wants, after its name: "asks", "wants to comment on SHOP-241"… */
export function decisionTitle(d: Decision): string {
  const agent = d.agentName ?? 'The agent';
  const open = d.status === 'OPEN';
  if (d.kind === 'QUESTION') return `${agent} ${open ? 'asks' : 'asked'}`;
  const wants = open ? 'wants' : 'wanted';
  const s = d.subject;
  switch (s?.type) {
    case 'TOOL_CALL':
      return s.what
        ? `${agent} ${wants} to ${s.what}`
        : `${agent} ${wants} to call ${s.tool ?? s.name ?? 'a tool'}${s.systemName ? ` on ${s.systemName}` : ''}`;
    case 'REPOSITORY':
      return `${agent} ${wants} repository ${s.repositoryName ?? ''}`.trimEnd();
    case 'HOST':
      return `${agent} ${wants} to reach ${s.host ?? 'a network host'}`;
    case 'CHANGE_REQUEST':
      return `${agent} ${wants} to open a change request${s.repositoryName ? ` on ${s.repositoryName}` : ''}`;
    case 'RESULT':
      return open
        ? `${agent}’s result waits for a review before anything is written`
        : `${agent}’s result was reviewed`;
    default:
      return `${agent} ${wants} your approval`;
  }
}

/** What the card shows once decided: the outcome, with the scope of a repository or a host. */
export function decidedLabel(d: Decision): string {
  if (d.status === 'CANCELLED' || !d.outcome) return 'Cancelled';
  if (d.outcome === 'ANSWERED') return 'Answered';
  if (d.subject?.type === 'RESULT')
    return d.outcome === 'DENIED' ? 'Sent back' : 'Approved, written';
  if (d.outcome === 'DENIED') return 'Denied';
  return d.scope === 'AGENT' ? 'Allowed for the agent, for good' : 'Approved';
}

/** "Comment on SHOP-241": a tool call in words, as the start of a sentence. */
export function toolCallWhat(s: {
  what?: string | null;
  tool?: string | null;
  name?: string | null;
}): string {
  const what = s.what ?? `call ${s.tool ?? s.name ?? 'a tool'}`;
  return what.charAt(0).toUpperCase() + what.slice(1);
}

/** A tool call's arguments as the card shows them: pretty JSON. */
export function prettyArguments(args: unknown): string | null {
  if (args === null || args === undefined) return null;
  if (typeof args === 'string') {
    try {
      return JSON.stringify(JSON.parse(args), null, 2);
    } catch {
      return args;
    }
  }
  return JSON.stringify(args, null, 2);
}

/**
 * The buttons of an open decision (plan §4.1): a question is answered; an approval is approved or
 * denied; a repository or a host is allowed for this conversation or, only for whoever may edit
 * the agent, for the agent for good.
 */
export function decisionActions(d: Decision, agentEditable: boolean): DecisionAction[] {
  if (d.kind === 'QUESTION') {
    return [{ id: 'ANSWERED', outcome: 'ANSWERED', label: 'Send answer', primary: true }];
  }
  const type = d.subject?.type;
  if (type === 'RESULT') {
    return [
      { id: 'APPROVED', outcome: 'APPROVED', label: 'Approve and write it', primary: true },
      { id: 'DENIED', outcome: 'DENIED', label: 'Send back', primary: false, needsText: true },
    ];
  }
  if (type === 'REPOSITORY' || type === 'HOST') {
    return [
      {
        id: 'APPROVED-CONVERSATION',
        outcome: 'APPROVED',
        scope: 'CONVERSATION',
        label: 'Allow for this conversation',
        primary: true,
      },
      ...(agentEditable
        ? [
            {
              id: 'APPROVED-AGENT',
              outcome: 'APPROVED' as const,
              scope: 'AGENT' as const,
              label: `Allow for ${d.agentName ?? 'the agent'}, for good`,
              primary: false,
            },
          ]
        : []),
      { id: 'DENIED', outcome: 'DENIED', label: 'Deny', primary: false },
    ];
  }
  return [
    { id: 'APPROVED', outcome: 'APPROVED', label: 'Approve', primary: true },
    { id: 'DENIED', outcome: 'DENIED', label: 'Deny', primary: false },
  ];
}

const ACCESS_OPTIONS: readonly SegmentedOption[] = [
  { value: 'READ', label: 'Read only' },
  { value: 'WRITE', label: 'Read and write' },
];

/**
 * A decision of a conversation (plan §4.1) as a card answerable in place, in the inbox and in the
 * conversation: a question with its options and a free answer; an approval by its subject: a tool
 * call with what it does, its system and the account it runs as, its arguments folded; a
 * repository (read or write) or a network host for this conversation or the agent for good; a
 * change request on a repository; a trigger's result to review before it is written (package 4).
 * Approve or deny with a note. Without `mayDecide` it reads "Waiting for …", naming the person or
 * the trigger's team; a team's approval is decided here, never through an AI client (package 6's
 * `DECIDE_IN_APP`). Decided, it loses its orange and shows the outcome and an approved call's
 * result. Each submission carries one `Idempotency-Key`, kept when the same one is sent again.
 *
 * `appearance="inline"` draws the body alone, without the frame and the title line, for a host
 * that draws them (the inbox's open row): the request in one sentence with its arguments folded,
 * visible labels, Cancel (`dismissed`), and Send that says what it lacks instead of waiting
 * disabled.
 */
@Component({
  selector: 'app-decision-card',
  imports: [RouterLink, NgIcon, HlmButton, HlmSpinner, HlmTextarea, Markdown, Segmented, Time],
  providers: [provideIcons({ lucideChevronRight })],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'block' },
  template: `
    @let d = decision();
    @let open = d.status === 'OPEN';
    @let s = d.subject;
    @let agent = d.agentName ?? 'the agent';
    @if (inline()) {
      <div
        class="flex min-w-0 flex-col gap-3.5 text-sm"
        data-testid="decision"
        data-appearance="inline"
        [attr.data-decision-id]="d.id"
        [attr.data-kind]="d.kind"
        [attr.data-subject]="s?.type ?? null"
        [attr.data-status]="d.status"
      >
        @if (d.purgedAt) {
          <p class="text-muted-foreground text-xs" data-testid="decision-purged">
            Its texts were removed <app-time [value]="d.purgedAt" /> by the company’s retention;
            what was decided stays.
          </p>
        }
        @if (d.question && showQuestion()) {
          <div>
            <p class="sr-only">Question:</p>
            <app-markdown class="text-sm" [text]="d.question" />
          </div>
        }
        @if (s) {
          <div class="flex min-w-0 flex-col gap-1.5" data-testid="decision-request">
            @switch (s.type) {
              @case ('TOOL_CALL') {
                <p class="leading-normal text-pretty" data-testid="decision-what">
                  <strong class="font-semibold">{{ what() }}</strong
                  >{{ toolCallTail() }}
                </p>
              }
              @case ('REPOSITORY') {
                <p class="leading-normal text-pretty">
                  Use the repository
                  <code [class]="inlineCode" data-testid="decision-repository">{{
                    s.repositoryName
                  }}</code>
                </p>
              }
              @case ('HOST') {
                <p class="leading-normal text-pretty">
                  Reach the network host
                  <code [class]="inlineCode" data-testid="decision-host">{{ s.host }}</code
                  >{{ s.host?.startsWith('*.') ? ', every subdomain of ' + s.host!.slice(2) : '' }}
                </p>
              }
              @case ('CHANGE_REQUEST') {
                <p class="leading-normal text-pretty">
                  Open a change request on
                  <code [class]="inlineCode" data-testid="decision-repository">{{
                    s.repositoryName
                  }}</code>
                </p>
              }
              @case ('RESULT') {
                @if (s.summary) {
                  <div class="flex flex-col gap-0.5">
                    <p class="text-muted-foreground text-xs font-semibold">Its result</p>
                    <app-markdown
                      class="text-[0.8125rem]"
                      data-testid="decision-summary"
                      [text]="s.summary"
                    />
                  </div>
                }
                <div class="flex flex-col gap-1">
                  <p class="text-muted-foreground text-xs font-semibold">
                    {{ open ? 'Would be written to the issue' : 'To be written to the issue' }}
                  </p>
                  @if (writes().length > 0) {
                    <ol class="flex flex-col gap-1.5" data-testid="decision-writes">
                      @for (write of writes(); track $index) {
                        <li class="bg-muted/50 rounded px-2 py-1.5">
                          <app-markdown class="text-[0.8125rem]" [text]="write" />
                        </li>
                      }
                    </ol>
                  } @else {
                    <p class="text-muted-foreground text-[0.8125rem]">Nothing</p>
                  }
                </div>
              }
            }
            @if (d.reason) {
              <p
                class="text-muted-foreground text-[0.8125rem] break-words whitespace-pre-line"
                data-testid="decision-reason"
              >
                Why, in {{ agent }}’s words: “{{ d.reason }}”
              </p>
            }
            @if (toolArguments(); as args) {
              <div>
                <button
                  type="button"
                  class="text-muted-foreground hover:text-foreground focus-visible:ring-ring/50 -ms-1 inline-flex min-h-6 items-center gap-1.5 rounded-md px-1 text-xs font-semibold outline-none focus-visible:ring-3 max-md:min-h-11"
                  data-testid="decision-arguments-toggle"
                  [attr.aria-expanded]="argumentsOpen()"
                  [attr.aria-controls]="argumentsId()"
                  (click)="argumentsOpen.set(!argumentsOpen())"
                >
                  <ng-icon
                    name="lucideChevronRight"
                    size="14"
                    class="transition-transform motion-reduce:transition-none"
                    [class.rotate-90]="argumentsOpen()"
                    aria-hidden="true"
                  />
                  {{ argumentsOpen() ? 'Hide' : 'Show' }} the arguments
                  <code class="font-mono font-medium">{{ s?.name ?? s?.tool }}</code>
                </button>
                @if (argumentsOpen()) {
                  <pre
                    class="bg-code m-0 mt-1.5 max-h-60 overflow-auto rounded-lg border px-3 py-2.5 font-mono text-xs break-all whitespace-pre-wrap"
                    data-testid="decision-arguments"
                    [id]="argumentsId()"
                    >{{ args }}</pre>
                }
              </div>
            }
          </div>
        }

        @if (!open) {
          @if (d.answer || d.note) {
            <p class="text-[0.8125rem]" data-testid="decision-answer">
              <span class="text-muted-foreground">{{ d.decidedByName ?? 'Someone' }}:</span>
              {{ d.answer ?? d.note }}
            </p>
          }
          @if (d.result) {
            <div class="text-[0.8125rem]" data-testid="decision-result">
              <p class="text-muted-foreground m-0 text-xs font-semibold">
                The call ran; its result:
              </p>
              <app-markdown class="text-[0.8125rem]" [text]="d.result" />
            </div>
          }
          @if (d.status === 'CANCELLED' && d.cancelCause) {
            <p class="text-muted-foreground text-xs" data-testid="decision-cancelled">
              {{ d.cancelCause }}
            </p>
          }
        } @else if (d.mayDecide) {
          @if (d.kind === 'QUESTION' && options().length > 0) {
            <div class="flex flex-col gap-1.5">
              <span class="text-muted-foreground text-xs font-semibold" [id]="optionsLabelId()"
                >Answers {{ agent }} offers: pick one</span
              >
              <div
                class="flex flex-wrap gap-2"
                role="group"
                data-testid="decision-options"
                [attr.aria-labelledby]="optionsLabelId()"
              >
                @for (option of options(); track option) {
                  <button
                    type="button"
                    class="hover:bg-accent focus-visible:ring-ring/50 aria-pressed:border-human aria-pressed:bg-human-soft aria-pressed:text-human border-input bg-card inline-flex min-h-8 max-w-full items-center rounded-full border px-3.5 py-1 text-start text-[0.8125rem] font-semibold outline-none focus-visible:ring-3 disabled:opacity-50 max-md:min-h-11"
                    [attr.aria-pressed]="selected() === option"
                    [disabled]="busy()"
                    (click)="pick(option)"
                  >
                    {{ option }}
                  </button>
                }
              </div>
            </div>
          }
          @if (s?.type === 'REPOSITORY') {
            <div class="flex flex-col gap-1.5">
              <span class="text-muted-foreground text-xs font-semibold" [id]="accessLabelId()"
                >Access</span
              >
              <app-segmented
                [inputId]="'decision-access-' + d.id"
                [labelledBy]="accessLabelId()"
                [options]="accessOptions"
                [value]="access()"
                [disabled]="busy()"
                (valueChange)="setAccess($event)"
              />
            </div>
          }
          <div class="flex flex-col gap-1.5">
            <label class="text-muted-foreground text-xs font-semibold" [for]="textId()">
              {{ inlineTextLabel() }}
              @if (textOptional()) {
                <span class="font-normal">(optional)</span>
              }
            </label>
            <textarea
              #inlineText
              hlmTextarea
              rows="2"
              class="max-h-60 min-h-16 [field-sizing:content]"
              [id]="textId()"
              [attr.maxlength]="maxText"
              [attr.aria-describedby]="missing() ? missingId() : null"
              [value]="text()"
              [disabled]="busy()"
              (input)="setText(inlineText.value)"
            ></textarea>
          </div>
          <div class="flex flex-wrap items-center gap-2">
            @for (action of actions(); track action.id) {
              <button
                hlmBtn
                size="sm"
                type="button"
                class="h-auto min-h-8 whitespace-normal max-md:min-h-11"
                [variant]="action.primary ? 'default' : 'outline'"
                [attr.data-testid]="'decide-' + action.id"
                [disabled]="busy()"
                (click)="send(action)"
              >
                @if (busy() && sending() === action.id) {
                  <hlm-spinner class="size-3.5" aria-hidden="true" />
                }
                {{ action.label }}
              </button>
            }
            @if (d.kind === 'QUESTION') {
              <button
                hlmBtn
                variant="ghost"
                size="sm"
                type="button"
                class="max-md:h-11"
                data-testid="decision-cancel"
                [disabled]="busy()"
                (click)="dismissed.emit()"
              >
                Cancel
              </button>
            }
            @if (missing(); as message) {
              <span
                class="text-destructive text-xs font-semibold"
                role="alert"
                data-testid="decision-missing"
                [id]="missingId()"
                >{{ message }}</span
              >
            } @else if (d.kind !== 'QUESTION' && text().trim()) {
              <span class="text-muted-foreground text-xs"
                >Your note goes to {{ agent }} with the decision.</span
              >
            }
          </div>
          <p class="text-muted-foreground -mt-1.5 text-xs" data-testid="decision-hint">
            {{ inlineHint() }}
          </p>
          @if (d.approverGroupName) {
            <p class="text-muted-foreground -mt-1.5 text-xs" data-testid="decision-team">
              For the team {{ d.approverGroupName }}: whoever decides first decides for everyone. A
              team’s approval is decided here in Tinfirm, never through an AI client.
            </p>
          }
        } @else {
          <p class="text-muted-foreground text-xs" data-testid="decision-read-only">
            Waiting for {{ approver() }}.
          </p>
        }
      </div>
    } @else {
      <article
        class="bg-card focus-visible:ring-ring/50 flex flex-col gap-2.5 rounded-[14px] border px-3.5 py-3 outline-none focus-visible:ring-3"
        tabindex="-1"
        data-testid="decision"
        [class.border-human]="open && d.mayDecide"
        [attr.data-decision-id]="d.id"
        [attr.data-kind]="d.kind"
        [attr.data-subject]="s?.type ?? null"
        [attr.data-status]="d.status"
        [attr.aria-labelledby]="titleId()"
      >
        <h3
          class="flex min-w-0 flex-wrap items-baseline gap-x-2 gap-y-0.5 text-sm font-normal"
          [id]="titleId()"
        >
          <span class="font-semibold" [class.text-human]="open && d.mayDecide">{{
            kindLabel()
          }}</span>
          <span class="text-muted-foreground min-w-0 break-words">· {{ title() }}</span>
          @if (!open) {
            <span
              class="bg-muted text-muted-foreground inline-flex h-5 items-center rounded-full px-2 text-xs font-semibold"
              data-testid="decision-status"
              >{{ statusLabel() }}</span
            >
          }
        </h3>
        <p
          class="text-muted-foreground -mt-1.5 flex flex-wrap items-center gap-x-1.5 gap-y-0.5 text-xs"
        >
          @if (showConversation()) {
            <a
              class="hover:text-foreground flex min-w-0 items-baseline gap-1.5 font-medium underline-offset-2 hover:underline"
              data-testid="decision-conversation"
              [routerLink]="['/c', companyId(), 'conversations', d.conversationId]"
            >
              <span class="shrink-0 font-mono">{{ d.conversationKey }}</span>
              <span class="truncate">{{ d.conversationTitle }}</span>
            </a>
            <span aria-hidden="true">·</span>
          }
          @if (open) {
            <span>waiting since <app-time [value]="d.createdAt" /></span>
          } @else {
            <app-time [value]="d.createdAt" />
          }
        </p>

        @if (d.purgedAt) {
          <p class="text-muted-foreground text-xs" data-testid="decision-purged">
            Its texts were removed <app-time [value]="d.purgedAt" /> by the company’s retention;
            what was decided stays.
          </p>
        }

        @if (d.question) {
          <div>
            <p class="sr-only">Question:</p>
            <app-markdown class="text-[0.8125rem]" [text]="d.question" />
          </div>
        }

        @if (s) {
          <dl
            class="flex flex-col gap-2 rounded-md border p-3 text-[0.8125rem]"
            data-testid="decision-request"
          >
            @switch (s.type) {
              @case ('TOOL_CALL') {
                <div class="flex flex-col gap-0.5">
                  <dt class="text-muted-foreground text-xs font-semibold">What</dt>
                  <dd data-testid="decision-what">{{ s.what ?? s.tool ?? s.name }}</dd>
                </div>
                @if (s.systemName) {
                  <div class="flex flex-col gap-0.5">
                    <dt class="text-muted-foreground text-xs font-semibold">System</dt>
                    <dd data-testid="decision-system">{{ s.systemName }}</dd>
                  </div>
                }
                <div class="flex flex-col gap-0.5">
                  <dt class="text-muted-foreground text-xs font-semibold">Runs as</dt>
                  <dd data-testid="decision-account">
                    {{
                      bindingText() ??
                        (s.systemName ?? 'Its system') +
                          ': the account this conversation uses there'
                    }}
                  </dd>
                </div>
                @if (toolArguments(); as args) {
                  <div>
                    <dt class="sr-only">Arguments</dt>
                    <dd>
                      <details class="group">
                        <summary
                          class="text-muted-foreground cursor-pointer text-xs font-semibold select-none"
                        >
                          The arguments {{ d.agentName ?? 'the agent' }} gave
                          <code class="font-mono">{{ s.name ?? s.tool }}</code>
                        </summary>
                        <pre
                          class="bg-code m-0 mt-1 max-h-60 overflow-auto rounded px-2 py-1.5 font-mono text-xs break-all whitespace-pre-wrap"
                          data-testid="decision-arguments"
                          >{{ args }}</pre>
                      </details>
                    </dd>
                  </div>
                }
              }
              @case ('REPOSITORY') {
                <div class="flex flex-col gap-0.5">
                  <dt class="text-muted-foreground text-xs font-semibold">Repository</dt>
                  <dd
                    class="font-mono text-xs font-semibold break-all"
                    data-testid="decision-repository"
                  >
                    {{ s.repositoryName }}
                  </dd>
                </div>
              }
              @case ('HOST') {
                <div class="flex flex-col gap-0.5">
                  <dt class="text-muted-foreground text-xs font-semibold">Network host</dt>
                  <dd>
                    @if (s.host; as host) {
                      <code
                        class="font-mono text-xs font-semibold break-all"
                        data-testid="decision-host"
                        >{{ host }}</code
                      >
                      @if (host.startsWith('*.')) {
                        <span class="text-muted-foreground text-xs">
                          every subdomain of {{ host.slice(2) }}</span
                        >
                      }
                    }
                  </dd>
                </div>
              }
              @case ('RESULT') {
                @if (s.summary) {
                  <div class="flex flex-col gap-0.5">
                    <dt class="text-muted-foreground text-xs font-semibold">Its result</dt>
                    <dd data-testid="decision-summary">
                      <app-markdown class="text-[0.8125rem]" [text]="s.summary" />
                    </dd>
                  </div>
                }
                <div class="flex flex-col gap-0.5">
                  <dt class="text-muted-foreground text-xs font-semibold">
                    {{ open ? 'Would be written to the issue' : 'To be written to the issue' }}
                  </dt>
                  <dd>
                    @if (writes().length > 0) {
                      <ol class="flex flex-col gap-1.5" data-testid="decision-writes">
                        @for (write of writes(); track $index) {
                          <li class="bg-muted/50 rounded px-2 py-1.5">
                            <app-markdown class="text-[0.8125rem]" [text]="write" />
                          </li>
                        }
                      </ol>
                    } @else {
                      <span class="text-muted-foreground">Nothing</span>
                    }
                  </dd>
                </div>
              }
              @case ('CHANGE_REQUEST') {
                <div class="flex flex-col gap-0.5">
                  <dt class="text-muted-foreground text-xs font-semibold">Change request on</dt>
                  <dd
                    class="font-mono text-xs font-semibold break-all"
                    data-testid="decision-repository"
                  >
                    {{ s.repositoryName }}
                  </dd>
                </div>
              }
            }
            @if (d.reason) {
              <div class="flex flex-col gap-0.5">
                <dt class="text-muted-foreground text-xs font-semibold">
                  Why: {{ d.agentName ?? 'the agent' }} says
                </dt>
                <dd class="break-words whitespace-pre-line" data-testid="decision-reason">
                  {{ d.reason }}
                </dd>
              </div>
            }
          </dl>
        }

        @if (!open) {
          @if (options().length > 0) {
            <ul
              class="flex flex-wrap gap-2"
              data-testid="decision-options"
              aria-label="Options offered"
            >
              @for (option of options(); track option) {
                <li
                  class="inline-flex min-h-8 max-w-full items-center rounded-full border px-3.5 py-1 text-[0.8125rem]"
                  [class.text-muted-foreground]="option !== d.option"
                  [class.font-semibold]="option === d.option"
                >
                  {{ option }}
                </li>
              }
            </ul>
          }
          @if (d.answer || d.note) {
            <p class="text-[0.8125rem]" data-testid="decision-answer">
              <span class="text-muted-foreground">{{ d.decidedByName ?? 'Someone' }}:</span>
              {{ d.answer ?? d.note }}
            </p>
          }
          @if (d.result) {
            <div class="text-[0.8125rem]" data-testid="decision-result">
              <p class="text-muted-foreground m-0 text-xs font-semibold">
                The call ran; its result:
              </p>
              <app-markdown class="text-[0.8125rem]" [text]="d.result" />
            </div>
          }
          @if (d.status === 'CANCELLED' && d.cancelCause) {
            <p class="text-muted-foreground text-xs" data-testid="decision-cancelled">
              {{ d.cancelCause }}
            </p>
          }
        } @else if (d.mayDecide) {
          @if (d.kind === 'QUESTION' && options().length > 0) {
            <div
              class="flex flex-wrap gap-2"
              role="group"
              data-testid="decision-options"
              [attr.aria-label]="'Answers ' + (d.agentName ?? 'the agent') + ' offers: pick one'"
            >
              @for (option of options(); track option) {
                <button
                  type="button"
                  class="hover:bg-accent focus-visible:ring-ring/50 aria-pressed:border-human aria-pressed:bg-human-soft aria-pressed:text-human inline-flex min-h-8 max-w-full items-center gap-1.5 rounded-full border px-3.5 py-1 text-start text-[0.8125rem] font-semibold outline-none focus-visible:ring-3 disabled:opacity-50"
                  [attr.aria-pressed]="selected() === option"
                  [disabled]="busy()"
                  (click)="pick(option)"
                >
                  {{ option }}
                </button>
              }
            </div>
          }
          @if (s?.type === 'REPOSITORY') {
            <div class="flex flex-col gap-1.5">
              <span class="text-muted-foreground text-xs font-semibold" [id]="accessLabelId()"
                >Access</span
              >
              <app-segmented
                [inputId]="'decision-access-' + d.id"
                [labelledBy]="accessLabelId()"
                [options]="accessOptions"
                [value]="access()"
                [disabled]="busy()"
                (valueChange)="setAccess($event)"
              />
            </div>
          }
          <div class="flex flex-col gap-1.5">
            <label class="text-muted-foreground text-xs font-semibold" [for]="textId()">
              {{ textLabel() }}
              @if (textOptional()) {
                <span class="font-normal">(optional)</span>
              }
            </label>
            <textarea
              #textInput
              hlmTextarea
              rows="2"
              class="max-h-60 min-h-16 [field-sizing:content]"
              [id]="textId()"
              [attr.maxlength]="maxText"
              [value]="text()"
              [disabled]="busy()"
              (input)="text.set(textInput.value)"
            ></textarea>
          </div>
          <div class="flex flex-wrap items-center gap-2">
            @for (action of actions(); track action.id) {
              <button
                hlmBtn
                size="sm"
                type="button"
                class="h-auto min-h-8 whitespace-normal"
                [variant]="action.primary ? 'default' : 'outline'"
                [attr.data-testid]="'decide-' + action.id"
                [disabled]="
                  busy() ||
                  (action.outcome === 'ANSWERED' && !ready()) ||
                  (!!action.needsText && !text().trim())
                "
                (click)="send(action)"
              >
                @if (busy() && sending() === action.id) {
                  <hlm-spinner class="size-3.5" aria-hidden="true" />
                }
                {{ action.label }}
              </button>
            }
            @if (d.kind === 'QUESTION' && options().length > 0 && !ready()) {
              <span class="text-muted-foreground text-xs">Pick an option or write an answer.</span>
            }
            @if (actionNeedsText() && !text().trim()) {
              <span class="text-muted-foreground text-xs" data-testid="decision-needs-note"
                >Sending it back needs a note: what should change.</span
              >
            }
            <p class="text-muted-foreground w-full text-xs" data-testid="decision-hint">
              {{ hint() }}
            </p>
            @if (d.approverGroupName) {
              <p class="text-muted-foreground w-full text-xs" data-testid="decision-team">
                For the team {{ d.approverGroupName }}: whoever decides first decides for everyone.
                A team’s approval is decided here in Tinfirm, never through an AI client.
              </p>
            }
          </div>
        } @else {
          @if (options().length > 0) {
            <ul
              class="flex flex-wrap gap-2"
              data-testid="decision-options"
              aria-label="Options offered"
            >
              @for (option of options(); track option) {
                <li
                  class="text-muted-foreground inline-flex min-h-8 max-w-full items-center rounded-full border px-3.5 py-1 text-[0.8125rem]"
                >
                  {{ option }}
                </li>
              }
            </ul>
          }
          <p class="text-muted-foreground text-xs" data-testid="decision-read-only">
            Waiting for {{ approver() }}.
          </p>
        }
      </article>
    }
  `,
})
export class DecisionCard {
  private readonly api = inject(Api);
  private readonly notify = inject(Notify);

  readonly decision = input.required<Decision>();
  readonly companyId = input.required<string>();
  /** Whether the reader may edit the agent (`Agent.editableByMe`): "for good" needs it. */
  readonly agentEditable = input(false);
  /** The account a tool call runs as, from the conversation's binding: "Jira: your own account". */
  readonly bindingText = input<string | null>(null);
  /** Names and links the conversation, for a card outside its page (the inbox). */
  readonly showConversation = input(false);
  /** `inline`: the body alone, for a host that draws the frame and the title (the inbox). */
  readonly appearance = input<'card' | 'inline'>('card');
  /** Inline: whether the body repeats the question; a host whose title says it whole leaves it out. */
  readonly showQuestion = input(true);
  /** Decided through the card; the parent re-reads what changed. */
  readonly decided = output<Decision>();
  /** Someone decided it first, or it was cancelled; the parent re-reads. */
  readonly outdated = output<void>();
  /** Inline: Cancel was pressed; the host folds the card. */
  readonly dismissed = output<void>();

  protected readonly maxText = MAX_TEXT;
  protected readonly accessOptions = ACCESS_OPTIONS;
  protected readonly inlineCode =
    'bg-code rounded px-1 font-mono text-[0.8125rem] font-medium break-all';
  protected readonly inline = computed(() => this.appearance() === 'inline');
  /** Inline: whether the tool call's arguments are unfolded. */
  protected readonly argumentsOpen = signal(false);
  /** Inline: what Send lacks, once pressed without it. */
  protected readonly missing = signal<string | null>(null);
  protected readonly text = signal('');
  /** The option the person picked; a second click on it clears the pick. */
  protected readonly selected = signal<string | null>(null);
  /** The access an approved repository gets: the one asked for, unless the person picks another. */
  protected readonly access = linkedSignal<AgentRepositoryAccess>(
    () => this.decision().subject?.access ?? this.decision().access ?? 'READ',
  );
  protected readonly busy = signal(false);
  /** One `Idempotency-Key` per submission, kept for the same submission sent again. */
  private readonly submission = new SubmissionKey();
  /** The `id` of the action being sent. */
  protected readonly sending = signal<string | null>(null);

  protected readonly titleId = computed(() => `decision-${this.decision().id}`);
  protected readonly textId = computed(() => `decision-text-${this.decision().id}`);
  protected readonly accessLabelId = computed(() => `decision-access-label-${this.decision().id}`);
  protected readonly optionsLabelId = computed(() => `decision-options-${this.decision().id}`);
  protected readonly argumentsId = computed(() => `decision-arguments-${this.decision().id}`);
  protected readonly missingId = computed(() => `decision-missing-${this.decision().id}`);
  protected readonly what = computed(() => toolCallWhat(this.decision().subject ?? {}));
  /** After the tool call's words: its system and the account it runs as, to the full stop. */
  protected readonly toolCallTail = computed(() => {
    const s = this.decision().subject;
    const system = s?.systemName ? ` on ${s.systemName}` : '';
    const account = this.bindingText() ?? s?.accountText;
    return account
      ? `${system}. Runs as ${account}.`
      : `${system}, as the account this conversation uses there.`;
  });
  protected readonly inlineTextLabel = computed(() => {
    const d = this.decision();
    if (d.kind === 'QUESTION') return this.options().length > 0 ? 'Add an answer' : 'Your answer';
    return `Note for ${d.agentName ?? 'the agent'}`;
  });
  protected readonly inlineHint = computed(() => {
    const d = this.decision();
    const agent = d.agentName ?? 'The agent';
    return d.subject?.type === 'TOOL_CALL'
      ? `Approved, the call runs now as that account and ${agent} gets its result. Denied, ${agent} is told and goes on without it.`
      : this.hint();
  });
  protected readonly options = computed<readonly string[]>(() => this.decision().options ?? []);
  protected readonly writes = computed<readonly string[]>(
    () => this.decision().subject?.writes ?? [],
  );
  protected readonly kindLabel = computed(() => decisionKindLabel(this.decision()));
  protected readonly approver = computed(() => approverText(this.decision()));
  protected readonly title = computed(() => decisionTitle(this.decision()));
  protected readonly toolArguments = computed(() =>
    prettyArguments(this.decision().subject?.arguments),
  );
  protected readonly statusLabel = computed(() => decidedLabel(this.decision()));
  protected readonly actions = computed(() =>
    decisionActions(this.decision(), this.agentEditable()),
  );
  protected readonly textLabel = computed(() => {
    if (this.decision().subject?.type === 'RESULT') return 'Note for the agent';
    if (this.decision().kind !== 'QUESTION') return 'Note for the agent';
    return this.options().length > 0 ? 'Add an answer' : 'Your answer';
  });
  protected readonly textOptional = computed(
    () => this.decision().kind !== 'QUESTION' || this.options().length > 0,
  );
  /** A question needs an option or an answer; an approval is ready at once. */
  protected readonly ready = computed(
    () => this.decision().kind !== 'QUESTION' || this.selected() !== null || !!this.text().trim(),
  );
  protected readonly actionNeedsText = computed(() => this.actions().some((a) => a.needsText));
  protected readonly hint = computed(() => {
    const d = this.decision();
    const agent = d.agentName ?? 'The agent';
    if (d.kind === 'QUESTION') {
      return `Your answer is posted in the conversation as your message, and ${agent} goes on once no question is open.`;
    }
    switch (d.subject?.type) {
      case 'TOOL_CALL':
        return `Approved, the call runs now with the account above and ${agent} gets its result; denied, it is told and goes on without it.`;
      case 'REPOSITORY':
      case 'HOST':
        return `For this conversation, its next turns have it; for good, every conversation with ${agent} does. Denied, it is told.`;
      case 'RESULT':
        return `Approved, exactly what is shown above is written to the issue; sent back, ${agent} gets your note and works on it again.`;
      default:
        return `Approved, the change request opens; denied, ${agent} is told and goes on.`;
    }
  });

  protected setAccess(value: string): void {
    if (value === 'READ' || value === 'WRITE') this.access.set(value);
  }

  protected pick(option: string): void {
    this.selected.set(this.selected() === option ? null : option);
    this.missing.set(null);
  }

  protected setText(value: string): void {
    this.text.set(value);
    this.missing.set(null);
  }

  protected async send(action: DecisionAction): Promise<void> {
    const d = this.decision();
    const text = this.text().trim().slice(0, MAX_TEXT);
    const option = this.selected();
    if (this.busy()) return;
    // The card's buttons wait disabled; inline, they say what is missing.
    if (action.outcome === 'ANSWERED' && option === null && !text) {
      this.missing.set(
        this.options().length > 0 ? 'Pick an option or write an answer.' : 'Write an answer first.',
      );
      return;
    }
    if (action.needsText && !text) {
      this.missing.set('Sending it back needs a note: what should change.');
      return;
    }
    const body: DecideRequest = {};
    if (action.outcome === 'ANSWERED') {
      if (option !== null) body.option = option;
      if (text) body.answer = text;
    } else {
      body.outcome = action.outcome;
      if (text) body.note = text;
      if (action.scope) body.scope = action.scope;
      if (action.outcome === 'APPROVED' && d.subject?.type === 'REPOSITORY') {
        body.access = this.access();
      }
    }
    this.busy.set(true);
    this.sending.set(action.id);
    try {
      const updated = await this.api.invoke(decideDecision, {
        companyId: this.companyId(),
        decisionId: d.id,
        'Idempotency-Key': this.submission.keyFor({ decisionId: d.id, body }),
        body,
      });
      this.submission.settle();
      this.text.set('');
      this.selected.set(null);
      this.decided.emit(updated);
    } catch (error) {
      this.submission.settle(error);
      this.notify.error("Couldn't send the decision", error);
      if (hasProblemCode(error, 'DECISION_DECIDED')) this.outdated.emit();
    } finally {
      this.busy.set(false);
      this.sending.set(null);
    }
  }
}
