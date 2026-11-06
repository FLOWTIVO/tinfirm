import { TestBed } from '@angular/core/testing';
import type { Conversation } from '../../api/generated/models';
import { aConversation, aMessage, anAgentSummary, aRun } from '../../testing/fixtures';
import { appears, settle, settleUntil } from '../../testing/test-helpers';
import { emailKey } from './email-conversations';
import { emailRequest } from './email-request';
import {
  OFFICE_JS_URL,
  resetOfficeForTests,
  type MessageRead,
  type OfficeApi,
  type RoamingSettings,
} from './office';
import { OUTLOOK_POLL_TIMING, TaskPane } from './task-pane';

const COMPANY = 'c1';

interface FakeOutlook {
  office: OfficeApi;
  item: MessageRead & { displayReplyForm: ReturnType<typeof vi.fn> };
  /** Selects another email, as a pinned pane sees it. */
  select(item: Partial<MessageRead>): void;
}

function fakeOutlook(
  host: string | null = 'Outlook',
  roaming: RoamingSettings | null = null,
  internetMessageId?: string,
): FakeOutlook {
  const item = {
    itemId: 'item-1',
    internetMessageId,
    subject: 'Checkout fails',
    from: { displayName: 'Ann Customer', emailAddress: 'ann@example.com' },
    to: [],
    cc: [],
    dateTimeCreated: new Date('2026-10-18T09:30:00Z'),
    body: {
      getAsync: (_type: string, callback: (r: { status: string; value: string }) => void) =>
        callback({ status: 'succeeded', value: 'It answers 500.' }),
    },
    displayReplyForm: vi.fn(),
  };
  let changed: () => void = () => undefined;
  const mailbox = {
    item: item as MessageRead | null,
    addHandlerAsync: (_type: string, handler: () => void) => {
      changed = handler;
    },
  };
  const office: OfficeApi = {
    onReady: () => Promise.resolve({ host, platform: host ? 'PC' : null }),
    // Outside Outlook, Office.js offers no mailbox.
    context: host
      ? {
          mailbox,
          ...(roaming ? { roamingSettings: roaming } : {}),
          ui: { displayDialogAsync: () => undefined, messageParent: () => undefined },
        }
      : {},
    AsyncResultStatus: { Succeeded: 'succeeded', Failed: 'failed' },
    CoercionType: { Text: 'text' },
    EventType: {
      ItemChanged: 'olkItemSelectedChanged',
      DialogMessageReceived: 'dialogMessageReceived',
      DialogEventReceived: 'dialogEventReceived',
    },
  };
  return {
    office,
    item,
    select: (next) => {
      mailbox.item = { ...item, ...next };
      changed();
    },
  };
}

/** The mailbox's roaming settings, which every Outlook of it shares. */
function fakeRoaming(): RoamingSettings & { values: Map<string, unknown> } {
  const values = new Map<string, unknown>();
  return {
    values,
    get: (name) => values.get(name),
    set: (name, value) => void values.set(name, structuredClone(value)),
    remove: (name) => void values.delete(name),
    saveAsync: vi.fn(),
  };
}

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}

/** The endpoint's `/files`: each file held, as `f-{name}`. */
async function holdAll(file: File): Promise<Response> {
  return new Response(
    JSON.stringify({
      id: `f-${file.name}`,
      fileName: file.name,
      contentType: file.type,
      sizeBytes: file.size,
      expiresAt: '2026-10-18T11:00:00Z',
    }),
    { status: 201, headers: { 'Content-Type': 'application/json' } },
  );
}

/** Tinfirm as the pane sees it: the token endpoint, the MCP endpoint's tools and its `/files`. */
function fakeTinfirm(
  tools: Record<string, (args: Record<string, unknown>) => unknown>,
  revoked: () => Response = () => json({}),
  files: (file: File) => Promise<Response> = holdAll,
) {
  return vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
    const url = String(input);
    if (url.endsWith('/oauth2/token')) {
      return json({ access_token: 'access', refresh_token: 'refresh-2', expires_in: 3600 });
    }
    if (url.endsWith('/oauth2/revoke')) {
      return revoked();
    }
    if (url.endsWith(`/api/companies/${COMPANY}/mcp/files`)) {
      return files((init?.body as FormData).get('file') as File);
    }
    const call = JSON.parse(String(init?.body)) as {
      params: { name: string; arguments: Record<string, unknown> };
    };
    const tool = tools[call.params.name];
    const result = await tool?.(call.params.arguments);
    // A tool may answer with the endpoint's own response: a 503 on the way, say.
    if (result instanceof Response) return result;
    return json({ jsonrpc: '2.0', id: 1, result: { structuredContent: result } });
  });
}

