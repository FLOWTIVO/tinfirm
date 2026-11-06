import { urls } from '../harness/config';
import { expect, expectNoAxeViolations, expectNoHorizontalOverflow, test } from '../fixtures';

/**
 * The harness's own smoke tests: the real server is up, the seed is there as each person sees it through the API,
 * and the scripted runner host takes the turns of conversations started through the API, asks, requests an
 * approval, calls the systems' tools through the server and the model through the relay. None of them needs a page
 * of the web app; the last one runs axe and the phone check on the harness's own status page.
 */

test.describe('the stack', () => {
  test('the server, the fakes and the runner host are up', async ({ seed, runner }) => {
    const readiness = await fetch(`${urls.management}/actuator/health/readiness`);
    expect(readiness.status).toBe(200);
    const info = (await (await fetch(`${urls.server}/api/system/info`)).json()) as {
      version: string;
      telemetryEnabled: boolean;
    };
    expect(info.version).toBeTruthy();
    expect(info.telemetryEnabled).toBe(false);

    const health = (await (await fetch(`${urls.control}/health`)).json()) as { ready: boolean };
    expect(health.ready).toBe(true);
    const status = await runner.status();
    expect(status.connected).toBe(true);
    expect(status.runnerHostId).toBe(seed.runnerHost.id);
    expect(status.companyId).toBe(seed.companyId);
  });
});

test.describe('the seed, through the API', () => {
  test('the company belongs to Olivia, with its members and roles; the root is an instance admin only', async ({
    api,
    apiPath,
    seed,
  }) => {
    const owner = await api('owner');
    const me = await owner.get('/api/me');
    expect(me.memberships).toContainEqual(
      expect.objectContaining({ companyId: seed.companyId, role: 'OWNER' }),
    );

    const members: { email: string; role: string }[] = await owner.all(apiPath('/members'));
    // Other tests add people of their own while this one runs; the seed's are among them.
    expect(members.map((member) => [member.email, member.role])).toEqual(
      expect.arrayContaining([
        ['adam@acme.test', 'ADMIN'],
        ['alice@acme.test', 'MEMBER'],
        ['bob@acme.test', 'MEMBER'],
        ['carol@acme.test', 'MEMBER'],
        ['dave@acme.test', 'MEMBER'],
        ['olivia@acme.test', 'OWNER'],
      ]),
    );
    expect(members.map((member) => member.email)).not.toContain(seed.root.email);
  });

  test('the admin sees the groups, systems, svc-dev with its grants, the gateway and the runner host', async ({
    api,
    apiPath,
    seed,
  }) => {
    const admin = await api('admin');
    const analysts = await admin.get(apiPath(`/groups/${seed.groups['Analysts']}`));
    expect(analysts.members.map((member: { userId: string }) => member.userId).sort()).toEqual(
      [seed.people.alice.id, seed.people.carol.id].sort(),
    );

    const systems: { name: string; kind: string; baseUrl: string }[] = await admin.all(
      apiPath('/systems'),
    );
    // Other tests register systems of their own while this one runs; the seed's are among them.
    expect(systems.map((system) => [system.name, system.kind])).toEqual(
      expect.arrayContaining([
        ['GitHub', 'GITHUB'],
        ['Jira', 'JIRA_DC'],
      ]),
    );
    const repositories = await admin.all(
      apiPath(`/systems/${seed.systems.github.id}/repositories`),
    );
    expect(repositories).toContainEqual(expect.objectContaining({ id: seed.repository.id }));

    const identity = await admin.get(apiPath(`/service-identities/${seed.serviceIdentity.id}`));
    expect(identity).toMatchObject({ name: 'svc-dev', status: 'ACTIVE' });
    // It holds no accounts of its own: it borrows the systems' credentials named after it (ADR-0072).
    const borrowed = await admin.get(
      apiPath(`/service-identities/${seed.serviceIdentity.id}/credentials`),
    );
    expect(
      borrowed.items.map(
        (entry: { system: { name: string }; credential: { name: string; status: string } }) => [
          entry.system.name,
          entry.credential.name,
          entry.credential.status,
        ],
      ),
    ).toEqual(
      expect.arrayContaining([
        ['GitHub', 'svc-dev', 'OK'],
        ['Jira', 'svc-dev', 'OK'],
      ]),
    );
    const grants: { groupName: string; access: string; systemName: string }[] = await admin.all(
      apiPath('/grants'),
    );
    expect(grants.map((grant) => [grant.systemName, grant.groupName, grant.access])).toEqual(
      expect.arrayContaining([
        ['GitHub', 'Analysts', 'READ'],
        ['Jira', 'Analysts', 'READ'],
      ]),
    );

    const settings = await admin.get(apiPath('/model-settings'));
    expect(settings).toMatchObject({
      configured: true,
      gateway: 'BUNDLED',
      relayEnabled: true,
      defaultBudgetUsd: 50,
    });
    const hosts: { id: string; scope: string; online: boolean }[] = await admin.all(
      apiPath('/runner-hosts'),
    );
    expect(hosts).toContainEqual(
      expect.objectContaining({
        id: seed.runnerHost.id,
        scope: 'COMPANY',
        online: true,
      }),
    );
  });

  test('every member may use the agent, and its readiness binds each as the plan’s scenarios need', async ({
    api,
    apiPath,
    seed,
  }) => {
    const binding = async (name: 'alice' | 'bob' | 'carol' | 'dave') => {
      const person = await api(name);
      const usable: { id: string }[] = await person.all(apiPath('/agents?usable=true'));
      expect(
        usable.map((agent) => agent.id),
        `${name} may use the agent`,
      ).toContain(seed.agent.id);
      const readiness = await person.get(apiPath(`/agents/${seed.agent.id}/readiness`));
      expect(readiness.payer.state, `${name}'s budget pays`).toBe('READY');
      expect(readiness.hosts.runnable, `a host runs ${name}'s turns`).toBe(true);
      const bySystem = (systemId: string) =>
        readiness.systems.find((system: { systemId: string }) => system.systemId === systemId);
      return { jira: bySystem(seed.systems.jira.id), github: bySystem(seed.systems.github.id) };
    };
    // V1: Alice and Bob act on GitHub as themselves. Alice reads Jira through Analysts.
    const alice = await binding('alice');
    expect(alice.github).toMatchObject({ binding: 'OWN' });
    expect(alice.jira).toMatchObject({ binding: 'GRANTED', access: 'READ' });
    const bob = await binding('bob');
    expect(bob.github).toMatchObject({ binding: 'OWN' });
    expect(bob.jira).toMatchObject({ binding: 'CONNECT' });
    // V5: Carol has no GitHub account of her own and acts through Analysts, read-only.
    expect((await binding('carol')).github).toMatchObject({ binding: 'GRANTED', access: 'READ' });
    // V6: Dave holds nothing and connects when the agent first needs it.
    expect((await binding('dave')).github).toMatchObject({ binding: 'CONNECT' });
  });
});

