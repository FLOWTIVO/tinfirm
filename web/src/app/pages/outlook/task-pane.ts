import {
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  inject,
  InjectionToken,
  input,
  signal,
  type OnInit,
} from '@angular/core';
import { NgIcon, provideIcons } from '@ng-icons/core';
import {
  lucideCircleAlert,
  lucideClock,
  lucideExternalLink,
  lucideEye,
  lucideInfo,
  lucideLogOut,
  lucideMail,
  lucidePaperclip,
  lucideReply,
  lucideRotateCcw,
  lucideSend,
} from '@ng-icons/lucide';
import { HlmAlertImports } from '@spartan-ng/helm/alert';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmNativeSelectImports } from '@spartan-ng/helm/native-select';
import { HlmSpinner } from '@spartan-ng/helm/spinner';
import { HlmTextarea } from '@spartan-ng/helm/textarea';
import type { AgentSummary, Conversation } from '../../api/generated/models';
import { pollWhileVisible } from '../../core/polling';
import { SubmissionKey, submitWithKey } from '../../core/submission-key';
import { Logo } from '../../shared/logo';
import { Markdown } from '../../shared/markdown';
import {
  EmailConversations,
  emailKey,
  LastAgent,
  type RememberedConversation,
} from './email-conversations';
import {
  ACTIONS,
  actionLabel,
  emailRequest,
  fenceTag,
  type EmailAction,
  type OpenEmail,
} from './email-request';
import {
  attachmentsOf,
  holdAttachments,
  MAX_ATTACHMENT_MEGABYTES,
  MAX_ATTACHMENTS,
  type HeldAttachments,
} from './email-attachments';
import { isMcpRetryable, McpClient, toolErrorMessage } from './mcp-client';
import { bodyText, loadOffice, type MessageRead, type OfficeApi } from './office';
import { OutlookAuth, SignedOutError, SignInError } from './outlook-auth';
import { isActive, openQuestions, paneStatus } from './pane-status';
import { paneThread } from './pane-thread';
import { replyHtml } from './reply-body';

/** How often the pane reads a conversation, as the app's `CONVERSATION_POLL_TIMING`. */
export interface OutlookPollTiming {
  /** While its turn goes: the agent or a runner host is on it. */
  readonly activeMs: number;
  /** While it waits for the person or for nobody. */
  readonly idleMs: number;
}

export const OUTLOOK_POLL_TIMING = new InjectionToken<OutlookPollTiming>('OUTLOOK_POLL_TIMING', {
  providedIn: 'root',
  factory: () => ({ activeMs: 4000, idleMs: 30_000 }),
});

type Phase = 'loading' | 'no-office' | 'not-outlook' | 'signed-out' | 'ready';

/** The open email as the pane shows it, and the key it remembers a conversation by. */
interface ShownEmail {
  key: string | null;
  subject: string;
  from: string | null;
  date: Date | null;
  /** Its attachments, not counting the images of its body. */
  attachments: number;
}

function shown(item: MessageRead, key: string | null): ShownEmail {
  return {
    key,
    subject: item.subject || '(no subject)',
    from: item.from ? item.from.displayName || item.from.emailAddress : null,
    date: item.dateTimeCreated ?? null,
    attachments: attachmentsOf(item).length,
  };
}

/**
 * The Outlook add-in's task pane (ADR-0074, ADR-0076), `/outlook/:companyId`, outside the shell:
 * inside Outlook it signs the person in through Office's dialog, then asks one of the agents they may
 * use to summarise, triage or investigate the open email, draft a reply, or follow their own words,
 * as a personal conversation through the company's MCP endpoint, with the email's attachments when
 * the person sends them (ADR-0084). It shows the whole conversation and
 * follows it while a turn runs, keeping the earlier answers in view; it opens Outlook's reply form
 * with an answer, and remembers the conversation of each email in the mailbox's roaming settings,
 * so that every Outlook of the mailbox shows it again. It never sends an email.
 */
