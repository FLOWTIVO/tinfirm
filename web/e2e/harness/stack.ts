/**
 * `npm run e2e:stack`: brings up everything an e2e run talks to, and keeps it up until stopped (Ctrl+C, SIGTERM):
 *
 * 1. recreates the database (unless E2E_DB_KEEP=1), creating the two roles when they are missing;
 * 2. starts the fake LiteLLM, the Jira and GitHub stubs, and the control API with the scripted runner host;
 * 3. starts the real server from `server/` (its jar, built when missing) on E2E_SERVER_PORT, with the bundled gateway
 *    pointing at the fake LiteLLM and the outbound guard opened for the stubs on 127.0.0.1;
 * 4. serves the web app (E2E_WEB): `ng serve` with a proxy to the server (`serve`, the default), the production build
 *    served by the server itself (`built`), or nothing (`none`).
 *
 * `--seed` seeds once everything is up (as `npm run e2e:seed` does). Playwright starts this as its web server and
 * waits for the control API's `/health`. Logs go to e2e/.state/*.log.
 */
import { type ChildProcess, spawn, spawnSync } from 'node:child_process';
import { randomBytes } from 'node:crypto';
import {
  cpSync,
  createWriteStream,
  existsSync,
  mkdirSync,
  readdirSync,
  readFileSync,
  rmSync,
  statSync,
  writeFileSync,
} from 'node:fs';
import { join, resolve } from 'node:path';
import {
  company,
  database,
  litellmMasterKey,
  personas,
  ports,
  repoDir,
  root,
  runnerVersion,
  server,
  spareCompany,
  stateDir,
  urls,
  webDir,
  webMode,
} from './config.ts';
import { ControlServer } from './control.ts';
import { FakeLiteLlm } from './fake-litellm.ts';
import { sleep } from './http-util.ts';
import { ScriptedRunner } from './scripted-runner.ts';
import { SystemStubs } from './stubs.ts';

const children: ChildProcess[] = [];
let stopping = false;

function log(message: string): void {
  console.log(`[e2e:stack] ${message}`);
}

async function main(): Promise<void> {
  mkdirSync(stateDir, { recursive: true });
  await ensurePortsFree();
  recreateDatabase();

  const litellm = new FakeLiteLlm(litellmMasterKey);
  await litellm.start(ports.litellm);
  const stubs = new SystemStubs();
  await stubs.start(ports.stubs);
  for (const person of Object.values(personas)) {
    const name = person.email.split('@')[0];
    stubs.addIdentity('jira', person.tokens.jira, name);
    stubs.addIdentity('github', person.tokens.github, name);
  }
  stubs.addIdentity('jira', company.serviceIdentity.tokens.jira, company.serviceIdentity.name);
  stubs.addIdentity('github', company.serviceIdentity.tokens.github, company.serviceIdentity.name);
  const runner = new ScriptedRunner(urls.server, runnerVersion);
  let ready = false;
  const control = new ControlServer({
    runner,
    stubs,
    litellm,
    stateDir,
    urls: { ...urls },
    webMode,
    ready: () => ready,
  });
  await control.start(ports.control);
  log(`fakes up: LiteLLM ${urls.litellm}, stubs ${urls.stubs}, control ${urls.control}`);

  const shutdown = async (code: number) => {
    if (stopping) return;
    stopping = true;
    runner.disconnect();
    await Promise.all([...children.map(stop), control.stop(), litellm.stop(), stubs.stop()]);
    process.exit(code);
  };
  process.on('SIGINT', () => void shutdown(0));
  process.on('SIGTERM', () => void shutdown(0));

  try {
    const webRoot = webMode === 'built' ? prepareBuiltWeb() : null;
    await startServer(webRoot);
    if (webMode === 'serve') await startNgServe();
    ready = true;
    log(`ready: app ${urls.app}, API ${urls.server}, control ${urls.control}`);
    if (process.argv.includes('--seed')) {
      const { seed, printSeed } = await import('./seed.ts');
      printSeed(await seed());
    }
  } catch (error) {
    console.error(`[e2e:stack] ${(error as Error).message}`);
    await shutdown(1);
  }
}

