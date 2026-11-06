import { type Locator, type Page, request, type TestInfo } from '@playwright/test';
import { Api, type Body } from '../harness/api.ts';
import { company, password, root, urls } from '../harness/config.ts';
import { deliverWebhook } from '../harness/webhook-sender.ts';
import {
  expect,
  expectNoAxeViolations,
  expectNoHorizontalOverflow,
  type SeedState,
  shoot,
  test,
} from '../fixtures';

/**
 * W5, triggers (the plan §4.5, §7's W5 row and §8; the package 4 contract, V2, V4, V9 and V14), against the real
 * server, its polls and its write-back, with the Jira stub's issues and the scripted runner. Every trigger a test
 * makes is its own, named with the project, and deleted when it ends; an account a test connects is disconnected.
 */

type Path = (suffix: string) => string;

/** Deletes the live triggers of these names, as the admin, who sees every trigger. */
async function deleteTriggers(admin: Api, path: Path, names: string[]): Promise<void> {
  const triggers: { id: string; name: string }[] = await admin.all(path('/triggers'));
  for (const t of triggers.filter((entry) => names.includes(entry.name))) {
    await admin.delete(path(`/triggers/${t.id}`)).catch(() => undefined);
  }
}

/** Deletes the live agents of these names, as the admin, trying again while a run still counts as active. */
async function deleteAgents(admin: Api, path: Path, names: string[]): Promise<void> {
  const agents: { id: string; name: string }[] = await admin.all(path('/agents'));
  for (const agent of agents.filter((a) => names.includes(a.name))) {
    for (let attempt = 1; attempt <= 20; attempt++) {
      try {
        await admin.delete(path(`/agents/${agent.id}`));
        break;
      } catch {
        await new Promise((resolve) => setTimeout(resolve, 500));
      }
    }
  }
}

/** Connects a person's own Jira account by its token (the stubs know `jira-token-<name>`). */
async function connectJira(api: Api, path: Path, seed: SeedState, token: string): Promise<void> {
  await api.put(path(`/me/accounts/${seed.systems.jira.id}/token`), { token });
}

async function disconnectJira(api: Api, path: Path, seed: SeedState): Promise<void> {
  await api.delete(path(`/me/accounts/${seed.systems.jira.id}`)).catch(() => undefined);
}

/** Polls until `read` answers something, or fails after `timeoutMs`. */
async function eventually<T>(
  what: string,
  read: () => Promise<T | undefined | null | false>,
  timeoutMs = 60_000,
): Promise<T> {
  const until = Date.now() + timeoutMs;
  for (;;) {
    const value = await read();
    if (value) return value;
    if (Date.now() > until) throw new Error(`Timed out waiting for ${what}`);
    await new Promise((resolve) => setTimeout(resolve, 1000));
  }
}

/**
 * Axe after an action: once the toast has faded in (its entering animation lowers the contrast axe measures for a
 * moment). The page's endless animations (spinners, the working dot) never settle, so this waits for a fixed time.
 */
async function axeWhenStill(page: Page, testInfo: TestInfo): Promise<void> {
  await page.waitForTimeout(800);
  await expectNoAxeViolations(page, testInfo);
}

/** A review screenshot of the whole page, once its fonts are in (`shoot` captures it at its full height). */
async function capture(page: Page, testInfo: TestInfo, name: string): Promise<void> {
  await page.evaluate(() => document.fonts.ready);
  await shoot(page, testInfo, 'w5', name);
}

/**
 * Opens a section of the editor by its tab, which shows one section at a time; on a phone the
 * tabs are the first screen, and "All sections" goes back to them from a section. A tab is named
 * by its content, the label and then how the section stands: the tab is the one whose label reads
 * `name`.
 */
async function openSection(page: Page, name: string): Promise<void> {
  const tab = page.getByRole('tab').filter({ has: page.getByText(name, { exact: true }) });
  const back = page.getByRole('button', { name: 'All sections' });
  // The editor first: until it has loaded, neither shows.
  await expect(tab.or(back).first()).toBeVisible();
  if (!(await tab.isVisible())) await back.click();
  await tab.click();
}

/** The editor's instruction, in its Markdown source. */
async function fillInstruction(page: Page, instruction: string): Promise<void> {
  await openSection(page, 'The agent does');
  await page
    .locator('#trigger-instruction')
    .locator('xpath=ancestor::app-markdown-editor')
    .getByTestId('editor-mode-markdown')
    .click();
  // The source's textarea: for a moment the rich editor, hidden now, still carries the id too.
  await page.locator('textarea#trigger-instruction').fill(instruction);
}

