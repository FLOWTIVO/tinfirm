/**
 * The e2e harness's configuration: ports, URLs, the database, the people and the test data, each overridable by an
 * environment variable. Every password, token and key in this file is a TEST FIXTURE for a throwaway database and
 * must never be used anywhere else.
 *
 * This module is loaded both by Playwright (the config, the setup project, the fixtures) and by Node directly
 * (`npm run e2e:stack`, `npm run e2e:seed`, with Node's type stripping), so it uses erasable TypeScript only and
 * resolves paths from the working directory, which npm and Playwright set to `web/`.
 */
import { existsSync } from 'node:fs';
import { resolve } from 'node:path';

const env = process.env;

function int(name: string, fallback: number): number {
  const value = env[name];
  if (value === undefined || value === '') return fallback;
  const parsed = Number.parseInt(value, 10);
  if (!Number.isInteger(parsed)) throw new Error(`${name} must be a whole number, not "${value}"`);
  return parsed;
}

/** `web/`: the directory of package.json and playwright.config.ts. */
export const webDir = (() => {
  const dir = resolve(env['E2E_WEB_DIR'] ?? process.cwd());
  if (!existsSync(resolve(dir, 'playwright.config.ts'))) {
    throw new Error(
      `Run the e2e harness from web/ (no playwright.config.ts in ${dir}); or set E2E_WEB_DIR`,
    );
  }
  return dir;
})();

export const repoDir = resolve(webDir, '..');

/** What the harness writes: logs, the seed's ids, the personas' sessions. Git-ignored. */
export const stateDir = resolve(env['E2E_STATE_DIR'] ?? resolve(webDir, 'e2e/.state'));

export const host = '127.0.0.1';

export const ports = {
  /** The real server (API, OIDC, the SPA when E2E_WEB=built). */
  server: int('E2E_SERVER_PORT', 18080),
  /** The server's management port (health). */
  management: int('E2E_MANAGEMENT_PORT', 18081),
  /** The harness's control API and status page. */
  control: int('E2E_CONTROL_PORT', 18090),
  /** The fake LiteLLM (admin API and model routes). */
  litellm: int('E2E_LITELLM_PORT', 18091),
  /** The stubs of Jira Data Center and GitHub. */
  stubs: int('E2E_STUBS_PORT', 18092),
  /** `ng serve` (E2E_WEB=serve). */
  web: int('E2E_WEB_PORT', 18082),
};

/** How the web app is served: `ng serve` with a proxy (default), the build served by the real server, or not at all. */
export type WebMode = 'serve' | 'built' | 'none';

export const webMode: WebMode = (() => {
  const mode = env['E2E_WEB'] ?? 'serve';
  if (mode !== 'serve' && mode !== 'built' && mode !== 'none') {
    throw new Error(`E2E_WEB must be serve, built or none, not "${mode}"`);
  }
  return mode;
})();

export const urls = {
  server: `http://${host}:${ports.server}`,
  management: `http://${host}:${ports.management}`,
  control: `http://${host}:${ports.control}`,
  litellm: `http://${host}:${ports.litellm}`,
  stubs: `http://${host}:${ports.stubs}`,
  /** Where the browser opens the app: ng serve's origin, or the server's. */
  app: webMode === 'serve' ? `http://${host}:${ports.web}` : `http://${host}:${ports.server}`,
};

/**
 * The database, recreated before every stack start unless E2E_DB_KEEP=1. The roles are created when missing, and an
 * existing role's password is never changed: other test suites on the same PostgreSQL use them.
 */
export const database = {
  host: env['E2E_DB_HOST'] ?? host,
  port: int('E2E_DB_PORT', 54417),
  name: env['E2E_DB_NAME'] ?? 'tinfirm_e2e',
  superuser: env['E2E_DB_SUPERUSER'] ?? 'postgres',
  superuserPassword: env['E2E_DB_SUPERUSER_PASSWORD'] ?? 'postgres',
  owner: 'tinfirm_owner',
  ownerPassword: env['E2E_DB_OWNER_PASSWORD'] ?? 'owner-test-password',
  app: 'tinfirm_app',
  appPassword: env['E2E_DB_APP_PASSWORD'] ?? 'app-test-password',
  keep: env['E2E_DB_KEEP'] === '1',
  psql: env['E2E_PSQL'] ?? 'psql',
};

/** The JDK of the server (Java 25): E2E_JAVA_HOME, else JAVA_HOME. */
function javaHome(): string {
  return env['E2E_JAVA_HOME'] || env['JAVA_HOME'] || '';
}

export const server = {
  /** The JDK whose `java` runs the server and whose home `./mvnw` builds it with. */
  javaHome: javaHome(),
  /** A server jar to run; default: `server/target/tinfirm-server-*.jar`, built when missing. */
  jar: env['E2E_SERVER_JAR'],
  /** `missing` (default) builds the jar only when there is none; `always` rebuilds it; `never` fails without one. */
  build: (env['E2E_SERVER_BUILD'] ?? 'missing') as 'missing' | 'always' | 'never',
  /** TEST FIXTURE: the vault key of the throwaway database. */
  vaultKeys: 'e2e:WnwifZ37C4G+i97uH6/IOwdQT5n7e3MzAK/ysFhSCKk=',
  vaultActiveKey: 'e2e',
  startTimeoutMs: int('E2E_SERVER_START_TIMEOUT_MS', 600_000),
};

/** TEST FIXTURE: the master key the fake LiteLLM expects, which the server holds as the bundled gateway's. */
export const litellmMasterKey = 'sk-e2e-master-key-fixture';