/** An email with one attachment, which Outlook gives the pane. */
function withAttachment(outlook: FakeOutlook): void {
  Object.assign(outlook.item, {
    attachments: [
      {
        id: 'a1',
        name: 'invoice.pdf',
        contentType: 'application/pdf',
        size: 8,
        attachmentType: 'file',
        isInline: false,
      },
    ],
    getAttachmentContentAsync: (
      _id: string,
      callback: (r: { status: string; value: { content: string; format: string } }) => void,
    ) => callback({ status: 'succeeded', value: { content: btoa('%PDF-1.4'), format: 'base64' } }),
  });
}

/** The pane's reads at a pace a test can wait for. */
function pollQuickly(): void {
  TestBed.configureTestingModule({
    providers: [{ provide: OUTLOOK_POLL_TIMING, useValue: { activeMs: 5, idleMs: 20 } }],
  });
}

/** The page as the browser reports it to the pane. */
function becomes(state: 'visible' | 'hidden'): void {
  Object.defineProperty(document, 'visibilityState', { value: state, configurable: true });
  document.dispatchEvent(new Event('visibilitychange'));
}

/** The email's conversation, whose turn waits for the person's approval in Tinfirm. */
function waitingOnPerson(): Conversation {
  return aConversation({ id: 'conv-1', waitingOn: 'PERSON', waitingReason: 'APPROVAL' });
}

/** The email's conversation after one turn: the pane's request and the agent's answer. */
function answered(id: string, answer: string, overrides: Partial<Conversation> = {}): Conversation {
  const result = {
    outcome: 'COMPLETED' as const,
    summary: answer,
    nextSteps: [],
    openQuestions: [],
  };
  return aConversation({
    id,
    turnStatus: 'SUCCEEDED',
    waitingOn: 'NONE',
    mayPost: true,
    result,
    turns: [aRun({ id: `${id}-turn-1`, status: 'SUCCEEDED', summary: answer, result })],
    messages: [
      aMessage({
        id: `${id}-m1`,
        body: emailRequest('DRAFT_REPLY', {
          subject: 'Checkout fails',
          from: null,
          to: [],
          cc: [],
          date: null,
          body: 'It answers 500.',
        }),
      }),
      aMessage({ id: `${id}-m2`, kind: 'AGENT', runId: `${id}-turn-1`, body: answer }),
    ],
    ...overrides,
  });
}

/** The conversation once it took the person's message, which waits for the next turn. */
function taken(conversation: Conversation, body: string): Conversation {
  return {
    ...conversation,
    turnStatus: 'QUEUED',
    waitingOn: 'RUNNER',
    result: null,
    messages: [
      ...conversation.messages,
      aMessage({ id: `${conversation.id}-m3`, body, waiting: true }),
    ],
  };
}

/** The texts of the pane's thread, oldest first. */
function threadOf(page: HTMLElement): string[] {
  return [...page.querySelectorAll('[data-testid="thread-message"]')].map(
    (m) => m.textContent?.replace(/\s+/g, ' ').trim() ?? '',
  );
}

/** A conversation remembered for the first email, and the person signed in. */
function rememberConversation(): void {
  localStorage.setItem(`tinfirm.outlook.${COMPANY}`, '{"refreshToken":"refresh-1"}');
  localStorage.setItem(
    `tinfirm.outlook.${COMPANY}.conversations`,
    JSON.stringify({
      'item-1': {
        conversationId: 'conv-1',
        agentName: 'Developer',
        action: 'INVESTIGATE',
        startedAt: '2026-10-18T10:00:00Z',
      },
    }),
  );
}

/**
 * Renders the pane. Its start-up awaits the email's key, a digest of `crypto.subtle` that takes
 * more turns on a loaded machine than `settle(n)` can count: a test awaits the element it needs
 * with `appears()` rather than querying the page at once.
 */
async function render(outlook: FakeOutlook): Promise<HTMLElement> {
  window.Office = outlook.office;
  const fixture = TestBed.createComponent(TaskPane);
  fixture.componentRef.setInput('companyId', COMPANY);
  fixture.detectChanges();
  await settle();
  return fixture.nativeElement as HTMLElement;
}

