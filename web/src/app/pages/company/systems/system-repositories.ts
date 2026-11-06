import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
  untracked,
} from '@angular/core';
import { form, FormField, maxLength, pattern, required, submit } from '@angular/forms/signals';
import { NgIcon, provideIcons } from '@ng-icons/core';
import { lucideCircleAlert, lucideFolderGit2, lucidePlus, lucideTrash2 } from '@ng-icons/lucide';
import { HlmAlertImports } from '@spartan-ng/helm/alert';
import { HlmAlertDialogImports } from '@spartan-ng/helm/alert-dialog';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmDialogImports } from '@spartan-ng/helm/dialog';
import { HlmFieldImports } from '@spartan-ng/helm/field';
import { HlmInput } from '@spartan-ng/helm/input';
import { HlmSpinner } from '@spartan-ng/helm/spinner';
import { Api } from '../../../api/generated/api';
import {
  addSystemRepository,
  listSystemRepositories,
  removeSystemRepository,
} from '../../../api/generated/functions';
import type {
  ExternalSystem,
  SystemRepository,
  SystemRepositoryCreateRequest,
} from '../../../api/generated/models';
import { mapSubmissionError, problemMessage } from '../../../api/problem';
import { Notify } from '../../../core/notify';
import { pagedResource, sameParams } from '../../../core/paged-resource';
import { ErrorState } from '../../../shared/error-state';
import { firstError } from '../../../shared/forms';
import { systemKind } from './system-kinds';

const PAGE_SIZE = 200;
const FORBIDDEN = { 'status:403': 'Only owners and admins can change the company’s repositories.' };

