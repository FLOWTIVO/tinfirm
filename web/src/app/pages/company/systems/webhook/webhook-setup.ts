import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import { NgIcon, provideIcons } from '@ng-icons/core';
import {
  lucideCircleAlert,
  lucidePencil,
  lucideRefreshCw,
  lucideShieldAlert,
} from '@ng-icons/lucide';
import { HlmAlertImports } from '@spartan-ng/helm/alert';
import { HlmAlertDialogImports } from '@spartan-ng/helm/alert-dialog';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmDialogImports } from '@spartan-ng/helm/dialog';
import { HlmSpinner } from '@spartan-ng/helm/spinner';
import { Api } from '../../../../api/generated/api';
import { rotateWebhookSecret } from '../../../../api/generated/functions';
import type { ExternalSystem, WebhookSecret as Rotated } from '../../../../api/generated/models';
import { problemMessage } from '../../../../api/problem';
import { CopyButton } from '../../../../shared/copy-button';
import { formatAbsolute, Time } from '../../../../shared/time';
import { senderInstructions, WebhookSecret } from './webhook-secret';

/** A secret older than this is due for a rotation (the contract's 90 days). */
const ROTATE_AFTER_DAYS = 90;

/** Whether a webhook's secret is due for a rotation: set more than 90 days ago. */
export function secretDue(secretSetAt: string | null | undefined, now: Date = new Date()): boolean {
  if (!secretSetAt) return false;
  return now.getTime() - new Date(secretSetAt).getTime() > ROTATE_AFTER_DAYS * 86_400_000;
}

/**
 * A webhook system's setup (package 5, §5.12): the URL its sender posts to, how the sender proves a
 * delivery is theirs (the secret header marked weaker), its deliveries a minute, and its secret's
 * age with Rotate, the new secret shown once while the previous one works for 24 hours.
 */
