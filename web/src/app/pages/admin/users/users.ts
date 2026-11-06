import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import {
  email,
  form,
  FormField,
  maxLength,
  minLength,
  required,
  submit,
  validate,
} from '@angular/forms/signals';
import { Router } from '@angular/router';
import { NgIcon, provideIcons } from '@ng-icons/core';
import {
  lucideCircleAlert,
  lucideEllipsis,
  lucideKeyRound,
  lucideUserCheck,
  lucideUserPlus,
  lucideUserX,
} from '@ng-icons/lucide';
import { HlmAlertImports } from '@spartan-ng/helm/alert';
import { HlmAlertDialogImports } from '@spartan-ng/helm/alert-dialog';
import { HlmBadge } from '@spartan-ng/helm/badge';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmDialogImports } from '@spartan-ng/helm/dialog';
import { HlmDropdownMenuImports } from '@spartan-ng/helm/dropdown-menu';
import { HlmFieldImports } from '@spartan-ng/helm/field';
import { HlmInput } from '@spartan-ng/helm/input';
import { HlmSpinner } from '@spartan-ng/helm/spinner';
import { HlmSwitchImports } from '@spartan-ng/helm/switch';
import { HlmTableImports } from '@spartan-ng/helm/table';
import { Api } from '../../../api/generated/api';
import {
  createUser,
  listUsers,
  setUserPassword,
  updateUser,
} from '../../../api/generated/functions';
import type { User, UserUpdateRequest } from '../../../api/generated/models';
import { mapSubmissionError, problemMessage } from '../../../api/problem';
import { Notify } from '../../../core/notify';
import { pagedResource } from '../../../core/paged-resource';
import { Session } from '../../../core/session';
import { ErrorState } from '../../../shared/error-state';
import { firstError, initials } from '../../../shared/forms';
import { ListSkeleton } from '../../../shared/list-skeleton';
import { PageHeader } from '../../../shared/page-header';
import { Time } from '../../../shared/time';

const PAGE_SIZE = 100;

/** `hlm-switch` exposes its state as a writable signal, so a failed change can be reverted. */
interface CheckedHolder {
  readonly checked: { set(value: boolean): void };
}

