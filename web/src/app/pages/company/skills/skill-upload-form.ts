import { ChangeDetectionStrategy, Component, computed, input, model } from '@angular/core';
import { NgIcon, provideIcons } from '@ng-icons/core';
import {
  lucideCircleAlert,
  lucideFileArchive,
  lucideTriangleAlert,
  lucideX,
} from '@ng-icons/lucide';
import { HlmAlertImports } from '@spartan-ng/helm/alert';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmInput } from '@spartan-ng/helm/input';
import { HlmLabel } from '@spartan-ng/helm/label';
import { HlmTextarea } from '@spartan-ng/helm/textarea';
import { fileSize } from '../../../shared/files/file-labels';
import { Segmented, type SegmentedOption } from '../../../shared/segmented';
import {
  count,
  frontMatterName,
  SKILL_MD_MAX_CHARS,
  SKILL_NOTE_MAX_CHARS,
  type SkillSource,
  type SkillUploadDraft,
  type UploadFailure,
} from './skill-labels';

const SOURCES: readonly SegmentedOption[] = [
  { value: 'write', label: 'Write SKILL.md' },
  { value: 'zip', label: 'Upload a zip' },
];

let nextId = 0;

/**
 * The fields of an upload (the skills contract §4.2), for a new skill and for a new version: a
 * `SKILL.md` written here (a skill of that one file), or a zip of the skill's folder, and a note
 * for the version's history. It edits `draft` only and shows what the page says went wrong; the
 * page sends it.
 */
