import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  input,
  linkedSignal,
  output,
  resource,
  signal,
} from '@angular/core';
import { RouterLink } from '@angular/router';
import { NgIcon, provideIcons } from '@ng-icons/core';
import { lucideCircleAlert, lucideTrash2 } from '@ng-icons/lucide';
import { HlmAlertImports } from '@spartan-ng/helm/alert';
import { HlmAlertDialogImports } from '@spartan-ng/helm/alert-dialog';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmSpinner } from '@spartan-ng/helm/spinner';
import { Api } from '../api/generated/api';
import {
  acceptTriggerMemoryNote,
  clearMyAgentMemory,
  clearTriggerMemory,
  deleteMyAgentMemoryNote,
  deleteTriggerMemoryNote,
  getMyAgentMemory,
  getTriggerMemory,
} from '../api/generated/functions';
import type { Agent, MemoryNote, MemoryNotes as Memory } from '../api/generated/models';
import { problemMessage } from '../api/problem';
import { ListSkeleton } from './list-skeleton';
import { Time } from './time';

/** Whose memory: the reader's own of an agent, or a trigger's (ADR-0063). */
export type MemoryOwner =
  | { readonly kind: 'person'; readonly agentId: string }
  | { readonly kind: 'trigger'; readonly triggerId: string };

/** The parts of the agent the panel names notes by; null when the reader cannot read the agent. */
export type MemoryAgent = Pick<Agent, 'name' | 'systems' | 'repositories' | 'memoryAllowed'>;

/** The accepted notes about one thing: the owner in general, a system or a repository. */
export interface MemoryGroup {
  readonly key: string;
  readonly title: string;
  readonly notes: readonly MemoryNote[];
}

/**
 * The accepted notes grouped as the prompt reads them: general first, then each system and each
 * repository in the agent's order, and last what the agent no longer uses.
 */
export function memoryGroups(
  notes: readonly MemoryNote[],
  agent: MemoryAgent | null,
  general: string,
): MemoryGroup[] {
  const keyOf = (n: MemoryNote) =>
    n.about === 'SYSTEM'
      ? `system:${n.systemId}`
      : n.about === 'REPOSITORY'
        ? `repository:${n.repositoryId}`
        : 'general';
  const order = [
    'general',
    ...(agent?.systems ?? []).map((s) => `system:${s.systemId}`),
    ...(agent?.repositories ?? []).map((r) => `repository:${r.repositoryId}`),
  ];
  const groups = new Map<string, MemoryNote[]>();
  for (const note of notes) {
    if (note.state !== 'ACTIVE') continue;
    const key = keyOf(note);
    groups.set(key, [...(groups.get(key) ?? []), note]);
  }
  const rank = (key: string) => {
    const i = order.indexOf(key);
    return i < 0 ? order.length : i;
  };
  return [...groups.entries()]
    .sort(([a], [b]) => rank(a) - rank(b))
    .map(([key, list]) => ({
      key,
      title: key === 'general' ? general : `About ${aboutName(list[0], agent)}`,
      notes: list,
    }));
}

/** What a note is about, by the agent's names: "acme/shop", "Jira", "you". */
export function aboutName(note: MemoryNote, agent: MemoryAgent | null): string {
  const gone = agent ? ' the agent no longer uses' : '';
  if (note.about === 'SYSTEM') {
    return agent?.systems.find((s) => s.systemId === note.systemId)?.name ?? `a system${gone}`;
  }
  if (note.about === 'REPOSITORY') {
    return (
      agent?.repositories.find((r) => r.repositoryId === note.repositoryId)?.name ??
      `a repository${gone}`
    );
  }
  return 'its owner';
}

const numbers = new Intl.NumberFormat('en');

/**
 * A memory of an agent (ADR-0063): the notes the agent keeps for the reader, or for a trigger, in
 * its own words. The reader deletes one note or all of them; a trigger's audience (its author, its
 * team, OWNER and ADMIN) also accepts or rejects what the trigger's conversations proposed, the
 * only orange here (DESIGN.md, the scarce orange rule). It reads and acts on its own; `revision`
 * reads it again (after a save that turned memory on or off), `changed` says it changed.
 */
