import {
  afterNextRender,
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  ElementRef,
  inject,
  Injector,
  input,
  linkedSignal,
  resource,
  signal,
  untracked,
} from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { NgIcon, provideIcons } from '@ng-icons/core';
import {
  lucideArrowLeft,
  lucideBot,
  lucideCircleAlert,
  lucideCircleCheck,
  lucideClock,
  lucideLink,
  lucidePause,
  lucidePlug,
  lucideSend,
  lucideX,
} from '@ng-icons/lucide';
import { HlmAlertImports } from '@spartan-ng/helm/alert';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmEmptyImports } from '@spartan-ng/helm/empty';
import { HlmInput } from '@spartan-ng/helm/input';
import { HlmKbdImports } from '@spartan-ng/helm/kbd';
import { HlmLabel } from '@spartan-ng/helm/label';
import { HlmNativeSelectImports } from '@spartan-ng/helm/native-select';
import { HlmSpinner } from '@spartan-ng/helm/spinner';
import { Api } from '../../../api/generated/api';
import {
  getAgent,
  getAgentReadiness,
  listAgents,
  listMyAccounts,
  listSystems,
  startConversation,
} from '../../../api/generated/functions';
import type {
  AgentReadiness,
  AgentReadinessSystem,
  ConversationStartRequest,
  ExternalSystem,
  SystemKind,
} from '../../../api/generated/models';
import { problemMessage, problemReason, toProblem } from '../../../api/problem';
import { CallerPrincipal } from '../../../core/caller-principal';
import { loadAll } from '../../../core/company-data';
import { CurrentCompany } from '../../../core/current-company';
import { Notify } from '../../../core/notify';
import { isRetryable, SubmissionKey, submitWithKey } from '../../../core/submission-key';
import { ErrorState } from '../../../shared/error-state';
import { initials } from '../../../shared/forms';
import { ACCESS_LABELS, SYSTEM_KIND_LABELS } from '../../../shared/labels';
import { ListSkeleton } from '../../../shared/list-skeleton';
import { signInReturn } from '../../../shared/sign-in-result';
import { formatAbsolute } from '../../../shared/time';
import { WhoActsWhoPays } from '../../../shared/who-acts-who-pays';
import { WideningCard } from '../agents/widening-card';
import { MAX_MESSAGE, MOD_KEY } from './composer';
import { ConnectNow } from './connect-now';

/** The kinds of systems a conversation can be about: issues and change requests. */
export const SUBJECT_KINDS: ReadonlySet<SystemKind> = new Set<SystemKind>([
  'JIRA_DC',
  'JIRA_CLOUD',
  'GITHUB',
  'GITLAB',
]);

/** The most repositories a workspace takes (`ConversationStartRequest.repositoryIds`). */
const MAX_REPOSITORIES = 20;

/** The systems of the agent the reader would connect first, or sign in to again. */
const TO_CONNECT: ReadonlySet<AgentReadinessSystem['binding']> = new Set<
  AgentReadinessSystem['binding']
>(['CONNECT', 'RECONNECT_YOURS']);

/** Why nobody can pay, or no host may ever run it (`details.reason` of a refused start). */
const START_REASONS: Record<string, string> = {
  GATEWAY_NOT_CONFIGURED:
    'An admin must set up model access first: the company’s model gateway is not configured.',
  PRINCIPAL_INACTIVE: 'Your model access is switched off. Ask an admin.',
  SUBSCRIPTION_GONE:
    'The subscription your settings pay with is no longer on its runner host. Pick another payer in your settings of the agent.',
  PERSONAL_HOST_CREDENTIAL_ONLY:
    'Your personal runner host runs only work paid by your own subscription.',
  MODEL_REQUIRED:
    'Choose a model in your settings of the agent: your budget pays only for a model the company’s gateway serves.',
  MODEL_NOT_ALLOWED:
    'Your model access does not allow the model in your settings of the agent. Pick another there.',
  NO_RUNNER_HOST: 'No runner host of the company runs this agent’s runtime.',
  NO_HOST_FOR_CREDENTIALS: 'No runner host may carry this conversation’s credentials.',
  CANNOT_RUN_ANYWHERE: 'No runner host may run this conversation as it is set up.',
};

/** A refused start in words: the reason of `MODEL_ACCESS_UNAVAILABLE` or `NO_HOST_FOR_CONVERSATION`. */
export function startProblem(error: unknown): string {
  const problem = toProblem(error);
  if (problem.code === 'MODEL_ACCESS_UNAVAILABLE' || problem.code === 'NO_HOST_FOR_CONVERSATION') {
    const reason = problemReason(error);
    if (reason && START_REASONS[reason]) return START_REASONS[reason];
  }
  const message = problemMessage(error, {
    AGENT_NOT_USABLE: 'This agent is not shared with you any more.',
  });
  return isRetryable(error) ? `${message} Start again: the conversation is started once.` : message;
}

