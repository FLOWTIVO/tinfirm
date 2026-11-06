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
import { Router, RouterLink } from '@angular/router';
import { NgIcon, provideIcons } from '@ng-icons/core';
import {
  lucideArrowLeft,
  lucideCircleAlert,
  lucideDownload,
  lucideEye,
  lucideFile,
  lucideTrash2,
  lucideUpload,
} from '@ng-icons/lucide';
import { HlmAlertImports } from '@spartan-ng/helm/alert';
import { HlmAlertDialogImports } from '@spartan-ng/helm/alert-dialog';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmSpinner } from '@spartan-ng/helm/spinner';
import { Api } from '../../../api/generated/api';
import {
  addSkillVersion,
  deleteSkill,
  getSkill,
  getSkillVersion,
} from '../../../api/generated/functions';
import type { Skill, SkillVersionSummary } from '../../../api/generated/models';
import { problemMessage, toProblem } from '../../../api/problem';
import { CurrentCompany } from '../../../core/current-company';
import { Notify } from '../../../core/notify';
import { ErrorState } from '../../../shared/error-state';
import { fileSize } from '../../../shared/files/file-labels';
import { ListSkeleton } from '../../../shared/list-skeleton';
import { Time } from '../../../shared/time';
import { NotFound } from '../../not-found';
import {
  count,
  emptyUpload,
  frontMatterName,
  ownerText,
  skillVersionUrl,
  type SkillUploadDraft,
  uploadBody,
  uploadDraftProblem,
  type UploadFailure,
  uploadFailure,
  usedByText,
  zipName,
} from './skill-labels';
import { SkillShares } from './skill-shares';
import { SkillUploadForm } from './skill-upload-form';

const NOT_EDITABLE = {
  SKILL_NOT_EDITABLE: 'Only the skill’s owner and the company’s admins can change this skill.',
};

/**
 * One skill of the library (the skills contract §4, §6), `skills/:skillId`: its description and
 * owner, its versions newest first, each opened in place (its files and the text of its SKILL.md)
 * and downloaded as its zip; whom it is shared with. Whoever edits it uploads a new version (the
 * same files as the latest store nothing), shares it and deletes it: the agents that pin a version
 * keep it.
 */
