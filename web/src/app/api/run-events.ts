import type { RunEvent, RunFailureClass } from './generated/models';

/*
 * Shapes of `RunEvent.payload` per `type`. The OpenAPI contract types the payload as a free-form
 * object and points to the runner protocol's `$defs/EventPayload*`; these mirror
 * `protocol/runner-v2.schema.json` (the same shapes as v1, plus the exact token total), and the
 * server stores payloads unchanged. Readers must stay defensive: every field is checked before
 * use, because a newer runner host may send more or less than this.
 */

export type ToolCallKind =
  | 'read'
  | 'edit'
  | 'delete'
  | 'move'
  | 'search'
  | 'execute'
  | 'think'
  | 'fetch'
  | 'switch_mode'
  | 'other';

export type ToolCallStatus = 'pending' | 'in_progress' | 'completed' | 'failed';

export interface ToolCallLocation {
  readonly path: string;
  readonly line?: number | null;
}

export interface ToolCallDiff {
  readonly path: string;
  readonly oldText?: string | null;
  readonly newText: string;
}

/** AGENT_MESSAGE, AGENT_THOUGHT */
export interface TextPayload {
  readonly text: string;
}

/** TOOL_CALL */
export interface ToolCallPayload {
  readonly toolCallId: string;
  readonly title: string;
  readonly kind: ToolCallKind;
  readonly status: ToolCallStatus;
  readonly input?: Record<string, unknown> | null;
  readonly locations?: readonly ToolCallLocation[];
}

/** TOOL_CALL_UPDATE */
export interface ToolCallUpdatePayload {
  readonly toolCallId: string;
  readonly status?: ToolCallStatus;
  readonly title?: string;
  readonly output?: string | null;
  readonly diffs?: readonly ToolCallDiff[];
}

export type PlanEntryStatus = 'pending' | 'in_progress' | 'completed';

export interface PlanEntry {
  readonly content: string;
  readonly status: PlanEntryStatus;
  readonly priority?: 'high' | 'medium' | 'low';
}

/** PLAN: the complete plan, sent again whenever it changes. */
export interface PlanPayload {
  readonly entries: readonly PlanEntry[];
}

/** USAGE: cumulative totals for the whole run. */
export interface UsagePayload {
  /** Input neither read from nor written to the prompt cache; with no `cacheWriteTokens`, the writes too. */
  readonly inputTokens?: number;
  readonly outputTokens?: number;
  /** Input read from the prompt cache. */
  readonly cachedInputTokens?: number | null;
  /** Input written to the prompt cache (protocol v2; a v1 host counts it as input). */
  readonly cacheWriteTokens?: number | null;
  readonly contextUsed?: number | null;
  readonly contextSize?: number | null;
  readonly costUsd?: number | null;
  /** The exact total so far (protocol v2). */
  readonly totalTokens?: number | null;
  /** The input/output split is an estimate (Codex); `totalTokens` stays exact. */
  readonly estimated?: boolean | null;
}

/** RATE_LIMIT */
export interface RateLimitPayload {
  readonly status: 'allowed' | 'allowed_warning' | 'rejected';
  readonly utilization?: number | null;
  readonly resetsAt?: string | null;
  readonly window?: string | null;
}

export type RunPhase =
  'preparing' | 'cloning' | 'starting' | 'running' | 'finalizing' | 'pushing' | 'cancelling';

/** STATUS */
export interface StatusPayload {
  readonly phase: RunPhase;
  readonly message?: string | null;
}

/** GIT: a git step in one repository of the run. */
export interface GitPayload {
  /** The repository; absent on events stored before multi-repository runs. */
  readonly componentId?: string | null;
  /** The repository's directory under `/workspace`; absent on older stored events. */
  readonly dir?: string | null;
  readonly action: 'cloned' | 'committed' | 'pushed' | 'nothing_to_push';
  readonly branch: string;
  readonly headSha?: string | null;
  readonly commits?: number | null;
}

export type LogLevel = 'debug' | 'info' | 'warn' | 'error';

/** LOG */
export interface LogPayload {
  readonly level: LogLevel;
  readonly message: string;
}

/** ERROR */
export interface ErrorPayload {
  readonly message: string;
  readonly failureClass?: RunFailureClass;
}

// --- Defensive readers ---------------------------------------------------------------------

type Payload = RunEvent['payload'];

export function str(p: Payload, key: string): string | undefined {
  const v = p[key];
  return typeof v === 'string' ? v : undefined;
}

export function num(p: Payload, key: string): number | undefined {
  const v = p[key];
  return typeof v === 'number' && Number.isFinite(v) ? v : undefined;
}

export function has(p: Payload, key: string): boolean {
  return Object.prototype.hasOwnProperty.call(p, key);
}

export function readLocations(p: Payload): ToolCallLocation[] {
  const raw = p['locations'];
  if (!Array.isArray(raw)) return [];
  return raw
    .filter((l): l is { path: string; line?: unknown } => typeof l?.path === 'string')
    .map((l) => ({ path: l.path, line: typeof l.line === 'number' ? l.line : null }));
}

export function readDiffs(p: Payload): ToolCallDiff[] {
  const raw = p['diffs'];
  if (!Array.isArray(raw)) return [];
  return raw
    .filter(
      (d): d is { path: string; newText: string; oldText?: unknown } =>
        typeof d?.path === 'string' && typeof d?.newText === 'string',
    )
    .map((d) => ({
      path: d.path,
      newText: d.newText,
      oldText: typeof d.oldText === 'string' ? d.oldText : null,
    }));
}

export function readPlanEntries(p: Payload): PlanEntry[] {
  const raw = p['entries'];
  if (!Array.isArray(raw)) return [];
  return raw
    .filter((e): e is PlanEntry => typeof e?.content === 'string')
    .map((e) => ({
      content: e.content,
      status: e.status === 'completed' || e.status === 'in_progress' ? e.status : 'pending',
      ...(e.priority ? { priority: e.priority } : {}),
    }));
}
