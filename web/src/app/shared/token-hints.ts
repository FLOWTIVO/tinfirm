import type { SystemKind } from '../api/generated/models';
import { problemDetail, problemMessage, toProblem } from '../api/problem';

/** The longest token the API takes (`AccountTokenRequest.token`). */
export const MAX_TOKEN_LENGTH = 4000;

export interface TokenHint {
  /** The field's label. */
  readonly label: string;
  /** What the token is and where the person gets it, under the field. */
  readonly hint: string;
  readonly placeholder: string;
}

/**
 * What "Paste a token" asks for, per kind of system: the token as the system takes it, which the
 * server checks with the system's "who am I" before it keeps it (CONVENTIONS §15.3). Accounts and
 * the Connect card say the same.
 */
const TOKEN_HINTS: Partial<Record<SystemKind, TokenHint>> = {
  JIRA_DC: {
    label: 'Personal access token',
    hint: 'A personal access token of your own Jira account: in Jira, open your profile, then Personal Access Tokens, and create one.',
    placeholder: 'Your Jira personal access token',
  },
  JIRA_CLOUD: {
    label: 'Email and API token',
    hint: 'The email of your Atlassian account and an API token, joined by a colon: email:api-token. Create the token at id.atlassian.com under Security, API tokens.',
    placeholder: 'you@example.com:api-token',
  },
  GITHUB: {
    label: 'GitHub token',
    hint: 'A personal access token of your own GitHub account, fine-grained or classic, with access to the repositories and issues agents work on.',
    placeholder: 'github_pat_…',
  },
  GITLAB: {
    label: 'GitLab token',
    hint: 'A personal access token of your own GitLab account with the api scope.',
    placeholder: 'glpat-…',
  },
  GIT: {
    label: 'Token for git',
    hint: 'A token of your own at the git host, which clones the repositories and pushes where the agent writes.',
    placeholder: 'Your token for git',
  },
  MCP: {
    label: 'Token',
    hint: 'The token the MCP server issued to you. Tinfirm sends it with every call made as you.',
    placeholder: 'Your token for the MCP server',
  },
};

const FALLBACK_HINT: TokenHint = {
  label: 'Token',
  hint: 'A token of your own account on the system.',
  placeholder: 'Your token',
};

export function tokenHint(kind: SystemKind | null | undefined): TokenHint {
  return (kind && TOKEN_HINTS[kind]) || FALLBACK_HINT;
}

/** The prefix of the server's detail of `400 ACCOUNT_REFUSED`, before the system's own words. */
const REFUSED_PREFIX =
  /^The (?:system|platform) refused the (?:token|client credentials|client's token):\s*/i;

/** The system's own words in the detail of `400 ACCOUNT_REFUSED`, without the server's prefix. */
export function refusalReason(detail: string | null | undefined): string {
  return detail?.replace(REFUSED_PREFIX, '').trim() ?? '';
}

/** "jira answered 401" → "Jira answered 401.": a reason as a sentence of its own. */
function asSentence(text: string): string {
  const trimmed = text.trim();
  const capital = trimmed.charAt(0).toUpperCase() + trimmed.slice(1);
  return /[.!?]$/.test(capital) ? capital : `${capital}.`;
}

/**
 * Why `409 IDENTITY_IN_USE` refused one's own account, from its `details` (CONVENTIONS §15.3;
 * ADR-0072): the user is one of the system's credentials, which groups act with as a shared
 * account, so the person learns whom to ask; another member's is never said by who they are.
 */
export function identityInUseMessage(error: unknown, systemName: string): string {
  const credential = problemDetail(error, 'credentialName');
  if (problemDetail(error, 'holder') === 'CREDENTIAL' && credential) {
    return (
      `This ${systemName} account is the company’s credential “${credential}”, which groups act ` +
      `with as a shared account. Ask an owner or admin to give “${credential}” another user, such ` +
      'as a machine user; then connect yours.'
    );
  }
  return `This ${systemName} account already belongs to someone else in this company.`;
}

/**
 * Why connecting an account failed, in the words of the page. A refused token (`400
 * ACCOUNT_REFUSED`) is said in the server's own sentence, which names the system by its name and
 * says what it answered ("Jira answered 401 when asked whose the token is"), never with the token;
 * "{system} refused this token." only when the server gave no reason. An identity in use says who
 * holds it (`identityInUseMessage`). The other codes their message.
 */
export function accountProblemMessage(error: unknown, systemName: string): string {
  const problem = toProblem(error);
  if (problem.code === 'ACCOUNT_REFUSED') {
    const reason = refusalReason(problem.detail);
    return reason ? asSentence(reason) : `${systemName} refused this token.`;
  }
  if (problem.code === 'IDENTITY_IN_USE') {
    return identityInUseMessage(error, systemName);
  }
  return problemMessage(error, {
    OAUTH_NOT_AVAILABLE: `${systemName} offers no sign-in here. Paste a token instead.`,
    PRINCIPAL_SUSPENDED:
      'Your access to this company is suspended, so no account can be connected.',
    'status:404': `${systemName} is gone, or you may no longer use it. Reload the page.`,
  });
}
