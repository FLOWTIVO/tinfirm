import { HttpErrorResponse } from '@angular/common/http';
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  signal,
  untracked,
} from '@angular/core';
import { form, FormField, maxLength, pattern, required, submit } from '@angular/forms/signals';
import { NgIcon, provideIcons } from '@ng-icons/core';
import {
  lucideCircleAlert,
  lucideKeyRound,
  lucideLock,
  lucidePlus,
  lucideRefreshCw,
  lucideSearch,
  lucideTrash2,
} from '@ng-icons/lucide';
import { HlmAlertImports } from '@spartan-ng/helm/alert';
import { HlmAlertDialogImports } from '@spartan-ng/helm/alert-dialog';
import { HlmBadge } from '@spartan-ng/helm/badge';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmDialogImports } from '@spartan-ng/helm/dialog';
import { HlmEmptyImports } from '@spartan-ng/helm/empty';
import { HlmFieldImports } from '@spartan-ng/helm/field';
import { HlmInput } from '@spartan-ng/helm/input';
import { HlmSpinner } from '@spartan-ng/helm/spinner';
import { HlmTableImports } from '@spartan-ng/helm/table';
import { HlmTextarea } from '@spartan-ng/helm/textarea';
import { Api } from '../../../api/generated/api';
import { createSecret, deleteSecret, updateSecret } from '../../../api/generated/functions';
import type { SecretMeta, SecretUpdateRequest } from '../../../api/generated/models';
import { mapSubmissionError, problemMessage } from '../../../api/problem';
import { CurrentCompany } from '../../../core/current-company';
import { Notify } from '../../../core/notify';
import { can } from '../../../core/permissions';
import { ErrorState } from '../../../shared/error-state';
import { firstError } from '../../../shared/forms';
import { ListSkeleton } from '../../../shared/list-skeleton';
import { Segmented, type SegmentedOption } from '../../../shared/segmented';
import { Time } from '../../../shared/time';
import { SettingsData } from '../settings/settings-data';

const SECRET_NAME = /^[A-Za-z_][A-Za-z0-9_]{0,63}$/;
const FORBIDDEN = { 'status:403': 'Only owners and admins can change secrets.' };

/**
 * The settings' Secrets section: the vault's credentials by name, never by value. People store,
 * renew and delete their own; those Tinfirm maintains for a system or an account say so, and can
 * only be deleted. A search and a filter narrow what is loaded.
 */