@Component({
  selector: 'app-skill-page',
  imports: [
    RouterLink,
    NgIcon,
    HlmAlertImports,
    HlmAlertDialogImports,
    HlmButton,
    HlmSpinner,
    ErrorState,
    ListSkeleton,
    NotFound,
    SkillShares,
    SkillUploadForm,
    Time,
  ],
  providers: [
    provideIcons({
      lucideArrowLeft,
      lucideCircleAlert,
      lucideDownload,
      lucideEye,
      lucideFile,
      lucideTrash2,
      lucideUpload,
    }),
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @let companyId = current.id();
    @if (notFound()) {
      <app-not-found />
    } @else {
      <div class="flex flex-col gap-5">
        <a
          hlmBtn
          variant="ghost"
          size="sm"
          class="-ms-3 self-start"
          [routerLink]="['/c', companyId, 'skills']"
        >
          <ng-icon name="lucideArrowLeft" aria-hidden="true" />
          Skills
        </a>

        @if (skillRes.isLoading() && !skill()) {
          <app-list-skeleton [rows]="3" />
        } @else if (skillRes.error() && !skill()) {
          <app-error-state
            [error]="skillRes.error()"
            heading="Couldn't load this skill"
            (retry)="skillRes.reload()"
          />
        } @else if (skill(); as s) {
          <header
            class="flex flex-col gap-3 sm:flex-row sm:flex-wrap sm:items-start sm:justify-between"
            data-testid="skill-header"
          >
            <div class="flex min-w-0 flex-col gap-1 sm:flex-[1_1_24rem]">
              <div class="flex flex-wrap items-center gap-x-2.5 gap-y-1">
                <h1 class="text-xl font-bold tracking-tight break-all sm:text-2xl">{{ s.name }}</h1>
                <span
                  class="bg-secondary text-muted-foreground inline-flex h-6 items-center rounded-full px-2.5 font-mono text-xs font-semibold"
                  >v{{ s.latestVersion }}</span
                >
              </div>
              <p class="text-muted-foreground text-sm text-pretty">{{ s.description }}</p>
              <p class="text-muted-foreground text-xs" data-testid="skill-meta">{{ meta() }}</p>
            </div>
            @if (s.editableByMe) {
              <div class="flex flex-wrap items-center gap-2 sm:shrink-0">
                <button
                  hlmBtn
                  variant="outline"
                  size="sm"
                  type="button"
                  data-testid="new-version"
                  [disabled]="uploadOpen()"
                  (click)="openUpload()"
                >
                  <ng-icon name="lucideUpload" aria-hidden="true" />
                  New version
                </button>
                <button
                  hlmBtn
                  variant="destructive"
                  size="sm"
                  type="button"
                  data-testid="delete-skill"
                  (click)="askDelete()"
                >
                  <ng-icon name="lucideTrash2" aria-hidden="true" />
                  Delete
                </button>
              </div>
            }
          </header>

          @if (uploadOpen()) {
            <form
              class="bg-card flex min-w-0 flex-col gap-4 rounded-xl border p-4 sm:p-5"
              novalidate
              aria-labelledby="upload-title"
              data-testid="version-form"
              (submit)="upload($event)"
            >
              <div class="flex flex-col gap-0.5">
                <h2 class="text-base font-bold" id="upload-title">
                  New version {{ s.latestVersion + 1 }}
                </h2>
                <p class="text-muted-foreground text-xs text-pretty">
                  It keeps the name {{ s.name }}. No agent moves to it by itself: their editors see
                  that it is there, and move the pin when they choose.
                </p>
              </div>
              <app-skill-upload-form
                [forVersion]="true"
                [draft]="draft()"
                (draftChange)="changeDraft($event)"
                [latestFileCount]="latestFileCount()"
                [problem]="uploadProblem()"
                [failure]="uploadError()"
                [disabled]="uploading()"
              />
              <div class="flex flex-col-reverse gap-2 border-t pt-4 sm:flex-row sm:justify-end">
                <button
                  hlmBtn
                  variant="outline"
                  type="button"
                  [disabled]="uploading()"
                  (click)="uploadOpen.set(false)"
                >
                  Cancel
                </button>
                <button hlmBtn type="submit" data-testid="upload-version" [disabled]="uploading()">
                  @if (uploading()) {
                    <hlm-spinner aria-hidden="true" />
                  }
                  Upload version
                </button>
              </div>
            </form>
          }

          <div class="grid gap-5 lg:grid-cols-[minmax(0,1fr)_20rem] lg:items-start">
            <div class="flex min-w-0 flex-col gap-5">
              @if (viewing(); as v) {
                <section
                  class="bg-card flex min-w-0 flex-col gap-4 rounded-xl border p-4 sm:p-5"
                  aria-labelledby="version-view-title"
                  data-testid="version-view"
                  [attr.data-version]="v"
                >
                  <div class="flex flex-wrap items-start justify-between gap-3">
                    <div class="flex min-w-0 flex-col gap-0.5">
                      <h2 class="text-base font-bold" id="version-view-title">
                        Version {{ v }}
                        @if (v === s.latestVersion) {
                          <span class="text-muted-foreground text-sm font-normal"
                            >· the latest</span
                          >
                        }
                      </h2>
                      @if (viewedSummary()?.note; as note) {
                        <p class="text-muted-foreground text-sm text-pretty">{{ note }}</p>
                      }
                    </div>
                    <a
                      hlmBtn
                      variant="outline"
                      size="sm"
                      data-testid="download-viewed"
                      [href]="zipUrl(v)"
                      [attr.download]="zip(v)"
                    >
                      <ng-icon name="lucideDownload" aria-hidden="true" />
                      Download
                    </a>
                  </div>
                  @if (versionRes.isLoading() && !version()) {
                    <app-list-skeleton [rows]="2" />
                  } @else if (versionRes.error() && !version()) {
                    <p class="text-destructive text-sm" role="alert">
                      Couldn't load version {{ v }}: {{ versionError() }}
                    </p>
                  } @else if (version(); as ver) {
                    <div class="flex min-w-0 flex-col gap-1.5">
                      <h3 class="text-sm font-semibold" id="version-files">
                        {{ countText(ver.files.length, 'file', 'files') }}
                      </h3>
                      <ul
                        class="flex flex-col divide-y rounded-lg border"
                        aria-labelledby="version-files"
                        data-testid="version-files"
                      >
                        @for (f of ver.files; track f.path) {
                          <li
                            class="flex min-w-0 items-center justify-between gap-3 px-3 py-2"
                            [attr.data-path]="f.path"
                          >
                            <span class="flex min-w-0 items-center gap-2">
                              <ng-icon
                                name="lucideFile"
                                size="14"
                                class="text-muted-foreground shrink-0"
                                aria-hidden="true"
                              />
                              <span class="truncate font-mono text-xs">{{ f.path }}</span>
                            </span>
                            <span class="text-muted-foreground shrink-0 font-mono text-xs">{{
                              size(f.sizeBytes)
                            }}</span>
                          </li>
                        }
                      </ul>
                    </div>
                    <div class="flex min-w-0 flex-col gap-1.5">
                      <h3 class="text-sm font-semibold" id="version-skill-md">SKILL.md</h3>
                      <pre
                        class="bg-code m-0 max-h-[32rem] overflow-auto rounded-lg border p-3 font-mono text-xs leading-relaxed whitespace-pre-wrap break-words"
                        aria-labelledby="version-skill-md"
                        data-testid="version-skill-md"
                        >{{ ver.skillMd }}</pre>
                    </div>
                  }
                </section>
              }

              <section
                class="bg-card flex min-w-0 flex-col gap-3 rounded-xl border p-4 sm:p-5"
                aria-labelledby="skill-versions"
              >
                <div class="flex flex-col gap-0.5">
                  <h2 class="text-base font-bold" id="skill-versions">Versions</h2>
                  <p class="text-muted-foreground text-xs text-pretty">
                    Each version stays as it was uploaded. An agent pins one of them.
                  </p>
                </div>
                <ul class="flex flex-col divide-y rounded-lg border" data-testid="skill-versions">
                  @for (v of s.versions; track v.version) {
                    @let open = v.version === viewing();
                    <li
                      class="flex min-w-0 flex-col gap-2 px-3 py-2.5 sm:flex-row sm:items-center sm:justify-between"
                      [class.bg-muted]="open"
                      [attr.data-version]="v.version"
                    >
                      <div class="flex min-w-0 flex-col gap-0.5">
                        <span class="flex flex-wrap items-baseline gap-x-2">
                          <span class="font-mono text-sm font-semibold">v{{ v.version }}</span>
                          <span
                            class="min-w-0 text-sm"
                            [class.text-muted-foreground]="!v.note"
                            data-note
                            >{{ v.note || 'No note' }}</span
                          >
                        </span>
                        <span class="text-muted-foreground text-xs" data-meta>
                          {{ v.createdByName ? 'by ' + v.createdByName + ' · ' : '' }}
                          <app-time [value]="v.createdAt" /> ·
                          <span class="font-mono">{{
                            countText(v.fileCount, 'file', 'files')
                          }}</span>
                          ·
                          <span class="font-mono">{{ size(v.sizeBytes) }}</span>
                        </span>
                      </div>
                      <div class="flex shrink-0 items-center gap-1">
                        <button
                          hlmBtn
                          variant="ghost"
                          size="sm"
                          type="button"
                          data-testid="view-version"
                          [attr.aria-pressed]="open"
                          [attr.aria-label]="'View version ' + v.version"
                          (click)="view(v.version)"
                        >
                          <ng-icon name="lucideEye" aria-hidden="true" />
                          View
                        </button>
                        <a
                          hlmBtn
                          variant="ghost"
                          size="icon-sm"
                          data-testid="download-version"
                          title="Download"
                          [href]="zipUrl(v.version)"
                          [attr.download]="zip(v.version)"
                          [attr.aria-label]="'Download version ' + v.version"
                        >
                          <ng-icon name="lucideDownload" aria-hidden="true" />
                        </a>
                      </div>
                    </li>
                  }
                </ul>
              </section>
            </div>

            <aside class="flex min-w-0 flex-col gap-5" aria-label="About the skill">
              @if (companyId) {
                <app-skill-shares
                  [companyId]="companyId"
                  [skill]="s"
                  [editable]="s.editableByMe"
                  (saved)="skillRes.set($event)"
                />
              }
              @if (usedBy(); as used) {
                <section
                  class="bg-card flex flex-col gap-1 rounded-xl border p-4"
                  aria-labelledby="skill-used-by"
                  data-testid="skill-used-by"
                >
                  <h2 class="text-sm font-semibold" id="skill-used-by">{{ used }}</h2>
                  <p class="text-muted-foreground text-xs text-pretty">
                    Each agent pins a version. A new version reaches an agent when its editors move
                    the pin; the agent’s page tells them it is there.
                  </p>
                </section>
              }
            </aside>
          </div>
        }
      </div>
    }

    <hlm-alert-dialog [state]="deleteOpen() ? 'open' : 'closed'" (closed)="deleteOpen.set(false)">
      <hlm-alert-dialog-content *hlmAlertDialogPortal="let ctx" data-testid="delete-dialog">
        <hlm-alert-dialog-header>
          <h2 hlmAlertDialogTitle class="break-all">Delete {{ skill()?.name }}?</h2>
          <p hlmAlertDialogDescription data-testid="delete-consequence">{{ deleteText() }}</p>
        </hlm-alert-dialog-header>
        @if (skill()?.agentCount !== 0) {
          <label class="flex items-start gap-2 text-sm">
            <input
              type="checkbox"
              class="accent-primary mt-0.5 size-4 shrink-0"
              data-testid="remove-from-agents"
              [checked]="removeFromAgents()"
              [disabled]="deleting()"
              (change)="removeFromAgents.set($any($event.target).checked)"
            />
            <span>
              Also remove it from {{ pinningAgents() }}
              <span class="text-muted-foreground block text-xs"
                >Their next turns run without it, agents you don’t edit included. Each agent’s
                change is recorded in its history and the audit log.</span
              >
            </span>
          </label>
        }
        @if (deleteError(); as message) {
          <div hlmAlert variant="destructive" data-testid="delete-error">
            <ng-icon name="lucideCircleAlert" aria-hidden="true" />
            <p hlmAlertDescription>{{ message }}</p>
          </div>
        }
        <hlm-alert-dialog-footer>
          <button hlmAlertDialogCancel [disabled]="deleting()">Cancel</button>
          <button
            hlmAlertDialogAction
            variant="destructive"
            data-testid="confirm-delete"
            [disabled]="deleting()"
            (click)="remove()"
          >
            @if (deleting()) {
              <hlm-spinner aria-hidden="true" />
            }
            Delete skill
          </button>
        </hlm-alert-dialog-footer>
      </hlm-alert-dialog-content>
    </hlm-alert-dialog>
  `,
})
export class SkillPage {
  private readonly api = inject(Api);
  private readonly router = inject(Router);
  private readonly notify = inject(Notify);
  protected readonly current = inject(CurrentCompany);

  readonly skillId = input.required<string>();

  protected readonly size = fileSize;

  protected readonly skillRes = resource({
    params: () => {
      const companyId = this.current.id();
      return companyId ? { companyId, skillId: this.skillId() } : undefined;
    },
    loader: ({ params }) => this.api.invoke(getSkill, params),
  });
  /** The skill as last read, or as the last change answered it. */
  protected readonly skill = computed<Skill | undefined>(() =>
    this.skillRes.hasValue() ? this.skillRes.value() : undefined,
  );
  protected readonly notFound = computed(
    () => !this.skill() && toProblem(this.skillRes.error()).status === 404,
  );
  protected readonly usedBy = computed(() => usedByText(this.skill()?.agentCount));
  protected readonly meta = computed(() => {
    const s = this.skill();
    if (!s) return '';
    const parts = [
      ownerText(s.ownerName),
      count(s.versions.length || s.latestVersion, 'version', 'versions'),
    ];
    const text = parts.join(' · ');
    return text.charAt(0).toUpperCase() + text.slice(1);
  });

  // --- The version in view ---------------------------------------------------------------------

  /** The version opened: the latest, until the reader opens another or a new one is stored. */
  protected readonly viewing = linkedSignal<Skill | undefined, number | null>({
    source: this.skill,
    computation: (skill, previous) => {
      if (!skill) return null;
      const before = previous?.source;
      return previous?.value &&
        before?.id === skill.id &&
        before.latestVersion === skill.latestVersion
        ? previous.value
        : skill.latestVersion;
    },
  });
  protected readonly viewedSummary = computed<SkillVersionSummary | undefined>(() =>
    this.skill()?.versions.find((v) => v.version === this.viewing()),
  );
  protected readonly versionRes = resource({
    params: () => {
      const companyId = this.current.id();
      const version = this.viewing();
      return companyId && version ? { companyId, skillId: this.skillId(), version } : undefined;
    },
    loader: ({ params }) => this.api.invoke(getSkillVersion, params),
  });
  /** The version read, once it is the one in view. */
  protected readonly version = computed(() => {
    const read = this.versionRes.hasValue() ? this.versionRes.value() : undefined;
    return read && read.version === this.viewing() ? read : undefined;
  });
  protected readonly versionError = computed(() => problemMessage(this.versionRes.error()));

  protected view(version: number): void {
    this.viewing.set(version);
  }

  protected zipUrl(version: number): string {
    return skillVersionUrl(this.current.id() ?? '', this.skillId(), version);
  }

  protected zip(version: number): string {
    return zipName(this.skill()?.name ?? 'skill', version);
  }

  protected countText(n: number, one: string, many: string): string {
    return count(n, one, many);
  }

  // --- A new version ---------------------------------------------------------------------------

  protected readonly uploadOpen = signal(false);
  protected readonly draft = signal<SkillUploadDraft>(emptyUpload(''));
  protected readonly uploadProblem = signal<string | null>(null);
  protected readonly uploadError = signal<UploadFailure | null>(null);
  protected readonly uploading = signal(false);
  /** The latest version's files: a SKILL.md written here leaves the others out. */
  protected readonly latestFileCount = computed(() => {
    const s = this.skill();
    return s?.versions.find((v) => v.version === s.latestVersion)?.fileCount ?? null;
  });

  /**
   * Opens the form, starting from the latest version's SKILL.md when it was read, else from a
   * front matter with the skill's name and description.
   */
  protected openUpload(): void {
    const s = this.skill();
    if (!s) return;
    const latest = this.version();
    const text =
      latest && latest.version === s.latestVersion
        ? latest.skillMd
        : `---\nname: ${s.name}\ndescription: ${s.description}\n---\n`;
    this.draft.set(emptyUpload(text));
    this.uploadProblem.set(null);
    this.uploadError.set(null);
    this.uploadOpen.set(true);
  }

  protected changeDraft(draft: SkillUploadDraft): void {
    this.draft.set(draft);
    this.uploadProblem.set(null);
    this.uploadError.set(null);
  }

  protected async upload(event: Event): Promise<void> {
    event.preventDefault();
    const companyId = this.current.id();
    const s = this.skill();
    if (!companyId || !s || this.uploading()) return;
    const draft = this.draft();
    const name = draft.source === 'write' ? frontMatterName(draft.skillMd) : null;
    const problem =
      uploadDraftProblem(draft) ??
      (name && name !== s.name
        ? `A new version keeps the skill’s name: write name: ${s.name} in the front matter of SKILL.md.`
        : null);
    this.uploadProblem.set(problem);
    this.uploadError.set(null);
    if (problem) return;
    this.uploading.set(true);
    try {
      const response = await this.api.invoke$Response(addSkillVersion, {
        companyId,
        skillId: s.id,
        body: uploadBody(draft),
      });
      const skill = response.body;
      this.uploadOpen.set(false);
      if (response.status === 200) {
        this.notify.warning(
          'Nothing new to store',
          `These are the same files as version ${skill.latestVersion}, so no version was added.`,
        );
      } else {
        this.notify.success(
          `Version ${skill.latestVersion} stored`,
          'No agent moves to it by itself: their editors see that it is there.',
        );
      }
      this.skillRes.set(skill);
    } catch (error) {
      this.uploadError.set(uploadFailure(error, s.name));
    } finally {
      this.uploading.set(false);
    }
  }

  // --- Delete ----------------------------------------------------------------------------------

  protected readonly deleteOpen = signal(false);
  protected readonly deleting = signal(false);
  protected readonly deleteError = signal<string | null>(null);
  /** Whether the delete also takes the skill from every agent that pins it. */
  protected readonly removeFromAgents = signal(false);
  protected readonly pinningAgents = computed(() => {
    const n = this.skill()?.agentCount;
    return n === null || n === undefined
      ? 'the agents that pin it'
      : `the ${count(n, 'agent', 'agents')} that pin it`;
  });
  protected readonly deleteText = computed(() => {
    const n = this.skill()?.agentCount;
    if (this.removeFromAgents()) {
      const agents =
        n === null || n === undefined
          ? 'Agents that pin it'
          : `The ${count(n, 'agent', 'agents')} that pin it`;
      return `${agents} lose it at their next turn. It leaves the library: nobody can add it to an agent again, and its name is free again. You can’t undo this.`;
    }
    const agents =
      n === null || n === undefined
        ? 'Agents that pin a version of it keep that version'
        : n === 0
          ? 'No agent pins it now; one that did would keep its version'
          : `The ${count(n, 'agent', 'agents')} that pin a version of it keep that version`;
    return `${agents}, and their runs still get it. It leaves the library: nobody can add it to an agent or move a pin to another of its versions, and its name is free again. You can’t undo this.`;
  });

  protected askDelete(): void {
    this.deleteError.set(null);
    this.removeFromAgents.set(false);
    this.deleteOpen.set(true);
  }

  protected async remove(): Promise<void> {
    const companyId = this.current.id();
    const s = this.skill();
    if (!companyId || !s || this.deleting()) return;
    this.deleting.set(true);
    this.deleteError.set(null);
    try {
      const removing = this.removeFromAgents();
      await this.api.invoke(deleteSkill, {
        companyId,
        skillId: s.id,
        ...(removing ? { removeFromAgents: true } : {}),
      });
      this.deleteOpen.set(false);
      this.notify.success(
        `${s.name} deleted`,
        removing
          ? 'It was removed from the agents that pinned it.'
          : 'The agents that pin it keep their version.',
      );
      void this.router.navigate(['/c', companyId, 'skills']);
    } catch (error) {
      this.deleteError.set(problemMessage(error, NOT_EDITABLE));
    } finally {
      this.deleting.set(false);
    }
  }
}
