/**
 * The seed (`npm run e2e:seed`, and the `seed` setup project of every Playwright run): the test data of the plan's
 * scenarios, made through the real API as the people who would make it, and idempotent, so that it can run again
 * against a stack that is up. It never resets anything; the stack recreates the database.
 *
 * - The bootstrap instance admin (root) enters an Enterprise licence key signed with the run's key pair (ADR-0077),
 *   so that every paid feature is in force; then creates the people, makes Olivia the OWNER of the declared company
 *   (ADR-0075), which the server created at its start with the root as OWNER, and leaves it. Adam is ADMIN; Alice, Bob, Carol and Dave are members.
 * - Adam: the groups Everyone and Analysts; the systems Jira (Data Center) and GitHub on the stubs, with the
 *   repository acme/shop; on both, a credential named svc-dev (ADR-0072), lent to Analysts read-only and to the
 *   service account (service identity) svc-dev;
 *   the model gateway (the bundled one, the fake LiteLLM, tested); a company runner host, to which the scripted runner
 *   connects; the agent Analyst, with Jira's and GitHub's tools and acme/shop, shared with Everyone.
 * - Alice and Bob connect their own GitHub accounts; Carol and Dave hold none (V5, V6).
 *
 * It writes what it made to e2e/.state/seed.json and the people's sessions to e2e/.state/auth/<persona>.json.
 */
import { spawnSync } from 'node:child_process';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { Api, ApiError } from './api.ts';
import {
  company,
  password,
  type PersonaName,
  personaNames,
  personas,
  repoDir,
  root,
  server,
  spareCompany,
  stateDir,
  urls,
} from './config.ts';
import { RunnerControl } from './control-client.ts';
import { waitFor } from './http-util.ts';

export interface SeededPerson {
  id: string;
  email: string;
  displayName: string;
  role: string;
  principalId: string;
  groups: string[];
  /** The person's own accounts by system key. */
  accounts: Partial<Record<'jira' | 'github', string>>;
  /** Playwright storage state of the person's session. */
  storageState: string;
}

export interface SeedState {
  seededAt: string;
  urls: typeof urls;
  password: string;
  companyId: string;
  companyName: string;
  root: { id: string; email: string };
  people: Record<PersonaName, SeededPerson>;
  groups: Record<string, string>;
  systems: Record<'jira' | 'github', { id: string; name: string; handle: string; baseUrl: string }>;
  repository: { id: string; url: string; name: string };
  serviceIdentity: {
    id: string;
    name: string;
    /** The systems' credentials it borrows, named after it, by system key (ADR-0072). */
    accounts: Record<'jira' | 'github', string>;
    grants: Record<'jira' | 'github', string>;
  };
  agent: { id: string; name: string; handle: string };
  runnerHost: { id: string; name: string };
  gateway: { relayEnabled: boolean; defaultBudgetUsd: number };
  report: { what: string; action: 'created' | 'kept' | 'changed' }[];
}

export const seedFile = resolve(stateDir, 'seed.json');

export function storageStateFile(persona: PersonaName): string {
  return resolve(stateDir, 'auth', `${persona}.json`);
}

type Report = SeedState['report'];

function note(report: Report, what: string, action: Report[number]['action']): void {
  report.push({ what, action });
}

/** Seeds the stack at `urls.server` and answers what is there. */
/** The id of the run's Enterprise licence, which the seed signs with the stack's key pair. */
const LICENCE_ID = 'e2e';

/**
 * The run's licence revision: past the one the server holds, and never below the ten minutes since 2026, so that a later
 * run's key is the later revision even when the database is kept (E2E_DB_KEEP) and the key it holds is no longer
 * shown. Within the server's most of 1,000,000 until 2045.
 */
function revision(held: number | undefined): number {
  return Math.max((held ?? 0) + 1, 1 + Math.floor((Date.now() - Date.UTC(2026, 0, 1)) / 600_000));
}

/**
 * Enters an Enterprise key, without seats or a cap of companies, signed with the run's private key under the key id
 * `e2e` (`.state/licence.pem`, its passphrase and its public key, which the stack made), unless one of this licence is
 * in force. A key of an earlier run is signed by a key pair the server no longer trusts, so it is not in force.
 */
