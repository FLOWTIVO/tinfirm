import { ChangeDetectionStrategy, Component, inject, signal, type OnInit } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { HlmSpinner } from '@spartan-ng/helm/spinner';
import { Logo } from '../../shared/logo';
import { loadOffice } from './office';
import type { CallbackMessage } from './outlook-auth';

/**
 * The sign-in callback of the Outlook add-in (ADR-0074), `/outlook/callback`, the one redirect URI
 * of the `tinfirm-outlook` client: inside Office's sign-in window it hands the authorization's code
 * and state, or its error, to the task pane with `messageParent`, and Outlook closes the window. The
 * task pane checks the state and exchanges the code: no token passes through here.
 */
@Component({
  selector: 'app-outlook-callback',
  imports: [HlmSpinner, Logo],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'bg-background text-foreground block min-h-dvh' },
  template: `
    <main class="mx-auto flex max-w-md flex-col gap-4 p-6">
      <app-logo class="h-8" wordmark />
      @if (handedOver()) {
        <p class="text-muted-foreground flex items-center gap-2 text-sm">
          <hlm-spinner class="size-4" /> Finishing the sign-in…
        </p>
      } @else {
        <p class="text-muted-foreground text-sm" data-testid="callback-outside">
          This window belongs to Tinfirm’s Outlook add-in. You can close it.
        </p>
      }
    </main>
  `,
})
export class OutlookCallback implements OnInit {
  private readonly route = inject(ActivatedRoute);

  protected readonly handedOver = signal(true);

  ngOnInit(): void {
    const query = this.route.snapshot.queryParamMap;
    const message: CallbackMessage = {
      code: query.get('code') ?? undefined,
      state: query.get('state') ?? undefined,
      error: query.get('error') ?? undefined,
      errorDescription: query.get('error_description') ?? undefined,
    };
    void loadOffice().then((office) => {
      try {
        if (!office?.context.ui) throw new Error('Not in Office’s dialog');
        office.context.ui.messageParent(JSON.stringify(message));
      } catch {
        this.handedOver.set(false);
      }
    });
  }
}
