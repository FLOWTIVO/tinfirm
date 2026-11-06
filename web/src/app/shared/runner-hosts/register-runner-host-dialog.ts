import {
  ChangeDetectionStrategy,
  Component,
  computed,
  DOCUMENT,
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
import { lucideCircleAlert, lucideTriangleAlert } from '@ng-icons/lucide';
import { HlmAlertImports } from '@spartan-ng/helm/alert';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmDialogImports } from '@spartan-ng/helm/dialog';
import { HlmFieldImports } from '@spartan-ng/helm/field';
import { HlmInput } from '@spartan-ng/helm/input';
import { HlmSpinner } from '@spartan-ng/helm/spinner';
import { Api } from '../../api/generated/api';
import { registerRunnerHost } from '../../api/generated/functions';
import type { RunnerHost, RunnerHostScope } from '../../api/generated/models';
import { mapSubmissionError } from '../../api/problem';
import { CopyButton } from '../copy-button';
import { firstError } from '../forms';

/** As the API takes it (`RunnerHostCreateRequest.name`). */
const HOST_NAME = /^[A-Za-z0-9][A-Za-z0-9._-]*$/;

/**
 * The server's base URL for `tinfirm-runner-host login`, from the registration's `connectUrl` (its
 * WebSocket scheme becomes the matching HTTP scheme, and the path is dropped), else the page's
 * origin.
 */
export function serverOrigin(connectUrl: string | null | undefined, fallback: string): string {
  if (!connectUrl) return fallback;
  try {
    const url = new URL(connectUrl);
    const protocol =
      url.protocol === 'wss:' ? 'https:' : url.protocol === 'ws:' ? 'http:' : url.protocol;
    return `${protocol}//${url.host}`;
  } catch {
    return fallback;
  }
}

export interface SetupStep {
  readonly note: string;
  readonly command: string;
}

/**
 * The commands to run on the new host, in order (runner/README.md). A personal host adds its
 * owner's subscriptions; a company host holds no credentials (package 2).
 */
export function setupSteps(scope: RunnerHostScope, origin: string, token: string): SetupStep[] {
  const steps: SetupStep[] = [
    {
      note: 'Connect the host to this server.',
      command: `tinfirm-runner-host login --server ${origin} --token ${token}`,
    },
  ];
  if (scope === 'PERSONAL') {
    steps.push(
      {
        note: 'Add your Claude subscription: it runs Claude Code\'s own sign-in (claude auth login) into a directory of the host.',
        command: 'tinfirm-runner-host credential add claude --label claude-max',
      },
      {
        note: 'Or add your ChatGPT subscription for Codex: it runs codex login.',
        command: 'tinfirm-runner-host credential add codex --label chatgpt',
      },
    );
  }
  steps.push(
    { note: 'Build the runner images.', command: 'tinfirm-runner-host images build' },
    {
      note: 'Check Docker, the images, the credentials and the connection.',
      command: 'tinfirm-runner-host doctor',
    },
    { note: 'Start serving runs.', command: 'tinfirm-runner-host run' },
  );
  return steps;
}

interface Registered {
  readonly host: RunnerHost;
  readonly token: string;
  readonly origin: string;
}

/**
 * Registers a runner host, a member's own (`PERSONAL`, the default) or, for OWNER and ADMIN, the
 * company's, and shows its token exactly once with the commands that set the host up. The token
 * lives only in this dialog's memory and is dropped when the dialog closes.
 */