@Component({
  selector: 'app-outlook-task-pane',
  imports: [
    NgIcon,
    HlmAlertImports,
    HlmButton,
    HlmNativeSelectImports,
    HlmSpinner,
    HlmTextarea,
    Logo,
    Markdown,
  ],
  providers: [
    provideIcons({
      lucideCircleAlert,
      lucideClock,
      lucideExternalLink,
      lucideEye,
      lucideInfo,
      lucideLogOut,
      lucideMail,
      lucidePaperclip,
      lucideReply,
      lucideRotateCcw,
      lucideSend,
    }),
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'bg-background text-foreground block min-h-dvh' },
  template: `
    <div class="mx-auto flex max-w-xl flex-col gap-4 p-4">
      <header class="flex items-center justify-between gap-2">
        <app-logo class="h-7" wordmark />
        @if (phase() === 'ready') {
          <button
            hlmBtn
            variant="ghost"
            size="sm"
            type="button"
            (click)="signOut()"
            data-testid="sign-out"
          >
            <ng-icon name="lucideLogOut" aria-hidden="true" />
            Sign out
          </button>
        }
      </header>

      @if (error(); as message) {
        <div hlmAlert variant="destructive" role="alert" data-testid="pane-error">
          <ng-icon name="lucideCircleAlert" aria-hidden="true" />
          <p hlmAlertDescription>{{ message }}</p>
        </div>
      }

      @switch (phase()) {
        @case ('loading') {
          <div class="text-muted-foreground flex items-center gap-2 text-sm" aria-busy="true">
            <hlm-spinner class="size-4" /> Loading…
          </div>
        }
        @case ('no-office') {
          <section class="bg-card rounded-xl border p-4" data-testid="no-office">
            <h1 class="text-base font-bold">Outlook’s add-in library didn’t start</h1>
            <p class="text-muted-foreground mt-1 text-sm">
              This computer must reach <code>appsforoffice.microsoft.com</code> and
              <code>ajax.aspnetcdn.com</code>, where Microsoft serves it. Check the network or VPN,
              then close the pane and open it again.
            </p>
          </section>
        }
        @case ('not-outlook') {
          <section class="bg-card rounded-xl border p-4" data-testid="not-outlook">
            <h1 class="text-base font-bold">Open this from Outlook</h1>
            <p class="text-muted-foreground mt-1 text-sm">
              This page is Tinfirm’s Outlook add-in. Open an email in Outlook and choose
              <strong>Ask an agent</strong> on its toolbar.
            </p>
          </section>
        }
        @case ('signed-out') {
          <section
            class="bg-card flex flex-col gap-3 rounded-xl border p-4"
            data-testid="signed-out"
          >
            <h1 class="text-base font-bold">Ask your company’s agents about an email</h1>
            <p class="text-muted-foreground text-sm">
              Sign in to Tinfirm once on this computer. A window opens where you sign in as you do
              in Tinfirm and allow this add-in to act as you.
            </p>
            <div>
              <button
                hlmBtn
                type="button"
                [disabled]="signingIn()"
                (click)="signIn()"
                data-testid="sign-in"
              >
                @if (signingIn()) {
                  <hlm-spinner class="size-4" />
                }
                Sign in to Tinfirm
              </button>
            </div>
          </section>
        }
        @case ('ready') {
          @if (email(); as mail) {
            <section class="bg-card rounded-xl border p-4" aria-label="The open email">
              <div class="flex items-start gap-2">
                <ng-icon
                  name="lucideMail"
                  class="text-muted-foreground mt-0.5"
                  aria-hidden="true"
                />
                <div class="min-w-0">
                  <p class="truncate text-sm font-semibold" data-testid="email-subject">
                    {{ mail.subject }}
                  </p>
                  @if (mail.from) {
                    <p class="text-muted-foreground truncate text-xs">From {{ mail.from }}</p>
                  }
                </div>
              </div>
            </section>

            @if (current(); as remembered) {
              <section
                class="bg-card flex flex-col gap-3 rounded-xl border p-4"
                aria-live="polite"
                data-testid="conversation"
              >
                <div class="flex items-baseline justify-between gap-2">
                  <h2 class="text-sm font-bold">
                    {{ label(remembered) }} · {{ remembered.agentName }}
                  </h2>
                  <a
                    hlmBtn
                    variant="link"
                    size="sm"
                    class="h-auto px-0"
                    target="_blank"
                    rel="noopener"
                    [href]="conversationUrl(remembered)"
                  >
                    Open in Tinfirm
                    <ng-icon name="lucideExternalLink" aria-hidden="true" />
                  </a>
                </div>

                @if (conversation(); as c) {
                  @let status = statusOf(c);
                  <ol
                    class="flex flex-col gap-3"
                    aria-label="The conversation"
                    data-testid="thread"
                  >
                    @for (m of thread(); track m.id) {
                      <li
                        class="min-w-0"
                        data-testid="thread-message"
                        [attr.data-author]="m.author"
                      >
                        @switch (m.author) {
                          @case ('you') {
                            <div class="flex flex-col items-end gap-1">
                              <div
                                class="bg-bubble-me text-bubble-me-foreground markdown-on-fill max-w-[90%] min-w-0 rounded-[16px_8px_16px_16px] px-3 py-2 text-sm"
                              >
                                <span class="sr-only">You:</span>
                                <app-markdown [text]="m.text" />
                              </div>
                              @if (m.aboutEmail) {
                                <p class="text-muted-foreground flex items-center gap-1 text-xs">
                                  <ng-icon name="lucideMail" class="shrink-0" aria-hidden="true" />
                                  With this email’s text
                                </p>
                              }
                              @if (m.waiting) {
                                <p
                                  class="text-muted-foreground flex items-center gap-1 text-xs"
                                  data-testid="message-waiting"
                                >
                                  <ng-icon name="lucideClock" class="shrink-0" aria-hidden="true" />
                                  Waits for the agent
                                </p>
                              }
                            </div>
                          }
                          @case ('agent') {
                            <div class="flex min-w-0 flex-col gap-1 text-sm">
                              <p class="text-xs font-semibold">{{ c.agentName }}</p>
                              <app-markdown [text]="m.text" />
                              @if (m.reply; as reply) {
                                <div>
                                  <button
                                    hlmBtn
                                    variant="outline"
                                    size="sm"
                                    type="button"
                                    (click)="useAsReply(reply)"
                                    data-testid="use-as-reply"
                                  >
                                    <ng-icon name="lucideReply" aria-hidden="true" />
                                    Use as reply
                                  </button>
                                </div>
                              }
                            </div>
                          }
                          @case ('tinfirm') {
                            <div
                              class="text-muted-foreground flex min-w-0 items-start gap-1.5 text-xs"
                            >
                              <ng-icon
                                name="lucideInfo"
                                class="mt-0.5 shrink-0"
                                aria-hidden="true"
                              />
                              <span class="sr-only">Tinfirm:</span>
                              <app-markdown [text]="m.text" />
                            </div>
                          }
                          @default {
                            <div class="flex min-w-0 flex-col gap-1 text-sm">
                              <p class="text-xs font-semibold">{{ m.name ?? 'Someone' }}</p>
                              <app-markdown [text]="m.text" />
                            </div>
                          }
                        }
                      </li>
                    }
                  </ol>

                  <!-- Where the conversation stands, under its newest message; "Done." says nothing more. -->
                  @if (status.tone !== 'done' || c.state === 'ENDED') {
                    <p
                      class="flex items-center gap-2 text-sm"
                      [class.text-muted-foreground]="
                        status.tone === 'agent' || status.tone === 'done'
                      "
                      [class.text-human]="status.tone === 'person'"
                      [class.text-destructive]="status.tone === 'failed'"
                      data-testid="conversation-status"
                    >
                      @if (status.active) {
                        <hlm-spinner class="size-4" />
                      }
                      {{ status.text }}
                    </p>
                  }

                  @if (mayWrite(c)) {
                    <form class="flex flex-col gap-2" (submit)="send($event)">
                      <label class="sr-only" for="pane-message">{{
                        questions().length ? 'Your answer' : 'Your message to the agent'
                      }}</label>
                      <textarea
                        hlmTextarea
                        id="pane-message"
                        rows="3"
                        [placeholder]="questions().length ? 'Your answer' : 'Ask a follow-up'"
                        [value]="message()"
                        (input)="message.set($any($event.target).value)"
                      ></textarea>
                      <div>
                        <button
                          hlmBtn
                          size="sm"
                          type="submit"
                          [disabled]="sending() || !message().trim()"
                        >
                          @if (sending()) {
                            <hlm-spinner class="size-3.5" />
                          }
                          <ng-icon name="lucideSend" aria-hidden="true" />
                          Send
                        </button>
                      </div>
                    </form>
                  }
                } @else {
                  <div class="text-muted-foreground flex items-center gap-2 text-sm">
                    <hlm-spinner class="size-4" /> Reading the conversation…
                  </div>
                }

                <div class="flex flex-wrap items-center justify-between gap-2 border-t pt-3">
                  <p class="text-muted-foreground flex items-start gap-1.5 text-xs">
                    <ng-icon name="lucideEye" class="mt-0.5 shrink-0" aria-hidden="true" />
                    Personal to you. Your company’s owners can read it in full; each read is
                    recorded and shown to you.
                  </p>
                  <button
                    hlmBtn
                    variant="ghost"
                    size="sm"
                    type="button"
                    (click)="startOver()"
                    data-testid="start-over"
                  >
                    <ng-icon name="lucideRotateCcw" aria-hidden="true" />
                    Ask something else
                  </button>
                </div>
              </section>
            } @else {
              <section class="bg-card flex flex-col gap-3 rounded-xl border p-4" data-testid="ask">
                @if (agents(); as list) {
                  @if (list.length) {
                    <div class="flex flex-col gap-1.5">
                      <label class="text-sm font-semibold" for="pane-agent">Agent</label>
                      <hlm-native-select
                        class="w-full"
                        selectId="pane-agent"
                        [value]="agentId() ?? ''"
                        (valueChange)="chooseAgent($event)"
                      >
                        @for (agent of list; track agent.id) {
                          <option [value]="agent.id">
                            {{ agent.name }} · &#64;{{ agent.handle }}
                          </option>
                        }
                      </hlm-native-select>
                    </div>
                    @if (mail.attachments) {
                      <label
                        class="flex cursor-pointer items-center gap-2 text-sm"
                        data-testid="send-attachments"
                      >
                        <input
                          type="checkbox"
                          class="accent-primary size-4 shrink-0"
                          [checked]="sendAttachments()"
                          (change)="sendAttachments.set($any($event.target).checked)"
                        />
                        <ng-icon name="lucidePaperclip" aria-hidden="true" />
                        Send its
                        {{
                          mail.attachments === 1 ? 'attachment' : mail.attachments + ' attachments'
                        }}
                        too
                      </label>
                    }
                    <div class="grid grid-cols-2 gap-2">
                      @for (choice of actions; track choice.action) {
                        <button
                          hlmBtn
                          variant="outline"
                          type="button"
                          class="h-auto flex-col items-start rounded-xl px-3 py-2 text-start whitespace-normal"
                          [disabled]="busy() !== null || !agentId()"
                          (click)="start(choice.action)"
                          [attr.data-action]="choice.action"
                        >
                          <span class="flex items-center gap-1.5">
                            @if (busy() === choice.action) {
                              <hlm-spinner class="size-3.5" />
                            }
                            {{ choice.label }}
                          </span>
                          <span class="text-muted-foreground text-xs font-normal">{{
                            choice.hint
                          }}</span>
                        </button>
                      }
                    </div>
                    <form class="flex flex-col gap-2" (submit)="ask($event)">
                      <label class="text-sm font-semibold" for="pane-ask"
                        >Or ask in your words</label
                      >
                      <textarea
                        hlmTextarea
                        id="pane-ask"
                        rows="3"
                        placeholder="Which customer is this, and what did we promise them?"
                        [value]="ownWords()"
                        (input)="ownWords.set($any($event.target).value)"
                      ></textarea>
                      <div>
                        <button
                          hlmBtn
                          size="sm"
                          type="submit"
                          [disabled]="busy() !== null || !agentId() || !ownWords().trim()"
                        >
                          @if (busy() === 'ASK') {
                            <hlm-spinner class="size-3.5" />
                          }
                          <ng-icon name="lucideSend" aria-hidden="true" />
                          Ask
                        </button>
                      </div>
                    </form>
                    <p class="text-muted-foreground text-xs">
                      The agent gets this email’s text, and its attachments when you send them: at
                      most {{ maxAttachments }} files and {{ maxAttachmentMegabytes }} MB, none that
                      holds a credential. It works with your own accounts and budget.
                    </p>
                  } @else {
                    <p class="text-muted-foreground text-sm" data-testid="no-agents">
                      You may not use any agent yet. Ask a colleague to share one with you in
                      Tinfirm.
                    </p>
                  }
                } @else {
                  <div class="text-muted-foreground flex items-center gap-2 text-sm">
                    <hlm-spinner class="size-4" /> Loading your agents…
                  </div>
                }
              </section>
            }
          } @else {
            <p class="text-muted-foreground text-sm" data-testid="no-email">
              Select an email to ask an agent about it.
            </p>
          }
        }
      }
    </div>
  `,
})
export class TaskPane implements OnInit {
  /** The route's company, whose endpoint the pane calls. */
  readonly companyId = input.required<string>();

