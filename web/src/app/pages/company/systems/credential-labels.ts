import type {
  AccountAccess,
  CredentialLending,
  ExternalSystem,
  MemberAccountCount,
  SystemCredential,
} from '../../../api/generated/models';
import { problemDetail, problemMessage, toProblem } from '../../../api/problem';
import { plural } from '../../../shared/labels';
import type { SegmentedOption } from '../../../shared/segmented';
import { accountProblemMessage } from '../../../shared/token-hints';
import { isChatKind, systemKind } from './system-kinds';

/**
 * The words of a system's credentials (ADR-0072): the company's tokens and sign-ins on a system,
 * lent to groups (a grant) and to service accounts (a lending), each `READ` or `WRITE`.
 */

/** What whoever a credential is lent to may do with it. */
export const ACCESS_CHOICES: readonly {
  value: AccountAccess;
  label: string;
  hint: string;
}[] = [
  { value: 'READ', label: 'Read-only', hint: 'No write tool is called with it.' },
  {
    value: 'WRITE',
    label: 'Read and write',
    hint: 'Write tools are called with it, as the agent and the person allow.',
  },
];

export const ACCESS_LABELS: Record<AccountAccess, string> = {
  READ: 'Read-only',
  WRITE: 'Read and write',
};

/** The access as the pills of a grant's or a lending's row. */
export const ACCESS_OPTIONS: readonly SegmentedOption[] = ACCESS_CHOICES.map((choice) => ({
  value: choice.value,
  label: choice.label,
  title: choice.hint,
}));

/** "Token", "Sign-in" or "Client credentials": how a credential was connected. */
export const CREDENTIAL_KIND_LABELS: Record<SystemCredential['kind'], string> = {
  TOKEN: 'Token',
  OAUTH: 'Sign-in',
  CLIENT: 'Client credentials',
};

/** What an admin does to a credential the system refused, by how it was connected. */
export const CREDENTIAL_RECONNECT_LABELS: Record<SystemCredential['kind'], string> = {
  TOKEN: 'Store a new token',
  OAUTH: 'Sign in again',
  CLIENT: 'Store new client credentials',
};

export type CredentialTone = 'neutral' | 'success' | 'human' | 'destructive';

/**
 * A credential's state as its chip reads, and whether whoever manages it must act (orange): a
 * disabled one acts for nothing until enabled again; one the system refused is signed in again or
 * given a new token.
 */
export function credentialState(
  credential: Pick<SystemCredential, 'status' | 'enabled' | 'kind'>,
): { label: string; tone: CredentialTone } {
  if (!credential.enabled) return { label: 'Disabled', tone: 'neutral' };
  switch (credential.status) {
    case 'OK':
      return { label: 'Working', tone: 'success' };
    case 'RECONNECT':
      return { label: CREDENTIAL_RECONNECT_LABELS[credential.kind], tone: 'human' };
    default:
      return { label: 'Not working', tone: 'destructive' };
  }
}

/** "2 triggers act with it", or that none does: a lending's live triggers (ADR-0072). */
export function triggersActing(triggers: number): string {
  return triggers
    ? `${plural(triggers, 'trigger')} ${triggers === 1 ? 'acts' : 'act'} with it`
    : 'no trigger acts with it';
}

/**
 * What a service account's triggers do when the credential it borrows goes (withdrawn or removed):
 * "2 triggers of svc-reader act with it and pause until their authors name another"; null when none
 * acts with it, so nothing pauses.
 */
export function triggersPausing(
  lending: Pick<CredentialLending, 'name' | 'triggers'>,
): string | null {
  const n = lending.triggers;
  if (!n) return null;
  const one = n === 1;
  return `${plural(n, 'trigger')} of ${lending.name} ${one ? 'acts' : 'act'} with it and ${one ? 'pauses' : 'pause'} until ${one ? 'its author names' : 'their authors name'} another`;
}

/**
 * What a service account's triggers do when another credential is lent to it in place of the one
 * it borrows (ADR-0072): they follow it, and their authors are told. "2 triggers of svc-reader act
 * with it and switch to CI bot; their authors are told"; null when none acts with it.
 */
export function triggersFollowing(
  lending: Pick<CredentialLending, 'name' | 'triggers'>,
  credentialName: string,
): string | null {
  const n = lending.triggers;
  if (!n) return null;
  const one = n === 1;
  return `${plural(n, 'trigger')} of ${lending.name} ${one ? 'acts' : 'act'} with it and ${one ? 'switches' : 'switch'} to ${credentialName}; ${one ? 'its author is' : 'their authors are'} told`;
}