async function ensurePortsFree(): Promise<void> {
  const wanted = [ports.server, ports.management, ports.control, ports.litellm, ports.stubs];
  if (webMode === 'serve') wanted.push(ports.web);
  for (const port of wanted) {
    const busy = await fetch(`http://127.0.0.1:${port}/`, { signal: AbortSignal.timeout(1000) })
      .then(() => true)
      .catch(
        (error: unknown) => !String((error as Error)?.cause ?? error).includes('ECONNREFUSED'),
      );
    if (busy) {
      throw new Error(
        `Port ${port} is in use: another e2e stack runs, or set the E2E_*_PORT variables (see e2e/README.md)`,
      );
    }
  }
}

function psql(sql: string, databaseName = 'postgres'): string {
  const result = spawnSync(
    database.psql,
    [
      '-h',
      database.host,
      '-p',
      String(database.port),
      '-U',
      database.superuser,
      '-d',
      databaseName,
      '-v',
      'ON_ERROR_STOP=1',
      '-tAc',
      sql,
    ],
    { env: { ...process.env, PGPASSWORD: database.superuserPassword }, encoding: 'utf8' },
  );
  if (result.error)
    throw new Error(`psql failed to run (${database.psql}): ${result.error.message}`);
  if (result.status !== 0) throw new Error(`psql failed: ${result.stderr.trim()}`);
  return result.stdout.trim();
}

function recreateDatabase(): void {
  const literal = (value: string) => `'${value.replace(/'/g, "''")}'`;
  for (const [role, secret] of [
    [database.owner, database.ownerPassword],
    [database.app, database.appPassword],
  ]) {
    if (psql(`SELECT 1 FROM pg_roles WHERE rolname = ${literal(role)}`) !== '1') {
      psql(`CREATE ROLE ${role} LOGIN PASSWORD ${literal(secret)}`);
      log(`created the role ${role}`);
    }
  }
  const exists =
    psql(`SELECT 1 FROM pg_database WHERE datname = ${literal(database.name)}`) === '1';
  if (exists && database.keep) {
    log(`keeping the database ${database.name} (E2E_DB_KEEP=1)`);
    return;
  }
  if (exists) psql(`DROP DATABASE "${database.name}" WITH (FORCE)`);
  psql(`CREATE DATABASE "${database.name}" OWNER ${database.owner}`);
  log(`database ${database.name} recreated on ${database.host}:${database.port}`);
}

/** The server jar: E2E_SERVER_JAR, else server/target/tinfirm-server-*.jar, built with ./mvnw when asked or missing. */
function serverJar(): string {
  if (server.jar) {
    if (!existsSync(server.jar)) throw new Error(`E2E_SERVER_JAR names no file: ${server.jar}`);
    return server.jar;
  }
  const target = resolve(repoDir, 'server/target');
  const find = () =>
    existsSync(target)
      ? readdirSync(target)
          .filter((name) => /^tinfirm-server-.*\.jar$/.test(name) && !name.endsWith('-plain.jar'))
          .map((name) => join(target, name))
          .sort((a, b) => statSync(b).mtimeMs - statSync(a).mtimeMs)[0]
      : undefined;
  let jar = find();
  if (server.build === 'always' || (!jar && server.build === 'missing')) {
    log('building the server jar (./mvnw -DskipTests package); E2E_SERVER_BUILD=never skips this');
    const build = spawnSync('./mvnw', ['-B', '-ntp', '-q', '-DskipTests', 'package'], {
      cwd: resolve(repoDir, 'server'),
      env: {
        ...process.env,
        JAVA_HOME: server.javaHome,
        PATH: `${server.javaHome}/bin:${process.env['PATH']}`,
      },
      stdio: ['ignore', 'inherit', 'inherit'],
    });
    if (build.status !== 0) throw new Error('The server build failed');
    jar = find();
  }
  if (!jar)
    throw new Error(
      'No server jar in server/target; build it, or set E2E_SERVER_JAR or E2E_SERVER_BUILD',
    );
  return jar;
}