  private readonly destroyRef = inject(DestroyRef);
  private readonly timing = inject(OUTLOOK_POLL_TIMING);

  protected readonly actions = ACTIONS;
  protected readonly phase = signal<Phase>('loading');
  protected readonly error = signal<string | null>(null);
  protected readonly signingIn = signal(false);
  protected readonly email = signal<ShownEmail | null>(null);
  protected readonly agents = signal<AgentSummary[] | null>(null);
  protected readonly agentId = signal<string | null>(null);
  protected readonly ownWords = signal('');
  protected readonly sendAttachments = signal(true);
  protected readonly maxAttachments = MAX_ATTACHMENTS;
  protected readonly maxAttachmentMegabytes = MAX_ATTACHMENT_MEGABYTES;
  protected readonly busy = signal<EmailAction | null>(null);
  protected readonly current = signal<RememberedConversation | null>(null);
  protected readonly conversation = signal<Conversation | null>(null);
  protected readonly message = signal('');
  protected readonly sending = signal(false);

  protected readonly questions = computed(() => {
    const c = this.conversation();
    return c ? openQuestions(c) : [];
  });
  protected readonly thread = computed(() => {
    const c = this.conversation();
    return c ? paneThread(c) : [];
  });

  private office: OfficeApi | null = null;
  private auth!: OutlookAuth;
  private mcp!: McpClient;
  private remembered!: EmailConversations;
  private lastAgent!: LastAgent;
  private reading = false;
  /** When a conversation that waits for the person, or for nobody, may be read again. */
  private nextIdleReadAt = 0;
  private destroyed = false;
  /** Counts the emails selected, so that a key made for an earlier one is dropped. */
  private selections = 0;
  /**
   * One key per start and per message (CONVENTIONS §15.14), kept while the same request is sent
   * again after a failure on the way.
   */
  private readonly startKey = new SubmissionKey(isMcpRetryable);
  private readonly messageKey = new SubmissionKey(isMcpRetryable);
  /**
   * What a start that failed on the way sent: its held attachments and the fence tag of its
   * message, so that the same start sent again is the same request, under the same key.
   */
  private pendingStart: {
    mailKey: string | null;
    send: boolean;
    held: HeldAttachments;
    tag: string;
  } | null = null;

