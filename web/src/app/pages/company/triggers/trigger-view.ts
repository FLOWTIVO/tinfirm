import type {
  Trigger,
  TriggerAccount,
  TriggerHostCheck,
  TriggerPayer,
  TriggerPreview,
  TriggerPreviewAccount,
  TriggerSystemRef,
} from '../../../api/generated/models';
import { formatDay } from '../../../shared/labels';
import type { WapLine, WhoActsWhoPaysView } from '../../../shared/who-acts-who-pays-view';

/**
 * Who acts and who pays for a trigger (plan §2, rule 2, and §4.5), as `app-who-acts-who-pays`
 * draws it: from the editor's preview before a save, and from the stored trigger on its page. It
 * repeats the server's answers; "your" is said for the reader's own account and budget.
 */

type RowStatus = TriggerAccount['status'] | TriggerPreviewAccount['status'];

interface Row {
  readonly systemId: string;
  readonly systemName?: string | null;
  readonly accountId?: string | null;
  readonly holderPrincipalId?: string | null;
  readonly holderName?: string | null;
  /** The credential a service account borrows, which the row acts with (ADR-0072). */
  readonly credentialName?: string | null;
  readonly externalLogin?: string | null;
  /** `READ`: the credential is lent read-only, so the row calls no write tool. */
  readonly access?: TriggerAccount['access'];
  readonly status: RowStatus;
}

function whose(row: Row, reader: string | null): string {
  if (row.holderPrincipalId && row.holderPrincipalId === reader) return 'your own account';
  if (row.credentialName)
    return `${row.holderName ?? 'a service account'} with ${row.credentialName}`;
  return row.holderName ? `${row.holderName}’s account` : 'an account';
}

/**
 * One row of the account table: "Jira: svc-ba with CI bot (svc-ba)", "Jira: your own account",
 * "GitHub: svc-ba with reader-bot, read-only" for a credential lent read-only.
 */
export function accountLine(row: Row, reader: string | null): WapLine {
  const name = row.systemName ?? 'A system';
  const login =
    row.externalLogin && row.holderPrincipalId !== reader ? ` (${row.externalLogin})` : '';
  const readOnly = row.access === 'READ' ? ', read-only' : '';
  const text = `${name}: ${whose(row, reader)}${login}${readOnly}`;
  const line = (tone: WapLine['tone'], detail: string | null = null): WapLine => ({
    key: row.systemId,
    text,
    tone,
    detail,
  });
  if (!row.accountId && row.status !== 'NOT_ENTITLED') {
    return {
      key: row.systemId,
      text: `${name}: no account`,
      tone: 'gone',
      detail: 'The account was disconnected; name another.',
    };
  }
  switch (row.status) {
    case 'OK':
      return line('ok');
    case 'RECONNECT_YOURS':
      return line('you', 'Sign in again on Accounts: until then the trigger is paused.');
    case 'RECONNECT_COMPANY':
      return line('blocked', 'An admin must sign it in again.');
    case 'SUSPENDED':
      return line('blocked', 'Its holder is suspended.');
    case 'NOT_ENTITLED':
      return line('you', 'The author may not use it: pick another.');
    case 'ACCOUNT_REMOVED':
      return line('gone', 'Disconnected: name another.');
  }
}

function unavailableLine(ref: TriggerSystemRef): WapLine {
  return {
    key: `unavailable-${ref.systemId}`,
    text: `${ref.systemName ?? 'A system'}: not available to this trigger’s runs`,
    tone: 'gone',
  };
}

/** An MCP server that needs no account (ADR-0071): the trigger's runs call it without one. */
function noAccountLine(ref: TriggerSystemRef): WapLine {
  return {
    key: `no-account-${ref.systemId}`,
    text: `${ref.systemName ?? 'A system'}: no account needed`,
    tone: 'ok',
  };
}

