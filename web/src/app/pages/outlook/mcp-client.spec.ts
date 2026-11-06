import { answerOf, isMcpRetryable, McpClient, McpToolError, toolErrorMessage } from './mcp-client';
import { OutlookAuth, SignedOutError } from './outlook-auth';

function answer(body: unknown, status = 200, type = 'application/json'): Response {
  return new Response(typeof body === 'string' ? body : JSON.stringify(body), {
    status,
    headers: { 'Content-Type': type },
  });
}

function authWith(tokens: string[]): OutlookAuth {
  const auth = new OutlookAuth('c1', 'https://tinfirm.example', null);
  let next = 0;
  vi.spyOn(auth, 'token').mockImplementation(async () => tokens[0]);
  vi.spyOn(auth, 'refresh').mockImplementation(
    async () => tokens[Math.min(++next, tokens.length - 1)],
  );
  return auth;
}

describe('the add-in’s calls of the MCP endpoint', () => {
  it('reads a JSON answer and the last data of an event stream', () => {
    expect(
      answerOf('application/json', '{"result":{"structuredContent":{"a":1}}}').result
        ?.structuredContent,
    ).toEqual({ a: 1 });
    const stream = 'event: message\ndata: {"result":{"structuredContent":{"b":2}}}\n\n';
    expect(answerOf('text/event-stream', stream).result?.structuredContent).toEqual({ b: 2 });
  });

  it('calls a tool with the bearer and answers its structured content', async () => {
    const fetchFn = vi.fn(async () =>
      answer({ jsonrpc: '2.0', id: 1, result: { structuredContent: { items: [] } } }),
    );
    const client = new McpClient('c1', authWith(['t1']), fetchFn as unknown as typeof fetch);
    expect(await client.listAgents()).toEqual({ items: [] });
    const [url, init] = fetchFn.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toBe('/api/companies/c1/mcp');
    expect((init.headers as Record<string, string>)['Authorization']).toBe('Bearer t1');
    expect(JSON.parse(String(init.body))).toMatchObject({
      method: 'tools/call',
      params: { name: 'list_agents', arguments: { usable: true } },
    });
  });

  it('refreshes once on a 401, then signs out', async () => {
    const fetchFn = vi
      .fn()
      .mockResolvedValueOnce(answer('', 401))
      .mockResolvedValueOnce(answer({ result: { structuredContent: { id: 'conv' } } }));
    const client = new McpClient(
      'c1',
      authWith(['old', 'new']),
      fetchFn as unknown as typeof fetch,
    );
    expect(await client.getConversation('conv')).toEqual({ id: 'conv' });
    expect(
      (
        (fetchFn.mock.calls[1] as unknown as [string, RequestInit])[1].headers as Record<
          string,
          string
        >
      )['Authorization'],
    ).toBe('Bearer new');

    const refused = new McpClient('c1', authWith(['old', 'new']), (async () =>
      answer('', 401)) as unknown as typeof fetch);
    await expect(refused.getConversation('conv')).rejects.toBeInstanceOf(SignedOutError);
  });

  it('turns a tool’s error and a JSON-RPC error into words', async () => {
    const limited = new McpClient('c1', authWith(['t']), (async () =>
      answer({
        result: {
          isError: true,
          structuredContent: { code: 'RATE_LIMITED', detail: 'Too many', retryAfter: 600 },
        },
      })) as unknown as typeof fetch);
    const error = await limited.startConversation('dev', 'hi', 'r1').catch((e: unknown) => e);
    expect(error).toBeInstanceOf(McpToolError);
    expect(toolErrorMessage(error)).toContain('10 minutes');

    const scope = new McpClient('c1', authWith(['t']), (async () =>
      answer(
        { error: { code: -32003, message: 'scope', data: { code: 'INSUFFICIENT_SCOPE' } } },
        403,
      )) as unknown as typeof fetch);
    expect(toolErrorMessage(await scope.listAgents().catch((e: unknown) => e))).toContain(
      'Read and write',
    );

    const edition = new McpClient('c1', authWith(['t']), (async () =>
      answer(
        {
          error: {
            code: -32600,
            message: 'The Outlook add-in needs the PRO edition',
            data: {
              code: 'EDITION_REQUIRED',
              details: { feature: 'OUTLOOK_ADDIN', edition: 'PRO' },
            },
          },
        },
        403,
      )) as unknown as typeof fetch);
    expect(toolErrorMessage(await edition.listAgents().catch((e: unknown) => e))).toBe(
      'The Outlook add-in needs a Pro licence. An instance admin enters a licence key under Licence.',
    );

    const origin = new McpClient('c1', authWith(['t']), (async () =>
      answer({ title: 'Forbidden' }, 403, 'application/problem+json')) as unknown as typeof fetch);
    expect(await origin.listAgents().catch((e: unknown) => (e as McpToolError).code)).toBe(
      'HTTP_403',
    );
  });

  it('holds a file for a start, and names the held files when it starts', async () => {
    const fetchFn = vi
      .fn()
      .mockResolvedValueOnce(answer({ id: 'f1', fileName: 'a.txt', sizeBytes: 2 }, 201))
      .mockResolvedValueOnce(
        new Response(JSON.stringify({ code: 'CREDENTIAL_FOUND', detail: 'A token' }), {
          status: 422,
          headers: { 'Content-Type': 'application/problem+json' },
        }),
      )
      .mockResolvedValueOnce(answer({ result: { structuredContent: { id: 'conv' } } }));
    const client = new McpClient('c1', authWith(['t']), fetchFn as unknown as typeof fetch);

    expect(await client.holdFile('a.txt', 'text/plain', new Blob(['hi']))).toMatchObject({
      id: 'f1',
    });
    const [url, init] = fetchFn.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toBe('/api/companies/c1/mcp/files');
    expect((init.headers as Record<string, string>)['Authorization']).toBe('Bearer t');
    const sent = (init.body as FormData).get('file') as File;
    expect(sent.name).toBe('a.txt');
    expect(await sent.text()).toBe('hi');

    const refused = await client
      .holdFile('b.txt', 'text/plain', new Blob(['x']))
      .catch((e: unknown) => e);
    expect(refused).toMatchObject({ code: 'CREDENTIAL_FOUND', detail: 'A token' });

    await client.startConversation('dev', 'hi', 'r1', ['f1']);
    expect(
      JSON.parse(String((fetchFn.mock.calls[2] as unknown as [string, RequestInit])[1].body)),
    ).toMatchObject({ params: { arguments: { fileIds: ['f1'] } } });
  });

  it('says which failures may be sent again with the same key: those on the way', () => {
    expect(isMcpRetryable(new TypeError('Failed to fetch'))).toBe(true);
    expect(isMcpRetryable(new McpToolError('HTTP_408', 'Tinfirm answered 408.'))).toBe(true);
    expect(isMcpRetryable(new McpToolError('HTTP_503', 'Tinfirm answered 503.'))).toBe(true);
    expect(isMcpRetryable(new McpToolError('HTTP_400', 'Tinfirm answered 400.'))).toBe(false);
    expect(isMcpRetryable(new McpToolError('RATE_LIMITED', 'Too many'))).toBe(false);
    expect(isMcpRetryable(new SignedOutError())).toBe(false);
    expect(isMcpRetryable(new Error('boom'))).toBe(false);
  });
});
