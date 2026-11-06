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
import { form, FormField, maxLength, pattern, required, submit } from '@angular/forms/signals';
import { NgIcon, provideIcons } from '@ng-icons/core';
import { lucideCircleAlert } from '@ng-icons/lucide';
import { HlmAlertImports } from '@spartan-ng/helm/alert';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmDialogImports } from '@spartan-ng/helm/dialog';
import { HlmFieldImports } from '@spartan-ng/helm/field';
import { HlmInput } from '@spartan-ng/helm/input';
import { HlmSpinner } from '@spartan-ng/helm/spinner';
import { Api } from '../../../api/generated/api';
import { createSystem } from '../../../api/generated/functions';
import type { ExternalSystem, SystemCreateRequest } from '../../../api/generated/models';
import { mapSubmissionError, toProblem } from '../../../api/problem';
import { firstError } from '../../../shared/forms';

interface ServerModel {
  name: string;
  baseUrl: string;
  authHeader: string;
}

const EMPTY: ServerModel = { name: '', baseUrl: '', authHeader: '' };

/** An HTTP header's name (RFC 9110's token), for the header a stored token goes in. */
const HEADER_NAME = /^[!#$%&'*+.^_`|~0-9A-Za-z-]+$/;

/** Why the outbound guard refused the address, without the server's field name. */
function addressRefusal(error: unknown): string {
  const why = toProblem(error)
    .detail?.replace(/^baseUrl is not usable:\s*/i, '')
    .trim();
  return why
    ? `Tinfirm may not call this address: ${why.replace(/[.!?]?$/, '.')}`
    : 'Tinfirm may not call this address.';
}

/**
 * Registers the reader's own MCP server (`createSystem` with `personal`, CONVENTIONS §15.2): only
 * they see it, and it binds only for their conversations. The server reaches it from Tinfirm, so
 * its address must be public https; the outbound guard refuses anything else
 * (`400 INVALID_BASE_URL`, said under the field). The company may switch personal servers off
 * (`409 PERSONAL_SYSTEMS_OFF`).
 */
@Component({
  selector: 'app-add-mcp-server-dialog',
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
      <hlm-dialog-content *hlmDialogPortal="let ctx" class="sm:max-w-lg">
        <hlm-dialog-header>
          <h2 hlmDialogTitle>Add your MCP server</h2>
          <p hlmDialogDescription>
            Only you see it, and only your own conversations use it, with your own account on it.
            Tinfirm calls it from the server, so its address must be public.
          </p>
        </hlm-dialog-header>

        <form
          id="mcp-server-form"
          class="flex flex-col gap-4"
          novalidate
          (submit)="onSubmit($event)"
        >
          @if (formError(); as message) {
            <div hlmAlert variant="destructive" data-testid="mcp-server-error">
              <ng-icon name="lucideCircleAlert" aria-hidden="true" />
              <p hlmAlertDescription class="break-words">{{ message }}</p>
            </div>
          }
          <div hlmField>
            <label hlmFieldLabel for="mcp-server-name">Name</label>
            <input
              hlmInput
              id="mcp-server-name"
              autocomplete="off"
              placeholder="My notes"
              [formField]="serverForm.name"
            />
            <hlm-field-error>{{ firstError(serverForm.name) }}</hlm-field-error>
          </div>
          <div hlmField>
            <label hlmFieldLabel for="mcp-server-url">Address</label>
            <input
              hlmInput
              id="mcp-server-url"
              type="url"
              class="font-mono"
              autocomplete="off"
              spellcheck="false"
              placeholder="https://mcp.example.com/mcp"
              [formField]="serverForm.baseUrl"
            />
            <p hlmFieldDescription>The server’s MCP endpoint (streamable HTTP), over https.</p>
            <hlm-field-error>{{ firstError(serverForm.baseUrl) }}</hlm-field-error>
          </div>
          <div hlmField>
            <label hlmFieldLabel for="mcp-server-header">Header for your token (optional)</label>
            <input
              hlmInput
              id="mcp-server-header"
              class="font-mono"
              autocomplete="off"
              spellcheck="false"
              placeholder="Authorization"
              [formField]="serverForm.authHeader"
            />
            <p hlmFieldDescription>
              Empty: your token goes as <code class="font-mono">Authorization: Bearer</code>. Name
              another header if the server wants the token there.
            </p>
            <hlm-field-error>{{ firstError(serverForm.authHeader) }}</hlm-field-error>
          </div>
        </form>

        <hlm-dialog-footer>
          <button hlmBtn variant="outline" type="button" hlmDialogClose>Cancel</button>
          <button
            hlmBtn
            type="submit"
            form="mcp-server-form"
            data-testid="save-mcp-server"
            [disabled]="serverForm().submitting()"
          >
            @if (serverForm().submitting()) {
              <hlm-spinner aria-hidden="true" />
            }
            Add server
          </button>
        </hlm-dialog-footer>
      </hlm-dialog-content>
    </hlm-dialog>
  `,
})
export class AddMcpServerDialog {
  private readonly api = inject(Api);

  readonly open = model(false);
  readonly companyId = input.required<string>();
  readonly created = output<ExternalSystem>();

  protected readonly firstError = firstError;
  protected readonly model = signal<ServerModel>({ ...EMPTY });
  protected readonly serverForm = form(this.model, (path) => {
    required(path.name, { message: 'Name the server.' });
    maxLength(path.name, 100, { message: 'At most 100 characters.' });
    required(path.baseUrl, { message: 'Enter the server’s address.' });
    maxLength(path.baseUrl, 500, { message: 'At most 500 characters.' });
    pattern(path.baseUrl, /^\s*https?:\/\/\S+$/i, {
      message: 'Enter the address with its scheme, e.g. https://mcp.example.com/mcp.',
    });
    maxLength(path.authHeader, 64, { message: 'At most 64 characters.' });
    pattern(path.authHeader, HEADER_NAME, {
      message: 'Letters, digits and dashes, e.g. X-Api-Key.',
    });
  });
  protected readonly formError = signal<string | null>(null);

  constructor() {
    effect(() => {
      if (!this.open()) return;
      untracked(() => {
        this.model.set({ ...EMPTY });
        this.serverForm().reset();
        this.formError.set(null);
      });
    });
  }

  protected onSubmit(event: Event): void {
    event.preventDefault();
    this.formError.set(null);
    void submit(this.serverForm, async () => {
      const value = this.model();
      const body: SystemCreateRequest = {
        name: value.name.trim(),
        kind: 'MCP',
        personal: true,
        baseUrl: value.baseUrl.trim(),
        ...(value.authHeader.trim() ? { authHeader: value.authHeader.trim() } : {}),
      };
      try {
        const system = await this.api.invoke(createSystem, { companyId: this.companyId(), body });
        this.open.set(false);
        this.created.emit(system);
        return undefined;
      } catch (error) {
        const failure = mapSubmissionError(this.serverForm, error, {
          codeFields: {
            DUPLICATE_SYSTEM_NAME: 'name',
            INVALID_BASE_URL: 'baseUrl',
          },
          overrides: {
            INVALID_BASE_URL: addressRefusal(error),
            DUPLICATE_SYSTEM_NAME: 'A system with this name already exists. Choose another name.',
          },
        });
        this.formError.set(failure.message);
        return failure.fieldErrors;
      }
    });
  }
}