/** E2E_WEB=built: the production build, as the server's `classpath:/static/` through a loader path. */
function prepareBuiltWeb(): string {
  const dist = resolve(process.env['E2E_WEB_DIST'] ?? resolve(webDir, 'dist/tinfirm-web/browser'));
  if (!existsSync(resolve(dist, 'index.html'))) {
    log('building the web app (npm run build)');
    const build = spawnSync('npm', ['run', 'build'], {
      cwd: webDir,
      stdio: ['ignore', 'inherit', 'inherit'],
    });
    if (build.status !== 0 || !existsSync(resolve(dist, 'index.html'))) {
      throw new Error('The web app does not build; fix it, or run with E2E_WEB=none');
    }
  }
  const webRoot = resolve(stateDir, 'web-root');
  rmSync(webRoot, { recursive: true, force: true });
  cpSync(dist, resolve(webRoot, 'static'), { recursive: true });
  return webRoot;
}

/**
 * The run's licence key pair (ADR-0077): a new Ed25519 pair made with ee/tools/LicenceKeyTool.java, its private key in
 * `.state/licence.pem`, encrypted with a passphrase of the run in `.state/licence.passphrase` (the tool reads it from
 * standard input), and its public key in `.state/licence.pub`, for the seed to sign the run's Enterprise key with. The
 * public key is returned for the server to trust (`TINFIRM_LICENCE_TEST_PUBLIC_KEY`, key id `e2e`, which the server
 * takes only under the `e2e` profile). FLOWTIVO's own keys are never involved, and no key is committed.
 */
function licenceTrust(): string {
  const privateKey = resolve(stateDir, 'licence.pem');
  const passphrase = resolve(stateDir, 'licence.passphrase');
  rmSync(privateKey, { force: true });
  writeFileSync(passphrase, randomBytes(24).toString('base64url'), { mode: 0o600 });
  const made = spawnSync(
    resolve(server.javaHome, 'bin/java'),
    [resolve(repoDir, 'ee/tools/LicenceKeyTool.java'), 'keygen', privateKey],
    { cwd: repoDir, encoding: 'utf8', input: `${readFileSync(passphrase, 'utf8')}\n` },
  );
  if (made.status !== 0) {
    throw new Error(`The licence key pair could not be made: ${made.stderr || made.error}`);
  }
  writeFileSync(resolve(stateDir, 'licence.pub'), made.stdout);
  return made.stdout;
}