@Component({
  selector: 'app-memory-notes',
  imports: [
    RouterLink,
    NgIcon,
    HlmAlertImports,
    HlmAlertDialogImports,
    HlmButton,
    HlmSpinner,
    ListSkeleton,
    Time,
  ],
  providers: [provideIcons({ lucideCircleAlert, lucideTrash2 })],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'block' },
  template: `
    <section
      class="bg-card flex scroll-mt-20 flex-col gap-4 rounded-xl border p-4 outline-none sm:p-5"
      id="memory"
      tabindex="-1"
      aria-labelledby="memory-title"
      data-testid="memory-notes"
    >
      <div class="flex min-w-0 flex-col gap-1">
        <div class="flex min-h-8 items-center justify-between gap-3">
          <div class="flex flex-wrap items-center gap-2">
            <h2 class="text-sm font-semibold" id="memory-title">Memory</h2>
            @if (memory(); as m) {
              <span
                class="inline-flex h-6 items-center rounded-full border px-2.5 text-xs font-semibold"
                [class]="m.inEffect ? 'bg-primary/10 text-primary border-transparent' : 'bg-card'"
                data-testid="memory-state"
                >{{ m.inEffect ? 'On' : 'Off' }}</span
              >
            }
          </div>
          @if ((memory()?.notes?.length ?? 0) > 0) {
            <button
              hlmBtn
              variant="ghost"
              size="sm"
              type="button"
              class="text-destructive -me-2 shrink-0"
              data-testid="clear-memory"
              [disabled]="busy()"
              (click)="confirm.set({ kind: 'clear' })"
            >
              <ng-icon name="lucideTrash2" aria-hidden="true" />
              Delete all
            </button>
          }
        </div>
        <p class="text-muted-foreground text-xs text-pretty">{{ intro() }}</p>
      </div>

      @if (error(); as message) {
        <div hlmAlert variant="destructive" data-testid="memory-error">
          <ng-icon name="lucideCircleAlert" aria-hidden="true" />
          <p hlmAlertDescription>{{ message }}</p>
        </div>
      }

      @if (memory(); as m) {
        @if (offNote(); as note) {
          <p
            class="bg-muted text-muted-foreground rounded-lg px-3 py-2 text-xs text-pretty"
            data-testid="memory-off"
          >
            {{ note }}
          </p>
        }

        @if (proposals().length > 0) {
          <div class="border-human flex flex-col rounded-lg border" data-testid="memory-proposals">
            <h3 class="text-human px-3 pt-2.5 pb-1 text-xs font-semibold">
              {{ proposals().length }} waiting for review
            </h3>
            <ul class="flex flex-col divide-y">
              @for (n of proposals(); track n.number) {
                <li class="flex min-w-0 flex-col gap-2 px-3 py-2.5" [attr.data-note]="n.number">
                  <p class="text-sm break-words">{{ n.text }}</p>
                  <p class="text-muted-foreground text-xs break-words">
                    <span class="font-mono">{{ n.id }}</span> · about {{ about(n) }} · proposed
                    <app-time [value]="n.updatedAt" />
                    @if (n.conversationId) {
                      ·
                      <a
                        class="underline-offset-2 hover:underline"
                        [routerLink]="['/c', companyId(), 'conversations', n.conversationId]"
                        >its conversation</a
                      >
                    }
                  </p>
                  @if (n.replaces; as replaced) {
                    <p class="text-muted-foreground text-xs break-words" data-testid="replaces">
                      Replaces <span class="font-mono">m{{ replaced }}</span
                      >{{ replacedText(replaced) }}
                    </p>
                  }
                  <div class="flex flex-wrap gap-2">
                    <button
                      hlmBtn
                      size="sm"
                      type="button"
                      data-testid="accept-note"
                      [disabled]="busy()"
                      (click)="accept(n)"
                    >
                      @if (pending() === n.number) {
                        <hlm-spinner aria-hidden="true" />
                      }
                      Accept
                    </button>
                    <button
                      hlmBtn
                      variant="outline"
                      size="sm"
                      type="button"
                      data-testid="reject-note"
                      [disabled]="busy()"
                      (click)="remove(n.number)"
                    >
                      Reject
                    </button>
                  </div>
                </li>
              }
            </ul>
          </div>
        }

        @for (g of groups(); track g.key) {
          <div class="flex min-w-0 flex-col gap-1.5" [attr.data-group]="g.key">
            <h3 class="text-muted-foreground text-xs font-semibold">{{ g.title }}</h3>
            <ul class="flex flex-col divide-y rounded-lg border">
              @for (n of g.notes; track n.number) {
                <li
                  class="flex min-w-0 items-start justify-between gap-2 px-3 py-2"
                  [attr.data-note]="n.number"
                >
                  <div class="flex min-w-0 flex-col gap-0.5">
                    <p class="text-sm break-words">{{ n.text }}</p>
                    <p class="text-muted-foreground text-xs">
                      <span class="font-mono">{{ n.id }}</span> · used
                      <app-time [value]="n.usedAt" />
                      @if (n.conversationId) {
                        ·
                        <a
                          class="underline-offset-2 hover:underline"
                          [routerLink]="['/c', companyId(), 'conversations', n.conversationId]"
                          >where it was learned</a
                        >
                      }
                    </p>
                  </div>
                  <button
                    hlmBtn
                    variant="ghost"
                    size="icon-sm"
                    type="button"
                    class="text-muted-foreground hover:text-destructive shrink-0"
                    data-testid="delete-note"
                    [attr.aria-label]="'Delete ' + n.id"
                    [disabled]="busy()"
                    (click)="confirm.set({ kind: 'note', note: n })"
                  >
                    <ng-icon name="lucideTrash2" aria-hidden="true" />
                  </button>
                </li>
              }
            </ul>
          </div>
        } @empty {
          @if (proposals().length === 0 && m.inEffect) {
            <p
              class="text-muted-foreground rounded-lg border border-dashed px-3 py-4 text-center text-xs"
              data-testid="memory-empty"
            >
              {{ emptyText() }}
            </p>
          }
        }

        @if (m.inEffect || m.notes.length > 0) {
          <p class="text-muted-foreground text-xs" data-testid="memory-usage">
            <span class="font-mono tabular-nums">{{ format(m.characters) }}</span> of
            <span class="font-mono tabular-nums">{{ format(m.maxCharacters) }}</span> characters
            @if (m.compactedAt) {
              · tidied up <app-time [value]="m.compactedAt" />
            }
          </p>
        }
      } @else if (loadError()) {
        <p class="text-destructive text-sm" role="alert" data-testid="memory-load-error">
          {{ loadError() }}
          <button hlmBtn variant="link" size="sm" type="button" (click)="res.reload()">
            Try again
          </button>
        </p>
      } @else {
        <app-list-skeleton [rows]="2" />
      }
    </section>

    <hlm-alert-dialog [state]="confirm() ? 'open' : 'closed'" (closed)="confirm.set(null)">
      <hlm-alert-dialog-content *hlmAlertDialogPortal="let ctx">
        <hlm-alert-dialog-header>
          @if (confirm()?.kind === 'clear') {
            <h2 hlmAlertDialogTitle>Delete every note?</h2>
            <p hlmAlertDialogDescription>
              {{ agentName() }} forgets all {{ memory()?.notes?.length }} notes{{
                owner().kind === 'trigger' ? ', the proposals among them' : ''
              }}. Memory stays {{ memory()?.inEffect ? 'on' : 'off' }}. You can’t undo this.
            </p>
          } @else {
            <h2 hlmAlertDialogTitle>Delete {{ confirmed()?.id }}?</h2>
            <p hlmAlertDialogDescription>
              {{ agentName() }} forgets “{{ confirmed()?.text }}”. You can’t undo this.
            </p>
          }
        </hlm-alert-dialog-header>
        <hlm-alert-dialog-footer>
          <button hlmAlertDialogCancel [disabled]="busy()">Cancel</button>
          <button
            hlmAlertDialogAction
            variant="destructive"
            data-testid="confirm-memory-delete"
            [disabled]="busy()"
            (click)="confirmDelete()"
          >
            @if (busy()) {
              <hlm-spinner aria-hidden="true" />
            }
            {{ confirm()?.kind === 'clear' ? 'Delete all' : 'Delete note' }}
          </button>
        </hlm-alert-dialog-footer>
      </hlm-alert-dialog-content>
    </hlm-alert-dialog>
  `,
})
export class MemoryNotesPanel {
  private readonly api = inject(Api);

