import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import { NgIcon, provideIcons } from '@ng-icons/core';
import { lucideShieldPlus } from '@ng-icons/lucide';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmSpinner } from '@spartan-ng/helm/spinner';
import { Api } from '../../../api/generated/api';
import { acceptAgentVersion } from '../../../api/generated/functions';
import type {
  Agent,
  AgentSettings,
  AgentWidenedRepository,
  AgentWidenedSkill,
  AgentWidenedTool,
  AgentWidening,
} from '../../../api/generated/models';
import { problemMessage } from '../../../api/problem';
import { ACCESS_LABELS, TOOL_MODE_LABELS } from '../../../shared/labels';
import { InstructionsDiff } from './instructions-diff';

/** The parts of an agent the card names things by. */
export type WideningAgent = Pick<Agent, 'id' | 'name' | 'systems' | 'repositories'>;

export interface WideningSection {
  readonly key: 'systems' | 'tools' | 'repositories' | 'hosts' | 'skills';
  readonly title: string;
  readonly items: readonly { readonly text: string; readonly mono?: boolean }[];
}

const WAS_TOOL: Record<NonNullable<AgentWidenedTool['was']>, string> = {
  ASK: 'it needed your approval',
  ALLOW_FOR_READS: 'it was allowed only while it read',
};

function toolText(tool: AgentWidenedTool, agent: WideningAgent): string {
  const system = agent.systems.find((s) => s.systemId === tool.systemId);
  const known = system?.tools.find((t) => t.name === tool.tool);
  const name = known?.title || tool.tool;
  const mode = TOOL_MODE_LABELS[tool.mode];
  const was = tool.was ? `, where ${WAS_TOOL[tool.was]}` : ' (new)';
  return `${system?.name ?? 'A system'} · ${name}: ${mode}${was}`;
}

function repositoryText(repo: AgentWidenedRepository, agent: WideningAgent): string {
  const name =
    agent.repositories.find((r) => r.repositoryId === repo.repositoryId)?.name ?? 'A repository';
  const access = ACCESS_LABELS[repo.access];
  return repo.was
    ? `${name}: ${access}, was ${ACCESS_LABELS[repo.was]}`
    : `${name}: ${access} (new)`;
}

function skillText(skill: AgentWidenedSkill): string {
  return skill.was == null
    ? `${skill.name}: version ${skill.version} (new)`
    : `${skill.name}: version ${skill.version}, was version ${skill.was}`;
}

/**
 * The skills the current version adds or pins at another version (ADR-0103: they widen where
 * the agent can act); null when none. Needs no agent: a skill names itself.
 */
export function skillsSection(widening: AgentWidening): WideningSection | null {
  const skills = widening.skills ?? [];
  return skills.length
    ? { key: 'skills', title: 'Skills', items: skills.map((s) => ({ text: skillText(s) })) }
    : null;
}

/**
 * Whether the current version changes only what the agent is told (its instructions or skills),
 * not what it may reach.
 */
export function changesOnlyContent(widening: AgentWidening): boolean {
  return (
    !widening.systems.length &&
    !widening.tools.length &&
    !widening.repositories.length &&
    !widening.hosts.length
  );
}

/**
 * What the agent's current version adds over the one the reader accepted (PLATFORM.md §6: widening
 * is accepted, narrowing is not), by kind: systems, tools with their mode and what they were,
 * repositories with their access and what it was, network hosts, and the skills added or moved
 * (the instructions are a diff of their own, {@link InstructionsDiff}).
 */
export function wideningSections(widening: AgentWidening, agent: WideningAgent): WideningSection[] {
  const sections: WideningSection[] = [
    {
      key: 'systems',
      title: 'Systems',
      items: widening.systems.map((id) => ({
        text: agent.systems.find((s) => s.systemId === id)?.name ?? 'A system',
      })),
    },
    {
      key: 'tools',
      title: 'Tools',
      items: widening.tools.map((t) => ({ text: toolText(t, agent) })),
    },
    {
      key: 'repositories',
      title: 'Repositories',
      items: widening.repositories.map((r) => ({ text: repositoryText(r, agent) })),
    },
    {
      key: 'hosts',
      title: 'Network hosts',
      items: widening.hosts.map((host) => ({ text: host, mono: true })),
    },
  ];
  const skills = skillsSection(widening);
  if (skills) sections.push(skills);
  return sections.filter((s) => s.items.length > 0);
}