async function startServer(webRoot: string | null): Promise<void> {
  const jar = serverJar();
  const java = resolve(server.javaHome, 'bin/java');
  const args = webRoot
    ? [
        `-Dloader.path=${webRoot}`,
        '-cp',
        jar,
        'org.springframework.boot.loader.launch.PropertiesLauncher',
      ]
    : ['-jar', jar];
  const env: NodeJS.ProcessEnv = {
    ...process.env,
    SERVER_PORT: String(ports.server),
    MANAGEMENT_SERVER_PORT: String(ports.management),
    SPRING_DATASOURCE_URL: `jdbc:postgresql://${database.host}:${database.port}/${database.name}`,
    SPRING_DATASOURCE_USERNAME: database.app,
    SPRING_DATASOURCE_PASSWORD: database.appPassword,
    SPRING_FLYWAY_USER: database.owner,
    SPRING_FLYWAY_PASSWORD: database.ownerPassword,
    TINFIRM_PUBLIC_URL: urls.app,
    TINFIRM_VAULT_KEYS: server.vaultKeys,
    TINFIRM_VAULT_ACTIVE_KEY: server.vaultActiveKey,
    TINFIRM_BOOTSTRAP_ADMIN_EMAIL: root.email,
    TINFIRM_BOOTSTRAP_ADMIN_PASSWORD: root.password,
    // The companies come from the deployment (ADR-0075); the root, the bootstrap admin, owns both.
    [`TINFIRM_COMPANIES_${company.key.toUpperCase()}_NAME`]: company.name,
    [`TINFIRM_COMPANIES_${spareCompany.key.toUpperCase()}_NAME`]: spareCompany.name,
    // The stubs speak http on 127.0.0.1 (CONVENTIONS §4: never in production).
    TINFIRM_CONNECTION_ALLOW_HTTP: 'true',
    TINFIRM_CONNECTION_ALLOW_PRIVATE_ADDRESSES: 'true',
    TINFIRM_CONNECTION_TIMEOUT: 'PT5S',
    // A spec reads an MCP server's catalogue twice in a row; the limiter (one a minute, M9) is off, as in the tests.
    TINFIRM_SYSTEM_CATALOGUE_REFRESH_INTERVAL: 'PT0S',
    // The bundled gateway is the fake LiteLLM.
    TINFIRM_LITELLM_ADMIN_URL: urls.litellm,
    TINFIRM_LITELLM_MASTER_KEY: litellmMasterKey,
    // Every persona logs in per run; the default per-IP throttle (30 a minute) is for people.
    TINFIRM_AUTH_LOGINTHROTTLE_PERIP_CAPACITY: '10000',
    TINFIRM_AUTH_LOGINTHROTTLE_PEREMAIL_CAPACITY: '1000',
    // A tool call gives up within a test's patience, and an idle stream gets its keep-alive soon (as in the tests).
    TINFIRM_RUN_TOOL_TIMEOUT: 'PT10S',
    TINFIRM_RUN_STREAM_KEEP_ALIVE: 'PT2S',
    TINFIRM_RUN_ACCEPT_TIMEOUT: 'PT5S',
    TINFIRM_RUN_REJECT_BACKOFF: 'PT2S',
    TINFIRM_RUN_DISPATCH_INTERVAL: 'PT1S',
    TINFIRM_MODEL_RECONCILE_INTERVAL: 'PT1H',
    TINFIRM_MODEL_RECONCILE_INITIAL_DELAY: 'PT1H',
    // Slack is the stub's (slack-stub.ts): the chat systems' fixed endpoints, for tests only (§15.16).
    TINFIRM_CHAT_ENDPOINTS_SLACKAPI: `${urls.stubs}/slack/api`,
    TINFIRM_CHAT_ENDPOINTS_SLACKFILES: `${urls.stubs}/slack/files`,
    // Triggers (W5): polls, schedules and the write-back look for work every two seconds, and a change is read
    // once it is three seconds old (beyond the stubs' answers), so that a spec sees a firing within a poll interval.
    TINFIRM_TRIGGER_POLL_TICK: 'PT2S',
    TINFIRM_TRIGGER_SCHEDULE_TICK: 'PT2S',
    TINFIRM_TRIGGER_SETTLE: 'PT3S',
    TINFIRM_TRIGGER_OUTBOX_TICK: 'PT2S',
    // The licence (ADR-0077): the server trusts the run's own key pair, and the seed enters an Enterprise key, so
    // that every paid feature is in force. Never outside the e2e profile.
    SPRING_PROFILES_ACTIVE: 'e2e',
    TINFIRM_LICENCE_TEST_PUBLIC_KEY: licenceTrust(),
  };
  const logFile = resolve(stateDir, 'server.log');
  const out = createWriteStream(logFile);
  log(`starting the server (${jar}) on ${urls.server}; log: ${logFile}`);
  const child = spawn(java, args, { env, stdio: ['ignore', 'pipe', 'pipe'] });
  children.push(child);
  child.stdout?.pipe(out);
  child.stderr?.pipe(out);
  let exited: number | null = null;
  child.on('exit', (code) => {
    exited = code ?? -1;
    if (!stopping) console.error(`[e2e:stack] the server exited (${code}); see ${logFile}`);
  });
  const deadline = Date.now() + server.startTimeoutMs;
  for (;;) {
    if (exited !== null)
      throw new Error(
        `The server exited with ${exited} while starting (${logFile}):\n${tail(logFile)}`,
      );
    const status = await fetch(`${urls.management}/actuator/health/readiness`, {
      signal: AbortSignal.timeout(2000),
    })
      .then((response) => response.status)
      .catch(() => 0);
    if (status === 200) return;
    if (Date.now() > deadline)
      throw new Error(
        `The server was not ready within ${server.startTimeoutMs} ms (E2E_SERVER_START_TIMEOUT_MS; ${logFile}):\n${tail(logFile)}`,
      );
    await sleep(500);
  }
}

