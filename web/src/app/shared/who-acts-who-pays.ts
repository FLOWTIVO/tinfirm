import { NgTemplateOutlet } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { NgIcon, provideIcons } from '@ng-icons/core';
import {
  lucideCircleAlert,
  lucideCircleCheck,
  lucideCircleDashed,
  lucideCircleSlash,
  lucideClock,
} from '@ng-icons/lucide';
import type {
  AgentReadiness,
  Conversation,
  ModelAccessView,
  MyAccount,
  Run,
} from '../api/generated/models';
import {
  conversationLineView,
  fromConversation,
  fromReadiness,
  fromRun,
  type WapLine,
  type WapLineView,
  type WapTone,
  type WhoActsWhoPaysView,
} from './who-acts-who-pays-view';

const ICONS: Record<WapTone, string> = {
  ok: 'lucideCircleCheck',
  later: 'lucideCircleDashed',
  you: 'lucideCircleAlert',
  blocked: 'lucideClock',
  gone: 'lucideCircleSlash',
};

const ICON_TONES: Record<WapTone, string> = {
  ok: 'text-success',
  later: 'text-faint',
  you: 'text-human',
  blocked: 'text-warning',
  gone: 'text-muted-foreground',
};

const TEXT_TONES: Record<WapTone, string> = {
  ok: '',
  later: 'text-muted-foreground',
  you: 'text-human font-semibold',
  blocked: '',
  gone: 'text-muted-foreground line-through decoration-1',
};

let nextId = 0;

/**
 * Who acts and who pays, before work starts and while it runs (plan §2, rule 2): one line per
 * system ("Jira: your own account", "GitHub: via Analysts, read-only", "Confluence: connect when
 * first needed"), then who pays ("Your budget: $38.20 of $50 left, resets 1 Nov"), then where it
 * runs. It draws from one of `readiness`, `conversation`, `run` or a ready `view` (a trigger's
 * preview), and says only what the server answered. Orange only on what the reader can act on.
 */
