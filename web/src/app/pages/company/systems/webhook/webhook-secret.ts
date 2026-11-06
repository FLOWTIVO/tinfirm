import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { NgIcon, provideIcons } from '@ng-icons/core';
import { lucideKeyRound } from '@ng-icons/lucide';
import { HlmAlertImports } from '@spartan-ng/helm/alert';
import { CopyButton } from '../../../../shared/copy-button';

/** How the sender proves a delivery is theirs, in a sentence for whoever sets the sender up. */
export function senderInstructions(
  scheme: 'STANDARD' | 'SECRET_HEADER',
  header: string | null,
): string {
  return scheme === 'SECRET_HEADER'
    ? `The sender puts the secret itself in the ${header || 'X-Tinfirm-Secret'} header of each delivery.`
    : 'The sender signs each delivery with it as Standard Webhooks do: the webhook-id, webhook-timestamp and webhook-signature headers. Its libraries take the secret as it is.';
}

/**
 * A webhook system's secret, shown once (package 5, §5.12): on its registration and after a
 * rotation. Tinfirm keeps it in its vault and never answers it again, so the person copies it now.
 */
@Component({
  selector: 'app-webhook-secret',
  imports: [NgIcon, HlmAlertImports, CopyButton],
  providers: [provideIcons({ lucideKeyRound })],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'flex flex-col gap-4' },
  template: `
    <div hlmAlert class="border-human" data-testid="webhook-secret-once">
      <ng-icon name="lucideKeyRound" class="text-human" aria-hidden="true" />
      <h3 hlmAlertTitle>Copy the secret now</h3>
      <p hlmAlertDescription>
        Tinfirm shows it only this once. Give it to the sender with the URL below.
        @if (previousUntil(); as until) {
          The previous secret still works until {{ until }}, while you update the sender.
        }
      </p>
    </div>
    <div class="flex flex-col gap-1">
      <span class="text-muted-foreground text-xs font-semibold" id="webhook-secret-label"
        >Secret</span
      >
      <div class="bg-muted/50 flex items-center gap-2 rounded-lg border px-3 py-1.5">
        <code
          class="min-w-0 flex-1 font-mono text-xs break-all select-all"
          aria-labelledby="webhook-secret-label"
          data-testid="webhook-secret"
          >{{ secret() }}</code
        >
        <app-copy-button [value]="secret()" ariaLabel="Copy the secret" />
      </div>
    </div>
    @if (url(); as u) {
      <div class="flex flex-col gap-1">
        <span class="text-muted-foreground text-xs font-semibold" id="webhook-secret-url-label"
          >Delivery URL</span
        >
        <div class="bg-muted/50 flex items-center gap-2 rounded-lg border px-3 py-1.5">
          <code
            class="min-w-0 flex-1 font-mono text-xs break-all select-all"
            aria-labelledby="webhook-secret-url-label"
            >{{ u }}</code
          >
          <app-copy-button [value]="u" ariaLabel="Copy the delivery URL" />
        </div>
      </div>
    }
    <p class="text-muted-foreground text-xs text-pretty">{{ instructions() }}</p>
  `,
})
export class WebhookSecret {
  readonly secret = input.required<string>();
  readonly url = input<string | null>(null);
  readonly scheme = input<'STANDARD' | 'SECRET_HEADER'>('STANDARD');
  readonly header = input<string | null>(null);
  /** After a rotation: until when the previous secret works, already in words. */
  readonly previousUntil = input<string | null>(null);

  protected readonly instructions = computed(() =>
    senderInstructions(this.scheme(), this.header()),
  );
}
