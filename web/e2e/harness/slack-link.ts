/**
 * Links a seeded person's Slack user through the API, as W6's spec does through the pages (package 5, §5.4): an
 * admin registers a Slack system on the stub, stores its signing secret and sets its bot; the user writes the bot a
 * direct message; the bot answers with its sign-in link; the person confirms it. Their inbox then holds the link
 * (`CHAT_LINKED`). `remove()` deletes the system and its service identity, and the link goes with them. TEST
 * FIXTURE ONLY.
 */
import type { Api } from './api.ts';
import type { SlackControl } from './control-client.ts';
import { waitFor } from './http-util.ts';
import { deliverSlackEvent, slackDirectMessage, slackWorkspace } from './slack-stub.ts';

const SIGNING_SECRET = 'e2e-signing-secret-0123456789abcdef'; // gitleaks:allow (test fixture)

export interface SlackLink {
  systemId: string;
  systemName: string;
  chatUserId: string;
  remove(): Promise<void>;
}

export async function linkSlackUser(options: {
  admin: Api;
  member: Api;
  path: (suffix: string) => string;
  serverUrl: string;
  slack: SlackControl;
  /** Keeps the workspace, the system's name and the chat user apart between projects and runs. */
  tag: string;
  displayName: string;
}): Promise<SlackLink> {
  const { admin, member, path, slack, tag } = options;
  const workspace = slackWorkspace(tag);
  const chatUserId = `U${workspace.teamId.slice(1)}L`;
  const systemName = `Slack ${tag}`;
  const identity = await admin.post(path('/service-identities'), {
    name: `svc-slack-${tag.toLowerCase()}`,
    description: 'Holds the Slack bot',
  });
  const system = await admin.post(path('/systems'), {
    name: systemName,
    kind: 'SLACK',
    slack: { transport: 'EVENTS_API' },
  });
  const remove = async () => {
    await admin.delete(path(`/systems/${system.id}`)).catch(() => undefined);
    await admin.delete(path(`/service-identities/${identity.id}`)).catch(() => undefined);
  };
  try {
    await admin.put(path(`/systems/${system.id}/chat/secrets`), { signingSecret: SIGNING_SECRET });
    await admin.put(path(`/systems/${system.id}/bot`), {
      principalId: identity.id,
      token: workspace.botToken,
    });
    await slack.setUser(chatUserId, options.displayName);
    const status = await deliverSlackEvent(
      options.serverUrl,
      system.id,
      SIGNING_SECRET,
      slackDirectMessage(workspace, chatUserId, 'Hello, Tinfirm'),
    );
    if (status !== 200) throw new Error(`The Events API answered ${status}`);
    const token = await waitFor('the bot’s sign-in link', async () => {
      for (const post of await slack.posts(`D${chatUserId}`)) {
        const found = /\/link\/[0-9a-f-]+#([A-Za-z0-9_-]+)/.exec(JSON.stringify(post.body));
        if (found) return found[1];
      }
      return undefined;
    });
    await member.post(path('/chat-links/confirm'), { token });
    return { systemId: system.id, systemName, chatUserId, remove };
  } catch (error) {
    await remove();
    throw error;
  }
}
