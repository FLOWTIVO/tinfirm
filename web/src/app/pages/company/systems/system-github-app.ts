import { ChangeDetectionStrategy, Component, inject, input, signal } from '@angular/core';
import { NgIcon, provideIcons } from '@ng-icons/core';
import { lucideCircleAlert, lucideKeyRound, lucideTrash2 } from '@ng-icons/lucide';
import { HlmAlertImports } from '@spartan-ng/helm/alert';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmFieldImports } from '@spartan-ng/helm/field';
import { HlmInput } from '@spartan-ng/helm/input';
import { HlmSpinner } from '@spartan-ng/helm/spinner';
import { Api } from '../../../api/generated/api';
import {
  getSystemGitHubApp,
  listSystemGitHubAppInstallations,
  removeSystemGitHubApp,
  setSystemGitHubApp,
} from '../../../api/generated/functions';
import type {
  ExternalSystem,
  GitHubApp,
  GitHubAppInstallation,
} from '../../../api/generated/models';
import { isStatus, problemMessage } from '../../../api/problem';

const FORBIDDEN = { 'status:403': 'Only owners and admins can change the company’s systems.' };

/** What the App's page says to give it at GitHub, and never to give (ADR-0116). */
export function permissionLine(app: Pick<GitHubApp, 'permissions'>): string {
  const give = app.permissions
    .filter((p) => p.access !== 'NEVER')
    .map((p) => `${p.name} (${p.access === 'WRITE' ? 'read and write' : 'read'})`);
  const never = app.permissions.filter((p) => p.access === 'NEVER').map((p) => p.name);
  return `Give it ${give.join(', ')}${never.length ? `; never ${never.join(', ')}` : ''}.`;
}

/** How many connected hosts cannot renew the App's tokens, in words; null when all can. */
export function hostsLine(
  app: Pick<GitHubApp, 'hostsConnected' | 'hostsWithoutAppTokens'>,
): string | null {
  if (app.hostsWithoutAppTokens === 0) return null;
  const n = app.hostsWithoutAppTokens;
  return `${n} of the ${app.hostsConnected} connected runner hosts cannot renew the App’s tokens: a run on an installation waits for a host that can. Upgrade them.`;
}

/**
 * A GitHub system's GitHub App (ADR-0116): the company's own App, its id and private key (write-
 * only, rotated by setting it again), what to give it at GitHub, its installations, and how many
 * runner hosts cannot renew its tokens yet. An installation becomes a credential with Add
 * credential. Works on github.com and on GitHub Enterprise Server.
 */
@Component({
  selector: 'app-system-github-app',
  imports: [NgIcon, HlmAlertImports, HlmButton, HlmFieldImports, HlmInput, HlmSpinner],
  providers: [provideIcons({ lucideCircleAlert, lucideKeyRound, lucideTrash2 })],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'block' },
  template: `
    <section
      class="bg-card overflow-hidden rounded-xl border"
      aria-labelledby="github-app-title"
      data-testid="system-github-app"
    >
      <div class="flex flex-wrap items-start gap-3 px-4 py-4 sm:px-5 sm:pt-5">
        <div class="flex min-w-0 flex-1 basis-64 flex-col gap-0.5">
          <h2 id="github-app-title" class="text-base font-bold">GitHub App</h2>
          <p class="text-muted-foreground text-sm text-pretty" data-testid="github-app-state">
            @if (app(); as a) {
              {{ a.name ?? a.slug ?? 'The App' }} (id {{ a.appId }}) mints each run a token of its
              own, for the run’s repositories, valid an hour.
            } @else if (loaded()) {
              No App yet: runs use members’ own accounts and the credentials’ tokens.
            }
          </p>
        </div>
        @if (canManage() && loaded()) {
          <span class="-me-2 flex flex-wrap gap-1">
            <button
              hlmBtn
              variant="outline"
              size="sm"
              class="max-sm:h-11"
              type="button"
              data-testid="github-app-edit"
              (click)="editing.set(!editing())"
            >
              <ng-icon name="lucideKeyRound" aria-hidden="true" />
              {{ app() ? 'Replace the key' : 'Set up the App' }}
            </button>
            @if (app()) {
              <button
                hlmBtn
                variant="ghost"
                size="sm"
                class="max-sm:h-11"
                type="button"
                data-testid="github-app-remove"
                [disabled]="busy()"
                (click)="remove()"
              >
                <ng-icon name="lucideTrash2" aria-hidden="true" />
                Remove the App
              </button>
            }
          </span>
        }
      </div>

      @if (error(); as message) {
        <div
          hlmAlert
          variant="destructive"
          class="mx-4 mb-4 sm:mx-5"
          data-testid="github-app-error"
        >
          <ng-icon name="lucideCircleAlert" aria-hidden="true" />
          <p hlmAlertDescription class="break-words">{{ message }}</p>
        </div>
      }

      @if (app(); as a) {
        <div class="flex flex-col gap-2 border-t px-4 py-4 text-sm sm:px-5">
          <p class="text-pretty" data-testid="github-app-permissions">{{ permissions(a) }}</p>
          @if (hosts(a); as line) {
            <p class="text-human text-pretty" data-testid="github-app-hosts">{{ line }}</p>
          }
          <p class="text-muted-foreground">Key stored · never shown again</p>
          @if (installations(); as list) {
            <ul
              class="flex flex-col gap-1"
              aria-label="Installations"
              data-testid="github-app-installations"
            >
              @for (i of list; track i.id) {
                <li>
                  <span class="font-semibold">{{ i.accountLogin }}</span>
                  <span class="text-muted-foreground">
                    · {{ i.accountType }}, {{ i.repositorySelection }} repositories</span
                  >
                  @if (i.credentialName) {
                    <span class="text-muted-foreground"> · credential {{ i.credentialName }}</span>
                  }
                </li>
              } @empty {
                <li class="text-muted-foreground">
                  Not installed anywhere yet: install it on GitHub.
                </li>
              }
            </ul>
          }
        </div>
      }

      @if (editing()) {
        <form
          class="flex flex-col gap-4 border-t px-4 py-4 sm:px-5"
          novalidate
          data-testid="github-app-form"
          (submit)="save($event)"
        >
          <div hlmField>
            <label hlmFieldLabel for="github-app-id">App ID</label>
            <input
              hlmInput
              id="github-app-id"
              inputmode="numeric"
              autocomplete="off"
              data-testid="github-app-id"
              [value]="appId()"
              (input)="appId.set($any($event.target).value)"
            />
            <p hlmFieldDescription>From the App’s settings page at GitHub.</p>
          </div>
          <div hlmField>
            <label hlmFieldLabel for="github-app-key">Private key</label>
            <textarea
              hlmInput
              id="github-app-key"
              rows="5"
              class="font-mono text-xs"
              autocomplete="off"
              spellcheck="false"
              data-testid="github-app-key"
              [value]="privateKey()"
              (input)="privateKey.set($any($event.target).value)"
            ></textarea>
            <p hlmFieldDescription>
              The .pem file GitHub generated for the App. Tinfirm asks GitHub for the App with it,
              then keeps it in the vault; nobody sees it again.
            </p>
          </div>
          <div class="flex justify-end gap-2">
            <button hlmBtn variant="outline" type="button" (click)="editing.set(false)">
              Cancel
            </button>
            <button hlmBtn type="submit" data-testid="github-app-save" [disabled]="busy()">
              @if (busy()) {
                <hlm-spinner aria-hidden="true" />
              }
              Check and save
            </button>
          </div>
        </form>
      }
    </section>
  `,
})
export class SystemGitHubApp {
  private readonly api = inject(Api);

