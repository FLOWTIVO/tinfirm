import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import { RouterLink } from '@angular/router';
import { NgIcon, provideIcons } from '@ng-icons/core';
import {
  lucideCircleX,
  lucideClock,
  lucidePlay,
  lucideRotateCw,
  lucideTimer,
} from '@ng-icons/lucide';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmSpinner } from '@spartan-ng/helm/spinner';
import { Api } from '../../../api/generated/api';
import { retryConversation } from '../../../api/generated/functions';
import type { InboxItem } from '../../../api/generated/models';
import { problemMessage } from '../../../api/problem';
import { SubmissionKey, submitWithKey } from '../../../core/submission-key';
import { initials } from '../../../shared/forms';
import { queueReasonView } from '../../../shared/queue-reason';
import { Time } from '../../../shared/time';
import {
  CELL_ACTS,
  CELL_ASIDE,
  CELL_KIND,
  CELL_TEXT,
  CELL_TILE,
  KIND_PILL,
  KIND_TONES,
  META,
  META_LINK,
  ROW_BUTTON,
  ROW_GRID,
  ROW_TITLE,
  TILE,
  TILE_AGENT,
} from './inbox-styles';
import {
  agentOf,
  type InboxDoneView,
  type InboxReader,
  inboxRowView,
  retriedView,
  waitingRun,
} from './inbox-words';

/**
 * A turn that stopped (plan §5), neutral: a failed turn with its cause and Retry; a turn stopped at
 * its time limit with Continue, which goes on from where it stopped; a turn that waits, with why,
 * when that ends (a budget's reset) and the link to its fix. Each opens its conversation. Retried,
 * it emits what its row collapses to.
 */
@Component({
  selector: 'app-inbox-row',
  imports: [RouterLink, NgIcon, HlmButton, HlmSpinner, Time],
  providers: [
    provideIcons({ lucideCircleX, lucideClock, lucidePlay, lucideRotateCw, lucideTimer }),
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'block' },
  template: `
    @let i = item();
    @let v = view();
    <div
      class="px-4 py-3 md:min-h-17"
      data-testid="inbox-row"
      [class]="grid"
      [attr.data-kind]="i.kind"
      [attr.data-tone]="v.tone"
    >
      <span [class]="cellKind">
        <span [class]="pill()"
          ><ng-icon [name]="icon()" size="12" aria-hidden="true" /><span class="truncate">{{
            v.kind
          }}</span></span
        >
      </span>
      <span [class]="cellAside">{{ at() }} <app-time mode="clock" [value]="i.since" /></span>
      <span [class]="tile" aria-hidden="true">{{ initials() }}</span>
      <div [class]="cellText">
        <p [class]="rowTitle" data-testid="inbox-row-title">
          {{ title() }}
          @if (sub(); as text) {
            <span class="text-muted-foreground font-normal" data-testid="inbox-row-body"
              >· {{ text }}</span
            >
          }
        </p>
        <p [class]="meta">
          @if (i.conversation; as c) {
            <a
              data-testid="inbox-row-conversation"
              [class]="metaLink"
              [routerLink]="['/c', companyId(), 'conversations', c.id]"
              >{{ c.key }}</a
            >
            <span class="min-w-0 break-words">{{ c.title }}</span>
          }
          <span class="text-faint max-md:hidden" aria-hidden="true">·</span>
          <span class="max-md:hidden">{{ at() }} <app-time mode="clock" [value]="i.since" /></span>
        </p>
        @if (error(); as message) {
          <p class="text-destructive mt-1 text-xs" role="alert" data-testid="inbox-row-error">
            {{ message }}
          </p>
        }
      </div>
      <div [class]="cellActs">
        @switch (v.action) {
          @case ('retry') {
            <button
              hlmBtn
              variant="outline"
              size="sm"
              type="button"
              data-testid="inbox-retry"
              [class]="rowButton"
              [disabled]="busy()"
              (click)="retry()"
            >
              @if (busy()) {
                <hlm-spinner class="size-3.5" aria-hidden="true" />
              } @else {
                <ng-icon name="lucideRotateCw" aria-hidden="true" />
              }
              Retry<span class="sr-only"> {{ i.conversation?.key }}</span>
            </button>
          }
          @case ('continue') {
            <button
              hlmBtn
              variant="outline"
              size="sm"
              type="button"
              data-testid="inbox-retry"
              [class]="rowButton"
              [disabled]="busy()"
              (click)="retry(true)"
            >
              @if (busy()) {
                <hlm-spinner class="size-3.5" aria-hidden="true" />
              } @else {
                <ng-icon name="lucidePlay" aria-hidden="true" />
              }
              Continue<span class="sr-only"> {{ i.conversation?.key }}</span>
            </button>
          }
        }
        @if (fix(); as f) {
          <a
            hlmBtn
            variant="ghost"
            size="sm"
            data-testid="queue-reason-fix"
            [class]="rowButton"
            [routerLink]="['/c', companyId()].concat(f.commands)"
            [queryParams]="f.queryParams"
            [fragment]="f.fragment"
            >{{ f.label }}</a
          >
        }
        @if (v.link; as link) {
          <a
            hlmBtn
            variant="ghost"
            size="sm"
            data-testid="inbox-row-link"
            [class]="rowButton"
            [routerLink]="['/c', companyId()].concat(link.commands)"
            [queryParams]="link.queryParams"
            >{{ link.label }}<span class="sr-only"> {{ i.conversation?.key }}</span></a
          >
        }
      </div>
    </div>
  `,
})
export class InboxRow {
  private readonly api = inject(Api);

