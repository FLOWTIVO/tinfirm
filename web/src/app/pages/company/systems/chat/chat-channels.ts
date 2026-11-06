import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  output,
  resource,
  signal,
  untracked,
} from '@angular/core';
import { RouterLink } from '@angular/router';
import { NgIcon, provideIcons } from '@ng-icons/core';
import { lucideHash, lucideLock, lucideSearch, lucideZap } from '@ng-icons/lucide';
import { HlmInput } from '@spartan-ng/helm/input';
import { HlmSkeleton } from '@spartan-ng/helm/skeleton';
import { Api } from '../../../../api/generated/api';
import { listChatChannels } from '../../../../api/generated/functions';
import type { ChatChannel, ExternalSystem } from '../../../../api/generated/models';
import { ErrorState } from '../../../../shared/error-state';
import { chatPlatform } from './chat-words';

/** How a channel reads in the list: its kind, and why no channel trigger may go there. */
export function channelState(channel: ChatChannel): { kind: string; note: string | null } {
  const kind =
    channel.kind === 'PRIVATE' ? 'Private' : channel.kind === 'SHARED' ? 'Shared' : 'Public';
  if (channel.archived) return { kind, note: 'Archived: no trigger answers here' };
  if (channel.kind === 'SHARED') {
    return { kind, note: 'Shared with another organisation: no trigger answers here' };
  }
  if (!channel.available) return { kind, note: 'Unavailable: no trigger answers here' };
  if (!channel.triggers.length) return { kind, note: 'No trigger yet: a mention gets a hint' };
  return { kind, note: null };
}

/**
 * The channels a chat system's bot is in (package 5b, §5.7; `GET /systems/{id}/chat/channels`):
 * public ones, and private ones the reader is in; each with its kind, whether a channel trigger may
 * go there, and the channel triggers the reader sees, the default marked. Triggers are made in the
 * trigger editor; this page only shows where they answer.
 */
