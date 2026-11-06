import {
  ChangeDetectionStrategy,
  Component,
  effect,
  inject,
  input,
  model,
  output,
  signal,
  untracked,
} from '@angular/core';
import { form, FormField, maxLength, required, submit } from '@angular/forms/signals';
import { NgIcon, provideIcons } from '@ng-icons/core';
import { lucideCircleAlert } from '@ng-icons/lucide';
import { HlmAlertImports } from '@spartan-ng/helm/alert';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmDialogImports } from '@spartan-ng/helm/dialog';
import { HlmFieldImports } from '@spartan-ng/helm/field';
import { HlmInput } from '@spartan-ng/helm/input';
import { HlmSpinner } from '@spartan-ng/helm/spinner';
import { Api } from '../../../api/generated/api';
import { createServiceIdentity, updateServiceIdentity } from '../../../api/generated/functions';
import type { ServiceIdentity, ServiceIdentityUpdateRequest } from '../../../api/generated/models';
import { mapSubmissionError } from '../../../api/problem';
import { CurrentCompany } from '../../../core/current-company';
import { firstError } from '../../../shared/forms';

const FORBIDDEN = { 'status:403': 'Only owners and admins can manage service accounts.' };

/** What a change sends: only what differs. */
export function identityChanges(
  m: { name: string; description: string },
  identity: ServiceIdentity,
): ServiceIdentityUpdateRequest {
  const body: ServiceIdentityUpdateRequest = {};
  if (m.name.trim() !== identity.name) body.name = m.name.trim();
  if (m.description.trim() !== (identity.description ?? ''))
    body.description = m.description.trim();
  return body;
}

/**
 * Create a service account, or rename and describe one (the web plan §5, Service identities). A
 * new one is active and usable by the Owner alone until groups are given it.
 */
@Component({
  selector: 'app-identity-dialog',
  imports: [
    FormField,
    NgIcon,
    HlmAlertImports,
    HlmButton,
    HlmDialogImports,
    HlmFieldImports,
    HlmInput,
    HlmSpinner,
  ],
  providers: [provideIcons({ lucideCircleAlert })],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <hlm-dialog [state]="open() ? 'open' : 'closed'" (closed)="open.set(false)">
      <hlm-dialog-content
        *hlmDialogPortal="let ctx"
        class="sm:max-w-md"
        data-testid="identity-dialog"
      >
        <hlm-dialog-header>
          <h2 hlmDialogTitle>
            {{ identity() ? 'Change ' + identity()!.name : 'New service account' }}
          </h2>
          <p hlmDialogDescription>
            @if (identity()) {
              What it borrows, who may use it and its budget stay as they are.
            } @else {
              An account of the company rather than a person. It never signs in to Tinfirm: it acts
              only with the credentials systems lend it, which you lend on each system’s page, and
              pays with its own budget.
            }
          </p>
        </hlm-dialog-header>
        <form id="identity-form" class="flex flex-col gap-4" novalidate (submit)="onSubmit($event)">
          @if (formError(); as message) {
            <div hlmAlert variant="destructive">
              <ng-icon name="lucideCircleAlert" aria-hidden="true" />
              <p hlmAlertDescription>{{ message }}</p>
            </div>
          }
          <div hlmField>
            <label hlmFieldLabel for="identity-name">Name</label>
            <input
              hlmInput
              id="identity-name"
              autocomplete="off"
              placeholder="svc-support"
              [formField]="identityForm.name"
            />
            <hlm-field-error>{{ firstError(identityForm.name) }}</hlm-field-error>
          </div>
          <div hlmField>
            <label hlmFieldLabel for="identity-description">Description</label>
            <input
              hlmInput
              id="identity-description"
              autocomplete="off"
              placeholder="What it is for"
              [formField]="identityForm.description"
            />
            <hlm-field-error>{{ firstError(identityForm.description) }}</hlm-field-error>
          </div>
        </form>
        <hlm-dialog-footer>
          <button hlmBtn variant="outline" type="button" hlmDialogClose>Cancel</button>
          <button
            hlmBtn
            type="submit"
            form="identity-form"
            data-testid="identity-save"
            [disabled]="identityForm().submitting()"
          >
            @if (identityForm().submitting()) {
              <hlm-spinner aria-hidden="true" />
            }
            {{ identity() ? 'Save' : 'Create' }}
          </button>
        </hlm-dialog-footer>
      </hlm-dialog-content>
    </hlm-dialog>
  `,
})
export class IdentityDialog {
  private readonly api = inject(Api);
  private readonly current = inject(CurrentCompany);

  readonly open = model(false);
  /** The identity to change; null creates one. */
  readonly identity = input<ServiceIdentity | null>(null);
  readonly saved = output<ServiceIdentity>();

  protected readonly firstError = firstError;
  protected readonly model = signal({ name: '', description: '' });
  protected readonly formError = signal<string | null>(null);
  protected readonly identityForm = form(this.model, (path) => {
    required(path.name, { message: 'Enter a name.' });
    maxLength(path.name, 100, { message: 'At most 100 characters.' });
    maxLength(path.description, 500, { message: 'At most 500 characters.' });
  });

  constructor() {
    effect(() => {
      if (!this.open()) return;
      const identity = this.identity();
      untracked(() => {
        this.model.set({ name: identity?.name ?? '', description: identity?.description ?? '' });
        this.identityForm().reset();
        this.formError.set(null);
      });
    });
  }

  protected onSubmit(event: Event): void {
    event.preventDefault();
    const companyId = this.current.id();
    if (!companyId) return;
    this.formError.set(null);
    void submit(this.identityForm, async () => {
      try {
        const existing = this.identity();
        const m = this.model();
        const saved = existing
          ? await this.api.invoke(updateServiceIdentity, {
              companyId,
              serviceIdentityId: existing.id,
              body: identityChanges(m, existing),
            })
          : await this.api.invoke(createServiceIdentity, {
              companyId,
              body: {
                name: m.name.trim(),
                ...(m.description.trim() ? { description: m.description.trim() } : {}),
              },
            });
        this.open.set(false);
        this.saved.emit(saved);
        return undefined;
      } catch (error) {
        const failure = mapSubmissionError(this.identityForm, error, {
          codeFields: { DUPLICATE_SERVICE_IDENTITY_NAME: 'name' },
          overrides: FORBIDDEN,
        });
        this.formError.set(failure.message);
        return failure.fieldErrors;
      }
    });
  }
}