/** An https address without credentials, a query or a fragment. */
const REPOSITORY_URL = /^https:\/\/[^\s/@?#]+\/[^\s?#]+$/;

/**
 * A code host's repositories (the web plan §5, Systems): agents get them in their workspace by
 * name, read-only or with writes as their definition and the account say. Owners and admins add
 * one by its URL on the system's host and remove it; everyone who sees the system reads the list.
 * It says how many it holds (`count`, "200+" while more pages wait), for the page's tab.
 */
@Component({
  selector: 'app-system-repositories',
  imports: [
    FormField,
    NgIcon,
    HlmAlertImports,
    HlmAlertDialogImports,
    HlmButton,
    HlmDialogImports,
    HlmFieldImports,
    HlmInput,
    HlmSpinner,
    ErrorState,
  ],
  providers: [provideIcons({ lucideCircleAlert, lucideFolderGit2, lucidePlus, lucideTrash2 })],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'block' },
  template: `
    <section
      class="bg-card overflow-hidden rounded-xl border"
      aria-labelledby="repositories-title"
      data-testid="system-repositories"
    >
      <div class="flex flex-wrap items-start gap-3 px-4 py-4 sm:px-5 sm:pt-5">
        <div class="flex min-w-0 flex-1 basis-64 flex-col gap-0.5">
          <h2 id="repositories-title" class="text-base font-bold">Repositories</h2>
          <p class="text-muted-foreground text-sm text-pretty">
            What agents may clone into their workspace, by name. Each person clones with their own
            account or the grant they act through, so a read-only grant never pushes.
          </p>
        </div>
        @if (canManage()) {
          <button
            hlmBtn
            variant="outline"
            size="sm"
            class="max-sm:h-11"
            type="button"
            data-testid="add-repository"
            (click)="openAdd()"
          >
            <ng-icon name="lucidePlus" aria-hidden="true" />
            Add a repository
          </button>
        }
      </div>

      @if (repositories.failed()) {
        <div class="px-4 pb-4 sm:px-5">
          <app-error-state
            [error]="repositories.error()"
            heading="Couldn't load the repositories"
            (retry)="repositories.retry()"
          />
        </div>
      } @else if (repositories.firstLoad()) {
        <p class="text-muted-foreground border-t px-4 py-4 text-sm sm:px-5" role="status">
          Loading the repositories…
        </p>
      } @else if (repositories.empty()) {
        <p
          class="text-muted-foreground mx-4 mb-4 rounded-lg border border-dashed px-3 py-4 text-center text-sm sm:mx-5 sm:mb-5"
          data-testid="repositories-empty"
        >
          No repositories yet.
        </p>
      } @else {
        <ul aria-labelledby="repositories-title">
          @for (repository of repositories.items(); track repository.id) {
            <li
              class="flex min-h-14 items-center gap-3 border-t py-2 ps-4 pe-2 sm:ps-5 sm:pe-3"
              [attr.data-testid]="'repository-' + repository.name"
            >
              <ng-icon
                name="lucideFolderGit2"
                class="text-muted-foreground shrink-0"
                aria-hidden="true"
              />
              <span class="flex min-w-0 flex-1 flex-col">
                <span class="truncate font-mono text-[0.8125rem] font-medium">{{
                  repository.name
                }}</span>
                <span class="text-muted-foreground truncate font-mono text-xs"
                  >{{ repository.url }}
                  @if (repository.defaultBranch) {
                    · {{ repository.defaultBranch }}
                  }
                </span>
              </span>
              @if (canManage()) {
                <button
                  hlmBtn
                  variant="ghost"
                  size="icon"
                  type="button"
                  class="text-muted-foreground max-sm:size-11"
                  [attr.aria-label]="'Remove ' + repository.name"
                  [title]="'Remove ' + repository.name"
                  (click)="askRemove(repository)"
                >
                  <ng-icon name="lucideTrash2" aria-hidden="true" />
                </button>
              }
            </li>
          }
        </ul>
        @if (repositories.hasMore()) {
          <div class="flex justify-center border-t px-4 py-3">
            <button
              hlmBtn
              variant="outline"
              size="sm"
              class="max-sm:h-11"
              type="button"
              [disabled]="repositories.loadingMore()"
              (click)="repositories.loadMore()"
            >
              Load more
            </button>
          </div>
        }
      }
    </section>

    <hlm-dialog [state]="addOpen() ? 'open' : 'closed'" (closed)="addOpen.set(false)">
      <hlm-dialog-content *hlmDialogPortal="let ctx" class="sm:max-w-md">
        <hlm-dialog-header>
          <h2 hlmDialogTitle>Add a repository</h2>
          <p hlmDialogDescription>{{ hint() }}</p>
        </hlm-dialog-header>
        <form
          id="repository-form"
          class="flex flex-col gap-4"
          novalidate
          (submit)="onAddSubmit($event)"
        >
          @if (addError(); as message) {
            <div hlmAlert variant="destructive">
              <ng-icon name="lucideCircleAlert" aria-hidden="true" />
              <p hlmAlertDescription>{{ message }}</p>
            </div>
          }
          <div hlmField>
            <label hlmFieldLabel for="repository-url">URL</label>
            <input
              hlmInput
              id="repository-url"
              type="url"
              class="font-mono"
              autocomplete="off"
              spellcheck="false"
              [placeholder]="placeholder()"
              [formField]="addForm.url"
            />
            <hlm-field-error>{{ firstError(addForm.url) }}</hlm-field-error>
          </div>
          <div hlmField>
            <label hlmFieldLabel for="repository-branch">Default branch</label>
            <input
              hlmInput
              id="repository-branch"
              class="font-mono"
              autocomplete="off"
              spellcheck="false"
              placeholder="main"
              [formField]="addForm.defaultBranch"
            />
            <p hlmFieldDescription>Empty: the host's default.</p>
            <hlm-field-error>{{ firstError(addForm.defaultBranch) }}</hlm-field-error>
          </div>
        </form>
        <hlm-dialog-footer>
          <button hlmBtn variant="outline" type="button" hlmDialogClose>Cancel</button>
          <button
            hlmBtn
            type="submit"
            form="repository-form"
            data-testid="repository-save"
            [disabled]="addForm().submitting()"
          >
            @if (addForm().submitting()) {
              <hlm-spinner aria-hidden="true" />
            }
            Add
          </button>
        </hlm-dialog-footer>
      </hlm-dialog-content>
    </hlm-dialog>

    <hlm-alert-dialog [state]="removeOpen() ? 'open' : 'closed'" (closed)="removeOpen.set(false)">
      <hlm-alert-dialog-content *hlmAlertDialogPortal="let ctx">
        <hlm-alert-dialog-header>
          <h2 hlmAlertDialogTitle>Remove {{ removing()?.name }}?</h2>
          <p hlmAlertDialogDescription>
            Agents no longer get it in new workspaces. The repository itself stays where it is.
          </p>
        </hlm-alert-dialog-header>
        @if (removeError(); as message) {
          <div hlmAlert variant="destructive">
            <ng-icon name="lucideCircleAlert" aria-hidden="true" />
            <p hlmAlertDescription>{{ message }}</p>
          </div>
        }
        <hlm-alert-dialog-footer>
          <button hlmAlertDialogCancel [disabled]="removeBusy()">Cancel</button>
          <button
            hlmAlertDialogAction
            variant="destructive"
            [disabled]="removeBusy()"
            (click)="confirmRemove()"
          >
            @if (removeBusy()) {
              <hlm-spinner aria-hidden="true" />
            }
            Remove repository
          </button>
        </hlm-alert-dialog-footer>
      </hlm-alert-dialog-content>
    </hlm-alert-dialog>
  `,
})
export class SystemRepositories {
  private readonly api = inject(Api);
  private readonly notify = inject(Notify);