test.describe('the scripted runner host', () => {
  test('takes the turn of a conversation started through the API, and finishes it with a result @serial', async ({
    api,
    apiPath,
    runner,
    startConversation,
    stubs,
    litellm,
    seed,
  }) => {
    const message = `Summarise SHOP-1 for me (${Date.now()})`;
    const { conversation, run } = await startConversation('alice', message);
    expect(conversation).toMatchObject({ state: 'OPEN', agentId: seed.agent.id });
    expect(run).toMatchObject({
      state: 'running',
      script: 'hold',
      conversationId: conversation.id,
    });
    expect(run.request).toContain(message);
    expect(run.tools.map((tool) => tool.name)).toEqual(
      expect.arrayContaining(['jira_get', 'github_comment']),
    );
    expect(run.workspaces).toEqual([
      expect.objectContaining({ name: 'acme/shop', readOnly: false, hasGitToken: true }),
    ]);
    expect(run.modelAccess).toMatchObject({ format: 'anthropic' });

    const alice = await api('alice');
    await expect
      .poll(async () => (await alice.get(apiPath(`/conversations/${conversation.id}`))).waitingOn)
      .toBe('AGENT');

    // A tool call runs on the server against the Jira stub, as the account the conversation binds: svc-dev's,
    // lent to Analysts.
    await stubs.clearCalls();
    const ack = await runner.tool(run.runId, { tool: 'jira_get', arguments: { key: 'SHOP-1' } });
    expect(ack.status).toBe('DONE');
    expect(JSON.stringify(ack.payload)).toContain('SHOP-1');
    const calls = await stubs.calls('jira');
    expect(calls).toContainEqual(
      expect.objectContaining({ method: 'GET', identity: 'svc-dev', status: 200 }),
    );

    // A model request goes through the server's relay to the gateway with Alice's own key.
    const model = await runner.callModel(run.runId);
    expect(model.status).toBe(200);
    const requests = await litellm.requests('/v1/messages');
    expect(requests.at(-1)?.alias).toContain(seed.people.alice.principalId);

    await runner.message(run.runId, 'Reading SHOP-1 now.');
    await runner.finish(run.runId, { summary: 'SHOP-1 asks for the e2e harness.' });

    await expect
      .poll(async () => (await alice.get(apiPath(`/conversations/${conversation.id}`))).turnStatus)
      .toBe('SUCCEEDED');
    const done = await alice.get(apiPath(`/conversations/${conversation.id}`));
    expect(done.waitingOn).toBe('NONE');
    expect(
      done.messages.map((entry: { kind: string; body: string }) => [entry.kind, entry.body]),
    ).toContainEqual(['AGENT', expect.stringContaining('SHOP-1 asks for the e2e harness.')]);
    const finished = await alice.get(apiPath(`/runs/${run.runId}`));
    expect(finished).toMatchObject({
      status: 'SUCCEEDED',
      runnerHostKind: 'COMPANY',
      modelAccess: 'BUDGET',
    });
  });

  test('asks a question; the person’s answer starts the next turn', async ({
    api,
    apiPath,
    runner,
    startConversation,
  }) => {
    const { conversation, run } = await startConversation('alice', 'Plan the release');
    const ack = await runner.ask(run.runId, {
      question: 'Which issue first?',
      options: ['SHOP-1', 'SHOP-2'],
    });
    expect(ack.status).toBe('PENDING');

    const alice = await api('alice');
    await expect
      .poll(async () => {
        const inbox = await alice.get(apiPath('/inbox'));
        return inbox.items.find(
          (item: { decision?: { conversationId: string; kind: string } }) =>
            item.decision?.conversationId === conversation.id && item.decision?.kind === 'QUESTION',
        )?.decision;
      })
      .toMatchObject({
        question: 'Which issue first?',
        options: ['SHOP-1', 'SHOP-2'],
        mayDecide: true,
      });
    const decisionId = (ack.payload as { decisionId: string }).decisionId;
    await alice.post(apiPath(`/decisions/${decisionId}/decide`), { option: 'SHOP-1' });

    const turn = await runner.awaitRun({ conversationId: conversation.id, state: 'running' });
    expect(turn.runId).not.toBe(run.runId);
    expect(turn.request).toContain('SHOP-1');
    await runner.finish(turn.runId);
  });

  test('requests an approval; the approved call runs as the person’s own account @serial', async ({
    api,
    apiPath,
    runner,
    startConversation,
    stubs,
  }) => {
    const { conversation, run } = await startConversation('bob', 'Comment on acme/shop#1');
    const ack = await runner.approval(run.runId, { tool: 'github_comment' });
    expect(ack.status).toBe('PENDING');

    const bob = await api('bob');
    await expect
      .poll(async () => {
        const view = await bob.get(apiPath(`/conversations/${conversation.id}`));
        return [view.waitingOn, view.waitingReason];
      })
      .toEqual(['PERSON', 'APPROVAL']);
    const decisionId = (ack.payload as { decisionId: string }).decisionId;
    const decision = await bob.get(apiPath(`/decisions/${decisionId}`));
    expect(decision).toMatchObject({
      kind: 'APPROVAL',
      mayDecide: true,
      subject: { type: 'TOOL_CALL', name: 'github_comment' },
    });

    await stubs.clearCalls();
    await bob.post(apiPath(`/decisions/${decisionId}/decide`), { outcome: 'APPROVED' });
    await expect
      .poll(async () => (await stubs.calls('github')).filter((call) => call.method === 'POST'))
      .toEqual([
        expect.objectContaining({
          path: '/repos/acme/shop/issues/1/comments',
          identity: 'bob',
          status: 201,
        }),
      ]);

    const turn = await runner.awaitRun({ conversationId: conversation.id, state: 'running' });
    expect(turn.runId).not.toBe(run.runId);
    await runner.finish(turn.runId);
  });

  test('plays a script named in the message: a failure with USAGE_LIMIT', async ({
    api,
    apiPath,
    startConversation,
  }) => {
    const { conversation } = await startConversation(
      'alice',
      'Do the impossible [e2e:fail:USAGE_LIMIT]',
    );
    const alice = await api('alice');
    await expect
      .poll(async () => {
        const view = await alice.get(apiPath(`/conversations/${conversation.id}`));
        return [view.turnStatus, view.failureClass];
      })
      .toEqual(['FAILED', 'USAGE_LIMIT']);
  });
});

test.describe('the personas’ browser sessions', () => {
  test.use({ persona: 'bob' });

  test('the default page is the persona’s, and each named page its person’s', async ({
    page,
    alicePage,
    ownerPage,
  }) => {
    const me = async (which: typeof page) =>
      (await (await which.request.get(`${urls.server}/api/me`)).json()) as { email: string };
    expect((await me(page)).email).toBe('bob@acme.test');
    expect((await me(alicePage)).email).toBe('alice@acme.test');
    expect((await me(ownerPage)).email).toBe('olivia@acme.test');
  });
});

test.describe('the page checks', { tag: '@phone' }, () => {
  test('axe and the phone check run on a page: the harness’s status page', async ({
    page,
  }, testInfo) => {
    await page.goto(urls.control);
    await expect(
      page.getByRole('heading', { name: 'Tinfirm e2e harness', level: 1 }),
    ).toBeVisible();
    await expect(page.getByRole('cell', { name: 'olivia@acme.test' })).toBeVisible();
    await expectNoAxeViolations(page, testInfo);
    await expectNoHorizontalOverflow(page);
  });
});
