import {
  ChangeDetectionStrategy,
  Component,
  computed,
  input,
  output,
  viewChild,
} from '@angular/core';
import { RouterLink } from '@angular/router';
import type { Conversation, ConversationRepository } from '../../../api/generated/models';
import { plural } from '../../../shared/labels';
import { formatCost, formatTokens, RunStatusBadge } from '../../../shared/run-status';
import { Time } from '../../../shared/time';
import { ConversationFiles } from './conversation-files';
import { ConversationOutputs } from './conversation-outputs';
import {
  conversationCost,
  type CostSummary,
  tokenBreakdown,
  tokenTotal,
} from './conversation-view';

/** Why a repository of the workspace is read-only (plan §4.1: "the grant of Analysts is read-only"). */
export function readOnlyWhy(
  repo: ConversationRepository,
  c: Pick<Conversation, 'bindings'>,
): string {
  switch (repo.readOnlyCause) {
    case 'GRANT': {
      const binding = repo.systemId ? c.bindings.find((b) => b.systemId === repo.systemId) : null;
      return binding?.groupName
        ? `the grant of ${binding.groupName} is read-only`
        : 'the grant it acts through is read-only';
    }
    case 'REQUEST':
      return 'the approval gave it read access';
    case 'AGENT':
      return 'the agent reads it only';
    default:
      return 'read-only';
  }
}

/** "$1.24", "$0.00" for nothing. */
function usd(value: number): string {
  return value > 0 ? (formatCost(value) ?? '$0.00') : '$0.00';
}

/** "$1.24", "~$1.35" while turns are not settled yet, "$0.00" before any spend. */
export function costTotal(cost: CostSummary): string {
  return cost.pendingTurns > 0 ? `~${usd(cost.total)}` : usd(cost.total);
}

/** What the total holds, in words: settled and estimated turns, and a subscription's tokens. */
export function costNotes(cost: CostSummary): string[] {
  const notes: string[] = [];
  if (cost.pendingTurns > 0) {
    const turns = plural(cost.pendingTurns, 'turn');
    const settled = cost.settledTurns > 0 ? `${usd(cost.settled)} settled, and ` : '';
    const note =
      cost.estimated > 0
        ? `${settled}~${usd(cost.estimated)} the runtime’s estimate for ${turns} whose spend ` +
          `settles a few minutes after it ends, with the tokens it reported.`
        : `${settled}${turns} still to settle, a few minutes after it ends: the runtime gave no ` +
          `estimate of its cost.`;
    notes.push(note.charAt(0).toUpperCase() + note.slice(1));
  } else if (cost.settledTurns > 0) {
    notes.push(`The settled spend of ${plural(cost.settledTurns, 'turn')}.`);
  }
  if (cost.subscriptionTokens > 0) {
    notes.push(`${formatTokens(cost.subscriptionTokens)} tokens on a subscription, at no cost.`);
  }
  if (notes.length === 0) {
    notes.push('No spend recorded yet: a turn’s spend settles a few minutes after it ends.');
  }
  return notes;
}

/** A section's heading row: its name, sentence case, and a count in faint mono. */
const HEADING = 'text-[0.8125rem] font-semibold';
const COUNT = 'text-muted-foreground font-mono text-xs tabular-nums';

/**
 * The side column of the conversation page (plan §4.1, `ConversationSide`): from `lg` a 20rem
 * column, below it a sheet behind "Details". One list of sections split by hairlines, each named
 * in sentence case: W4c's outputs (change requests with their state, documents previewed and
 * downloaded, links) and files (add, preview, download, delete), then the workspace (each
 * repository, read-only and why), the network hosts its next turn may reach, what it cost so far
 * (settled spend and tokens, and the runtime's estimate and reported tokens of turns not settled yet,
 * marked as one), and the turns
 * (each a link to its run). A file added or deleted is told to the page (`changed`), which reads the
 * conversation again; `pickFile()` opens the file picker of its files, for the composer's
 * attach button.
 */
