/**
 * A stub of a company's MCP server for the e2e runs, in the spirit of the server's `McpStub`
 * (server/src/test/java/.../support/McpStub.java): MCP Streamable HTTP with JSON answers, on the stubs' port under
 * `/mcp` (any path below it is an endpoint of its own, so a spec may register a server of its own at
 * `/mcp/<anything>`). It needs no token, so a system on it reads its tools without an account, and offers no OAuth
 * (its discovery finds nothing).
 *
 * It serves `initialize`, `notifications/initialized`, `tools/list` with the catalogue below (one page) and
 * `tools/call` with a text result that names the tool. The catalogue covers the effects an admin confirms (the web
 * plan §5, Systems): a tool whose hints say it only reads, one whose hints say it writes, and one without hints.
 * TEST FIXTURE ONLY.
 */
import type { ServerResponse } from 'node:http';
import { type Request, sendJson } from './http-util.ts';

/** The tools every endpoint lists, as MCP's `tools/list` sends them. */
export const MCP_TOOLS = [
  {
    name: 'search_notes',
    title: 'Search the notes',
    description: 'Finds notes whose text matches the query.',
    inputSchema: {
      type: 'object',
      properties: { query: { type: 'string' } },
      required: ['query'],
    },
    annotations: { readOnlyHint: true },
  },
  {
    name: 'create_note',
    title: 'Create a note',
    description: 'Writes a new note with the given text.',
    inputSchema: {
      type: 'object',
      properties: { text: { type: 'string' } },
      required: ['text'],
    },
    annotations: { readOnlyHint: false, destructiveHint: false },
  },
  {
    name: 'summarise_note',
    description: 'Summarises a note by its id.',
    inputSchema: {
      type: 'object',
      properties: { id: { type: 'string' } },
      required: ['id'],
    },
  },
] as const;

/** Whether the request is for the MCP stub. */
export function isMcpPath(path: string): boolean {
  return path === '/mcp' || path.startsWith('/mcp/');
}

/** Answers one request to an MCP endpoint of the stub. */
export function handleMcp(request: Request, response: ServerResponse): void {
  if (request.method === 'DELETE') {
    response.writeHead(204);
    response.end();
    return;
  }
  if (request.method !== 'POST') {
    response.writeHead(405, { allow: 'POST, DELETE' });
    response.end();
    return;
  }
  let message: { id?: number | string; method?: string; params?: { name?: string } };
  try {
    message = request.json();
  } catch {
    return sendJson(response, 400, {
      jsonrpc: '2.0',
      id: null,
      error: { code: -32700, message: 'Parse error' },
    });
  }
  const answer = (result: unknown) =>
    sendJson(response, 200, { jsonrpc: '2.0', id: message.id, result });
  switch (message.method) {
    case 'initialize':
      response.setHeader('mcp-session-id', `e2e-${Date.now()}`);
      return answer({
        protocolVersion: '2025-06-18',
        capabilities: { tools: {} },
        serverInfo: { name: 'e2e-notes', version: '1' },
      });
    case 'notifications/initialized':
      response.writeHead(202);
      response.end();
      return;
    case 'tools/list':
      return answer({ tools: MCP_TOOLS });
    case 'tools/call':
      return answer({
        content: [{ type: 'text', text: `called ${message.params?.name ?? 'nothing'}` }],
        isError: false,
      });
    default:
      return sendJson(response, 200, {
        jsonrpc: '2.0',
        id: message.id ?? null,
        error: { code: -32601, message: `Method not found: ${message.method}` },
      });
  }
}
