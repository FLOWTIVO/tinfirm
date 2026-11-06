import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { NgIcon, provideIcons } from '@ng-icons/core';
import { lucideCircleAlert } from '@ng-icons/lucide';
import { HlmSpinner } from '@spartan-ng/helm/spinner';
import type { TriggerPreview } from '../../../api/generated/models';
import { ListSkeleton } from '../../../shared/list-skeleton';
import { Time } from '../../../shared/time';
import { WhoActsWhoPays } from '../../../shared/who-acts-who-pays';
import type { WhoActsWhoPaysView } from '../../../shared/who-acts-who-pays-view';
import { fromTriggerPreview } from './trigger-view';

/** The preview as `app-who-acts-who-pays` draws it, with the open conversations a change keeps. */
export function previewView(preview: TriggerPreview, reader: string | null): WhoActsWhoPaysView {
  const kept = preview.openConversationsKeepingAccounts ?? 0;
  return {
    ...fromTriggerPreview(preview, reader),
    actsNote:
      kept > 0
        ? kept === 1
          ? '1 open conversation keeps the accounts it started with.'
          : `${kept} open conversations keep the accounts they started with.`
        : null,
  };
}

/**
 * "Before you save" of the trigger editor (plan §4.5; the package 4 contract, §5.2 and §5.8), read
 * again after every change: the trigger as one sentence, who acts per system and who pays, whether
 * a runner host can run it, a schedule's next firings, what to mind, and for a change how many open
 * conversations keep their accounts. Beside the sections on a wide screen, in a sheet behind the
 * save bar on a phone. The warning when anyone may drive it is `app-trigger-open-events`.
 */
@Component({
  selector: 'app-trigger-preview-panel',
  imports: [NgIcon, HlmSpinner, ListSkeleton, Time, WhoActsWhoPays],
  providers: [provideIcons({ lucideCircleAlert })],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'block', 'data-testid': 'trigger-preview' },
  template: `
    <section
      class="flex flex-col gap-3"
      [class]="framed() ? 'bg-card rounded-xl border p-4' : ''"
      [attr.aria-labelledby]="titled() ? 'trigger-preview-title' : null"
      [attr.aria-label]="titled() ? null : 'Before you save'"
    >
      @if (titled()) {
        <div class="flex items-center justify-between gap-2">
          <h2 class="text-sm font-bold" id="trigger-preview-title">Before you save</h2>
          @if (loading()) {
            <hlm-spinner class="size-3.5" aria-label="Updating the preview" />
          }
        </div>
      }
      @if (preview(); as p) {
        <p class="text-sm font-medium text-pretty" data-testid="preview-sentence">
          {{ p.sentence }}
        </p>
      } @else if (!problem()) {
        <p class="text-muted-foreground text-xs" data-testid="preview-waiting">
          {{ hint() ?? 'Fill in the trigger to see who acts and who pays.' }}
        </p>
      }
      @if (problem(); as message) {
        <p
          class="text-destructive flex items-start gap-1.5 text-sm"
          role="alert"
          data-testid="preview-error"
        >
          <ng-icon name="lucideCircleAlert" class="mt-0.5 shrink-0" aria-hidden="true" />
          {{ message }}
        </p>
      }

      @if (preview(); as p) {
        <div class="bg-border h-px" aria-hidden="true"></div>
        <app-who-acts-who-pays
          heading="Who acts and who pays"
          intro="As the trigger’s runs would be bound now. Nothing switches later by itself."
          [framed]="false"
          [view]="view()"
        />

        @if (p.nextFirings.length) {
          <div class="bg-border h-px" aria-hidden="true"></div>
          <div class="flex flex-col gap-1">
            <h3 class="text-sm font-semibold">Next firings</h3>
            <ul
              class="text-muted-foreground flex flex-col gap-0.5 font-mono text-xs tabular-nums"
              data-testid="preview-firings"
            >
              @for (f of p.nextFirings; track f) {
                <li><app-time [value]="f" mode="absolute" /></li>
              }
            </ul>
          </div>
        }

        @if (p.warnings?.length) {
          <div class="bg-border h-px" aria-hidden="true"></div>
          <div class="flex flex-col gap-1 text-sm" data-testid="preview-warnings">
            <h3 class="text-sm font-semibold">Mind that</h3>
            <ul class="text-muted-foreground flex list-disc flex-col gap-0.5 ps-4 text-xs">
              @for (w of p.warnings; track w) {
                <li>{{ w }}</li>
              }
            </ul>
          </div>
        }
      } @else if (loading() && !problem()) {
        <app-list-skeleton [rows]="2" />
      }
    </section>
  `,
})
export class TriggerPreviewPanel {
  readonly preview = input<TriggerPreview | null>(null);
  readonly loading = input(false);
  /** The preview's refusal, in words. */
  readonly problem = input<string | null>(null);
  /** Why there is no preview yet. */
  readonly hint = input<string | null>(null);
  readonly reader = input<string | null>(null);
  /** In a card of its own; off inside a sheet, which is the card. */
  readonly framed = input(true);
  /** With its heading; off where a sheet's title says "Before you save". */
  readonly titled = input(true);

  protected readonly view = computed(() => {
    const p = this.preview();
    return p ? previewView(p, this.reader()) : null;
  });
}