async function ensureEnterpriseLicence(rootApi: Api, report: Report): Promise<void> {
  const held = await rootApi.get<{ current?: { licenceId: string; rev: number; edition: string } }>(
    '/api/admin/licence',
  );
  if (held.current?.licenceId === LICENCE_ID && held.current.edition === 'ENTERPRISE') {
    note(report, 'licence (Enterprise)', 'kept');
    return;
  }
  const day = (offset: number) =>
    new Date(Date.now() + offset * 86_400_000).toISOString().slice(0, 10);
  const signed = spawnSync(
    resolve(server.javaHome, 'bin/java'),
    [
      resolve(repoDir, 'ee/tools/LicenceKeyTool.java'),
      'sign',
      '--private-key',
      resolve(stateDir, 'licence.pem'),
      '--key-id',
      'e2e',
      '--public-key',
      resolve(stateDir, 'licence.pub'),
      '--id',
      LICENCE_ID,
      '--rev',
      String(revision(held.current?.licenceId === LICENCE_ID ? held.current.rev : undefined)),
      '--customer',
      'E2E',
      '--edition',
      'ENTERPRISE',
      '--starts',
      day(-1),
      '--ends',
      day(365),
    ],
    {
      cwd: repoDir,
      encoding: 'utf8',
      input: `${readFileSync(resolve(stateDir, 'licence.passphrase'), 'utf8')}\n`,
    },
  );
  if (signed.status !== 0) {
    throw new Error(`The e2e licence key could not be signed: ${signed.stderr || signed.error}`);
  }
  await rootApi.put('/api/admin/licence', { key: signed.stdout.trim() });
  note(report, 'licence (Enterprise)', 'created');
  // Under Community the server waits to create the declared companies beyond the first; it creates them once the
  // key is in force (ADR-0077: several companies are Enterprise's).
  await waitFor(`the declared company ${spareCompany.key}`, async () =>
    (await rootApi.all<{ key?: string | null }>('/api/companies')).some(
      (entry) => entry.key === spareCompany.key,
    )
      ? true
      : undefined,
  );
}

