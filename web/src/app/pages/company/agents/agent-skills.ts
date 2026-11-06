import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { NgIcon, provideIcons } from '@ng-icons/core';
import {
  lucideArchive,
  lucideCircleArrowUp,
  lucideDownload,
  lucideLibraryBig,
  lucidePencil,
} from '@ng-icons/lucide';
import { HlmButton } from '@spartan-ng/helm/button';
import type { Agent, AgentSkill } from '../../../api/generated/models';
import { agentSkillUrl, zipName } from '../skills/skill-labels';

/** Whether the library has a newer version than the one the agent pins, which its editors may take. */
export function hasUpdate(
  skill: Pick<AgentSkill, 'version' | 'latestVersion' | 'deleted'>,
): boolean {
  return !skill.deleted && skill.latestVersion > skill.version;
}

/**
 * An agent's skills (ADR-0064; the skills contract §5.2, §5.4), on its page: each by name, with
 * its pinned version's description. Whoever sees the agent downloads the version it pins, as they
 * read its instructions. Its editors see the updates the library has ("Update to v5 available")
 * and a skill deleted from the library, which the agent keeps; they change the pins in the editor.
 */
@Component({
  selector: 'app-agent-skills',
  imports: [RouterLink, NgIcon, HlmButton],
  providers: [
    provideIcons({
      lucideArchive,
      lucideCircleArrowUp,
      lucideDownload,
      lucideLibraryBig,
      lucidePencil,
    }),
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {
    class: 'bg-card flex min-w-0 flex-col gap-3 rounded-xl border p-4 sm:p-5',
    'data-testid': 'agent-skills',
  },
  template: `
    @let a = agent();
    <div class="flex flex-wrap items-start justify-between gap-3">
      <div class="flex min-w-0 flex-col gap-0.5">
        <h2 class="text-base font-bold" id="agent-skills-title">Skills</h2>
        <p class="text-muted-foreground text-xs text-pretty">
          Know-how from the company’s library, each at the version it pins. It reads a skill when a
          task fits; download one to read what it says.
        </p>
      </div>
      @if (a.editableByMe) {
        <a
          hlmBtn
          variant="ghost"
          size="sm"
          data-testid="edit-skills"
          [routerLink]="['/c', companyId(), 'agents', a.id, 'edit']"
          [queryParams]="{ tab: 'skills' }"
        >
          <ng-icon name="lucidePencil" aria-hidden="true" />
          Edit skills
        </a>
      }
    </div>
    @if (a.skills.length > 0) {
      <ul class="flex flex-col divide-y rounded-lg border" aria-labelledby="agent-skills-title">
        @for (s of a.skills; track s.skillId) {
          <li
            class="flex min-w-0 items-start justify-between gap-3 px-3 py-2.5"
            [attr.data-skill]="s.skillId"
          >
            <div class="flex min-w-0 flex-col gap-1">
              <div class="flex flex-wrap items-center gap-x-2 gap-y-1">
                @if (a.editableByMe && !s.deleted) {
                  <a
                    class="focus-visible:ring-ring/50 rounded-sm font-mono text-sm font-semibold underline-offset-2 outline-none hover:underline focus-visible:ring-3"
                    data-testid="skill-name"
                    [routerLink]="['/c', companyId(), 'skills', s.skillId]"
                    >{{ s.name }}</a
                  >
                } @else {
                  <span class="font-mono text-sm font-semibold" data-testid="skill-name">{{
                    s.name
                  }}</span>
                }
                <span
                  class="bg-secondary text-muted-foreground inline-flex h-5 items-center rounded-full px-2 font-mono text-xs font-semibold"
                  data-testid="skill-pinned"
                  [attr.title]="'It pins version ' + s.version"
                  >v{{ s.version }}</span
                >
                @if (s.deleted) {
                  <span
                    class="bg-muted text-muted-foreground inline-flex h-5 items-center gap-1 rounded-full border px-2 text-xs font-semibold"
                    data-testid="skill-deleted"
                    title="Deleted from the library: the agent keeps the version it pins, which no one can move"
                  >
                    <ng-icon name="lucideArchive" size="12" aria-hidden="true" />
                    Deleted from the library
                  </span>
                } @else if (a.editableByMe && update(s)) {
                  <a
                    class="bg-primary/10 text-primary focus-visible:ring-ring/50 inline-flex h-5 items-center gap-1 rounded-full px-2 text-xs font-semibold outline-none hover:underline focus-visible:ring-3"
                    data-testid="skill-update"
                    [routerLink]="['/c', companyId(), 'agents', a.id, 'edit']"
                    [queryParams]="{ tab: 'skills' }"
                    [attr.aria-label]="
                      'Update to v' + s.latestVersion + ' available: edit the skills of ' + a.name
                    "
                  >
                    <ng-icon name="lucideCircleArrowUp" size="12" aria-hidden="true" />
                    Update to v{{ s.latestVersion }} available
                  </a>
                }
              </div>
              <p class="text-muted-foreground text-sm text-pretty">{{ s.description }}</p>
            </div>
            <a
              hlmBtn
              variant="ghost"
              size="icon-sm"
              class="text-muted-foreground shrink-0"
              data-testid="skill-download"
              title="Download"
              [href]="url(s)"
              [attr.download]="zip(s)"
              [attr.aria-label]="'Download ' + s.name + ', version ' + s.version"
            >
              <ng-icon name="lucideDownload" aria-hidden="true" />
            </a>
          </li>
        }
      </ul>
      @if (a.editableByMe && updates() > 0) {
        <p class="text-muted-foreground text-xs" data-testid="skill-updates-note">
          A new version reaches {{ a.name }} only when you move its pin: everyone gets it at their
          next turn.
        </p>
      }
    } @else {
      <p class="text-muted-foreground flex items-center gap-2 text-sm" data-testid="no-skills">
        <ng-icon name="lucideLibraryBig" class="shrink-0" aria-hidden="true" />
        @if (a.editableByMe) {
          None yet. Add skills from the company’s library in Edit skills.
        } @else {
          None: it works from its instructions alone.
        }
      </p>
    }
  `,
})
export class AgentSkills {
  readonly companyId = input.required<string>();
  readonly agent = input.required<Pick<Agent, 'id' | 'name' | 'skills' | 'editableByMe'>>();

  protected readonly updates = computed(() => this.agent().skills.filter(hasUpdate).length);

  protected update(skill: AgentSkill): boolean {
    return hasUpdate(skill);
  }

  protected url(skill: AgentSkill): string {
    return agentSkillUrl(this.companyId(), this.agent().id, skill.skillId);
  }

  protected zip(skill: AgentSkill): string {
    return zipName(skill.name, skill.version);
  }
}