  readonly companyId = input.required<string>();
  readonly owner = input.required<MemoryOwner>();
  /** The agent, for the names of what notes are about; null when the reader cannot read it. */
  readonly agent = input<MemoryAgent | null>(null);
  /** Bumped by the page to read the memory again. */
  readonly revision = input(0);
  /** The memory after the reader deleted, rejected or accepted a note. */
  readonly changed = output<Memory>();

  protected readonly res = resource({
    params: () => ({ companyId: this.companyId(), owner: this.owner(), revision: this.revision() }),
    loader: ({ params: { companyId, owner } }) =>
      owner.kind === 'person'
        ? this.api.invoke(getMyAgentMemory, { companyId, agentId: owner.agentId })
        : this.api.invoke(getTriggerMemory, { companyId, triggerId: owner.triggerId }),
  });
  /** The memory as last read, or as the last action answered it. */
  protected readonly memory = linkedSignal<Memory | null>(() =>
    this.res.hasValue() ? (this.res.value() ?? null) : null,
  );
  protected readonly loadError = computed(() =>
    this.res.error() ? problemMessage(this.res.error()) : null,
  );

  protected readonly busy = signal(false);
  /** The note an accept is waiting on. */
  protected readonly pending = signal<number | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly confirm = signal<
    { kind: 'clear' } | { kind: 'note'; note: MemoryNote } | null
  >(null);
  protected readonly confirmed = computed(() => {
    const c = this.confirm();
    return c?.kind === 'note' ? c.note : null;
  });

