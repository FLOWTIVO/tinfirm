import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  input,
  output,
  resource,
} from '@angular/core';
import { NgIcon, provideIcons } from '@ng-icons/core';
import { lucideBellOff } from '@ng-icons/lucide';
import { Api } from '../../../../api/generated/api';
import { getInbox } from '../../../../api/generated/functions';
import type { ExternalSystem, InboxItem } from '../../../../api/generated/models';
import { Time } from '../../../../shared/time';
import { ChatSetup } from './chat-setup';

/** The admins' item of a Slack system whose events stopped (`CHAT_SUBSCRIPTION_SILENT`), if any. */
export function silentItem(items: readonly InboxItem[], systemId: string): InboxItem | undefined {
  return items.find(
    (item) => item.kind === 'CHAT_SUBSCRIPTION_SILENT' && item.chat?.systemId === systemId,
  );
}

/**
 * A chat system's overview, its page's first tab (package 5a; the web plan §5, Systems): whether
 * Slack still sends its events (the admins' `CHAT_SUBSCRIPTION_SILENT`, with the step that
 * re-enables them), and the platform's app with its URLs and Slack's secrets. The bot and the
 * channels it is in have tabs of their own (`app-chat-bot`, `app-chat-channels`).
 */
@Component({
  selector: 'app-chat-system',
  imports: [NgIcon, Time, ChatSetup],
  providers: [provideIcons({ lucideBellOff })],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'flex flex-col gap-5' },
  template: `
    @if (silent(); as item) {
      <div
        class="border-human bg-human-soft/40 flex items-start gap-3 rounded-xl border px-4 py-3 text-sm"
        role="alert"
        data-testid="chat-silent"
      >
        <ng-icon name="lucideBellOff" class="text-human mt-0.5 shrink-0" aria-hidden="true" />
        <div class="flex min-w-0 flex-col gap-1">
          <p class="font-semibold">
            Slack stopped sending events
            <span class="text-muted-foreground font-normal">
              · since <app-time [value]="item.since" />
            </span>
          </p>
          <p class="text-pretty">{{ item.chat?.text }}</p>
        </div>
      </div>
    }

    <app-chat-setup
      [companyId]="companyId()"
      [system]="system()"
      [canManage]="canManage()"
      (changed)="saved.emit($event)"
      (changeTransport)="changeTransport.emit()"
    />
  `,
})
export class ChatSystem {
  private readonly api = inject(Api);

  readonly companyId = input.required<string>();
  readonly system = input.required<ExternalSystem>();
  readonly canManage = input(false);
  /** A secret was stored: the system as the server answered. */
  readonly saved = output<ExternalSystem>();
  readonly changeTransport = output<void>();

  /** The admins' inbox holds a silent subscription's item; only Slack's Events API goes silent. */
  private readonly inbox = resource({
    params: () =>
      this.canManage() &&
      this.system().kind === 'SLACK' &&
      this.system().chat?.transport !== 'SOCKET_MODE'
        ? this.companyId()
        : undefined,
    loader: ({ params: companyId }) => this.api.invoke(getInbox, { companyId }),
  });
  protected readonly silent = computed(() =>
    this.inbox.hasValue() ? silentItem(this.inbox.value().items, this.system().id) : undefined,
  );
}
