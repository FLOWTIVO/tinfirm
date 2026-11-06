import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  resource,
  signal,
} from '@angular/core';
import { RouterLink } from '@angular/router';
import { NgIcon, provideIcons } from '@ng-icons/core';
import {
  lucideCircleAlert,
  lucideCirclePause,
  lucideCirclePlay,
  lucideOctagonPause,
  lucidePlay,
  lucideShieldCheck,
} from '@ng-icons/lucide';
import { HlmAlertImports } from '@spartan-ng/helm/alert';
import { HlmAlertDialogImports } from '@spartan-ng/helm/alert-dialog';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmCardImports } from '@spartan-ng/helm/card';
import { HlmFieldImports } from '@spartan-ng/helm/field';
import { HlmNativeSelectImports } from '@spartan-ng/helm/native-select';
import { HlmSkeleton } from '@spartan-ng/helm/skeleton';
import { HlmSpinner } from '@spartan-ng/helm/spinner';
import { Api } from '../../../api/generated/api';
import {
  getKillSwitches,
  liftRunsStop,
  listSystems,
  pauseSystem,
  resumeMember,
  resumeSystem,
  stopRuns,
} from '../../../api/generated/functions';
import type { KillSwitch, KillSwitches, RunsLiftResult } from '../../../api/generated/models';
import { problemMessage } from '../../../api/problem';
import { loadAll } from '../../../core/company-data';
import { CurrentCompany } from '../../../core/current-company';
import { KillSwitchState } from '../../../core/kill-switch-state';
import { Notify } from '../../../core/notify';
import { managesCompany } from '../../../core/permissions';
import { ErrorState } from '../../../shared/error-state';
import {
  KILL_SWITCH_LABELS,
  stillOnPhrase,
  suspensionCause,
  switchCountPhrase,
  switchLink,
} from '../../../shared/governance-labels';
import { pulledSentence } from '../../../shared/kill-switch-banner';
import { plural } from '../../../shared/labels';
import { PageHeader } from '../../../shared/page-header';
import { ReasonDialog } from '../../../shared/reason-dialog';
import {
  nothingOn,
  pausableSystems,
  resumeTriggersLabel,
  switchGroups,
} from './kill-switches-view';

const FORBIDDEN = { 'status:403': 'Only owners and admins pull and undo these switches.' };

/**
 * The kill switches on one page (the plan §5, W7; the package 6 contract, §5.4): stopping and
 * lifting the company's runs, pausing and resuming a system with or without its triggers, who is
 * suspended, and every other switch that is on, each with who pulled it, when and why. A member
 * sees the company's stop and their own suspension only. Every switch is undone by a person here or
 * on its own page, never by time.
 */
