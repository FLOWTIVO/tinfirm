import { HttpErrorResponse } from '@angular/common/http';
import type { FieldTree, ValidationError } from '@angular/forms/signals';
import {
  editionRequiredMessage,
  licenceKeyMessage,
  seatsExhaustedMessage,
} from '../shared/licence-labels';
import type { CredentialMatch, FieldError, Problem } from './generated/models';

/**
 * Stable problem `code` values of the instance and company core (CONVENTIONS §6) and of the
 * redesign's domain (§15): systems, accounts and grants (package 1), model access and runner hosts
 * (package 2), agents, settings, conversations, their files and decisions (package 3), the kill
 * switches (package 6) and idempotent submissions (package R).
 */
export type ProblemCode =
  | 'VALIDATION_FAILED'
  | 'RATE_LIMITED'
  | 'DUPLICATE_EMAIL'
  | 'DUPLICATE_SECRET_NAME'
  | 'DUPLICATE_MEMBER'
  | 'USER_NOT_FOUND'
  | 'LAST_OWNER'
  | 'LAST_INSTANCE_ADMIN'
  | 'NOT_LOCAL_USER'
  | 'WRONG_PASSWORD'
  | 'EMAIL_IN_USE'
  | 'SECRET_MANAGED'
  | 'DUPLICATE_GROUP_NAME'
  // Personal access tokens (ADR-0045)
  | 'TOKEN_LIMIT'
  | 'TOKEN_INVALID'
  | 'TOKEN_EXPIRED'
  | 'RECENT_SIGN_IN_REQUIRED'
  // Service accounts (service identities), systems, accounts, credentials and grants (package 1,
  // §15.1 to §15.3; ADR-0072)
  | 'DUPLICATE_SERVICE_IDENTITY_NAME'
  | 'PRINCIPAL_SUSPENDED'
  | 'DUPLICATE_SYSTEM_NAME'
  | 'INVALID_BASE_URL'
  | 'SYSTEM_KIND_INVALID'
  | 'PERSONAL_SYSTEM'
  | 'PERSONAL_SYSTEMS_OFF'
  | 'OAUTH_NOT_AVAILABLE'
  | 'SYSTEM_APP_ENDPOINTS_CHANGED'
  | 'DUPLICATE_REPOSITORY'
  | 'ACCOUNT_REFUSED'
  | 'IDENTITY_IN_USE'
  | 'GRANT_NOT_SERVICE'
  | 'DUPLICATE_GRANT'
  | 'DUPLICATE_CREDENTIAL_NAME'
  // Model access and runner hosts (package 2, §15.4)
  | 'GATEWAY_NOT_CONFIGURED'
  | 'GATEWAY_UNREACHABLE'
  // Keys a company's gateway team provides (ADR-0094)
  | 'MODEL_KEYS_MANAGED'
  | 'MODEL_KEY_REFUSED'
  | 'CANNOT_RUN_ANYWHERE'
  | 'SUBSCRIPTION_GONE'
  | 'PERSONAL_HOST_CREDENTIAL_ONLY'
  | 'DUPLICATE_RUNNER_HOST_NAME'
  | 'RUNNER_HOST_HAS_ACTIVE_RUNS'
  | 'RUNNER_HOST_POOL_UNSUPPORTED'
  // Agents and settings (package 3a, §15.5)
  | 'DUPLICATE_AGENT_NAME'
  | 'DUPLICATE_AGENT_HANDLE'
  | 'SYSTEM_NOT_ON_AGENT'
  | 'UNKNOWN_TOOL'
  | 'RUNTIME_NOT_LISTED'
  | 'MODEL_NOT_ALLOWED'
  | 'MODEL_REQUIRED'
  | 'SUBSCRIPTION_NOT_SERVING'
  | 'SETTINGS_WIDEN'
  | 'AGENT_NOT_USABLE'
  | 'AGENT_NOT_EDITABLE'
  | 'AGENT_HAS_ACTIVE_RUNS'
  | 'NETWORK_HOST_LIMIT'
  // Conversations (package 3c, §15.7)
  | 'MODEL_ACCESS_UNAVAILABLE'
  | 'NO_HOST_FOR_CONVERSATION'
  | 'TOO_MANY_REPOSITORIES'
  | 'CONVERSATION_ENDED'
  | 'CONVERSATION_BOUND'
  | 'TURN_ACTIVE'
  | 'NOTHING_TO_RETRY'
  | 'BINDING_NOT_WAITING'
  | 'GRANT_NOT_OFFERED'
  // Conversations' files (package 3c part 3; ADR-0014)
  | 'ATTACHMENT_TOO_LARGE'
  | 'TOO_MANY_ATTACHMENTS'
  | 'DUPLICATE_ATTACHMENT_NAME'
  | 'ATTACHMENT_IS_OUTPUT'
  | 'CREDENTIAL_FOUND'
  | 'ATTACHMENT_NOT_SCANNABLE'
  | 'CONTENT_PURGED'
  // Decisions (package 3c part 2)
  | 'AGENT_PAUSED'
  | 'DECISION_DECIDED'
  // A team's approval through an AI client (package 6, §15.10)
  | 'DECIDE_IN_APP'
  // Kill switches (package 6, §5.4) and idempotent submissions (package R, §3.5)
  | 'COMPANY_STOPPED'
  | 'SYSTEM_PAUSED'
  | 'IDEMPOTENCY_KEY_REUSED'
  // Chats (package 5a, §15.16)
  | 'BOT_IDENTITY_MISMATCH'
  | 'BOT_ACCOUNT'
  | 'BOT_ACCOUNT_IN_USE'
  | 'BOT_NOT_SET'
  | 'CHAT_WORKSPACE_TAKEN'
  | 'CHAT_LINK_EXPIRED'
  | 'CHAT_LINK_USED'
  | 'ALREADY_LINKED'
  | 'NOT_A_CHAT_ACCOUNT'
  // The Gmail add-on (ADR-0083)
  | 'GMAIL_LINK_EXPIRED'
  | 'GMAIL_LINK_USED'
  | 'SERVICE_IDENTITY_IN_USE'
  // Governance (package 6, §15.11 to §15.13): suspension, departures, spend, the audit, retention
  | 'CANNOT_SUSPEND_SELF'
  | 'DEPARTURE_OPEN'
  | 'SPEND_RANGE_INVALID'
  | 'AUDIT_EXPORT_TOO_LARGE'
  | 'RETENTION_INVALID'
  | 'ERASURE_NOT_ALLOWED'
  | 'ON_HOLD'
  | 'ADMIN_ACCESS_REQUIRED'
  // The inbox's notices (§15.7)
  | 'NOTICE_NOT_DISMISSIBLE'
  // Channel triggers and the webhook (package 5b, §15.17)
  | 'CHANNEL_UNAVAILABLE'
  | 'CHANNEL_NOT_JOINED'
  | 'CHANNEL_TAKEN'
  | 'MAPPING_INVALID'
  | 'WEBHOOK_LIMIT_TOO_HIGH'
  | 'PLACE_IS_EXTERNAL'
  // Triggers (package 4, §15.15)
  | 'DUPLICATE_TRIGGER_NAME'
  | 'INVALID_TRIGGER'
  | 'SOURCE_ACCOUNT_REQUIRED'
  | 'ACCOUNT_NOT_ENTITLED'
  | 'MODEL_ACCESS_NOT_ENTITLED'
  | 'OPEN_EVENTS_NOT_ACKNOWLEDGED'
  | 'TRIGGER_NOT_EDITABLE'
  | 'AUTHOR_ACCOUNT_NOT_ADMINS'
  | 'TOO_MANY_TRIGGERS'
  | 'TRIGGER_LIMIT_TOO_HIGH'
  | 'SETTINGS_CHANGED'
  | 'TRIGGER_PAUSED'
  | 'SUBJECT_NOT_VISIBLE'
  | 'TRIGGER_CONDITIONS_FAIL'
  // Agent memory (ADR-0063, §15.18)
  | 'MEMORY_FULL'
  // The skills library (ADR-0064, the skills contract §9)
  | 'SKILL_INVALID'
  | 'SKILL_TOO_LARGE'
  | 'SKILL_NAME_CHANGED'
  | 'DUPLICATE_SKILL_NAME'
  | 'SKILL_CONTAINS_CREDENTIAL'
  | 'SKILL_NOT_EDITABLE'
  | 'SKILL_VERSION_LIMIT'
  | 'SKILL_DELETED'
  | 'SKILL_NAME_CONFLICT'
  | 'SKILL_NOT_ON_AGENT'
  | 'ERROR_REPORTING_SET_BY_OPERATOR'
  | 'ERROR_REPORTING_NO_DESTINATION'
  // The licence, its editions and seats (ADR-0077)
  | 'SEATS_EXHAUSTED'
  | 'EDITION_REQUIRED'
  | 'VIEWER_READ_ONLY'
  | 'LICENCE_KEY_INVALID'
  | 'LICENCE_KEY_EXPIRED'
  | 'LICENCE_KEY_SUPERSEDED'
  | 'LICENCE_SEATS_TOO_FEW'
  // SCIM provisioning (ADR-0087)
  | 'SCIM_NOT_ENABLED'
  | 'SCIM_TOKEN_LIMIT';