  constructor() {
    this.destroyRef.onDestroy(() => (this.destroyed = true));
    pollWhileVisible((shown) => void this.follow(shown), this.timing.activeMs);
  }

  ngOnInit(): void {
    const companyId = this.companyId();
    this.auth = new OutlookAuth(companyId);
    this.mcp = new McpClient(companyId, this.auth);
    this.lastAgent = new LastAgent(companyId);
    void this.boot();
  }

  private async boot(): Promise<void> {
    this.office = await loadOffice();
    if (this.destroyed) return;
    if (!this.office) {
      // Office.js did not load, or did not start: it was refused, unreachable or timed out.
      this.phase.set('no-office');
      return;
    }
    if (!this.office.context.mailbox) {
      this.phase.set('not-outlook');
      return;
    }
    // Every Outlook of the mailbox shares the roaming settings (ADR-0076).
    this.remembered = new EmailConversations(
      this.companyId(),
      this.office.context.roamingSettings ?? null,
    );
    try {
      // A pinned pane follows the selected email (Mailbox 1.5).
      this.office.context.mailbox.addHandlerAsync(
        this.office.EventType.ItemChanged,
        () => void this.readItem(),
      );
    } catch {
      // An older Outlook: the pane reads the email it was opened on.
    }
    await this.readItem();
    if (this.destroyed) return;
    if (this.auth.isSignedIn()) {
      this.phase.set('ready');
      void this.loadAgents();
      void this.follow();
    } else {
      this.phase.set('signed-out');
    }
  }

