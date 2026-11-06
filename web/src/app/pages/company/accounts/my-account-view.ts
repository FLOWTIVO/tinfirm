import type { GrantChoice, MyAccount } from '../../../api/generated/models';
import { ACCESS_LABELS, SYSTEM_KIND_LABELS } from '../../../shared/labels';

/**
 * How a system's row on Accounts › Systems reads (plan §5), from `MyAccount`: what a conversation
 * of the reader would bind there now (`binding`), their own account and its status, and the grants
 * of their groups. The client only puts the server's answer into words.
 */

/**
 * The chip's tone. `ok`: a conversation acts there now. `you`: the reader must act (orange, the
 * scarce orange). `waiting`: someone else must act (an admin). `idle`: nothing is connected yet,
 * which a conversation asks for when it first needs the system. `off`: switched off.
 */
export type AccountTone = 'ok' | 'you' | 'waiting' | 'idle' | 'off';

export interface MyAccountView {
  readonly key: string;
  readonly name: string;
  /** "GitHub", "Jira Data Center", "Your MCP server". */
  readonly kind: string;
  readonly chip: string;
  readonly tone: AccountTone;
  /** How conversations act on the system, in one or two sentences. */
  readonly says: string;
  /** The grants the reader would act through, one line each, when they matter. */
  readonly grants: readonly string[];
  /** The reader's own account's last error, while it is broken. */
  readonly error: string | null;
  /** Connect (or connect again) one's own account: sign in or paste a token. */
  readonly connect: 'connect' | 'again' | 'own' | null;
  readonly disconnect: boolean;
}

/** "Analysts: the credential CI bot, read-only". */
export function grantLine(grant: GrantChoice): string {
  return `${grant.groupName}: the credential ${grant.credentialName}, ${ACCESS_LABELS[grant.access]}`;
}

/** "Via Analysts, read-only": the one grant a conversation would act through, as a chip. */
function via(grant: GrantChoice | undefined): string {
  return grant ? `Via ${grant.groupName}, ${ACCESS_LABELS[grant.access]}` : 'Via a grant';
}

export function myAccountView(item: MyAccount): MyAccountView {
  const { system, account, grants, binding } = item;
  const name = system.name;
  const kind = system.personal ? 'Your MCP server' : SYSTEM_KIND_LABELS[system.kind];
  const login = account?.login ? ` (${account.login})` : '';
  const first = grants.at(0);
  const base = {
    key: system.id,
    name,
    kind,
    grants: [] as string[],
    error: null as string | null,
    disconnect: !!account,
  };
  switch (binding) {
    case 'OWN':
      return {
        ...base,
        chip: 'Your own account',
        tone: 'ok',
        says: account?.login
          ? `Agents act on ${name} as you: ${account.login}.`
          : `Agents act on ${name} as you, with your own account.`,
        connect: 'again',
      };
    case 'GRANTED':
      return {
        ...base,
        chip: via(first),
        tone: 'ok',
        says: `You hold no ${name} account of your own, so agents act with ${first ? `the credential ${first.credentialName}, lent to ${first.groupName}` : 'a credential lent to your group'}. Connect your own to act as yourself in new conversations.`,
        connect: 'own',
      };
    case 'CHOOSE':
      return {
        ...base,
        chip: 'Choose per conversation',
        tone: 'ok',
        says: `Several of your groups lend you a ${name} credential. A conversation asks which one to act with when it first needs ${name}.`,
        grants: grants.map(grantLine),
        connect: 'own',
      };
    case 'RECONNECT_YOURS':
      return {
        ...base,
        chip: 'Sign in again',
        tone: 'you',
        says: `Your ${name} account${login} stopped working. Conversations that need ${name} wait until you connect it again.`,
        error: account?.lastError ?? null,
        connect: 'again',
      };
    case 'RECONNECT_OR_GRANT':
      return {
        ...base,
        chip: 'Sign in again',
        tone: 'you',
        says: `Your ${name} account${login} stopped working. Connect it again, or a conversation offers ${first ? `the grant of ${first.groupName} (${ACCESS_LABELS[first.access]})` : 'a grant of your groups'} instead.`,
        grants: grants.map(grantLine),
        error: account?.lastError ?? null,
        connect: 'again',
      };
    case 'RECONNECT_COMPANY':
      return {
        ...base,
        chip: 'Waits for an admin',
        tone: 'waiting',
        says: `Agents act with ${first ? `the credential ${first.credentialName}, lent to ${first.groupName}` : 'a credential lent to your group'}, which an admin must sign in again. Admins were told. Meanwhile you can connect your own.`,
        connect: 'own',
      };
    case 'NOT_NEEDED':
      return {
        ...base,
        chip: 'No account needed',
        tone: 'ok',
        says: account
          ? `${name} needs no account: agents call it without one, so your own${login} is not used. You can disconnect it.`
          : `${name} needs no account: agents call it without one.`,
        connect: null,
      };
    case 'UNAVAILABLE':
      return {
        ...base,
        chip: 'Switched off',
        tone: 'off',
        says: system.personal
          ? 'Personal MCP servers are switched off in this company. Your server stays, but agents use it again only once an admin switches them on.'
          : `Agents cannot use ${name} now.`,
        connect: null,
      };
    default:
      return {
        ...base,
        chip: 'Not connected',
        tone: 'idle',
        says: `Connect your ${name} account now, or when an agent first needs it: the conversation asks you then.`,
        connect: 'connect',
      };
  }
}
