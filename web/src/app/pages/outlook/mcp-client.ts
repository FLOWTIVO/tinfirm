import type { AgentPage, Conversation } from '../../api/generated/models';
import { editionRequiredMessage } from '../../shared/licence-labels';
import { SignedOutError, type OutlookAuth } from './outlook-auth';

/** A tool's error: `{CODE}: {detail}` (CONVENTIONS §14.11, "Errors"). */
export class McpToolError extends Error {
  constructor(
    readonly code: string,
    readonly detail: string,
    readonly retryAfter: number | null = null,
  ) {
    super(detail || code);
    this.name = 'McpToolError';
  }
}

interface JsonRpcAnswer {
  result?: {
    isError?: boolean;
    structuredContent?: unknown;
    content?: { type: string; text?: string }[];
  };
  error?: { code: number; message: string; data?: { code?: string } };
}

/** The JSON-RPC answer of a response: a JSON body, or the last `data:` of an event stream. */
export function answerOf(contentType: string | null, body: string): JsonRpcAnswer {
  if ((contentType ?? '').includes('text/event-stream')) {
    const data = body
      .split(/\r?\n/)
      .filter((line) => line.startsWith('data:'))
      .map((line) => line.slice(5).trim())
      .filter((line) => line.length > 0);
    return JSON.parse(data[data.length - 1] ?? '{}') as JsonRpcAnswer;
  }
  return JSON.parse(body) as JsonRpcAnswer;
}

/** A file the endpoint holds for a start (ADR-0084): `fileIds` of `start_conversation` names it. */
export interface HeldFile {
  id: string;
  fileName: string;
  contentType: string;
  sizeBytes: number;
  expiresAt: string;
}

/**
 * The company's MCP endpoint, as the Outlook add-in calls it (ADR-0074): one JSON-RPC `tools/call`
 * per request, with the person's bearer token, from Tinfirm's own origin. A tool's
 * `structuredContent` is the REST API's DTO of the same operation, so the generated models type it.
 * The email's attachments go to its `/files` first (ADR-0084), one multipart file each.
 */
export class McpClient {
  private id = 0;

  constructor(
    private readonly companyId: string,
    private readonly auth: OutlookAuth,
    private readonly fetchFn: typeof fetch = (input, init) => fetch(input, init),
  ) {}

  listAgents(): Promise<AgentPage> {
    return this.call<AgentPage>('list_agents', { usable: true, limit: 200 });
  }

  getConversation(conversation: string): Promise<Conversation> {
    return this.call<Conversation>('get_conversation', { conversation, messages: 1 });
  }

  startConversation(
    agent: string,
    message: string,
    requestId: string,
    fileIds: readonly string[] = [],
  ): Promise<Conversation> {
    return this.call<Conversation>('start_conversation', {
      agent,
      message,
      requestId,
      ...(fileIds.length ? { fileIds } : {}),
    });
  }

  /** Holds a file for the person's next start, for an hour. Refusals are {@link McpToolError}s. */
  async holdFile(name: string, contentType: string, content: Blob): Promise<HeldFile> {
    const send = (token: string) => {
      const form = new FormData();
      form.append('file', new File([content], name, { type: contentType }));
      return this.fetchFn(`/api/companies/${this.companyId}/mcp/files`, {
        method: 'POST',
        headers: { Authorization: `Bearer ${token}`, Accept: 'application/json' },
        body: form,
      });
    };
    let response = await send(await this.auth.token());
    if (response.status === 401) {
      response = await send(await this.auth.refresh());
      if (response.status === 401) {
        this.auth.forget();
        throw new SignedOutError();
      }
    }
    const text = await response.text();
    let body: Record<string, unknown> = {};
    try {
      body = JSON.parse(text) as Record<string, unknown>;
    } catch {
      // Not JSON: the status says it.
    }
    if (!response.ok) {
      const retryAfter = Number(response.headers.get('Retry-After'));
      throw new McpToolError(
        typeof body['code'] === 'string' ? body['code'] : `HTTP_${response.status}`,
        typeof body['detail'] === 'string'
          ? body['detail']
          : `Tinfirm answered ${response.status}.`,
        Number.isFinite(retryAfter) && retryAfter > 0 ? retryAfter : null,
      );
    }
    return body as unknown as HeldFile;
  }

