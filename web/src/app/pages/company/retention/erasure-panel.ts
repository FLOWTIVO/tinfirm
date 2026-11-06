import {
  afterNextRender,
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  Injector,
  input,
  linkedSignal,
  output,
  resource,
  signal,
} from '@angular/core';
import { NgIcon, provideIcons } from '@ng-icons/core';
import {
  lucideArrowLeft,
  lucideArrowRight,
  lucideCheck,
  lucideCircleAlert,
  lucideCircleCheck,
  lucideEraser,
  lucideExternalLink,
  lucideGlobe,
  lucideInfo,
  lucideLock,
  lucideShieldCheck,
} from '@ng-icons/lucide';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmFieldImports } from '@spartan-ng/helm/field';
import { HlmInput } from '@spartan-ng/helm/input';
import { HlmNativeSelectImports } from '@spartan-ng/helm/native-select';
import { HlmSpinner } from '@spartan-ng/helm/spinner';
import { Api } from '../../../api/generated/api';
import {
  eraseExternalIdentity,
  erasePrincipal,
  listSystems,
  previewExternalIdentityErasure,
  previewPrincipalErasure,
} from '../../../api/generated/functions';
import type { Erasure, ErasurePreview, LegalHold } from '../../../api/generated/models';
import { hasProblemCode, problemHoldIds, problemMessage } from '../../../api/problem';
import { loadAll } from '../../../core/company-data';
import { CurrentCompany } from '../../../core/current-company';
import { Session } from '../../../core/session';
import { erasedPhrase, formatCount, remainsLabel } from '../../../shared/governance-labels';
import {
  askPhrase,
  confirmLead,
  confirmMatches,
  confirmPhrase,
  type EraseTarget,
  goesCounts,
  heldTitle,
  holdOn,
  remainsReview,
  reviewOf,
  targetLabel,
  targetNote,
} from './erasure-view';
import { holdSetPhrase, holdTarget } from './holds-panel';
import type { PersonChoice } from './person-choice';

type Step = 1 | 2 | 3;

/** What the erasure answered, or why it was refused. */
interface Outcome {
  readonly label: string;
  readonly erasure?: Erasure;
  readonly error?: string;
  /** For `409 ON_HOLD`: the holds it names, by what they hold. */
  readonly holds?: readonly string[];
}

const STEPS: readonly { step: Step; label: string }[] = [
  { step: 1, label: 'Whose content' },
  { step: 2, label: 'Review what goes' },
  { step: 3, label: 'Confirm' },
];

/** The step pills, as the system dialog's (DESIGN.md: phase chips): current, passed, to come. */
const STEP_PILL = {
  current: 'border-primary bg-primary text-primary-foreground pe-2.5',
  done: 'bg-muted text-primary pe-2.5',
  todo: 'border-input text-muted-foreground border-dashed pe-2.5',
} as const;

const STEP_DOT = {
  current: 'bg-primary-foreground/20',
  done: 'bg-primary/10',
  todo: 'border-input border',
} as const;

const WHO = [
  {
    value: 'member',
    label: 'A member',
    help: 'For a member who left: their personal conversations, and what they said, added and decided in others’. For a member still here: their ended personal conversations.',
  },
  {
    value: 'outside',
    label: 'Someone outside Tinfirm',
    help: 'A chat user’s or a commenter’s words in every conversation, open ones included, and the transcripts that quoted them.',
  },
] as const;

/** Two targets are the same person, or the same identity at the same system. */
function sameTarget(a: EraseTarget, b: EraseTarget): boolean {
  if (a.kind === 'person' && b.kind === 'person') {
    return a.principalId === b.principalId && a.left === b.left;
  }
  if (a.kind === 'identity' && b.kind === 'identity') {
    return a.systemId === b.systemId && a.externalId === b.externalId;
  }
  return false;
}

/** What the dry run is asked about: the company, the target, and the holds the page knows now. */
interface PreviewKey {
  readonly companyId: string;
  readonly target: EraseTarget;
  readonly holds: readonly LegalHold[];
}

/**
 * Erasure on request (the package 6 contract, §5.9, open point 17; CONVENTIONS §15.11), the
 * Owner's, in three steps: whose content (a member, or someone outside Tinfirm by their id at a
 * system), a review of what goes, what stays and what is not Tinfirm's to erase, then the person's
 * name typed to erase. The review is the erasure's dry run (`…/erase/preview`): what goes, counted
 * by the erasure's own selection, what remains, and the open holds that would refuse it, on the
 * person or on a conversation it reaches. A hold stops it there: no way on to the confirmation,
 * and whom to ask to lift it. Until the dry run answers, or if it fails, the review says it in
 * words, and the erasure itself still refuses under a hold (`409 ON_HOLD`, naming the holds). The
 * answer says what went and what remains, where, and who can remove it.
 */