/** Who pays: "svc-ba’s budget", "Your budget", with the wait or the refusal. */
export function payerLine(payer: TriggerPayer | null | undefined, reader: string | null): WapLine {
  if (!payer) return { key: 'payer', text: 'Nobody pays yet', tone: 'you' };
  const mine = payer.principalId === reader;
  const owner = mine ? 'Your' : `${payer.name ?? 'Its payer'}’s`;
  const what = payer.modelAccess === 'SUBSCRIPTION' ? 'subscription' : 'budget';
  const text = `${owner} ${what}`;
  switch (payer.state) {
    case 'READY':
      return { key: 'payer', text, tone: 'ok', detail: payer.detail ?? null };
    case 'WAITING': {
      const until = formatDay(payer.until);
      return {
        key: 'payer',
        text,
        tone: 'blocked',
        detail:
          payer.detail ??
          (until ? `Spent: its turns wait until ${until}.` : 'Its turns wait for now.'),
      };
    }
    case 'REFUSED':
      return {
        key: 'payer',
        text,
        tone: 'blocked',
        detail: payer.detail ?? refusalText(payer.reason),
      };
  }
}

const REFUSALS: Record<string, string> = {
  GATEWAY_NOT_CONFIGURED: 'An admin must set up model access first.',
  PRINCIPAL_INACTIVE: 'The payer is suspended.',
  SUBSCRIPTION_GONE: 'The subscription is no longer on its runner host.',
  PERSONAL_HOST_CREDENTIAL_ONLY: 'A personal runner host runs only its owner’s subscriptions.',
};

function refusalText(reason: string | null | undefined): string {
  return (reason && REFUSALS[reason]) ?? 'Nobody can pay for its runs as it stands.';
}

/** Where it runs: whether a runner host may take its runs. */
export function hostsLine(hosts: TriggerHostCheck): WapLine {
  if (hosts.runnable) {
    return {
      key: 'hosts',
      text:
        hosts.eligible === 1
          ? 'A runner host can run it'
          : `${hosts.eligible} runner hosts can run it`,
      tone: 'ok',
    };
  }
  return {
    key: 'hosts',
    text: 'No runner host can run it now',
    tone: 'blocked',
    detail: hosts.reason ?? null,
  };
}

/** The editor's preview as the panel's view. */
export function fromTriggerPreview(
  preview: TriggerPreview,
  reader: string | null,
): WhoActsWhoPaysView {
  return {
    acts: [
      ...preview.accounts.map((a) => accountLine(a, reader)),
      ...preview.unavailableSystems.map(unavailableLine),
      ...preview.noAccountSystems.map(noAccountLine),
    ],
    actsNote:
      preview.openConversationsKeepingAccounts && preview.openConversationsKeepingAccounts > 0
        ? `${preview.openConversationsKeepingAccounts === 1 ? '1 open conversation keeps' : `${preview.openConversationsKeepingAccounts} open conversations keep`} the accounts it started with.`
        : null,
    pays: payerLine(preview.payer, reader),
    runs: hostsLine(preview.hosts),
  };
}

/** A stored trigger as the panel's view: its table, its payer; where it runs is not stored. */
export function fromTrigger(trigger: Trigger, reader: string | null): WhoActsWhoPaysView {
  return {
    acts: [
      ...trigger.accounts.map((a) => accountLine(a, reader)),
      ...trigger.unavailableSystems.map(unavailableLine),
      ...trigger.noAccountSystems.map(noAccountLine),
    ],
    actsNote: null,
    pays: payerLine(trigger.payer, reader),
    runs: null,
  };
}

/**
 * The phone's sticky summary of the preview (plan §4.5): "Acts as svc-ba · paid by svc-ba · can
 * run".
 */
export function previewSummary(preview: TriggerPreview, reader: string | null): string {
  const holders = [
    ...new Set(
      preview.accounts.map((a) =>
        a.holderPrincipalId && a.holderPrincipalId === reader ? 'you' : (a.holderName ?? '—'),
      ),
    ),
  ];
  const acts = holders.length ? `Acts as ${holders.join(' and ')}` : 'Acts as nobody yet';
  const payer = preview.payer.principalId === reader ? 'you' : (preview.payer.name ?? 'its payer');
  const pays = preview.payer.state === 'REFUSED' ? `${payer} cannot pay` : `paid by ${payer}`;
  const runs = preview.hosts.runnable ? 'can run' : 'cannot run now';
  return `${acts} · ${pays} · ${runs}`;
}