  readonly companyId = input.required<string>();
  readonly system = input.required<ExternalSystem>();
  readonly canManage = input(false);
  /** How many it holds, once read: the page's tab shows it. */
  readonly count = output<string>();

  protected readonly firstError = firstError;
  protected readonly hint = computed(
    () => systemKind(this.system().kind).repositories?.hint ?? 'Its https address.',
  );
  protected readonly placeholder = computed(
    () => systemKind(this.system().kind).repositories?.placeholder ?? '',
  );

  private readonly key = computed(
    () => ({ companyId: this.companyId(), systemId: this.system().id }),
    { equal: sameParams },
  );
  protected readonly repositories = pagedResource<
    SystemRepository,
    { companyId: string; systemId: string }
  >({
    params: () => this.key(),
    load: (params, cursor) =>
      this.api.invoke(listSystemRepositories, { ...params, limit: PAGE_SIZE, cursor }),
  });

  constructor() {
    effect(() => {
      if (this.repositories.firstLoad() || this.repositories.failed()) return;
      const n = this.repositories.items().length;
      const count = this.repositories.hasMore() ? `${n}+` : String(n);
      untracked(() => this.count.emit(count));
    });
  }

  // --- Add ----------------------------------------------------------------------------------

  protected readonly addOpen = signal(false);
  protected readonly addModel = signal({ url: '', defaultBranch: '' });
  protected readonly addError = signal<string | null>(null);
  protected readonly addForm = form(this.addModel, (path) => {
    required(path.url, { message: 'Enter the repository’s address.' });
    maxLength(path.url, 500, { message: 'At most 500 characters.' });
    pattern(path.url, REPOSITORY_URL, {
      message: 'An https address, without a password, a query or a fragment.',
    });
    maxLength(path.defaultBranch, 255, { message: 'At most 255 characters.' });
  });

  protected openAdd(): void {
    this.addModel.set({ url: '', defaultBranch: '' });
    this.addForm().reset();
    this.addError.set(null);
    this.addOpen.set(true);
  }

  protected onAddSubmit(event: Event): void {
    event.preventDefault();
    this.addError.set(null);
    void submit(this.addForm, async () => {
      const body: SystemRepositoryCreateRequest = { url: this.addModel().url.trim() };
      const branch = this.addModel().defaultBranch.trim();
      if (branch) body.defaultBranch = branch;
      try {
        const added = await this.api.invoke(addSystemRepository, { ...this.key(), body });
        this.addOpen.set(false);
        this.repositories.refresh();
        this.notify.success(`${added.name} added`);
        return undefined;
      } catch (error) {
        const failure = mapSubmissionError(this.addForm, error, {
          codeFields: { DUPLICATE_REPOSITORY: 'url' },
          overrides: FORBIDDEN,
        });
        this.addError.set(failure.message);
        return failure.fieldErrors;
      }
    });
  }

  // --- Remove -------------------------------------------------------------------------------

  protected readonly removing = signal<SystemRepository | null>(null);
  protected readonly removeOpen = signal(false);
  protected readonly removeBusy = signal(false);
  protected readonly removeError = signal<string | null>(null);

  protected askRemove(repository: SystemRepository): void {
    this.removing.set(repository);
    this.removeError.set(null);
    this.removeOpen.set(true);
  }

  protected async confirmRemove(): Promise<void> {
    const repository = this.removing();
    if (!repository || this.removeBusy()) return;
    this.removeBusy.set(true);
    try {
      await this.api.invoke(removeSystemRepository, {
        ...this.key(),
        repositoryId: repository.id,
      });
      this.removeOpen.set(false);
      this.repositories.update((items) => items.filter((r) => r.id !== repository.id));
      this.notify.success(`${repository.name} removed`);
    } catch (error) {
      this.removeError.set(problemMessage(error, FORBIDDEN));
    } finally {
      this.removeBusy.set(false);
    }
  }
}