/** Normalized view of a failed request: an RFC 9457 problem plus the HTTP status. */
export interface ProblemInfo {
  /** HTTP status; 0 when the server could not be reached. */
  readonly status: number;
  readonly code?: string;
  readonly title?: string;
  readonly detail?: string;
  readonly fieldErrors: readonly FieldError[];
}

const CODE_MESSAGES: Record<ProblemCode, string> = {
  VALIDATION_FAILED: 'Some fields are invalid. Check the form and try again.',
  RATE_LIMITED: 'Too many attempts. Wait a minute and try again.',
  DUPLICATE_EMAIL: 'A user with this email already exists.',
  DUPLICATE_SECRET_NAME: 'A secret with this name already exists.',
  DUPLICATE_MEMBER: 'This user is already a member of the company.',
  USER_NOT_FOUND:
    'No user has this email. An instance admin has to create the account (or the person signs in with SSO once) first.',
  LAST_OWNER: 'A company needs at least one owner. Make someone else an owner first.',
  LAST_INSTANCE_ADMIN:
    'This is the last active instance admin. Make another user an instance admin first.',
  NOT_LOCAL_USER: 'This user signs in with SSO and has no local password.',
  WRONG_PASSWORD: 'The current password is incorrect.',
  EMAIL_IN_USE:
    'This email already belongs to another Tinfirm account. Accounts are never linked automatically.',
  SECRET_MANAGED: 'Tinfirm manages this secret where it is used; change it there.',
  DUPLICATE_GROUP_NAME: 'A group with this name already exists.',
  TOKEN_LIMIT: 'You have 25 active tokens, the most allowed. Revoke one you no longer use first.',
  TOKEN_INVALID: 'The token is invalid, expired or revoked.',
  TOKEN_EXPIRED: 'This token has expired, and an expired token is not renewed. Create a new one.',
  RECENT_SIGN_IN_REQUIRED:
    'Creating a token needs a sign-in of the last 10 minutes. Sign in again, then create it.',
  DUPLICATE_SERVICE_IDENTITY_NAME: 'A service account with this name already exists.',
  PRINCIPAL_SUSPENDED:
    'Suspended in this company: nothing acts as this person or service account, or with what it holds or borrows or its budget, until an admin resumes it.',
  DUPLICATE_SYSTEM_NAME: 'A system with this name already exists.',
  INVALID_BASE_URL: 'Tinfirm may not connect to this address.',
  SYSTEM_KIND_INVALID: 'This kind of system cannot be used here yet.',
  PERSONAL_SYSTEM: 'A personal MCP server serves only its owner, so it cannot be used here.',
  PERSONAL_SYSTEMS_OFF: 'Personal MCP servers are switched off in this company.',
  OAUTH_NOT_AVAILABLE: 'This system offers no sign-in. Connect an account with a token instead.',
  SYSTEM_APP_ENDPOINTS_CHANGED:
    'The server now names other sign-in endpoints. Saving again removes the accounts and credentials that signed in.',
  DUPLICATE_REPOSITORY: 'This system already lists this repository.',
  ACCOUNT_REFUSED: 'The system refused this token.',
  IDENTITY_IN_USE: 'This account on the system already belongs to someone else in this company.',
  GRANT_NOT_SERVICE:
    'Only a system’s credential can be lent to a group, never a member’s own account.',
  DUPLICATE_GRANT: 'This credential is already lent to this group.',
  DUPLICATE_CREDENTIAL_NAME:
    'The system has a credential of this name already. Names are unique on a system, ignoring case.',
  GATEWAY_NOT_CONFIGURED:
    'The company’s model gateway is not set up yet. An admin sets it up under Model access.',
  GATEWAY_UNREACHABLE: 'Tinfirm could not reach the model gateway.',
  MODEL_KEYS_MANAGED: 'This key cannot be entered or changed here.',
  MODEL_KEY_REFUSED: 'The gateway refused this key.',
  CANNOT_RUN_ANYWHERE: 'No runner host may run this as it is set up.',
  SUBSCRIPTION_GONE: 'This subscription is no longer on its runner host.',
  PERSONAL_HOST_CREDENTIAL_ONLY:
    'A personal runner host runs only work paid by its owner’s own subscriptions.',
  DUPLICATE_RUNNER_HOST_NAME: 'A runner host with this name already exists in this company.',
  RUNNER_HOST_HAS_ACTIVE_RUNS:
    'Runs are queued for or running on this runner host. Cancel them or wait for them to finish, then try again.',
  RUNNER_HOST_POOL_UNSUPPORTED:
    'This runner host keeps no ready sandboxes: only a company host with a VM per run does.',
  DUPLICATE_AGENT_NAME: 'An agent with this name already exists.',
  DUPLICATE_AGENT_HANDLE: 'Another agent already has this handle.',
  SYSTEM_NOT_ON_AGENT: 'The agent does not use this system.',
  UNKNOWN_TOOL: 'This tool is not offered here.',
  RUNTIME_NOT_LISTED: 'The agent does not list this runtime.',
  MODEL_NOT_ALLOWED: 'Your model access does not allow this model.',
  MODEL_REQUIRED: 'Choose a model: a budget pays only for a model the company’s gateway serves.',
  SUBSCRIPTION_NOT_SERVING: 'This subscription does not serve the runtime on your runner host.',
  SETTINGS_WIDEN: 'Your settings can narrow the agent, never widen it.',
  AGENT_NOT_USABLE:
    'This agent is not shared with you. Ask its owner to share it with you or your group.',
  AGENT_NOT_EDITABLE: 'Only the agent’s owner and the company’s admins can change this agent.',
  AGENT_HAS_ACTIVE_RUNS:
    'A run of this agent is queued or running. Wait for it to finish or cancel it, then try again.',
  NETWORK_HOST_LIMIT: 'An agent may reach at most 100 network hosts.',
  MODEL_ACCESS_UNAVAILABLE: 'Nobody can pay for this conversation as your settings stand.',
  NO_HOST_FOR_CONVERSATION: 'No runner host may run this conversation.',
  TOO_MANY_REPOSITORIES: 'Choose at most 20 repositories for the workspace.',
  CONVERSATION_ENDED: 'This conversation has ended and takes nothing more.',
  CONVERSATION_BOUND:
    'Only the person whose conversation this is writes, decides and connects accounts in it.',
  TURN_ACTIVE: 'A turn is still queued or running. Wait for it to end, or cancel it.',
  NOTHING_TO_RETRY: 'The last turn neither failed nor was cancelled, so there is nothing to retry.',
  BINDING_NOT_WAITING: 'This account was settled in the meantime.',
  GRANT_NOT_OFFERED: 'This grant cannot be used here now.',
  ATTACHMENT_TOO_LARGE: 'This file is larger than the server accepts.',
  TOO_MANY_ATTACHMENTS: 'This conversation has as many files as it can hold.',
  DUPLICATE_ATTACHMENT_NAME: 'This conversation already has a file with this name.',
  ATTACHMENT_IS_OUTPUT:
    'The agent saved this file as an output: it stays with its output and cannot be removed.',
  CREDENTIAL_FOUND:
    'The file contains a credential, so it is not stored. Remove the secret from it and try again.',
  ATTACHMENT_NOT_SCANNABLE: 'The file could not be scanned for credentials, so it is not stored.',
  CONTENT_PURGED:
    'The company’s retention removed this file’s content. Its name, size and hash stay; the file itself cannot be downloaded any more.',
  AGENT_PAUSED: 'The agent is paused: approvals wait until it is resumed.',
  DECISION_DECIDED: 'This was already decided, or it was cancelled.',
  DECIDE_IN_APP:
    'A team’s approval is decided in Tinfirm, never through an AI client. Open the conversation in Tinfirm to decide it.',
  COMPANY_STOPPED:
    'An admin stopped the company’s runs: no conversation starts and no approval is decided until the stop is lifted. Answers and messages are kept, and their turns wait.',
  SYSTEM_PAUSED:
    'An admin paused this system: testing it, reading its tools and connecting accounts on it wait until it is resumed.',
  IDEMPOTENCY_KEY_REUSED:
    'This request carried the key of an earlier, different one, so nothing was done. Reload the page and try again.',
  BOT_IDENTITY_MISMATCH:
    'This token belongs to another workspace or another bot than the system’s. Use the token of the app installed in this workspace.',
  BOT_ACCOUNT:
    'This is a chat system’s bot: it only receives and posts in the chat, and is never lent to a group, bound or used by a trigger.',
  BOT_ACCOUNT_IN_USE:
    'This is the chat system’s bot, or another service account borrows it: it is set, replaced and removed under Bot on the system’s page.',
  BOT_NOT_SET: 'The system has no bot yet. Set its bot first.',
  CHAT_WORKSPACE_TAKEN:
    'Another system of this company already serves this workspace or tenant. One system per workspace.',
  CHAT_LINK_EXPIRED:
    'This link has expired. Send the Tinfirm bot a direct message again for a new one.',
  CHAT_LINK_USED:
    'This link was already used. Send the Tinfirm bot a direct message again if you still need one.',
  ALREADY_LINKED:
    'Your account is already linked to another user of this chat. Unlink it under Accounts first.',
  NOT_A_CHAT_ACCOUNT: 'Only an account on a chat system can be removed here.',
  GMAIL_LINK_EXPIRED:
    'This link has expired. Choose Sign in to Tinfirm in Gmail again for a new one.',
  GMAIL_LINK_USED:
    'This link was already used. Choose Sign in to Tinfirm in Gmail again if you still need one.',
  SERVICE_IDENTITY_IN_USE:
    'A trigger acts through this service account or pays with its budget. Change or delete those triggers first.',
  CANNOT_SUSPEND_SELF: 'You cannot suspend yourself. Another admin can.',
  NOTICE_NOT_DISMISSIBLE: 'This notice asks you to act, so it stays until that is done.',
  DEPARTURE_OPEN:
    'Some items still need someone: the paused triggers, the groups left without members or the accounts they connected. Resolve them, or close the checklist anyway.',
  SPEND_RANGE_INVALID:
    'This range cannot be shown: it must end after it starts, cover at most 366 days and group by at most two things.',
  AUDIT_EXPORT_TOO_LARGE:
    'An export holds at most 1,000,000 events. Choose a shorter range and export it in parts.',
  RETENTION_INVALID:
    'A period is out of range, or chats’ content would be kept longer than other content.',
  ERASURE_NOT_ALLOWED:
    'Only ended conversations of a member still in the company can be erased. End it first.',
  ON_HOLD: 'A legal hold covers some of this, so nothing was erased. Lift the hold first.',
  ADMIN_ACCESS_REQUIRED:
    'Open an access to this company with a reason first: an instance admin who is not a member needs one.',
  CHANNEL_UNAVAILABLE:
    'The bot is not in this channel, or it is archived, deleted or shared with another organisation.',
  CHANNEL_NOT_JOINED:
    'The trigger’s author must be linked on this chat and a member of the channel.',
  CHANNEL_TAKEN: 'This channel already has a trigger of this agent, or already has a default.',
  MAPPING_INVALID: 'A pointer of the mapping is not a JSON Pointer, such as /issue/key.',
  WEBHOOK_LIMIT_TOO_HIGH: 'This is above the company’s most deliveries a minute, set in Settings.',
  PLACE_IS_EXTERNAL:
    'This conversation lives in its place, an issue or a chat: write there. Its open questions can be answered here.',
  DUPLICATE_TRIGGER_NAME: 'A trigger with this name already exists.',
  INVALID_TRIGGER: 'The trigger could never fire as it is set up.',
  SOURCE_ACCOUNT_REQUIRED:
    'The trigger needs an account on its own system: it reads the system and writes the results back with it.',
  ACCOUNT_NOT_ENTITLED:
    'The author may not use this account: pick their own, or a service account they may use and the credential it borrows.',
  MODEL_ACCESS_NOT_ENTITLED:
    'The author may not use this budget: pick their own, or a service account’s they may use.',
  OPEN_EVENTS_NOT_ACKNOWLEDGED:
    'Anyone who may write at its source drives this trigger, and its agent then writes without approval, with the author’s own account or a credential lent to a service account. Acknowledge it, or set the write tools to Ask.',
  TRIGGER_NOT_EDITABLE:
    'Only the trigger’s author and the company’s admins change it. Its team runs and pauses it.',
  AUTHOR_ACCOUNT_NOT_ADMINS:
    'Only the trigger’s author adds their own accounts or MCP servers to it. On their behalf, keep the ones it has, or name a service account’s credential.',
  TOO_MANY_TRIGGERS:
    'This system has as many triggers as it can hold (100), or the company as many schedules (50). Delete one first.',
  TRIGGER_LIMIT_TOO_HIGH: 'This is more firings per hour than the company allows.',
  SETTINGS_CHANGED:
    'Someone changed these settings meanwhile. They were read again; make your change on top of them.',
  TRIGGER_PAUSED: 'The trigger is paused, so it does not run.',
  SUBJECT_NOT_VISIBLE: 'The trigger’s account cannot read this issue, so it cannot run on it.',
  TRIGGER_CONDITIONS_FAIL: 'The issue does not meet the trigger’s scope or conditions.',
  MEMORY_FULL: 'The memory is full. Delete a note first, then accept this one.',
  SKILL_INVALID: 'The skill is not in the Agent Skills format.',
  SKILL_TOO_LARGE: 'The skill is too large.',
  SKILL_NAME_CHANGED:
    'A new version keeps the skill’s name: put its name back in the front matter of SKILL.md.',
  DUPLICATE_SKILL_NAME: 'The library already has a skill with this name.',
  SKILL_CONTAINS_CREDENTIAL:
    'The skill contains a credential, so nothing was stored. Remove it and upload the skill again.',
  SKILL_NOT_EDITABLE: 'Only the skill’s owner and the company’s admins can change this skill.',
  SKILL_VERSION_LIMIT:
    'This skill has 100 versions, the most it can hold. Create a new skill under another name instead.',
  SKILL_DELETED:
    'This skill was deleted from the library: an agent that pins it keeps its version, but it cannot be added or moved to another version.',
  SKILL_NAME_CONFLICT:
    'Two skills with the same name cannot be on one agent: a deleted skill it keeps has the name of one you add. Remove one of them.',
  SKILL_NOT_ON_AGENT:
    'The agent no longer has a skill your settings switch off. Reload the page to see the agent as it is now.',
  ERROR_REPORTING_SET_BY_OPERATOR:
    'The operator sets error reporting for this installation (TINFIRM_ERROR_REPORTS); it is changed there.',
  ERROR_REPORTING_NO_DESTINATION:
    'Automatic delivery needs a destination, which the operator sets with TINFIRM_ERROR_REPORTS_DSN.',
  // The licence's problems carry their numbers and names in `details`: see `licenceMessage`.
  SEATS_EXHAUSTED: seatsExhaustedMessage(undefined, undefined),
  EDITION_REQUIRED: editionRequiredMessage(undefined, undefined),
  VIEWER_READ_ONLY:
    'Viewers read what is shared with them, but start, change and answer nothing. An Owner or Admin can make you a Member.',
  LICENCE_KEY_INVALID: licenceKeyMessage('LICENCE_KEY_INVALID', undefined)!,
  LICENCE_KEY_EXPIRED: licenceKeyMessage('LICENCE_KEY_EXPIRED', undefined)!,
  LICENCE_KEY_SUPERSEDED: licenceKeyMessage('LICENCE_KEY_SUPERSEDED', undefined)!,
  LICENCE_SEATS_TOO_FEW: licenceKeyMessage('LICENCE_SEATS_TOO_FEW', undefined)!,
  SCIM_NOT_ENABLED:
    'The deployment does not allow SCIM for this company: whoever runs the instance sets companies[].scim.enabled.',
  SCIM_TOKEN_LIMIT:
    'Two SCIM tokens work already. Revoke the old one once your identity provider holds the new one.',
};

