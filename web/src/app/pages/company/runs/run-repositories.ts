import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { NgIcon, provideIcons } from '@ng-icons/core';
import { lucideFolderGit2 } from '@ng-icons/lucide';
import type { RunRepository } from '../../../api/generated/models';
import { commitCount, shortSha } from '../../../shared/run-status';

/** What happened in one repository of a run, in a few words. */
export function gitResult(repo: RunRepository, active: boolean): string {
  const commits = repo.commits ?? null;
  if (repo.pushed === true) return commits !== null ? `Pushed ${commitCount(commits)}` : 'Pushed';
  if (repo.pushed === false) {
    if (!commits) return 'No changes';
    return repo.readOnly
      ? `${commitCount(commits)}, not pushed (read-only)`
      : `${commitCount(commits)}, not pushed`;
  }
  return active ? 'Working' : 'No result';
}

/** Why a repository of a run is read-only (the package 3 contract, §3.11). */
export const READ_ONLY_CAUSES: Record<NonNullable<RunRepository['readOnlyCause']>, string> = {
  AGENT: 'The agent may only read this repository',
  GRANT: 'Read-only: the conversation acts through a read-only grant here',
  REQUEST: 'Read access was what the approval gave',
};

/**
 * The workspace of a run (its conversation's repositories, each with its read-only flag and why)
 * with the git result of each once the run has finished: directory, push, commits and head. The
 * change requests are the conversation's outputs.
 */
@Component({
  selector: 'app-run-repositories',
  imports: [NgIcon],
  providers: [provideIcons({ lucideFolderGit2 })],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'block min-w-0' },
  template: `
    <ul class="flex flex-col gap-1" aria-label="Repositories of this run">
      @for (repo of repositories(); track repo.dir) {
        <li
          class="flex min-w-0 flex-wrap items-center gap-x-2 gap-y-0.5 text-xs"
          data-testid="run-repository"
        >
          <ng-icon
            name="lucideFolderGit2"
            class="text-muted-foreground shrink-0"
            aria-hidden="true"
          />
          <span class="text-foreground font-semibold">{{ repo.repositoryName }}</span>
          <code
            class="text-muted-foreground max-w-[14rem] truncate font-mono"
            [title]="'/workspace/' + repo.dir"
            >{{ repo.dir }}</code
          >
          @if (repo.readOnly) {
            <span
              class="text-muted-foreground rounded-full border px-2 font-semibold"
              data-testid="read-only"
              [title]="readOnlyWhy(repo)"
              >Read-only</span
            >
          }
          <span class="text-muted-foreground tabular-nums">{{ result(repo) }}</span>
          @if (repo.headSha) {
            <code class="text-muted-foreground font-mono" [title]="repo.headSha"
              ><span class="sr-only">at </span>{{ sha(repo.headSha) }}</code
            >
          }
        </li>
      }
    </ul>
  `,
})
export class RunRepositories {
  readonly repositories = input.required<readonly RunRepository[]>();
  /** The run is still queued or running, so there is no git result yet. */
  readonly active = input(false);

  protected readonly sha = shortSha;

  protected readOnlyWhy(repo: RunRepository): string {
    const cause = repo.readOnlyCause ? READ_ONLY_CAUSES[repo.readOnlyCause] : null;
    return `${cause ?? 'The agent could only read this repository'}: Tinfirm does not push it`;
  }

  protected result(repo: RunRepository): string {
    return gitResult(repo, this.active());
  }
}
