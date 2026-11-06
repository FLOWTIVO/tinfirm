import { NgTemplateOutlet } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  linkedSignal,
  resource,
  signal,
  untracked,
} from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { NgIcon, provideIcons } from '@ng-icons/core';
import {
  lucideArrowLeft,
  lucideArrowRight,
  lucideChevronDown,
  lucideChevronLeft,
  lucideChevronRight,
  lucideCircleAlert,
  lucideCircleCheck,
  lucideCircleArrowUp,
  lucideCircleDashed,
  lucideFolder,
  lucideGitBranch,
  lucideGlobe,
  lucideLibraryBig,
  lucideLock,
  lucidePlug,
  lucidePlus,
  lucideSearch,
  lucideShieldPlus,
  lucideTrash2,
  lucideX,
} from '@ng-icons/lucide';
import { HlmAlertImports } from '@spartan-ng/helm/alert';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmInput } from '@spartan-ng/helm/input';
import { HlmLabel } from '@spartan-ng/helm/label';
import { HlmNativeSelectImports } from '@spartan-ng/helm/native-select';
import { HlmSpinner } from '@spartan-ng/helm/spinner';
import { HlmSwitch } from '@spartan-ng/helm/switch';
import { HlmTextarea } from '@spartan-ng/helm/textarea';
import { Api } from '../../../api/generated/api';
import {
  createAgent,
  getAgent,
  getMyModelAccess,
  listGatewayModels,
  listGroups,
  listMembers,
  listSkills,
  listSystemRepositories,
  listSystems,
  listSystemTools,
  replaceAgentHosts,
  replaceAgentRepositories,
  replaceAgentShares,
  replaceAgentSkills,
  replaceAgentSystems,
  updateAgent,
} from '../../../api/generated/functions';
import type {
  Agent,
  AgentRepositoryAccess,
  AgentRuntime,
  AgentToolMode,
  ExternalSystem,
  SkillSummary,
  SystemKind,
  SystemRepository,
  SystemTool,
} from '../../../api/generated/models';
import { problemDetail, problemMessage, toProblem } from '../../../api/problem';
import { loadAll } from '../../../core/company-data';
import { CurrentCompany } from '../../../core/current-company';
import { mediaQuery, WIDE_SCREEN } from '../../../core/media-query';
import { Notify } from '../../../core/notify';
import { Session } from '../../../core/session';
import { canEditAgent } from '../../../core/permissions';
import { ErrorState } from '../../../shared/error-state';
import { matchesPerson, sharePeople } from '../../../shared/share-people';
import { initials } from '../../../shared/forms';
import {
  ACCESS_LABELS,
  RUNTIME_LABELS,
  SYSTEM_KIND_LABELS,
  TOOL_EFFECT_LABELS,
} from '../../../shared/labels';
import { ListSkeleton } from '../../../shared/list-skeleton';
import { MarkdownEditor } from '../../../shared/markdown-editor';
import { Segmented, type SegmentedOption } from '../../../shared/segmented';
import { NotFound } from '../../not-found';
import {
  AGENT_SYSTEM_KINDS,
  type AgentDraft,
  type AgentSection,
  changedSections,
  changeOf,
  CODE_HOST_KINDS,
  createRequest,
  type DraftSkill,
  type DraftSystem,
  type DraftTool,
  draftOf,
  EMPTY_DRAFT,
  asksSinceItWrites,
  effectiveMode,
  effectOfPick,
  hostProblem,
  normalizeHost,
  skillInputs,
  TURN_TIME_LIMIT_MAX,
  TURN_TIME_LIMIT_MIN,
  turnTimeLimitProblem,
  toolsForNewSystem,
  wideningOf,
} from './agent-draft';
import { AGENT_SKILLS_MAX } from '../skills/skill-labels';
import { namesText, runtimesText } from './agent-labels';
import { suggestedRuntime, type SuggestedRuntime } from './default-runtime';

/** The parts of the editor a problem can name. */
export type EditorField =
  | 'name'
  | 'handle'
  | 'description'
  | 'runtimes'
  | 'turnTimeLimitMinutes'
  | 'instructions'
  | 'skills'
  | 'systems'
  | 'repositories'
  | 'hosts'
  | 'shares';

type EditorErrors = Partial<Record<EditorField, string>>;

const RUNTIMES: readonly AgentRuntime[] = ['CLAUDE_CODE', 'CODEX', 'PI', 'OPENCODE'];

const MODE_CHOICES: readonly SegmentedOption[] = [
  { value: 'ALLOW', label: 'Allowed' },
  { value: 'ASK', label: 'Needs approval' },
];

const ACCESS_CHOICES: readonly SegmentedOption[] = [
  { value: 'READ', label: 'Read-only' },
  { value: 'WRITE', label: 'Read and write' },
];

/** The kinds of system the picker of "What it may use" filters by, in its order. */
const SYSTEM_GROUPS: readonly { id: string; label: string; kinds: readonly SystemKind[] }[] = [
  { id: 'issues', label: 'Issue trackers', kinds: ['JIRA_CLOUD', 'JIRA_DC'] },
  { id: 'code', label: 'Code hosts', kinds: ['GITHUB', 'GITLAB', 'GIT'] },
  { id: 'mcp', label: 'MCP servers', kinds: ['MCP'] },
  { id: 'chat', label: 'Chat', kinds: ['SLACK'] },
];

/** A section of the page: the page shows one at a time. */
export type EditorSection = 'who' | 'instructions' | 'skills' | 'reach' | 'sharing';

/** The sections of the page, in order: the tabs, and the steps of Back and Next. */
const SECTIONS: readonly { id: EditorSection; label: string; lede: string }[] = [
  { id: 'who', label: 'Who it is', lede: 'How people find it, mention it and run it.' },
  {
    id: 'instructions',
    label: 'Instructions',
    lede: 'The first part of every prompt. Where the agent can act (a write tool Allowed, a read and write repository or a network host), a change waits until each person who uses it accepts it; otherwise everyone gets it at their next turn, told so.',
  },
  {
    id: 'skills',
    label: 'Skills',
    lede: 'Know-how from the company’s library, each pinned to a version: it reads one when a task fits. A new version reaches the agent only when you move its pin. Like the instructions, a skill added or moved waits for acceptance where the agent can act; a skill removed goes at once.',
  },
  {
    id: 'reach',
    label: 'What it may use',
    lede: 'The company’s systems it may call, and the network hosts it may reach besides. With neither, it works only in its workspace.',
  },
  {
    id: 'sharing',
    label: 'Whom you share it with',
    lede: 'The members of these groups and these people may use it, each with their own accounts and budget. Nobody else may until you share it.',
  },
];

/** How a section stands, as its tab shows it. */
type SectionState = 'done' | 'todo' | 'private' | 'error';

/** The section each field is in, to open it on a problem. */
const FIELD_SECTIONS: Record<EditorField, EditorSection> = {
  name: 'who',
  handle: 'who',
  description: 'who',
  runtimes: 'who',
  turnTimeLimitMinutes: 'who',
  instructions: 'instructions',
  skills: 'skills',
  systems: 'reach',
  repositories: 'reach',
  hosts: 'reach',
  shares: 'sharing',
};

/** The fields in the page's order, the first with a problem taking the focus. */
const FIELD_ORDER: readonly EditorField[] = [
  'name',
  'handle',
  'description',
  'runtimes',
  'turnTimeLimitMinutes',
  'instructions',
  'skills',
  'systems',
  'repositories',
  'hosts',
  'shares',
];

/** The input a field's problem focuses; the others focus their section's heading. */
const FIELD_INPUTS: Partial<Record<EditorField, string>> = {
  name: 'agent-name',
  handle: 'agent-handle',
  description: 'agent-description',
  turnTimeLimitMinutes: 'agent-turn-time-limit',
};

/** "1 tool", "3 tools". */
function count(n: number, one: string, many: string): string {
  return `${n} ${n === 1 ? one : many}`;
}

/** The id one arrow key, Home or End moves to in a list of tabs, or null for another key. */
function tabKeyTarget(key: string, ids: readonly string[], current: string): string | null {
  const i = Math.max(0, ids.indexOf(current));
  switch (key) {
    case 'ArrowDown':
    case 'ArrowRight':
      return ids[(i + 1) % ids.length];
    case 'ArrowUp':
    case 'ArrowLeft':
      return ids[(i - 1 + ids.length) % ids.length];
    case 'Home':
      return ids[0];
    case 'End':
      return ids[ids.length - 1];
    default:
      return null;
  }
}

const SECTION_LABELS: Record<AgentSection, string> = {
  about: 'Who it is and instructions',
  skills: 'Skills',
  systems: 'Systems and tools',
  repositories: 'Repositories',
  hosts: 'Network hosts',
  shares: 'Sharing',
};

/** The field a problem names (plan §4.3), else the section whose call failed. */
const CODE_FIELDS: Record<string, EditorField> = {
  DUPLICATE_AGENT_NAME: 'name',
  DUPLICATE_AGENT_HANDLE: 'handle',
  UNKNOWN_TOOL: 'systems',
  PERSONAL_SYSTEM: 'systems',
  SYSTEM_KIND_INVALID: 'systems',
  SYSTEM_NOT_ON_AGENT: 'repositories',
  NETWORK_HOST_LIMIT: 'hosts',
  SKILL_DELETED: 'skills',
  SKILL_NAME_CONFLICT: 'skills',
};

const SECTION_FIELDS: Record<AgentSection, EditorField | null> = {
  about: null,
  skills: 'skills',
  systems: 'systems',
  repositories: 'repositories',
  hosts: 'hosts',
  shares: 'shares',
};

/** The system, tool or repository a refused save names (`details` of the problem). */
export interface EditorErrorAt {
  readonly field: 'systems' | 'repositories';
  readonly systemId?: string;
  readonly tool?: string;
  readonly repositoryId?: string;
}

/**
 * A problem that names what it refused (`UNKNOWN_TOOL` with `details.systemId` and `details.tool`,
 * `SYSTEM_NOT_ON_AGENT` with `details.systemId` or `details.repositoryId`): where on the page it
 * points, and its message in the draft's names rather than the server's ids.
 */
function pointedFailure(
  error: unknown,
  draft: AgentDraft | undefined,
): { at: EditorErrorAt; message: string } | null {
  const code = toProblem(error).code;
  const systemId = problemDetail(error, 'systemId');
  const system = draft?.systems.find((s) => s.systemId === systemId);
  if (code === 'UNKNOWN_TOOL' && systemId) {
    const tool = problemDetail(error, 'tool');
    const title = system?.tools.find((t) => t.name === tool)?.title || tool;
    return {
      at: { field: 'systems', systemId, tool },
      message: `${system?.name ?? 'Its system'} no longer offers ${title ? `“${title}”` : 'this tool'}, or it is switched off: remove it, then save again.`,
    };
  }
  if (code === 'SYSTEM_NOT_ON_AGENT') {
    const repositoryId = problemDetail(error, 'repositoryId');
    if (repositoryId) {
      const name = draft?.repositories.find((r) => r.repositoryId === repositoryId)?.name;
      return {
        at: { field: 'repositories', repositoryId },
        message: `${name ?? 'This repository'} is not on a code host the agent lists: add its system under What it may use first.`,
      };
    }
    if (systemId) {
      return {
        at: { field: 'systems', systemId },
        message: `${system?.name ?? 'This system'} is not on the agent: add it under What it may use first.`,
      };
    }
  }
  return null;
}

/**
 * Field errors of a failed save: what the problem names (a system, tool or repository, in the
 * draft's names), by code, by the problem's own field names, else the section's.
 */