@Component({
  selector: 'app-erasure-panel',
  imports: [NgIcon, HlmButton, HlmFieldImports, HlmInput, HlmNativeSelectImports, HlmSpinner],
  providers: [
    provideIcons({
      lucideArrowLeft,
      lucideArrowRight,
      lucideCheck,
      lucideCircleAlert,
      lucideCircleCheck,
      lucideEraser,
      lucideExternalLink,
      lucideGlobe,
      lucideInfo,
      lucideLock,
      lucideShieldCheck,
    }),
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section
      class="bg-card rounded-xl border"
      aria-labelledby="erasure-title"
      data-testid="erasure"
    >
      <div class="px-4 pt-4 pb-3.5 sm:px-5">
        <h2 class="text-base font-bold" id="erasure-title">Erase on request</h2>
        <p class="text-muted-foreground mt-0.5 text-sm text-pretty">
          Removes someone’s content now, as retention would, and says what remains. It cannot be
          undone, and a legal hold stops it.
        </p>
        <ol
          class="mt-3.5 flex flex-wrap items-center gap-1.5"
          aria-label="Steps"
          data-testid="erasure-steps"
        >
          @for (item of stepItems(); track item.step; let first = $first) {
            <li
              class="flex items-center gap-1.5"
              [attr.aria-current]="item.state === 'current' ? 'step' : null"
            >
              @if (!first) {
                <span class="bg-input h-px w-3 sm:w-4" aria-hidden="true"></span>
              }
              <span
                class="inline-flex h-7 items-center gap-1.5 rounded-full border ps-1 text-xs font-semibold"
                [class]="item.pill"
              >
                <span
                  class="inline-flex size-5 items-center justify-center rounded-full font-mono text-[10px]"
                  [class]="item.dot"
                  aria-hidden="true"
                >
                  @if (item.state === 'done') {
                    <ng-icon name="lucideCheck" size="11" />
                  } @else {
                    {{ item.step }}
                  }
                </span>
                <span [class]="item.labelClass">{{ item.label }}</span>
                @if (item.state === 'done') {
                  <span class="sr-only">, done</span>
                }
              </span>
            </li>
          }
        </ol>
      </div>

      @if (outcome()?.erasure; as e) {
        <!-- Done: what went and what remains, as the server answered. -->
        <div class="flex flex-col items-start gap-3.5 border-t px-4 py-4 sm:px-5">
          <div
            class="bg-success/10 flex items-start gap-3 self-stretch rounded-xl px-4 py-3.5"
            role="status"
            data-testid="erasure-result"
          >
            <ng-icon
              name="lucideCircleCheck"
              size="16"
              class="text-success mt-0.5 shrink-0"
              aria-hidden="true"
            />
            <div class="flex min-w-0 flex-col gap-1.5 text-sm">
              <h3 class="m-0 font-bold">{{ outcome()!.label }}: erased</h3>
              <p class="m-0">{{ erased(e.erased) }}</p>
              @if (e.remains.length) {
                <p class="m-0 mt-1 font-semibold">What remains</p>
                <dl class="m-0 flex flex-col gap-1.5" data-testid="erasure-remains">
                  @for (r of e.remains; track r.what) {
                    <div>
                      <dt class="font-medium">
                        {{ remains(r) }}
                        @if (r.count !== null && r.count !== undefined) {
                          ({{ r.count }})
                        }
                      </dt>
                      <dd class="text-muted-foreground m-0 text-[0.8125rem]">
                        {{ r.where }}. {{ r.removedBy }}
                      </dd>
                    </div>
                  }
                </dl>
              }
              <p class="text-muted-foreground m-0 mt-1 text-[0.8125rem]">
                Recorded in the audit log as
                <span class="font-mono">{{
                  doneKind() === 'person' ? 'principal.erased' : 'identity.erased'
                }}</span
                >.
              </p>
            </div>
          </div>
          <button
            hlmBtn
            variant="outline"
            size="sm"
            type="button"
            class="max-sm:h-11"
            data-testid="erase-again"
            (click)="restart()"
          >
            Erase someone else’s content
          </button>
        </div>
      } @else {
        @switch (step()) {
          @case (1) {
            <div class="flex flex-col gap-4 border-t px-4 py-4 sm:px-5">
              <div role="radiogroup" aria-label="Whose content" class="grid gap-3 md:grid-cols-2">
                @for (choice of whoChoices; track choice.value) {
                  <label
                    class="has-checked:border-primary has-checked:ring-primary has-focus-visible:ring-ring/50 hover:bg-muted/60 flex cursor-pointer items-start gap-3 rounded-xl border px-4 py-3.5 has-checked:ring-1 has-focus-visible:ring-3"
                    [attr.data-who]="choice.value"
                  >
                    <input
                      type="radio"
                      name="erase-who"
                      class="peer sr-only"
                      [id]="'erase-who-' + choice.value"
                      [value]="choice.value"
                      [checked]="who() === choice.value"
                      [attr.aria-describedby]="'erase-who-' + choice.value + '-help'"
                      (change)="who.set(choice.value)"
                    />
                    <span
                      class="border-input bg-card peer-checked:border-primary mt-0.5 size-[18px] shrink-0 rounded-full border-[1.5px] peer-checked:border-[5px]"
                      aria-hidden="true"
                    ></span>
                    <span class="min-w-0">
                      <span class="block text-sm font-semibold">{{ choice.label }}</span>
                      <span
                        class="text-muted-foreground block text-[0.8125rem] leading-normal text-pretty"
                        [id]="'erase-who-' + choice.value + '-help'"
                        >{{ choice.help }}</span
                      >
                    </span>
                  </label>
                }
              </div>
              @if (who() === 'member') {
                <div hlmField class="w-full max-w-sm">
                  <label hlmFieldLabel for="erase-person">Member</label>
                  <hlm-native-select
                    class="w-full"
                    selectId="erase-person"
                    selectClass="max-sm:h-11"
                    [value]="person()"
                    (valueChange)="person.set($event ?? '')"
                  >
                    @for (p of people(); track p.value) {
                      <option [value]="p.value">{{ p.label }}</option>
                    }
                  </hlm-native-select>
                  @if (personNote(); as note) {
                    <p hlmFieldDescription data-testid="erase-person-note">{{ note }}</p>
                  }
                </div>
              } @else {
                <div class="flex flex-col gap-1.5">
                  <div class="flex flex-wrap items-start gap-3">
                    <div hlmField class="w-full sm:w-48">
                      <label hlmFieldLabel for="erase-system">System</label>
                      <hlm-native-select
                        class="w-full"
                        selectId="erase-system"
                        selectClass="max-sm:h-11"
                        [value]="systemId()"
                        (valueChange)="systemId.set($event ?? '')"
                      >
                        @for (s of systems(); track s.id) {
                          <option [value]="s.id">{{ s.name }}</option>
                        }
                      </hlm-native-select>
                    </div>
                    <div hlmField class="w-full sm:w-64">
                      <label hlmFieldLabel for="erase-external">Their id there</label>
                      <input
                        hlmInput
                        id="erase-external"
                        class="font-mono max-sm:h-11"
                        autocomplete="off"
                        spellcheck="false"
                        maxlength="255"
                        aria-describedby="erase-external-help"
                        [value]="externalId()"
                        (input)="externalId.set($any($event.target).value)"
                        (keydown.enter)="$event.preventDefault(); goTo(2)"
                      />
                    </div>
                  </div>
                  <p class="text-muted-foreground m-0 text-[0.8125rem]" id="erase-external-help">
                    As the system knows them: a Slack member ID, a GitHub or Jira username.
                  </p>
                </div>
              }
            </div>
            <div class="flex flex-wrap items-center justify-end gap-2 border-t px-4 py-3.5 sm:px-5">
              <button
                hlmBtn
                type="button"
                class="max-sm:h-11"
                data-testid="erase-review"
                [disabled]="!target()"
                (click)="goTo(2)"
              >
                Review what goes
                <ng-icon name="lucideArrowRight" aria-hidden="true" />
              </button>
            </div>
          }
          @default {
            @let t = target()!;
            <div
              class="flex items-center gap-3 border-t px-4 py-3.5 sm:px-5"
              data-testid="erasure-target"
            >
              <span
                class="bg-secondary text-foreground flex size-8 shrink-0 items-center justify-center rounded-[10px] text-xs font-bold"
                aria-hidden="true"
              >
                @if (t.kind === 'person') {
                  {{ initials() }}
                } @else {
                  <ng-icon name="lucideGlobe" size="16" />
                }
              </span>
              <div class="min-w-0 flex-1">
                <p class="m-0 truncate text-sm font-semibold">{{ label() }}</p>
                <p class="text-muted-foreground m-0 text-[0.8125rem]">{{ note() }}</p>
              </div>
              <button
                hlmBtn
                variant="ghost"
                size="sm"
                type="button"
                class="max-sm:h-11"
                data-testid="erase-change"
                (click)="goTo(1)"
              >
                Change
              </button>
            </div>

            @if (step() === 2) {
              <div class="flex flex-col gap-4 px-4 pb-4 sm:px-5">
                <h3 class="sr-only" id="erase-step-title" tabindex="-1">
                  Step 2 of 3: review what goes
                </h3>
                @if (held()) {
                  <div
                    class="bg-warning/10 flex items-start gap-3 rounded-xl px-4 py-3.5"
                    role="alert"
                    data-testid="erasure-held"
                  >
                    <ng-icon
                      name="lucideLock"
                      size="16"
                      class="text-warning mt-0.5 shrink-0"
                      aria-hidden="true"
                    />
                    <div class="flex min-w-0 flex-1 flex-col gap-1 text-sm">
                      <p class="m-0 font-bold">{{ heldHeading() }}</p>
                      <ul
                        class="m-0 flex list-none flex-col gap-1 p-0"
                        data-testid="erasure-holds-named"
                      >
                        @for (h of blocking(); track h.id) {
                          <li class="text-pretty break-words">
                            “{{ h.reason }}”
                            <span class="text-muted-foreground"
                              >{{ heldWhat(h) }} · {{ setPhrase(h) }}</span
                            >
                          </li>
                        }
                      </ul>
                      <p class="m-0 mt-1 text-pretty" data-testid="erasure-ask">
                        Nothing of it can be erased while
                        {{ blocking().length === 1 ? 'the hold stands' : 'they stand' }}.
                        {{ ask() }}
                      </p>
                      <p class="m-0 mt-1.5">
                        <button
                          hlmBtn
                          variant="outline"
                          size="sm"
                          type="button"
                          class="max-sm:h-11"
                          id="erase-to-holds"
                          data-testid="erase-to-holds"
                          (click)="toHolds.emit()"
                        >
                          Go to Legal holds
                          <ng-icon name="lucideArrowRight" aria-hidden="true" />
                        </button>
                      </p>
                    </div>
                  </div>
                }
                <div class="grid items-start gap-4 md:grid-cols-2" data-testid="erasure-review">
                  <div
                    class="rounded-xl border px-4 pt-3.5 pb-1"
                    [class]="held() ? 'bg-muted/60' : ''"
                    data-testid="erasure-goes"
                  >
                    <h3 class="m-0 mb-1.5 flex items-center gap-2 text-sm font-bold">
                      <ng-icon
                        name="lucideEraser"
                        size="16"
                        class="text-destructive"
                        aria-hidden="true"
                      />
                      @if (!held()) {
                        Goes now
                      } @else if (blocking().length === 1) {
                        Would go, once the hold is lifted
                      } @else {
                        Would go, once the holds are lifted
                      }
                    </h3>
                    @if (goes(); as items) {
                      @if (items.length) {
                        <ul class="m-0 list-none p-0" data-testid="erasure-counts">
                          @for (item of items; track item.what) {
                            <li class="flex items-start gap-3 border-t py-2.5">
                              <span
                                class="min-w-10 shrink-0 text-right font-mono text-sm font-medium tabular-nums"
                                [class.text-muted-foreground]="held()"
                                >{{ count(item.count) }}</span
                              >
                              <span class="min-w-0">
                                <span
                                  class="block text-sm font-semibold"
                                  [class.text-muted-foreground]="held()"
                                  >{{ item.what }}</span
                                >
                                @if (item.detail) {
                                  <span
                                    class="text-muted-foreground block text-xs leading-normal"
                                    >{{ item.detail }}</span
                                  >
                                }
                              </span>
                            </li>
                          }
                        </ul>
                      } @else {
                        <p
                          class="text-muted-foreground m-0 border-t py-2.5 text-sm"
                          data-testid="erasure-nothing"
                        >
                          Nothing of theirs is left to erase.
                        </p>
                      }
                    } @else {
                      @if (preview.isLoading()) {
                        <p
                          class="text-muted-foreground m-0 flex items-center gap-2 border-t py-2.5 text-xs"
                          role="status"
                          data-testid="erasure-counting"
                        >
                          <hlm-spinner class="size-3.5" aria-hidden="true" />
                          Counting what goes…
                        </p>
                      }
                      <ul class="m-0 list-none p-0">
                        @for (item of review()!.goes; track item.what) {
                          <li class="border-t py-2.5">
                            <span
                              class="block text-sm font-semibold"
                              [class.text-muted-foreground]="held()"
                              >{{ item.what }}</span
                            >
                            <span class="text-muted-foreground block text-xs leading-normal">{{
                              item.detail
                            }}</span>
                          </li>
                        }
                      </ul>
                    }
                  </div>
                  <div class="rounded-xl border px-4 pt-3.5 pb-1" data-testid="erasure-stays">
                    <h3 class="m-0 mb-1.5 flex items-center gap-2 text-sm font-bold">
                      <ng-icon
                        name="lucideShieldCheck"
                        size="16"
                        class="text-success"
                        aria-hidden="true"
                      />
                      Stays
                    </h3>
                    <ul class="m-0 list-none p-0">
                      @for (item of remaining()!.stays; track item.what) {
                        <li class="flex items-start gap-2.5 border-t py-2.5">
                          <ng-icon
                            name="lucideCheck"
                            size="14"
                            class="text-muted-foreground mt-1 shrink-0"
                            aria-hidden="true"
                          />
                          <span class="min-w-0">
                            <span class="block text-sm font-semibold">{{ item.what }}</span>
                            <span class="text-muted-foreground block text-xs leading-normal">{{
                              item.detail
                            }}</span>
                          </span>
                        </li>
                      }
                    </ul>
                    <h4 class="text-muted-foreground m-0 mt-2.5 mb-0.5 text-xs font-semibold">
                      Not Tinfirm’s to erase here
                    </h4>
                    <ul class="m-0 list-none p-0" data-testid="erasure-elsewhere">
                      @for (item of remaining()!.elsewhere; track item.what) {
                        <li class="flex items-start gap-2.5 border-t py-2.5">
                          <ng-icon
                            name="lucideExternalLink"
                            size="14"
                            class="text-muted-foreground mt-1 shrink-0"
                            aria-hidden="true"
                          />
                          <span class="min-w-0">
                            <span class="block text-sm font-semibold">{{ item.what }}</span>
                            <span class="text-muted-foreground block text-xs leading-normal">{{
                              item.detail
                            }}</span>
                          </span>
                        </li>
                      }
                    </ul>
                  </div>
                </div>
                @if (previewFailed(); as failure) {
                  <div
                    class="text-muted-foreground flex flex-wrap items-start gap-2 text-[0.8125rem] leading-normal text-pretty"
                    role="alert"
                    data-testid="erasure-preview-failed"
                  >
                    <ng-icon
                      name="lucideCircleAlert"
                      size="14"
                      class="text-destructive mt-0.5 shrink-0"
                      aria-hidden="true"
                    />
                    <span class="min-w-0 flex-1 basis-48"
                      >Couldn’t count what goes: {{ failure }} The erasure still refuses if a legal
                      hold covers any of it, and its result says what went.</span
                    >
                    <button
                      hlmBtn
                      variant="outline"
                      size="sm"
                      type="button"
                      class="max-sm:h-11"
                      data-testid="erasure-preview-retry"
                      (click)="preview.reload()"
                    >
                      Count again
                    </button>
                  </div>
                } @else if (previewed()) {
                  <p
                    class="text-muted-foreground m-0 flex items-start gap-2 text-[0.8125rem] leading-normal text-pretty"
                    data-testid="erasure-counted"
                  >
                    <ng-icon
                      name="lucideInfo"
                      size="14"
                      class="mt-0.5 shrink-0"
                      aria-hidden="true"
                    />
                    <span
                      >Counted now, as the erasure counts, without erasing anything. If more comes
                      in before you confirm, the result says what went.</span
                    >
                  </p>
                }
              </div>
              <div class="flex flex-wrap items-center gap-2 border-t px-4 py-3.5 sm:px-5">
                <button hlmBtn variant="ghost" type="button" class="max-sm:h-11" (click)="goTo(1)">
                  <ng-icon name="lucideArrowLeft" aria-hidden="true" />
                  Back
                </button>
                <span class="flex-1"></span>
                @if (!held()) {
                  <button
                    hlmBtn
                    type="button"
                    class="max-sm:h-11"
                    id="erase-next"
                    data-testid="erase-next"
                    (click)="goTo(3)"
                  >
                    Next: confirm
                    <ng-icon name="lucideArrowRight" aria-hidden="true" />
                  </button>
                }
              </div>
            } @else {
              <form novalidate (submit)="$event.preventDefault(); erase()">
                <div class="flex flex-col gap-3.5 px-4 pb-4 sm:px-5">
                  <p class="m-0 text-sm font-semibold text-pretty" data-testid="erase-lead">
                    {{ lead() }}
                  </p>
                  <p class="text-muted-foreground m-0 text-sm">
                    It cannot be undone. What each conversation was, its spend and the audit log
                    stay.
                  </p>
                  <div hlmField class="w-full max-w-sm">
                    <label hlmFieldLabel for="erase-typed"
                      >Type&nbsp;<span [class.font-mono]="t.kind === 'identity'">{{
                        phrase()
                      }}</span
                      >&nbsp;to confirm</label
                    >
                    <input
                      hlmInput
                      id="erase-typed"
                      class="max-sm:h-11"
                      autocomplete="off"
                      spellcheck="false"
                      [value]="typed()"
                      (input)="typed.set($any($event.target).value)"
                    />
                  </div>
                  @if (outcome(); as o) {
                    <div
                      class="bg-destructive/10 flex items-start gap-3 rounded-xl px-4 py-3.5"
                      role="alert"
                      data-testid="erasure-refused"
                    >
                      <ng-icon
                        name="lucideCircleAlert"
                        size="16"
                        class="text-destructive mt-0.5 shrink-0"
                        aria-hidden="true"
                      />
                      <div class="flex min-w-0 flex-1 flex-col gap-1 text-sm">
                        <h3 class="text-destructive m-0 font-bold">
                          {{ o.label }}: nothing was erased
                        </h3>
                        <p class="m-0">{{ o.error }}</p>
                        @if (o.holds?.length) {
                          <ul class="m-0 list-disc ps-5" data-testid="erasure-holds">
                            @for (h of o.holds; track h) {
                              <li>{{ h }}</li>
                            }
                          </ul>
                          <p class="m-0 mt-1.5">
                            <button
                              hlmBtn
                              variant="outline"
                              size="sm"
                              type="button"
                              class="max-sm:h-11"
                              (click)="toHolds.emit()"
                            >
                              Go to Legal holds
                              <ng-icon name="lucideArrowRight" aria-hidden="true" />
                            </button>
                          </p>
                        }
                      </div>
                    </div>
                  }
                </div>
                <div class="flex flex-wrap items-center gap-2 border-t px-4 py-3.5 sm:px-5">
                  <button
                    hlmBtn
                    variant="ghost"
                    type="button"
                    class="max-sm:h-11"
                    [disabled]="busy()"
                    (click)="goTo(2)"
                  >
                    <ng-icon name="lucideArrowLeft" aria-hidden="true" />
                    Back
                  </button>
                  <span class="flex-1"></span>
                  <button
                    hlmBtn
                    variant="destructive"
                    type="submit"
                    class="max-sm:h-11"
                    data-testid="confirm-erase"
                    [disabled]="busy() || !matches() || held()"
                  >
                    @if (busy()) {
                      <hlm-spinner aria-hidden="true" />
                    } @else {
                      <ng-icon name="lucideEraser" aria-hidden="true" />
                    }
                    {{ eraseLabel() }}
                  </button>
                </div>
              </form>
            }
          }
        }
      }
    </section>
  `,
})
export class ErasurePanel {
  private readonly api = inject(Api);
  private readonly current = inject(CurrentCompany);
  private readonly session = inject(Session);
  private readonly injector = inject(Injector);

  // Not required: the page's rail reads the summary before the inputs are first set.
  readonly holds = input<readonly LegalHold[]>([]);
  readonly people = input<readonly PersonChoice[]>([]);
  /** Members' names by their user id: who set a hold that stops the erasure. */
  readonly names = input<ReadonlyMap<string, string>>(new Map());
  /** The person picked first (a checklist's `?erase=`), else the first. */
  readonly preselect = input<string | null>(null);
  /** The person asked to see the legal holds. */
  readonly toHolds = output<void>();
  /** An erasure was refused for a hold: the page reads the holds again. */
  readonly refusedForHold = output<void>();

  protected readonly erased = erasedPhrase;
  protected readonly remains = remainsLabel;
  protected readonly whoChoices = WHO;

  protected readonly step = signal<Step>(1);
  protected readonly who = signal<'member' | 'outside'>('member');
  protected readonly person = linkedSignal(() => this.preselect() ?? this.people()[0]?.value ?? '');
  protected readonly externalId = signal('');
  protected readonly typed = signal('');
  protected readonly busy = signal(false);
  protected readonly outcome = signal<Outcome | null>(null);
  /** The kind of the target erased: its audit action. */
  protected readonly doneKind = signal<EraseTarget['kind']>('person');

  private readonly systemsList = resource({
    params: () => this.current.id(),
    loader: ({ params: companyId }) =>
      loadAll((cursor) => this.api.invoke(listSystems, { companyId, limit: 100, cursor })),
  });
  protected readonly systems = computed(() =>
    (this.systemsList.hasValue() ? (this.systemsList.value() ?? []) : [])
      .filter((s) => !s.personal)
      .sort((a, b) => a.name.localeCompare(b.name)),
  );
  protected readonly systemId = linkedSignal(() => this.systems()[0]?.id ?? '');

  /** Whose content, as picked; null until a member or an id at a system is given. */
  readonly target = computed<EraseTarget | null>(() => {
    if (this.who() === 'member') {
      const choice = this.people().find((p) => p.value === this.person());
      return choice
        ? { kind: 'person', principalId: choice.value, name: choice.name, left: choice.left }
        : null;
    }
    const system = this.systems().find((s) => s.id === this.systemId());
    const externalId = this.externalId().trim();
    return system && externalId
      ? { kind: 'identity', systemId: system.id, systemName: system.name, externalId }
      : null;
  });

  protected readonly label = computed(() => {
    const t = this.target();
    return t ? targetLabel(t) : '';
  });
  protected readonly note = computed(() => {
    const t = this.target();
    return t ? targetNote(t) : '';
  });
  protected readonly initials = computed(() =>
    this.label()
      .split(/\s+/)
      .filter(Boolean)
      .slice(0, 2)
      .map((w) => w[0]!.toUpperCase())
      .join(''),
  );
  protected readonly personNote = computed(() => {
    const t = this.target();
    if (!t || t.kind !== 'person') return '';
    return t.left
      ? `${t.name} left ${this.current.name() ?? 'the company'}: everything of theirs goes.`
      : `${t.name} is still a member: only their ended personal conversations go.`;
  });
  // --- The dry run (`…/erase/preview`) ---------------------------------------------------------

  /** Asked from the review on, again when the target or the known holds change; not per step. */
  private readonly previewKey = computed<PreviewKey | undefined>(
    () => {
      const companyId = this.current.id();
      const target = this.target();
      if (!companyId || !target || this.step() === 1 || this.outcome()?.erasure) return undefined;
      return { companyId, target, holds: this.holds() };
    },
    {
      equal: (a, b) =>
        a === b ||
        (!!a &&
          !!b &&
          a.companyId === b.companyId &&
          a.holds === b.holds &&
          sameTarget(a.target, b.target)),
    },
  );
  protected readonly preview = resource({
    params: () => this.previewKey(),
    loader: ({ params: { companyId, target } }): Promise<ErasurePreview> =>
      target.kind === 'person'
        ? this.api.invoke(previewPrincipalErasure, {
            companyId,
            principalId: target.principalId,
            body: {},
          })
        : this.api.invoke(previewExternalIdentityErasure, {
            companyId,
            systemId: target.systemId,
            externalId: target.externalId,
          }),
  });
  /** The dry run's answer, once it came. */
  protected readonly previewed = computed<ErasurePreview | null>(() =>
    this.preview.hasValue() ? (this.preview.value() ?? null) : null,
  );
  protected readonly previewFailed = computed(() =>
    this.preview.error() ? problemMessage(this.preview.error()) : null,
  );

  /** The words of the review, until the dry run answers or when it failed. */
  protected readonly review = computed(() => {
    const t = this.target();
    return t ? reviewOf(t) : null;
  });
  /** What goes, counted by the dry run. */
  protected readonly goes = computed(() => {
    const t = this.target();
    const p = this.previewed();
    return t && p ? goesCounts(t, p.erases) : null;
  });
  /** What stays and what is another's to remove: the dry run's, else the words'. */
  protected readonly remaining = computed(() => {
    const t = this.target();
    const p = this.previewed();
    if (!t) return null;
    return p ? remainsReview(t, p.remains) : this.review();
  });
  protected readonly count = formatCount;

  /**
   * The holds that stop it: those the dry run names; before it answers, or when it failed, a hold
   * on the person known from the holds loaded.
   */
  readonly blocking = computed<readonly LegalHold[]>(() => {
    const p = this.previewed();
    if (p) return p.holds;
    const t = this.target();
    const known = t ? holdOn(this.holds(), t) : null;
    return known ? [known] : [];
  });
  readonly held = computed(() => this.blocking().length > 0);
  protected readonly heldHeading = computed(() => {
    const t = this.target();
    return t ? heldTitle(t, this.blocking()) : '';
  });
  protected readonly ask = computed(() =>
    askPhrase(this.blocking(), this.names(), this.session.user()?.id),
  );
  protected setPhrase(hold: LegalHold): string {
    return holdSetPhrase(hold, this.names());
  }
  protected readonly heldWhat = holdTarget;

  protected readonly lead = computed(() => {
    const t = this.target();
    return t ? confirmLead(t, this.previewed()?.erases) : '';
  });
  protected readonly phrase = computed(() => {
    const t = this.target();
    return t ? confirmPhrase(t) : '';
  });
  protected readonly matches = computed(() => {
    const t = this.target();
    return !!t && confirmMatches(t, this.typed());
  });
  protected readonly eraseLabel = computed(() => {
    const t = this.target();
    return t?.kind === 'person' ? `Erase ${t.name}’s content` : 'Erase their words';
  });

  protected readonly stepItems = computed(() => {
    const done = !!this.outcome()?.erasure;
    const current = this.step();
    return STEPS.map(({ step, label }) => {
      const state = done || step < current ? 'done' : step === current ? 'current' : 'todo';
      return {
        step,
        label,
        state,
        pill: STEP_PILL[state],
        dot: STEP_DOT[state],
        // On a phone only the current step keeps its name on screen; readers hear them all.
        labelClass: state === 'current' ? '' : 'max-sm:sr-only',
      };
    });
  });

  /** What the rail says of this section now. */
  readonly summary = computed(() => {
    const label = this.label();
    if (this.outcome()?.erasure) return `Done · ${this.outcome()!.label}`;
    const step = this.step();
    if (step === 1 || !label) return 'Removes content now';
    if (step === 2 && this.held()) return `Held · ${label}`;
    return `Step ${step} of 3 · ${label}`;
  });

  protected goTo(step: Step): void {
    if (step > 1 && !this.target()) return;
    if (step === 3 && this.held()) return;
    if (step < 3) {
      this.typed.set('');
      this.outcome.set(null);
    }
    this.step.set(step);
    afterNextRender(
      () => {
        const id =
          step === 1 ? `erase-who-${this.who()}` : step === 2 ? 'erase-step-title' : 'erase-typed';
        document.getElementById(id)?.focus();
      },
      { injector: this.injector },
    );
  }

  protected restart(): void {
    this.outcome.set(null);
    this.typed.set('');
    this.externalId.set('');
    this.goTo(1);
  }

  protected async erase(): Promise<void> {
    const companyId = this.current.id();
    const target = this.target();
    if (!companyId || !target || this.busy() || !this.matches() || this.held()) return;
    this.busy.set(true);
    this.outcome.set(null);
    const label = targetLabel(target);
    try {
      let erasure: Erasure;
      if (target.kind === 'person') {
        erasure = await this.api.invoke(erasePrincipal, {
          companyId,
          principalId: target.principalId,
          body: {},
        });
      } else {
        erasure = await this.api.invoke(eraseExternalIdentity, {
          companyId,
          systemId: target.systemId,
          externalId: target.externalId,
        });
      }
      this.doneKind.set(target.kind);
      this.outcome.set({ label, erasure });
    } catch (error) {
      this.outcome.set({ label, ...this.refusal(error) });
    } finally {
      this.busy.set(false);
    }
  }

  private refusal(error: unknown): { error: string; holds?: string[] } {
    if (hasProblemCode(error, 'ON_HOLD')) {
      this.refusedForHold.emit();
      const ids = problemHoldIds(error);
      const known = this.holds();
      const named = ids.map((id) => {
        const hold = known.find((h) => h.id === id);
        return hold ? `${holdTarget(hold)}: “${hold.reason}”` : 'A hold set meanwhile';
      });
      return {
        error:
          ids.length === 1
            ? 'A legal hold covers some of it. Lift the hold first:'
            : 'Legal holds cover some of it. Lift them first:',
        holds: named,
      };
    }
    return {
      error: problemMessage(error, {
        ERASURE_NOT_ALLOWED:
          'One of their conversations is still open: a member still here has only their ended conversations erased. End it first.',
        'status:403': 'Only the Owner erases content.',
        'status:404': 'Nothing of this company has this id.',
      }),
    };
  }
}