/**
 * The licence's problems in their own numbers and names (ADR-0077): how many seats a
 * `SEATS_EXHAUSTED` found taken, which feature and edition an `EDITION_REQUIRED` names. Null for
 * any other problem.
 */
function licenceMessage(code: string, details: Record<string, unknown> | undefined): string | null {
  if (code === 'SEATS_EXHAUSTED') {
    return seatsExhaustedMessage(details?.['licensed'], details?.['used']);
  }
  if (code === 'EDITION_REQUIRED') {
    return editionRequiredMessage(details?.['feature'], details?.['edition']);
  }
  return licenceKeyMessage(code, details);
}

/**
 * Codes whose `detail` carries the specific reason, so the message shows both: which address the
 * guard refused, why the system refused a token, which limit a file broke, why nobody can pay.
 */
const CODES_WITH_DETAIL: ReadonlySet<string> = new Set<ProblemCode>([
  'INVALID_BASE_URL',
  'ACCOUNT_REFUSED',
  'GATEWAY_UNREACHABLE',
  'MODEL_KEYS_MANAGED',
  'ATTACHMENT_TOO_LARGE',
  'TOO_MANY_ATTACHMENTS',
  'MODEL_ACCESS_UNAVAILABLE',
  'NO_HOST_FOR_CONVERSATION',
  'SERVICE_IDENTITY_IN_USE',
  'MAPPING_INVALID',
  'CHANNEL_NOT_JOINED',
  'INVALID_TRIGGER',
  'TRIGGER_PAUSED',
  'TRIGGER_CONDITIONS_FAIL',
]);