  readonly item = input.required<InboxItem>();
  readonly companyId = input.required<string>();
  readonly reader = input<InboxReader>({ admin: false, owner: false });
  /** Queued again, with what its row collapses to. */
  readonly done = output<InboxDoneView>();

  protected readonly grid = ROW_GRID;
  protected readonly cellKind = CELL_KIND;
  protected readonly cellAside = CELL_ASIDE;
  protected readonly cellText = CELL_TEXT;
  protected readonly cellActs = CELL_ACTS;
  protected readonly rowTitle = ROW_TITLE;
  protected readonly rowButton = ROW_BUTTON;
  protected readonly meta = META;
  protected readonly metaLink = META_LINK;
  protected readonly tile = `${TILE} ${TILE_AGENT} ${CELL_TILE}`;

  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  private readonly submission = new SubmissionKey();

  protected readonly view = computed(() => inboxRowView(this.item(), this.reader()));
  protected readonly pill = computed(() => `${KIND_PILL} ${KIND_TONES[this.view().tone]}`);
  protected readonly icon = computed(() => {
    if (this.item().kind === 'WAITING') return 'lucideClock';
    return this.view().action === 'continue' ? 'lucideTimer' : 'lucideCircleX';
  });
  protected readonly initials = computed(() => initials(agentOf(this.item()) ?? '?'));
  /** "failed at", "stopped at", "waiting since": before the clock of `since`. */
  protected readonly at = computed(() => {
    if (this.item().kind === 'WAITING') return 'waiting since';
    return this.view().action === 'continue' ? 'stopped at' : 'failed at';
  });

  /** A waiting turn's reason, with the time that matters and the link to its fix. */
  private readonly queued = computed(() =>
    this.item().kind === 'WAITING'
      ? queueReasonView(waitingRun(this.item()), { member: true, admin: this.reader().admin })
      : null,
  );
  protected readonly title = computed(() => this.queued()?.text ?? this.view().title);
  protected readonly sub = computed(() => {
    const time = this.queued()?.time;
    return time ? `${time.prefix} ${time.text}` : this.view().body;
  });
  protected readonly fix = computed(() => this.queued()?.fix ?? null);

  /**
   * Runs the failed turn's request again, with one `Idempotency-Key` for this submission;
   * `goesOn` for a turn stopped at its time limit, which the retry continues.
   */
  protected async retry(goesOn = false): Promise<void> {
    const conversation = this.item().conversation;
    if (!conversation || this.busy()) return;
    this.busy.set(true);
    this.error.set(null);
    try {
      const params = { companyId: this.companyId(), conversationId: conversation.id };
      await submitWithKey(this.submission, params, (key) =>
        this.api.invoke(retryConversation, { ...params, 'Idempotency-Key': key }),
      );
      this.done.emit(retriedView(this.item(), goesOn));
    } catch (error) {
      this.error.set(problemMessage(error));
    } finally {
      this.busy.set(false);
    }
  }
}