  protected async signIn(): Promise<void> {
    if (!this.office || this.signingIn()) return;
    this.signingIn.set(true);
    this.error.set(null);
    try {
      await this.auth.signIn(this.office);
      this.phase.set('ready');
      void this.loadAgents();
      void this.follow();
    } catch (error) {
      if (!(error instanceof SignInError && error.dismissed)) {
        this.error.set(error instanceof Error ? error.message : 'The sign-in did not finish.');
      }
    } finally {
      this.signingIn.set(false);
    }
  }

  protected async signOut(): Promise<void> {
    const revoked = await this.auth.signOut();
    this.agents.set(null);
    this.conversation.set(null);
    this.error.set(
      revoked
        ? null
        : 'Signed out on this computer, but Tinfirm did not confirm it. Revoke “Tinfirm for Outlook” under Connected apps on your profile in Tinfirm.',
    );
    this.phase.set('signed-out');
  }

  /** The email Outlook shows now, and the conversation remembered for it. */
  private async readItem(): Promise<void> {
    const item = this.office?.context.mailbox?.item ?? null;
    const selection = ++this.selections;
    const key = item ? await emailKey(item) : null;
    // Another email was selected while this one's key was made.
    if (selection !== this.selections) return;
    const mail = item ? shown(item, key) : null;
    this.email.set(mail);
    // Before ADR-0076 this computer remembered a conversation by Outlook's item id.
    const legacyKey = item?.itemId ?? item?.internetMessageId ?? null;
    this.current.set(key ? this.remembered.get(key, legacyKey) : null);
    this.conversation.set(null);
    this.message.set('');
    this.error.set(null);
    this.pendingStart = null;
    void this.follow();
  }

