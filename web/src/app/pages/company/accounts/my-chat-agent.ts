import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  input,
  linkedSignal,
  resource,
  signal,
} from '@angular/core';
import { HlmNativeSelectImports } from '@spartan-ng/helm/native-select';
import { HlmSpinner } from '@spartan-ng/helm/spinner';
import { Api } from '../../../api/generated/api';
import {
  getMyChatPreference,
  listAgents,
  setMyChatPreference,
} from '../../../api/generated/functions';
import { Notify } from '../../../core/notify';

/** The select's value for "no default": the bot then shows a picker. */
const NONE = '';

/**
 * The agent a member's direct messages go to without a handle (package 5, §5.6; `GET`, `PUT
 * /me/chat`): one of the agents they use, or none, and the bot asks each time with a picker. A
 * leading `@handle` always names the agent instead.
 */
@Component({
  selector: 'app-my-chat-agent',
  imports: [HlmNativeSelectImports, HlmSpinner],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'block' },
  template: `
    <div
      class="bg-card flex flex-col gap-2 rounded-xl border p-4 sm:flex-row sm:items-center sm:gap-4"
      data-testid="chat-agent"
    >
      <div class="flex min-w-0 flex-1 flex-col gap-0.5">
        <label class="text-sm font-semibold" for="chat-default-agent">
          Your direct messages go to
        </label>
        <p class="text-muted-foreground text-xs text-pretty">
          When a message names no agent. Start one with &#64;handle to ask another.
        </p>
      </div>
      <div class="flex items-center gap-2 sm:w-64 sm:shrink-0">
        @if (loading()) {
          <hlm-spinner aria-hidden="true" />
          <span class="sr-only" role="status">Loading your agents…</span>
        } @else {
          <hlm-native-select
            class="w-full"
            selectId="chat-default-agent"
            [value]="selected()"
            [disabled]="saving()"
            (valueChange)="choose($event)"
          >
            <option [value]="none">Ask me each time</option>
            @for (agent of agents(); track agent.id) {
              <option [value]="agent.id">{{ agent.name }} (&#64;{{ agent.handle }})</option>
            }
          </hlm-native-select>
        }
      </div>
    </div>
  `,
})
export class MyChatAgent {
  private readonly api = inject(Api);
  private readonly notify = inject(Notify);

  readonly companyId = input.required<string>();

  protected readonly none = NONE;
  private readonly preference = resource({
    params: () => this.companyId(),
    loader: ({ params: companyId }) => this.api.invoke(getMyChatPreference, { companyId }),
  });
  private readonly agentsRes = resource({
    params: () => this.companyId(),
    loader: ({ params: companyId }) =>
      this.api.invoke(listAgents, { companyId, usable: true, limit: 200 }),
  });
  protected readonly agents = computed(() =>
    this.agentsRes.hasValue() ? this.agentsRes.value().items : [],
  );
  protected readonly loading = computed(
    () => !this.preference.hasValue() || !this.agentsRes.hasValue(),
  );
  /** The saved default, kept only while the reader still uses that agent (else none counts). */
  protected readonly selected = linkedSignal(() => {
    const id = this.preference.hasValue() ? this.preference.value().defaultAgentId : null;
    return id && this.agents().some((agent) => agent.id === id) ? id : NONE;
  });
  protected readonly saving = signal(false);

  protected async choose(value: string | null | undefined): Promise<void> {
    const before = this.selected();
    const next = value ?? NONE;
    if (next === before || this.saving()) return;
    this.selected.set(next);
    this.saving.set(true);
    try {
      await this.api.invoke(setMyChatPreference, {
        companyId: this.companyId(),
        body: { defaultAgentId: next || null },
      });
      const agent = this.agents().find((a) => a.id === next);
      this.notify.success(
        agent ? `Your direct messages go to ${agent.name}` : 'The bot asks which agent each time',
      );
    } catch (error) {
      this.selected.set(before);
      this.notify.error('Couldn’t change it', error);
    } finally {
      this.saving.set(false);
    }
  }
}