function isProblemLike(value: unknown): value is Problem {
  return typeof value === 'object' && value !== null;
}

function parseBody(body: unknown): Problem | undefined {
  if (typeof body === 'string') {
    try {
      const parsed: unknown = JSON.parse(body);
      return isProblemLike(parsed) ? parsed : undefined;
    } catch {
      return undefined;
    }
  }
  return isProblemLike(body) ? body : undefined;
}

/** Turns anything a request can throw into a {@link ProblemInfo}. */
export function toProblem(error: unknown): ProblemInfo {
  if (error instanceof HttpErrorResponse) {
    const body = parseBody(error.error);
    const fieldErrors = Array.isArray(body?.errors)
      ? body.errors.filter(
          (e): e is FieldError => typeof e?.field === 'string' && typeof e?.message === 'string',
        )
      : [];
    return {
      status: error.status,
      code: typeof body?.code === 'string' ? body.code : undefined,
      title: typeof body?.title === 'string' ? body.title : undefined,
      detail: typeof body?.detail === 'string' ? body.detail : undefined,
      fieldErrors,
    };
  }
  return {
    status: -1,
    fieldErrors: [],
    detail: error instanceof Error ? error.message : undefined,
  };
}

/**
 * A failed request whose body was asked for as a `Blob` (a download) carries its problem as one too:
 * this reads it back into an error {@link toProblem} understands. Anything else is returned as is.
 */
