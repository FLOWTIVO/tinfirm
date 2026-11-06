import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { HlmSkeleton } from '@spartan-ng/helm/skeleton';
import { LicenceState } from '../../../core/licence';
import { Session } from '../../../core/session';
import { ErrorState } from '../../../shared/error-state';
import {
  EDITION_LABELS,
  LICENCE_STATE_LABELS,
  LICENCE_STATE_TONES,
  licenceDay,
  seatsLine,
} from '../../../shared/licence-labels';
import { SettingRow } from './setting-row';

/**
 * The settings' Licence section (ADR-0077 decision 6), read-only for the company's Owners and
 * Admins: the edition, the seats used of those licensed and the end. One key covers the instance,
 * so it is entered by an instance admin on the instance's licence page, never here.
 */
@Component({
  selector: 'app-licence-settings',
  imports: [RouterLink, HlmSkeleton, ErrorState, SettingRow],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section
      class="bg-card rounded-xl border"
      aria-labelledby="licence-title"
      data-testid="licence"
    >
      <div class="px-4 pt-4 pb-3.5 sm:px-5">
        <h2 id="licence-title" class="text-base font-bold">Licence</h2>
        <p class="text-muted-foreground mt-0.5 text-sm text-pretty">
          One licence covers every company of this instance.
          @if (instanceAdmin()) {
            You enter its key on the
            <a
              class="text-primary font-semibold underline underline-offset-4"
              routerLink="/admin/licence"
              >instance’s licence page</a
            >.
          } @else {
            An instance admin enters its key.
          }
        </p>
      </div>
      @if (state.licence.error() && !licence()) {
        <div class="border-t px-4 py-4 sm:px-5">
          <app-error-state
            [error]="state.licence.error()"
            heading="Couldn't load the licence"
            (retry)="state.reload()"
          />
        </div>
      } @else if (licence(); as l) {
        <app-setting-row rowId="licence-edition" label="Edition">
          <span class="flex flex-wrap items-center gap-2 text-sm">
            <span class="font-semibold" data-testid="licence-edition">{{
              editionLabels[l.edition]
            }}</span>
            <span
              class="inline-flex h-[22px] items-center rounded-full border px-2.5 text-xs font-semibold"
              [class]="stateTones[l.state]"
              data-testid="licence-state"
              >{{ stateLabels[l.state] }}</span
            >
          </span>
        </app-setting-row>
        <app-setting-row rowId="licence-seats" label="Seats">
          <span help
            >Owners, Admins and Members each hold one, counted once across the instance’s companies.
            Viewers hold none.</span
          >
          <span class="text-sm" data-testid="licence-seats">{{ seats() }}</span>
        </app-setting-row>
        @if (l.endsAt) {
          <app-setting-row rowId="licence-ends" label="Ends">
            @if (l.graceEndsAt && (l.state === 'GRACE' || l.state === 'EXPIRED')) {
              <span help
                >Paid features stop after 30 days of grace, on {{ day(l.graceEndsAt) }}.</span
              >
            }
            <span class="text-sm" data-testid="licence-ends">{{ day(l.endsAt) }}</span>
          </app-setting-row>
        }
      } @else {
        <div class="flex flex-col gap-3 border-t px-4 py-4 sm:px-5" aria-busy="true">
          <hlm-skeleton class="h-4 w-40" />
          <hlm-skeleton class="h-4 w-56" />
        </div>
      }
    </section>
  `,
})
export class LicenceSettings {
  protected readonly state = inject(LicenceState);
  private readonly session = inject(Session);

  protected readonly editionLabels = EDITION_LABELS;
  protected readonly stateLabels = LICENCE_STATE_LABELS;
  protected readonly stateTones = LICENCE_STATE_TONES;
  protected readonly day = licenceDay;

  protected readonly licence = this.state.value;
  protected readonly instanceAdmin = this.session.isInstanceAdmin;
  protected readonly seats = computed(() => {
    const licence = this.licence();
    return licence ? seatsLine(licence.seats) : '';
  });
}