@Component({
  selector: 'app-secrets',
  imports: [
    FormField,
    NgIcon,
    HlmAlertImports,
    HlmAlertDialogImports,
    HlmBadge,
    HlmButton,
    HlmDialogImports,
    HlmEmptyImports,
    HlmFieldImports,
    HlmInput,
    HlmSpinner,
    HlmTableImports,
    HlmTextarea,
    ErrorState,
    ListSkeleton,
    Segmented,
    Time,
  ],
  providers: [
    provideIcons({
      lucideCircleAlert,
      lucideKeyRound,
      lucideLock,
      lucidePlus,
      lucideRefreshCw,
      lucideSearch,
      lucideTrash2,
    }),
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './secrets.html',
})
export class Secrets {
  private readonly api = inject(Api);
  private readonly notify = inject(Notify);
  private readonly current = inject(CurrentCompany);

  protected readonly canList = computed(() => can.viewSecrets(this.current.access()));
  protected readonly canWrite = computed(() => can.manageSecrets(this.current.access()));
  protected readonly firstError = firstError;
  /** Shown without asking the server when the role cannot list secrets (MEMBER). */
  protected readonly noAccess = new HttpErrorResponse({ status: 403 });
  protected readonly forbiddenMessages = {
    'status:403': 'Secrets are visible to owners and admins.',
  };

  /** The vault's secrets, shared with the settings' index, which counts them. */
  protected readonly secrets = inject(SettingsData).secrets;

  // --- Find ---------------------------------------------------------------------------------

  protected readonly filters: readonly SegmentedOption[] = [
    { value: 'all', label: 'All' },
    { value: 'own', label: 'Added here' },
    { value: 'managed', label: 'Managed' },
  ];
  protected readonly filter = signal('all');
  protected readonly query = signal('');
  /** Tinfirm maintains some, or a page not loaded yet may hold one: the filter is offered. */
  protected readonly hasManaged = computed(
    () => this.secrets.hasMore() || this.secrets.items().some((s) => s.managed),
  );
  /** The secrets the search and the filter keep, from every page once either narrows. */
  protected readonly visible = computed(() => {
    const query = this.query().trim().toLowerCase();
    const filter = this.hasManaged() ? this.filter() : 'all';
    return this.secrets
      .items()
      .filter(
        (s) =>
          (filter === 'all' || (filter === 'managed') === s.managed) &&
          (!query ||
            s.name.toLowerCase().includes(query) ||
            (s.description ?? '').toLowerCase().includes(query)),
      );
  });

  constructor() {
    // A search or a filter covers every secret, not only the pages loaded so far.
    effect(() => {
      const narrowing = this.query().trim() !== '' || this.filter() !== 'all';
      const secrets = this.secrets;
      if (narrowing && secrets.hasMore() && !secrets.loadingMore() && !secrets.moreFailed()) {
        untracked(() => secrets.loadMore());
      }
    });
  }

  // --- Create -------------------------------------------------------------------------------

  protected readonly createOpen = signal(false);
  protected readonly createModel = signal({ name: '', description: '', value: '' });
  protected readonly createForm = form(this.createModel, (path) => {
    required(path.name, { message: 'Enter a name.' });
    pattern(path.name, SECRET_NAME, {
      message:
        'Use letters, digits and underscores, start with a letter or underscore, at most 64 characters.',
    });
    maxLength(path.description, 500, { message: 'At most 500 characters.' });
    required(path.value, { message: 'Enter the value to store.' });
    maxLength(path.value, 65536, { message: 'The value is too long (64 KiB at most).' });
  });
  protected readonly createError = signal<string | null>(null);

  protected openCreate(): void {
    this.createModel.set({ name: '', description: '', value: '' });
    this.createForm().reset();
    this.createError.set(null);
    this.createOpen.set(true);
  }

  protected closeCreate(): void {
    this.createOpen.set(false);
    this.wipeCreateValue();
  }

  protected onCreateSubmit(event: Event): void {
    event.preventDefault();
    const companyId = this.current.id();
    if (!companyId) return;
    this.createError.set(null);
    void submit(this.createForm, async () => {
      const { name, description, value } = this.createModel();
      try {
        const secret = await this.api.invoke(createSecret, {
          companyId,
          body: { name, value, ...(description.trim() ? { description: description.trim() } : {}) },
        });
        this.wipeCreateValue();
        this.createOpen.set(false);
        this.secrets.refresh();
        this.notify.success(
          `Secret ${secret.name} stored`,
          'The value is encrypted and write-only.',
        );
        return undefined;
      } catch (error) {
        const failure = mapSubmissionError(this.createForm, error, {
          codeFields: { DUPLICATE_SECRET_NAME: 'name' },
          overrides: FORBIDDEN,
        });
        this.createError.set(failure.message);
        return failure.fieldErrors;
      }
    });
  }

  /** Drops the plaintext from memory as soon as it is not needed. */
  private wipeCreateValue(): void {
    this.createModel.update((m) => ({ ...m, value: '' }));
  }

  // --- Update value -------------------------------------------------------------------------

  protected readonly updating = signal<SecretMeta | null>(null);
  protected readonly updateOpen = signal(false);
  protected readonly updateModel = signal({ value: '', description: '' });
  protected readonly updateForm = form(this.updateModel, (path) => {
    required(path.value, { message: 'Enter the new value.' });
    maxLength(path.value, 65536, { message: 'The value is too long (64 KiB at most).' });
    maxLength(path.description, 500, { message: 'At most 500 characters.' });
  });
  protected readonly updateError = signal<string | null>(null);

  protected openUpdate(secret: SecretMeta): void {
    this.updating.set(secret);
    this.updateModel.set({ value: '', description: secret.description ?? '' });
    this.updateForm().reset();
    this.updateError.set(null);
    this.updateOpen.set(true);
  }

  protected closeUpdate(): void {
    this.updateOpen.set(false);
    this.updateModel.update((m) => ({ ...m, value: '' }));
  }

  protected onUpdateSubmit(event: Event): void {
    event.preventDefault();
    const companyId = this.current.id();
    const secret = this.updating();
    if (!companyId || !secret) return;
    this.updateError.set(null);
    void submit(this.updateForm, async () => {
      const { value, description } = this.updateModel();
      const body: SecretUpdateRequest = { value };
      if (description.trim() !== (secret.description ?? '')) body.description = description.trim();
      try {
        const updated = await this.api.invoke(updateSecret, {
          companyId,
          secretId: secret.id,
          body,
        });
        this.updateModel.set({ value: '', description: '' });
        this.updateOpen.set(false);
        this.secrets.update((items) => items.map((s) => (s.id === updated.id ? updated : s)));
        this.notify.success(`${updated.name} updated`, `Version ${updated.version} is now active.`);
        return undefined;
      } catch (error) {
        const failure = mapSubmissionError(this.updateForm, error, { overrides: FORBIDDEN });
        this.updateError.set(failure.message);
        return failure.fieldErrors;
      }
    });
  }

  // --- Delete -------------------------------------------------------------------------------

  protected readonly deleting = signal<SecretMeta | null>(null);
  protected readonly deleteOpen = signal(false);
  protected readonly deleteBusy = signal(false);
  protected readonly deleteError = signal<string | null>(null);

  protected askDelete(secret: SecretMeta): void {
    this.deleting.set(secret);
    this.deleteError.set(null);
    this.deleteOpen.set(true);
  }

  protected async confirmDelete(): Promise<void> {
    const companyId = this.current.id();
    const secret = this.deleting();
    if (!companyId || !secret || this.deleteBusy()) return;
    this.deleteBusy.set(true);
    this.deleteError.set(null);
    try {
      await this.api.invoke(deleteSecret, { companyId, secretId: secret.id });
      this.deleteOpen.set(false);
      this.secrets.update((items) => items.filter((s) => s.id !== secret.id));
      this.notify.success(`Secret ${secret.name} deleted`);
    } catch (error) {
      this.deleteError.set(problemMessage(error, FORBIDDEN));
    } finally {
      this.deleteBusy.set(false);
    }
  }
}