export async function readBlobError(error: unknown): Promise<unknown> {
  if (!(error instanceof HttpErrorResponse) || !(error.error instanceof Blob)) return error;
  const text = await error.error.text();
  return new HttpErrorResponse({
    error: text,
    headers: error.headers,
    status: error.status,
    statusText: error.statusText,
    url: error.url ?? undefined,
  });
}

export function hasProblemCode(error: unknown, code: ProblemCode): boolean {
  return toProblem(error).code === code;
}

/** A problem's body as sent: the extension members the contract's `Problem` does not declare. */
function rawBody(error: unknown): Record<string, unknown> | undefined {
  if (!(error instanceof HttpErrorResponse)) return undefined;
  return parseBody(error.error) as Record<string, unknown> | undefined;
}

/**
 * What the upload scan found, from a `422 CREDENTIAL_FOUND` problem (ADR-0014): its
 * `credentialMatches`. Never the value, only a vault secret's name or the name of a known
 * credential format. An owner or admin may store a file whose matches are all formats, with
 * `acknowledgeCredentialMatch`; a vault secret's value is never stored. Entries that are not
 * matches (a body from elsewhere) are left out.
 */
export function credentialMatches(error: unknown): CredentialMatch[] {
  const matches = rawBody(error)?.['credentialMatches'];
  if (!Array.isArray(matches)) return [];
  return matches.filter(
    (m): m is CredentialMatch =>
      typeof m === 'object' &&
      m !== null &&
      ((m as CredentialMatch).kind === 'VAULT_SECRET' ||
        (m as CredentialMatch).kind === 'FORMAT') &&
      typeof (m as CredentialMatch).name === 'string',
  );
}

