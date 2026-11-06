/**
 * A minimal Slack for the e2e runs (W6), in the spirit of the server's `FakeSlack`
 * (server/src/test/java/.../support/FakeSlack.java), under `/slack/` on the stubs' port. The server reaches it as
 * Slack's Web API through `tinfirm.chat.endpoints.slack-api` (stack.ts).
 *
 * - **Workspaces by token.** A bot token `xoxb-e2e-<TAG>-<anything>` is the bot of the workspace `T<TAG>` ("E2E
 *   <TAG>"), bot user `UB<TAG>`; any other token is refused (`invalid_auth`). So a test picks a workspace by its
 *   token, and two projects never share one (a company has one system per workspace).
 * - **Users.** `users.info` answers any user id as a member of the bot's workspace, named as the control API set
 *   (`POST /slack/users`), else "Slack user <id>".
 * - **Channels.** `users.conversations` answers the bot's channels in every workspace: #general and #support,
 *   public, and #e2e-private, private; asked for a user, none (so a private channel is listed to nobody).
 * - **Posts.** `chat.postMessage`, `chat.update`, `chat.postEphemeral` and `reactions.add` answer `ok` and are
 *   recorded, for a test to read (`GET /slack/posts`): the sign-in link the bot sends, "Linked to …".
 *
 * Deliveries go the other way: `deliverSlackEvent` signs an Events API envelope as Slack does and posts it to the
 * server, from the spec. TEST FIXTURE ONLY: the tokens and secrets are test values.
 */
import { createHmac } from 'node:crypto';
import type { ServerResponse } from 'node:http';
import { bearer, type Request, sendJson } from './http-util.ts';

export interface SlackPost {
  at: string;
  method: string;
  channel: string | null;
  text: string;
  /** The whole body, blocks and buttons included. */
  body: Record<string, unknown>;
}

export interface SlackWorkspace {
  teamId: string;
  teamName: string;
  botUserId: string;
  botToken: string;
}

/** The workspace a test's tag names, with its bot token (`xoxb-e2e-<TAG>-…`). */
export function slackWorkspace(tag: string): SlackWorkspace {
  const id = tag.toUpperCase().replace(/[^A-Z0-9]/g, '');
  return {
    teamId: `T${id}`,
    teamName: `E2E ${id}`,
    botUserId: `UB${id}`,
    botToken: `xoxb-e2e-${id}-bot`,
  };
}

function workspaceOf(token: string | null): SlackWorkspace | null {
  const tag = token ? /^xoxb-e2e-([A-Z0-9]+)-/.exec(token)?.[1] : undefined;
  return tag ? slackWorkspace(tag) : null;
}

/** The channels every workspace's bot is in; each id ends with the workspace's tag. */
export const SLACK_CHANNELS = [
  { id: 'CGENERAL', name: 'general', is_private: false },
  { id: 'CSUPPORT', name: 'support', is_private: false },
  { id: 'CPRIVATE', name: 'e2e-private', is_private: true },
] as const;

export class SlackStub {
  private readonly posts: SlackPost[] = [];
  private readonly names = new Map<string, string>();
  private nextTs = 1;

  isPath(path: string): boolean {
    return path.startsWith('/slack/');
  }

  setUser(id: string, name: string): void {
    this.names.set(id, name);
  }

  /** The bot's posts, oldest first; to one channel (a direct message is `D<user>`) when given. */
  postsTo(channel?: string): SlackPost[] {
    return this.posts.filter((post) => !channel || post.channel === channel);
  }

  clear(): void {
    this.posts.length = 0;
  }