export async function seed(): Promise<SeedState> {
  const report: Report = [];
  const control = new RunnerControl(urls.control);
  const apis: Api[] = [];
  const login = async (email: string, secret: string) => {
    const api = await Api.login(urls.server, email, secret);
    apis.push(api);
    return api;
  };
  try {
    let rootApi: Api;
    try {
      rootApi = await login(root.email, root.password);
    } catch (error) {
      throw new Error(
        `The root cannot log in at ${urls.server}; is the stack up (npm run e2e:stack)? ${String(error)}`,
        {
          cause: error,
        },
      );
    }
    const rootMe = rootApi.me!;

    // ---- the licence: Enterprise, so that every paid feature is in force (ADR-0077) ----
    await ensureEnterpriseLicence(rootApi, report);

    // ---- people ----
    const users = await rootApi.all<{ id: string; email: string }>('/api/admin/users');
    const userIds = {} as Record<PersonaName, string>;
    for (const name of personaNames) {
      const person = personas[name];
      const found = users.find((user) => user.email.toLowerCase() === person.email);
      if (found) {
        userIds[name] = found.id;
        note(report, `user ${person.email}`, 'kept');
      } else {
        const created = await rootApi.post('/api/admin/users', {
          email: person.email,
          displayName: person.displayName,
          password,
          instanceAdmin: false,
        });
        userIds[name] = created.id;
        note(report, `user ${person.email}`, 'created');
      }
    }

    // ---- the company: the declared one, handed to Olivia ----
    const findCompany = async (api: Api) =>
      (await api.all<{ id: string; name: string }>('/api/companies')).find(
        (entry) => entry.name === company.name,
      );
    let manager: Api = rootApi;
    let companyId = (await findCompany(rootApi))?.id;
    let rootIsMember = companyId !== undefined;
    if (companyId) {
      const memberIds = (
        await rootApi.all<{ userId: string }>(`/api/companies/${companyId}/members`)
      ).map((m) => m.userId);
      rootIsMember = memberIds.includes(rootMe.id);
    }
    if (!rootIsMember) {
      const ownerApi = await login(personas.owner.email, password).catch(() => null);
      const owned = ownerApi ? await findCompany(ownerApi) : undefined;
      if (owned && ownerApi) {
        companyId = owned.id;
        manager = ownerApi;
      }
    }
    // Companies come from the deployment (ADR-0075): the stack declares this one.
    if (!companyId)
      throw new Error(
        `No company ${company.name} that the root or ${personas.owner.email} can manage: declare it with TINFIRM_COMPANIES_${company.key.toUpperCase()}_NAME`,
      );
    const path = (suffix: string) => `/api/companies/${companyId}${suffix}`;
    note(report, `company ${company.name}`, 'kept');

    const members = await manager.all<{ userId: string; role: string }>(path('/members'));
    for (const name of personaNames) {
      const person = personas[name];
      const member = members.find((entry) => entry.userId === userIds[name]);
      if (!member) {
        await manager.post(path('/members'), { email: person.email, role: person.role });
        note(report, `member ${person.email} (${person.role})`, 'created');
      } else if (member.role !== person.role) {
        await manager.patch(path(`/members/${userIds[name]}`), { role: person.role });
        note(report, `member ${person.email} (${person.role})`, 'changed');
      } else {
        note(report, `member ${person.email} (${person.role})`, 'kept');
      }
    }
    if (rootIsMember) {
      // The root leaves: the company belongs to its Owner, and the root stays an instance admin only.
      await rootApi.delete(path(`/members/${rootMe.id}`));
      note(report, `the root left ${company.name}`, 'changed');
    }

    // ---- the people's sessions ----
    const as = {} as Record<PersonaName, Api>;
    for (const name of personaNames) as[name] = await login(personas[name].email, password);
    const admin = as.admin;

    // ---- groups ----
    const groups = await admin.all<{ id: string; name: string }>(path('/groups'));
    const groupIds: Record<string, string> = {};
    for (const [groupName, description] of Object.entries(company.groups)) {
      const memberIds = personaNames
        .filter((name) => (personas[name].groups as string[]).includes(groupName))
        .map((name) => userIds[name]);
      const found = groups.find((group) => group.name === groupName);
      if (!found) {
        groupIds[groupName] = (
          await admin.post(path('/groups'), { name: groupName, description, memberIds })
        ).id;
        note(report, `group ${groupName}`, 'created');
        continue;
      }
      groupIds[groupName] = found.id;
      const group = await admin.get<{ members: { userId: string }[] }>(path(`/groups/${found.id}`));
      const have = group.members.map((member) => member.userId).sort();
      if (JSON.stringify(have) !== JSON.stringify([...memberIds].sort())) {
        await admin.put(path(`/groups/${found.id}`), { name: groupName, description, memberIds });
        note(report, `group ${groupName}`, 'changed');
      } else {
        note(report, `group ${groupName}`, 'kept');
      }
    }

    // ---- systems on the stubs ----
    const systems = {} as SeedState['systems'];
    const existingSystems = await admin.all<{
      id: string;
      name: string;
      handle: string;
      baseUrl?: string;
    }>(path('/systems'));
    for (const key of ['jira', 'github'] as const) {
      const spec = company.systems[key];
      const baseUrl = `${urls.stubs}${spec.path}`;
      let found = existingSystems.find((system) => system.name === spec.name);
      if (found && found.baseUrl !== baseUrl) {
        // The kind and base URL never change (CONVENTIONS §15.2): a moved stub needs the system made again.
        await admin.delete(path(`/systems/${found.id}`));
        note(report, `system ${spec.name} at its old address`, 'changed');
        found = undefined;
      }
      if (found) {
        note(report, `system ${spec.name}`, 'kept');
      } else {
        found = await admin.post(path('/systems'), {
          name: spec.name,
          kind: spec.kind,
          baseUrl,
        });
        note(report, `system ${spec.name} (${spec.kind}) at ${baseUrl}`, 'created');
      }
      systems[key] = { id: found!.id, name: spec.name, handle: found!.handle, baseUrl };
    }
    const repositories = await admin.all<{ id: string; url: string; name?: string }>(
      path(`/systems/${systems.github.id}/repositories`),
    );
    let repository = repositories.find((entry) => entry.url === company.repository.url);
    if (repository) {
      note(report, `repository ${company.repository.name}`, 'kept');
    } else {
      repository = await admin.post(path(`/systems/${systems.github.id}/repositories`), {
        url: company.repository.url,
        defaultBranch: company.repository.defaultBranch,
      });
      note(report, `repository ${company.repository.name}`, 'created');
    }

    // ---- the service account, the systems' credentials it borrows and their grants (ADR-0072) ----
    const spec = company.serviceIdentity;
    const identities = await admin.all<{ id: string; name: string; userGroups?: { id: string }[] }>(
      path('/service-identities'),
    );
    let identity = identities.find((entry) => entry.name === spec.name);
    if (identity) {
      note(report, `service identity ${spec.name}`, 'kept');
    } else {
      identity = await admin.post(path('/service-identities'), {
        name: spec.name,
        description: spec.description,
      });
      note(report, `service identity ${spec.name}`, 'created');
    }
    await admin.put(path(`/service-identities/${identity!.id}/users`), {
      groupIds: [groupIds[spec.grantTo]],
    });
    const serviceAccounts = {} as Record<'jira' | 'github', string>;
    for (const key of ['jira', 'github'] as const) {
      const credentials = (
        await admin.get<{
          items: {
            id: string;
            name: string;
            status: string;
            serviceAccounts: { serviceIdentityId: string }[];
          }[];
        }>(path(`/systems/${systems[key].id}/credentials`))
      ).items;
      const what = `${systems[key].name}'s credential ${spec.name}`;
      const credential = credentials.find(
        (entry) => entry.name.toLowerCase() === spec.name.toLowerCase(),
      );
      if (!credential) {
        serviceAccounts[key] = (
          await admin.post(path(`/systems/${systems[key].id}/credentials`), {
            name: spec.name,
            token: spec.tokens[key],
          })
        ).id;
        note(report, what, 'created');
      } else if (credential.status !== 'OK') {
        serviceAccounts[key] = (
          await admin.put(path(`/systems/${systems[key].id}/credentials/${credential.id}/token`), {
            token: spec.tokens[key],
          })
        ).id;
        note(report, what, 'changed');
      } else {
        serviceAccounts[key] = credential.id;
        note(report, what, 'kept');
      }
      const lent = credential?.serviceAccounts.some(
        (lending) => lending.serviceIdentityId === identity!.id,
      );
      if (!lent) {
        await admin.put(
          path(
            `/systems/${systems[key].id}/credentials/${serviceAccounts[key]}/service-accounts/${identity!.id}`,
          ),
          { access: 'WRITE' },
        );
      }
      note(report, `${what} lent to ${spec.name}`, lent ? 'kept' : 'created');
    }
    const grants = await admin.all<{
      id: string;
      credentialId: string;
      groupId: string;
      access: string;
    }>(path('/grants'));
    const grantIds = {} as Record<'jira' | 'github', string>;
    for (const key of ['jira', 'github'] as const) {
      const found = grants.find(
        (grant) =>
          grant.credentialId === serviceAccounts[key] && grant.groupId === groupIds[spec.grantTo],
      );
      const what = `grant of ${systems[key].name}'s credential ${spec.name} to ${spec.grantTo} (${spec.access})`;
      if (!found) {
        grantIds[key] = (
          await admin.post(path('/grants'), {
            credentialId: serviceAccounts[key],
            groupId: groupIds[spec.grantTo],
            access: spec.access,
          })
        ).id;
        note(report, what, 'created');
      } else {
        grantIds[key] = found.id;
        if (found.access !== spec.access) {
          await admin.patch(path(`/grants/${found.id}`), { access: spec.access });
          note(report, what, 'changed');
        } else {
          note(report, what, 'kept');
        }
      }
    }

    // ---- the people's own accounts ----
    const ownAccounts = {} as Record<PersonaName, SeededPerson['accounts']>;
    for (const name of personaNames) {
      ownAccounts[name] = {};
      const mine = (
        await as[name].get<{
          items: { system: { id: string }; account?: { id: string; status: string } | null }[];
        }>(path('/me/accounts'))
      ).items;
      for (const key of personas[name].connect as ('jira' | 'github')[]) {
        const entry = mine.find((item) => item.system.id === systems[key].id);
        if (entry?.account?.status === 'OK') {
          ownAccounts[name][key] = entry.account.id;
          note(report, `${name}'s own ${systems[key].name} account`, 'kept');
        } else {
          ownAccounts[name][key] = (
            await as[name].put(path(`/me/accounts/${systems[key].id}/token`), {
              token: personas[name].tokens[key],
            })
          ).id;
          note(report, `${name}'s own ${systems[key].name} account`, 'created');
        }
      }
      // An account a spec connected for someone who holds none in the seed (V6: Dave) goes again.
      for (const key of ['jira', 'github'] as const) {
        const connect = personas[name].connect as ('jira' | 'github')[];
        const entry = mine.find((item) => item.system.id === systems[key].id);
        if (!connect.includes(key) && entry?.account) {
          await as[name].delete(path(`/me/accounts/${systems[key].id}`));
          note(report, `${name}'s own ${systems[key].name} account, disconnected`, 'changed');
        }
      }
    }

    // ---- model access: the bundled gateway (the fake LiteLLM), tested ----
    const settings = await admin.get<{
      configured: boolean;
      gateway?: string;
      relayEnabled: boolean;
      defaultBudgetUsd?: number;
    }>(path('/model-settings'));
    if (settings.configured && settings.gateway === 'BUNDLED' && settings.relayEnabled) {
      note(report, 'model gateway (bundled, tested)', 'kept');
    } else {
      await admin.put(path('/model-settings'), {
        gateway: 'BUNDLED',
        defaultBudgetUsd: company.gateway.defaultBudgetUsd,
        budgetPeriod: company.gateway.budgetPeriod,
        defaultModels: company.gateway.defaultModels,
      });
      await admin.post(path('/model-settings/test'));
      note(
        report,
        `model gateway (bundled at ${urls.litellm}, $${company.gateway.defaultBudgetUsd} per ${company.gateway.budgetPeriod})`,
        'created',
      );
    }
    const principals = {} as Record<PersonaName, string>;
    for (const name of personaNames) {
      const access = await waitFor(
        `${name}'s model key`,
        async () => {
          const view = await as[name].get<{ principalId: string; keyStatus?: string }>(
            path('/me/model-access'),
          );
          return view.keyStatus === 'OK' ? view : undefined;
        },
        30_000,
      );
      principals[name] = access.principalId;
    }

    // ---- a company runner host, with the scripted runner connected ----
    const hosts = await admin.all<{ id: string; name: string; scope: string }>(
      path('/runner-hosts'),
    );
    let runnerHost = hosts.find(
      (entry) => entry.name === company.runnerHost.name && entry.scope === 'COMPANY',
    );
    const runner = await control.status();
    if (runnerHost && runner.connected && runner.runnerHostId === runnerHost.id) {
      note(report, `runner host ${company.runnerHost.name} (connected)`, 'kept');
    } else {
      const had = runnerHost !== undefined;
      if (runnerHost) {
        // Its token was shown once and is gone with the stack that held it: register the host again.
        await admin.delete(path(`/runner-hosts/${runnerHost.id}`));
      }
      const registered = await admin.post<{ runnerHost: { id: string }; token: string }>(
        path('/runner-hosts'),
        {
          name: company.runnerHost.name,
          scope: company.runnerHost.scope,
        },
      );
      runnerHost = {
        id: registered.runnerHost.id,
        name: company.runnerHost.name,
        scope: company.runnerHost.scope,
      };
      await control.connect(registered.token);
      note(
        report,
        `runner host ${company.runnerHost.name} (COMPANY), the scripted runner connected`,
        had ? 'changed' : 'created',
      );
    }
    await waitFor(
      'the runner host online and configured',
      async () => {
        const view = (
          await admin.all<{
            id: string;
            online: boolean;
            configPending: boolean;
          }>(path('/runner-hosts'))
        ).find((entry) => entry.id === runnerHost!.id);
        return view?.online && !view.configPending ? view : undefined;
      },
      30_000,
    );

    // ---- the agent, shared with everyone ----
    const agentSpec = company.agent;
    const agents = await admin.all<{ id: string; name: string; handle: string }>(path('/agents'));
    let agent = agents.find((entry) => entry.name === agentSpec.name);
    const shareGroupIds = agentSpec.shareWith.map((groupName) => groupIds[groupName]);
    if (!agent) {
      agent = await admin.post(path('/agents'), {
        name: agentSpec.name,
        handle: agentSpec.handle,
        description: agentSpec.description,
        instructions: agentSpec.instructions,
        runtimes: agentSpec.runtimes,
        systems: [{ systemId: systems.jira.id }, { systemId: systems.github.id }],
        repositories: [{ repositoryId: repository!.id, access: agentSpec.repositoryAccess }],
        shareGroupIds,
      });
      note(
        report,
        `agent ${agentSpec.name} (@${agentSpec.handle}) shared with ${agentSpec.shareWith.join(', ')}`,
        'created',
      );
    } else {
      await admin.put(path(`/agents/${agent.id}/shares`), { groupIds: shareGroupIds });
      note(report, `agent ${agentSpec.name}`, 'kept');
    }

    // ---- each person's settings of the agent name a model: a budget pays for nothing else ----
    for (const name of personaNames) {
      const settingsPath = path(`/agents/${agent!.id}/settings/me`);
      const mine = await as[name]
        .get<{ model?: string | null }>(settingsPath)
        .catch((error: unknown) => {
          if (error instanceof ApiError && (error.status === 403 || error.status === 404))
            return null;
          throw error;
        });
      if (!mine) continue;
      if (mine.model) {
        note(report, `${name}'s model for ${agentSpec.name}`, 'kept');
      } else {
        await as[name].put(settingsPath, { model: agentSpec.model });
        note(report, `${name}'s model for ${agentSpec.name} (${agentSpec.model})`, 'changed');
      }
    }

    // ---- what a member sees: the agent is usable, paid and runnable ----
    const readiness = await as.alice.get<{
      payer: { state: string; reason?: string };
      hosts: { runnable: boolean; reason?: string };
    }>(path(`/agents/${agent!.id}/readiness`));
    if (readiness.payer.state !== 'READY' || !readiness.hosts.runnable) {
      throw new Error(`The agent is not ready for Alice: ${JSON.stringify(readiness)}`);
    }

    // ---- sessions for the browser ----
    const people = {} as Record<PersonaName, SeededPerson>;
    mkdirSync(resolve(stateDir, 'auth'), { recursive: true });
    for (const name of personaNames) {
      const file = storageStateFile(name);
      await as[name].saveStorageState(file);
      people[name] = {
        id: userIds[name],
        email: personas[name].email,
        displayName: personas[name].displayName,
        role: personas[name].role,
        principalId: principals[name],
        groups: [...personas[name].groups],
        accounts: ownAccounts[name],
        storageState: file,
      };
    }

    const state: SeedState = {
      seededAt: new Date().toISOString(),
      urls,
      password,
      companyId,
      companyName: company.name,
      root: { id: rootMe.id, email: root.email },
      people,
      groups: groupIds,
      systems,
      repository: {
        id: repository!.id,
        url: company.repository.url,
        name: company.repository.name,
      },
      serviceIdentity: {
        id: identity!.id,
        name: spec.name,
        accounts: serviceAccounts,
        grants: grantIds,
      },
      agent: { id: agent!.id, name: agentSpec.name, handle: agentSpec.handle },
      runnerHost: { id: runnerHost!.id, name: company.runnerHost.name },
      gateway: { relayEnabled: true, defaultBudgetUsd: company.gateway.defaultBudgetUsd },
      report,
    };
    mkdirSync(stateDir, { recursive: true });
    writeFileSync(seedFile, JSON.stringify(state, null, 2));
    return state;
  } catch (error) {
    if (error instanceof ApiError)
      throw new Error(`The seed failed: ${error.message}`, { cause: error });
    throw error;
  } finally {
    await Promise.all(apis.map((api) => api.dispose()));
  }
}

/** Prints what the seed made, and how to log in. */
export function printSeed(state: SeedState): void {
  const lines = [
    `Seeded ${state.companyName} (${state.companyId}) at ${state.urls.server}; app ${state.urls.app}`,
    '',
    ...state.report.map((entry) => `  ${entry.action.padEnd(8)} ${entry.what}`),
    '',
    `People (password ${state.password}; root ${state.root.email} is the instance admin):`,
    ...Object.entries(state.people).map(
      ([name, person]) =>
        `  ${name.padEnd(6)} ${person.email.padEnd(18)} ${person.role.padEnd(7)} ${person.groups.join(', ')}`,
    ),
    '',
    `Agent ${state.agent.name} (${state.agent.id}); runner host ${state.runnerHost.name} (${state.runnerHost.id})`,
    `Written to ${seedFile}`,
  ];
  console.log(lines.join('\n'));
}