/**
 * What a skill's credential scan found, from a `422 SKILL_CONTAINS_CREDENTIAL` problem (the
 * skills contract §4.2): its `credentialMatches`, the names of the vault's secrets and of the known
 * formats, never a value. Each is a match as an attachment's are (`{kind, name}`), or a bare name,
 * whose kind is then unknown (null).
 */
export function scanMatches(
  error: unknown,
): { kind: CredentialMatch['kind'] | null; name: string }[] {
  const matches = rawBody(error)?.['credentialMatches'];
  if (!Array.isArray(matches)) return [];
  return matches.flatMap((m: unknown) => {
    if (typeof m === 'string') return m ? [{ kind: null, name: m }] : [];
    if (typeof m !== 'object' || m === null) return [];
    const { kind, name } = m as Partial<CredentialMatch>;
    if (typeof name !== 'string' || !name) return [];
    return [{ kind: kind === 'VAULT_SECRET' || kind === 'FORMAT' ? kind : null, name }];
  });
}

/**
 * The reason a `409 MODEL_ACCESS_UNAVAILABLE` or `NO_HOST_FOR_CONVERSATION` gives in
 * `details.reason` (CONVENTIONS §6): package 2's refusal (`GATEWAY_NOT_CONFIGURED`,
 * `PRINCIPAL_INACTIVE`, `SUBSCRIPTION_GONE`, `PERSONAL_HOST_CREDENTIAL_ONLY`, and for a budget
 * `MODEL_REQUIRED`, `MODEL_NOT_ALLOWED`), or why no host may
 * run it (`NO_RUNNER_HOST`, `NO_HOST_FOR_CREDENTIALS`, `CANNOT_RUN_ANYWHERE`). Undefined otherwise.
 */
