import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  model,
  output,
  resource,
  signal,
  untracked,
} from '@angular/core';
import { NgIcon, provideIcons } from '@ng-icons/core';
import { lucideCircleAlert, lucideTriangleAlert } from '@ng-icons/lucide';
import { HlmAlertImports } from '@spartan-ng/helm/alert';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmDialogImports } from '@spartan-ng/helm/dialog';
import { HlmFieldImports } from '@spartan-ng/helm/field';
import { HlmNativeSelectImports } from '@spartan-ng/helm/native-select';
import { HlmSpinner } from '@spartan-ng/helm/spinner';
import { Api } from '../../../api/generated/api';
import {
  createGrant,
  lendSystemCredential,
  listGroups,
  listServiceIdentities,
} from '../../../api/generated/functions';
import type {
  AccountAccess,
  ExternalSystem,
  SystemCredential,
} from '../../../api/generated/models';
import { problemMessage } from '../../../api/problem';
import { loadAll } from '../../../core/company-data';
import { Segmented, type SegmentedOption } from '../../../shared/segmented';
import { ACCESS_CHOICES, CREDENTIAL_MESSAGES, triggersFollowing } from './credential-labels';

const LIMIT = 200;

/** To whom a credential is lent: a group (a grant) or a service account (a lending). */
export type LendTo = 'group' | 'service-account';

/** What was lent, for the page's notice. */
export interface Lent {
  readonly to: LendTo;
  /** The group's or the service account's name. */
  readonly name: string;
  readonly access: AccountAccess;
}

/**
 * The credential a service account borrows on the system now, other than this one: lending it
 * this one replaces that one (a service account borrows at most one per system, ADR-0072).
 */
export function borrowedElsewhere(
  credentials: readonly SystemCredential[],
  credentialId: string,
  serviceIdentityId: string,
): SystemCredential | null {
  return (
    credentials.find(
      (c) =>
        c.id !== credentialId &&
        c.serviceAccounts.some((lending) => lending.serviceIdentityId === serviceIdentityId),
    ) ?? null
  );
}

/**
 * Lend a system's credential (ADR-0072). To a group (`createGrant`): its members may then act with
 * it in their own conversations, when they hold no account of their own on the system, read-only
 * (no write tool) or with writes. To a service account (`lendSystemCredential`): the triggers that
 * act as it act with this credential; it borrows one per system, so lending it this one replaces
 * what it borrowed here, and its triggers that acted with that one switch to this one, their authors
 * told. The groups and service accounts it is lent to already are not offered.
 */