export function editorFailure(
  error: unknown,
  section: AgentSection | 'create',
  draft?: AgentDraft,
): { errors: EditorErrors; message: string | null; at: EditorErrorAt | null } {
  const pointed = pointedFailure(error, draft);
  if (pointed) {
    return { errors: { [pointed.at.field]: pointed.message }, message: null, at: pointed.at };
  }
  const problem = toProblem(error);
  const message = problemMessage(error);
  const withDetail =
    problem.detail && problem.status === 400 && !message.includes(problem.detail)
      ? `${message} ${problem.detail.trim().replace(/([^.!?])$/, '$1.')}`
      : message;
  const errors: EditorErrors = {};
  for (const fe of problem.fieldErrors) {
    const field = fe.field.split(/[.[]/)[0] as EditorField;
    if (
      [
        'name',
        'handle',
        'description',
        'runtimes',
        'turnTimeLimitMinutes',
        'instructions',
      ].includes(field)
    ) {
      errors[field] = fe.message;
    } else if (
      field === 'systems' ||
      field === 'repositories' ||
      field === 'hosts' ||
      field === 'skills'
    ) {
      errors[field] = fe.message;
    } else if (['shareGroupIds', 'groupIds', 'sharePrincipalIds', 'principalIds'].includes(field)) {
      errors.shares = fe.message;
    }
  }
  const byCode = problem.code ? CODE_FIELDS[problem.code] : undefined;
  if (byCode) {
    errors[byCode] = withDetail;
    return { errors, message: null, at: null };
  }
  if (Object.keys(errors).length > 0) return { errors, message: null, at: null };
  const bySection = section === 'create' ? null : SECTION_FIELDS[section];
  if (bySection && problem.status === 400) {
    return { errors: { [bySection]: withDetail }, message: null, at: null };
  }
  return { errors, message: withDetail, at: null };
}

/** Loads per system, kept for the visit: the tools and repositories each system serves. */
interface Loaded<T> {
  readonly state: 'loading' | 'ok' | 'error';
  readonly items: readonly T[];
}

/**
 * The agent editor (plan §4.3), `agents/new` and `agents/:agentId/edit`: one section at a time
 * (not a wizard: any tab opens any section). The tabs on the left say what each section holds;
 * Back and Next step through them. Who it is, its instructions, what it may use (the systems with
 * each tool's effect and mode, a code host's repositories, the network hosts its runs may reach),
 * and the groups and people it is shared with. Every section stays in the page, hidden when
 * another is open, so nothing typed is lost. On a phone the tabs are the first screen, and a
 * section opens on its own. One Save: a new agent in one `createAgent` (version 1); a change sends
 * only the sections it changed, in that order. Before the Save a notice names what widens the
 * agent: people who use it keep their version until they accept it.
 */
@Component({
  selector: 'app-agent-editor',
  imports: [
    NgTemplateOutlet,
    RouterLink,
    NgIcon,
    HlmAlertImports,
    HlmButton,
    HlmInput,
    HlmLabel,
    HlmNativeSelectImports,
    HlmSpinner,
    HlmSwitch,
    HlmTextarea,
    ErrorState,
    ListSkeleton,
    MarkdownEditor,
    NotFound,
    Segmented,
  ],
  providers: [
    provideIcons({
      lucideArrowLeft,
      lucideArrowRight,
      lucideChevronDown,
      lucideChevronLeft,
      lucideChevronRight,
      lucideCircleAlert,
      lucideCircleCheck,
      lucideCircleArrowUp,
      lucideCircleDashed,
      lucideFolder,
      lucideGitBranch,
      lucideGlobe,
      lucideLibraryBig,
      lucideLock,
      lucidePlug,
      lucidePlus,
      lucideSearch,
      lucideShieldPlus,
      lucideTrash2,
      lucideX,
    }),
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @let companyId = current.id();
    @if (notFound()) {
      <app-not-found />
    } @else {
      <div class="flex flex-col gap-5">
        <a
          hlmBtn
          variant="ghost"
          size="sm"
          class="-ms-3 self-start"
          [routerLink]="
            saved() ? ['/c', companyId, 'agents', saved()!.id] : ['/c', companyId, 'agents']
          "
        >
          <ng-icon name="lucideArrowLeft" aria-hidden="true" />
          {{ saved()?.name ?? 'Agents' }}
        </a>

        @if (editing() && agentRes.isLoading() && !saved()) {
          <ng-container [ngTemplateOutlet]="heading" />
          <app-list-skeleton [rows]="4" />
        } @else if (editing() && agentRes.error() && !saved()) {
          <ng-container [ngTemplateOutlet]="heading" />
          <app-error-state
            [error]="agentRes.error()"
            heading="Couldn't load this agent"
            (retry)="agentRes.reload()"
          />
        } @else if (editing() && saved() && !mayEdit()) {
          <ng-container [ngTemplateOutlet]="heading" />
          <div hlmAlert data-testid="not-editable">
            <ng-icon name="lucideCircleAlert" aria-hidden="true" />
            <h2 hlmAlertTitle>You cannot edit this agent</h2>
            <p hlmAlertDescription>Only its owner and the company’s admins can change it.</p>
          </div>
        } @else {
          @let d = draft();
          <form
            class="flex min-w-0 flex-col gap-5"
            novalidate
            (submit)="save($event)"
            data-testid="agent-editor"
          >
            <div
              class="flex flex-col gap-4 sm:flex-row sm:flex-wrap sm:items-end sm:justify-between sm:gap-x-8"
            >
              <ng-container [ngTemplateOutlet]="heading" />
              <div class="flex min-w-0 flex-col gap-2 sm:items-end" data-testid="save-bar">
                <div class="grid grid-cols-2 gap-2 sm:flex">
                  <a
                    hlmBtn
                    variant="outline"
                    [routerLink]="
                      saved()
                        ? ['/c', companyId, 'agents', saved()!.id]
                        : ['/c', companyId, 'agents']
                    "
                    >Cancel</a
                  >
                  <button
                    hlmBtn
                    type="submit"
                    data-testid="save-agent"
                    [disabled]="saving() || (editing() && sections().length === 0)"
                  >
                    @if (saving()) {
                      <hlm-spinner aria-hidden="true" />
                    }
                    {{ editing() ? 'Save' : 'Create agent' }}
                  </button>
                </div>
                <p
                  class="m-0 min-w-0 text-xs text-pretty sm:max-w-sm sm:text-end"
                  aria-live="polite"
                  data-testid="save-status"
                >
                  @if (!editing()) {
                    <span class="text-muted-foreground">{{ newAgentStatus() }}</span>
                  } @else if (sections().length === 0) {
                    <span class="text-muted-foreground">No changes</span>
                  } @else {
                    <span class="text-primary font-semibold">Changed: {{ changedText() }}</span>
                    @if (widening().length > 0) {
                      <span class="text-muted-foreground"> · widens it, see below</span>
                    }
                  }
                </p>
              </div>
            </div>

            @if (formError(); as message) {
              <div hlmAlert variant="destructive" data-testid="editor-error">
                <ng-icon name="lucideCircleAlert" aria-hidden="true" />
                <p hlmAlertDescription>{{ message }}</p>
              </div>
            }

            @if (widening().length > 0) {
              <section
                class="bg-card flex flex-col gap-2 rounded-xl border px-4 py-3"
                role="status"
                aria-labelledby="widening-notice-title"
                data-testid="widening-notice"
              >
                <h2
                  class="flex items-center gap-2 text-sm font-semibold"
                  id="widening-notice-title"
                >
                  <ng-icon
                    name="lucideShieldPlus"
                    class="text-warning shrink-0"
                    aria-hidden="true"
                  />
                  This change widens {{ saved()?.name }}
                </h2>
                <p class="text-sm text-pretty" data-testid="widening-sentence">
                  People who use {{ saved()?.name }} keep version {{ saved()?.version }} until they
                  accept this change; what you remove applies at their next turn.
                </p>
                <ul class="text-muted-foreground flex list-disc flex-col gap-0.5 ps-5 text-sm">
                  @for (item of widening(); track item.key) {
                    <li [attr.data-widening]="item.kind">{{ item.text }}</li>
                  }
                </ul>
              </section>
            }

            <div class="grid gap-5 lg:grid-cols-[17rem_minmax(0,1fr)] lg:items-start">
              <!-- The sections, each with what it holds; on a phone, the page's first screen. -->
              <nav
                class="flex min-w-0 flex-col gap-4 lg:sticky lg:top-18"
                [class.max-lg:hidden]="phoneOnSection()"
                aria-label="Sections"
                data-testid="section-index"
              >
                <div class="flex min-w-0 items-center gap-3 px-1" data-testid="agent-preview">
                  <div
                    class="bg-primary text-primary-foreground flex size-11 shrink-0 items-center justify-center rounded-[14px] text-[0.9375rem] font-bold"
                    aria-hidden="true"
                  >
                    {{ initialsOf(d.name) }}
                  </div>
                  <div class="flex min-w-0 flex-col">
                    <span
                      class="truncate font-bold"
                      [class.text-muted-foreground]="!d.name.trim()"
                      >{{ d.name.trim() || 'Its name' }}</span
                    >
                    @if (previewHandle(); as handle) {
                      <span class="text-muted-foreground truncate font-mono text-xs"
                        >&#64;{{ handle }}</span
                      >
                    }
                  </div>
                </div>
                <div
                  class="flex flex-col gap-1"
                  role="tablist"
                  aria-orientation="vertical"
                  aria-label="Sections of the agent"
                >
                  @for (row of sectionRows(); track row.id) {
                    @let open = row.id === section();
                    <button
                      type="button"
                      role="tab"
                      class="focus-visible:ring-ring/50 max-lg:bg-card max-lg:border-border flex w-full min-w-0 items-start gap-2.5 rounded-xl border px-3 py-2.5 text-start outline-none focus-visible:ring-3"
                      [class]="
                        open
                          ? 'bg-card border-border shadow-[0_1px_3px_rgb(0_0_0/0.08)]'
                          : 'hover:bg-accent border-transparent'
                      "
                      [id]="'section-tab-' + row.id"
                      [attr.aria-selected]="open"
                      [attr.aria-controls]="'section-panel-' + row.id"
                      [tabIndex]="open ? 0 : -1"
                      [attr.data-section]="row.id"
                      [attr.data-state]="row.state"
                      (click)="openSection(row.id)"
                      (keydown)="onSectionKeys($event)"
                    >
                      @switch (row.state) {
                        @case ('done') {
                          <ng-icon
                            name="lucideCircleCheck"
                            size="16"
                            class="text-primary mt-0.5 shrink-0"
                            aria-hidden="true"
                          />
                        }
                        @case ('error') {
                          <ng-icon
                            name="lucideCircleAlert"
                            size="16"
                            class="text-destructive mt-0.5 shrink-0"
                            aria-hidden="true"
                          />
                        }
                        @case ('private') {
                          <ng-icon
                            name="lucideLock"
                            size="16"
                            class="text-muted-foreground mt-0.5 shrink-0"
                            aria-hidden="true"
                          />
                        }
                        @default {
                          <ng-icon
                            name="lucideCircleDashed"
                            size="16"
                            class="text-muted-foreground mt-0.5 shrink-0"
                            aria-hidden="true"
                          />
                        }
                      }
                      <span class="flex min-w-0 flex-1 flex-col">
                        <span
                          class="text-sm font-semibold"
                          [id]="'section-tab-' + row.id + '-label'"
                          >{{ row.label }}</span
                        >
                        <span class="flex flex-col" [id]="'section-tab-' + row.id + '-details'">
                          @if (row.state === 'error') {
                            <span class="sr-only">Has a problem:</span>&ngsp;
                          }
                          <span
                            class="text-xs text-pretty"
                            [class.text-destructive]="row.state === 'error'"
                            [class.text-muted-foreground]="row.state !== 'error'"
                            data-summary
                            >{{ row.summary }}</span
                          >
                          @if (row.more) {
                            &ngsp;<span class="text-muted-foreground text-xs" data-summary>{{
                              row.more
                            }}</span>
                          }
                        </span>
                      </span>
                      <!-- An icon's own display wins over lg:hidden: its wrapper hides it. -->
                      <span class="text-muted-foreground mt-0.5 flex shrink-0 lg:hidden">
                        <ng-icon name="lucideChevronRight" size="16" aria-hidden="true" />
                      </span>
                    </button>
                  }
                </div>
                @if (!editing()) {
                  <p class="text-muted-foreground m-0 px-1 text-xs text-pretty">
                    Only a name and a runtime are required. The rest can wait for a later version.
                  </p>
                }
              </nav>

              <!-- One section at a time; on a phone, its own screen. -->
              <div
                class="bg-card flex min-w-0 flex-col overflow-hidden rounded-xl border lg:min-h-[38rem]"
                [class.max-lg:hidden]="!phoneOnSection()"
              >
                <div class="border-b px-2 py-1.5 lg:hidden">
                  <button
                    hlmBtn
                    variant="ghost"
                    size="sm"
                    type="button"
                    (click)="showSectionList()"
                  >
                    <ng-icon name="lucideChevronLeft" aria-hidden="true" />
                    All sections
                  </button>
                </div>
                @for (s of sectionList; track s.id; let i = $index) {
                  <section
                    class="flex min-w-0 flex-col"
                    role="tabpanel"
                    [id]="'section-panel-' + s.id"
                    [attr.aria-labelledby]="'section-tab-' + s.id + '-label'"
                    [attr.data-testid]="'editor-' + s.id"
                    [hidden]="section() !== s.id"
                  >
                    <div class="flex items-start justify-between gap-4 border-b px-4 py-4 sm:px-6">
                      <div class="flex min-w-0 flex-col gap-0.5">
                        <h2
                          class="text-base font-bold outline-none"
                          [id]="s.id + '-title'"
                          tabindex="-1"
                        >
                          {{ s.label }}
                        </h2>
                        <p class="text-muted-foreground text-xs text-pretty sm:text-[0.8125rem]">
                          {{ s.lede }}
                        </p>
                      </div>
                      <span
                        class="text-muted-foreground mt-1 shrink-0 font-mono text-xs tabular-nums"
                        >{{ i + 1 }} of {{ sectionList.length }}</span
                      >
                    </div>
                    <div class="flex min-w-0 flex-col gap-5 px-4 py-4 sm:px-6 sm:py-5">
                      @switch (s.id) {
                        @case ('who') {
                          <div class="grid gap-4 sm:grid-cols-2">
                            <div class="flex min-w-0 flex-col gap-1.5">
                              <label hlmLabel for="agent-name">Name</label>
                              <input
                                hlmInput
                                id="agent-name"
                                autocomplete="off"
                                placeholder="Business analyst"
                                maxlength="100"
                                [value]="d.name"
                                [forceInvalid]="!!errors().name"
                                [attr.aria-invalid]="!!errors().name || null"
                                [attr.aria-describedby]="errors().name ? 'agent-name-error' : null"
                                (input)="patch({ name: $any($event.target).value }, 'name')"
                              />
                              @if (errors().name; as message) {
                                <p
                                  class="text-destructive text-sm"
                                  id="agent-name-error"
                                  role="alert"
                                  data-error="name"
                                >
                                  {{ message }}
                                </p>
                              }
                            </div>
                            <div class="flex min-w-0 flex-col gap-1.5">
                              <label hlmLabel for="agent-handle">Handle</label>
                              <div class="relative">
                                <span
                                  class="text-muted-foreground pointer-events-none absolute start-3 top-1/2 -translate-y-1/2 font-mono text-sm"
                                  aria-hidden="true"
                                  >&#64;</span
                                >
                                <input
                                  hlmInput
                                  id="agent-handle"
                                  class="ps-7 font-mono"
                                  autocomplete="off"
                                  maxlength="50"
                                  [placeholder]="handlePlaceholder()"
                                  [value]="d.handle"
                                  [forceInvalid]="!!errors().handle"
                                  [attr.aria-invalid]="!!errors().handle || null"
                                  [attr.aria-describedby]="
                                    errors().handle ? 'agent-handle-error' : 'agent-handle-hint'
                                  "
                                  (input)="patch({ handle: $any($event.target).value }, 'handle')"
                                />
                              </div>
                              @if (errors().handle; as message) {
                                <p
                                  class="text-destructive text-sm"
                                  id="agent-handle-error"
                                  role="alert"
                                  data-error="handle"
                                >
                                  {{ message }}
                                </p>
                              } @else {
                                <p class="text-muted-foreground text-xs" id="agent-handle-hint">
                                  How people mention it. Empty: made from the name.
                                </p>
                              }
                            </div>
                          </div>
                          <div class="flex min-w-0 flex-col gap-1.5">
                            <label hlmLabel for="agent-description"
                              >Description
                              <span class="text-muted-foreground font-normal"
                                >(optional)</span
                              ></label
                            >
                            <textarea
                              hlmTextarea
                              id="agent-description"
                              rows="2"
                              maxlength="1000"
                              class="min-h-16 [field-sizing:content]"
                              placeholder="Prepares tickets: reads the code and writes the brief."
                              [value]="d.description"
                              [attr.aria-invalid]="!!errors().description || null"
                              (input)="
                                patch({ description: $any($event.target).value }, 'description')
                              "
                            ></textarea>
                            @if (errors().description; as message) {
                              <p
                                class="text-destructive text-sm"
                                role="alert"
                                data-error="description"
                              >
                                {{ message }}
                              </p>
                            }
                          </div>
                          <fieldset
                            class="m-0 flex min-w-0 flex-col gap-2 border-0 p-0"
                            data-testid="runtimes"
                          >
                            <legend class="mb-1 p-0 text-sm font-medium">Runtimes</legend>
                            <p class="text-muted-foreground -mt-1 text-xs">
                              The runtimes its instructions work on. One is the default; each person
                              may pick another in their settings.
                            </p>
                            @if (shownSuggestion(); as s) {
                              <p class="text-muted-foreground text-xs" data-testid="runtime-reason">
                                {{ runtimeLabels[s.runtime] }} is the default: {{ s.reason }}
                              </p>
                            }
                            <ul class="grid grid-cols-2 gap-2 sm:grid-cols-4">
                              @for (r of runtimeRows(); track r.runtime) {
                                <li
                                  class="flex min-h-16 min-w-0 flex-col gap-1.5 rounded-xl border px-3 py-2.5"
                                  [class]="r.on ? 'border-primary/50 bg-primary/5' : ''"
                                  [attr.data-runtime]="r.runtime"
                                >
                                  <label
                                    class="flex min-w-0 cursor-pointer items-center gap-2 text-sm font-semibold"
                                  >
                                    <input
                                      type="checkbox"
                                      class="accent-primary size-4 shrink-0"
                                      [checked]="r.on"
                                      (change)="
                                        toggleRuntime(r.runtime, $any($event.target).checked)
                                      "
                                    />
                                    <span class="truncate">{{ runtimeLabels[r.runtime] }}</span>
                                  </label>
                                  @if (r.default) {
                                    <span
                                      class="bg-primary/10 text-primary inline-flex h-5 items-center self-start rounded-full px-2 text-xs font-semibold"
                                      >Default</span
                                    >
                                  } @else if (r.on) {
                                    <button
                                      hlmBtn
                                      variant="ghost"
                                      size="xs"
                                      type="button"
                                      class="-ms-2 self-start"
                                      (click)="makeDefault(r.runtime)"
                                    >
                                      Make default
                                    </button>
                                  } @else {
                                    <span class="text-muted-foreground text-xs">Not used</span>
                                  }
                                </li>
                              }
                            </ul>
                            @if (errors().runtimes; as message) {
                              <p
                                class="text-destructive text-sm"
                                role="alert"
                                data-error="runtimes"
                              >
                                {{ message }}
                              </p>
                            }
                          </fieldset>
                          <div
                            class="flex min-w-0 flex-col gap-1.5"
                            data-field="turnTimeLimitMinutes"
                          >
                            <label hlmLabel for="agent-turn-time-limit">Time limit of a turn</label>
                            <p
                              class="text-muted-foreground -mt-1 text-xs"
                              id="agent-turn-time-limit-hint"
                            >
                              Minutes one turn may run before the runner host stops it ({{
                                timeLimitMin
                              }}
                              to {{ timeLimitMax }}). A stopped turn keeps its work and can
                              continue. Empty: the default{{
                                defaultTimeLimit() ? ' (' + defaultTimeLimit() + ' min)' : ''
                              }}.
                            </p>
                            <div class="relative w-48">
                              <input
                                hlmInput
                                id="agent-turn-time-limit"
                                class="w-full pe-11 font-mono"
                                type="number"
                                inputmode="numeric"
                                autocomplete="off"
                                [min]="timeLimitMin"
                                [max]="timeLimitMax"
                                step="1"
                                [placeholder]="
                                  defaultTimeLimit() ? 'Default: ' + defaultTimeLimit() : 'Default'
                                "
                                [value]="d.turnTimeLimitMinutes"
                                [forceInvalid]="!!errors().turnTimeLimitMinutes"
                                [attr.aria-invalid]="!!errors().turnTimeLimitMinutes || null"
                                [attr.aria-describedby]="
                                  errors().turnTimeLimitMinutes
                                    ? 'agent-turn-time-limit-hint agent-turn-time-limit-error'
                                    : 'agent-turn-time-limit-hint'
                                "
                                (input)="
                                  patch(
                                    { turnTimeLimitMinutes: $any($event.target).value },
                                    'turnTimeLimitMinutes'
                                  )
                                "
                              />
                              <span
                                class="text-muted-foreground pointer-events-none absolute end-3 top-1/2 -translate-y-1/2 text-xs"
                                aria-hidden="true"
                                >min</span
                              >
                            </div>
                            @if (errors().turnTimeLimitMinutes; as message) {
                              <p
                                class="text-destructive text-sm"
                                id="agent-turn-time-limit-error"
                                role="alert"
                                data-error="turnTimeLimitMinutes"
                              >
                                {{ message }}
                              </p>
                            }
                          </div>
                          <div class="flex min-w-0 flex-col gap-1.5" data-field="memoryAllowed">
                            <span class="text-sm font-medium">Memory</span>
                            <label class="flex items-start gap-3" for="agent-memory-allowed">
                              <hlm-switch
                                inputId="agent-memory-allowed"
                                class="mt-0.5"
                                data-testid="memory-allowed"
                                [checked]="d.memoryAllowed"
                                (checkedChange)="patch({ memoryAllowed: $event })"
                              />
                              <span class="flex min-w-0 flex-col gap-0.5">
                                <span class="text-sm"
                                  >People and triggers may turn its memory on</span
                                >
                                <span class="text-muted-foreground text-xs text-pretty">
                                  Each person who turns it on gets notes of their own, which only
                                  their conversations read; a trigger’s notes wait for its team’s
                                  review. Forbidding it keeps the notes, unread and unwritten.
                                </span>
                              </span>
                            </label>
                          </div>
                        }

                        @case ('instructions') {
                          <app-markdown-editor
                            inputId="agent-instructions"
                            labelId="instructions-title"
                            placeholder="Read the issue and the code, then write a brief."
                            [minRows]="14"
                            [value]="d.instructions"
                            (valueChange)="patch({ instructions: $event }, 'instructions')"
                          />
                          @if (errors().instructions; as message) {
                            <p
                              class="text-destructive text-sm"
                              role="alert"
                              data-error="instructions"
                            >
                              {{ message }}
                            </p>
                          }
                        }

                        @case ('skills') {
                          <div class="flex min-w-0 flex-col gap-2" data-testid="editor-skills">
                            <h3
                              class="flex items-center justify-between gap-2 text-sm font-semibold"
                            >
                              <span id="editor-skills-title">Its skills</span>
                              <span
                                class="text-muted-foreground font-mono text-xs font-normal tabular-nums"
                                >{{ d.skills.length }} of {{ skillsMax }}</span
                              >
                            </h3>
                            @if (d.skills.length > 0) {
                              <ul
                                class="flex flex-col divide-y rounded-lg border"
                                aria-labelledby="editor-skills-title"
                              >
                                @for (k of d.skills; track k.skillId) {
                                  @let was = savedSkill(k.skillId);
                                  <li
                                    class="flex min-w-0 flex-col gap-2 px-3 py-2.5"
                                    [attr.data-skill]="k.skillId"
                                  >
                                    <div class="flex min-w-0 items-start justify-between gap-2">
                                      <div class="flex min-w-0 flex-col gap-0.5">
                                        <div class="flex flex-wrap items-center gap-x-2 gap-y-1">
                                          <span
                                            class="font-mono text-sm font-semibold break-all"
                                            [id]="'skill-' + k.skillId"
                                            >{{ k.name }}</span
                                          >
                                          @if (!was) {
                                            <span
                                              class="bg-primary/10 text-primary inline-flex h-5 items-center rounded-full px-2 text-xs font-semibold"
                                              data-testid="skill-new"
                                              >New</span
                                            >
                                          } @else if (was.version !== k.version) {
                                            <span
                                              class="bg-secondary text-muted-foreground inline-flex h-5 items-center rounded-full px-2 text-xs font-semibold"
                                              data-testid="skill-moved"
                                              >Was v{{ was.version }}</span
                                            >
                                          }
                                          @if (k.deleted) {
                                            <span
                                              class="bg-muted text-muted-foreground inline-flex h-5 items-center rounded-full border px-2 text-xs font-semibold"
                                              data-testid="skill-deleted"
                                              >Deleted from the library</span
                                            >
                                          }
                                        </div>
                                        <span class="text-muted-foreground text-xs text-pretty">{{
                                          k.description
                                        }}</span>
                                      </div>
                                      <button
                                        hlmBtn
                                        variant="ghost"
                                        size="sm"
                                        type="button"
                                        class="text-muted-foreground shrink-0"
                                        data-testid="remove-skill"
                                        [attr.aria-label]="'Remove ' + k.name"
                                        (click)="removeSkill(k.skillId)"
                                      >
                                        <ng-icon name="lucideTrash2" aria-hidden="true" />
                                        Remove
                                      </button>
                                    </div>
                                    @if (k.deleted) {
                                      <p class="text-muted-foreground text-xs text-pretty">
                                        It keeps version {{ k.version }}, and its runs still get it;
                                        a deleted skill’s pin cannot move. Once removed, it cannot
                                        be added again.
                                      </p>
                                    } @else {
                                      <div class="flex min-w-0 flex-wrap items-center gap-2">
                                        <label
                                          class="text-muted-foreground text-xs"
                                          [for]="'skill-version-' + k.skillId"
                                          >Pinned at</label
                                        >
                                        <hlm-native-select
                                          class="w-40"
                                          size="sm"
                                          [selectId]="'skill-version-' + k.skillId"
                                          [value]="'' + k.version"
                                          (valueChange)="setSkillVersion(k.skillId, $event)"
                                        >
                                          @for (v of versionsOf(k); track v) {
                                            <option [value]="'' + v">
                                              Version {{ v
                                              }}{{ v === k.latestVersion ? ' (latest)' : '' }}
                                            </option>
                                          }
                                        </hlm-native-select>
                                        @if (k.version < k.latestVersion) {
                                          <button
                                            hlmBtn
                                            variant="outline"
                                            size="xs"
                                            type="button"
                                            data-testid="update-skill"
                                            (click)="setSkillVersion(k.skillId, k.latestVersion)"
                                          >
                                            <ng-icon
                                              name="lucideCircleArrowUp"
                                              aria-hidden="true"
                                            />
                                            Update to v{{ k.latestVersion }}
                                          </button>
                                        }
                                      </div>
                                    }
                                  </li>
                                }
                              </ul>
                            } @else {
                              <p class="text-muted-foreground text-sm">
                                No skills yet: it works from its instructions alone.
                              </p>
                            }
                          </div>

                          <div
                            class="flex min-w-0 flex-col gap-3 rounded-xl border p-3 sm:p-4"
                            data-testid="skill-library"
                          >
                            <div class="flex flex-col gap-0.5">
                              <h3 class="text-sm font-semibold">The company’s library</h3>
                              <p class="text-muted-foreground text-xs text-pretty">
                                The skills you see: yours, and those shared with you or your groups.
                                One joins at its latest version.
                              </p>
                            </div>
                            <div class="relative">
                              <ng-icon
                                name="lucideSearch"
                                class="text-faint pointer-events-none absolute start-3 top-1/2 -translate-y-1/2"
                                aria-hidden="true"
                              />
                              <input
                                hlmInput
                                class="w-full ps-9"
                                type="search"
                                autocomplete="off"
                                placeholder="Find a skill by name or description"
                                aria-label="Find a skill"
                                data-testid="skill-library-filter"
                                [value]="libraryFilter()"
                                (input)="libraryFilter.set($any($event.target).value)"
                              />
                            </div>
                            @if (libraryRes.isLoading()) {
                              <p class="text-muted-foreground text-xs">Loading the library…</p>
                            } @else if (libraryRes.error()) {
                              <p class="text-destructive text-xs" role="alert">
                                Couldn't load the library.
                              </p>
                            } @else if (addableSkills().length === 0) {
                              <p
                                class="text-muted-foreground text-xs"
                                data-testid="no-addable-skills"
                              >
                                @if (libraryFilter().trim()) {
                                  No skill you can add matches.
                                } @else if (librarySkills().length === 0) {
                                  The library has no skill you see yet.
                                  <a
                                    class="text-primary font-semibold underline-offset-2 hover:underline"
                                    [routerLink]="['/c', companyId, 'skills', 'new']"
                                    >Create one</a
                                  >
                                } @else {
                                  It has every skill you see.
                                }
                              </p>
                            } @else {
                              <ul class="grid gap-2 sm:grid-cols-2">
                                @for (k of addableSkills(); track k.id) {
                                  <li class="min-w-0">
                                    <button
                                      type="button"
                                      class="hover:border-primary/50 hover:bg-primary/5 focus-visible:ring-ring/50 border-input flex min-h-14 w-full min-w-0 items-center gap-2.5 rounded-xl border border-dashed px-3 py-2 text-start outline-none focus-visible:ring-3 disabled:pointer-events-none disabled:opacity-50"
                                      [attr.data-add-skill]="k.id"
                                      [disabled]="d.skills.length >= skillsMax"
                                      (click)="addSkill(k)"
                                    >
                                      <span class="sr-only">Add&ngsp;</span>
                                      <span class="flex min-w-0 flex-1 flex-col">
                                        <span class="truncate font-mono text-sm font-semibold">{{
                                          k.name
                                        }}</span>
                                        <span class="text-muted-foreground line-clamp-2 text-xs">{{
                                          k.description
                                        }}</span>
                                      </span>
                                      <span class="text-muted-foreground shrink-0 font-mono text-xs"
                                        >v{{ k.latestVersion }}</span
                                      >
                                      <ng-icon
                                        name="lucidePlus"
                                        size="16"
                                        class="text-primary shrink-0"
                                        aria-hidden="true"
                                      />
                                    </button>
                                  </li>
                                }
                              </ul>
                            }
                            @if (d.skills.length >= skillsMax) {
                              <p class="text-muted-foreground text-xs">
                                An agent holds at most {{ skillsMax }} skills.
                              </p>
                            }
                          </div>
                          @if (errors().skills; as message) {
                            <p class="text-destructive text-sm" role="alert" data-error="skills">
                              {{ message }}
                            </p>
                          }
                        }

                        @case ('reach') {
                          <div class="flex min-w-0 flex-col gap-7">
                            <section
                              class="flex min-w-0 flex-col gap-3"
                              aria-labelledby="reach-systems-title"
                              data-testid="editor-systems"
                            >
                              <div class="flex min-w-0 items-start gap-3">
                                <span
                                  class="bg-secondary flex size-9 shrink-0 items-center justify-center rounded-[10px]"
                                  aria-hidden="true"
                                >
                                  <ng-icon name="lucidePlug" size="18" />
                                </span>
                                <div class="flex min-w-0 flex-1 flex-col gap-0.5">
                                  <div class="flex items-baseline justify-between gap-3">
                                    <h3 class="text-base font-bold" id="reach-systems-title">
                                      Systems
                                    </h3>
                                    <span
                                      class="text-muted-foreground shrink-0 text-xs font-semibold"
                                      >{{
                                        d.systems.length === 0
                                          ? 'None yet'
                                          : count(d.systems.length, 'system', 'systems')
                                      }}</span
                                    >
                                  </div>
                                  <p class="text-muted-foreground text-[0.8125rem] text-pretty">
                                    Each system brings its tools, and each tool says whether the
                                    agent may call it without asking.
                                  </p>
                                </div>
                              </div>

                              @if (d.systems.length > 0) {
                                <p
                                  class="bg-muted/60 text-muted-foreground rounded-[10px] px-3.5 py-2.5 text-xs text-pretty"
                                  data-testid="mode-legend"
                                >
                                  <span class="text-foreground font-semibold">Allowed</span> calls
                                  the tool without asking.
                                  <span class="text-foreground font-semibold">Needs approval</span>
                                  makes each call wait for an approval: from the person in a
                                  personal conversation, from the trigger’s team in a shared one.
                                </p>
                              }

                              <div class="bg-card min-w-0 overflow-hidden rounded-xl border">
                                <div class="bg-muted/50 flex min-w-0 flex-col gap-2.5 p-3">
                                  <div
                                    class="flex flex-wrap items-baseline justify-between gap-x-3 gap-y-1 px-0.5"
                                  >
                                    <label hlmLabel for="system-search" class="text-[0.8125rem]"
                                      >Add a system</label
                                    >
                                    @if (catalogText(); as catalog) {
                                      <span
                                        class="text-muted-foreground text-xs"
                                        data-testid="system-catalog"
                                        >{{ catalog }}</span
                                      >
                                    }
                                  </div>
                                  <div class="relative">
                                    <ng-icon
                                      name="lucideSearch"
                                      class="text-faint pointer-events-none absolute start-3 top-1/2 -translate-y-1/2"
                                      aria-hidden="true"
                                    />
                                    <input
                                      hlmInput
                                      id="system-search"
                                      class="w-full ps-9"
                                      type="search"
                                      autocomplete="off"
                                      placeholder="Search by name or kind"
                                      [attr.aria-controls]="pickerOpen() ? 'system-picker' : null"
                                      [value]="systemFilter()"
                                      (input)="
                                        systemFilter.set($any($event.target).value);
                                        pickerChoice.set(true)
                                      "
                                      (click)="pickerChoice.set(true)"
                                      (keydown.arrowdown)="pickerChoice.set(true)"
                                      (keydown.escape)="closePicker()"
                                    />
                                  </div>
                                  @if (pickerOpen()) {
                                    <div
                                      class="flex min-w-0 flex-col gap-2.5"
                                      id="system-picker"
                                      data-testid="system-picker"
                                    >
                                      @if (systemsRes.isLoading()) {
                                        <p class="text-muted-foreground px-0.5 text-xs">
                                          Loading the company’s systems…
                                        </p>
                                      } @else if (systemsRes.error()) {
                                        <p class="text-destructive px-0.5 text-xs" role="alert">
                                          Couldn't load the company’s systems.
                                        </p>
                                      } @else if (addable().length === 0) {
                                        <p
                                          class="text-muted-foreground rounded-[10px] border border-dashed px-3.5 py-3 text-[0.8125rem]"
                                        >
                                          {{
                                            companySystems().length === 0
                                              ? 'The company has no systems yet; an admin registers them.'
                                              : 'It uses every system of the company.'
                                          }}
                                        </p>
                                      } @else {
                                        @if (showSystemGroups()) {
                                          <div
                                            class="flex flex-wrap gap-1.5"
                                            role="group"
                                            aria-label="Kind of system"
                                          >
                                            @for (g of systemGroups(); track g.id) {
                                              @let picked = activeSystemGroup() === g.id;
                                              <button
                                                type="button"
                                                class="focus-visible:ring-ring/50 inline-flex h-8 items-center gap-1.5 rounded-full border px-3 text-xs font-semibold outline-none focus-visible:ring-3"
                                                [class]="
                                                  picked
                                                    ? 'bg-secondary text-foreground border-input'
                                                    : 'text-muted-foreground hover:bg-accent hover:text-foreground bg-transparent'
                                                "
                                                [attr.aria-pressed]="picked"
                                                [attr.data-system-group]="g.id"
                                                (click)="systemGroup.set(g.id)"
                                              >
                                                {{ g.label }}
                                                <span class="text-faint font-mono font-normal">{{
                                                  g.count
                                                }}</span>
                                              </button>
                                            }
                                          </div>
                                        }
                                        @if (systemResults().length > 0) {
                                          <ul
                                            class="bg-border m-0 flex max-h-72 list-none flex-col gap-px overflow-y-auto rounded-[10px] border p-0"
                                            aria-label="Systems you can add"
                                          >
                                            @for (s of systemResults(); track s.id) {
                                              <li class="shrink-0">
                                                <button
                                                  type="button"
                                                  class="bg-card hover:bg-accent focus-visible:ring-ring/50 flex min-h-14 w-full min-w-0 items-center gap-3 px-3 py-2 text-start outline-none focus-visible:ring-3 focus-visible:ring-inset disabled:pointer-events-none disabled:opacity-50"
                                                  [attr.data-add-system]="s.id"
                                                  [disabled]="!!adding()"
                                                  (click)="addSystem(s.id)"
                                                >
                                                  <span class="sr-only">Add&ngsp;</span>
                                                  <span
                                                    class="bg-secondary text-muted-foreground flex size-9 shrink-0 items-center justify-center rounded-[10px] text-xs font-bold"
                                                    aria-hidden="true"
                                                    >{{ initialsOf(s.name) }}</span
                                                  >
                                                  <span class="flex min-w-0 flex-1 flex-col">
                                                    <span class="truncate text-sm font-semibold">{{
                                                      s.name
                                                    }}</span>
                                                    <span
                                                      class="text-muted-foreground truncate text-xs"
                                                      >{{ kindLabels[s.kind] }}</span
                                                    >
                                                  </span>
                                                  @if (adding() === s.id) {
                                                    <hlm-spinner
                                                      class="shrink-0"
                                                      aria-hidden="true"
                                                    />
                                                  } @else {
                                                    <span
                                                      class="border-primary/40 text-primary inline-flex h-7 shrink-0 items-center gap-1 rounded-full border px-2.5 text-xs font-semibold"
                                                      aria-hidden="true"
                                                    >
                                                      <ng-icon name="lucidePlus" size="14" />
                                                      Add
                                                    </span>
                                                  }
                                                </button>
                                              </li>
                                            }
                                          </ul>
                                        } @else {
                                          <p
                                            class="text-muted-foreground rounded-[10px] border border-dashed px-3.5 py-3 text-[0.8125rem]"
                                            data-testid="no-system-matches"
                                          >
                                            {{
                                              systemFilter().trim()
                                                ? 'No system left to add matches “' +
                                                  systemFilter().trim() +
                                                  '”.'
                                                : 'No system of this kind is left to add.'
                                            }}
                                          </p>
                                        }
                                      }
                                      <div
                                        class="flex flex-wrap items-center justify-between gap-x-4 gap-y-2 px-0.5"
                                      >
                                        <span class="text-muted-foreground text-xs text-pretty">
                                          Its reads start as Allowed, its writes and unconfirmed
                                          tools as Needs approval.
                                        </span>
                                        <button
                                          hlmBtn
                                          variant="outline"
                                          size="sm"
                                          type="button"
                                          class="shrink-0"
                                          data-testid="close-system-picker"
                                          (click)="closePicker()"
                                        >
                                          Done
                                        </button>
                                      </div>
                                    </div>
                                  }
                                </div>

                                @if (d.systems.length === 0) {
                                  <div
                                    class="text-muted-foreground flex items-center gap-3 border-t p-4 text-sm"
                                    data-testid="no-systems"
                                  >
                                    <ng-icon
                                      name="lucideFolder"
                                      size="18"
                                      class="shrink-0"
                                      aria-hidden="true"
                                    />
                                    <span
                                      >No systems yet: the agent works only in its workspace. Pick
                                      one above to let it call that system’s tools.</span
                                    >
                                  </div>
                                }

                                @for (s of d.systems; track s.systemId) {
                                  @let open = isSystemOpen(s.systemId);
                                  <div class="min-w-0 border-t" [attr.data-system]="s.systemId">
                                    <div class="flex min-w-0 items-center gap-2 px-3 py-2.5">
                                      <button
                                        type="button"
                                        class="hover:bg-accent focus-visible:ring-ring/50 -m-1.5 flex min-w-0 flex-1 items-center gap-3 rounded-[10px] p-1.5 text-start outline-none focus-visible:ring-3"
                                        [id]="'system-toggle-' + s.systemId"
                                        [attr.aria-expanded]="open"
                                        [attr.aria-controls]="'system-body-' + s.systemId"
                                        (click)="toggleSystem(s.systemId)"
                                      >
                                        <span
                                          class="bg-secondary flex size-9 shrink-0 items-center justify-center rounded-[10px] text-xs font-bold"
                                          aria-hidden="true"
                                          >{{ initialsOf(s.name) }}</span
                                        >
                                        <span class="flex min-w-0 flex-1 flex-col">
                                          <span
                                            class="truncate text-sm font-semibold"
                                            [id]="'system-' + s.systemId + '-name'"
                                            >{{ s.name }}</span
                                          >
                                          <span
                                            class="text-muted-foreground truncate text-xs"
                                            [id]="'system-' + s.systemId + '-meta'"
                                            >{{ systemMeta(s) }}</span
                                          >
                                        </span>
                                        <ng-icon
                                          name="lucideChevronDown"
                                          size="18"
                                          class="text-muted-foreground shrink-0 transition-transform"
                                          [class.rotate-180]="open"
                                          aria-hidden="true"
                                        />
                                      </button>
                                      <button
                                        hlmBtn
                                        variant="ghost"
                                        size="sm"
                                        type="button"
                                        class="text-muted-foreground shrink-0"
                                        [attr.aria-label]="'Remove ' + s.name"
                                        (click)="removeSystem(s.systemId)"
                                      >
                                        <ng-icon name="lucideTrash2" aria-hidden="true" />
                                        Remove
                                      </button>
                                    </div>
                                    <div [id]="'system-body-' + s.systemId" [hidden]="!open">
                                      @if (s.tools.length > 0) {
                                        <ul
                                          class="flex flex-col divide-y border-t"
                                          [attr.aria-labelledby]="'system-' + s.systemId + '-name'"
                                        >
                                          @for (t of s.tools; track t.name) {
                                            <li
                                              class="flex min-w-0 flex-col gap-2 py-2.5 ps-3 pe-3 sm:flex-row sm:items-center sm:justify-between sm:ps-16"
                                              [class.bg-destructive/5]="
                                                isErrorTool(s.systemId, t.name)
                                              "
                                              [attr.data-tool]="t.name"
                                              [attr.data-error-tool]="
                                                isErrorTool(s.systemId, t.name) || null
                                              "
                                            >
                                              <div class="flex min-w-0 flex-col">
                                                <span
                                                  class="text-sm"
                                                  [class.text-muted-foreground]="!t.offered"
                                                  >{{ t.title || t.name }}</span
                                                >
                                                <span
                                                  class="text-muted-foreground text-xs"
                                                  data-effect
                                                >
                                                  @if (!t.offered) {
                                                    No longer offered: its system does not serve it
                                                  } @else {
                                                    {{ effectText(t) }}
                                                    @if (asksSinceItWrites(t)) {
                                                      <span data-testid="asks-since-it-writes"
                                                        >· needs approval: it was allowed while it
                                                        read, and now writes. Pick “Allowed” to
                                                        allow it for writes too; one save does it,
                                                        and the notice above names the
                                                        widening.</span
                                                      >
                                                    }
                                                  }
                                                </span>
                                              </div>
                                              @if (t.offered) {
                                                <div class="flex min-w-0 items-center gap-1">
                                                  <app-segmented
                                                    [inputId]="'mode-' + s.systemId + '-' + t.name"
                                                    [label]="(t.title || t.name) + ' on ' + s.name"
                                                    [options]="modeChoices"
                                                    [value]="effectiveOf(t)"
                                                    (valueChange)="
                                                      setMode(s.systemId, t.name, $any($event))
                                                    "
                                                  />
                                                  <button
                                                    hlmBtn
                                                    variant="ghost"
                                                    size="icon-sm"
                                                    type="button"
                                                    class="shrink-0"
                                                    title="Remove"
                                                    [attr.aria-label]="
                                                      'Remove ' + (t.title || t.name)
                                                    "
                                                    (click)="removeTool(s.systemId, t.name)"
                                                  >
                                                    <ng-icon name="lucideX" aria-hidden="true" />
                                                  </button>
                                                </div>
                                              } @else {
                                                <button
                                                  hlmBtn
                                                  variant="outline"
                                                  size="sm"
                                                  type="button"
                                                  (click)="removeTool(s.systemId, t.name)"
                                                >
                                                  Remove
                                                </button>
                                              }
                                            </li>
                                          }
                                        </ul>
                                      } @else {
                                        <p
                                          class="text-muted-foreground border-t py-2.5 ps-3 pe-3 text-xs sm:ps-16"
                                        >
                                          No tools listed{{
                                            isCodeHost(s) ? ': its repositories only' : ''
                                          }}.
                                        </p>
                                      }
                                      @let more = moreTools(s);
                                      @if (more.length > 0) {
                                        <div
                                          class="bg-muted/50 flex flex-col gap-1.5 border-t py-2.5 ps-3 pe-3 sm:ps-16"
                                          data-testid="more-tools"
                                        >
                                          <span class="text-muted-foreground text-xs"
                                            >It also serves:</span
                                          >
                                          <div class="flex flex-wrap gap-1.5">
                                            @for (t of more; track t.name) {
                                              <button
                                                hlmBtn
                                                variant="outline"
                                                size="xs"
                                                type="button"
                                                [attr.data-add-tool]="t.name"
                                                (click)="addTool(s.systemId, t)"
                                              >
                                                <ng-icon name="lucidePlus" aria-hidden="true" />
                                                {{ t.title || t.name }}
                                              </button>
                                            }
                                          </div>
                                        </div>
                                      }
                                      @if (isCodeHost(s)) {
                                        @let repos = repositoriesOf(s.systemId);
                                        <fieldset
                                          class="m-0 flex min-w-0 flex-col gap-1.5 border-0 border-t py-3 ps-3 pe-3 sm:ps-16"
                                          [attr.data-repositories-of]="s.systemId"
                                        >
                                          <legend
                                            class="float-left mb-1 flex w-full items-center gap-2 p-0 text-sm font-semibold"
                                          >
                                            <ng-icon
                                              name="lucideGitBranch"
                                              size="14"
                                              aria-hidden="true"
                                            />
                                            Repositories
                                          </legend>
                                          <p class="text-muted-foreground text-xs">
                                            Its workspace: read-only is cloned and never pushed;
                                            read and write may push a branch and open a change
                                            request.
                                          </p>
                                          @if (
                                            repos.state === 'loading' && repos.items.length === 0
                                          ) {
                                            <p class="text-muted-foreground text-xs">
                                              Loading its repositories…
                                            </p>
                                          } @else if (repos.items.length === 0) {
                                            <p class="text-muted-foreground text-xs">
                                              {{
                                                repos.state === 'error'
                                                  ? 'Couldn’t load its repositories.'
                                                  : 'It lists no repositories yet; an admin adds them on the system’s page.'
                                              }}
                                            </p>
                                          } @else {
                                            <ul class="flex flex-col divide-y rounded-lg border">
                                              @for (r of repos.items; track r.id) {
                                                @let on = repositoryAccess(r.id);
                                                <li
                                                  class="flex min-w-0 flex-col gap-2 px-3 py-2 sm:flex-row sm:items-center sm:justify-between"
                                                  [attr.data-repository]="r.id"
                                                >
                                                  <label class="flex min-w-0 items-center gap-2">
                                                    <input
                                                      type="checkbox"
                                                      class="accent-primary size-4 shrink-0"
                                                      [checked]="!!on"
                                                      (change)="
                                                        toggleRepository(
                                                          r,
                                                          $any($event.target).checked
                                                        )
                                                      "
                                                    />
                                                    <span class="truncate font-mono text-xs">{{
                                                      r.name
                                                    }}</span>
                                                  </label>
                                                  @if (on) {
                                                    <app-segmented
                                                      [inputId]="'access-' + r.id"
                                                      [label]="'Access to ' + r.name"
                                                      [options]="accessChoices"
                                                      [value]="on"
                                                      (valueChange)="setAccess(r.id, $any($event))"
                                                    />
                                                  }
                                                </li>
                                                @if (
                                                  errorAt()?.repositoryId === r.id &&
                                                    errors().repositories;
                                                  as message
                                                ) {
                                                  <li
                                                    class="text-destructive bg-destructive/5 px-3 py-2 text-sm outline-none"
                                                    role="alert"
                                                    tabindex="-1"
                                                    data-error-at
                                                    data-error="repositories"
                                                  >
                                                    {{ message }}
                                                  </li>
                                                }
                                              }
                                            </ul>
                                          }
                                        </fieldset>
                                      }
                                      @if (
                                        errorAt()?.systemId === s.systemId && errors().systems;
                                        as message
                                      ) {
                                        <p
                                          class="text-destructive border-t py-2.5 ps-3 pe-3 text-sm outline-none sm:ps-16"
                                          role="alert"
                                          tabindex="-1"
                                          data-error-at
                                          data-error="systems"
                                        >
                                          {{ message }}
                                        </p>
                                      }
                                    </div>
                                  </div>
                                }
                              </div>
                              @if (!shownAt('systems') && errors().systems; as message) {
                                <p
                                  class="text-destructive text-sm"
                                  role="alert"
                                  data-error="systems"
                                >
                                  {{ message }}
                                </p>
                              }
                              @if (!shownAt('repositories') && errors().repositories; as message) {
                                <p
                                  class="text-destructive text-sm"
                                  role="alert"
                                  data-error="repositories"
                                >
                                  {{ message }}
                                </p>
                              }
                            </section>

                            <section
                              class="flex min-w-0 flex-col gap-3 border-t pt-6"
                              aria-labelledby="reach-hosts-title"
                              data-testid="editor-hosts"
                            >
                              <div class="flex min-w-0 items-start gap-3">
                                <span
                                  class="bg-secondary flex size-9 shrink-0 items-center justify-center rounded-[10px]"
                                  aria-hidden="true"
                                >
                                  <ng-icon name="lucideGlobe" size="18" />
                                </span>
                                <div class="flex min-w-0 flex-1 flex-col gap-0.5">
                                  <div class="flex items-baseline justify-between gap-3">
                                    <h3 class="text-base font-bold" id="reach-hosts-title">
                                      Network hosts
                                    </h3>
                                    <span
                                      class="text-muted-foreground shrink-0 text-xs font-semibold"
                                      >{{
                                        d.hosts.length === 0
                                          ? 'None'
                                          : count(d.hosts.length, 'host', 'hosts')
                                      }}</span
                                    >
                                  </div>
                                  <p class="text-muted-foreground text-[0.8125rem] text-pretty">
                                    What else its runs may reach, such as a package registry. Its
                                    systems need no entry here; a run asks for any other host.
                                  </p>
                                </div>
                              </div>

                              <div class="bg-card min-w-0 overflow-hidden rounded-xl border">
                                <div class="bg-muted/50 flex min-w-0 flex-col gap-2.5 p-3">
                                  <div
                                    class="flex flex-wrap items-baseline justify-between gap-x-3 gap-y-1 px-0.5"
                                  >
                                    <label hlmLabel for="new-host" class="text-[0.8125rem]"
                                      >Add a host</label
                                    >
                                    <span class="text-muted-foreground text-xs"
                                      >Its name only, without https:// or a path</span
                                    >
                                  </div>
                                  <div class="flex min-w-0 flex-wrap gap-2">
                                    <div class="relative min-w-0 flex-[1_1_13rem]">
                                      <ng-icon
                                        name="lucideGlobe"
                                        class="text-faint pointer-events-none absolute start-3 top-1/2 -translate-y-1/2"
                                        aria-hidden="true"
                                      />
                                      <input
                                        hlmInput
                                        id="new-host"
                                        class="w-full ps-9 font-mono"
                                        autocomplete="off"
                                        spellcheck="false"
                                        placeholder="registry.npmjs.org"
                                        [attr.aria-describedby]="
                                          hostError() ? 'new-host-error' : null
                                        "
                                        [value]="newHost()"
                                        [forceInvalid]="!!hostError()"
                                        (input)="
                                          newHost.set($any($event.target).value);
                                          hostError.set(null)
                                        "
                                        (keydown.enter)="$event.preventDefault(); addHost()"
                                      />
                                    </div>
                                    <div class="min-w-0 flex-[2_1_16rem]">
                                      <label class="sr-only" for="new-host-reason"
                                        >Why it needs this host (optional)</label
                                      >
                                      <input
                                        hlmInput
                                        id="new-host-reason"
                                        class="w-full"
                                        autocomplete="off"
                                        maxlength="500"
                                        placeholder="Why it needs it (optional)"
                                        [value]="newHostReason()"
                                        (input)="newHostReason.set($any($event.target).value)"
                                        (keydown.enter)="$event.preventDefault(); addHost()"
                                      />
                                    </div>
                                    <button
                                      hlmBtn
                                      variant="outline"
                                      type="button"
                                      class="border-primary/40 text-primary hover:bg-primary/5 hover:text-primary shrink-0"
                                      data-testid="add-host"
                                      (click)="addHost()"
                                    >
                                      <ng-icon name="lucidePlus" aria-hidden="true" />
                                      Add
                                    </button>
                                  </div>
                                  @if (hostError(); as message) {
                                    <p
                                      class="text-destructive px-0.5 text-sm"
                                      id="new-host-error"
                                      role="alert"
                                      data-error="new-host"
                                    >
                                      {{ message }}
                                    </p>
                                  }
                                </div>

                                @if (d.hosts.length === 0) {
                                  <div
                                    class="text-muted-foreground flex items-center gap-3 border-t p-4 text-sm"
                                    data-testid="no-hosts"
                                  >
                                    <ng-icon
                                      name="lucideGlobe"
                                      size="18"
                                      class="shrink-0"
                                      aria-hidden="true"
                                    />
                                    <span
                                      >No extra hosts: a run asks before it reaches anything beyond
                                      its systems.</span
                                    >
                                  </div>
                                }

                                @for (h of d.hosts; track h.host) {
                                  <div
                                    class="flex min-w-0 items-center gap-2 border-t px-3 py-2.5"
                                    [attr.data-host]="h.host"
                                  >
                                    <div class="flex min-w-0 flex-1 items-center gap-3">
                                      <span
                                        class="bg-secondary flex size-9 shrink-0 items-center justify-center rounded-[10px]"
                                        aria-hidden="true"
                                      >
                                        <ng-icon name="lucideGlobe" size="16" />
                                      </span>
                                      <span class="flex min-w-0 flex-col">
                                        <code
                                          class="font-mono text-[0.8125rem] font-medium break-all"
                                          >{{ h.host }}</code
                                        >
                                        <span class="text-muted-foreground text-xs text-pretty">{{
                                          h.reason || 'No reason given'
                                        }}</span>
                                      </span>
                                    </div>
                                    <button
                                      hlmBtn
                                      variant="ghost"
                                      size="sm"
                                      type="button"
                                      class="text-muted-foreground shrink-0"
                                      [attr.aria-label]="'Remove ' + h.host"
                                      (click)="removeHost(h.host)"
                                    >
                                      <ng-icon name="lucideTrash2" aria-hidden="true" />
                                      Remove
                                    </button>
                                  </div>
                                }
                              </div>
                              @if (errors().hosts; as message) {
                                <p class="text-destructive text-sm" role="alert" data-error="hosts">
                                  {{ message }}
                                </p>
                              }
                            </section>
                          </div>
                        }

                        @case ('sharing') {
                          <div class="flex flex-col gap-1.5" data-testid="sharing-groups">
                            <h3 class="text-sm font-semibold">Groups</h3>
                            @if (groupsRes.isLoading()) {
                              <p class="text-muted-foreground text-xs">Loading the groups…</p>
                            } @else if (groupsRes.error()) {
                              <p class="text-destructive text-xs" role="alert">
                                Couldn't load the groups; sharing stays as it is.
                              </p>
                            } @else if (groups().length === 0) {
                              <p class="text-muted-foreground text-xs">
                                The company has no groups yet; an admin creates them. You can still
                                share it with people.
                              </p>
                            } @else {
                              <ul class="grid gap-2 sm:grid-cols-2">
                                @for (g of groups(); track g.id) {
                                  @let shared = d.shareGroupIds.includes(g.id);
                                  <li class="min-w-0" [attr.data-group]="g.id">
                                    <label
                                      class="flex min-h-12 min-w-0 cursor-pointer items-center gap-2.5 rounded-xl border px-3 py-2"
                                      [class]="
                                        shared
                                          ? 'border-primary/50 bg-primary/5'
                                          : 'hover:bg-accent'
                                      "
                                    >
                                      <input
                                        type="checkbox"
                                        class="accent-primary size-4 shrink-0"
                                        [checked]="shared"
                                        (change)="toggleShare(g.id, $any($event.target).checked)"
                                      />
                                      <span class="flex min-w-0 flex-col">
                                        <span class="truncate text-sm font-semibold">{{
                                          g.name
                                        }}</span>
                                        <span class="text-muted-foreground text-xs"
                                          >{{ g.memberCount }}
                                          {{ g.memberCount === 1 ? 'member' : 'members' }}</span
                                        >
                                      </span>
                                    </label>
                                  </li>
                                }
                              </ul>
                            }
                          </div>
                          <div class="flex flex-col gap-1.5" data-testid="sharing-people">
                            <h3
                              class="flex items-center justify-between gap-2 text-sm font-semibold"
                            >
                              <span id="sharing-people-title">People</span>
                              <span class="text-muted-foreground text-xs font-normal"
                                >{{ d.sharePrincipalIds.length }} picked</span
                              >
                            </h3>
                            <p class="text-muted-foreground text-xs">
                              Someone in none of these groups, or in no group at all, may use it on
                              their own.
                            </p>
                            @if (membersRes.isLoading()) {
                              <p class="text-muted-foreground text-xs">Loading the members…</p>
                            } @else {
                              @if (membersRes.error()) {
                                <p class="text-destructive text-xs" role="alert">
                                  Couldn't load the company's members; only the people it is shared
                                  with are listed.
                                </p>
                              }
                              <div class="relative">
                                <ng-icon
                                  name="lucideSearch"
                                  class="text-faint pointer-events-none absolute start-3 top-1/2 -translate-y-1/2"
                                  aria-hidden="true"
                                />
                                <input
                                  hlmInput
                                  id="sharing-people-filter"
                                  class="w-full ps-9"
                                  type="search"
                                  autocomplete="off"
                                  placeholder="Find a person by name or email"
                                  aria-label="Find a person"
                                  [value]="peopleFilter()"
                                  (input)="peopleFilter.set($any($event.target).value)"
                                />
                              </div>
                              <ul
                                class="m-0 flex max-h-64 list-none flex-col overflow-y-auto rounded-lg border p-1"
                                aria-labelledby="sharing-people-title"
                              >
                                @for (person of shownPeople(); track person.principalId) {
                                  <li [attr.data-person]="person.principalId">
                                    <label
                                      class="hover:bg-muted/60 flex cursor-pointer items-center gap-3 rounded-md px-2 py-1.5 text-sm"
                                    >
                                      <input
                                        type="checkbox"
                                        class="accent-primary size-4 shrink-0"
                                        [checked]="d.sharePrincipalIds.includes(person.principalId)"
                                        (change)="
                                          togglePerson(
                                            person.principalId,
                                            $any($event.target).checked
                                          )
                                        "
                                      />
                                      <span class="flex min-w-0 flex-col">
                                        <span class="truncate font-medium">{{ person.name }}</span>
                                        @if (person.email) {
                                          <span class="text-muted-foreground truncate text-xs">{{
                                            person.email
                                          }}</span>
                                        }
                                      </span>
                                    </label>
                                  </li>
                                } @empty {
                                  <li class="text-muted-foreground px-2 py-1.5 text-xs">
                                    {{
                                      peopleFilter()
                                        ? 'Nobody matches.'
                                        : 'Nobody else to share it with.'
                                    }}
                                  </li>
                                }
                              </ul>
                            }
                          </div>
                          @if (errors().shares; as message) {
                            <p class="text-destructive text-sm" role="alert" data-error="shares">
                              {{ message }}
                            </p>
                          }
                        }
                      }
                    </div>
                  </section>
                }
                <!-- On a phone, Next on top, each the full width: a section's name can be long. -->
                <div
                  class="bg-muted/50 mt-auto flex flex-col-reverse gap-2 border-t px-4 py-3 sm:flex-row sm:items-center sm:justify-between sm:gap-3 sm:px-6"
                  data-testid="section-steps"
                >
                  @if (previousSection(); as previous) {
                    <button
                      hlmBtn
                      variant="ghost"
                      type="button"
                      class="max-sm:w-full"
                      (click)="step(previous.id)"
                    >
                      <ng-icon name="lucideArrowLeft" aria-hidden="true" />
                      {{ previous.label }}
                    </button>
                  } @else {
                    <span class="max-sm:hidden"></span>
                  }
                  @if (nextSection(); as next) {
                    <button
                      hlmBtn
                      variant="outline"
                      type="button"
                      class="max-sm:w-full"
                      (click)="step(next.id)"
                    >
                      Next: {{ next.label }}
                      <ng-icon name="lucideArrowRight" aria-hidden="true" />
                    </button>
                  } @else {
                    <button
                      hlmBtn
                      type="submit"
                      class="max-sm:w-full"
                      [disabled]="saving() || (editing() && sections().length === 0)"
                    >
                      {{ editing() ? 'Save' : 'Create agent' }}
                    </button>
                  }
                </div>
              </div>
            </div>
          </form>
        }
      </div>
    }

    <ng-template #heading>
      <div class="flex min-w-0 flex-col gap-1 sm:flex-[1_1_28rem]">
        <h1 class="text-xl font-bold tracking-tight text-balance sm:text-2xl">
          {{ editing() ? 'Edit ' + (saved()?.name ?? 'agent') : 'New agent' }}
        </h1>
        <p class="text-muted-foreground text-sm text-pretty">
          @if (editing()) {
            A change makes a new version. What widens the agent waits until each person who uses it
            accepts; what you remove applies at their next turn.
          } @else {
            Who it is, what it may use and whom you share it with. It holds no credentials: it acts
            as whoever uses it.
          }
        </p>
      </div>
    </ng-template>
  `,
})
export class AgentEditor {
  private readonly api = inject(Api);
  private readonly router = inject(Router);
  private readonly notify = inject(Notify);
  private readonly session = inject(Session);
  protected readonly current = inject(CurrentCompany);

  /** The agent to change; absent on `agents/new`. */
  readonly agentId = input<string | undefined>(undefined);
  /** `?tab=skills` opens that section first (the agent page's "Edit skills"). */
  readonly tab = input<string | undefined>(undefined);

  protected readonly sections = computed<AgentSection[]>(() => {
    const saved = this.saved();
    return saved ? changedSections(changeOf(saved, this.draft())) : [];
  });
  protected readonly sectionList = SECTIONS;
  protected readonly runtimeLabels = RUNTIME_LABELS;
  protected readonly timeLimitMin = TURN_TIME_LIMIT_MIN;
  protected readonly timeLimitMax = TURN_TIME_LIMIT_MAX;
  protected readonly kindLabels = SYSTEM_KIND_LABELS;
  protected readonly modeChoices = MODE_CHOICES;
  protected readonly accessChoices = ACCESS_CHOICES;
  protected readonly accessLabels = ACCESS_LABELS;

  protected readonly editing = computed(() => !!this.agentId());

  /** The agent as saved: the baseline of a change, replaced by each section's answer. */
  protected readonly saved = signal<Agent | null>(null);
  /** The deployment's time limit of a turn, which an agent without its own takes; known once saved. */
  protected readonly defaultTimeLimit = computed(
    () => this.saved()?.defaultTurnTimeLimitMinutes ?? null,
  );
  protected readonly draft = signal<AgentDraft>(EMPTY_DRAFT);
  protected readonly errors = signal<EditorErrors>({});
  /** What a refused save named, shown where it stands on the page. */
  protected readonly errorAt = signal<EditorErrorAt | null>(null);
  protected readonly formError = signal<string | null>(null);
  protected readonly saving = signal(false);

  protected readonly agentRes = resource({
    params: () => {
      const companyId = this.current.id();
      const agentId = this.agentId();
      return companyId && agentId ? { companyId, agentId } : undefined;
    },
    loader: ({ params }) => this.api.invoke(getAgent, params),
  });
  protected readonly notFound = computed(
    () => !this.saved() && toProblem(this.agentRes.error()).status === 404,
  );
  protected readonly mayEdit = computed(() => {
    const saved = this.saved();
    return !saved || canEditAgent(saved);
  });

  protected readonly systemsRes = resource({
    params: () => this.current.id(),
    loader: ({ params: companyId }) =>
      loadAll((cursor) => this.api.invoke(listSystems, { companyId, limit: 100, cursor })),
  });
  protected readonly companySystems = computed<ExternalSystem[]>(() =>
    (this.systemsRes.hasValue() ? (this.systemsRes.value() ?? []) : []).filter(
      (s) => !s.personal && AGENT_SYSTEM_KINDS.has(s.kind),
    ),
  );
  protected readonly addable = computed(() => {
    const on = new Set(this.draft().systems.map((s) => s.systemId));
    return this.companySystems().filter((s) => !on.has(s.id));
  });
  /** The system being added, while its tools are read. */
  protected readonly adding = signal<string | null>(null);

  /** What the person typed to find a system to add: its name, handle or kind. */
  protected readonly systemFilter = signal('');
  /** The kind of system the picker shows: `all`, or a group of `SYSTEM_GROUPS`. */
  protected readonly systemGroup = signal('all');
  /** Whether the person opened or closed the picker; until then it opens while nothing is added. */
  protected readonly pickerChoice = signal<boolean | null>(null);
  protected readonly pickerOpen = computed(
    () => this.pickerChoice() ?? this.draft().systems.length === 0,
  );

  private readonly matchingSystems = computed(() => {
    const q = this.systemFilter().trim().toLowerCase();
    return this.addable().filter(
      (s) =>
        !q || [s.name, s.handle, this.kindLabels[s.kind]].some((v) => v?.toLowerCase().includes(q)),
    );
  });
  /** The kinds of system there are to add, each with how many match what was typed. */
  protected readonly systemGroups = computed(() => {
    const addable = this.addable();
    const matching = this.matchingSystems();
    return [
      { id: 'all', label: 'All', count: matching.length },
      ...SYSTEM_GROUPS.filter((g) => addable.some((s) => g.kinds.includes(s.kind))).map((g) => ({
        id: g.id,
        label: g.label,
        count: matching.filter((s) => g.kinds.includes(s.kind)).length,
      })),
    ];
  });
  /** The kinds filter the list once it is long and holds more than one kind; the search always. */
  protected readonly showSystemGroups = computed(
    () => this.addable().length > 6 && this.systemGroups().length > 2,
  );
  /**
   * The kind the list is filtered by: the picked one while its filter shows, else all. An add can
   * hide the filters, or leave none of the picked kind; the list then shows every kind again.
   */
  protected readonly activeSystemGroup = computed(() => {
    const picked = this.systemGroup();
    return this.showSystemGroups() && this.systemGroups().some((g) => g.id === picked)
      ? picked
      : 'all';
  });
  /** The systems the picker lists: of the picked kind, matching what was typed, by name. */
  protected readonly systemResults = computed(() => {
    const group = SYSTEM_GROUPS.find((g) => g.id === this.activeSystemGroup());
    return this.matchingSystems()
      .filter((s) => !group || group.kinds.includes(s.kind))
      .sort((a, b) => a.name.localeCompare(b.name));
  });
  /** "12 systems in the company", "10 of 12 left to add"; nothing until they are read. */
  protected readonly catalogText = computed(() => {
    const all = this.companySystems().length;
    const left = this.addable().length;
    if (all === 0) return '';
    if (left === 0) return 'Every system of the company is on it';
    return left === all
      ? `${count(all, 'system', 'systems')} in the company`
      : `${left} of ${all} left to add`;
  });

  /** The systems whose tools show; until the person opens or closes one, the first. */
  private readonly openSystems = signal<readonly string[] | null>(null);

  protected readonly groupsRes = resource({
    params: () => this.current.id(),
    loader: ({ params: companyId }) =>
      loadAll((cursor) => this.api.invoke(listGroups, { companyId, limit: 100, cursor })),
  });
  protected readonly groups = computed(() =>
    this.groupsRes.hasValue() ? (this.groupsRes.value() ?? []) : [],
  );

  /** The company's members, to share the agent with people on their own. */
  protected readonly membersRes = resource({
    params: () => this.current.id(),
    loader: ({ params: companyId }) =>
      loadAll((cursor) => this.api.invoke(listMembers, { companyId, limit: 100, cursor })),
  });
  /** The people the sharing lists: not its owner, who is the caller for a new agent. */
  protected readonly people = computed(() => {
    const saved = this.saved();
    return sharePeople(
      this.membersRes.hasValue() ? (this.membersRes.value() ?? []) : [],
      saved?.memberShares ?? [],
      saved ? { principalId: saved.ownerPrincipalId } : { userId: this.session.user()?.id },
    );
  });
  protected readonly peopleFilter = signal('');
  protected readonly shownPeople = computed(() =>
    this.people().filter((p) => matchesPerson(p, this.peopleFilter())),
  );

  /** The tools and repositories each system serves, read once per visit. */
  private readonly tools = signal<Record<string, Loaded<SystemTool>>>({});
  private readonly repositories = signal<Record<string, Loaded<SystemRepository>>>({});

  protected readonly widening = computed(() => {
    const saved = this.saved();
    return saved ? wideningOf(saved, this.draft()) : [];
  });
  protected readonly changedText = computed(() =>
    this.sections()
      .map((s) => SECTION_LABELS[s])
      .join(', '),
  );

  protected readonly handlePlaceholder = computed(() => {
    const slug = this.draft()
      .name.toLowerCase()
      .normalize('NFKD')
      .replace(/[̀-ͯ]/g, '')
      .replace(/[^a-z0-9]+/g, '-')
      .replace(/^-+|-+$/g, '')
      .slice(0, 50);
    return slug || 'business-analyst';
  });

  // --- A new agent's first runtime ------------------------------------------------------------

  /** For a new agent only: the gateway's models (409 without a gateway) and the reader's subscriptions. */
  private readonly gatewayModelsRes = resource({
    params: () => (this.agentId() ? undefined : this.current.id()),
    loader: ({ params: companyId }) => this.api.invoke(listGatewayModels, { companyId }),
  });
  private readonly myAccessRes = resource({
    params: () => (this.agentId() ? undefined : this.current.id()),
    loader: ({ params: companyId }) => this.api.invoke(getMyModelAccess, { companyId }),
  });
  /** Whether the person changed a new agent's runtimes: the suggestion no longer applies. */
  private readonly runtimesPicked = signal(false);
  /** The runtime a new agent starts on, once both reads answered or failed (default-runtime.ts). */
  private readonly suggestion = computed<SuggestedRuntime | null>(() => {
    if (this.agentId()) return null;
    const models = this.gatewayModelsRes;
    const access = this.myAccessRes;
    if (models.isLoading() || access.isLoading()) return null;
    return suggestedRuntime(
      models.hasValue() ? (models.value()?.models ?? []) : null,
      access.hasValue() ? (access.value()?.subscriptions ?? []) : [],
    );
  });
  /** The suggestion's reason, while the draft still starts on it. */
  protected readonly shownSuggestion = computed(() => {
    const s = this.suggestion();
    return s && !this.runtimesPicked() && this.draft().runtimes[0] === s.runtime ? s : null;
  });

  /** In a fixed order, so a row stays under the cursor as it is checked or made the default. */
  protected readonly runtimeRows = computed(() => {
    const on = this.draft().runtimes;
    return RUNTIMES.map((runtime) => ({
      runtime,
      on: on.includes(runtime),
      default: on[0] === runtime,
    }));
  });

  protected readonly newHost = signal('');
  protected readonly newHostReason = signal('');
  protected readonly hostError = signal<string | null>(null);

  // --- Skills ----------------------------------------------------------------------------------

  protected readonly skillsMax = AGENT_SKILLS_MAX;
  /** Whether the Skills section was opened: the library is read then, once. */
  private readonly libraryWanted = linkedSignal<EditorSection, boolean>({
    source: () => this.section(),
    computation: (section, previous) => previous?.value || section === 'skills',
  });
  /** The skills of the library the reader sees, to add (ADR-0064). */
  protected readonly libraryRes = resource({
    params: () => (this.libraryWanted() ? this.current.id() : undefined),
    loader: ({ params: companyId }) =>
      loadAll((cursor) => this.api.invoke(listSkills, { companyId, limit: 100, cursor })),
  });
  protected readonly librarySkills = computed<SkillSummary[]>(() =>
    this.libraryRes.hasValue() ? (this.libraryRes.value() ?? []) : [],
  );
  protected readonly libraryFilter = signal('');
  /** The library's skills the draft does not have, matching the filter. */
  protected readonly addableSkills = computed(() => {
    const on = new Set(this.draft().skills.map((k) => k.skillId));
    const q = this.libraryFilter().trim().toLowerCase();
    return this.librarySkills().filter(
      (k) =>
        !on.has(k.id) &&
        (!q || k.name.toLowerCase().includes(q) || k.description.toLowerCase().includes(q)),
    );
  });

  // --- Sections --------------------------------------------------------------------------------

  /** The open section. */
  protected readonly section = linkedSignal<EditorSection>(
    () => SECTIONS.find((s) => s.id === this.tab())?.id ?? 'who',
  );
  /** On a phone: whether the open section shows, rather than the list of sections. */
  protected readonly phoneOnSection = linkedSignal(() => SECTIONS.some((s) => s.id === this.tab()));
  /** Whether the tabs and the open section stand side by side, rather than one screen each. */
  private readonly wide = mediaQuery(WIDE_SCREEN, true);
  protected readonly previousSection = computed(
    () => SECTIONS[SECTIONS.findIndex((s) => s.id === this.section()) - 1] ?? null,
  );
  protected readonly nextSection = computed(
    () => SECTIONS[SECTIONS.findIndex((s) => s.id === this.section()) + 1] ?? null,
  );

  /** Each section's tab: how it stands and, in a line or two, what it holds. */
  protected readonly sectionRows = computed(() => {
    const d = this.draft();
    return SECTIONS.map((s) => ({ ...s, ...this.summaryOf(s.id, d) }));
  });

  /** Whom it is shared with, by name: the groups, then the people. */
  private readonly sharedNames = computed(() => {
    const d = this.draft();
    return [
      ...this.groups()
        .filter((g) => d.shareGroupIds.includes(g.id))
        .map((g) => g.name),
      ...this.people()
        .filter((p) => d.sharePrincipalIds.includes(p.principalId))
        .map((p) => p.name),
    ];
  });

  /** The handle the preview shows: the one typed, else the one the name makes. */
  protected readonly previewHandle = computed(() => {
    const d = this.draft();
    return d.handle.trim() || (d.name.trim() ? this.handlePlaceholder() : '');
  });

  /** What a new agent's Save makes. */
  protected readonly newAgentStatus = computed(() => {
    const d = this.draft();
    const picked = d.shareGroupIds.length + d.sharePrincipalIds.length;
    if (picked === 0) return 'Saved as version 1, private to you until you share it.';
    const names = this.sharedNames();
    return names.length === picked
      ? `Saved as version 1, shared with ${namesText(names)}.`
      : 'Saved as version 1, shared with the groups and people you picked.';
  });

  /** In "What it may use": the systems shown with their tools, the first one until one is picked. */
  private readonly expandedSystems = computed(() => {
    const first = this.draft().systems[0]?.systemId;
    return this.openSystems() ?? (first ? [first] : []);
  });

  constructor() {
    // A new agent starts on the suggested runtime, until the person picks.
    effect(() => {
      const s = this.suggestion();
      if (!s) return;
      untracked(() => {
        if (!this.runtimesPicked()) this.draft.update((d) => ({ ...d, runtimes: [s.runtime] }));
      });
    });
    // The agent as read becomes the baseline and the draft, once.
    effect(() => {
      const agent = this.agentRes.hasValue() ? this.agentRes.value() : undefined;
      if (!agent) return;
      untracked(() => {
        if (this.saved()?.id === agent.id) return;
        this.saved.set(agent);
        this.draft.set(draftOf(agent));
      });
    });
    // Each system on the draft: its tools; each code host: its repositories.
    effect(() => {
      const companyId = this.current.id();
      const systems = this.draft().systems;
      if (!companyId) return;
      untracked(() => {
        for (const s of systems) {
          this.ensureTools(companyId, s.systemId);
          if (CODE_HOST_KINDS.has(s.kind)) this.ensureRepositories(companyId, s.systemId);
        }
      });
    });
  }

  private ensureTools(companyId: string, systemId: string): Promise<readonly SystemTool[]> {
    const known = this.tools()[systemId];
    if (known && known.state !== 'error') return Promise.resolve(known.items);
    this.tools.update((t) => ({ ...t, [systemId]: { state: 'loading', items: [] } }));
    return this.api.invoke(listSystemTools, { companyId, systemId }).then(
      (list) => {
        this.tools.update((t) => ({ ...t, [systemId]: { state: 'ok', items: list.items } }));
        return list.items;
      },
      (error: unknown) => {
        this.tools.update((t) => ({ ...t, [systemId]: { state: 'error', items: [] } }));
        throw error;
      },
    );
  }

  private ensureRepositories(companyId: string, systemId: string): void {
    const known = this.repositories()[systemId];
    if (known && known.state !== 'error') return;
    this.repositories.update((r) => ({ ...r, [systemId]: { state: 'loading', items: [] } }));
    loadAll((cursor) =>
      this.api.invoke(listSystemRepositories, { companyId, systemId, limit: 100, cursor }),
    ).then(
      (items) => this.repositories.update((r) => ({ ...r, [systemId]: { state: 'ok', items } })),
      () => this.repositories.update((r) => ({ ...r, [systemId]: { state: 'error', items: [] } })),
    );
  }

  // --- Editing ---------------------------------------------------------------------------------

  protected patch(change: Partial<AgentDraft>, field?: EditorField): void {
    this.draft.update((d) => ({ ...d, ...change }));
    if (field) this.clearError(field);
  }

  private clearError(field: EditorField): void {
    if (this.errors()[field]) this.errors.update(({ [field]: _, ...rest }) => rest);
    if (this.errorAt()?.field === field) this.errorAt.set(null);
  }

  /** Whether the tool row is the one a refused save named. */
  protected isErrorTool(systemId: string, tool: string): boolean {
    const at = this.errorAt();
    return !!at && at.systemId === systemId && at.tool === tool;
  }

  /** Whether a refused save's message stands at what it named, rather than under the section. */
  protected shownAt(field: 'systems' | 'repositories'): boolean {
    const at = this.errorAt();
    if (at?.field !== field) return false;
    const d = this.draft();
    return at.repositoryId
      ? d.repositories.some((r) => r.repositoryId === at.repositoryId)
      : d.systems.some((s) => s.systemId === at.systemId);
  }

  // --- Sections --------------------------------------------------------------------------------

  private sectionError(id: EditorSection): boolean {
    const e = this.errors();
    return FIELD_ORDER.some((field) => !!e[field] && FIELD_SECTIONS[field] === id);
  }

  /** How a section stands, and what it holds, for its tab. */
  private summaryOf(
    id: EditorSection,
    d: AgentDraft,
  ): { state: SectionState; summary: string; more: string } {
    const error = this.sectionError(id);
    switch (id) {
      case 'who': {
        const ready = !!d.name.trim() && d.runtimes.length > 0;
        return {
          state: error ? 'error' : ready ? 'done' : 'todo',
          summary: d.runtimes.length > 0 ? runtimesText([...d.runtimes]) : 'No runtime yet',
          more: '',
        };
      }
      case 'instructions': {
        const written = !!d.instructions.trim();
        return {
          state: error ? 'error' : written ? 'done' : 'todo',
          summary: written ? 'Written' : 'None yet',
          more: '',
        };
      }
      case 'reach': {
        const tools = d.systems.flatMap((s) => s.tools.filter((t) => t.offered));
        const allowed = tools.filter((t) => effectiveMode(t) === 'ALLOW').length;
        const asking = tools.length - allowed;
        const parts = [
          d.systems.length > 0 ? count(d.systems.length, 'system', 'systems') : '',
          tools.length > 0 ? count(tools.length, 'tool', 'tools') : '',
          d.repositories.length > 0
            ? count(d.repositories.length, 'repository', 'repositories')
            : '',
          d.hosts.length > 0 ? count(d.hosts.length, 'host', 'hosts') : '',
        ].filter(Boolean);
        return {
          state: error ? 'error' : parts.length > 0 ? 'done' : 'todo',
          summary: parts.length > 0 ? parts.join(' · ') : 'Only its workspace',
          more:
            tools.length > 0
              ? `${allowed} allowed, ${asking} ${asking === 1 ? 'needs' : 'need'} approval`
              : '',
        };
      }
      case 'skills': {
        const updates = d.skills.filter((k) => !k.deleted && k.version < k.latestVersion).length;
        return {
          state: error ? 'error' : d.skills.length > 0 ? 'done' : 'todo',
          summary: d.skills.length > 0 ? count(d.skills.length, 'skill', 'skills') : 'None yet',
          more: updates > 0 ? count(updates, 'update', 'updates') + ' in the library' : '',
        };
      }
      case 'sharing': {
        const picked = d.shareGroupIds.length + d.sharePrincipalIds.length;
        const names = this.sharedNames();
        return {
          state: error ? 'error' : picked > 0 ? 'done' : 'private',
          summary:
            picked === 0
              ? 'Private to you'
              : names.length === picked
                ? namesText(names)
                : count(picked, 'group or person', 'groups and people'),
          more: '',
        };
      }
    }
  }

  /**
   * Opens a section; on a phone, on its own screen. There the tabs hide as it opens, so the focus
   * goes to its heading rather than staying on a hidden tab; beside the tabs it stays on the tab.
   */
  protected openSection(id: EditorSection, focusHeading = !this.wide()): void {
    this.section.set(id);
    this.phoneOnSection.set(true);
    if (focusHeading) setTimeout(() => document.getElementById(`${id}-title`)?.focus());
  }

  /** Back and Next: opens the section and brings its heading to the reader. */
  protected step(id: EditorSection): void {
    this.openSection(id, true);
  }

  protected showSectionList(): void {
    this.phoneOnSection.set(false);
    setTimeout(() => document.getElementById(`section-tab-${this.section()}`)?.focus());
  }

  /** The arrow keys, Home and End move along the tabs of the sections, opening each. */
  protected onSectionKeys(event: KeyboardEvent): void {
    const to = tabKeyTarget(
      event.key,
      SECTIONS.map((s) => s.id),
      this.section(),
    ) as EditorSection | null;
    if (!to) return;
    event.preventDefault();
    this.section.set(to);
    document.getElementById(`section-tab-${to}`)?.focus();
  }

  protected isSystemOpen(systemId: string): boolean {
    return this.expandedSystems().includes(systemId);
  }

  /** Shows or hides a system's tools and repositories; the others stay as they are. */
  protected toggleSystem(systemId: string): void {
    const open = this.expandedSystems();
    this.openSystems.set(
      open.includes(systemId) ? open.filter((id) => id !== systemId) : [...open, systemId],
    );
  }

  private openSystem(systemId: string): void {
    const open = this.expandedSystems();
    if (!open.includes(systemId)) this.openSystems.set([...open, systemId]);
  }

  /** Done, or Escape in the search: the picker folds away and forgets what it was filtered by. */
  protected closePicker(): void {
    this.pickerChoice.set(false);
    this.systemFilter.set('');
    this.systemGroup.set('all');
    document.getElementById('system-search')?.focus();
  }

  protected initialsOf(name: string): string {
    return initials(name);
  }

  protected count(n: number, one: string, many: string): string {
    return count(n, one, many);
  }

  /**
   * "Jira Cloud · 4 tools · 2 need approval"; a code host adds its repositories. A kind its name
   * says is left out.
   */
  protected systemMeta(system: DraftSystem): string {
    const kind = this.kindLabels[system.kind];
    const asking = system.tools.filter((t) => t.offered && effectiveMode(t) === 'ASK').length;
    const parts = [
      ...(kind === system.name ? [] : [kind]),
      count(system.tools.length, 'tool', 'tools'),
      ...(asking > 0 ? [`${asking} ${asking === 1 ? 'needs' : 'need'} approval`] : []),
    ];
    if (CODE_HOST_KINDS.has(system.kind)) {
      const repositories = this.draft().repositories.filter(
        (r) => r.systemId === system.systemId,
      ).length;
      parts.push(count(repositories, 'repository', 'repositories'));
    }
    return parts.join(' · ');
  }

  protected toggleRuntime(runtime: AgentRuntime, on: boolean): void {
    this.runtimesPicked.set(true);
    const runtimes = this.draft().runtimes.filter((r) => r !== runtime);
    this.patch({ runtimes: on ? [...runtimes, runtime] : runtimes }, 'runtimes');
  }

  protected makeDefault(runtime: AgentRuntime): void {
    this.runtimesPicked.set(true);
    this.patch({ runtimes: [runtime, ...this.draft().runtimes.filter((r) => r !== runtime)] });
  }

  protected effectText(tool: DraftTool): string {
    return tool.effect ? TOOL_EFFECT_LABELS[tool.effect] : '';
  }

  /**
   * The mode the switch shows: what the tool does now, an "Allowed" that asks since it writes as
   * "Needs approval".
   */
  protected effectiveOf(tool: DraftTool) {
    return effectiveMode(tool);
  }

  protected asksSinceItWrites(tool: DraftTool): boolean {
    return asksSinceItWrites(tool);
  }

  protected isCodeHost(system: DraftSystem): boolean {
    return CODE_HOST_KINDS.has(system.kind);
  }

  private updateSystem(systemId: string, fn: (s: DraftSystem) => DraftSystem): void {
    this.draft.update((d) => ({
      ...d,
      systems: d.systems.map((s) => (s.systemId === systemId ? fn(s) : s)),
    }));
    this.clearError('systems');
  }

  protected setMode(systemId: string, tool: string, mode: AgentToolMode): void {
    if (mode !== 'ALLOW' && mode !== 'ASK') return;
    this.updateSystem(systemId, (s) => ({
      ...s,
      // The owner's pick, for the effect the row shows: the server chooses the mode anew for it.
      tools: s.tools.map((t) =>
        t.name === tool ? { ...t, mode, picked: effectOfPick(t.effect) } : t,
      ),
    }));
  }

  protected removeTool(systemId: string, tool: string): void {
    this.updateSystem(systemId, (s) => ({ ...s, tools: s.tools.filter((t) => t.name !== tool) }));
  }

  /** The tools the system serves that the draft does not list, each to add. */
  protected moreTools(system: DraftSystem): DraftTool[] {
    const served = this.tools()[system.systemId]?.items ?? [];
    const listed = new Set(system.tools.map((t) => t.name));
    return toolsForNewSystem(served).filter((t) => !listed.has(t.name));
  }

  protected addTool(systemId: string, tool: DraftTool): void {
    this.updateSystem(systemId, (s) => ({ ...s, tools: [...s.tools, tool] }));
  }

  /** Adds a system with the tools it serves, and opens it; the picker stays open for the next. */
  protected async addSystem(systemId: string): Promise<void> {
    const companyId = this.current.id();
    const system = this.companySystems().find((s) => s.id === systemId);
    if (!companyId || !system || this.adding()) return;
    this.adding.set(system.id);
    try {
      const served = await this.ensureTools(companyId, system.id);
      this.draft.update((d) => ({
        ...d,
        systems: [
          ...d.systems,
          {
            systemId: system.id,
            name: system.name,
            handle: system.handle,
            kind: system.kind,
            tools: toolsForNewSystem(served),
          },
        ],
      }));
      this.openSystem(system.id);
      this.pickerChoice.set(true);
      this.clearError('systems');
      // Its row in the picker is gone: the focus goes back to the search, for the next system.
      setTimeout(() => document.getElementById('system-search')?.focus());
    } catch (error) {
      this.errors.update((e) => ({
        ...e,
        systems: `Couldn't read the tools of ${system.name}. ${problemMessage(error)}`,
      }));
    } finally {
      this.adding.set(null);
    }
  }

  /** Removing a system takes its repositories with it, as the server does. */
  protected removeSystem(systemId: string): void {
    this.draft.update((d) => ({
      ...d,
      systems: d.systems.filter((s) => s.systemId !== systemId),
      repositories: d.repositories.filter((r) => r.systemId !== systemId),
    }));
    this.openSystems.set(this.expandedSystems().filter((id) => id !== systemId));
    this.clearError('systems');
    // Its row is gone: the focus goes to the search above the list.
    setTimeout(() => document.getElementById('system-search')?.focus());
  }

  protected repositoriesOf(systemId: string): Loaded<SystemRepository> {
    const loaded = this.repositories()[systemId] ?? { state: 'loading', items: [] };
    // A repository on the draft that the list does not hold (it failed, or it is gone) stays shown.
    const known = new Set(loaded.items.map((r) => r.id));
    const extra = this.draft()
      .repositories.filter((r) => r.systemId === systemId && !known.has(r.repositoryId))
      .map((r) => ({ id: r.repositoryId, systemId, name: r.name, url: '' }));
    return { state: loaded.state, items: [...loaded.items, ...extra] };
  }

  protected repositoryAccess(repositoryId: string): AgentRepositoryAccess | undefined {
    return this.draft().repositories.find((r) => r.repositoryId === repositoryId)?.access;
  }

  protected toggleRepository(repository: SystemRepository, on: boolean): void {
    this.draft.update((d) => ({
      ...d,
      repositories: on
        ? [
            ...d.repositories.filter((r) => r.repositoryId !== repository.id),
            {
              repositoryId: repository.id,
              systemId: repository.systemId,
              name: repository.name,
              access: 'READ',
            },
          ]
        : d.repositories.filter((r) => r.repositoryId !== repository.id),
    }));
    this.clearError('repositories');
  }

  protected setAccess(repositoryId: string, access: AgentRepositoryAccess): void {
    if (access !== 'READ' && access !== 'WRITE') return;
    this.draft.update((d) => ({
      ...d,
      repositories: d.repositories.map((r) =>
        r.repositoryId === repositoryId ? { ...r, access } : r,
      ),
    }));
    this.clearError('repositories');
  }

  protected addHost(): void {
    const raw = this.newHost();
    const problem = hostProblem(raw);
    if (problem) {
      this.hostError.set(problem);
      return;
    }
    const host = normalizeHost(raw);
    if (this.draft().hosts.some((h) => normalizeHost(h.host) === host)) {
      this.hostError.set(`${host} is listed already.`);
      return;
    }
    if (this.draft().hosts.length >= 100) {
      this.hostError.set('An agent may reach at most 100 network hosts.');
      return;
    }
    this.patch(
      {
        hosts: [...this.draft().hosts, { host, reason: this.newHostReason().trim() || null }],
      },
      'hosts',
    );
    this.newHost.set('');
    this.newHostReason.set('');
    this.hostError.set(null);
  }

  protected removeHost(host: string): void {
    this.patch({ hosts: this.draft().hosts.filter((h) => h.host !== host) }, 'hosts');
  }

  /** The skill as the saved agent pins it; none for a skill the change adds. */
  protected savedSkill(skillId: string): DraftSkill | undefined {
    return this.saved()?.skills.find((k) => k.skillId === skillId);
  }

  /** The versions a pin may move to: every version of the skill, which are never removed. */
  protected versionsOf(skill: DraftSkill): number[] {
    return Array.from({ length: skill.latestVersion }, (_, i) => skill.latestVersion - i);
  }

  /** Adds a skill of the library at its latest version. */
  protected addSkill(skill: SkillSummary): void {
    const d = this.draft();
    if (d.skills.length >= AGENT_SKILLS_MAX || d.skills.some((k) => k.skillId === skill.id)) return;
    const added: DraftSkill = {
      skillId: skill.id,
      name: skill.name,
      description: skill.description,
      version: skill.latestVersion,
      latestVersion: skill.latestVersion,
      deleted: false,
    };
    this.patch(
      {
        skills: [...d.skills, added].sort((a, b) => a.name.localeCompare(b.name)),
      },
      'skills',
    );
  }

  protected removeSkill(skillId: string): void {
    this.patch({ skills: this.draft().skills.filter((k) => k.skillId !== skillId) }, 'skills');
  }

  /** Moves a pin, up or down; a deleted skill's never moves. */
  protected setSkillVersion(skillId: string, value: string | number | null | undefined): void {
    const version = Number(value);
    this.patch(
      {
        skills: this.draft().skills.map((k) =>
          k.skillId === skillId &&
          !k.deleted &&
          Number.isInteger(version) &&
          version >= 1 &&
          version <= k.latestVersion
            ? { ...k, version }
            : k,
        ),
      },
      'skills',
    );
  }

  protected toggleShare(groupId: string, on: boolean): void {
    const ids = this.draft().shareGroupIds.filter((id) => id !== groupId);
    this.patch({ shareGroupIds: on ? [...ids, groupId] : ids }, 'shares');
  }

  protected togglePerson(principalId: string, on: boolean): void {
    const ids = this.draft().sharePrincipalIds.filter((id) => id !== principalId);
    this.patch({ sharePrincipalIds: on ? [...ids, principalId] : ids }, 'shares');
  }

  // --- Saving ----------------------------------------------------------------------------------

  /** The client's checks before a Save; the server checks everything again. */
  private validate(): EditorErrors {
    const d = this.draft();
    const errors: EditorErrors = {};
    const name = d.name.trim();
    if (!name) errors.name = 'Enter a name.';
    else if (name.length > 100) errors.name = 'At most 100 characters.';
    const handle = d.handle.trim().toLowerCase();
    if (handle && !/^[a-z0-9]([a-z0-9-]{0,48}[a-z0-9])?$/.test(handle)) {
      errors.handle =
        'Use 1 to 50 lower-case letters, digits and hyphens, not starting or ending with a hyphen.';
    }
    if (d.description.trim().length > 1000) errors.description = 'At most 1000 characters.';
    if (d.runtimes.length === 0) errors.runtimes = 'Pick at least one runtime.';
    const limit = turnTimeLimitProblem(d.turnTimeLimitMinutes);
    if (limit) errors.turnTimeLimitMinutes = limit;
    return errors;
  }

  /** Opens the section of the first field with a problem, and focuses the field or the section. */
  private focusFirstError(errors: EditorErrors): void {
    const first = FIELD_ORDER.find((field) => errors[field]);
    if (!first) return;
    const section = FIELD_SECTIONS[first];
    this.openSection(section, false);
    setTimeout(() => {
      const target = document.getElementById(FIELD_INPUTS[first] ?? `${section}-title`);
      target?.focus();
      target?.scrollIntoView?.({ block: 'center' });
    });
  }

  protected async save(event: Event): Promise<void> {
    event.preventDefault();
    const companyId = this.current.id();
    if (!companyId || this.saving()) return;
    this.formError.set(null);
    const invalid = this.validate();
    if (Object.keys(invalid).length > 0) {
      this.errors.set(invalid);
      this.focusFirstError(invalid);
      return;
    }
    this.errors.set({});
    this.errorAt.set(null);
    this.saving.set(true);
    try {
      const saved = this.saved();
      if (!saved) await this.create(companyId);
      else await this.change(companyId, saved);
    } finally {
      this.saving.set(false);
    }
  }

  private async create(companyId: string): Promise<void> {
    try {
      const agent = await this.api.invoke(createAgent, {
        companyId,
        body: createRequest(this.draft()),
      });
      // A new agent takes its skills in a call of their own; it exists already if that fails.
      if (this.draft().skills.length > 0) {
        try {
          await this.api.invoke(replaceAgentSkills, {
            companyId,
            agentId: agent.id,
            body: { skills: skillInputs(this.draft()) },
          });
        } catch (error) {
          this.notify.error(`${agent.name} created without its skills`, error);
          void this.router.navigate(['/c', companyId, 'agents', agent.id, 'edit'], {
            queryParams: { tab: 'skills' },
          });
          return;
        }
      }
      this.notify.success(
        `${agent.name} created`,
        agent.shares.length > 0 || agent.memberShares.length > 0
          ? 'Version 1, shared with the groups and people you picked.'
          : 'Version 1, private to you until you share it.',
      );
      void this.router.navigate(['/c', companyId, 'agents', agent.id]);
    } catch (error) {
      this.fail(error, 'create');
    }
  }

  /** Sends the changed sections in order; each answer becomes the baseline of the next. */
  private async change(companyId: string, start: Agent): Promise<void> {
    const change = changeOf(start, this.draft());
    const agentId = start.id;
    let agent = start;
    const done: AgentSection[] = [];
    for (const section of changedSections(change)) {
      try {
        switch (section) {
          case 'about':
            agent = await this.api.invoke(updateAgent, { companyId, agentId, body: change.about! });
            break;
          case 'systems':
            agent = await this.api.invoke(replaceAgentSystems, {
              companyId,
              agentId,
              body: change.systems!,
            });
            break;
          case 'repositories':
            agent = await this.api.invoke(replaceAgentRepositories, {
              companyId,
              agentId,
              body: change.repositories!,
            });
            break;
          case 'hosts':
            agent = await this.api.invoke(replaceAgentHosts, {
              companyId,
              agentId,
              body: change.hosts!,
            });
            break;
          case 'skills':
            agent = await this.api.invoke(replaceAgentSkills, {
              companyId,
              agentId,
              body: change.skills!,
            });
            break;
          case 'shares':
            agent = await this.api.invoke(replaceAgentShares, {
              companyId,
              agentId,
              body: change.shares!,
            });
            break;
        }
        this.saved.set(agent);
        done.push(section);
      } catch (error) {
        this.fail(error, section, done);
        return;
      }
    }
    this.notify.success(
      `${agent.name} saved`,
      agent.version > start.version ? `Version ${agent.version}.` : undefined,
    );
    void this.router.navigate(['/c', companyId, 'agents', agentId]);
  }

  private fail(error: unknown, section: AgentSection | 'create', done: AgentSection[] = []): void {
    const failure = editorFailure(error, section, this.draft());
    this.errors.set(failure.errors);
    this.errorAt.set(failure.at);
    const partly =
      done.length > 0
        ? `Saved: ${done.map((s) => SECTION_LABELS[s]).join(', ')}. Not saved yet: ${SECTION_LABELS[section as AgentSection]} and what follows.`
        : null;
    this.formError.set([partly, failure.message].filter(Boolean).join(' ') || null);
    if (this.formError()) {
      setTimeout(() => document.querySelector('[data-testid=editor-error]')?.scrollIntoView?.());
    }
    if (failure.at) this.showErrorAt(failure.at);
    else this.focusFirstError(failure.errors);
  }

  /** Opens the system, tool or repository a refused save names, and brings its message into view. */
  private showErrorAt(at: EditorErrorAt): void {
    const systemId =
      at.systemId ??
      this.draft().repositories.find((r) => r.repositoryId === at.repositoryId)?.systemId;
    this.openSection('reach', false);
    if (systemId) this.openSystem(systemId);
    setTimeout(() => {
      const message = document.querySelector<HTMLElement>('[data-error-at]');
      message?.scrollIntoView?.({ block: 'center' });
      message?.focus({ preventScroll: true });
    });
  }
}