@Component({
  selector: 'app-skill-upload-form',
  imports: [NgIcon, HlmAlertImports, HlmButton, HlmInput, HlmLabel, HlmTextarea, Segmented],
  providers: [provideIcons({ lucideCircleAlert, lucideFileArchive, lucideTriangleAlert, lucideX })],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'flex min-w-0 flex-col gap-4', 'data-testid': 'skill-upload-form' },
  template: `
    @let d = draft();
    <fieldset class="m-0 flex min-w-0 flex-col gap-4 border-0 p-0" [disabled]="disabled()">
      <app-segmented
        class="sm:self-start"
        [inputId]="id + '-source'"
        label="Upload from"
        [options]="sources"
        [value]="d.source"
        (valueChange)="setSource($event)"
      />

      @if (d.source === 'write') {
        <div class="flex min-w-0 flex-col gap-1.5" data-field="skillMd">
          <label hlmLabel [for]="id + '-skill-md'">SKILL.md</label>
          <p class="text-muted-foreground -mt-0.5 text-xs text-pretty" [id]="id + '-skill-md-hint'">
            The front matter first: its <code class="font-mono">name</code>, lower-case words joined
            by hyphens, which is its folder’s name in the sandbox, and a
            <code class="font-mono">description</code>, which is how an agent knows when to read it.
            Then the instructions, in Markdown.
          </p>
          <textarea
            hlmTextarea
            class="min-h-64 font-mono text-[0.8125rem] leading-relaxed [field-sizing:content]"
            spellcheck="false"
            autocomplete="off"
            data-testid="skill-md"
            [id]="id + '-skill-md'"
            [attr.maxlength]="maxChars"
            [attr.aria-describedby]="id + '-skill-md-hint'"
            [value]="d.skillMd"
            (input)="patch({ skillMd: $any($event.target).value })"
          ></textarea>
          @if (name(); as n) {
            <p class="text-muted-foreground text-xs" data-testid="skill-md-name">
              Its name: <code class="text-foreground font-mono">{{ n }}</code>
            </p>
          }
          @if (droppedFiles() > 0) {
            <p
              class="text-muted-foreground flex items-start gap-1.5 text-xs text-pretty"
              data-testid="drops-files"
            >
              <ng-icon
                name="lucideTriangleAlert"
                size="14"
                class="text-warning mt-px shrink-0"
                aria-hidden="true"
              />
              A SKILL.md written here makes a version of that one file: the
              {{ countText(droppedFiles(), 'other file', 'other files') }} of the latest version are
              left out. Upload a zip to keep them.
            </p>
          }
        </div>
      } @else {
        <div class="flex min-w-0 flex-col gap-1.5" data-field="file">
          <span class="text-sm leading-none font-medium" [id]="id + '-file-label'"
            >The skill’s folder, as a zip</span
          >
          <p class="text-muted-foreground -mt-0.5 text-xs text-pretty">
            <code class="font-mono">SKILL.md</code> at its root, or under its one top folder, with
            any scripts, references or templates. At most 200 files and 5 MiB.
          </p>
          <input
            #picker
            type="file"
            accept=".zip,application/zip"
            class="sr-only"
            tabindex="-1"
            aria-hidden="true"
            data-testid="skill-zip"
            (change)="picked($event)"
          />
          @if (d.file; as f) {
            <div
              class="flex min-w-0 items-center gap-2 rounded-lg border px-3 py-2"
              data-testid="skill-zip-chosen"
            >
              <ng-icon
                name="lucideFileArchive"
                class="text-muted-foreground shrink-0"
                aria-hidden="true"
              />
              <span class="min-w-0 flex-1 truncate font-mono text-xs">{{ f.name }}</span>
              <span class="text-muted-foreground shrink-0 font-mono text-xs">{{
                size(f.size)
              }}</span>
              <button
                hlmBtn
                variant="ghost"
                size="icon-sm"
                type="button"
                class="shrink-0"
                [attr.aria-label]="'Remove ' + f.name"
                (click)="patch({ file: null })"
              >
                <ng-icon name="lucideX" aria-hidden="true" />
              </button>
            </div>
          } @else {
            <button
              hlmBtn
              variant="outline"
              type="button"
              class="self-start"
              data-testid="choose-zip"
              [attr.aria-describedby]="id + '-file-label'"
              (click)="picker.click()"
            >
              <ng-icon name="lucideFileArchive" aria-hidden="true" />
              Choose a zip
            </button>
          }
        </div>
      }

      <div class="flex min-w-0 flex-col gap-1.5" data-field="note">
        <label hlmLabel [for]="id + '-note'"
          >{{ noteLabel() }}
          <span class="text-muted-foreground font-normal">(optional)</span></label
        >
        <input
          hlmInput
          autocomplete="off"
          data-testid="skill-note"
          [id]="id + '-note'"
          [attr.maxlength]="maxNote"
          [placeholder]="notePlaceholder()"
          [value]="d.note"
          (input)="patch({ note: $any($event.target).value })"
        />
      </div>
    </fieldset>

    @if (problem() || failure()) {
      <div hlmAlert variant="destructive" data-testid="upload-error">
        <ng-icon name="lucideCircleAlert" aria-hidden="true" />
        <div hlmAlertDescription class="flex flex-col gap-1.5" role="alert">
          <p>{{ problem() ?? failure()?.message }}</p>
          @if (!problem() && failure()?.path; as path) {
            <p data-testid="upload-error-path">
              In <code class="font-mono break-all">{{ path }}</code>
            </p>
          }
          @if (!problem() && failure()?.matches?.length) {
            <ul class="list-disc ps-5 text-xs" data-testid="upload-matches">
              @for (m of failure()!.matches; track m.name) {
                <li>
                  @switch (m.kind) {
                    @case ('VAULT_SECRET') {
                      The value of the secret <code class="font-mono">{{ m.name }}</code> from the
                      company’s vault
                    }
                    @case ('FORMAT') {
                      A <code class="font-mono">{{ m.name }}</code> credential
                    }
                    @default {
                      <code class="font-mono">{{ m.name }}</code>
                    }
                  }
                </li>
              }
            </ul>
          }
        </div>
      </div>
    }
  `,
})
export class SkillUploadForm {
  readonly draft = model.required<SkillUploadDraft>();
  /** What the page found wrong before sending; it wins over the server's answer. */
  readonly problem = input<string | null>(null);
  /** The server's refusal of the last upload. */
  readonly failure = input<UploadFailure | null>(null);
  readonly disabled = input(false);
  /** A new version: the latest's files beyond its SKILL.md, which a SKILL.md written here leaves out. */
  readonly latestFileCount = input<number | null>(null);
  /** Whether this is a new version, whose note says what changed. */
  readonly forVersion = input(false);

  protected readonly id = `skill-upload-${nextId++}`;
  protected readonly sources = SOURCES;
  protected readonly maxChars = SKILL_MD_MAX_CHARS;
  protected readonly maxNote = SKILL_NOTE_MAX_CHARS;
  protected readonly size = fileSize;

  protected readonly name = computed(() =>
    this.draft().source === 'write' ? frontMatterName(this.draft().skillMd) : null,
  );
  protected readonly droppedFiles = computed(() => Math.max(0, (this.latestFileCount() ?? 1) - 1));
  protected readonly noteLabel = computed(() => (this.forVersion() ? 'What changed' : 'Note'));
  protected readonly notePlaceholder = computed(() =>
    this.forVersion() ? 'Adds the acceptance criteria' : 'First version',
  );

  protected countText(n: number, one: string, many: string): string {
    return count(n, one, many);
  }

  protected patch(change: Partial<SkillUploadDraft>): void {
    this.draft.update((d) => ({ ...d, ...change }));
  }

  protected setSource(value: string): void {
    if (value === 'write' || value === 'zip') this.patch({ source: value as SkillSource });
  }

  protected picked(event: Event): void {
    const target = event.target as HTMLInputElement;
    const file = target.files?.[0] ?? null;
    target.value = '';
    if (file) this.patch({ file });
  }
}
