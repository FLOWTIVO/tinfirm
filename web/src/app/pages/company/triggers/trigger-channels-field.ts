import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  input,
  model,
  resource,
} from '@angular/core';
import { Api } from '../../../api/generated/api';
import { listChatChannels } from '../../../api/generated/functions';
import type { ChatChannel } from '../../../api/generated/models';
import { problemMessage } from '../../../api/problem';
import { CurrentCompany } from '../../../core/current-company';
import { ListSkeleton } from '../../../shared/list-skeleton';
import { type DraftChannel, MAX_CHANNELS } from './trigger-draft';

const KIND_LABELS: Record<ChatChannel['kind'], string> = {
  PUBLIC: 'public',
  PRIVATE: 'private',
  SHARED: 'shared with another organisation',
};

/**
 * A channel trigger's channels (package 5b, CONVENTIONS §15.17): the channels the chat's bot is in
 * (`listChatChannels`, private ones only to their members), each picked or not, and which of the
 * picked ones it answers by default, when a mention names no agent's handle. A channel the bot
 * cannot use, or whose default another trigger already is, says so and offers nothing.
 */
@Component({
  selector: 'app-trigger-channels-field',
  imports: [ListSkeleton],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'flex min-w-0 flex-col gap-1.5', 'data-testid': 'trigger-channels' },
  template: `
    <span class="text-sm font-medium" id="trigger-channels-label">Channels</span>
    <p class="text-muted-foreground text-xs">
      A mention of the agent’s handle in one of them starts a conversation in its thread; in a
      default channel any mention of the bot does. You must be a member of each.
    </p>
    @if (channels.isLoading() && !channels.hasValue()) {
      <app-list-skeleton [rows]="2" />
    } @else if (channels.error()) {
      <p class="text-destructive text-sm" role="alert">{{ error() }}</p>
    } @else if (list().length === 0) {
      <p class="text-muted-foreground text-sm" data-testid="no-channels">
        The bot is in no channel you can see. Invite it to a channel first.
      </p>
    } @else {
      <ul class="flex flex-col divide-y rounded-lg border" aria-labelledby="trigger-channels-label">
        @for (c of list(); track c.channelId) {
          @let picked = pickedOf(c.channelId);
          <li
            class="flex min-w-0 flex-col gap-1.5 px-3 py-2.5 text-sm sm:flex-row sm:items-center sm:gap-3"
            [attr.data-channel]="c.channelId"
          >
            <label class="flex min-w-0 flex-1 items-start gap-2">
              <input
                type="checkbox"
                class="accent-primary mt-0.5 size-4 shrink-0"
                [checked]="!!picked"
                [disabled]="!c.available && !picked"
                (change)="toggle(c, $any($event.target).checked)"
              />
              <span class="flex min-w-0 flex-col">
                <span class="font-medium break-words">#{{ c.name ?? c.channelId }}</span>
                <span class="text-muted-foreground text-xs">
                  {{ kindLabels[c.kind] }}
                  @if (!c.available) {
                    · the bot cannot use it{{ c.archived ? ': archived' : '' }}
                  }
                  @for (t of c.triggers; track t.triggerId) {
                    · {{ t.name }}{{ t.default ? ' (its default)' : '' }}
                  }
                </span>
              </span>
            </label>
            @if (picked) {
              <label class="flex shrink-0 items-center gap-1.5 text-xs">
                <input
                  type="checkbox"
                  class="accent-primary size-4"
                  [checked]="picked.default"
                  [disabled]="c.hasDefault && !picked.default"
                  (change)="setDefault(c.channelId, $any($event.target).checked)"
                />
                Its default
              </label>
            }
          </li>
        }
      </ul>
    }
    @if (draft().length >= max) {
      <p class="text-muted-foreground text-xs">At most {{ max }} channels.</p>
    }
  `,
})
export class TriggerChannelsField {
  private readonly api = inject(Api);
  private readonly current = inject(CurrentCompany);

  readonly systemId = input.required<string>();
  /** An admin edits for the author: the channels the author sees. */
  readonly authorPrincipalId = input<string | null>(null);
  readonly draft = model.required<readonly DraftChannel[]>();

  protected readonly kindLabels = KIND_LABELS;
  protected readonly max = MAX_CHANNELS;

  protected readonly channels = resource({
    params: () => {
      const companyId = this.current.id();
      return companyId
        ? {
            companyId,
            systemId: this.systemId(),
            authorPrincipalId: this.authorPrincipalId() ?? undefined,
          }
        : undefined;
    },
    loader: ({ params }) => this.api.invoke(listChatChannels, params),
  });
  protected readonly list = computed(() =>
    this.channels.hasValue() ? (this.channels.value()?.items ?? []) : [],
  );
  protected readonly error = computed(() => problemMessage(this.channels.error()));

  protected pickedOf(channelId: string): DraftChannel | undefined {
    return this.draft().find((c) => c.channelId === channelId);
  }

  protected toggle(channel: ChatChannel, on: boolean): void {
    this.draft.update((list) =>
      on
        ? list.length >= MAX_CHANNELS
          ? list
          : [...list, { channelId: channel.channelId, name: channel.name ?? null, default: false }]
        : list.filter((c) => c.channelId !== channel.channelId),
    );
  }

  protected setDefault(channelId: string, on: boolean): void {
    this.draft.update((list) =>
      list.map((c) => (c.channelId === channelId ? { ...c, default: on } : c)),
    );
  }
}
