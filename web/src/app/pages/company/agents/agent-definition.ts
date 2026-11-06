import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { NgIcon, provideIcons } from '@ng-icons/core';
import { lucideFolderGit2, lucideGlobe, lucidePlug } from '@ng-icons/lucide';
import type { Agent, AgentTool } from '../../../api/generated/models';
import { ACCESS_LABELS, SYSTEM_KIND_LABELS, TOOL_EFFECT_LABELS } from '../../../shared/labels';
import { effectiveMode } from './agent-draft';

/** A tool's mode in words, as it stands now (I19: "Allowed" for a read asks once it writes). */
export function toolModeText(tool: AgentTool): string {
  if (!tool.offered) return 'No longer offered';
  const mode = effectiveMode({
    mode: tool.mode,
    effect: tool.effect ?? null,
    chosenForEffect: tool.chosenForEffect,
  });
  return mode === 'ALLOW' ? 'Allowed' : 'Needs your approval';
}

/**
 * What an agent may use (plan §4.3): its systems with each tool's effect and mode ("Allowed" or
 * "Needs your approval"), its repositories with their access, and the network hosts its runs may
 * reach.
 */
@Component({
  selector: 'app-agent-definition',
  imports: [NgIcon],
  providers: [provideIcons({ lucideFolderGit2, lucideGlobe, lucidePlug })],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'flex flex-col gap-5' },
  template: `
    @let a = agent();
    <section
      class="flex flex-col gap-2"
      aria-labelledby="definition-systems"
      data-testid="definition-systems"
    >
      <h3 class="flex items-center gap-2 text-sm font-semibold" id="definition-systems">
        <ng-icon name="lucidePlug" size="16" class="text-muted-foreground" aria-hidden="true" />
        Systems and tools
      </h3>
      @for (s of a.systems; track s.systemId) {
        <div class="flex min-w-0 flex-col rounded-lg border" [attr.data-system]="s.systemId">
          <div class="flex flex-wrap items-baseline gap-x-2 px-3 py-2">
            <span class="text-sm font-semibold">{{ s.name }}</span>
            <span class="text-muted-foreground text-xs">{{ kindLabels[s.kind] }}</span>
          </div>
          @if (s.tools.length > 0) {
            <ul class="flex flex-col divide-y border-t">
              @for (t of s.tools; track t.name) {
                @let mode = modeText(t);
                <li
                  class="flex min-w-0 items-center justify-between gap-3 px-3 py-2"
                  [attr.data-tool]="t.name"
                >
                  <span class="flex min-w-0 flex-col">
                    <span class="truncate text-sm" [class.text-muted-foreground]="!t.offered">{{
                      t.title || t.name
                    }}</span>
                    <span class="text-muted-foreground text-xs">
                      @if (t.effect) {
                        {{ effectLabels[t.effect] }}
                      } @else {
                        Its system no longer serves it
                      }
                    </span>
                  </span>
                  <span
                    class="inline-flex h-6 shrink-0 items-center rounded-full border px-2.5 text-xs font-semibold"
                    [class]="
                      mode === 'Allowed'
                        ? 'bg-primary/10 text-primary border-transparent'
                        : 'bg-card'
                    "
                    data-mode
                    >{{ mode }}</span
                  >
                </li>
              }
            </ul>
          } @else {
            <p class="text-muted-foreground border-t px-3 py-2 text-xs">
              No tools: its repositories only.
            </p>
          }
        </div>
      } @empty {
        <p class="text-muted-foreground text-sm">It uses no system.</p>
      }
    </section>

    <section
      class="flex flex-col gap-2"
      aria-labelledby="definition-repositories"
      data-testid="definition-repositories"
    >
      <h3 class="flex items-center gap-2 text-sm font-semibold" id="definition-repositories">
        <ng-icon
          name="lucideFolderGit2"
          size="16"
          class="text-muted-foreground"
          aria-hidden="true"
        />
        Repositories
      </h3>
      @if (a.repositories.length > 0) {
        <ul class="flex flex-col divide-y rounded-lg border">
          @for (r of a.repositories; track r.repositoryId) {
            <li
              class="flex min-w-0 items-center justify-between gap-3 px-3 py-2"
              [attr.data-repository]="r.repositoryId"
            >
              <span class="truncate font-mono text-xs">{{ r.name }}</span>
              <span class="text-muted-foreground shrink-0 text-xs" data-access>{{
                accessLabels[r.access]
              }}</span>
            </li>
          }
        </ul>
      } @else {
        <p class="text-muted-foreground text-sm">None: its workspace starts empty.</p>
      }
    </section>

    <section
      class="flex flex-col gap-2"
      aria-labelledby="definition-hosts"
      data-testid="definition-hosts"
    >
      <h3 class="flex items-center gap-2 text-sm font-semibold" id="definition-hosts">
        <ng-icon name="lucideGlobe" size="16" class="text-muted-foreground" aria-hidden="true" />
        Network hosts
      </h3>
      @if (a.hosts.length > 0) {
        <ul class="flex flex-col divide-y rounded-lg border">
          @for (h of a.hosts; track h.host) {
            <li class="flex min-w-0 flex-col px-3 py-2" [attr.data-host]="h.host">
              <code class="font-mono text-xs font-semibold break-all">{{ h.host }}</code>
              @if (h.reason) {
                <span class="text-muted-foreground text-xs text-pretty">{{ h.reason }}</span>
              }
            </li>
          }
        </ul>
      } @else {
        <p class="text-muted-foreground text-sm">
          None beyond its systems. A run asks when it needs another host.
        </p>
      }
    </section>
  `,
})
export class AgentDefinition {
  readonly agent = input.required<Pick<Agent, 'systems' | 'repositories' | 'hosts'>>();

  protected readonly kindLabels = SYSTEM_KIND_LABELS;
  protected readonly effectLabels = TOOL_EFFECT_LABELS;
  protected readonly accessLabels = ACCESS_LABELS;

  protected modeText(tool: AgentTool): string {
    return toolModeText(tool);
  }
}