@Component({
  selector: 'app-chat-channels',
  imports: [RouterLink, NgIcon, HlmInput, HlmSkeleton, ErrorState],
  providers: [provideIcons({ lucideHash, lucideLock, lucideSearch, lucideZap })],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'block' },
  template: `
    <section
      class="bg-card overflow-hidden rounded-xl border"
      aria-labelledby="chat-channels-title"
      data-testid="chat-channels"
    >
      <div
        class="flex flex-col gap-3 px-4 py-4 sm:flex-row sm:items-start sm:justify-between sm:px-5 sm:pt-5"
      >
        <div class="flex min-w-0 flex-col gap-0.5">
          <h2 id="chat-channels-title" class="text-base font-bold">Channels</h2>
          <p class="text-muted-foreground text-sm text-pretty">
            Where the bot is in {{ platform() }}. A channel trigger answers mentions of the bot in
            its channels, with the accounts and budget its author chose. Invite the bot to a channel
            in {{ platform() }} to add it here.
          </p>
        </div>
        <label class="relative sm:w-56 sm:shrink-0">
          <span class="sr-only">Find a channel</span>
          <ng-icon
            name="lucideSearch"
            class="text-muted-foreground pointer-events-none absolute start-2.5 top-1/2 -translate-y-1/2"
            aria-hidden="true"
          />
          <input
            hlmInput
            type="search"
            class="w-full ps-8"
            placeholder="Find a channel"
            data-testid="channel-query"
            [value]="query()"
            (input)="onQuery($event)"
          />
        </label>
      </div>

      @if (channels.isLoading() && !channels.hasValue()) {
        <div class="flex flex-col gap-2 px-4 pb-4 sm:px-5" aria-busy="true">
          <span class="sr-only" role="status">Loading the channels…</span>
          <hlm-skeleton class="h-10 w-full" />
          <hlm-skeleton class="h-10 w-full" />
        </div>
      } @else if (channels.error() && !channels.hasValue()) {
        <div class="px-4 pb-4 sm:px-5">
          <app-error-state
            [error]="channels.error()"
            heading="Couldn't read the channels"
            (retry)="channels.reload()"
          />
        </div>
      } @else if (!items().length) {
        <p
          class="text-muted-foreground mx-4 mb-4 rounded-lg border border-dashed px-3 py-4 text-center text-sm text-pretty sm:mx-5 sm:mb-5"
          data-testid="channels-empty"
        >
          @if (query()) {
            No channel of the bot's matches "{{ query() }}".
          } @else if (!system().chat?.botSet) {
            The bot is in no channel: set the bot first.
          } @else {
            The bot is in no channel yet. Invite it to one in {{ platform() }}; until then it
            answers direct messages only.
          }
        </p>
      } @else {
        <ul aria-labelledby="chat-channels-title">
          @for (channel of items(); track channel.channelId) {
            @let state = stateOf(channel);
            <li
              class="flex flex-col gap-1.5 border-t px-4 py-3 sm:flex-row sm:items-start sm:gap-3 sm:px-5"
              data-testid="channel"
              [attr.data-channel]="channel.channelId"
              [attr.data-available]="channel.available && !channel.archived"
            >
              <div class="flex min-w-0 flex-1 flex-col gap-0.5">
                <span class="flex flex-wrap items-center gap-x-2 gap-y-1 text-sm">
                  <ng-icon
                    [name]="channel.kind === 'PRIVATE' ? 'lucideLock' : 'lucideHash'"
                    class="text-muted-foreground shrink-0"
                    aria-hidden="true"
                  />
                  <span class="font-semibold break-all">{{
                    channel.name ?? channel.channelId
                  }}</span>
                  <span class="text-muted-foreground text-xs">{{ state.kind }}</span>
                </span>
                @if (state.note) {
                  <span class="text-muted-foreground text-xs" data-testid="channel-note">{{
                    state.note
                  }}</span>
                }
              </div>
              @if (channel.triggers.length) {
                <ul
                  class="flex flex-wrap gap-1.5"
                  [attr.aria-label]="'Triggers in ' + (channel.name ?? channel.channelId)"
                >
                  @for (trigger of channel.triggers; track trigger.triggerId) {
                    <li>
                      <a
                        class="bg-card hover:bg-accent focus-visible:ring-ring/50 inline-flex h-7 items-center gap-1.5 rounded-full border px-2.5 text-xs font-semibold outline-none focus-visible:ring-3 max-sm:h-9"
                        data-testid="channel-trigger"
                        [routerLink]="['/c', companyId(), 'triggers', trigger.triggerId]"
                      >
                        <ng-icon name="lucideZap" aria-hidden="true" />
                        {{ trigger.name }}
                        @if (trigger.default) {
                          <span class="text-muted-foreground font-normal">· default</span>
                        }
                      </a>
                    </li>
                  }
                </ul>
              } @else if (channel.hasDefault) {
                <span class="text-muted-foreground text-xs"
                  >Has a default trigger you don't see</span
                >
              }
            </li>
          }
        </ul>
      }
    </section>
  `,
})
export class ChatChannels {
  private readonly api = inject(Api);

  readonly companyId = input.required<string>();
  readonly system = input.required<ExternalSystem>();
  /** How many channels the bot is in, once read without a query: the page's tab shows it. */
  readonly count = output<number>();

  protected readonly platform = computed(() => chatPlatform(this.system().kind));
  protected readonly query = signal('');
  private timer: ReturnType<typeof setTimeout> | undefined;

  /**
   * Read only once the system has a bot: the server reads Slack's list at most once a minute per
   * system, so a read before the bot would leave the list empty for that minute after it is set.
   */
  protected readonly channels = resource({
    params: () =>
      this.system().chat?.botSet
        ? {
            companyId: this.companyId(),
            systemId: this.system().id,
            query: this.query().trim() || undefined,
          }
        : undefined,
    loader: ({ params }) =>
      this.api.invoke(listChatChannels, {
        companyId: params.companyId,
        systemId: params.systemId,
        query: params.query,
      }),
  });
  protected readonly items = computed(() =>
    this.channels.hasValue() ? this.channels.value().items : [],
  );

  constructor() {
    effect(() => {
      if (!this.channels.hasValue() || this.query().trim()) return;
      const count = this.items().length;
      untracked(() => this.count.emit(count));
    });
  }

  protected stateOf(channel: ChatChannel) {
    return channelState(channel);
  }

  /** The platform is asked on every read, so the query waits for the typing to pause. */
  protected onQuery(event: Event): void {
    const value = (event.target as HTMLInputElement).value;
    clearTimeout(this.timer);
    this.timer = setTimeout(() => this.query.set(value), 300);
  }
}