@Component({
  selector: 'app-webhook-setup',
  imports: [
    NgIcon,
    HlmAlertImports,
    HlmAlertDialogImports,
    HlmButton,
    HlmDialogImports,
    HlmSpinner,
    CopyButton,
    Time,
    WebhookSecret,
  ],
  providers: [
    provideIcons({ lucideCircleAlert, lucidePencil, lucideRefreshCw, lucideShieldAlert }),
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'block' },
  template: `
    @let w = system().webhook!;
    <section
      class="bg-card overflow-hidden rounded-xl border"
      aria-labelledby="webhook-setup-title"
      data-testid="webhook-setup"
      [attr.data-scheme]="w.scheme"
    >
      <div class="flex flex-wrap items-start gap-3 px-4 py-4 sm:px-5 sm:pt-5">
        <div class="flex min-w-0 flex-1 basis-64 flex-col gap-0.5">
          <h2 id="webhook-setup-title" class="text-base font-bold">The sender</h2>
          <p class="text-muted-foreground text-sm text-pretty">
            Point the other software at this URL. Each delivery fires the webhook triggers whose
            events and conditions it matches; nothing else reads it.
          </p>
        </div>
        @if (canManage()) {
          <button
            hlmBtn
            variant="ghost"
            size="sm"
            type="button"
            class="max-sm:h-11 -me-2"
            data-testid="change-webhook"
            (click)="changeSettings.emit()"
          >
            <ng-icon name="lucidePencil" aria-hidden="true" />
            Change
          </button>
        }
      </div>

      <div
        class="grid gap-x-8 gap-y-1 border-t px-4 py-3.5 sm:grid-cols-[minmax(0,15rem)_minmax(0,1fr)] sm:px-5"
      >
        <div class="min-w-0">
          <span class="block text-sm font-semibold" id="webhook-url-label">Delivery URL</span>
          <p class="text-muted-foreground mt-0.5 text-[0.8125rem] text-pretty">
            POST, JSON, at most 1 MiB. {{ instructions() }}
          </p>
        </div>
        <div class="flex min-w-0 items-start gap-1.5">
          <code
            class="bg-muted/50 min-w-0 flex-1 rounded-lg border px-2.5 py-1.5 font-mono text-xs break-all select-all"
            aria-labelledby="webhook-url-label"
            data-testid="webhook-url"
            >{{ w.url }}</code
          >
          <app-copy-button [value]="w.url" ariaLabel="Copy the delivery URL" />
        </div>
      </div>

      @if (w.scheme === 'SECRET_HEADER') {
        <div
          class="bg-muted/50 flex items-start gap-2 border-t px-4 py-3 text-sm sm:px-5"
          data-testid="webhook-weaker"
        >
          <ng-icon
            name="lucideShieldAlert"
            class="text-warning mt-0.5 shrink-0"
            aria-hidden="true"
          />
          <p class="text-pretty">
            <strong class="font-semibold">The weaker scheme.</strong>
            Whoever captures one delivery can send any until the secret is rotated, and an old one
            can be sent again. Use signed deliveries if the sender can sign.
          </p>
        </div>
      }

      <dl>
        <div
          class="grid gap-x-8 gap-y-1 border-t px-4 py-3.5 sm:grid-cols-[minmax(0,15rem)_minmax(0,1fr)] sm:px-5"
          data-testid="webhook-scheme-shown"
        >
          <dt class="text-sm font-semibold">Proof</dt>
          <dd class="text-sm">
            {{
              w.scheme === 'SECRET_HEADER'
                ? 'Secret in the ' + (w.secretHeader || 'X-Tinfirm-Secret') + ' header'
                : 'Signed (Standard Webhooks)'
            }}
          </dd>
        </div>
        <div
          class="grid gap-x-8 gap-y-1 border-t px-4 py-3.5 sm:grid-cols-[minmax(0,15rem)_minmax(0,1fr)] sm:px-5"
          data-testid="webhook-rate"
        >
          <dt class="text-sm font-semibold">Deliveries a minute</dt>
          <dd class="text-sm">
            {{ w.perMinute }}
            @if (w.maxPerMinute === null || w.maxPerMinute === undefined) {
              <span class="text-muted-foreground">(the company's default)</span>
            }
          </dd>
        </div>
        <div
          class="grid gap-x-8 gap-y-1 border-t px-4 py-3.5 sm:grid-cols-[minmax(0,15rem)_minmax(0,1fr)] sm:px-5"
          data-testid="webhook-secret-age"
        >
          <dt class="text-sm font-semibold">Secret</dt>
          <dd class="flex flex-wrap items-start justify-between gap-x-4 gap-y-2 text-sm">
            <span>
              @if (w.secretSetAt; as at) {
                Set <app-time [value]="at" />
                @if (due()) {
                  <span class="text-warning font-semibold">· rotate it: older than 90 days</span>
                }
              } @else {
                Set
              }
              @if (w.previousUntil; as until) {
                <span class="text-muted-foreground block"
                  >The previous one works until <app-time [value]="until" mode="absolute"
                /></span>
              }
            </span>
            @if (canManage()) {
              <button
                hlmBtn
                variant="outline"
                size="sm"
                class="max-sm:h-11"
                type="button"
                data-testid="rotate-secret"
                (click)="openRotate()"
              >
                <ng-icon name="lucideRefreshCw" aria-hidden="true" />
                Rotate the secret
              </button>
            }
          </dd>
        </div>
      </dl>
    </section>

    <hlm-alert-dialog [state]="rotateOpen() ? 'open' : 'closed'" (closed)="rotateOpen.set(false)">
      <hlm-alert-dialog-content *hlmAlertDialogPortal="let ctx">
        <hlm-alert-dialog-header>
          <h2 hlmAlertDialogTitle>Rotate the secret of {{ system().name }}?</h2>
          <p hlmAlertDialogDescription>
            @if (revokePrevious()) {
              Tinfirm makes a new secret and shows it once. The current one stops now: deliveries
              signed with it are refused until the sender has the new one.
            } @else {
              Tinfirm makes a new secret and shows it once. The current one still works for 24
              hours, while you give the sender the new one; the one before it stops now.
            }
          </p>
        </hlm-alert-dialog-header>
        <label class="flex items-start gap-2 text-sm">
          <input
            type="checkbox"
            class="accent-primary mt-0.5 size-4 shrink-0"
            data-testid="revoke-previous"
            [checked]="revokePrevious()"
            [disabled]="rotating()"
            (change)="revokePrevious.set($any($event.target).checked)"
          />
          <span>Stop the current secret now, for a secret that leaked</span>
        </label>
        @if (rotateError(); as message) {
          <div hlmAlert variant="destructive">
            <ng-icon name="lucideCircleAlert" aria-hidden="true" />
            <p hlmAlertDescription>{{ message }}</p>
          </div>
        }
        <hlm-alert-dialog-footer>
          <button hlmAlertDialogCancel [disabled]="rotating()">Cancel</button>
          <button
            hlmAlertDialogAction
            data-testid="confirm-rotate-secret"
            [disabled]="rotating()"
            (click)="rotate()"
          >
            @if (rotating()) {
              <hlm-spinner aria-hidden="true" />
            }
            Rotate
          </button>
        </hlm-alert-dialog-footer>
      </hlm-alert-dialog-content>
    </hlm-alert-dialog>

    <hlm-dialog [state]="rotated() ? 'open' : 'closed'" (closed)="closeRotated()">
      <hlm-dialog-content *hlmDialogPortal="let ctx" class="sm:max-w-lg">
        <hlm-dialog-header>
          <h2 hlmDialogTitle>The new secret of {{ system().name }}</h2>
        </hlm-dialog-header>
        @if (rotated(); as r) {
          <app-webhook-secret
            [secret]="r.secret"
            [scheme]="w.scheme"
            [header]="w.secretHeader ?? null"
            [previousUntil]="r.previousUntil ? absolute(r.previousUntil) : null"
          />
        }
        <hlm-dialog-footer>
          <button hlmBtn type="button" data-testid="rotated-done" (click)="closeRotated()">
            I have copied the secret
          </button>
        </hlm-dialog-footer>
      </hlm-dialog-content>
    </hlm-dialog>
  `,
})
export class WebhookSetup {
  private readonly api = inject(Api);

