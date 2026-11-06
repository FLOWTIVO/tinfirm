import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { NgIcon, provideIcons } from '@ng-icons/core';
import { lucideArrowLeft } from '@ng-icons/lucide';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmSpinner } from '@spartan-ng/helm/spinner';
import { Api } from '../../../api/generated/api';
import { createSkill } from '../../../api/generated/functions';
import { CurrentCompany } from '../../../core/current-company';
import { Notify } from '../../../core/notify';
import {
  emptyUpload,
  frontMatterName,
  type SkillUploadDraft,
  uploadBody,
  uploadDraftProblem,
  type UploadFailure,
  uploadFailure,
} from './skill-labels';
import { SkillUploadForm } from './skill-upload-form';

/**
 * A new skill of the library (the skills contract §4.2, §6), `skills/new`: a `SKILL.md` written
 * here, from a template of its front matter, or a zip of its folder, and a note. Every member
 * creates skills and owns those they create; version 1 is private to its owner until shared. The
 * server checks the format, the paths and the sizes and scans for credentials; what it refuses is
 * said in the form.
 */
@Component({
  selector: 'app-new-skill',
  imports: [RouterLink, NgIcon, HlmButton, HlmSpinner, SkillUploadForm],
  providers: [provideIcons({ lucideArrowLeft })],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @let companyId = current.id();
    <div class="flex max-w-3xl flex-col gap-5">
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
      <div class="flex min-w-0 flex-col gap-1">
        <h1 class="text-xl font-bold tracking-tight text-balance sm:text-2xl">New skill</h1>
        <p class="text-muted-foreground text-sm text-pretty">
          A procedure written once, for every agent that needs it. An agent reads its name and
          description, and the rest when a task fits. It is private to you until you share it.
        </p>
      </div>

      <form
        class="bg-card flex min-w-0 flex-col gap-5 rounded-xl border p-4 sm:p-5"
        novalidate
        data-testid="new-skill-form"
        (submit)="save($event)"
      >
        <app-skill-upload-form
          [draft]="draft()"
          (draftChange)="change($event)"
          [problem]="problem()"
          [failure]="failure()"
          [disabled]="saving()"
        />
        <div class="flex flex-col-reverse gap-2 border-t pt-4 sm:flex-row sm:justify-end">
          <a hlmBtn variant="outline" [routerLink]="['/c', companyId, 'skills']">Cancel</a>
          <button hlmBtn type="submit" data-testid="create-skill" [disabled]="saving()">
            @if (saving()) {
              <hlm-spinner aria-hidden="true" />
            }
            Create skill
          </button>
        </div>
      </form>
    </div>
  `,
})
export class NewSkill {
  private readonly api = inject(Api);
  private readonly router = inject(Router);
  private readonly notify = inject(Notify);
  protected readonly current = inject(CurrentCompany);

  protected readonly draft = signal<SkillUploadDraft>(emptyUpload());
  protected readonly problem = signal<string | null>(null);
  protected readonly failure = signal<UploadFailure | null>(null);
  protected readonly saving = signal(false);
  private readonly name = computed(() =>
    this.draft().source === 'write' ? frontMatterName(this.draft().skillMd) : null,
  );

  protected change(draft: SkillUploadDraft): void {
    this.draft.set(draft);
    this.problem.set(null);
    this.failure.set(null);
  }

  protected async save(event: Event): Promise<void> {
    event.preventDefault();
    const companyId = this.current.id();
    if (!companyId || this.saving()) return;
    const draft = this.draft();
    const problem = uploadDraftProblem(draft);
    this.problem.set(problem);
    this.failure.set(null);
    if (problem) return;
    this.saving.set(true);
    try {
      const skill = await this.api.invoke(createSkill, { companyId, body: uploadBody(draft) });
      this.notify.success(`${skill.name} created`, 'Version 1, private to you until you share it.');
      void this.router.navigate(['/c', companyId, 'skills', skill.id]);
    } catch (error) {
      this.failure.set(uploadFailure(error, this.name()));
    } finally {
      this.saving.set(false);
    }
  }
}