@Component({
  selector: 'app-who-acts-who-pays',
  imports: [NgIcon, NgTemplateOutlet],
  providers: [
    provideIcons({
      lucideCircleAlert,
      lucideCircleCheck,
      lucideCircleDashed,
      lucideCircleSlash,
      lucideClock,
    }),
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {
    class: 'block',
    'data-testid': 'who-acts-who-pays',
  },
  template: `
    @if (shown(); as v) {
      <section
        class="flex flex-col gap-3 text-sm"
        [class]="framed() ? 'bg-card rounded-xl border p-4' : ''"
        [attr.aria-labelledby]="headingId"
      >
        <div class="flex flex-col gap-0.5">
          <h2 class="text-sm font-semibold" [id]="headingId">{{ heading() }}</h2>
          @if (intro(); as text) {
            <p class="text-muted-foreground text-xs">{{ text }}</p>
          }
        </div>
        <dl class="grid gap-3" [class]="columns() ? 'sm:grid-cols-3 sm:gap-5' : ''">
          <div class="flex min-w-0 flex-col gap-1" data-part="acts">
            <dt class="text-muted-foreground text-xs font-semibold">Acts as</dt>
            <dd class="flex flex-col gap-1">
              <ul class="flex flex-col gap-1">
                @for (line of v.acts; track line.key) {
                  <ng-container
                    [ngTemplateOutlet]="lineTpl"
                    [ngTemplateOutletContext]="{ $implicit: line }"
                  />
                }
              </ul>
              @if (v.actsNote) {
                <p class="text-muted-foreground text-xs">{{ v.actsNote }}</p>
              }
            </dd>
          </div>
          <div class="flex min-w-0 flex-col gap-1" data-part="pays">
            <dt class="text-muted-foreground text-xs font-semibold">Pays</dt>
            <dd>
              <ul>
                <ng-container
                  [ngTemplateOutlet]="lineTpl"
                  [ngTemplateOutletContext]="{ $implicit: v.pays }"
                />
              </ul>
            </dd>
          </div>
          @if (v.runs; as runs) {
            <div class="flex min-w-0 flex-col gap-1" data-part="runs">
              <dt class="text-muted-foreground text-xs font-semibold">Runs on</dt>
              <dd>
                <ul>
                  <ng-container
                    [ngTemplateOutlet]="lineTpl"
                    [ngTemplateOutletContext]="{ $implicit: runs }"
                  />
                </ul>
              </dd>
            </div>
          }
        </dl>
      </section>
    }

    <ng-template #lineTpl let-line>
      <li
        class="flex min-w-0 items-start gap-2"
        [attr.data-line]="line.key"
        [attr.data-tone]="line.tone"
      >
        <ng-icon
          class="mt-0.5 shrink-0"
          size="14"
          aria-hidden="true"
          [name]="icons[toneOf(line)]"
          [class]="iconTones[toneOf(line)]"
        />
        <span class="flex min-w-0 flex-col">
          <span class="break-words" [class]="textTones[toneOf(line)]">{{ line.text }}</span>
          @if (line.detail) {
            <span class="text-muted-foreground text-xs break-words">{{ line.detail }}</span>
          }
        </span>
      </li>
    </ng-template>
  `,
})
export class WhoActsWhoPays {
  /** Before a conversation starts: the agent's readiness for the reader. */
  readonly readiness = input<AgentReadiness | null>(null);
  /** A conversation: its bindings, its payer and its turns. */
  readonly conversation = input<Conversation | null>(null);
  /** One turn. */
  readonly run = input<Run | null>(null);
  /** A view built elsewhere, such as a trigger's preview. */
  readonly view = input<WhoActsWhoPaysView | null>(null);

  /** The reader's model access (`getMyModelAccess`): the budget left and the subscriptions' hosts. */
  readonly access = input<ModelAccessView | null>(null);
  /** The reader's accounts (`listMyAccounts`): the group a grant comes from. */
  readonly accounts = input<readonly MyAccount[]>([]);
  /** The reader's principal, to say "your" (plan §9, gap 2). */
  readonly principalId = input<string | null>(null);

  readonly heading = input('Who acts and who pays');
  readonly intro = input<string | null>(null);
  /** Inside a sheet of its own; off where the page already gives it one. */
  readonly framed = input(true);
  /** The three parts side by side from `sm`, for a page header. */
  readonly columns = input(false);

  protected readonly headingId = `who-acts-who-pays-${nextId++}`;
  protected readonly icons = ICONS;
  protected readonly iconTones = ICON_TONES;
  protected readonly textTones = TEXT_TONES;

  protected readonly shown = computed<WhoActsWhoPaysView | null>(() => {
    const view = this.view();
    if (view) return view;
    const readiness = this.readiness();
    if (readiness) {
      return fromReadiness(readiness, { access: this.access(), accounts: this.accounts() });
    }
    const conversation = this.conversation();
    if (conversation) {
      return fromConversation(conversation, { principalId: this.principalId() }, this.access());
    }
    const run = this.run();
    return run ? fromRun(run, { principalId: this.principalId() }) : null;
  });

  protected toneOf(line: WapLine): WapTone {
    return line.tone;
  }
}

/**
 * Who acts and who pays as one quiet line, for the head of the conversation page: "Acts as GitHub:
 * your own account · Pays from your budget, $38.20 left · Runs on build-01", behind the icon of the
 * part that most needs a look, orange only where the reader acts. What the page puts inside follows
 * the parts on the same line (its "Details", who else read it). On a phone it is one line cut short;
 * the page shows the panel in full elsewhere. Its parts carry `data-part`, as the panel's do.
 */
@Component({
  selector: 'app-who-acts-who-pays-line',
  imports: [NgIcon],
  providers: [
    provideIcons({
      lucideCircleAlert,
      lucideCircleCheck,
      lucideCircleDashed,
      lucideCircleSlash,
      lucideClock,
    }),
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'block', 'data-testid': 'who-acts-who-pays', 'data-variant': 'line' },
  template: `
    @let v = view();
    <div
      class="text-muted-foreground flex min-w-0 items-center gap-x-2 gap-y-1 text-[0.8125rem] leading-snug max-sm:flex-nowrap sm:flex-wrap"
      role="group"
      aria-label="Who acts and who pays"
    >
      <ng-icon
        class="shrink-0"
        size="14"
        aria-hidden="true"
        [name]="icons[v.tone]"
        [class]="iconTones[v.tone]"
      />
      <span class="min-w-0 max-sm:flex-1 max-sm:truncate sm:contents">
        @for (part of v.parts; track part.key; let last = $last) {
          <span
            class="sm:min-w-0"
            [class.max-sm:hidden]="part.key === 'runs'"
            [attr.data-part]="part.key"
          >
            @if (part.label) {
              {{ part.label }}
            }
            @for (line of part.lines; track line.key; let lastLine = $last) {
              <span
                [attr.data-line]="line.key"
                [attr.data-tone]="line.tone"
                [class]="textTones[line.tone] || 'text-foreground'"
                >{{ line.text }}</span
              >{{ lastLine ? '' : ', ' }}
            }
          </span>
          @if (!last) {
            <span
              class="text-muted-foreground"
              aria-hidden="true"
              [class.max-sm:hidden]="part.key === 'pays'"
            >
              ·
            </span>
          }
        }
      </span>
      <ng-content />
    </div>
  `,
})
export class WhoActsWhoPaysLine {
  readonly conversation = input.required<Conversation>();
  /** The reader's model access: the budget left. */
  readonly access = input<ModelAccessView | null>(null);
  /** The reader's principal, to say "your". */
  readonly principalId = input<string | null>(null);

  protected readonly icons = ICONS;
  protected readonly iconTones = ICON_TONES;
  protected readonly textTones = TEXT_TONES;

  protected readonly view = computed<WapLineView>(() =>
    conversationLineView(this.conversation(), { principalId: this.principalId() }, this.access()),
  );
}