  protected readonly agentName = computed(() => this.agent()?.name ?? 'The agent');
  private readonly person = computed(() => this.owner().kind === 'person');

  protected readonly intro = computed(() =>
    this.person()
      ? `What ${this.agentName()} noted in your conversations, in its own words. A conversation gets only the notes it needs: a few at its start, a system’s when it uses that system, others when it searches. Only you see them.`
      : `What ${this.agentName()} noted in the trigger’s conversations, in its own words. A note waits for the trigger’s team before later conversations read it; one not reviewed in 30 days goes.`,
  );
  protected readonly offNote = computed(() => {
    const m = this.memory();
    if (!m || m.inEffect) return null;
    if (this.agent()?.memoryAllowed === false) {
      return `${this.agentName()}’s editor does not allow memory: these notes are kept, but no conversation reads or writes them.`;
    }
    return this.person()
      ? 'Memory is off: your conversations neither read nor write these notes. Turn it on in your settings above.'
      : 'Memory is off for this trigger: its conversations neither read nor write notes. Its author turns it on in the trigger’s settings.';
  });
  protected readonly emptyText = computed(
    () => `No notes yet. ${this.agentName()} writes one when it learns something worth keeping.`,
  );

  protected readonly proposals = computed(() =>
    (this.memory()?.notes ?? []).filter((n) => n.state === 'PROPOSED'),
  );
  protected readonly groups = computed(() =>
    memoryGroups(this.memory()?.notes ?? [], this.agent(), this.person() ? 'About you' : 'General'),
  );

  protected about(note: MemoryNote): string {
    if (note.about === 'GENERAL') return this.person() ? 'you' : 'the trigger';
    return aboutName(note, this.agent());
  }

  /** What a proposal replaces, after its id: the accepted note's text, or that it is gone. */
  protected replacedText(number: number): string {
    const text = this.memory()?.notes.find(
      (n) => n.number === number && n.state === 'ACTIVE',
    )?.text;
    return text ? `: “${text}”` : ', which is gone';
  }

  protected format(n: number): string {
    return numbers.format(n);
  }

  protected async confirmDelete(): Promise<void> {
    const c = this.confirm();
    if (!c) return;
    if (c.kind === 'clear') await this.clear();
    else await this.remove(c.note.number);
    this.confirm.set(null);
  }

  protected async accept(note: MemoryNote): Promise<void> {
    const owner = this.owner();
    if (owner.kind !== 'trigger') return;
    this.pending.set(note.number);
    await this.act(() =>
      this.api.invoke(acceptTriggerMemoryNote, {
        companyId: this.companyId(),
        triggerId: owner.triggerId,
        noteNumber: note.number,
      }),
    );
    this.pending.set(null);
  }

  /** Deletes a note, or rejects a proposal: the same call. */
  protected async remove(noteNumber: number): Promise<void> {
    const owner = this.owner();
    const companyId = this.companyId();
    await this.act(() =>
      owner.kind === 'person'
        ? this.api.invoke(deleteMyAgentMemoryNote, {
            companyId,
            agentId: owner.agentId,
            noteNumber,
          })
        : this.api.invoke(deleteTriggerMemoryNote, {
            companyId,
            triggerId: owner.triggerId,
            noteNumber,
          }),
    );
  }

  private async clear(): Promise<void> {
    const owner = this.owner();
    const companyId = this.companyId();
    await this.act(() =>
      owner.kind === 'person'
        ? this.api.invoke(clearMyAgentMemory, { companyId, agentId: owner.agentId })
        : this.api.invoke(clearTriggerMemory, { companyId, triggerId: owner.triggerId }),
    );
  }

  private async act(fn: () => Promise<Memory>): Promise<void> {
    if (this.busy()) return;
    this.busy.set(true);
    this.error.set(null);
    try {
      const memory = await fn();
      this.memory.set(memory);
      this.changed.emit(memory);
    } catch (error) {
      this.error.set(problemMessage(error));
      this.res.reload();
    } finally {
      this.busy.set(false);
    }
  }
}