  /** Answers the conversation as it is now, the message among its messages. */
  sendMessage(
    conversation: string,
    body: string,
    answersOpenQuestions: boolean,
    requestId: string,
  ): Promise<Conversation> {
    return this.call<Conversation>('send_message', {
      conversation,
      body,
      answersOpenQuestions,
      requestId,
    });
  }

  async call<T>(name: string, args: Record<string, unknown>): Promise<T> {
    let response = await this.post(name, args, await this.auth.token());
    if (response.status === 401) {
      // An access token the endpoint no longer takes: refresh once, then sign in again.
      response = await this.post(name, args, await this.auth.refresh());
      if (response.status === 401) {
        this.auth.forget();
        throw new SignedOutError();
      }
    }
    const text = await response.text();
    let answer: JsonRpcAnswer;
    try {
      answer = answerOf(response.headers.get('Content-Type'), text);
    } catch {
      throw new McpToolError(`HTTP_${response.status}`, `Tinfirm answered ${response.status}.`);
    }
    if (answer.error) {
      throw new McpToolError(answer.error.data?.code ?? 'ERROR', answer.error.message);
    }
    if (!response.ok) {
      throw new McpToolError(`HTTP_${response.status}`, `Tinfirm answered ${response.status}.`);
    }
    const result = answer.result;
    if (!result) throw new McpToolError('ERROR', 'Tinfirm gave no answer.');
    if (result.isError) {
      const structured = (result.structuredContent ?? {}) as {
        code?: string;
        detail?: string;
        retryAfter?: number;
      };
      throw new McpToolError(
        structured.code ?? 'ERROR',
        structured.detail ?? result.content?.[0]?.text ?? '',
        structured.retryAfter ?? null,
      );
    }
    return result.structuredContent as T;
  }

  private post(name: string, args: Record<string, unknown>, token: string): Promise<Response> {
    return this.fetchFn(`/api/companies/${this.companyId}/mcp`, {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${token}`,
        'Content-Type': 'application/json',
        Accept: 'application/json, text/event-stream',
      },
      body: JSON.stringify({
        jsonrpc: '2.0',
        id: ++this.id,
        method: 'tools/call',
        params: { name, arguments: args },
      }),
    });
  }
}

/**
 * A failure on the way, where the same request may be sent again with its key (CONVENTIONS
 * §15.14): no answer (`fetch` failed), a timeout or a 5xx of the endpoint, never a tool's refusal.
 */
export function isMcpRetryable(error: unknown): boolean {
  if (error instanceof TypeError) return true;
  return error instanceof McpToolError && /^HTTP_(408|5\d\d)$/.test(error.code);
}

/** What a tool's error means to the person in the pane. */
export function toolErrorMessage(error: unknown): string {
  if (error instanceof SignedOutError) return error.message;
  if (error instanceof McpToolError) {
    switch (error.code) {
      case 'RATE_LIMITED':
        return error.retryAfter
          ? `You started many conversations from AI clients this hour. Try again in ${Math.ceil(error.retryAfter / 60)} minutes.`
          : 'Too many requests. Try again in a minute.';
      case 'INSUFFICIENT_SCOPE':
        return 'This sign-in may only read. Sign out, sign in again and choose “Read and write”.';
      case 'AGENT_NOT_USABLE':
        return 'You may not use this agent, or it is paused.';
      case 'MODEL_ACCESS_UNAVAILABLE':
        return 'You have no model access to pay for this. Ask your administrator.';
      case 'NO_HOST_FOR_CONVERSATION':
        return 'No runner host can run this agent for you now.';
      case 'CONVERSATION_ENDED':
        return 'The conversation has ended. Start a new one.';
      case 'NOT_FOUND':
        return 'Tinfirm does not know this, or you no longer see it.';
      case 'EDITION_REQUIRED':
        // The add-in is Pro's and Enterprise's (ADR-0077): the server refuses its pane under Community.
        return editionRequiredMessage('OUTLOOK_ADDIN', undefined);
      default:
        return error.detail || 'Tinfirm refused the request.';
    }
  }
  return error instanceof Error && error.message ? error.message : 'Something went wrong.';
}