@Component({
  selector: 'app-register-runner-host-dialog',
  imports: [
    FormField,
    NgIcon,
    HlmAlertImports,
    HlmButton,
    HlmDialogImports,
    HlmFieldImports,
    HlmInput,
    HlmSpinner,
    CopyButton,
  ],
  providers: [provideIcons({ lucideCircleAlert, lucideTriangleAlert })],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <hlm-dialog [state]="open() ? 'open' : 'closed'" (closed)="close()">
      <hlm-dialog-content
        *hlmDialogPortal="let ctx"
        class="max-h-[92svh] overflow-y-auto sm:max-w-2xl"
      >
        @if (result(); as r) {
          <hlm-dialog-header>
            <h2 hlmDialogTitle>Set up {{ r.host.name }}</h2>
            <p hlmDialogDescription>
              Run these commands on the machine. It needs Docker, and the runner host from the
              Tinfirm release.
            </p>
          </hlm-dialog-header>

          <div hlmAlert class="border-warning/50" data-testid="token-once">
            <ng-icon name="lucideTriangleAlert" class="text-warning" aria-hidden="true" />
            <h3 hlmAlertTitle>Copy the token now</h3>
            <p hlmAlertDescription>
              This is the only time Tinfirm shows it; the server keeps only its hash. If you lose
              it, remove the host and register it again.
            </p>
          </div>

          <div class="flex min-w-0 flex-col gap-1.5">
            <span class="text-sm font-semibold" id="runner-token-label">Host token</span>
            <div class="bg-muted/50 flex items-center gap-2 rounded-lg border px-3 py-2">
              <code
                class="min-w-0 flex-1 font-mono text-xs break-all select-all"
                aria-labelledby="runner-token-label"
                data-testid="runner-token"
                >{{ r.token }}</code
              >
              <app-copy-button
                [value]="r.token"
                ariaLabel="Copy the token"
                label="Copy"
                variant="outline"
              />
            </div>
          </div>

          <div class="flex min-w-0 flex-col gap-1.5">
            <div class="flex items-center justify-between gap-2">
              <span class="text-sm font-semibold">Next steps</span>
              <app-copy-button
                [value]="commandText()"
                ariaLabel="Copy all the commands"
                label="Copy all"
              />
            </div>
            <ol class="flex flex-col gap-1.5 text-xs">
              @for (step of steps(); track $index) {
                <li class="flex min-w-0 flex-col gap-1">
                  <span class="text-muted-foreground">{{ $index + 1 }}. {{ step.note }}</span>
                  <div class="bg-muted/50 flex items-start gap-2 rounded-lg border px-2.5 py-1.5">
                    <code class="min-w-0 flex-1 font-mono break-all">{{ step.command }}</code>
                    <app-copy-button [value]="step.command" ariaLabel="Copy the command" />
                  </div>
                </li>
              }
            </ol>
          </div>

          <hlm-dialog-footer>
            <button hlmBtn type="button" data-testid="runner-host-done" hlmDialogClose>Done</button>
          </hlm-dialog-footer>
        } @else {
          <hlm-dialog-header>
            <h2 hlmDialogTitle>
              {{
                scope() === 'PERSONAL'
                  ? 'Register a runner host of yours'
                  : 'Register a company runner host'
              }}
            </h2>
            <p hlmDialogDescription>
              @if (scope() === 'PERSONAL') {
                A machine of yours with Docker. It runs only your own conversations, paid by your
                Claude or ChatGPT subscription on it; the subscription never leaves the machine.
              } @else {
                A machine with Docker that runs any member’s conversations, paid by their budgets.
                It holds no credentials of its own.
              }
            </p>
          </hlm-dialog-header>

          <form
            id="register-runner-host-form"
            class="flex flex-col gap-4"
            novalidate
            (submit)="onSubmit($event)"
          >
            @if (formError(); as message) {
              <div hlmAlert variant="destructive" data-testid="register-error">
                <ng-icon name="lucideCircleAlert" aria-hidden="true" />
                <p hlmAlertDescription>{{ message }}</p>
              </div>
            }
            <div hlmField>
              <label hlmFieldLabel for="runner-host-name">Name</label>
              <input
                hlmInput
                id="runner-host-name"
                class="font-mono"
                autocomplete="off"
                spellcheck="false"
                [placeholder]="scope() === 'PERSONAL' ? 'my-laptop' : 'build-01'"
                [formField]="registerForm.name"
              />
              <p hlmFieldDescription>Letters, digits, dots, dashes and underscores.</p>
              <hlm-field-error>{{ firstError(registerForm.name) }}</hlm-field-error>
            </div>
          </form>

          <hlm-dialog-footer>
            <button hlmBtn variant="outline" type="button" hlmDialogClose>Cancel</button>
            <button
              hlmBtn
              type="submit"
              form="register-runner-host-form"
              data-testid="register-runner-host"
              [disabled]="registerForm().submitting()"
            >
              @if (registerForm().submitting()) {
                <hlm-spinner aria-hidden="true" />
              }
              Register
            </button>
          </hlm-dialog-footer>
        }
      </hlm-dialog-content>
    </hlm-dialog>
  `,
})
export class RegisterRunnerHostDialog {
  private readonly api = inject(Api);
  private readonly document = inject(DOCUMENT);

  readonly open = model(false);
  readonly companyId = input.required<string>();
  readonly scope = input<RunnerHostScope>('PERSONAL');
  readonly registered = output<RunnerHost>();

  protected readonly firstError = firstError;
  protected readonly model = signal({ name: '' });
  protected readonly registerForm = form(this.model, (path) => {
    required(path.name, { message: 'Enter a name.' });
    maxLength(path.name, 100, { message: 'At most 100 characters.' });
    pattern(path.name, HOST_NAME, {
      message: 'Start with a letter or digit; then letters, digits, dots, dashes, underscores.',
    });
  });
  protected readonly formError = signal<string | null>(null);

  /** Set after a registration; cleared when the dialog closes. */
  protected readonly result = signal<Registered | null>(null);

  protected readonly steps = computed(() => {
    const r = this.result();
    return r ? setupSteps(this.scope(), r.origin, r.token) : [];
  });
  protected readonly commandText = computed(() =>
    this.steps()
      .map((s) => s.command)
      .join('\n'),
  );

  constructor() {
    effect(() => {
      if (!this.open()) return;
      untracked(() => {
        this.result.set(null);
        this.model.set({ name: '' });
        this.registerForm().reset();
        this.formError.set(null);
      });
    });
  }

  protected close(): void {
    this.open.set(false);
    // Forget the token: it is never shown again.
    this.result.set(null);
  }

  protected onSubmit(event: Event): void {
    event.preventDefault();
    this.formError.set(null);
    void submit(this.registerForm, async () => {
      try {
        const registration = await this.api.invoke(registerRunnerHost, {
          companyId: this.companyId(),
          body: { name: this.model().name.trim(), scope: this.scope() },
        });
        this.result.set({
          host: registration.runnerHost,
          token: registration.token,
          origin: serverOrigin(registration.connectUrl, this.document.location.origin),
        });
        this.registered.emit(registration.runnerHost);
        return undefined;
      } catch (error) {
        const failure = mapSubmissionError(this.registerForm, error, {
          codeFields: { DUPLICATE_RUNNER_HOST_NAME: 'name' },
          overrides: { 'status:403': 'Only owners and admins register company runner hosts.' },
        });
        this.formError.set(failure.message);
        return failure.fieldErrors;
      }
    });
  }
}
