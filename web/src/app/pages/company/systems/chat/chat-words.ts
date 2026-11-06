import type { SystemKind } from '../../../../api/generated/models';

/**
 * The words of a chat system's page (package 5a; CONVENTIONS §15.16): the platform's name, and the
 * problems of its bot and secrets as the page says them.
 */

/** "Slack", "Teams": how a chat platform is named in a sentence. */
export function chatPlatform(kind: SystemKind): string {
  return kind === 'TEAMS' ? 'Teams' : 'Slack';
}

/** Problems of a chat system's bot and secrets, in the words of its page. */
export const CHAT_SYSTEM_MESSAGES: Record<string, string> = {
  SYSTEM_PAUSED:
    'An admin paused this system: no bot is set on it until it is resumed. Removing the bot still works.',
  BOT_ACCOUNT_IN_USE:
    'Another service account borrows this system’s bot. Only the same service account replaces its token: remove the bot first to move it.',
  PRINCIPAL_SUSPENDED: 'This service account is suspended. Resume it, or choose another one.',
  IDENTITY_IN_USE:
    'This bot is already another account or credential in this company. Use the token of this system’s own app.',
  SYSTEM_KIND_INVALID: 'Only a Slack or Teams system has a bot and chat secrets.',
  'status:404': 'This service account or system is gone. Reload the page.',
  'status:403': 'Only owners and admins can change the company’s chat systems.',
};