/**
 * The conversation's title as the server makes it from the first message (`ConversationService.title`):
 * its first line that is not blank, without a heading's `#`s; null for a message still empty.
 */
export function conversationTitle(message: string): string | null {
  const line = message
    .split(/\r?\n/)
    .map((l) => l.trim())
    .find((l) => l.length > 0);
  if (line === undefined) return null;
  return line.replace(/^#+\s*/, '').trim() || 'Conversation';
}

/** What the readiness says before the start, besides the panel (plan §4.2, the states). */
export interface ReadinessNote {
  readonly key: string;
  readonly tone: 'refused' | 'waits';
  readonly text: string;
}

export function readinessNotes(r: AgentReadiness): ReadinessNote[] {
  const notes: ReadinessNote[] = [];
  const payer = r.payer;
  if (payer.state === 'REFUSED') {
    notes.push({
      key: 'payer',
      tone: 'refused',
      text:
        (payer.reason && START_REASONS[payer.reason]) ??
        payer.detail ??
        'Nobody can pay for a conversation with this agent as your settings stand.',
    });
  } else if (payer.state === 'WAITING') {
    const until = payer.until ? formatAbsolute(payer.until) : null;
    const why =
      payer.reason === 'BUDGET_SPENT'
        ? until
          ? `until your budget resets on ${until}`
          : 'until your budget resets'
        : payer.reason === 'HOST_OFFLINE'
          ? 'until the runner host of your subscription is online'
          : 'until your model access works again';
    notes.push({
      key: 'payer',
      tone: 'waits',
      text: `You can start; the first turn waits ${why}.`,
    });
  }
  if (!r.hosts.runnable) {
    notes.push({
      key: 'hosts',
      tone: 'waits',
      text: `No runner host can take it now${r.hosts.reason ? `: ${r.hosts.reason.replace(/\.$/, '')}` : ''}. You can start; the first turn waits.`,
    });
  }
  return notes;
}

/**
 * Start a conversation, `conversations/new?agentId=&subjectSystemId=&subjectKey=` (plan §4.2): say
 * who acts and who pays before the first message goes. A page rather than a dialog, so that it has
 * room on a phone.
 *
 * The first message leads: one large box whose header picks the agent ("To", those the reader may
 * use, `listAgents usable=true`) with a pill of its readiness (ready, the first turn waits, cannot
 * start), whose text says the conversation's title as it is typed, and whose foot holds what it is
 * about (an issue or a change request of a tracker or code host, opened from an optional chip), the
 * repositories of the workspace as chips (the agent's, preset from the settings' defaults) and
 * Start (or Ctrl+Enter / ⌘+Enter). Right under it, the panel fills from the agent's readiness
 * (`getAgentReadiness`): the account on each system, who pays, whether a host can run it. A new
 * version of the agent to accept shows W3's widening card. A system the reader would connect first
 * offers "Connect now, or when the agent first needs it" (orange, optional). Start
 * (`startConversation`, an idempotent submission) opens the conversation.
 *
 * The states: no agent shared with the reader yet; nobody can pay (`REFUSED`: Start is off); the
 * first turn would wait (`WAITING`); no host can take it now; systems to connect; a widening.
 */
@Component({
  selector: 'app-new-conversation',
  imports: [
    RouterLink,
    NgIcon,
    HlmAlertImports,
    HlmButton,
    HlmEmptyImports,
    HlmInput,
    HlmKbdImports,
    HlmLabel,
    HlmNativeSelectImports,
    HlmSpinner,
    ConnectNow,
    ErrorState,
    ListSkeleton,
    WhoActsWhoPays,
    WideningCard,
  ],
  providers: [
    provideIcons({
      lucideArrowLeft,
      lucideBot,
      lucideCircleAlert,
      lucideCircleCheck,
      lucideClock,
      lucideLink,
      lucidePause,
      lucidePlug,
      lucideSend,
      lucideX,
    }),
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {
    class: 'mx-auto flex w-full max-w-3xl flex-col gap-5',
    'data-testid': 'new-conversation',
  },
  template: `
    @let companyId = current.id() ?? '';
    <div class="flex flex-col gap-1">
      <a
        hlmBtn
        variant="ghost"
        size="sm"
        class="-ms-3 self-start"
        [routerLink]="['/c', companyId, 'conversations']"
      >
        <ng-icon name="lucideArrowLeft" aria-hidden="true" />
        Conversations
      </a>
      <h1 class="text-xl font-bold tracking-tight sm:text-2xl">New conversation</h1>
      <p class="text-muted-foreground text-sm">
        Say what you need. Who acts and who pays sits right under it, before anything goes.
      </p>
    </div>

    @if (agentsRes.isLoading() && !agentsRes.hasValue()) {
      <app-list-skeleton [rows]="3" />
    } @else if (agentsRes.error(); as error) {
      <app-error-state
        [error]="error"
        heading="Couldn't load the agents you may use"
        (retry)="agentsRes.reload()"
      />
    } @else if (agents().length === 0) {
      <div hlmEmpty class="border" data-testid="no-agents">
        <div hlmEmptyHeader>
          <div hlmEmptyMedia variant="icon"><ng-icon name="lucideBot" aria-hidden="true" /></div>
          <h2 hlmEmptyTitle>No agent is shared with you yet</h2>
          <p hlmEmptyDescription>
            Ask an agent’s owner to share it with one of your groups, or create one of your own.
          </p>
        </div>
        <div hlmEmptyContent>
          <a hlmBtn size="sm" [routerLink]="['/c', companyId, 'agents', 'new']">Create an agent</a>
        </div>
      </div>
    } @else {
      <form class="flex flex-col gap-5" novalidate (submit)="start($event)">
        <section
          class="bg-popover border-input focus-within:border-ring focus-within:ring-ring/30 flex flex-col overflow-hidden rounded-[18px] border transition-[border-color,box-shadow] focus-within:ring-3"
          aria-labelledby="new-message"
          data-testid="first-message-box"
        >
          <div class="flex flex-col gap-2 border-b py-3 ps-4 pe-3.5 sm:ps-5">
            <div class="flex flex-wrap items-center gap-x-2.5 gap-y-2">
              <label class="text-muted-foreground text-xs font-semibold" for="new-agent-select"
                >To</label
              >
              <div class="relative flex max-w-full min-w-0 items-center">
                @if (agent(); as a) {
                  <span
                    class="bg-primary text-primary-foreground pointer-events-none absolute start-1.5 z-10 inline-flex size-6 items-center justify-center rounded-full text-[0.625rem] font-bold"
                    aria-hidden="true"
                    >{{ agentInitials() }}</span
                  >
                }
                <hlm-native-select
                  class="max-w-full min-w-0"
                  selectId="new-agent-select"
                  data-testid="agent-select"
                  [selectClass]="
                    'bg-muted hover:bg-accent h-9 rounded-full border-border font-semibold ' +
                    (agent() ? 'ps-9' : 'ps-3.5')
                  "
                  [value]="chosenId() ?? ''"
                  (valueChange)="choose($event ?? '')"
                >
                  <option hlmNativeSelectOption value="">Pick an agent</option>
                  @for (a of agents(); track a.id) {
                    <option hlmNativeSelectOption [value]="a.id">
                      {{ a.name }} · &#64;{{ a.handle }}
                    </option>
                  }
                </hlm-native-select>
              </div>
              @if (readinessState(); as state) {
                <span
                  class="ms-auto inline-flex h-6 shrink-0 items-center gap-1.5 rounded-full px-2.5 text-xs font-semibold"
                  data-testid="readiness-state"
                  [attr.data-state]="state.key"
                  [class]="state.tone"
                >
                  <ng-icon [name]="state.icon" size="13" aria-hidden="true" />
                  {{ state.label }}
                </span>
              }
            </div>
            @if (agent()?.description; as description) {
              <p class="text-muted-foreground line-clamp-2 text-xs text-pretty">
                {{ description }}
              </p>
            }
          </div>

          <h2 class="sr-only" id="new-message">
            <label for="new-message-text">Your first message</label>
          </h2>
          <textarea
            #messageInput
            id="new-message-text"
            rows="8"
            class="placeholder:text-muted-foreground block max-h-[60vh] min-h-64 w-full resize-none bg-transparent px-4 pt-5 pb-2 text-base leading-relaxed outline-none [field-sizing:content] sm:px-5 md:text-[1.0625rem]"
            data-testid="first-message"
            aria-describedby="new-message-hint"
            [attr.maxlength]="maxMessage"
            [placeholder]="agent() ? 'What should ' + agent()!.name + ' do?' : 'What should it do?'"
            [value]="message()"
            (input)="message.set(messageInput.value)"
            (keydown)="onKeydown($event)"
          ></textarea>
          <p
            class="text-muted-foreground flex min-w-0 items-baseline gap-2 px-4 pb-4 text-xs sm:px-5"
            id="new-message-hint"
            data-testid="title-preview"
          >
            @if (title(); as t) {
              <span class="shrink-0 font-semibold">Title<span class="sr-only">:</span></span>
              &ngsp;<span class="text-foreground min-w-0 truncate font-semibold">{{ t }}</span>
            } @else {
              Markdown. Its first line becomes the conversation’s title.
            }
          </p>

          @if (agent()?.status === 'PAUSED') {
            <p
              class="bg-warning/10 mx-3 mb-3 flex items-start gap-2 rounded-[10px] px-3 py-2.5 text-sm"
              data-testid="agent-paused"
            >
              <ng-icon name="lucidePause" class="text-warning mt-0.5 shrink-0" aria-hidden="true" />
              {{ agent()!.name }} is paused: you can start, and its first turn waits until it is
              resumed.
            </p>
          }
          @for (note of notes(); track note.key) {
            @if (note.tone === 'refused') {
              <p
                class="bg-destructive/10 mx-3 mb-3 flex items-start gap-2 rounded-[10px] px-3 py-2.5 text-sm"
                role="alert"
                data-testid="readiness-refused"
              >
                <ng-icon
                  name="lucideCircleAlert"
                  class="text-destructive mt-0.5 shrink-0"
                  aria-hidden="true"
                />
                {{ note.text }}
              </p>
            } @else {
              <p
                class="bg-warning/10 mx-3 mb-3 flex items-start gap-2 rounded-[10px] px-3 py-2.5 text-sm"
                data-testid="readiness-waits"
                [attr.data-note]="note.key"
              >
                <ng-icon
                  name="lucideClock"
                  class="text-warning mt-0.5 shrink-0"
                  aria-hidden="true"
                />
                {{ note.text }}
              </p>
            }
          }
          @if (error(); as message) {
            <p
              class="bg-destructive/10 mx-3 mb-3 flex items-start gap-2 rounded-[10px] px-3 py-2.5 text-sm"
              role="alert"
              data-testid="start-error"
            >
              <ng-icon
                name="lucideCircleAlert"
                class="text-destructive mt-0.5 shrink-0"
                aria-hidden="true"
              />
              {{ message }}
            </p>
          }

          @if (aboutOpen()) {
            <section
              class="flex flex-col gap-3 border-t px-4 py-3.5 sm:px-5"
              aria-labelledby="new-subject"
              data-testid="subject"
            >
              <div class="flex items-start gap-2">
                <div class="flex min-w-0 flex-1 flex-col gap-0.5">
                  <h3 class="text-sm font-semibold" id="new-subject">About</h3>
                  <p class="text-muted-foreground text-xs">
                    An issue or a change request it is about. Its conversations are grouped by it.
                  </p>
                </div>
                <button
                  hlmBtn
                  type="button"
                  variant="ghost"
                  size="icon-sm"
                  class="-me-1.5 shrink-0"
                  data-testid="subject-close"
                  (click)="closeAbout()"
                >
                  <ng-icon name="lucideX" aria-hidden="true" />
                  <span class="sr-only">Not about an issue</span>
                </button>
              </div>
              <div class="grid gap-3 sm:grid-cols-2">
                <div class="flex min-w-0 flex-col gap-1.5">
                  <label hlmLabel for="new-subject-system">System</label>
                  <hlm-native-select
                    class="w-full"
                    selectId="new-subject-system"
                    [value]="subjectSystem()"
                    (valueChange)="subjectSystem.set($event ?? '')"
                  >
                    <option hlmNativeSelectOption value="">None</option>
                    @for (s of subjectSystems(); track s.id) {
                      <option hlmNativeSelectOption [value]="s.id">
                        {{ s.name }} · {{ kindLabels[s.kind] }}
                      </option>
                    }
                  </hlm-native-select>
                </div>
                <div class="flex min-w-0 flex-col gap-1.5">
                  <label hlmLabel for="new-subject-key">Key</label>
                  <input
                    #keyInput
                    hlmInput
                    id="new-subject-key"
                    class="font-mono"
                    maxlength="200"
                    data-testid="subject-key"
                    placeholder="SHOP-241, acme/shop#57"
                    [value]="subjectKeyText()"
                    [disabled]="!subjectSystem()"
                    (input)="subjectKeyText.set(keyInput.value)"
                  />
                </div>
                @if (subjectSystem()) {
                  <div class="flex min-w-0 flex-col gap-1.5">
                    <label hlmLabel for="new-subject-url"
                      >Link <span class="text-muted-foreground font-normal">(optional)</span></label
                    >
                    <input
                      #urlInput
                      hlmInput
                      id="new-subject-url"
                      type="url"
                      maxlength="2000"
                      placeholder="https://"
                      [value]="subjectUrl()"
                      (input)="subjectUrl.set(urlInput.value)"
                    />
                  </div>
                  <div class="flex min-w-0 flex-col gap-1.5">
                    <label hlmLabel for="new-subject-title"
                      >Title
                      <span class="text-muted-foreground font-normal">(optional)</span></label
                    >
                    <input
                      #titleInput
                      hlmInput
                      id="new-subject-title"
                      maxlength="500"
                      [value]="subjectTitle()"
                      (input)="subjectTitle.set(titleInput.value)"
                    />
                  </div>
                }
              </div>
              @if (subjectError(); as message) {
                <p class="text-destructive text-sm" role="alert">{{ message }}</p>
              }
            </section>
          }

          <div class="bg-muted/60 flex flex-wrap items-center gap-2 border-t px-3 py-2.5 sm:ps-4">
            <div class="flex min-w-0 flex-1 flex-wrap items-center gap-2">
              @if (!aboutOpen()) {
                <button
                  type="button"
                  class="border-input hover:bg-accent focus-visible:ring-ring/50 inline-flex h-8 max-w-full items-center gap-1.5 rounded-full border border-dashed ps-2.5 pe-3 text-sm font-semibold outline-none focus-visible:ring-3"
                  data-testid="subject-open"
                  (click)="openAbout()"
                >
                  <ng-icon
                    name="lucideLink"
                    class="text-muted-foreground shrink-0"
                    aria-hidden="true"
                  />
                  About an issue
                  <span class="text-muted-foreground font-normal">optional</span>
                </button>
              }
              @if (repositories().length > 0) {
                <ul
                  class="flex min-w-0 flex-wrap gap-2"
                  aria-label="Repositories: the workspace of its turns, preset from your settings"
                  data-testid="repositories"
                >
                  @for (repo of repositories(); track repo.repositoryId) {
                    <li class="max-w-full min-w-0" [attr.data-repository]="repo.repositoryId">
                      <label
                        class="bg-card hover:bg-accent has-[:focus-visible]:ring-ring/50 flex h-8 max-w-full min-w-0 cursor-pointer items-center gap-2 rounded-full border ps-2.5 pe-3 has-[:focus-visible]:ring-3"
                      >
                        <input
                          type="checkbox"
                          class="accent-primary size-3.5 shrink-0 outline-none"
                          [checked]="chosenRepos().has(repo.repositoryId)"
                          (change)="toggleRepo(repo.repositoryId, $any($event.target).checked)"
                        />
                        <span class="min-w-0 truncate font-mono text-xs">{{ repo.name }}</span>
                        <span class="text-muted-foreground shrink-0 text-xs">{{
                          accessLabels[repo.access]
                        }}</span>
                      </label>
                    </li>
                  }
                </ul>
              }
            </div>
            <div class="ms-auto flex items-center gap-3">
              <span class="text-muted-foreground hidden items-center gap-1 text-xs sm:inline-flex">
                <kbd hlmKbd>{{ modKey }}</kbd>
                <kbd hlmKbd>Enter</kbd>
                <span class="sr-only">to start</span>
              </span>
              <button
                hlmBtn
                size="lg"
                type="submit"
                data-testid="start-conversation"
                [disabled]="!canStart()"
              >
                @if (busy()) {
                  <hlm-spinner aria-hidden="true" />
                } @else {
                  <ng-icon name="lucideSend" aria-hidden="true" />
                }
                Start
              </button>
            </div>
            @if (repositories().length > 0) {
              @if (chosenRepos().size === 0) {
                <p class="text-muted-foreground basis-full text-xs" data-testid="no-repository">
                  No repository: the agent asks you if it needs one.
                </p>
              } @else if (chosenRepos().size > maxRepositories) {
                <p class="text-destructive basis-full text-xs" role="alert">
                  Pick at most 20 repositories.
                </p>
              }
            }
          </div>
        </section>

        @if (agent(); as a) {
          @if (a.widening) {
            <app-widening-card
              [companyId]="companyId"
              [agent]="a"
              [widening]="a.widening"
              (accepted)="onAccepted()"
            />
          }
        }

        @if (chosenId()) {
          @if (readiness(); as r) {
            <div class="flex flex-col gap-3" data-testid="readiness">
              <app-who-acts-who-pays
                heading="Who acts and who pays"
                intro="An agent holds no credentials: it acts as you, with the accounts below."
                [readiness]="r"
                [access]="caller.modelAccess()"
                [accounts]="accounts()"
                [columns]="true"
              />
              @if (toConnect().length > 0) {
                <section
                  class="bg-card border-human flex flex-col gap-3 rounded-xl border px-3.5 py-3 text-sm"
                  aria-labelledby="connect-now-title"
                  data-testid="connect-box"
                >
                  <div class="flex items-start gap-2.5">
                    <ng-icon
                      name="lucidePlug"
                      class="text-human mt-0.5 shrink-0"
                      aria-hidden="true"
                    />
                    <div class="flex flex-col gap-0.5">
                      <h3 class="text-human font-semibold" id="connect-now-title">
                        Connect now, or when the agent first needs it
                      </h3>
                      <p class="text-muted-foreground">
                        Optional: a conversation that needs the system asks you then.
                      </p>
                    </div>
                  </div>
                  <ul class="flex flex-col gap-3">
                    @for (s of toConnect(); track s.systemId) {
                      <li class="flex flex-col gap-1.5">
                        <span class="font-semibold"
                          >{{ s.name
                          }}{{ s.binding === 'RECONNECT_YOURS' ? ': sign in again' : '' }}</span
                        >
                        <app-connect-now
                          [companyId]="companyId"
                          [systemId]="s.systemId"
                          [systemName]="s.name"
                          [systemKind]="s.kind"
                          [signInAvailable]="signIn().get(s.systemId) ?? false"
                          (connected)="onConnected(s.name)"
                        />
                      </li>
                    }
                  </ul>
                </section>
              }
            </div>
          } @else if (readinessRes.error(); as error) {
            <div hlmAlert variant="destructive" data-testid="readiness-error">
              <ng-icon name="lucideCircleAlert" aria-hidden="true" />
              <p hlmAlertDescription>{{ readinessMessage(error) }}</p>
            </div>
          } @else {
            <app-list-skeleton [rows]="2" />
          }
        }
      </form>
    }
  `,
})
export class NewConversation {
  private readonly api = inject(Api);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly notify = inject(Notify);
  protected readonly current = inject(CurrentCompany);
  protected readonly caller = inject(CallerPrincipal);

  /** From the query (`?agentId=&subjectSystemId=&subjectKey=`). */
  readonly agentId = input<string | undefined>();
  readonly subjectSystemId = input<string | undefined>();
  readonly subjectKey = input<string | undefined>();

  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly injector = inject(Injector);
  private readonly back = signInReturn();
  private readonly startKey = new SubmissionKey();

  protected readonly kindLabels = SYSTEM_KIND_LABELS;
  protected readonly accessLabels = ACCESS_LABELS;
  protected readonly maxMessage = MAX_MESSAGE;
  protected readonly maxRepositories = MAX_REPOSITORIES;
  protected readonly modKey = MOD_KEY;

  // --- What the reader may use ------------------------------------------------------------------

  protected readonly agentsRes = resource({
    params: () => this.current.id(),
    loader: ({ params: companyId }) =>
      loadAll((cursor) => this.api.invoke(listAgents, { companyId, usable: true, cursor })),
  });
  protected readonly agents = computed(() =>
    this.agentsRes.hasValue() ? (this.agentsRes.value() ?? []) : [],
  );

  /** The agent picked: the query's, else the only one the reader may use. */
  protected readonly chosenId = linkedSignal<string | null>(() => {
    const fromQuery = this.agentId();
    if (fromQuery) return fromQuery;
    const agents = this.agents();
    return agents.length === 1 ? agents[0].id : null;
  });

  /** Bumped after an account is connected or a version accepted: the readiness is read again. */
  private readonly version = signal(0);

  protected readonly agentRes = resource({
    params: () => {
      const companyId = this.current.id();
      const agentId = this.chosenId();
      return companyId && agentId ? { companyId, agentId, v: this.version() } : undefined;
    },
    loader: ({ params }) =>
      this.api.invoke(getAgent, { companyId: params.companyId, agentId: params.agentId }),
  });
  protected readonly agent = computed(() => {
    const a = this.agentRes.hasValue() ? this.agentRes.value() : undefined;
    return a && a.id === this.chosenId() ? a : null;
  });
  protected readonly agentInitials = computed(() => initials(this.agent()?.name));

  protected readonly readinessRes = resource({
    params: () => {
      const companyId = this.current.id();
      const agentId = this.chosenId();
      return companyId && agentId ? { companyId, agentId, v: this.version() } : undefined;
    },
    loader: ({ params }) =>
      this.api.invoke(getAgentReadiness, { companyId: params.companyId, agentId: params.agentId }),
  });
  protected readonly readiness = computed(() => {
    if (!this.readinessRes.hasValue() || this.readinessRes.error()) return null;
    return this.readinessRes.value() ?? null;
  });
  protected readonly notes = computed(() => {
    const r = this.readiness();
    return r ? readinessNotes(r) : [];
  });
  protected readonly refused = computed(() => this.readiness()?.payer.state === 'REFUSED');
  /** The pill in the header of the message: whether Start starts it now. */
  protected readonly readinessState = computed(() => {
    if (!this.readiness()) return null;
    if (this.refused()) {
      return {
        key: 'refused',
        label: 'Can’t start yet',
        icon: 'lucideCircleAlert',
        tone: 'bg-destructive/10 text-destructive',
      };
    }
    if (this.notes().length > 0 || this.agent()?.status === 'PAUSED') {
      return {
        key: 'waits',
        label: 'First turn waits',
        icon: 'lucideClock',
        tone: 'bg-warning/10 text-warning',
      };
    }
    return {
      key: 'ready',
      label: 'Ready to start',
      icon: 'lucideCircleCheck',
      tone: 'bg-success/10 text-success',
    };
  });
  protected readonly toConnect = computed(() =>
    (this.readiness()?.systems ?? []).filter((s) => TO_CONNECT.has(s.binding)),
  );

  private readonly accountsRes = resource({
    params: () => {
      const companyId = this.current.id();
      return companyId ? { companyId, v: this.version() } : undefined;
    },
    loader: ({ params }) => this.api.invoke(listMyAccounts, { companyId: params.companyId }),
  });
  protected readonly accounts = computed(() =>
    this.accountsRes.hasValue() ? (this.accountsRes.value()?.items ?? []) : [],
  );
  protected readonly signIn = computed<ReadonlyMap<string, boolean>>(
    () => new Map(this.accounts().map((a) => [a.system.id, a.oauthAvailable])),
  );

  private readonly systemsRes = resource({
    params: () => this.current.id(),
    loader: ({ params: companyId }) =>
      loadAll((cursor) => this.api.invoke(listSystems, { companyId, cursor })),
  });
  /** The company's trackers and code hosts: what a conversation can be about. */
  protected readonly subjectSystems = computed<ExternalSystem[]>(() =>
    (this.systemsRes.hasValue() ? (this.systemsRes.value() ?? []) : []).filter(
      (s) => SUBJECT_KINDS.has(s.kind) && !s.personal,
    ),
  );

  // --- The form ---------------------------------------------------------------------------------

  protected readonly subjectSystem = linkedSignal(() => this.subjectSystemId() ?? '');
  protected readonly subjectKeyText = linkedSignal(() => this.subjectKey() ?? '');
  protected readonly subjectUrl = signal('');
  protected readonly subjectTitle = signal('');
  protected readonly message = signal('');
  protected readonly title = computed(() => conversationTitle(this.message()));
  /** "About" is open: by the chip, or with a subject from the query. */
  protected readonly aboutOpen = linkedSignal(
    () => !!this.subjectSystemId() || !!this.subjectKey(),
  );

  /**
   * The agent's repositories the reader's turns use, the only ones a start may name: as the version
   * they accepted has them (one the new version adds waits for the acceptance, one it raises keeps
   * the access accepted), less those of a system their settings switch off.
   */
  protected readonly repositories = computed(() => {
    const agent = this.agent();
    if (!agent) return [];
    const off = new Set(this.readiness()?.settings.systemsOff ?? []);
    const widened = new Map(
      (agent.widening?.repositories ?? []).map((r) => [r.repositoryId, r.was ?? null]),
    );
    return agent.repositories.flatMap((r) => {
      if (off.has(r.systemId)) return [];
      if (!widened.has(r.repositoryId)) return [r];
      const was = widened.get(r.repositoryId);
      return was ? [{ ...r, access: was }] : [];
    });
  });
  /** The workspace: the settings' default repositories, else all of the agent's. */
  protected readonly chosenRepos = linkedSignal<ReadonlySet<string>>(() => {
    const repos = this.repositories().map((r) => r.repositoryId);
    const defaults = (this.readiness()?.settings.defaultRepositoryIds ?? []).filter((id) =>
      repos.includes(id),
    );
    return new Set(defaults.length > 0 ? defaults : repos);
  });

  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);

  protected readonly subjectError = computed(() => {
    if (!this.subjectSystem()) return null;
    if (!this.subjectKeyText().trim()) return 'Give the key people know it by, such as SHOP-241.';
    const url = this.subjectUrl().trim();
    if (url && !/^https?:\/\/[^\s@/]+(?:\/\S*)?$/i.test(url)) {
      return 'The link starts with http:// or https://, without a user name.';
    }
    return null;
  });

  protected readonly canStart = computed(
    () =>
      !!this.agent() &&
      !this.busy() &&
      !this.refused() &&
      !!this.message().trim() &&
      !this.subjectError() &&
      this.chosenRepos().size <= MAX_REPOSITORIES,
  );

  constructor() {
    // Back from "Sign in with …" of Connect now: say what happened and read the readiness again.
    effect(() => {
      const result = this.back.result();
      if (!result) return;
      untracked(() => {
        this.back.dismiss();
        if (result.connected) {
          this.notify.success(result.message, 'The agent acts with it from the first turn.');
          this.version.update((v) => v + 1);
        } else {
          this.notify.warning('Nothing was connected', result.message);
        }
      });
    });
  }

  protected choose(agentId: string): void {
    this.chosenId.set(agentId || null);
    this.error.set(null);
    // In the URL too, so that a sign-in started here comes back to this agent.
    void this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { agentId: agentId || null },
      queryParamsHandling: 'merge',
      replaceUrl: true,
    });
  }

  protected openAbout(): void {
    this.aboutOpen.set(true);
    this.focusLater('#new-subject-system');
  }

  /** Not about anything after all: the subject is cleared with the fields. */
  protected closeAbout(): void {
    this.aboutOpen.set(false);
    this.subjectSystem.set('');
    this.subjectKeyText.set('');
    this.subjectUrl.set('');
    this.subjectTitle.set('');
    this.focusLater('[data-testid=subject-open]');
  }

  /** The chip and the fields take each other's place: the cursor follows. */
  private focusLater(selector: string): void {
    afterNextRender(
      { write: () => this.host.nativeElement.querySelector<HTMLElement>(selector)?.focus() },
      { injector: this.injector },
    );
  }

  protected onKeydown(event: KeyboardEvent): void {
    if (event.key === 'Enter' && (event.metaKey || event.ctrlKey)) void this.start(event);
  }

  protected toggleRepo(repositoryId: string, on: boolean): void {
    const next = new Set(this.chosenRepos());
    if (on) next.add(repositoryId);
    else next.delete(repositoryId);
    this.chosenRepos.set(next);
  }

  protected onAccepted(): void {
    this.version.update((v) => v + 1);
    this.notify.success('Version accepted', 'Your conversations use it from their next turn.');
  }

  protected onConnected(system: string): void {
    this.version.update((v) => v + 1);
    this.notify.success(`${system} is connected`, 'The agent acts with your own account there.');
  }

  protected readinessMessage(error: unknown): string {
    return problemMessage(error, { AGENT_NOT_USABLE: 'This agent is not shared with you.' });
  }

  /** The request, as `startConversation` takes it. */
  private request(): ConversationStartRequest | null {
    const agent = this.agent();
    if (!agent) return null;
    const request: ConversationStartRequest = { agentId: agent.id, message: this.message().trim() };
    const systemId = this.subjectSystem();
    if (systemId) {
      request.subject = {
        systemId,
        key: this.subjectKeyText().trim(),
        ...(this.subjectUrl().trim() ? { url: this.subjectUrl().trim() } : {}),
        ...(this.subjectTitle().trim() ? { title: this.subjectTitle().trim() } : {}),
      };
    }
    if (this.repositories().length > 0) {
      // In the agent's order, so that the same choice is the same submission; none ticked is an
      // empty list, which starts without a repository (left out, it would be all of them).
      request.repositoryIds = this.repositories()
        .map((r) => r.repositoryId)
        .filter((id) => this.chosenRepos().has(id));
    }
    return request;
  }

  protected async start(event: Event): Promise<void> {
    event.preventDefault();
    const companyId = this.current.id();
    const body = this.request();
    if (!companyId || !body || !this.canStart()) return;
    this.busy.set(true);
    this.error.set(null);
    try {
      const conversation = await submitWithKey(this.startKey, { companyId, body }, (key) =>
        this.api.invoke(startConversation, { companyId, 'Idempotency-Key': key, body }),
      );
      void this.router.navigate(['/c', companyId, 'conversations', conversation.id]);
    } catch (error) {
      this.error.set(startProblem(error));
    } finally {
      this.busy.set(false);
    }
  }
}