/**
 * How many members hold their own account on the system, as Members counts them (ADR-0072,
 * decision 11): "3 members connect their own account on GitHub (1 needs signing in again)."
 */
export function memberAccountsLine(count: MemberAccountCount, systemName: string): string {
  const total = count.ok + count.notOk;
  if (!total) return `No member connects their own account on ${systemName} yet.`;
  const again = count.notOk
    ? ` (${count.notOk} ${count.notOk === 1 ? 'needs' : 'need'} signing in again)`
    : '';
  return `${plural(total, 'member')} ${total === 1 ? 'connects' : 'connect'} their own account on ${systemName}${again}.`;
}

/** Whether a credential on the system is added by pasting a token: never on a chat or webhook. */
export function takesToken(system: Pick<ExternalSystem, 'kind'>): boolean {
  return systemKind(system.kind).accounts;
}

/**
 * Whether a credential on the system may be an OAuth client's own credentials
 * (`client_credentials`): Jira Cloud, for an Atlassian service account's OAuth 2.0 credential.
 */
export function takesClient(system: Pick<ExternalSystem, 'kind'>): boolean {
  return system.kind === 'JIRA_CLOUD';
}

/**
 * Whether a credential on the system is added by signing in with its app: where the app is set up,
 * and on Slack only for its `history` tool (the package 5 contract, open point 13).
 */
export function takesSignIn(system: Pick<ExternalSystem, 'kind' | 'oauthAvailable'>): boolean {
  if (!system.oauthAvailable) return false;
  return systemKind(system.kind).app || system.kind === 'SLACK';
}

/**
 * Whether a company system holds credentials on its page: a tracker, a code host, a git host and an
 * MCP server; a chat its bot and, on Slack, sign-ins for `history`. Never a personal server, whose
 * owner's own account is the only one there (400 PERSONAL_SYSTEM), nor a webhook.
 */
export function holdsCredentials(system: Pick<ExternalSystem, 'kind' | 'personal'>): boolean {
  if (system.personal) return false;
  return systemKind(system.kind).accounts || isChatKind(system.kind);
}

/** Messages of a credential's changes, its grants and lendings, in the words of the system's page. */
export const CREDENTIAL_MESSAGES: Record<string, string> = {
  DUPLICATE_CREDENTIAL_NAME:
    'The system has a credential of this name already. Names are unique on a system, ignoring case.',
  PERSONAL_SYSTEM:
    'A personal MCP server serves only its owner, so it holds no credential of the company.',
  SYSTEM_KIND_INVALID:
    'This system takes no token here: a chat’s bot is set under Bot, and a webhook holds no credential.',
  OAUTH_NOT_AVAILABLE: 'The system has no sign-in app yet. Paste a token, or set up its app first.',
  BOT_ACCOUNT_IN_USE:
    'This is the chat system’s bot: it is set, replaced and removed under Bot, and lent to no one else.',
  BOT_ACCOUNT:
    'This is the chat system’s bot: it only receives and posts in the chat, and is never lent to a group.',
  DUPLICATE_GRANT: 'This credential is lent to this group already. Change its access in the list.',
  GRANT_NOT_SERVICE: 'Only a system’s credential can be lent to a group.',
  'status:403': 'Only owners and admins can manage the company’s credentials.',
  'status:404': 'This credential, group or service account is gone. Reload the page.',
};

/**
 * Why a credential's token or sign-in was not taken: the system's own reason for a refused token;
 * for a user who is already a member's own account there, that the company needs a user of its
 * own (a member is never named); else the code's words.
 */
export function credentialProblem(error: unknown, systemName: string): string {
  const code = toProblem(error).code;
  if (code === 'ACCOUNT_REFUSED') return accountProblemMessage(error, systemName);
  if (code === 'IDENTITY_IN_USE') {
    const holder = problemDetail(error, 'holder');
    const credential = problemDetail(error, 'credentialName');
    if (holder === 'CREDENTIAL' && credential) {
      return `This ${systemName} user is already the credential “${credential}”.`;
    }
    return (
      `This ${systemName} user is a member’s own account. Use a user of the company’s own, such ` +
      'as a machine user.'
    );
  }
  return problemMessage(error, CREDENTIAL_MESSAGES);
}