@Component({
  selector: 'app-users',
  imports: [
    FormField,
    NgIcon,
    HlmAlertImports,
    HlmAlertDialogImports,
    HlmBadge,
    HlmButton,
    HlmDialogImports,
    HlmDropdownMenuImports,
    HlmFieldImports,
    HlmInput,
    HlmSpinner,
    HlmSwitchImports,
    HlmTableImports,
    ErrorState,
    ListSkeleton,
    PageHeader,
    Time,
  ],
  providers: [
    provideIcons({
      lucideCircleAlert,
      lucideEllipsis,
      lucideKeyRound,
      lucideUserCheck,
      lucideUserPlus,
      lucideUserX,
    }),
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './users.html',
})
export class Users {
  private readonly api = inject(Api);
  private readonly router = inject(Router);
  private readonly notify = inject(Notify);
  private readonly session = inject(Session);

  protected readonly myId = computed(() => this.session.user()?.id);
  protected readonly firstError = firstError;

  protected readonly users = pagedResource<User, true>({
    params: () => true,
    load: (_, cursor) => this.api.invoke(listUsers, { limit: PAGE_SIZE, cursor }),
  });

  protected readonly busyId = signal<string | null>(null);

  private replace(user: User): void {
    this.users.update((items) => items.map((u) => (u.id === user.id ? user : u)));
  }

  private async patch(user: User, body: UserUpdateRequest): Promise<User> {
    this.busyId.set(user.id);
    try {
      const updated = await this.api.invoke(updateUser, { userId: user.id, body });
      this.replace(updated);
      return updated;
    } finally {
      this.busyId.set(null);
    }
  }

  private readonly lastAdminMessage = {
    LAST_INSTANCE_ADMIN:
      'This is the last active instance admin. Make another user an instance admin first.',
  };

  // --- Instance admin flag ------------------------------------------------------------------

  protected async setInstanceAdmin(
    user: User,
    value: boolean,
    toggle: CheckedHolder,
  ): Promise<void> {
    if (value === user.instanceAdmin) return;
    try {
      const updated = await this.patch(user, { instanceAdmin: value });
      this.notify.success(
        updated.instanceAdmin
          ? `${updated.displayName} is now an instance admin`
          : `${updated.displayName} is no longer an instance admin`,
      );
      if (updated.id === this.myId() && !updated.instanceAdmin) {
        await this.session.refresh();
        await this.router.navigate(['/']);
      }
    } catch (error) {
      toggle.checked.set(user.instanceAdmin);
      this.notify.error(`Couldn't update ${user.displayName}`, error, this.lastAdminMessage);
    }
  }

  // --- Activate / deactivate ----------------------------------------------------------------

  protected readonly deactivating = signal<User | null>(null);
  protected readonly deactivateOpen = signal(false);
  protected readonly deactivateError = signal<string | null>(null);

  protected async activate(user: User): Promise<void> {
    try {
      const updated = await this.patch(user, { active: true });
      this.notify.success(`${updated.displayName} can sign in again`);
    } catch (error) {
      this.notify.error(`Couldn't activate ${user.displayName}`, error);
    }
  }

  protected askDeactivate(user: User): void {
    this.deactivating.set(user);
    this.deactivateError.set(null);
    this.deactivateOpen.set(true);
  }

  protected async confirmDeactivate(): Promise<void> {
    const user = this.deactivating();
    if (!user || this.busyId()) return;
    this.deactivateError.set(null);
    try {
      const updated = await this.patch(user, { active: false });
      this.deactivateOpen.set(false);
      this.notify.success(`${updated.displayName} deactivated`, 'Their sessions have ended.');
    } catch (error) {
      this.deactivateError.set(problemMessage(error, this.lastAdminMessage));
    }
  }

  // --- Create user --------------------------------------------------------------------------

  protected readonly createOpen = signal(false);
  protected readonly createModel = signal({
    email: '',
    displayName: '',
    password: '',
    instanceAdmin: false,
  });
  protected readonly createForm = form(this.createModel, (path) => {
    required(path.email, { message: 'Enter an email.' });
    email(path.email, { message: 'Enter a valid email address.' });
    maxLength(path.email, 320);
    required(path.displayName, { message: 'Enter a name.' });
    maxLength(path.displayName, 100, { message: 'At most 100 characters.' });
    required(path.password, { message: 'Enter an initial password.' });
    minLength(path.password, 12, { message: 'Use at least 12 characters.' });
    maxLength(path.password, 1024);
  });
  protected readonly createError = signal<string | null>(null);

  protected openCreate(): void {
    this.createModel.set({ email: '', displayName: '', password: '', instanceAdmin: false });
    this.createForm().reset();
    this.createError.set(null);
    this.createOpen.set(true);
  }

  protected closeCreate(): void {
    this.createOpen.set(false);
    this.createModel.update((m) => ({ ...m, password: '' }));
  }

  protected setCreateAdmin(value: boolean): void {
    this.createModel.update((m) => ({ ...m, instanceAdmin: value }));
  }

  protected onCreateSubmit(event: Event): void {
    event.preventDefault();
    this.createError.set(null);
    void submit(this.createForm, async () => {
      try {
        const user = await this.api.invoke(createUser, { body: this.createModel() });
        this.closeCreate();
        this.users.refresh();
        this.notify.success(`${user.displayName} created`, 'Share the initial password securely.');
        return undefined;
      } catch (error) {
        const failure = mapSubmissionError(this.createForm, error, {
          codeFields: { DUPLICATE_EMAIL: 'email' },
        });
        this.createError.set(failure.message);
        return failure.fieldErrors;
      }
    });
  }

  // --- Set password -------------------------------------------------------------------------

  protected readonly passwordUser = signal<User | null>(null);
  protected readonly passwordOpen = signal(false);
  protected readonly passwordModel = signal({ newPassword: '', confirmPassword: '' });
  protected readonly passwordForm = form(this.passwordModel, (path) => {
    required(path.newPassword, { message: 'Enter a new password.' });
    minLength(path.newPassword, 12, { message: 'Use at least 12 characters.' });
    maxLength(path.newPassword, 1024);
    required(path.confirmPassword, { message: 'Repeat the password.' });
    validate(path.confirmPassword, ({ value, valueOf }) =>
      value() && value() !== valueOf(path.newPassword)
        ? { kind: 'mismatch', message: "The passwords don't match." }
        : undefined,
    );
  });
  protected readonly passwordError = signal<string | null>(null);

  protected openSetPassword(user: User): void {
    this.passwordUser.set(user);
    this.passwordModel.set({ newPassword: '', confirmPassword: '' });
    this.passwordForm().reset();
    this.passwordError.set(null);
    this.passwordOpen.set(true);
  }

  protected closeSetPassword(): void {
    this.passwordOpen.set(false);
    this.passwordModel.set({ newPassword: '', confirmPassword: '' });
  }

  protected onPasswordSubmit(event: Event): void {
    event.preventDefault();
    const user = this.passwordUser();
    if (!user) return;
    this.passwordError.set(null);
    void submit(this.passwordForm, async () => {
      try {
        await this.api.invoke(setUserPassword, {
          userId: user.id,
          body: { newPassword: this.passwordModel().newPassword },
        });
        this.closeSetPassword();
        this.notify.success(`Password set for ${user.displayName}`);
        return undefined;
      } catch (error) {
        const failure = mapSubmissionError(this.passwordForm, error);
        this.passwordError.set(failure.message);
        return failure.fieldErrors;
      }
    });
  }

  protected initialsOf(name: string): string {
    return initials(name);
  }
}