export function problemReason(error: unknown): string | undefined {
  return problemDetail(error, 'reason');
}

/**
 * A string member of a problem's `details`: `UNKNOWN_TOOL` names `systemId` and `tool`,
 * `SYSTEM_NOT_ON_AGENT` `systemId` or `repositoryId` (CONVENTIONS §15.5). Undefined otherwise.
 */
export function problemDetail(error: unknown, key: string): string | undefined {
  const details = rawBody(error)?.['details'];
  if (typeof details !== 'object' || details === null) return undefined;
  const value = (details as Record<string, unknown>)[key];
  return typeof value === 'string' ? value : undefined;
}

/** The holds a `409 ON_HOLD` names (its `holdIds`, or `details.holdIds`); empty otherwise. */
export function problemHoldIds(error: unknown): string[] {
  const body = rawBody(error);
  const details = body?.['details'];
  const ids =
    body?.['holdIds'] ??
    (typeof details === 'object' && details !== null
      ? (details as Record<string, unknown>)['holdIds']
      : undefined);
  return Array.isArray(ids) ? ids.filter((id): id is string => typeof id === 'string') : [];
}

/**
 * A problem's `details` as sent (CONVENTIONS §6), e.g. `details.systemId` of a trigger's
 * `ACCOUNT_NOT_ENTITLED`; undefined when there are none.
 */