describe('the Outlook add-in’s task pane', () => {
  beforeEach(() => {
    resetOfficeForTests();
    localStorage.clear();
  });

  afterEach(() => {
    delete window.Office;
    vi.restoreAllMocks();
    Reflect.deleteProperty(document, 'visibilityState');
  });

  it('asks to be opened from Outlook on any other page', async () => {
    const page = await render(fakeOutlook(null));
    expect((await appears(page, '[data-testid="not-outlook"]')).textContent).toContain(
      'Open this from Outlook',
    );
  });

  it('says when Office.js did not start, rather than that this is not Outlook', async () => {
    // No Office yet: the pane loads Office.js, which the CSP or the network refuses.
    const fixture = TestBed.createComponent(TaskPane);
    fixture.componentRef.setInput('companyId', COMPANY);
    fixture.detectChanges();
    const script = await appears<HTMLScriptElement>(
      document.head,
      `script[src="${OFFICE_JS_URL}"]`,
    );
    script.dispatchEvent(new Event('error'));

    const page = fixture.nativeElement as HTMLElement;
    expect((await appears(page, '[data-testid="no-office"]')).textContent).toContain(
      'ajax.aspnetcdn.com',
    );
    expect(page.querySelector('[data-testid="not-outlook"]')).toBeNull();
    script.remove();
  });

  it('asks the person to sign in once on this computer', async () => {
    const page = await render(fakeOutlook());
    expect((await appears(page, '[data-testid="sign-in"]')).textContent).toContain(
      'Sign in to Tinfirm',
    );
  });

  it('starts a conversation about the open email with the chosen agent', async () => {
    localStorage.setItem(`tinfirm.outlook.${COMPANY}`, '{"refreshToken":"refresh-1"}');
    let started: Record<string, unknown> | null = null;
    fakeTinfirm({
      list_agents: () => ({
        items: [
          anAgentSummary({ id: 'a-paused', name: 'Paused', status: 'PAUSED' }),
          anAgentSummary({ id: 'a-dev', name: 'Developer', handle: 'dev', usableByMe: true }),
        ],
      }),
      start_conversation: (args) => {
        started = args;
        return aConversation({
          id: 'conv-1',
          agentName: 'Developer',
          turnStatus: 'RUNNING',
          waitingOn: 'AGENT',
        });
      },
    });
    const outlook = fakeOutlook();
    const page = await render(outlook);
    expect((await appears(page, '[data-testid="email-subject"]')).textContent).toContain(
      'Checkout fails',
    );
    await appears(page, '#pane-agent option');
    const options = [...page.querySelectorAll('#pane-agent option')].map((o) =>
      o.textContent?.trim(),
    );
    expect(options).toEqual(['Developer · @dev']);

    (page.querySelector('[data-action="INVESTIGATE"]') as HTMLButtonElement).click();
    const status = await appears(page, '[data-testid="conversation-status"]');

    expect(started).toMatchObject({ agent: 'a-dev' });
    expect(String(started!['message'])).toContain('Do not change any code');
    expect(String(started!['message'])).toContain('It answers 500.');
    expect(page.querySelector('[data-testid="conversation"]')?.textContent).toContain(
      'Investigate · Developer',
    );
    expect(status.textContent).toContain('Working on it');
    const key = (await emailKey({ itemId: 'item-1' }))!;
    expect(
      JSON.parse(localStorage.getItem(`tinfirm.outlook.${COMPANY}.conversations`) ?? '{}')[key],
    ).toMatchObject({ conversationId: 'conv-1', action: 'INVESTIGATE' });
  });

  it('sends the email’s attachments as files of the conversation, and names those left out', async () => {
    localStorage.setItem(`tinfirm.outlook.${COMPANY}`, '{"refreshToken":"refresh-1"}');
    let started: Record<string, unknown> | null = null;
    const uploaded: Record<string, string> = {};
    fakeTinfirm(
      {
        list_agents: () => ({
          items: [anAgentSummary({ id: 'a-dev', name: 'Developer', usableByMe: true })],
        }),
        start_conversation: (args) => {
          started = args;
          return aConversation({ id: 'conv-1', agentName: 'Developer', turnStatus: 'RUNNING' });
        },
      },
      undefined,
      async (file) => {
        uploaded[file.name] = await file.text();
        if (file.name === 'keys.env') {
          return new Response(JSON.stringify({ code: 'CREDENTIAL_FOUND', detail: 'A token' }), {
            status: 422,
            headers: { 'Content-Type': 'application/problem+json' },
          });
        }
        return holdAll(file);
      },
    );
    const outlook = fakeOutlook();
    const contents: Record<string, { content: string; format: string }> = {
      a1: { content: btoa('%PDF-1.4'), format: 'base64' },
      a3: { content: btoa('TOKEN=ghp_x'), format: 'base64' },
      a4: { content: 'Subject: Order', format: 'eml' },
    };
    Object.assign(outlook.item, {
      attachments: [
        {
          id: 'a1',
          name: 'invoice.pdf',
          contentType: 'application/pdf',
          size: 8,
          attachmentType: 'file',
          isInline: false,
        },
        {
          id: 'a2',
          name: 'logo.png',
          contentType: 'image/png',
          size: 4,
          attachmentType: 'file',
          isInline: true,
        },
        {
          id: 'a3',
          name: 'keys.env',
          contentType: 'text/plain',
          size: 11,
          attachmentType: 'file',
          isInline: false,
        },
        { id: 'a4', name: 'Re: Order', size: 14, attachmentType: 'item', isInline: false },
        { id: 'a5', name: 'Plan', size: 0, attachmentType: 'cloud', isInline: false },
      ],
      getAttachmentContentAsync: (
        id: string,
        callback: (r: { status: string; value: { content: string; format: string } }) => void,
      ) => callback({ status: 'succeeded', value: contents[id] }),
    });
    const page = await render(outlook);
    expect((await appears(page, '[data-testid="send-attachments"]')).textContent).toContain(
      'Send its 4 attachments too',
    );

    (page.querySelector('[data-action="SUMMARISE"]') as HTMLButtonElement).click();
    await appears(page, '[data-testid="conversation"]');

    expect(uploaded).toEqual({
      'invoice.pdf': '%PDF-1.4',
      'keys.env': 'TOKEN=ghp_x',
      'Re: Order.eml': 'Subject: Order',
    });
    expect(started).toMatchObject({ fileIds: ['f-invoice.pdf', 'f-Re: Order.eml'] });
    const message = String(started!['message']);
    expect(message).toContain(
      'Attachments (files of this conversation): invoice.pdf; Re: Order.eml\n',
    );
    expect(message).toContain(
      'Attachments left out: keys.env (it holds a credential); Plan (a link to a cloud file)\n',
    );
    expect(message).not.toContain('logo.png');
  });

  it('names the attachments the person chose not to send, and sends none', async () => {
    localStorage.setItem(`tinfirm.outlook.${COMPANY}`, '{"refreshToken":"refresh-1"}');
    let started: Record<string, unknown> | null = null;
    const files = vi.fn(holdAll);
    fakeTinfirm(
      {
        list_agents: () => ({
          items: [anAgentSummary({ id: 'a-dev', name: 'Developer', usableByMe: true })],
        }),
        start_conversation: (args) => {
          started = args;
          return aConversation({ id: 'conv-1', agentName: 'Developer', turnStatus: 'RUNNING' });
        },
      },
      undefined,
      files,
    );
    const outlook = fakeOutlook();
    Object.assign(outlook.item, {
      attachments: [
        { id: 'a1', name: 'invoice.pdf', size: 8, attachmentType: 'file', isInline: false },
      ],
      getAttachmentContentAsync: vi.fn(),
    });
    const page = await render(outlook);
    const box = await appears<HTMLInputElement>(page, '[data-testid="send-attachments"] input');
    expect(box.checked).toBe(true);
    box.click();
    await settle();

    (page.querySelector('[data-action="TRIAGE"]') as HTMLButtonElement).click();
    await appears(page, '[data-testid="conversation"]');

    expect(files).not.toHaveBeenCalled();
    expect(started).not.toHaveProperty('fileIds');
    expect(String(started!['message'])).toContain('Attachments left out: invoice.pdf (not sent)\n');
  });

  it('remembers a conversation in the mailbox, and shows it in another Outlook after the email moved', async () => {
    localStorage.setItem(`tinfirm.outlook.${COMPANY}`, '{"refreshToken":"refresh-1"}');
    const roaming = fakeRoaming();
    fakeTinfirm({
      list_agents: () => ({
        items: [anAgentSummary({ id: 'a-dev', name: 'Developer', usableByMe: true })],
      }),
      start_conversation: () =>
        aConversation({ id: 'conv-1', agentName: 'Developer', turnStatus: 'RUNNING' }),
      get_conversation: (args) => answered(String(args['conversation']), 'The cause is a timeout.'),
    });
    const page = await render(fakeOutlook('Outlook', roaming, '<m1@example.com>'));
    (await appears<HTMLButtonElement>(page, '[data-action="SUMMARISE"]')).click();
    await appears(page, '[data-testid="conversation"]');
    expect(roaming.saveAsync).toHaveBeenCalled();
    TestBed.resetTestingModule();

    // Another computer: its own storage is empty, and the email has another item id in Archive.
    localStorage.clear();
    localStorage.setItem(`tinfirm.outlook.${COMPANY}`, '{"refreshToken":"refresh-1"}');
    resetOfficeForTests();
    const elsewhere = fakeOutlook('Outlook', roaming, '<m1@example.com>');
    elsewhere.item.itemId = 'archive-7';
    const again = await render(elsewhere);

    await appears(again, '[data-testid="thread-message"]');
    expect(again.querySelector('[data-testid="conversation"]')?.textContent).toContain(
      'Summarise · Developer',
    );
    expect(threadOf(again).join(' ')).toContain('The cause is a timeout.');
  });

  it('shows the email’s whole conversation again, and offers an answer as the reply', async () => {
    localStorage.setItem(`tinfirm.outlook.${COMPANY}`, '{"refreshToken":"refresh-1"}');
    localStorage.setItem(
      `tinfirm.outlook.${COMPANY}.conversations`,
      JSON.stringify({
        'item-1': {
          conversationId: 'conv-1',
          agentName: 'Developer',
          action: 'DRAFT_REPLY',
          startedAt: '2026-10-18T10:00:00Z',
        },
      }),
    );
    fakeTinfirm({
      list_agents: () => ({ items: [] }),
      get_conversation: () => answered('conv-1', 'Dear Ann, **thank you**.'),
    });
    const outlook = fakeOutlook();
    const page = await render(outlook);

    const useAsReply = await appears<HTMLButtonElement>(page, '[data-testid="use-as-reply"]');
    const thread = threadOf(page);
    expect(thread).toHaveLength(2);
    expect(thread[0]).toContain('Draft a reply');
    expect(thread[0]).toContain('With this email’s text');
    expect(thread[0]).not.toContain('It answers 500.');
    expect(thread[1]).toContain('Dear Ann, thank you.');
    // "Done." says nothing the answer does not.
    expect(page.querySelector('[data-testid="conversation-status"]')).toBeNull();
    useAsReply.click();
    expect(outlook.item.displayReplyForm).toHaveBeenCalledWith({
      htmlBody: '<p>Dear Ann, <strong>thank you</strong>.</p>',
    });
    expect(page.querySelector('[data-testid="conversation"] a[href]')?.getAttribute('href')).toBe(
      `${location.origin}/c/${COMPANY}/conversations/conv-1`,
    );
  });

  it('builds the reply form from escaped text, never from the rendered Markdown (M10)', async () => {
    localStorage.setItem(`tinfirm.outlook.${COMPANY}`, '{"refreshToken":"refresh-1"}');
    localStorage.setItem(
      `tinfirm.outlook.${COMPANY}.conversations`,
      JSON.stringify({
        'item-1': {
          conversationId: 'conv-1',
          agentName: 'Developer',
          action: 'DRAFT_REPLY',
          startedAt: '2026-10-18T10:00:00Z',
        },
      }),
    );
    fakeTinfirm({
      list_agents: () => ({ items: [] }),
      get_conversation: () =>
        answered(
          'conv-1',
          'Hello <kbd> <img src="https://evil.test/p.png?d=\n\nSee [site](https://e.test).',
        ),
    });
    const outlook = fakeOutlook();
    const page = await render(outlook);

    (await appears<HTMLButtonElement>(page, '[data-testid="use-as-reply"]')).click();
    const body = (outlook.item.displayReplyForm.mock.calls[0][0] as { htmlBody: string }).htmlBody;
    expect(body).toContain('See site (https://e.test).');
    expect(body.replace(/<\/?(p|br|strong|em)>/g, '')).not.toContain('<');
    expect(body).not.toMatch(/<[^>]*\sclass=/i);
  });

  it('keeps a reply that lands after another email was selected out of that email’s pane', async () => {
    rememberConversation();
    // The second email has a conversation of its own.
    localStorage.setItem(
      `tinfirm.outlook.${COMPANY}.conversations`,
      JSON.stringify({
        ...JSON.parse(localStorage.getItem(`tinfirm.outlook.${COMPANY}.conversations`)!),
        'item-2': {
          conversationId: 'conv-2',
          agentName: 'Analyst',
          action: 'SUMMARISE',
          startedAt: '2026-10-18T11:00:00Z',
        },
      }),
    );
    let delivered: () => void = () => undefined;
    fakeTinfirm({
      list_agents: () => ({ items: [] }),
      get_conversation: (args) =>
        answered(
          String(args['conversation']),
          args['conversation'] === 'conv-1' ? 'About email A.' : 'About email B.',
        ),
      send_message: () =>
        new Promise<Conversation>(
          (resolve) => (delivered = () => resolve(answered('conv-1', 'About email A.'))),
        ),
    });
    const outlook = fakeOutlook();
    const page = await render(outlook);
    const textarea = await appears<HTMLTextAreaElement>(page, '#pane-message');
    textarea.value = 'And the fix?';
    textarea.dispatchEvent(new Event('input'));
    await settle();
    page.querySelector('[data-testid="conversation"] form')!.dispatchEvent(new Event('submit'));
    await settle();

    outlook.select({ itemId: 'item-2', subject: 'Invoice question' });
    await settleUntil(
      () => threadOf(page).join(' ').includes('About email B.'),
      'the second email’s conversation',
    );
    delivered();
    // The reply lands; the pane must not show it, so this waits a fixed number of turns.
    await settle(6);

    expect(page.querySelector('[data-testid="email-subject"]')?.textContent).toContain(
      'Invoice question',
    );
    expect(threadOf(page).join(' ')).toContain('About email B.');
    expect(threadOf(page).join(' ')).not.toContain('About email A.');
  });

  it('keeps the earlier answers in view while a follow-up runs', async () => {
    rememberConversation();
    let sent = false;
    const before = answered('conv-1', 'The cause is a missing null check.');
    const after = taken(before, 'And the fix?');
    fakeTinfirm({
      list_agents: () => ({ items: [] }),
      get_conversation: () => (sent ? after : before),
      send_message: () => {
        sent = true;
        return after;
      },
    });
    const page = await render(fakeOutlook());
    const textarea = await appears<HTMLTextAreaElement>(page, '#pane-message');
    textarea.value = 'And the fix?';
    textarea.dispatchEvent(new Event('input'));
    await settle();
    page.querySelector('[data-testid="conversation"] form')!.dispatchEvent(new Event('submit'));
    await appears(page, '[data-testid="message-waiting"]');

    const thread = threadOf(page);
    expect(thread).toHaveLength(3);
    expect(thread[1]).toContain('The cause is a missing null check.');
    expect(thread[2]).toContain('And the fix?');
    expect(thread[2]).toContain('Waits for the agent');
    expect(page.querySelector('[data-testid="conversation-status"]')?.textContent).toContain(
      'Waiting for a runner host',
    );
    // While the turn goes, the pane takes no other message.
    expect(page.querySelector('#pane-message')).toBeNull();
  });

  it('reads a conversation that waits for the person again, and shows the turn that resumed', async () => {
    rememberConversation();
    pollQuickly();
    // The conversation waits until the test resumes it: the idle reads before that all see it
    // waiting, whatever their pace.
    let resumed = false;
    let reads = 0;
    fakeTinfirm({
      list_agents: () => ({ items: [] }),
      get_conversation: () => {
        reads++;
        return resumed
          ? aConversation({ id: 'conv-1', turnStatus: 'RUNNING', waitingOn: 'AGENT' })
          : waitingOnPerson();
      },
    });
    const page = await render(fakeOutlook());
    const status = await appears(page, '[data-testid="conversation-status"]');
    expect(status.textContent).toContain('Decide it in Tinfirm');
    resumed = true;

    await settleUntil(
      () =>
        page
          .querySelector('[data-testid="conversation-status"]')
          ?.textContent?.includes('Working on it') ?? false,
      'the turn that resumed',
      200,
    );
    expect(reads).toBeGreaterThanOrEqual(2);
  });

  it('reads the conversation at once when the pane is shown again', async () => {
    rememberConversation();
    const reads = vi.fn(() => waitingOnPerson());
    fakeTinfirm({ list_agents: () => ({ items: [] }), get_conversation: reads });
    const page = await render(fakeOutlook());
    await appears(page, '[data-testid="conversation-status"]');
    expect(reads).toHaveBeenCalledTimes(1);

    becomes('hidden');
    becomes('visible');
    await settleUntil(() => reads.mock.calls.length === 2, 'a read on showing the pane');
  });

  it('reads a conversation that has ended once', async () => {
    rememberConversation();
    pollQuickly();
    const reads = vi.fn(() => aConversation({ id: 'conv-1', state: 'ENDED' }));
    fakeTinfirm({ list_agents: () => ({ items: [] }), get_conversation: reads });
    const page = await render(fakeOutlook());
    expect((await appears(page, '[data-testid="conversation-status"]')).textContent).toContain(
      'The conversation has ended.',
    );
    // Longer than the idle pace: a conversation that waits for the person would be read again.
    await new Promise((resolve) => setTimeout(resolve, 60));
    await settle();
    expect(reads).toHaveBeenCalledTimes(1);
  });

  it('sends a start that failed on the way again with its key and its held files, then starts afresh', async () => {
    localStorage.setItem(`tinfirm.outlook.${COMPANY}`, '{"refreshToken":"refresh-1"}');
    const starts: { requestId: unknown; fileIds: unknown }[] = [];
    const files = vi.fn(holdAll);
    fakeTinfirm(
      {
        list_agents: () => ({
          items: [anAgentSummary({ id: 'a-dev', name: 'Developer', usableByMe: true })],
        }),
        start_conversation: (args) => {
          starts.push({ requestId: args['requestId'], fileIds: args['fileIds'] });
          return starts.length === 1
            ? new Response(null, { status: 503 })
            : aConversation({
                id: `conv-${starts.length}`,
                agentName: 'Developer',
                turnStatus: 'RUNNING',
              });
        },
      },
      undefined,
      files,
    );
    const outlook = fakeOutlook();
    withAttachment(outlook);
    const page = await render(outlook);
    (await appears<HTMLButtonElement>(page, '[data-action="SUMMARISE"]')).click();
    expect((await appears(page, '[data-testid="pane-error"]')).textContent).toContain(
      'Tinfirm answered 503',
    );

    (page.querySelector('[data-action="SUMMARISE"]') as HTMLButtonElement).click();
    await appears(page, '[data-testid="conversation"]');
    expect(starts).toHaveLength(2);
    expect(starts[1]).toEqual(starts[0]);
    expect(starts[0].requestId).toMatch(/^[0-9a-f-]{36}$/);
    expect(starts[0].fileIds).toEqual(['f-invoice.pdf']);
    expect(files).toHaveBeenCalledTimes(1);

    // Started: the next start is another submission, with the files held again.
    (page.querySelector('[data-testid="start-over"]') as HTMLButtonElement).click();
    (await appears<HTMLButtonElement>(page, '[data-action="SUMMARISE"]')).click();
    await appears(page, '[data-testid="conversation"]');
    expect(starts).toHaveLength(3);
    expect(starts[2].requestId).not.toBe(starts[0].requestId);
    expect(files).toHaveBeenCalledTimes(2);
  });

  it('starts afresh, with a new key and the files held again, after Tinfirm refused a start', async () => {
    localStorage.setItem(`tinfirm.outlook.${COMPANY}`, '{"refreshToken":"refresh-1"}');
    const starts: unknown[] = [];
    const files = vi.fn(holdAll);
    fakeTinfirm(
      {
        list_agents: () => ({
          items: [anAgentSummary({ id: 'a-dev', name: 'Developer', usableByMe: true })],
        }),
        start_conversation: (args) => {
          starts.push(args['requestId']);
          return starts.length === 1
            ? new Response(null, { status: 400 })
            : aConversation({ id: 'conv-1', agentName: 'Developer', turnStatus: 'RUNNING' });
        },
      },
      undefined,
      files,
    );
    const outlook = fakeOutlook();
    withAttachment(outlook);
    const page = await render(outlook);
    (await appears<HTMLButtonElement>(page, '[data-action="TRIAGE"]')).click();
    expect((await appears(page, '[data-testid="pane-error"]')).textContent).toContain(
      'Tinfirm answered 400',
    );

    (page.querySelector('[data-action="TRIAGE"]') as HTMLButtonElement).click();
    await appears(page, '[data-testid="conversation"]');
    expect(starts).toHaveLength(2);
    expect(starts[1]).not.toBe(starts[0]);
    expect(files).toHaveBeenCalledTimes(2);
  });

  it('sends a message that failed on the way again with its key, and the next one afresh', async () => {
    rememberConversation();
    const sends: unknown[] = [];
    fakeTinfirm({
      list_agents: () => ({ items: [] }),
      get_conversation: () => answered('conv-1', 'The cause is a timeout.'),
      send_message: (args) => {
        sends.push(args['requestId']);
        return sends.length === 1
          ? new Response(null, { status: 503 })
          : answered('conv-1', 'The cause is a timeout.');
      },
    });
    const page = await render(fakeOutlook());
    const say = async (text: string) => {
      const textarea = await appears<HTMLTextAreaElement>(page, '#pane-message');
      textarea.value = text;
      textarea.dispatchEvent(new Event('input'));
      await settle();
      page.querySelector('[data-testid="conversation"] form')!.dispatchEvent(new Event('submit'));
      await settle();
    };

    await say('And the fix?');
    expect((await appears(page, '[data-testid="pane-error"]')).textContent).toContain(
      'Tinfirm answered 503',
    );
    await say('And the fix?');
    await settleUntil(() => sends.length === 2, 'the message sent again');
    expect(sends[1]).toBe(sends[0]);

    await say('And the fix?');
    await settleUntil(() => sends.length === 3, 'another message');
    expect(sends[2]).not.toBe(sends[0]);
  });

  it('shows a message Tinfirm took from its answer, and keeps it sent when the next read fails', async () => {
    rememberConversation();
    const before = answered('conv-1', 'The cause is a timeout.');
    const sent = taken(before, 'And the fix?');
    const done: Conversation = {
      ...before,
      messages: [
        ...sent.messages.map((m) => ({ ...m, waiting: false })),
        aMessage({ id: 'conv-1-m4', kind: 'AGENT', body: 'Add a null check.' }),
      ],
    };
    const sends: unknown[] = [];
    let reads = 0;
    fakeTinfirm({
      list_agents: () => ({ items: [] }),
      get_conversation: () => {
        reads++;
        if (!sends.length) return before;
        // The first read after the message was taken fails on the way; the next finds the turn done.
        return reads === 2 ? new Response(null, { status: 503 }) : done;
      },
      send_message: (args) => {
        sends.push(args['requestId']);
        return sent;
      },
    });
    const page = await render(fakeOutlook());
    const textarea = await appears<HTMLTextAreaElement>(page, '#pane-message');
    textarea.value = 'And the fix?';
    textarea.dispatchEvent(new Event('input'));
    await settle();
    page.querySelector('[data-testid="conversation"] form')!.dispatchEvent(new Event('submit'));
    await appears(page, '[data-testid="message-waiting"]');

    // Taken: the message is in the thread and out of the composer, with no read for it.
    expect(threadOf(page).at(-1)).toContain('And the fix?');
    expect(page.querySelector('#pane-message')).toBeNull();
    expect(reads).toBe(1);

    // The next read fails: the message stays sent, nothing offers to send it again.
    becomes('hidden');
    becomes('visible');
    expect((await appears(page, '[data-testid="pane-error"]')).textContent).toContain(
      'Tinfirm answered 503',
    );
    expect(threadOf(page).at(-1)).toContain('And the fix?');
    expect(page.querySelector('#pane-message')).toBeNull();
    expect(sends).toHaveLength(1);

    // A later read shows the turn it started; the composer comes back empty.
    becomes('hidden');
    becomes('visible');
    await settleUntil(
      () => threadOf(page).join(' ').includes('Add a null check.'),
      'the turn’s answer',
    );
    expect((await appears<HTMLTextAreaElement>(page, '#pane-message')).value).toBe('');
    expect(sends).toHaveLength(1);
  });

  it('says when Tinfirm did not confirm the sign-out', async () => {
    rememberConversation();
    fakeTinfirm(
      { list_agents: () => ({ items: [] }), get_conversation: () => aConversation() },
      () => new Response(null, { status: 503 }),
    );
    const page = await render(fakeOutlook());
    (await appears<HTMLButtonElement>(page, '[data-testid="sign-out"]')).click();

    expect((await appears(page, '[data-testid="pane-error"]')).textContent).toContain(
      'Connected apps',
    );
    expect(page.querySelector('[data-testid="sign-in"]')).not.toBeNull();
    expect(localStorage.getItem(`tinfirm.outlook.${COMPANY}`)).toBeNull();
  });
});