  private async loadAgents(): Promise<void> {
    try {
      const page = await this.mcp.listAgents();
      // The person signed out meanwhile: nothing of the answer lands.
      if (this.phase() !== 'ready') return;
      const usable = page.items.filter((agent) => agent.usableByMe && agent.status === 'ACTIVE');
      this.agents.set(usable);
      const last = this.lastAgent.get();
      this.agentId.set(usable.find((agent) => agent.id === last)?.id ?? usable[0]?.id ?? null);
    } catch (error) {
      this.fail(error);
    }
  }

  protected chooseAgent(agentId: string | null | undefined): void {
    if (!agentId) return;
    this.agentId.set(agentId);
    this.lastAgent.set(agentId);
  }

  protected ask(event: Event): void {
    event.preventDefault();
    void this.start('ASK');
  }

  /** Starts the conversation about the open email. */
  protected async start(action: EmailAction): Promise<void> {
    const office = this.office;
    const item = office?.context.mailbox?.item;
    const agentId = this.agentId();
    const mail = this.email();
    if (!office || !item || !agentId || !mail || this.busy()) return;
    this.busy.set(action);
    this.error.set(null);
    try {
      const body = await bodyText(office, item);
      const { held, tag } = await this.holdFor(office, item, mail);
      const open: OpenEmail = {
        subject: item.subject ?? '',
        from: item.from ?? null,
        to: item.to ?? [],
        cc: item.cc ?? [],
        date: item.dateTimeCreated ?? null,
        body,
        attached: held.attached,
        leftOut: held.leftOut,
      };
      const request = {
        agent: agentId,
        message: emailRequest(action, open, this.ownWords(), tag),
        fileIds: held.ids,
      };
      const started = await submitWithKey(this.startKey, request, (key) =>
        this.mcp.startConversation(request.agent, request.message, key, request.fileIds),
      );
      this.pendingStart = null;
      const remembered = mail.key
        ? this.remembered.remember(mail.key, started, action)
        : {
            conversationId: started.id,
            agentName: started.agentName,
            action,
            startedAt: new Date().toISOString(),
          };
      if (this.email() === mail) {
        this.current.set(remembered);
        this.conversation.set(started);
        this.ownWords.set('');
      }
    } catch (error) {
      // A failure on the way keeps the held files for the same start, sent again with its key.
      if (!isMcpRetryable(error)) this.pendingStart = null;
      this.fail(error);
    } finally {
      this.busy.set(null);
    }
  }