/** `ng serve` on E2E_WEB_PORT with a proxy of /api, /oauth2 and /login/oauth2 to the server. */
async function startNgServe(): Promise<void> {
  const proxyFile = resolve(stateDir, 'proxy.conf.json');
  const target = { target: urls.server, secure: false, changeOrigin: false, logLevel: 'warn' };
  writeFileSync(
    proxyFile,
    JSON.stringify({ '/api': target, '/oauth2': target, '/login/oauth2': target }, null, 2),
  );
  const logFile = resolve(stateDir, 'web.log');
  const out = createWriteStream(logFile);
  log(`starting ng serve on ${urls.app}; log: ${logFile}`);
  // `npm start` would regenerate the API client first (prestart); so does this.
  const generate = spawnSync('npm', ['run', 'generate:api'], {
    cwd: webDir,
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  if (generate.status !== 0)
    throw new Error(`npm run generate:api failed:\n${generate.stderr?.toString()}`);
  const child = spawn(
    'npx',
    [
      'ng',
      'serve',
      '--host',
      '127.0.0.1',
      '--port',
      String(ports.web),
      '--proxy-config',
      proxyFile,
      '--no-hmr',
      '--no-live-reload',
    ],
    {
      cwd: webDir,
      env: { ...process.env, NG_CLI_ANALYTICS: 'false' },
      stdio: ['ignore', 'pipe', 'pipe'],
      detached: true,
    },
  );
  children.push(child);
  let output = '';
  const collect = (chunk: Buffer) => {
    output += chunk.toString();
    out.write(chunk);
  };
  child.stdout?.on('data', collect);
  child.stderr?.on('data', collect);
  let exited: number | null = null;
  child.on('exit', (code) => {
    exited = code ?? -1;
  });
  const deadline = Date.now() + 600_000;
  for (;;) {
    if (exited !== null) throw new Error(`ng serve exited with ${exited}:\n${tail(logFile)}`);
    if (output.includes('Application bundle generation failed')) {
      throw new Error(
        `The web app does not build, so ng serve serves nothing (see ${logFile}). Fix it, or run with E2E_WEB=none ` +
          'to run the specs that need no app page.',
      );
    }
    const status = await fetch(`${urls.app}/`, { signal: AbortSignal.timeout(2000) })
      .then((response) => response.status)
      .catch(() => 0);
    if (status === 200) return;
    if (Date.now() > deadline)
      throw new Error(`ng serve was not ready within 600 s:\n${tail(logFile)}`);
    await sleep(500);
  }
}

/**
 * Stops a child: SIGTERM, then SIGKILL after 15 s. A server stopped while it migrates the database may not exit on
 * SIGTERM alone, and must not outlive the stack holding its port.
 */
async function stop(child: ChildProcess): Promise<void> {
  if (child.exitCode !== null || child.signalCode !== null || child.pid === undefined) return;
  // ng serve runs detached in its own process group, so that its workers stop with it.
  const group = child.spawnargs.includes('serve');
  const signal = (name: NodeJS.Signals) => {
    try {
      if (group) process.kill(-child.pid!, name);
      else child.kill(name);
    } catch {
      // Gone already.
    }
  };
  const exited = new Promise<void>((resolvePromise) => child.once('exit', () => resolvePromise()));
  signal('SIGTERM');
  const timer = setTimeout(() => signal('SIGKILL'), 15_000);
  await exited;
  clearTimeout(timer);
}

function tail(file: string, lines = 8): string {
  try {
    return readFileSync(file, 'utf8').split('\n').slice(-lines).join('\n');
  } catch {
    return '(no log)';
  }
}

main().catch(async (error: unknown) => {
  console.error(`[e2e:stack] ${(error as Error).message}`);
  await Promise.all(children.map(stop));
  process.exit(1);
});
