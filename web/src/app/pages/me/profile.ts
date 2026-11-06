import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import {
  form,
  FormField,
  maxLength,
  minLength,
  required,
  submit,
  validate,
} from '@angular/forms/signals';
import { RouterLink } from '@angular/router';
import { NgIcon, provideIcons } from '@ng-icons/core';
import { lucideChevronRight, lucideCircleAlert, lucideShieldCheck } from '@ng-icons/lucide';
import { HlmAlertImports } from '@spartan-ng/helm/alert';
import { HlmBadge } from '@spartan-ng/helm/badge';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmCardImports } from '@spartan-ng/helm/card';
import { HlmFieldImports } from '@spartan-ng/helm/field';
import { HlmInput } from '@spartan-ng/helm/input';
import { HlmSpinner } from '@spartan-ng/helm/spinner';
import { Api } from '../../api/generated/api';
import { changeOwnPassword } from '../../api/generated/functions';
import { mapSubmissionError } from '../../api/problem';
import { Notify } from '../../core/notify';
import { Session } from '../../core/session';
import { firstError, initials } from '../../shared/forms';
import { PageHeader } from '../../shared/page-header';
import { RoleBadge } from '../../shared/role-badge';
import { ConnectedApps } from './connected-apps';
import { GmailLinks } from './gmail-links';
import { PersonalTokens } from './personal-tokens';

const EMPTY_PASSWORDS = { currentPassword: '', newPassword: '', confirmPassword: '' };

