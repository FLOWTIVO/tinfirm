// The licence check of the packages the web app ships (ADR-0082): every production dependency
// (`npm query ".prod"`), not the build tools. It fails on a package whose licence the policy does
// not accept, or that names none, and lists them. Run after `npm ci`: `npm run licences`.
//
// The policy is ADR-0082's. server/pom.xml and runner/about.toml hold the same accepted licences:
// change them together. A package whose package.json names its licence wrongly gets a line in
// CLARIFIED, for its one version, after a look at the licence it ships.
import { execFileSync } from 'node:child_process';

/** The licences the policy accepts, as SPDX identifiers. */
const ACCEPTED = new Set([
  'MIT',
  'MIT-0',
  'Apache-2.0',
  'BSD-2-Clause',
  'BSD-3-Clause',
  'ISC',
  '0BSD',
  'Zlib',
  'Unlicense',
  'CC0-1.0',
  'Unicode-3.0',
  'Unicode-DFS-2016',
  'BSL-1.0',
  'EDL-1.0',
  'OFL-1.1',
  'EPL-2.0',
  'MPL-2.0',
  'LGPL-2.1-only',
  'LGPL-2.1-or-later',
  'LGPL-2.1',
]);

/** The licences the policy accepts only with an exception. */
const ACCEPTED_WITH = new Set([
  'GPL-2.0-only WITH Classpath-exception-2.0',
  'GPL-2.0 WITH Classpath-exception-2.0',
  'Apache-2.0 WITH LLVM-exception',
]);

/** `name@version` to the licence it really ships, where its package.json is wrong. */
const CLARIFIED = {};

/** The package's licence as an SPDX expression, or null when it names none. */
function licenceOf(pkg) {
  const clarified = CLARIFIED[`${pkg.name}@${pkg.version}`];
  if (clarified) {
    return clarified;
  }
  if (typeof pkg.license === 'string' && pkg.license.trim()) {
    return pkg.license.trim();
  }
  if (pkg.license && typeof pkg.license.type === 'string') {
    return pkg.license.type;
  }
  // The old form, a list of licences, means any one of them.
  if (Array.isArray(pkg.licenses) && pkg.licenses.length > 0) {
    return pkg.licenses.map((l) => (typeof l === 'string' ? l : l.type)).join(' OR ');
  }
  return null;
}

/** Whether the SPDX expression holds a choice of licences the policy accepts. */
function accepted(expression) {
  const tokens = expression.match(/\(|\)|[^\s()]+/g) ?? [];
  let at = 0;
  const peek = () => tokens[at];
  // or := and ("OR" and)*; and := with ("AND" with)*; with := term ("WITH" id)?
  function or() {
    let ok = and();
    while (peek() === 'OR') {
      at++;
      ok = and() || ok;
    }
    return ok;
  }
  function and() {
    let ok = withException();
    while (peek() === 'AND') {
      at++;
      ok = withException() && ok;
    }
    return ok;
  }
  function withException() {
    if (peek() === '(') {
      at++;
      const ok = or();
      if (tokens[at++] !== ')') {
        throw new Error(`unbalanced parentheses in "${expression}"`);
      }
      return ok;
    }
    const id = tokens[at++];
    if (id === undefined) {
      throw new Error(`incomplete expression "${expression}"`);
    }
    if (peek() === 'WITH') {
      at++;
      return ACCEPTED_WITH.has(`${id} WITH ${tokens[at++]}`);
    }
    return ACCEPTED.has(id);
  }
  try {
    const ok = or();
    return at === tokens.length && ok;
  } catch {
    return false;
  }
}

const packages = JSON.parse(
  execFileSync('npm', ['query', '.prod'], { encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 }),
);
const refused = [];
let checked = 0;
for (const pkg of packages) {
  if (pkg.location === '') {
    continue; // the app itself
  }
  checked++;
  const licence = licenceOf(pkg);
  if (licence === null || !accepted(licence)) {
    refused.push(`${pkg.name}@${pkg.version}: ${licence ?? 'no licence'} (${pkg.location})`);
  }
}
if (refused.length > 0) {
  console.error(`Licences the policy does not accept (ADR-0082), in ${refused.length} of ${checked} packages:`);
  for (const line of refused) {
    console.error(`  ${line}`);
  }
  process.exit(1);
}
console.log(`The licences of ${checked} production packages are accepted (ADR-0082).`);