  handle(request: Request, response: ServerResponse): void {
    const method = /^\/slack\/api\/([a-zA-Z.]+)$/.exec(request.path)?.[1];
    if (!method) return sendJson(response, 404, { ok: false, error: 'unknown_method' });
    const workspace = workspaceOf(bearer(request.headers['authorization']));
    if (!workspace) return sendJson(response, 200, { ok: false, error: 'invalid_auth' });
    const body = this.body(request);
    switch (method) {
      case 'auth.test':
        return sendJson(response, 200, {
          ok: true,
          team_id: workspace.teamId,
          team: workspace.teamName,
          user_id: workspace.botUserId,
          bot_id: `B${workspace.botUserId.slice(1)}`,
        });
      case 'users.info': {
        const id = String(body['user'] ?? '');
        return sendJson(response, 200, {
          ok: true,
          user: {
            id,
            team_id: workspace.teamId,
            name: id.toLowerCase(),
            deleted: false,
            is_bot: false,
            is_restricted: false,
            is_ultra_restricted: false,
            is_stranger: false,
            profile: {
              display_name: this.names.get(id) ?? `Slack user ${id}`,
              real_name: this.names.get(id) ?? '',
            },
          },
        });
      }
      case 'users.conversations':
        return sendJson(response, 200, {
          ok: true,
          channels: body['user']
            ? []
            : SLACK_CHANNELS.map((channel) => ({
                ...channel,
                id: `${channel.id}${workspace.teamId.slice(1)}`,
                is_archived: false,
                is_ext_shared: false,
              })),
          response_metadata: { next_cursor: '' },
        });
      case 'conversations.open':
        return sendJson(response, 200, { ok: true, channel: { id: `D${String(body['users'])}` } });
      case 'chat.postMessage':
      case 'chat.update':
      case 'chat.postEphemeral':
      case 'reactions.add': {
        this.posts.push({
          at: new Date().toISOString(),
          method,
          channel: typeof body['channel'] === 'string' ? body['channel'] : null,
          text: typeof body['text'] === 'string' ? body['text'] : '',
          body,
        });
        if (this.posts.length > 2000) this.posts.splice(0, this.posts.length - 2000);
        const ts = `1800000000.${String(this.nextTs++).padStart(6, '0')}`;
        return sendJson(response, 200, { ok: true, ts, channel: body['channel'] });
      }
      default:
        return sendJson(response, 200, { ok: false, error: 'unknown_method' });
    }
  }

  private body(request: Request): Record<string, unknown> {
    const type = String(request.headers['content-type'] ?? '');
    if (type.includes('application/json')) {
      try {
        return request.json<Record<string, unknown>>();
      } catch {
        return {};
      }
    }
    return Object.fromEntries(new URLSearchParams(request.body));
  }
}

// ---- deliveries, from the spec ----------------------------------------------------------------------------------

let nextEvent = 1;

/** A direct message to the bot (`message.im`) from a user of the workspace, as an Events API envelope. */
export function slackDirectMessage(
  workspace: SlackWorkspace,
  user: string,
  text: string,
): Record<string, unknown> {
  const ts = `${Math.floor(Date.now() / 1000)}.${String(nextEvent).padStart(6, '0')}`;
  return {
    token: 'unused',
    team_id: workspace.teamId,
    api_app_id: `A${workspace.teamId.slice(1)}`,
    type: 'event_callback',
    event_id: `Ev${Date.now().toString(36)}${nextEvent++}`,
    event_time: Math.floor(Date.now() / 1000),
    event: {
      type: 'message',
      channel: `D${user}`,
      channel_type: 'im',
      user,
      text,
      ts,
      event_ts: ts,
    },
  };
}

/** Posts an envelope to the system's Events API endpoint, signed now as Slack signs: `v0:{ts}:{body}`. */
export async function deliverSlackEvent(
  serverUrl: string,
  systemId: string,
  signingSecret: string,
  envelope: Record<string, unknown>,
): Promise<number> {
  const body = JSON.stringify(envelope);
  const timestamp = String(Math.floor(Date.now() / 1000));
  const signature = `v0=${createHmac('sha256', signingSecret).update(`v0:${timestamp}:${body}`).digest('hex')}`;
  const response = await fetch(`${serverUrl}/api/chat/slack/${systemId}/events`, {
    method: 'POST',
    headers: {
      'content-type': 'application/json',
      'x-slack-request-timestamp': timestamp,
      'x-slack-signature': signature,
    },
    body,
  });
  return response.status;
}