  /**
   * The email's attachments held for the start, and the fence tag of its message: those of a
   * start of the same email and choice that failed on the way, so that the start sent again is
   * the same request, else held and drawn now.
   */
  private async holdFor(
    office: OfficeApi,
    item: MessageRead,
    mail: ShownEmail,
  ): Promise<{ held: HeldAttachments; tag: string }> {
    const send = this.sendAttachments();
    const kept = this.pendingStart;
    if (kept && kept.mailKey === mail.key && kept.send === send) return kept;
    const held = await holdAttachments(office, item, send, (name, type, file) =>
      this.mcp.holdFile(name, type, file),
    );
    const pending = { mailKey: mail.key, send, held, tag: fenceTag() };
    this.pendingStart = pending;
    return pending;
  }

  /**
   * Reads the conversation: once when none was read yet, every `activeMs` while its turn goes,
   * every `idleMs` while it waits for the person (who may act in Tinfirm) or for nobody, at once
   * when the pane is `shown` again, and no more once it has ended.
   */
  private async follow(shown = false): Promise<void> {
    if (shown) this.nextIdleReadAt = 0;
    const remembered = this.current();
    const read = this.conversation();
    if (!remembered || this.phase() !== 'ready' || this.reading) return;
    if (read && read.state === 'ENDED') return;
    if (read && !isActive(read) && Date.now() < this.nextIdleReadAt) return;
    this.reading = true;
    try {
      const conversation = await this.mcp.getConversation(remembered.conversationId);
      // The person signed out meanwhile: nothing of the answer lands.
      if (this.phase() !== 'ready') return;
      this.nextIdleReadAt = Date.now() + this.timing.idleMs;
      if (this.current() === remembered) this.conversation.set(conversation);
    } catch (error) {
      this.fail(error);
    } finally {
      this.reading = false;
    }
  }

  protected mayWrite(conversation: Conversation): boolean {
    if (conversation.state !== 'OPEN' || isActive(conversation)) return false;
    return (this.questions().length ? conversation.mayAnswer : conversation.mayPost) === true;
  }

  protected async send(event: Event): Promise<void> {
    event.preventDefault();
    const conversation = this.conversation();
    const remembered = this.current();
    const body = this.message().trim();
    if (!conversation || !body || this.sending()) return;
    this.sending.set(true);
    this.error.set(null);
    try {
      const request = {
        conversation: conversation.id,
        body,
        answersOpenQuestions: this.questions().length > 0,
      };
      // The answer is the conversation as it is now, the message among its messages: the pane
      // shows it from the answer, and reads nothing more for it, so that a read that fails after
      // the message was taken cannot leave it in the composer to be sent twice.
      const sent = await submitWithKey(this.messageKey, request, (key) =>
        this.mcp.sendMessage(request.conversation, request.body, request.answersOpenQuestions, key),
      );
      // The person may have selected another email meanwhile: its pane is not this conversation's.
      if (this.current() === remembered) {
        this.message.set('');
        this.conversation.set(sent);
      }
    } catch (error) {
      this.fail(error);
    } finally {
      this.sending.set(false);
    }
  }

  /** Outlook's own reply form, with the agent's text: the person edits and sends it. */
  protected useAsReply(markdown: string): void {
    this.office?.context.mailbox?.item?.displayReplyForm({ htmlBody: replyHtml(markdown) });
  }

  protected startOver(): void {
    const mail = this.email();
    if (mail?.key) this.remembered.forget(mail.key);
    this.current.set(null);
    this.conversation.set(null);
    this.message.set('');
    this.pendingStart = null;
  }

  protected conversationUrl(remembered: RememberedConversation): string {
    return `${location.origin}/c/${this.companyId()}/conversations/${remembered.conversationId}`;
  }

  protected label(remembered: RememberedConversation): string {
    return actionLabel(remembered.action);
  }

  protected statusOf(conversation: Conversation) {
    return paneStatus(conversation);
  }

  private fail(error: unknown): void {
    if (error instanceof SignedOutError) {
      this.phase.set('signed-out');
    }
    this.error.set(toolErrorMessage(error));
  }
}