  readonly companyId = input.required<string>();
  readonly system = input.required<ExternalSystem>();
  readonly canManage = input(false);

  protected readonly app = signal<GitHubApp | null>(null);
  protected readonly installations = signal<GitHubAppInstallation[] | null>(null);
  protected readonly loaded = signal(false);
  protected readonly editing = signal(false);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly appId = signal('');
  protected readonly privateKey = signal('');

  protected readonly permissions = permissionLine;
  protected readonly hosts = hostsLine;

  constructor() {
    queueMicrotask(() => void this.load());
  }

  private key(): { companyId: string; systemId: string } {
    return { companyId: this.companyId(), systemId: this.system().id };
  }

  protected async load(): Promise<void> {
    // The system's view says whether it holds an App; without one there is nothing to read.
    if (!this.canManage() || !this.system().githubApp) {
      this.app.set(null);
      this.loaded.set(true);
      return;
    }
    try {
      const app = await this.api.invoke(getSystemGitHubApp, this.key());
      this.app.set(app);
      this.loaded.set(true);
      await this.loadInstallations();
    } catch (error) {
      this.app.set(null);
      this.loaded.set(true);
      if (!isStatus(error, 404)) this.error.set(problemMessage(error, FORBIDDEN));
    }
  }

  private async loadInstallations(): Promise<void> {
    try {
      const list = await this.api.invoke(listSystemGitHubAppInstallations, this.key());
      this.installations.set(list.items);
    } catch (error) {
      this.error.set(problemMessage(error, FORBIDDEN));
    }
  }

  protected async save(event: Event): Promise<void> {
    event.preventDefault();
    const appId = Number(this.appId().trim());
    if (!Number.isInteger(appId) || appId <= 0 || !this.privateKey().trim()) {
      this.error.set('Give the App’s id and its private key.');
      return;
    }
    this.busy.set(true);
    this.error.set(null);
    try {
      const app = await this.api.invoke(setSystemGitHubApp, {
        ...this.key(),
        body: { appId, privateKey: this.privateKey() },
      });
      this.privateKey.set('');
      this.app.set(app);
      this.editing.set(false);
      await this.loadInstallations();
    } catch (error) {
      this.error.set(problemMessage(error, FORBIDDEN));
    } finally {
      this.busy.set(false);
    }
  }

  protected async remove(): Promise<void> {
    this.busy.set(true);
    this.error.set(null);
    try {
      await this.api.invoke(removeSystemGitHubApp, this.key());
      this.app.set(null);
      this.installations.set(null);
    } catch (error) {
      this.error.set(problemMessage(error, FORBIDDEN));
    } finally {
      this.busy.set(false);
    }
  }
}