  readonly companyId = input.required<string>();
  readonly system = input.required<ExternalSystem>();
  readonly canManage = input(false);
  /** "Change": the page opens its change dialog, which holds the scheme and the rate. */
  readonly changeSettings = output<void>();
  /** A rotation: the page reads the system again for the secret's new age. */
  readonly rotatedSecret = output<void>();

  protected readonly instructions = computed(() => {
    const w = this.system().webhook;
    return w ? senderInstructions(w.scheme, w.secretHeader ?? null) : '';
  });
  protected readonly due = computed(() => secretDue(this.system().webhook?.secretSetAt));
  protected readonly absolute = formatAbsolute;

  protected readonly rotateOpen = signal(false);
  protected readonly rotating = signal(false);
  protected readonly rotateError = signal<string | null>(null);
  /** Whether the rotation drops the current secret at once (a leaked one) instead of after 24 hours. */
  protected readonly revokePrevious = signal(false);
  /** The new secret, held only while its dialog is open. */
  protected readonly rotated = signal<Rotated | null>(null);

  /** Asks to rotate the secret; the page's health strip asks too. */
  openRotate(): void {
    this.rotateError.set(null);
    this.revokePrevious.set(false);
    this.rotateOpen.set(true);
  }

  protected async rotate(): Promise<void> {
    if (this.rotating()) return;
    this.rotating.set(true);
    this.rotateError.set(null);
    try {
      const secret = await this.api.invoke(rotateWebhookSecret, {
        companyId: this.companyId(),
        systemId: this.system().id,
        ...(this.revokePrevious() ? { body: { revokePrevious: true } } : {}),
      });
      this.rotateOpen.set(false);
      this.rotated.set(secret);
      this.rotatedSecret.emit();
    } catch (error) {
      this.rotateError.set(problemMessage(error));
    } finally {
      this.rotating.set(false);
    }
  }

  protected closeRotated(): void {
    this.rotated.set(null);
  }
}