@Component({
  selector: 'app-profile',
  imports: [
    FormField,
    RouterLink,
    NgIcon,
    HlmAlertImports,
    HlmBadge,
    HlmButton,
    HlmCardImports,
    HlmFieldImports,
    HlmInput,
    HlmSpinner,
    PageHeader,
    ConnectedApps,
    GmailLinks,
    PersonalTokens,
    RoleBadge,
  ],
  providers: [provideIcons({ lucideChevronRight, lucideCircleAlert, lucideShieldCheck })],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="flex flex-col gap-6">
      <app-page-header title="Profile" description="Your account on this Tinfirm instance." />

      @if (user(); as u) {
        <section hlmCard aria-labelledby="account-title">
          <div hlmCardHeader>
            <h2 hlmCardTitle id="account-title">Account</h2>
          </div>
          <div hlmCardContent class="flex flex-col gap-5">
            <div class="flex items-center gap-3">
              <span
                class="bg-muted flex size-10 shrink-0 items-center justify-center rounded-full text-sm font-medium"
                aria-hidden="true"
                >{{ initialsOf(u.displayName) }}</span
              >
              <div class="min-w-0">
                <p class="flex flex-wrap items-center gap-2 font-medium">
                  {{ u.displayName }}
                  @if (u.instanceAdmin) {
                    <span hlmBadge variant="secondary">
                      <ng-icon name="lucideShieldCheck" aria-hidden="true" />
                      Instance admin
                    </span>
                  }
                </p>
                <p class="text-muted-foreground truncate text-sm">{{ u.email }}</p>
              </div>
            </div>
            <dl class="grid grid-cols-1 gap-x-6 gap-y-2 text-sm sm:grid-cols-[10rem_1fr]">
              <dt class="text-muted-foreground">Sign-in method</dt>
              <dd>{{ isLocal() ? 'Email and password' : 'Single sign-on (OIDC)' }}</dd>
              <dt class="text-muted-foreground">User ID</dt>
              <dd>
                <code class="text-xs break-all">{{ u.id }}</code>
              </dd>
            </dl>
          </div>
        </section>

        <section hlmCard aria-labelledby="companies-title">
          <div hlmCardHeader>
            <h2 hlmCardTitle id="companies-title">Companies</h2>
            <p hlmCardDescription>Where you are a member, and your role there.</p>
          </div>
          <div hlmCardContent>
            @if (u.memberships.length === 0) {
              <p class="text-muted-foreground text-sm">You're not a member of any company yet.</p>
            } @else {
              <ul class="-mx-2 flex flex-col">
                @for (m of u.memberships; track m.companyId) {
                  <li>
                    <a
                      [routerLink]="['/c', m.companyId, 'inbox']"
                      class="hover:bg-muted/60 focus-visible:ring-ring/50 flex items-center gap-3 rounded-md px-2 py-2 text-sm outline-none focus-visible:ring-3"
                    >
                      <span class="min-w-0 flex-1 truncate font-medium">{{ m.companyName }}</span>
                      <app-role-badge [role]="m.role" />
                      <ng-icon
                        name="lucideChevronRight"
                        class="text-muted-foreground"
                        aria-hidden="true"
                      />
                    </a>
                  </li>
                }
              </ul>
            }
          </div>
        </section>

        <section hlmCard aria-labelledby="password-title">
          <div hlmCardHeader>
            <h2 hlmCardTitle id="password-title">Password</h2>
            @if (isLocal()) {
              <p hlmCardDescription>Use at least 12 characters. A passphrase works well.</p>
            }
          </div>
          <div hlmCardContent>
            @if (isLocal()) {
              <form
                id="password-form"
                class="flex max-w-md flex-col gap-4"
                novalidate
                (submit)="onSubmit($event)"
              >
                <input
                  type="text"
                  class="hidden"
                  autocomplete="username"
                  [value]="u.email"
                  readonly
                  aria-hidden="true"
                  tabindex="-1"
                />
                @if (formError(); as message) {
                  <div hlmAlert variant="destructive">
                    <ng-icon name="lucideCircleAlert" aria-hidden="true" />
                    <p hlmAlertDescription>{{ message }}</p>
                  </div>
                }
                <div hlmField>
                  <label hlmFieldLabel for="current-password">Current password</label>
                  <input
                    hlmInput
                    id="current-password"
                    type="password"
                    autocomplete="current-password"
                    [formField]="passwordForm.currentPassword"
                  />
                  <hlm-field-error>{{ firstError(passwordForm.currentPassword) }}</hlm-field-error>
                </div>
                <div hlmField>
                  <label hlmFieldLabel for="new-password">New password</label>
                  <input
                    hlmInput
                    id="new-password"
                    type="password"
                    autocomplete="new-password"
                    [formField]="passwordForm.newPassword"
                  />
                  <hlm-field-error>{{ firstError(passwordForm.newPassword) }}</hlm-field-error>
                </div>
                <div hlmField>
                  <label hlmFieldLabel for="confirm-password">Repeat new password</label>
                  <input
                    hlmInput
                    id="confirm-password"
                    type="password"
                    autocomplete="new-password"
                    [formField]="passwordForm.confirmPassword"
                  />
                  <hlm-field-error>{{ firstError(passwordForm.confirmPassword) }}</hlm-field-error>
                </div>
                <div>
                  <button hlmBtn type="submit" [disabled]="passwordForm().submitting()">
                    @if (passwordForm().submitting()) {
                      <hlm-spinner aria-hidden="true" />
                    }
                    Change password
                  </button>
                </div>
              </form>
            } @else {
              <p class="text-muted-foreground text-sm" data-testid="sso-password-note">
                You sign in with single sign-on, so you have no Tinfirm password. Change your
                password with your identity provider.
              </p>
            }
          </div>
        </section>

        <app-connected-apps />
        <app-gmail-links />

        <app-personal-tokens />
      }
    </div>
  `,
})
export class Profile {
  private readonly api = inject(Api);
  private readonly notify = inject(Notify);
  private readonly session = inject(Session);

  protected readonly user = this.session.user;
  protected readonly isLocal = computed(() => this.user()?.authSource === 'LOCAL');
  protected readonly firstError = firstError;

  protected readonly model = signal({ ...EMPTY_PASSWORDS });
  protected readonly passwordForm = form(this.model, (path) => {
    required(path.currentPassword, { message: 'Enter your current password.' });
    maxLength(path.currentPassword, 1024);
    required(path.newPassword, { message: 'Enter a new password.' });
    minLength(path.newPassword, 12, { message: 'Use at least 12 characters.' });
    maxLength(path.newPassword, 1024, { message: 'At most 1024 characters.' });
    validate(path.newPassword, ({ value, valueOf }) =>
      value() && value() === valueOf(path.currentPassword)
        ? { kind: 'same', message: 'Choose a password that differs from the current one.' }
        : undefined,
    );
    required(path.confirmPassword, { message: 'Repeat the new password.' });
    validate(path.confirmPassword, ({ value, valueOf }) =>
      value() && value() !== valueOf(path.newPassword)
        ? { kind: 'mismatch', message: "The passwords don't match." }
        : undefined,
    );
  });
  protected readonly formError = signal<string | null>(null);

  protected onSubmit(event: Event): void {
    event.preventDefault();
    this.formError.set(null);
    void submit(this.passwordForm, async () => {
      const { currentPassword, newPassword } = this.model();
      try {
        await this.api.invoke(changeOwnPassword, { body: { currentPassword, newPassword } });
        this.model.set({ ...EMPTY_PASSWORDS });
        this.passwordForm().reset();
        this.notify.success(
          'Password changed',
          'Your other sessions were signed out, and your personal access tokens and connected apps revoked.',
        );
        return undefined;
      } catch (error) {
        const failure = mapSubmissionError(this.passwordForm, error, {
          codeFields: { WRONG_PASSWORD: 'currentPassword' },
        });
        this.formError.set(failure.message);
        return failure.fieldErrors;
      }
    });
  }

  protected initialsOf(name: string): string {
    return initials(name);
  }
}