@Component({
  selector: 'app-conversation-side',
  imports: [RouterLink, ConversationFiles, ConversationOutputs, RunStatusBadge, Time],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'flex min-w-0 flex-col divide-y', 'data-testid': 'conversation-side' },
  template: `
    @let c = conversation();
    <app-conversation-outputs
      class="py-3.5"
      [companyId]="companyId()"
      [conversationId]="c.id"
      [outputs]="c.outputs"
      [attachments]="c.attachments"
      [turns]="c.turns"
    />
    <app-conversation-files
      class="py-3.5"
      [companyId]="companyId()"
      [conversationId]="c.id"
      [attachments]="c.attachments"
      [mine]="mine()"
      [personName]="c.personName ?? null"
      [ended]="c.state === 'ENDED'"
      [mayAcknowledge]="mayAcknowledge()"
      (added)="changed.emit()"
      (deleted)="changed.emit()"
    />

    <section
      class="flex flex-col gap-2 py-3.5"
      [attr.aria-labelledby]="ids.workspace"
      data-testid="side-workspace"
    >
      <h2 [class]="heading" [id]="ids.workspace">Workspace</h2>
      @if (c.workspace.length > 0) {
        <ul class="flex flex-col gap-1.5 text-[0.8125rem]">
          @for (repo of c.workspace; track repo.repositoryId) {
            <li
              class="flex min-w-0 flex-wrap items-baseline gap-x-1.5"
              [attr.data-repository]="repo.repositoryId"
            >
              @if (repo.url) {
                <a
                  class="text-foreground hover:decoration-primary focus-visible:ring-ring/50 max-w-full truncate rounded-sm font-mono text-xs underline decoration-1 underline-offset-2 outline-none focus-visible:ring-3"
                  target="_blank"
                  rel="noopener noreferrer"
                  [href]="repo.url"
                  >{{ repo.name }}<span class="sr-only"> (opens in a new tab)</span></a
                >
              } @else {
                <span class="max-w-full truncate font-mono text-xs">{{ repo.name }}</span>
              }
              <span class="text-muted-foreground" aria-hidden="true">·</span>
              <span class="text-muted-foreground" data-testid="repository-access">
                @if (repo.readOnly) {
                  Read-only: {{ why(repo) }}
                } @else {
                  Read and write
                }
                @if (repo.source === 'REQUESTED') {
                  · added on request
                }
              </span>
            </li>
          }
        </ul>
      } @else {
        <p class="text-muted-foreground text-[0.8125rem]">
          No repositories: the agent works without code.
        </p>
      }
    </section>

    @if (hosts().length > 0) {
      <section
        class="flex flex-col gap-2 py-3.5"
        [attr.aria-labelledby]="ids.hosts"
        data-testid="side-hosts"
      >
        <h2 [class]="heading" [id]="ids.hosts">Network hosts</h2>
        <ul class="flex flex-wrap gap-1.5">
          @for (host of hosts(); track host.name) {
            <li
              class="bg-secondary inline-flex h-6 max-w-full items-center rounded-full px-2.5 font-mono text-xs"
              [title]="host.granted ? 'Granted in this conversation' : 'The agent’s'"
            >
              <span class="truncate">{{ host.name }}</span>
            </li>
          }
        </ul>
        @if (c.grantedHosts.length > 0) {
          <p class="text-muted-foreground text-xs">
            {{ c.grantedHosts.join(', ') }} granted in this conversation.
          </p>
        }
      </section>
    }

    @if (cost(); as spent) {
      <section
        class="flex flex-col gap-1.5 py-3.5"
        [attr.aria-labelledby]="ids.cost"
        data-testid="side-cost"
      >
        <div class="flex items-baseline justify-between gap-2">
          <h2 [class]="heading" [id]="ids.cost">Cost</h2>
          <span class="font-mono text-[0.8125rem] tabular-nums" data-testid="cost-total">{{
            total(spent)
          }}</span>
        </div>
        @if (tokens(spent); as breakdown) {
          <p
            class="text-muted-foreground font-mono text-xs tabular-nums"
            data-testid="cost-tokens"
            [title]="'Tokens: ' + breakdown"
          >
            <span class="sr-only">Tokens: </span>{{ breakdown }}
          </p>
        }
        @for (note of notes(spent); track $index) {
          <p class="text-muted-foreground text-xs" data-testid="cost-note">{{ note }}</p>
        }
      </section>
    }

    @if (c.turns.length > 0) {
      <section
        class="flex flex-col gap-2 py-3.5"
        [attr.aria-labelledby]="ids.turns"
        data-testid="side-turns"
      >
        <div class="flex items-center gap-1.5">
          <h2 [class]="heading" [id]="ids.turns">Turns</h2>
          <span [class]="count">{{ c.turns.length }}</span>
        </div>
        <ol class="flex flex-col gap-1.5 text-[0.8125rem]">
          @for (run of turns(); track run.id) {
            <li class="flex min-w-0 items-center gap-2">
              <a
                class="hover:decoration-primary focus-visible:ring-ring/50 min-w-0 flex-1 truncate rounded-sm underline decoration-1 underline-offset-2 outline-none focus-visible:ring-3 max-sm:py-2.5"
                [routerLink]="['/c', companyId(), 'runs', run.id]"
                >Turn {{ run.turnNumber }}<span class="sr-only">, view its run</span></a
              >
              <app-time
                class="text-muted-foreground shrink-0 font-mono text-xs"
                mode="clock"
                [value]="run.createdAt"
              />
              <app-run-status-badge class="shrink-0" [status]="run.status" />
            </li>
          }
        </ol>
      </section>
    }
  `,
})
export class ConversationSide {
  readonly conversation = input.required<Conversation>();
  readonly companyId = input.required<string>();
  /** The reader is the conversation's person: they add and delete its files. */
  readonly mine = input(false);
  /** OWNER or ADMIN: may store a file that looks like a credential, once acknowledged (ADR-0014). */
  readonly mayAcknowledge = input(false);
  /** A file was added or deleted: the page reads the conversation again. */
  readonly changed = output<void>();