/** The editor's rule: a name, Jira, the events and the project SHOP; then the instruction. */
async function fillRule(
  page: Page,
  seed: SeedState,
  name: string,
  event: string,
  instruction: string,
): Promise<void> {
  await openSection(page, 'When');
  await page.getByLabel('Name', { exact: true }).fill(name);
  await page.locator('#trigger-system').selectOption(seed.systems.jira.id);
  await page.locator('#trigger-event-0').selectOption(event);
  await page.locator('#trigger-scope').fill('SHOP');
  await fillInstruction(page, instruction);
}

/** Picks "Use the accounts of" a principal, in "Acts as and pays". */
async function choose(page: Page, principalId: string): Promise<void> {
  await openSection(page, 'Acts as and pays');
  await page.locator(`[data-choice="${principalId}"] input[type=radio]`).check();
}

/**
 * Picks the seed's model in "Narrowed for its runs": a budget pays only for a model the gateway serves, and the
 * preview refuses the trigger without one (`MODEL_REQUIRED`, openapi `AgentSettingsRequest.model`).
 */
async function chooseModel(page: Page): Promise<void> {
  await openSection(page, 'Narrowed for its runs');
  await page.locator('[data-field="model"] select').selectOption(company.agent.model);
}

function settingsTool(page: Page, systemId: string, tool: string): Locator {
  return page.locator(`[data-system="${systemId}"] [data-tool="${tool}"]`);
}

