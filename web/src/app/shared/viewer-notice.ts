import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { NgIcon, provideIcons } from '@ng-icons/core';
import { lucideEye } from '@ng-icons/lucide';

/** Where the notice stands, which decides what it says first. */
export type ViewerNoticePlace = 'conversation' | 'conversations' | 'agents' | 'triggers' | 'inbox';

/** What a Viewer does not do there (ADR-0077 decision 5). */
const WHAT: Record<ViewerNoticePlace, string> = {
  conversation: 'Viewers read a conversation, but don’t write in it or answer its requests.',
  conversations: 'Viewers read the conversations shared with their groups, but start none.',
  agents: 'Viewers read the agents, but don’t talk to them, create or edit them.',
  triggers: 'Viewers read the triggers, but don’t create or edit them.',
  inbox: 'Viewers see what waits here, but don’t answer questions or decide approvals.',
};

/**
 * Why a Viewer has no Start, New or composer (ADR-0077): a quiet note in their place, not an error.
 * A Viewer holds no seat and never starts or continues work; an Owner or Admin can make them a
 * Member. The server refuses the same calls; the note only says why they are not offered.
 */
@Component({
  selector: 'app-viewer-notice',
  imports: [NgIcon],
  providers: [provideIcons({ lucideEye })],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'block' },
  template: `
    <div
      class="bg-muted flex items-start gap-2.5 rounded-xl border px-4 py-3 text-sm"
      [class.rounded-[18px]]="place() === 'conversation'"
      role="note"
      data-testid="viewer-notice"
    >
      <ng-icon name="lucideEye" class="text-muted-foreground mt-0.5 shrink-0" aria-hidden="true" />
      <p class="min-w-0 text-pretty">
        <span class="font-semibold">You are a Viewer in this company.</span>
        <span class="text-muted-foreground">
          {{ what() }} An Owner or Admin can make you a Member.</span
        >
      </p>
    </div>
  `,
})
export class ViewerNotice {
  readonly place = input.required<ViewerNoticePlace>();
  protected readonly what = computed(() => WHAT[this.place()]);
}