let nextId = 0;

/**
 * The widening card (plan §4.3), on the agent page, the settings page and the new conversation
 * page while the agent's current version widens the one the reader accepted. Orange, because only
 * the reader can accept: it lists what the new version adds, then "Accept version 6", which names
 * the version shown so that nothing is accepted unseen (`acceptAgentVersion`).
 */
@Component({
  selector: 'app-widening-card',
  imports: [NgIcon, HlmButton, HlmSpinner, InstructionsDiff],
  providers: [provideIcons({ lucideShieldPlus })],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'block' },
  template: `
    <section
      class="bg-card border-human flex flex-col gap-3 rounded-xl border px-3.5 py-3 text-sm"
      data-testid="widening-card"
      [attr.aria-labelledby]="titleId"
    >
      <div class="flex items-start gap-2.5">
        <ng-icon name="lucideShieldPlus" class="text-human mt-0.5 shrink-0" aria-hidden="true" />
        <div class="flex min-w-0 flex-col gap-0.5">
          <h3 class="text-human font-semibold text-pretty" [id]="titleId">
            {{ agent().name }} has a new version to accept
          </h3>
          <p class="text-muted-foreground text-pretty">
            @if (contentOnly()) {
              Version {{ widening().currentVersion }} changes what it is told: its instructions or
              skills. Your conversations keep those of version
              {{ widening().acceptedVersion }} until you accept it.
            } @else {
              Version {{ widening().currentVersion }} may do more than version
              {{ widening().acceptedVersion }}, which your conversations keep until you accept it.
            }
          </p>
        </div>
      </div>

      <dl class="flex flex-col gap-2.5">
        @for (section of sections(); track section.key) {
          <div class="flex flex-col gap-1" [attr.data-section]="section.key">
            <dt class="text-muted-foreground text-xs font-semibold">{{ section.title }}</dt>
            @for (item of section.items; track $index) {
              <dd class="break-words" [class.font-mono]="item.mono" [class.text-xs]="item.mono">
                {{ item.text }}
              </dd>
            }
          </div>
        }
        @if (widening().instructions; as instructions) {
          <div class="flex flex-col gap-1" data-section="instructions">
            <dt class="text-muted-foreground text-xs font-semibold">Instructions</dt>
            <dd>
              <app-instructions-diff
                [accepted]="instructions.accepted"
                [current]="instructions.current"
              />
            </dd>
          </div>
        }
      </dl>

      <div class="flex flex-col gap-1.5 sm:flex-row sm:items-center sm:gap-3">
        <button
          hlmBtn
          size="sm"
          type="button"
          class="self-start"
          data-testid="accept-version"
          [disabled]="busy()"
          (click)="accept()"
        >
          @if (busy()) {
            <hlm-spinner aria-hidden="true" />
          }
          Accept version {{ widening().currentVersion }}
        </button>
        <p class="text-muted-foreground text-xs">
          What it no longer has applies already; only this waits.
        </p>
      </div>

      @if (error(); as message) {
        <p class="text-destructive" role="alert" data-testid="widening-error">{{ message }}</p>
      }
    </section>
  `,
})
export class WideningCard {
  private readonly api = inject(Api);

  readonly companyId = input.required<string>();
  readonly agent = input.required<WideningAgent>();
  readonly widening = input.required<AgentWidening>();

  /** The reader's settings of the agent, as they are after accepting. */
  readonly accepted = output<AgentSettings>();

  protected readonly titleId = `widening-${nextId++}`;
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly sections = computed(() => wideningSections(this.widening(), this.agent()));
  protected readonly contentOnly = computed(() => changesOnlyContent(this.widening()));

  protected async accept(): Promise<void> {
    this.busy.set(true);
    this.error.set(null);
    try {
      const settings = await this.api.invoke(acceptAgentVersion, {
        companyId: this.companyId(),
        agentId: this.agent().id,
        body: { version: this.widening().currentVersion },
      });
      this.accepted.emit(settings);
    } catch (error) {
      this.error.set(problemMessage(error));
    } finally {
      this.busy.set(false);
    }
  }
}
