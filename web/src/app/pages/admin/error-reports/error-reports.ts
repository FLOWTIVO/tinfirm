import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  resource,
  signal,
} from '@angular/core';
import { NgIcon, provideIcons } from '@ng-icons/core';
import {
  lucideBug,
  lucideChevronDown,
  lucideChevronRight,
  lucideCircleAlert,
  lucideDownload,
  lucideTrash2,
} from '@ng-icons/lucide';
import { HlmAlertImports } from '@spartan-ng/helm/alert';
import { HlmBadge } from '@spartan-ng/helm/badge';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmCardImports } from '@spartan-ng/helm/card';
import { HlmEmptyImports } from '@spartan-ng/helm/empty';
import { HlmSkeleton } from '@spartan-ng/helm/skeleton';
import { HlmSpinner } from '@spartan-ng/helm/spinner';
import { HlmSwitchImports } from '@spartan-ng/helm/switch';
import { HlmTableImports } from '@spartan-ng/helm/table';
import { Api } from '../../../api/generated/api';
import {
  deleteErrorReport,
  exportErrorReports,
  getErrorReport,
  getErrorReporting,
  listErrorReports,
  updateErrorReporting,
} from '../../../api/generated/functions';
import type { ErrorReport, ErrorReportFrame, ErrorReporting } from '../../../api/generated/models';
import { Notify } from '../../../core/notify';
import { pagedResource } from '../../../core/paged-resource';
import { ErrorState } from '../../../shared/error-state';
import { ListSkeleton } from '../../../shared/list-skeleton';
import { PageHeader } from '../../../shared/page-header';
import { Time } from '../../../shared/time';

const PAGE_SIZE = 50;

/** `hlm-switch` exposes its state as a writable signal, so a failed change can be reverted. */
interface CheckedHolder {
  readonly checked: { set(value: boolean): void };
}

/**
 * `/admin/errors` (ADR-0070): the installation's error reports, scrubbed when captured, and whether they are sent to
 * the vendor automatically. The download holds exactly what delivery sends.
 */
@Component({
  selector: 'app-error-reports',
  imports: [
    NgIcon,
    HlmAlertImports,
    HlmBadge,
    HlmButton,
    HlmCardImports,
    HlmEmptyImports,
    HlmSkeleton,
    HlmSpinner,
    HlmSwitchImports,
    HlmTableImports,
    ErrorState,
    ListSkeleton,
    PageHeader,
    Time,
  ],
  providers: [
    provideIcons({
      lucideBug,
      lucideChevronDown,
      lucideChevronRight,
      lucideCircleAlert,
      lucideDownload,
      lucideTrash2,
    }),
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './error-reports.html',
})
export class ErrorReports {
  private readonly api = inject(Api);
  private readonly notify = inject(Notify);

  protected readonly exportUrl = exportErrorReports.PATH;

  protected readonly reporting = resource({
    loader: () => this.api.invoke(getErrorReporting, {}),
  });

  protected readonly settings = computed<ErrorReporting | undefined>(() =>
    this.reporting.hasValue() ? this.reporting.value() : undefined,
  );

  /** Why the switch cannot be moved, if it cannot. */
  protected readonly locked = computed<string | null>(() => {
    const settings = this.settings();
    if (!settings) return null;
    if (settings.setBy === 'OPERATOR') {
      return 'Your operator sets this with TINFIRM_ERROR_REPORTS; it is changed there.';
    }
    if (!settings.destination) {
      return 'There is no destination: your operator sets one with TINFIRM_ERROR_REPORTS_DSN.';
    }
    return null;
  });

  protected readonly reports = pagedResource<ErrorReport, true>({
    params: () => true,
    load: (_, cursor) => this.api.invoke(listErrorReports, { limit: PAGE_SIZE, cursor }),
  });

  protected readonly saving = signal(false);

  protected async setAutomatic(automatic: boolean, toggle: CheckedHolder): Promise<void> {
    const settings = this.settings();
    if (!settings || this.saving() || automatic === (settings.mode === 'AUTOMATIC')) return;
    this.saving.set(true);
    try {
      const updated = await this.api.invoke(updateErrorReporting, { body: { automatic } });
      this.reporting.set(updated);
      this.notify.success(
        automatic ? 'Errors are sent automatically' : 'Errors stay on this installation',
        automatic
          ? `The next delivery sends them to ${updated.destination}.`
          : 'Nothing more is sent; you can still download them.',
      );
    } catch (error) {
      toggle.checked.set(!automatic);
      this.notify.error("Couldn't change error reporting", error);
    } finally {
      this.saving.set(false);
    }
  }

  // --- A report's sample ------------------------------------------------------------------------

  protected readonly openId = signal<string | null>(null);

  protected readonly detail = resource({
    params: () => this.openId() ?? undefined,
    loader: ({ params: reportId }) => this.api.invoke(getErrorReport, { reportId }),
  });

  /** `at com.flowtivo.Turns.start (Turns.java:10)`, as a stack trace reads. */
  protected frameText(frame: ErrorReportFrame): string {
    const where = [frame.file ?? '?', frame.line, frame.column]
      .filter((part) => part !== undefined && part !== null)
      .join(':');
    return `at ${frame.module ? frame.module + '.' : ''}${frame.function ?? '?'} (${where})`;
  }

  protected toggle(report: ErrorReport): void {
    this.openId.update((id) => (id === report.id ? null : report.id));
  }

  // --- Delete -----------------------------------------------------------------------------------

  protected readonly deleting = signal<string | null>(null);

  protected async remove(report: ErrorReport): Promise<void> {
    if (this.deleting()) return;
    this.deleting.set(report.id);
    try {
      await this.api.invoke(deleteErrorReport, { reportId: report.id });
      this.reports.update((items) => items.filter((r) => r.id !== report.id));
      if (this.openId() === report.id) this.openId.set(null);
      this.notify.success('Report deleted', 'It starts again if the error happens again.');
    } catch (error) {
      this.notify.error("Couldn't delete the report", error, {
        'status:404': 'This report is already gone. Reload the page.',
      });
    } finally {
      this.deleting.set(null);
    }
  }
}