@Component({
  selector: 'app-credential-lend-dialog',
  imports: [
    NgIcon,
    HlmAlertImports,
    HlmButton,
    HlmDialogImports,
    HlmFieldImports,
    HlmNativeSelectImports,
    HlmSpinner,
    Segmented,
  ],
  providers: [provideIcons({ lucideCircleAlert, lucideTriangleAlert })],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <hlm-dialog [state]="open() ? 'open' : 'closed'" (closed)="open.set(false)">
      <hlm-dialog-content
        *hlmDialogPortal="let ctx"
        class="max-h-[92svh] overflow-y-auto sm:max-w-md"
        data-testid="lend-dialog"
      >
        <hlm-dialog-header>
          <h2 hlmDialogTitle>
            Lend {{ credential()?.name }} to a {{ to() === 'group' ? 'group' : 'service account' }}
          </h2>
          <p hlmDialogDescription>
            @if (to() === 'group') {
              The group’s members may act with it on {{ system().name }} in their own conversations,
              when they hold no account of their own there.
            } @else {
              Triggers that act as the service account act with it on {{ system().name }}, and the
              service account’s budget pays. A service account borrows one credential per system.
            }
          </p>
        </hlm-dialog-header>

        <form id="lend-form" class="flex flex-col gap-4" novalidate (submit)="onSubmit($event)">
          @if (error(); as message) {
            <div hlmAlert variant="destructive" data-testid="lend-error">
              <ng-icon name="lucideCircleAlert" aria-hidden="true" />
              <p hlmAlertDescription>{{ message }}</p>
            </div>
          }

          @if (to() === 'group') {
            <div hlmField>
              <label hlmFieldLabel for="lend-group">Group</label>
              <hlm-native-select
                class="w-full"
                selectId="lend-group"
                [value]="pickedId()"
                [disabled]="groups.isLoading()"
                (valueChange)="pickedId.set($event ?? '')"
              >
                <option value="">
                  {{
                    groups.isLoading()
                      ? 'Loading…'
                      : groupOptions().length
                        ? 'Pick a group'
                        : 'Every group has it already'
                  }}
                </option>
                @for (group of groupOptions(); track group.id) {
                  <option [value]="group.id">{{ group.name }}</option>
                }
              </hlm-native-select>
              @if (submitted() && !pickedId()) {
                <hlm-field-error forceShow>Pick the group to lend it to.</hlm-field-error>
              }
            </div>
          } @else {
            <div hlmField>
              <label hlmFieldLabel for="lend-service-account">Service account</label>
              <hlm-native-select
                class="w-full"
                selectId="lend-service-account"
                [value]="pickedId()"
                [disabled]="serviceAccounts.isLoading()"
                (valueChange)="pickedId.set($event ?? '')"
              >
                <option value="">
                  {{
                    serviceAccounts.isLoading()
                      ? 'Loading…'
                      : serviceAccountOptions().length
                        ? 'Pick a service account'
                        : 'No service account to lend it to'
                  }}
                </option>
                @for (account of serviceAccountOptions(); track account.id) {
                  <option [value]="account.id">
                    {{ account.name }}{{ account.status === 'SUSPENDED' ? ' (suspended)' : '' }}
                  </option>
                }
              </hlm-native-select>
              @if (submitted() && !pickedId()) {
                <hlm-field-error forceShow>Pick the service account to lend it to.</hlm-field-error>
              }
            </div>
            @if (replaces(); as other) {
              <div hlmAlert data-testid="lend-replaces">
                <ng-icon name="lucideTriangleAlert" aria-hidden="true" />
                <p hlmAlertDescription class="text-foreground text-pretty">
                  {{ pickedName() }} borrows {{ other.name }} on {{ system().name }} now. Lending it
                  {{ credential()?.name }} replaces {{ other.name
                  }}{{ other.following ? ': ' + other.following + '.' : '; no trigger acts with it.' }}
                </p>
              </div>
            }
          }

          <div hlmField>
            <span hlmFieldLabel id="lend-access-label">Access</span>
            <app-segmented
              inputId="lend-access"
              labelledBy="lend-access-label"
              [options]="accessOptions"
              [value]="access()"
              (valueChange)="setAccess($event)"
            />
            <p hlmFieldDescription>{{ accessHint() }}</p>
          </div>
        </form>

        <hlm-dialog-footer>
          <button hlmBtn variant="outline" type="button" hlmDialogClose>Cancel</button>
          <button hlmBtn type="submit" form="lend-form" data-testid="lend-save" [disabled]="busy()">
            @if (busy()) {
              <hlm-spinner aria-hidden="true" />
            }
            Lend it
          </button>
        </hlm-dialog-footer>
      </hlm-dialog-content>
    </hlm-dialog>
  `,
})
export class CredentialLendDialog {
  private readonly api = inject(Api);

  readonly open = model(false);
  readonly companyId = input.required<string>();
  readonly system = input.required<ExternalSystem>();
  /** The credential to lend. */
  readonly credential = input<SystemCredential | null>(null);
  readonly to = input<LendTo>('group');
  /** The system's credentials, to say which one a service account borrows here now. */
  readonly credentials = input<readonly SystemCredential[]>([]);
  readonly lent = output<Lent>();

  protected readonly accessOptions: readonly SegmentedOption[] = ACCESS_CHOICES.map((choice) => ({
    value: choice.value,
    label: choice.label,
  }));
  protected readonly pickedId = signal('');
  protected readonly access = signal<AccountAccess>('READ');
  protected readonly accessHint = computed(
    () => ACCESS_CHOICES.find((choice) => choice.value === this.access())?.hint ?? '',
  );
  protected readonly submitted = signal(false);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);

  /** Read each time the dialog opens, so that a new group or service account is there. */
  private readonly loadKey = computed(() =>
    this.open() ? { companyId: this.companyId(), to: this.to(), at: Date.now() } : undefined,
  );

  protected readonly groups = resource({
    params: () => {
      const key = this.loadKey();
      return key?.to === 'group' ? key : undefined;
    },
    loader: ({ params }) =>
      loadAll((cursor) =>
        this.api.invoke(listGroups, { companyId: params.companyId, limit: LIMIT, cursor }),
      ),
  });
  protected readonly groupOptions = computed(() => {
    const lent = new Set((this.credential()?.grants ?? []).map((g) => g.groupId));
    return (this.groups.hasValue() ? this.groups.value() : []).filter((g) => !lent.has(g.id));
  });

  protected readonly serviceAccounts = resource({
    params: () => {
      const key = this.loadKey();
      return key?.to === 'service-account' ? key : undefined;
    },
    loader: ({ params }) =>
      loadAll((cursor) =>
        this.api.invoke(listServiceIdentities, {
          companyId: params.companyId,
          limit: LIMIT,
          cursor,
        }),
      ),
  });
  protected readonly serviceAccountOptions = computed(() => {
    const lent = new Set(
      (this.credential()?.serviceAccounts ?? []).map((l) => l.serviceIdentityId),
    );
    return (this.serviceAccounts.hasValue() ? this.serviceAccounts.value() : []).filter(
      (s) => !lent.has(s.id) && s.status !== 'LEFT',
    );
  });
  protected readonly pickedName = computed(
    () => this.serviceAccountOptions().find((s) => s.id === this.pickedId())?.name ?? '',
  );
  /**
   * What the picked service account borrows on the system now, which this lending replaces, and
   * its triggers that act with that one and switch to this one (none: nothing switches).
   */
  protected readonly replaces = computed(() => {
    const credential = this.credential();
    const picked = this.pickedId();
    if (!credential || !picked || this.to() !== 'service-account') return null;
    const other = borrowedElsewhere(this.credentials(), credential.id, picked);
    if (!other) return null;
    const lending = other.serviceAccounts.find((l) => l.serviceIdentityId === picked);
    return {
      name: other.name,
      following: lending ? triggersFollowing(lending, credential.name) : null,
    };
  });

  constructor() {
    effect(() => {
      if (!this.open()) return;
      untracked(() => {
        this.pickedId.set('');
        // A service account's own credential was always WRITE (ADR-0072, open point 1).
        this.access.set(this.to() === 'group' ? 'READ' : 'WRITE');
        this.submitted.set(false);
        this.busy.set(false);
        this.error.set(null);
      });
    });
  }

  protected setAccess(value: string): void {
    if (value === 'READ' || value === 'WRITE') this.access.set(value);
  }

  protected async onSubmit(event: Event): Promise<void> {
    event.preventDefault();
    this.submitted.set(true);
    const credential = this.credential();
    const picked = this.pickedId();
    if (!credential || !picked || this.busy()) return;
    this.busy.set(true);
    this.error.set(null);
    const access = this.access();
    try {
      if (this.to() === 'group') {
        const grant = await this.api.invoke(createGrant, {
          companyId: this.companyId(),
          body: { credentialId: credential.id, groupId: picked, access },
        });
        this.open.set(false);
        this.lent.emit({ to: 'group', name: grant.groupName, access: grant.access });
      } else {
        const name = this.pickedName();
        await this.api.invoke(lendSystemCredential, {
          companyId: this.companyId(),
          systemId: this.system().id,
          credentialId: credential.id,
          serviceIdentityId: picked,
          body: { access },
        });
        this.open.set(false);
        this.lent.emit({ to: 'service-account', name, access });
      }
    } catch (error) {
      this.error.set(problemMessage(error, CREDENTIAL_MESSAGES));
    } finally {
      this.busy.set(false);
    }
  }
}