  private static nextId = 0;
  private readonly files = viewChild(ConversationFiles);

  protected readonly heading = HEADING;
  protected readonly count = COUNT;
  protected readonly ids = (() => {
    const n = ConversationSide.nextId++;
    return {
      workspace: `side-workspace-${n}`,
      hosts: `side-hosts-${n}`,
      cost: `side-cost-${n}`,
      turns: `side-turns-${n}`,
    };
  })();

  /** What it cost so far, once it has turns; null while the server sends no cost. */
  protected readonly cost = computed(() => {
    const c = this.conversation();
    return c.turns.length > 0 ? conversationCost(c) : null;
  });
  protected readonly total = costTotal;
  protected readonly notes = costNotes;

  /** "In 1,150 · Cache write 210 · Cache read 5,400 · Out 325"; nothing before any token. */
  protected tokens(cost: CostSummary): string | null {
    return tokenTotal(cost.tokens) > 0 ? tokenBreakdown(cost.tokens) : null;
  }

  /** The hosts its next turn may reach (`hosts`), the agent's and the granted ones. */
  protected readonly hosts = computed(() => {
    const c = this.conversation();
    const granted = new Set(c.grantedHosts);
    const all = c.hosts ?? [...c.grantedHosts];
    return all.map((name) => ({ name, granted: granted.has(name) }));
  });
  /** The newest turn first. */
  protected readonly turns = computed(() =>
    [...this.conversation().turns].sort((a, b) => b.turnNumber - a.turnNumber),
  );

  /** Opens the file picker of its files, when the reader may add one. */
  pickFile(): boolean {
    return this.files()?.pick() ?? false;
  }

  protected why(repo: ConversationRepository): string {
    return readOnlyWhy(repo, this.conversation());
  }
}