@Component({
  selector: 'app-kill-switches',
  imports: [
    NgIcon,
    RouterLink,
    HlmAlertImports,
    HlmAlertDialogImports,
    HlmButton,
    HlmCardImports,
    HlmFieldImports,
    HlmNativeSelectImports,
    HlmSkeleton,
    HlmSpinner,
    ErrorState,
    PageHeader,
    ReasonDialog,
  ],
  providers: [
    provideIcons({
      lucideCircleAlert,
      lucideCirclePause,
      lucideCirclePlay,
      lucideOctagonPause,
      lucidePlay,
      lucideShieldCheck,
    }),
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './kill-switches.html',
})
export class KillSwitchesPage {
  private readonly api = inject(Api);
  private readonly notify = inject(Notify);
  private readonly current = inject(CurrentCompany);
  private readonly shared = inject(KillSwitchState);

  protected readonly companyId = this.current.id;
  protected readonly manages = computed(() => managesCompany(this.current.access()));
  protected readonly labels = KILL_SWITCH_LABELS;
  protected readonly countPhrase = switchCountPhrase;
  protected readonly linkOf = switchLink;
  protected readonly causeOf = suspensionCause;
  protected readonly resumeLabel = resumeTriggersLabel;

  protected readonly switches = resource({
    params: () => this.current.id(),
    loader: ({ params: companyId }) => this.api.invoke(getKillSwitches, { companyId }),
  });
  protected readonly loaded = computed<KillSwitches | null>(() =>
    this.switches.hasValue() ? (this.switches.value() ?? null) : null,
  );
  protected readonly groups = computed(() => {
    const loaded = this.loaded();
    return loaded ? switchGroups(loaded) : null;
  });
  /** The counts beside the cards; the company's stop has its own card. */
  protected readonly chips = computed(() =>
    (this.loaded()?.counts ?? []).filter((c) => c.kind !== 'RUNS_STOPPED'),
  );
  protected readonly allOff = computed(() => {
    const loaded = this.loaded();
    return !!loaded && nothingOn(loaded);
  });

  constructor() {
    // The banner of every page follows what this page read.
    effect(() => {
      const loaded = this.loaded();
      if (loaded) this.shared.set(loaded);
    });
  }

  protected pulled(kill: KillSwitch, verb: string): string {
    return pulledSentence(verb, kill.since, kill.byName, kill.reason);
  }

  protected stoppedText(): string {
    const stop = this.loaded()?.runsStop;
    return stop ? pulledSentence('Stopped', stop.stoppedAt, stop.byName, stop.reason) : '';
  }

  // --- The company's runs ------------------------------------------------------------------------

  protected readonly stopOpen = signal(false);
  protected readonly stopBusy = signal(false);
  protected readonly stopError = signal<string | null>(null);

  protected openStop(): void {
    this.stopError.set(null);
    this.stopOpen.set(true);
  }

  protected async stop(reason: string): Promise<void> {
    const companyId = this.current.id();
    if (!companyId || this.stopBusy()) return;
    this.stopBusy.set(true);
    this.stopError.set(null);
    try {
      const result = await this.api.invoke(stopRuns, { companyId, body: { reason } });
      this.stopOpen.set(false);
      this.lifted.set(null);
      this.notify.success(
        'The company’s runs are stopped',
        result.runsCancelled
          ? `${plural(result.runsCancelled, 'running turn')} cancelled.`
          : 'Nothing was running.',
      );
      this.switches.reload();
    } catch (error) {
      this.stopError.set(problemMessage(error, FORBIDDEN));
    } finally {
      this.stopBusy.set(false);
    }
  }

  protected readonly liftOpen = signal(false);
  protected readonly liftBusy = signal(false);
  protected readonly liftError = signal<string | null>(null);
  /** What the last lift answered: what went on again, and what stays on. */
  protected readonly lifted = signal<RunsLiftResult | null>(null);
  protected readonly liftedStillOn = computed(() => {
    const lifted = this.lifted();
    return lifted ? stillOnPhrase(lifted.stillOn) : null;
  });
  /** The other switches that stay on after a lift, from what the page shows now. */
  protected readonly stayingOn = computed(() => {
    const loaded = this.loaded();
    return loaded ? stillOnPhrase(loaded.counts) : null;
  });

  protected openLift(): void {
    this.liftError.set(null);
    this.liftOpen.set(true);
  }

  protected async lift(): Promise<void> {
    const companyId = this.current.id();
    if (!companyId || this.liftBusy()) return;
    this.liftBusy.set(true);
    this.liftError.set(null);
    try {
      const result = await this.api.invoke(liftRunsStop, { companyId });
      this.liftOpen.set(false);
      this.lifted.set(result);
      this.notify.success('The company’s runs go again', 'Waiting turns are dispatched.');
      this.switches.reload();
    } catch (error) {
      this.liftError.set(problemMessage(error, FORBIDDEN));
    } finally {
      this.liftBusy.set(false);
    }
  }

  // --- Systems ---------------------------------------------------------------------------------

  private readonly systems = resource({
    params: () => (this.manages() ? this.current.id() : undefined),
    loader: ({ params: companyId }) =>
      loadAll((cursor) => this.api.invoke(listSystems, { companyId, limit: 100, cursor })),
  });
  protected readonly pausable = computed(() =>
    pausableSystems(this.systems.hasValue() ? (this.systems.value() ?? []) : []),
  );

  protected readonly pauseOpen = signal(false);
  protected readonly pauseBusy = signal(false);
  protected readonly pauseError = signal<string | null>(null);
  protected readonly pauseSystemId = signal('');
  protected readonly pauseStopRunning = signal(false);

  protected openPause(): void {
    this.pauseError.set(null);
    this.pauseSystemId.set(this.pausable()[0]?.id ?? '');
    this.pauseStopRunning.set(false);
    this.pauseOpen.set(true);
  }

  protected async pause(reason: string): Promise<void> {
    const companyId = this.current.id();
    const systemId = this.pauseSystemId();
    if (!companyId || !systemId || this.pauseBusy()) return;
    this.pauseBusy.set(true);
    this.pauseError.set(null);
    try {
      const result = await this.api.invoke(pauseSystem, {
        companyId,
        systemId,
        body: { reason, stopRunning: this.pauseStopRunning() },
      });
      const name = this.pausable().find((s) => s.id === systemId)?.name ?? 'The system';
      this.pauseOpen.set(false);
      const parts = [];
      if (result.runsCancelled) parts.push(`${plural(result.runsCancelled, 'turn')} cancelled`);
      if (result.triggersPaused) parts.push(`${plural(result.triggersPaused, 'trigger')} paused`);
      this.notify.success(`${name} is paused`, parts.length ? `${parts.join(', ')}.` : undefined);
      this.switches.reload();
      this.systems.reload();
    } catch (error) {
      this.pauseError.set(problemMessage(error, FORBIDDEN));
    } finally {
      this.pauseBusy.set(false);
    }
  }

  // --- Suspended members, resumed in place --------------------------------------------------------

  /** A member's suspension an admin may lift here: not one the user's deactivation made. */
  protected resumable(kill: KillSwitch): boolean {
    return kill.kind === 'MEMBER_SUSPENDED' && !!kill.userId && kill.cause !== 'USER_DEACTIVATED';
  }

  protected readonly resumingMember = signal<string | null>(null);

  protected async resumeMember(kill: KillSwitch): Promise<void> {
    const companyId = this.current.id();
    if (!companyId || !kill.userId || this.resumingMember()) return;
    this.resumingMember.set(kill.userId);
    try {
      await this.api.invoke(resumeMember, { companyId, userId: kill.userId });
      this.notify.success(
        `${kill.name} is resumed`,
        'Their waiting turns go on; triggers they wrote stay paused until resumed.',
      );
      this.switches.reload();
    } catch (error) {
      this.notify.error(`Couldn't resume ${kill.name}`, error, {
        ...FORBIDDEN,
        PRINCIPAL_SUSPENDED: `${kill.name}'s Tinfirm account was deactivated: reactivating the user resumes them.`,
      });
    } finally {
      this.resumingMember.set(null);
    }
  }

  protected readonly resuming = signal<KillSwitch | null>(null);
  protected readonly resumeOpen = signal(false);
  protected readonly resumeBusy = signal(false);
  protected readonly resumeError = signal<string | null>(null);
  protected readonly resumeTriggers = signal(true);

  protected openResume(kill: KillSwitch): void {
    this.resuming.set(kill);
    this.resumeTriggers.set(true);
    this.resumeError.set(null);
    this.resumeOpen.set(true);
  }

  protected async resume(): Promise<void> {
    const companyId = this.current.id();
    const kill = this.resuming();
    if (!companyId || !kill || this.resumeBusy()) return;
    this.resumeBusy.set(true);
    this.resumeError.set(null);
    try {
      const hasTriggers = (kill.pausedTriggers?.length ?? 0) > 0;
      const result = await this.api.invoke(resumeSystem, {
        companyId,
        systemId: kill.targetId,
        body: { resumeTriggers: hasTriggers ? this.resumeTriggers() : true },
      });
      this.resumeOpen.set(false);
      const parts = [];
      if (result.triggersResumed) {
        parts.push(`${plural(result.triggersResumed, 'trigger')} resumed`);
      }
      if (result.triggersStillPaused) {
        parts.push(
          `${plural(result.triggersStillPaused, 'trigger')} ${result.triggersStillPaused === 1 ? 'stays' : 'stay'} paused`,
        );
      }
      this.notify.success(
        `${kill.name} is resumed`,
        parts.length ? `${parts.join(', ')}.` : undefined,
      );
      this.switches.reload();
      this.systems.reload();
    } catch (error) {
      this.resumeError.set(problemMessage(error, FORBIDDEN));
    } finally {
      this.resumeBusy.set(false);
    }
  }
}