test.describe('W5: triggers', { tag: '@app' }, () => {
  test(
    'V14: the editor holds Save until “I understand” while anyone may drive it as you @serial',
    { tag: '@phone' },
    async ({ alicePage, api, apiPath, appPath, seed }, testInfo) => {
      const name = `Answer mentions ${testInfo.project.name}`;
      const agentName = `Commenter ${testInfo.project.name}`;
      const admin = await api('admin');
      const alice = await api('alice');
      await deleteTriggers(admin, apiPath, [name]);
      await deleteAgents(admin, apiPath, [agentName]);
      // An agent whose Jira comment runs without asking, shared with everyone.
      const agent: Body = await admin.post(apiPath('/agents'), {
        name: agentName,
        description: 'Answers on Jira.',
        instructions: 'Answer the question in a comment.',
        runtimes: ['CLAUDE_CODE'],
        systems: [
          {
            systemId: seed.systems.jira.id,
            tools: [
              { name: 'get', mode: 'ALLOW' },
              { name: 'comment', mode: 'ALLOW', chosenForEffect: 'WRITE' },
            ],
          },
        ],
        repositories: [],
        shareGroupIds: [seed.groups['Everyone']],
      });
      await connectJira(alice, apiPath, seed, 'jira-token-alice');
      try {
        const page = alicePage;
        await page.goto(appPath(`/triggers/new?agentId=${agent.id}`));
        await expect(page.getByRole('heading', { level: 1, name: 'New trigger' })).toBeVisible();
        await fillRule(page, seed, name, 'MENTIONED', 'Answer the question asked of you.');
        await expect(page.locator('#trigger-agent')).toHaveValue(agent.id);

        // Anyone who may comment can mention it: svc-dev comes first, and every write asks.
        await openSection(page, 'Acts as and pays');
        await expect(
          page.locator(`[data-choice="${seed.serviceIdentity.id}"] input`),
        ).toBeChecked();
        await chooseModel(page);
        await expect(page.getByTestId('ask-prefilled')).toBeVisible();

        // Alice picks her own accounts and lets the comment run as the agent says.
        await choose(page, seed.people.alice.principalId);
        await openSection(page, 'Narrowed for its runs');
        const comment = settingsTool(page, seed.systems.jira.id, 'comment');
        await comment.getByText('As the agent says').click();
        // The warning stands beside the sections; on a phone, in Acts as and pays, whose tab says
        // it needs the reader (on a phone the tabs are hidden while a section is open).
        await openSection(page, 'Acts as and pays');
        await expect(
          page
            .getByRole('tab', { name: 'Acts as and pays', includeHidden: true })
            .getByTestId('needs-you-dot'),
        ).toBeAttached();
        const warning = page.getByTestId('open-to-anyone');
        await expect(warning).toContainText('Anyone can drive this agent');
        await expect(warning).toContainText('as you (your own account)');
        await expect(page.getByTestId('save-status')).toHaveText(
          'Tick “I understand” in the warning to create it.',
        );
        await expect(page.getByTestId('save-trigger')).toBeDisabled();
        await axeWhenStill(page, testInfo);
        if (testInfo.project.name === 'phone') await expectNoHorizontalOverflow(page);
        await capture(page, testInfo, 'editor-open-events');

        // The server refuses to save it without the acknowledgement too (V14).
        const refused = await alice.raw('POST', apiPath('/triggers'), {
          name,
          source: 'TRACKER',
          systemId: seed.systems.jira.id,
          events: [{ event: 'MENTIONED' }],
          subject: 'ISSUE',
          scope: 'SHOP',
          conditions: [],
          repeat: 'EVERY_TIME',
          agentId: agent.id,
          agentVersion: agent.version,
          instruction: 'Answer the question asked of you.',
          settings: { model: company.agent.model },
          accounts: [
            {
              systemId: seed.systems.jira.id,
              accountId: await jiraAccountOf(alice, apiPath, seed),
            },
          ],
          payerPrincipalId: seed.people.alice.principalId,
        });
        expect(refused.status()).toBe(400);
        expect((await refused.json()).code).toBe('OPEN_EVENTS_NOT_ACKNOWLEDGED');

        // The warning's one way out is every write asking (D2: a service account is none, so it offers no
        // "use svc-dev instead"); svc-dev's accounts are chosen in Acts as and pays as any other.
        await choose(page, seed.serviceIdentity.id);
        await page.getByTestId('ask-every-write').click();
        await expect(page.getByTestId('open-to-anyone')).toContainText(
          'Every write asks the team first',
        );
        await expect(page.getByTestId('save-trigger')).toBeEnabled();
        await page.getByTestId('save-trigger').click();
        await expect(page).toHaveURL(/\/triggers\/[0-9a-f-]{36}$/);
        await expect(page.getByRole('heading', { level: 1, name })).toBeVisible();
        await expect(page.getByTestId('who-acts-who-pays')).toContainText(
          'Jira: svc-dev with svc-dev',
        );
      } finally {
        await deleteTriggers(admin, apiPath, [name]);
        await deleteAgents(admin, apiPath, [agentName]);
        await disconnectJira(alice, apiPath, seed);
      }
    },
  );

  test(
    'the list and a trigger’s page read on a phone',
    { tag: '@phone' },
    async ({ alicePage, api, apiPath, appPath, seed }, testInfo) => {
      const name = `Weekly digest ${testInfo.project.name}`;
      const admin = await api('admin');
      const alice = await api('alice');
      await deleteTriggers(admin, apiPath, [name]);
      const agentVersion = (await alice.get(apiPath(`/agents/${seed.agent.id}`))).version;
      const created: Body = await alice.post(apiPath('/triggers'), {
        name,
        source: 'SCHEDULE',
        schedule: { cron: '0 9 * * 1', zone: 'Europe/Prague', overlap: 'SKIP', catchUp: 'NONE' },
        agentId: seed.agent.id,
        agentVersion,
        instruction: 'Write the weekly digest of what changed in SHOP.',
        settings: { model: company.agent.model },
        accounts: [
          { systemId: seed.systems.jira.id, accountId: seed.serviceIdentity.accounts.jira },
        ],
        payerPrincipalId: seed.serviceIdentity.id,
        teamGroupId: seed.groups['Analysts'],
        results: { comment: false, review: true, onSuccess: [], onFailure: [] },
      });
      try {
        const page = alicePage;
        await page.goto(appPath('/triggers'));
        const row = page.getByTestId(`trigger-${created.id}`);
        await expect(row).toContainText('Schedule · Analyst · by Alice');
        await axeWhenStill(page, testInfo);
        if (testInfo.project.name === 'phone') await expectNoHorizontalOverflow(page);
        if (testInfo.project.name === 'phone') await capture(page, testInfo, 'triggers-list');

        await row.getByRole('link', { name }).click();
        await expect(page.getByRole('heading', { level: 1, name })).toBeVisible();
        await expect(page.getByTestId('trigger-rule')).toContainText('0 9 * * 1 in Europe/Prague');
        await expect(page.getByRole('switch', { name: /reviews each result/ })).toBeChecked();
        await expect(page.getByTestId('firings-empty')).toBeVisible();
        await capture(page, testInfo, 'trigger-schedule');

        // Paused by its team's member, and the page says so.
        await page.getByTestId('pause-trigger').click();
        await page.getByTestId('confirm-pause').click();
        await expect(page.getByTestId('paused-notice')).toContainText('Paused: Paused by hand');
        await axeWhenStill(page, testInfo);
        if (testInfo.project.name === 'phone') await expectNoHorizontalOverflow(page);
        await capture(page, testInfo, 'trigger-paused');
        await page.getByTestId('resume-trigger').click();
        await expect(page.getByTestId('paused-notice')).toHaveCount(0);
      } finally {
        await deleteTriggers(admin, apiPath, [name]);
      }
    },
  );

  test('a webhook trigger fires on a signed delivery its events and conditions match', async ({
    alicePage,
    api,
    apiPath,
    appPath,
    runner,
    seed,
  }, testInfo) => {
    const name = `Ops incidents ${testInfo.project.name}`;
    const systemName = `Ops hooks ${testInfo.project.name}`;
    const admin = await api('admin');
    const alice = await api('alice');
    const systems: { id: string; name: string }[] = await admin.all(apiPath('/systems'));
    for (const old of systems.filter((x) => x.name === systemName)) {
      await admin.delete(apiPath(`/systems/${old.id}`)).catch(() => undefined);
    }
    const system: Body = await admin.post(apiPath('/systems'), {
      name: systemName,
      kind: 'WEBHOOK',
      webhook: {
        scheme: 'STANDARD',
        mapping: { event: '/action', subjectKey: '/issue/key', subjectTitle: '/issue/title' },
      },
    });
    const secret: string = system.webhook.secret;
    try {
      const page = alicePage;
      await page.goto(appPath(`/triggers/new?agentId=${seed.agent.id}`));
      await page.getByLabel('Name', { exact: true }).fill(name);
      // "It starts when" is a select: its menu offers each way with its detail.
      await page.locator('#trigger-source').click();
      await page.getByRole('menuitemradio', { name: /^A webhook is delivered/ }).click();
      await expect(page.locator('#trigger-source')).toHaveText(/A webhook is delivered/);
      await page.locator('#trigger-system').selectOption(system.id);
      await page.locator('#trigger-webhook-events').fill('opened');
      await page.getByTestId('add-webhook-condition').click();
      await page.locator('#trigger-wc-pointer-0').fill('/issue/severity');
      await page.locator('#trigger-wc-values-0').fill('high');
      await fillInstruction(page, 'Open the incident and say what you see.');
      await choose(page, seed.serviceIdentity.id);
      await chooseModel(page);
      await expect(page.getByTestId('preview-sentence')).toBeVisible();
      await expect(page.getByTestId('save-trigger')).toBeEnabled();
      await axeWhenStill(page, testInfo);
      await capture(page, testInfo, 'editor-webhook');
      await page.getByTestId('save-trigger').click();
      await expect(page).toHaveURL(/\/triggers\/[0-9a-f-]{36}$/);
      const triggerId = page.url().split('/').pop()!;
      await expect(page.getByTestId('trigger-webhook-events')).toContainText('delivers opened');
      await expect(page.getByTestId('run-now')).toHaveCount(0);

      // A low-severity incident is left out by the condition; a high one fires.
      const tag = Date.now() % 100_000;
      const low = {
        action: 'opened',
        issue: { key: `OPS-${tag}`, title: 'Slow page', severity: 'low' },
      };
      const high = {
        action: 'opened',
        issue: { key: `OPS-${tag + 1}`, title: 'Disk full', severity: 'high' },
      };
      expect((await deliverWebhook(urls.server, system.id, secret, low)).status).toBeLessThan(300);
      expect((await deliverWebhook(urls.server, system.id, secret, high)).status).toBeLessThan(300);
      const firing: Body = await eventually('the webhook’s firing', async () =>
        ((await alice.get(apiPath(`/triggers/${triggerId}/firings`))).items as Body[]).find(
          (f) => f.subjectKey === `OPS-${tag + 1}`,
        ),
      );
      expect(firing).toMatchObject({ event: 'DELIVERED', outcome: 'STARTED' });
      const firings: Body[] = (await alice.get(apiPath(`/triggers/${triggerId}/firings`))).items;
      expect(firings.some((f) => f.subjectKey === `OPS-${tag}`)).toBe(false);
      const run = await runner.awaitRun({ conversationId: firing.conversationId });
      expect(run.prompt).toContain('Open the incident and say what you see.');

      await page.reload();
      await expect(page.locator(`[data-firing="${firing.id}"]`)).toContainText(`OPS-${tag + 1}`);
      await axeWhenStill(page, testInfo);
      await capture(page, testInfo, 'trigger-webhook');

      // Its conversation: the delivery's text as an EXTERNAL message without an author, and where it started.
      await page.goto(appPath(`/conversations/${firing.conversationId}`));
      await expect(page.getByTestId('place-notice')).toContainText(
        `Started by ${name} on a webhook’s delivery from ${systemName} about OPS-${tag + 1}.`,
      );
      const delivered = page.locator('[data-testid=thread] [data-testid=external-message]');
      await expect(delivered.getByTestId('external-author')).toHaveText(systemName);
      await expect(delivered).toContainText(`a delivery of opened about OPS-${tag + 1}`);
      await expect(page.getByTestId('composer-input')).toBeVisible();
      await axeWhenStill(page, testInfo);
      await capture(page, testInfo, 'conversation-webhook');
    } finally {
      await deleteTriggers(admin, apiPath, [name]);
      await admin.delete(apiPath(`/systems/${system.id}`)).catch(() => undefined);
    }
  });

  test(
    'its team reviews a result on the conversation page before it is written to the issue',
    { tag: '@phone' },
    async ({ alicePage, api, apiPath, appPath, runner, stubs, seed }, testInfo) => {
      const name = `Review briefs ${testInfo.project.name}`;
      const admin = await api('admin');
      const alice = await api('alice');
      await deleteTriggers(admin, apiPath, [name]);
      const agentVersion = (await alice.get(apiPath(`/agents/${seed.agent.id}`))).version;
      const created: Body = await alice.post(apiPath('/triggers'), {
        name,
        source: 'TRACKER',
        systemId: seed.systems.jira.id,
        events: [{ event: 'ASSIGNED' }],
        scope: 'SHOP',
        agentId: seed.agent.id,
        agentVersion,
        instruction: 'Prepare the issue: write a brief as a comment.',
        settings: { model: company.agent.model },
        accounts: [
          { systemId: seed.systems.jira.id, accountId: seed.serviceIdentity.accounts.jira },
        ],
        payerPrincipalId: seed.serviceIdentity.id,
        teamGroupId: seed.groups['Analysts'],
        results: { comment: true, review: true, onSuccess: [], onFailure: [] },
      });
      try {
        await alice.post(apiPath(`/triggers/${created.id}/run`), { subjectKey: 'SHOP-2' });
        const firing: Body = await eventually('the firing', async () =>
          ((await alice.get(apiPath(`/triggers/${created.id}/firings`))).items as Body[]).find(
            (f) => f.conversationId,
          ),
        );
        const run = await runner.awaitRun({ conversationId: firing.conversationId });

        // Alice, of Analysts, has the page open while the agent works: the conversation's stream brings the
        // review in without a reload.
        const page = alicePage;
        await page.goto(appPath(`/conversations/${firing.conversationId}`));
        await expect(page.getByTestId('place-notice')).toContainText(
          `Started by ${name} on SHOP-2.`,
        );
        await expect(page.getByTestId('conversation-state')).toHaveText('Working');
        const summary = `The brief of SHOP-2 (${testInfo.project.name} ${Date.now().toString(36)}).`;
        await runner.finish(run.runId, { summary });

        // Nothing is written before its team approves: the card says exactly what will be.
        const card = page.locator('[data-testid=turn] app-result-review-card');
        await expect(card).toContainText('review it before it is written');
        await expect(card.getByTestId('review-writes')).toContainText('What will be written');
        await expect(card.getByTestId('review-writes')).toContainText(summary);
        await expect(
          card.getByRole('link', { name: 'Link SHOP-2 to this conversation in Tinfirm' }),
        ).toBeVisible();
        await expect(card.getByTestId('review-writes')).not.toContainText('http');
        // Each write is its own item, under what it is.
        await expect(card.getByTestId('write-label')).toHaveText([
          'A comment on SHOP-2',
          'A link on SHOP-2',
        ]);
        await expect(page.getByTestId('conversation-state')).toHaveText(
          'Waits for Analysts · Review',
        );
        await expect(page.getByTestId('conversation-state')).toHaveAttribute('data-tone', 'human');
        // Tinfirm's note of the review says the same as the card: one line while the card is open.
        const folded = page.getByTestId('system-folded');
        await expect(folded).toHaveText(
          /Tinfirm asked for a review before writing; see the card above\.\s*Show/,
        );
        const note = page
          .locator('[data-author=SYSTEM]')
          .filter({ hasText: 'Review the result before Tinfirm writes it' });
        expect(
          (await stubs.jiraIssue('SHOP-2')).comments.some((c) => c.body.includes(summary)),
        ).toBe(false);
        await axeWhenStill(page, testInfo);
        if (testInfo.project.name === 'phone') await expectNoHorizontalOverflow(page);
        await capture(page, testInfo, 'conversation-review');
        // "Show" unfolds it, its link by its words, not the raw address.
        await folded.getByTestId('system-show').click();
        await expect(note).toContainText(summary);
        await expect(
          note.getByRole('link', { name: 'Link SHOP-2 to this conversation in Tinfirm' }),
        ).toBeVisible();
        await expect(note).not.toContainText('http');
        await page.reload();

        await card.getByTestId('approve-result').click();
        await expect(card.getByTestId('review-outcome')).toContainText('Approved');
        // Decided: the note shows in full.
        await expect(page.getByTestId('system-folded')).toHaveCount(0);
        await expect(
          page
            .locator('[data-author=SYSTEM]')
            .filter({ hasText: 'Review the result before Tinfirm writes it' }),
        ).toContainText(summary);
        const written = await eventually('the write-back', async () =>
          (await stubs.jiraIssue('SHOP-2')).comments.find((c) => c.body.includes(summary)),
        );
        expect(written.author).toBe('svc-dev');
      } finally {
        await deleteTriggers(admin, apiPath, [name]);
      }
    },
  );

  test('V2: a trigger on svc-dev’s accounts fires, writes back as svc-dev, and only its team sees it', async ({
    alicePage,
    pageAs,
    api,
    apiPath,
    appPath,
    runner,
    stubs,
    seed,
  }, testInfo) => {
    const name = `Prepare assigned ${testInfo.project.name}`;
    const admin = await api('admin');
    const alice = await api('alice');
    await deleteTriggers(admin, apiPath, [name]);
    try {
      const page = alicePage;
      await page.goto(appPath(`/triggers/new?agentId=${seed.agent.id}`));
      await fillRule(
        page,
        seed,
        name,
        'ASSIGNED',
        'Prepare the issue: write a brief as a comment.',
      );
      await choose(page, seed.serviceIdentity.id);
      await chooseModel(page);
      await openSection(page, 'Team');
      await page.locator('#trigger-team').selectOption(seed.groups['Analysts']);
      await expect(page.getByTestId('preview-sentence')).toBeVisible();
      await expect(page.getByTestId('who-acts-who-pays')).toContainText(
        'Jira: svc-dev with svc-dev',
      );
      await expect(page.getByTestId('who-acts-who-pays')).toContainText('svc-dev’s budget');
      await axeWhenStill(page, testInfo);
      await capture(page, testInfo, 'editor-new');
      await page.getByTestId('save-trigger').click();
      await expect(page).toHaveURL(/\/triggers\/[0-9a-f-]{36}$/);
      const triggerId = page.url().split('/').pop()!;

      // Run now on SHOP-2, found by the trigger's own account.
      await page.getByTestId('run-now').click();
      const dialog = page.getByTestId('run-now-dialog');
      await dialog.locator('[data-subject="SHOP-2"]').click();
      await dialog.getByTestId('confirm-run-now').click();
      await expect(page.getByTestId('run-now-result')).toContainText(
        'Run now on SHOP-2: Started a conversation',
      );
      const firing: Body = (await alice.get(apiPath(`/triggers/${triggerId}/firings`))).items[0];
      expect(firing.outcome).toBe('STARTED');

      // Its run: the trigger's instruction, paid by svc-dev through the relay; Jira as svc-dev.
      const run = await runner.awaitRun({ conversationId: firing.conversationId });
      expect(run.prompt).toContain('Prepare the issue: write a brief as a comment.');
      expect(run.modelAccess).not.toBeNull();
      await stubs.clearCalls();
      await runner.tool(run.runId, { tool: 'jira_get', arguments: { key: 'SHOP-2' } });
      const read = (await stubs.calls('jira')).find((c) =>
        c.path.startsWith('/rest/api/2/issue/SHOP-2'),
      );
      expect(read?.identity).toBe('svc-dev');

      // The result goes back to the issue as svc-dev, labelled as the agent's.
      await runner.finish(run.runId, { summary: `SHOP-2 is ready (${testInfo.project.name}).` });
      const written = await eventually('the write-back', async () =>
        (await stubs.jiraIssue('SHOP-2')).comments.find((c) =>
          c.body.includes(`SHOP-2 is ready (${testInfo.project.name}).`),
        ),
      );
      expect(written.author).toBe('svc-dev');
      expect(written.body).toContain('Analyst (Tinfirm agent):');

      await page.reload();
      await expect(page.locator(`[data-firing="${firing.id}"]`)).toContainText('Run now');
      await axeWhenStill(page, testInfo);
      await capture(page, testInfo, 'trigger-page');

      // Carol is in Analysts, its team: she sees it and may run and pause it, not edit it.
      const carol = await pageAs('carol');
      await carol.goto(appPath(`/triggers/${triggerId}`));
      await expect(carol.getByRole('heading', { level: 1, name })).toBeVisible();
      await expect(carol.getByTestId('run-now')).toBeVisible();
      await expect(carol.getByTestId('edit-trigger')).toHaveCount(0);
      // Dave is not: the trigger does not exist for him (T15).
      const dave = await pageAs('dave');
      await dave.goto(appPath(`/triggers/${triggerId}`));
      await expect(dave.getByRole('heading', { level: 1, name: 'Page not found' })).toBeVisible();
      await dave.goto(appPath('/triggers'));
      await expect(dave.getByRole('link', { name, exact: true })).toHaveCount(0);
    } finally {
      await deleteTriggers(admin, apiPath, [name]);
    }
  });

  test('V4: Alice’s own trigger fires on what is assigned to her, with her account, and not on Bob’s @serial', async ({
    alicePage,
    bobPage,
    api,
    apiPath,
    appPath,
    runner,
    stubs,
    seed,
  }, testInfo) => {
    test.setTimeout(240_000);
    const name = `Prepare mine ${testInfo.project.name}`;
    const admin = await api('admin');
    const alice = await api('alice');
    await deleteTriggers(admin, apiPath, [name]);
    await connectJira(alice, apiPath, seed, 'jira-token-alice');
    const suffix = Date.now() % 100_000;
    const mine = `SHOP-${200_000 + suffix}`;
    const bobs = `SHOP-${300_000 + suffix}`;
    try {
      const page = alicePage;
      await page.goto(appPath(`/triggers/new?agentId=${seed.agent.id}`));
      await fillRule(page, seed, name, 'ASSIGNED', 'Prepare what is assigned to me.');
      await choose(page, seed.people.alice.principalId);
      await chooseModel(page);
      await expect(page.locator('#trigger-team')).toHaveValue('');
      await expect(page.getByTestId('who-acts-who-pays')).toContainText('Jira: your own account');
      await expect(page.getByTestId('who-acts-who-pays')).toContainText('Your budget');
      await page.getByTestId('save-trigger').click();
      await expect(page).toHaveURL(/\/triggers\/[0-9a-f-]{36}$/);
      const triggerId = page.url().split('/').pop()!;

      // Someone assigns one issue to Alice and another to Bob, after the trigger was made.
      await stubs.putJiraIssue({
        key: mine,
        assignee: 'alice',
        summary: 'Checkout fails for Alice',
      });
      await stubs.putJiraIssue({ key: bobs, assignee: 'bob', summary: 'Refunds for Bob' });

      // The poll reads Jira with Alice's token and fires on hers only.
      const firing: Body = await eventually(
        'the firing on Alice’s issue',
        async () =>
          ((await alice.get(apiPath(`/triggers/${triggerId}/firings`))).items as Body[]).find(
            (f) => f.subjectKey === mine,
          ),
        150_000,
      );
      expect(firing).toMatchObject({ event: 'ASSIGNED', outcome: 'STARTED' });
      const searches = (await stubs.calls('jira')).filter((c) =>
        c.path.startsWith('/rest/api/2/search'),
      );
      expect(searches.some((c) => c.identity === 'alice')).toBe(true);
      const firings: Body[] = (await alice.get(apiPath(`/triggers/${triggerId}/firings`))).items;
      expect(firings.some((f) => f.subjectKey === bobs)).toBe(false);

      // Its run works and writes back with Alice's own account.
      const run = await runner.awaitRun({ conversationId: firing.conversationId });
      await runner.finish(run.runId, { summary: `${mine} is prepared.` });
      const written = await eventually('the write-back', async () =>
        (await stubs.jiraIssue(mine)).comments.find((c) => c.body.includes(`${mine} is prepared.`)),
      );
      expect(written.author).toBe('alice');

      await page.goto(appPath('/triggers'));
      await expect(page.getByTestId(`trigger-${triggerId}`)).toContainText(`on ${mine}`);
      await axeWhenStill(page, testInfo);
      await capture(page, testInfo, 'triggers-list');

      // Bob is neither its author nor in a team: not found.
      await bobPage.goto(appPath(`/triggers/${triggerId}`));
      await expect(
        bobPage.getByRole('heading', { level: 1, name: 'Page not found' }),
      ).toBeVisible();
    } finally {
      await deleteTriggers(admin, apiPath, [name]);
      await disconnectJira(alice, apiPath, seed);
    }
  });

  test('V9: an author who leaves pauses the trigger; an admin reassigns it and the new author accepts it @serial', async ({
    adminPage,
    api,
    apiPath,
    appPath,
    pageAs,
    stubs,
    seed,
  }, testInfo) => {
    const name = `Erin’s triage ${testInfo.project.name}`;
    const admin = await api('admin');
    await deleteTriggers(admin, apiPath, [name]);

    // Erin, a member of her own for this test, in Everyone and Analysts, with her own Jira account.
    const email = `erin.${testInfo.project.name}@acme.test`;
    const rootApi = await loginAs(root.email, root.password);
    const users: { id: string; email: string }[] = await rootApi.all('/api/admin/users');
    const erinId =
      users.find((u) => u.email === email)?.id ??
      (
        await rootApi.post('/api/admin/users', {
          email,
          displayName: `Erin ${testInfo.project.name}`,
          password,
          instanceAdmin: false,
        })
      ).id;
    const members: { userId: string }[] = await admin.all(apiPath('/members'));
    if (!members.some((m) => m.userId === erinId)) {
      await admin.post(apiPath('/members'), { email, role: 'MEMBER' });
    }
    // Erin alone, so that the projects that run this at the same moment all keep their Erin.
    for (const group of ['Everyone', 'Analysts']) {
      await admin.put(apiPath(`/groups/${seed.groups[group]}/members/${erinId}`));
    }
    const erin = await loginAs(email, password);
    await stubs.addIdentity(
      'jira',
      `jira-token-erin-${testInfo.project.name}`,
      `erin-${testInfo.project.name}`,
    );
    await connectJira(erin, apiPath, seed, `jira-token-erin-${testInfo.project.name}`);
    const erinPrincipal: string = (await erin.get(apiPath('/me/model-access'))).principalId;
    const erinJira = await jiraAccountOf(erin, apiPath, seed);
    const created: Body = await erin.post(apiPath('/triggers'), {
      name,
      source: 'TRACKER',
      systemId: seed.systems.jira.id,
      events: [{ event: 'ASSIGNED' }],
      subject: 'ISSUE',
      scope: 'SHOP',
      conditions: [],
      repeat: 'EVERY_TIME',
      agentId: seed.agent.id,
      agentVersion: (await erin.get(apiPath(`/agents/${seed.agent.id}`))).version,
      instruction: 'Triage what is assigned to me.',
      settings: { model: company.agent.model },
      accounts: [{ systemId: seed.systems.jira.id, accountId: erinJira }],
      payerPrincipalId: erinPrincipal,
    });
    try {
      // Erin leaves the company.
      await admin.delete(apiPath(`/members/${erinId}`));

      const page = adminPage;
      await page.goto(appPath('/triggers?status=PAUSED'));
      await expect(
        page.getByTestId(`trigger-${created.id}`).getByTestId('trigger-paused'),
      ).toHaveText('Paused: Its author left the company');
      await page.getByTestId(`trigger-${created.id}`).getByRole('link', { name }).click();
      const notice = page.getByTestId('paused-notice');
      await expect(notice).toContainText('An admin reassigns it to another member');
      await axeWhenStill(page, testInfo);
      await capture(page, testInfo, 'trigger-author-left');

      // Reassigned to Bob: svc-dev is not his to use, so it is not offered, and keeping the rows is refused.
      // An option is the holder and the account (rowKey in trigger-draft.ts).
      const svcDevJira = `${seed.serviceIdentity.id}:${seed.serviceIdentity.accounts.jira}`;
      await notice.getByRole('button', { name: 'Reassign it' }).click();
      const dialog = page.getByTestId('reassign-dialog');
      await dialog.locator('#reassign-author').selectOption(seed.people.bob.id);
      const jiraRow = dialog.locator(`#reassign-row-${seed.systems.jira.id}`);
      await expect(jiraRow.locator(`option[value="${svcDevJira}"]`)).toHaveCount(0);
      await dialog.getByTestId('confirm-reassign').click();
      await expect(dialog.getByTestId('reassign-error')).toBeVisible();
      await capture(page, testInfo, 'reassign-refused');

      // To Carol, in Analysts, who may: reassigned, and it stays paused until Carol accepts it (H2).
      await dialog.locator('#reassign-author').selectOption(seed.people.carol.id);
      await expect(jiraRow.locator(`option[value="${svcDevJira}"]`)).toHaveCount(1);
      await jiraRow.selectOption(svcDevJira);
      await dialog.locator('#reassign-payer').selectOption(seed.serviceIdentity.id);
      await dialog.getByTestId('confirm-reassign').click();
      await expect(dialog).toHaveCount(0);
      await expect(page.getByTestId('trigger-meta')).toContainText('by Carol');
      await expect(page.getByTestId('who-acts-who-pays')).toContainText(
        'Jira: svc-dev with svc-dev',
      );
      // The admin sees it wait for Carol and cannot resume it in her place; Carol accepts its table, its payer
      // and, where needed, its open events from her page, and it runs again.
      await expect(page.getByTestId('paused-notice')).toBeVisible();
      await expect(page.getByTestId('resume-trigger')).toHaveCount(0);
      await expect(page.getByTestId('accept-reassigned')).toHaveCount(0);
      const carolPage = await pageAs('carol');
      await carolPage.goto(appPath(`/triggers/${created.id}`));
      await expect(carolPage.getByTestId('accept-reassigned')).toBeVisible();
      if ((await carolPage.getByTestId('accept-ack-needed').count()) > 0) {
        await carolPage.locator('#trigger-accept-acknowledge').check();
      }
      await carolPage.getByTestId('accept-trigger').click();
      await expect(carolPage.getByTestId('paused-notice')).toHaveCount(0);
      await expect(carolPage.getByTestId('pause-trigger')).toBeVisible();
    } finally {
      await deleteTriggers(admin, apiPath, [name]);
      await admin.delete(apiPath(`/members/${erinId}`)).catch(() => undefined);
      await Promise.all([rootApi.dispose(), erin.dispose()]);
    }
  });
});

/** A person's own Jira account's id, from Accounts. */
async function jiraAccountOf(api: Api, path: Path, seed: SeedState): Promise<string | undefined> {
  const mine: { items: { system: { id: string }; account?: { id: string } | null }[] } =
    await api.get(path('/me/accounts'));
  return mine.items.find((i) => i.system.id === seed.systems.jira.id)?.account?.id;
}

/** Someone logged in through the API, in a request context of their own (no persona's cookies). */
async function loginAs(email: string, secret: string): Promise<Api> {
  const context = await request.newContext({
    baseURL: urls.server,
    storageState: { cookies: [], origins: [] },
  });
  const api = new Api(context, urls.server);
  await api.get('/api/auth/providers');
  api.me = await api.post('/api/auth/login', { email, password: secret });
  return api;
}