export function problemDetails(error: unknown): Record<string, unknown> | undefined {
  const details = rawBody(error)?.['details'];
  return typeof details === 'object' && details !== null
    ? (details as Record<string, unknown>)
    : undefined;
}

export function isStatus(error: unknown, status: number): boolean {
  return error instanceof HttpErrorResponse && error.status === status;
}

function statusMessage(status: number): string | undefined {
  if (status === 0) return "Can't reach the server. Check your connection and try again.";
  if (status === 401) return 'Your session has ended. Sign in again.';
  if (status === 403) return "You don't have permission to do this.";
  if (status === 404) return "It doesn't exist, or you don't have access to it.";
  if (status === 429) return CODE_MESSAGES.RATE_LIMITED;
  if (status >= 500) return 'Something went wrong on the server. Try again in a moment.';
  return undefined;
}

function withPeriod(text: string): string {
  const trimmed = text.trim();
  return /[.!?]$/.test(trimmed) ? trimmed : `${trimmed}.`;
}

/**
 * A human-readable message for a failed request. Per-code overrides win, then known problem
 * codes, then per-status overrides, then the server's `detail`, then a message for the status.
 *
 * @param overrides per-call messages keyed by problem code (or `status:<n>`), e.g. to explain
 *   `LAST_OWNER` in the words of the screen.
 */
export function problemMessage(error: unknown, overrides: Record<string, string> = {}): string {
  const problem = toProblem(error);
  if (problem.code && overrides[problem.code]) return overrides[problem.code];
  const licence = problem.code ? licenceMessage(problem.code, problemDetails(error)) : null;
  if (licence) return licence;
  // A known code is more specific than a per-status message (e.g. a 409 RUNNER_HOST_HAS_ACTIVE_RUNS).
  if (problem.code && problem.code in CODE_MESSAGES) {
    const message = CODE_MESSAGES[problem.code as ProblemCode];
    return CODES_WITH_DETAIL.has(problem.code) && problem.detail
      ? `${message} ${withPeriod(problem.detail)}`
      : message;
  }
  if (overrides[`status:${problem.status}`]) return overrides[`status:${problem.status}`];
  if (problem.code?.startsWith('DUPLICATE_')) {
    return problem.detail ?? 'Something with this name already exists.';
  }
  if (problem.status > 0 && problem.status < 500 && problem.detail) return problem.detail;
  return (
    statusMessage(problem.status) ?? problem.detail ?? problem.title ?? 'Something went wrong.'
  );
}

/** The result of mapping a failed submit onto a signal form. */
export interface SubmissionFailure {
  /** Errors attached to individual fields; hand them back from the `submit()` action. */
  readonly fieldErrors: ValidationError.WithFieldTree[];
  /** A message for the whole form, or null when every error landed on a field. */
  readonly message: string | null;
}

/**
 * Maps a failed request onto a signal form: `errors[]` from the problem and known codes (via
 * `codeFields`) become field errors; anything else becomes a form-level message.
 */
export function mapSubmissionError<T extends object>(
  form: FieldTree<T>,
  error: unknown,
  options: {
    codeFields?: Partial<Record<ProblemCode, keyof T & string>>;
    overrides?: Record<string, string>;
  } = {},
): SubmissionFailure {
  const problem = toProblem(error);
  const fieldErrors: ValidationError.WithFieldTree[] = [];
  const children = form as unknown as Record<string, FieldTree<unknown> | undefined>;

  for (const fe of problem.fieldErrors) {
    const field = children[fe.field];
    if (field) fieldErrors.push({ kind: 'server', message: fe.message, fieldTree: field });
  }

  const codeField = problem.code ? options.codeFields?.[problem.code as ProblemCode] : undefined;
  const codeTarget = codeField ? children[codeField] : undefined;
  if (codeTarget) {
    fieldErrors.push({
      kind: 'server',
      message: problemMessage(error, options.overrides),
      fieldTree: codeTarget,
    });
    return { fieldErrors, message: null };
  }

  const allMapped =
    problem.fieldErrors.length > 0 && fieldErrors.length === problem.fieldErrors.length;
  return {
    fieldErrors,
    message: allMapped ? null : problemMessage(error, options.overrides),
  };
}