/** TEST FIXTURE: the password of every seeded person. */
export const password = env['E2E_PASSWORD'] ?? 'e2e-password-123';

/** The bootstrap instance admin: creates the people and the company, then leaves the company to its Owner. */
export const root = {
  email: env['E2E_ROOT_EMAIL'] ?? 'root@tinfirm.test',
  /** TEST FIXTURE; the server wants at least 12 characters. */
  password: env['E2E_ROOT_PASSWORD'] ?? 'e2e-root-password-1',
  displayName: 'Root',
};

export type Role = 'OWNER' | 'ADMIN' | 'MEMBER';

export interface PersonaSpec {
  email: string;
  displayName: string;
  role: Role;
  /** Groups by name. */
  groups: string[];
  /** The tokens the stubs know as this person ("who am I"), per system; connected as their own when `connect` is set. */
  tokens: { jira: string; github: string };
  /** Systems on which the seed connects the person's own account. */
  connect: ('jira' | 'github')[];
}

/**
 * The seeded people. Owner, Admin, Alice and Bob are the personas of the slices; Carol and Dave are the plan's
 * people for V5 (in Analysts, no GitHub account of her own) and V6 (no account, connects mid-conversation).
 */
export const personas = {
  owner: {
    email: 'olivia@acme.test',
    displayName: 'Olivia Owner',
    role: 'OWNER',
    groups: ['Everyone'],
    tokens: { jira: 'jira-token-olivia', github: 'gh-token-olivia' },
    connect: [],
  },
  admin: {
    email: 'adam@acme.test',
    displayName: 'Adam Admin',
    role: 'ADMIN',
    groups: ['Everyone'],
    tokens: { jira: 'jira-token-adam', github: 'gh-token-adam' },
    connect: [],
  },
  alice: {
    email: 'alice@acme.test',
    displayName: 'Alice Analyst',
    role: 'MEMBER',
    groups: ['Everyone', 'Analysts'],
    tokens: { jira: 'jira-token-alice', github: 'gh-token-alice' },
    connect: ['github'],
  },
  bob: {
    email: 'bob@acme.test',
    displayName: 'Bob Builder',
    role: 'MEMBER',
    groups: ['Everyone'],
    tokens: { jira: 'jira-token-bob', github: 'gh-token-bob' },
    connect: ['github'],
  },
  carol: {
    email: 'carol@acme.test',
    displayName: 'Carol Clerk',
    role: 'MEMBER',
    groups: ['Everyone', 'Analysts'],
    tokens: { jira: 'jira-token-carol', github: 'gh-token-carol' },
    connect: [],
  },
  dave: {
    email: 'dave@acme.test',
    displayName: 'Dave Developer',
    role: 'MEMBER',
    groups: ['Everyone'],
    tokens: { jira: 'jira-token-dave', github: 'gh-token-dave' },
    connect: [],
  },
} satisfies Record<string, PersonaSpec>;

export type PersonaName = keyof typeof personas;

export const personaNames = Object.keys(personas) as PersonaName[];

/** The company and what the seed puts in it. */
export const company = {
  /**
   * The key the stack declares it by (`TINFIRM_COMPANIES_ACME_*`, ADR-0075): the server creates it
   * at its start with the root, the bootstrap admin, as OWNER.
   */
  key: 'acme',
  name: env['E2E_COMPANY_NAME'] ?? 'Acme',
  groups: {
    Everyone: 'Everyone in Acme',
    Analysts: 'The analysts: they may read Jira and GitHub as svc-dev',
  } as Record<string, string>,
  /** Tinfirm makes each handle from the name (`jira`, `github`), never the seed (CONVENTIONS §15.2). */
  systems: {
    jira: { name: 'Jira', kind: 'JIRA_DC', path: '/jira' },
    github: { name: 'GitHub', kind: 'GITHUB', path: '/github' },
  },
  /** A repository on the GitHub stub's host; never cloned (the fake runner clones nothing). */
  repository: { url: `https://${host}/acme/shop`, defaultBranch: 'main', name: 'acme/shop' },
  serviceIdentity: {
    name: 'svc-dev',
    description: 'The development service identity',
    /** TEST FIXTURE tokens the stubs know as svc-dev. */
    tokens: { jira: 'jira-token-svc-dev', github: 'gh-token-svc-dev' },
    /** Its accounts are lent to Analysts, read-only. */
    grantTo: 'Analysts',
    access: 'READ' as const,
  },
  gateway: {
    defaultBudgetUsd: 50,
    budgetPeriod: '30d',
    defaultModels: [] as string[],
  },
  runnerHost: { name: 'e2e-runner', scope: 'COMPANY' as const },
  agent: {
    name: 'Analyst',
    handle: 'analyst',
    description: 'Reads Jira issues and works in the shop repository',
    instructions: 'You help the team of Acme with Jira issues and the shop repository. Be brief.',
    runtimes: ['CLAUDE_CODE'],
    /** Each person's settings name it: a budget pays only for a model the gateway serves (`MODEL_REQUIRED`). */
    model: 'claude-sonnet-4-5',
    /** Shared with every member. */
    shareWith: ['Everyone'],
    repositoryAccess: 'WRITE' as const,
  },
};

/**
 * A second company the stack declares and nothing sets up, owned by the root as the bootstrap admin:
 * for what a company shows before its model access is set up.
 */
export const spareCompany = {
  key: 'beta',
  name: env['E2E_SPARE_COMPANY_NAME'] ?? 'Beta',
};

/** The runner's version in hello: 0.2.0 is the first a budget's run is sent to (CONVENTIONS §15.4). */
export const runnerVersion = '0.2.0';
