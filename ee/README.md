# ee/: Tinfirm's commercial part

`ee/` holds the code that only Pro and Enterprise put in force ([ADR-0077](../docs/adr/0077-licence-editions-and-seats.md)). It is in every build and every image, beside the MIT core. The server's build compiles `ee/src/main/java` and its tests in, as it does `libs/jira` (`server/pom.xml`, build-helper). Its licence is the Tinfirm Enterprise licence (ADR-0077, decision 1), whose text is [LICENSE](LICENSE) here. The server's image carries it as `/licenses/ee/LICENSE` ([ADR-0082](../docs/adr/0082-licence-notices-and-dependency-licences.md)).

| Path | Contents |
|---|---|
| `src/main/java/com/flowtivo/tinfirm/ee` | Module `ee`: the licence, its keys, edition, state and seats (CONVENTIONS §15.24). It implements the core's port `shared.licence.Licence`, and no module of the core depends on it. |
| `src/main/java/com/flowtivo/tinfirm/<module>/internal` | The paid features' code, in the package of the core module it belongs to (ADR-0077, decision 1): the audit's tools (`audit`), changing the retention periods and setting legal holds (`governance`), the Outlook add-in's manifest (`mcp`), the Gmail add-on's cards, deployment file and linking (`mcp`) with its calls to the Gmail API (`system`), and elastic runners' session bundles (`run`) and schedule of ready sandboxes (`runner`, [ADR-0100](../docs/adr/0100-elastic-runners-in-pro.md)). Each implements an interface of its module, which the core finds when used and answers as Community without (CONVENTIONS §15.24); no class of the core names one of these, and only the core's listed limits and fallbacks name `Feature` (`ModularityTest`). The SQL only these features run is here too: `RetentionWrites` (the periods saved, a hold inserted), `AuditToolsStore` (an export's range by time, a check's acknowledgement), `SessionBundleRows` (a bundle found and stored) and `RunnerPoolStore` (a pool's settings and the count sent). |
| `runner/` | The runner host's code that only elastic runners run ([ADR-0100](../docs/adr/0100-elastic-runners-in-pro.md)): the sandbox bridge's crate (`tinfirm-bridge/src`, `tests`), the runner host's modules of the bridge, the VM executor and its boot, the DigitalOcean driver, ready VMs, session bundles and the run's files over the bridge (`tinfirm-runner-host/src`), their tests (`tinfirm-runner-host/tests`), and the VM image's recipe (`images/vm`). The crates' manifests stay in `runner/` (a Cargo workspace's members are under its root) and name these files (`[lib] path`, `[[test]] path`, `#[path]`), so the one `tinfirm-runner-host` binary holds them, as every server image holds the server's part of `ee/`. They run only with `run --sandbox-bridge` or `--executor vm`, which a server refuses without `ELASTIC_RUNNERS`. |
| `src/main/resources/ee/licence-public-keys/<key id>.pub` | FLOWTIVO's Ed25519 public keys, X.509 PEM, one file a key id. Each licence key names the key id it is signed with (`kid`); the server verifies it against that key, and refuses a key whose id has no file here. |
| `src/test/java` | Its tests. They sign keys with a key pair they make at every run (`TestLicences`). |
| `tools/LicenceKeyTool.java` | FLOWTIVO's tool to make a signing key pair and sign keys. JDK 25 only. |

## Signing keys

The bundled public keys, `src/main/resources/ee/licence-public-keys/`:

| Key id | Role | SHA-256 of the public key's DER |
|---|---|---|
| `master` | Signs every licence key | `1575eea10ec51163e02f8cddfdd04dcdbcf3aecad20c6244cddd3e44330340f8` |
| `backup` | The standby: trusted by every release, signs nothing while `master` is in service | `f7825d67e6063b4aea643956df4c4467462980118a000832bf25b6a29f9de2b0` |

Check a file against its line: `openssl pkey -pubin -in master.pub -outform DER | sha256sum`. The tests' keys are never bundled: they are added at run time, under the ids `test` and `e2e`, only in the `test` and `e2e` profiles, and no file here may take those ids.

```sh
java ee/tools/LicenceKeyTool.java sign --private-key /secure/place/master-key.pem --key-id master \
  --id ACME-2027 --rev 1 --customer "Acme s.r.o." --edition PRO --seats 25 \
  --starts 2027-01-01 --ends 2027-12-31
```

- **The key id** is the public key's file name without `.pub`: up to 64 lower-case letters, digits or `-`, never `test` or `e2e`. A licence key names it in its signed payload, so a key id never changes and is never reused. A new pair: `java ee/tools/LicenceKeyTool.java keygen /secure/place/<key id>-key.pem > ee/src/main/resources/ee/licence-public-keys/<key id>.pub`.
- **The passphrase.** `keygen` asks for it twice on the terminal and writes the private key encrypted with it (encrypted PKCS#8: PBES2, PBKDF2-HMAC-SHA256 with 600,000 iterations, AES-256-CBC; `openssl pkey` reads it too). `sign` asks for it once. It is never an argument or an environment variable. Without a terminal, the tool reads it from the first line of standard input, for a pipe from a secret store. Use at least 12 characters; a long passphrase from a password manager is better.
- **A key without a passphrase** (unencrypted PKCS#8, a `BEGIN PRIVATE KEY` PEM) still signs, with a warning. Encrypt it in place with `java ee/tools/LicenceKeyTool.java protect /secure/place/master-key.pem`: it asks for a passphrase twice, writes the encrypted key beside the old one, checks that it decrypts to the same key, then replaces the file. The key, its public key and its key id stay the same. Then replace or destroy every unencrypted copy, backups included.
- **`sign` checks its own work.** Before it prints a key, it verifies the signature against the key id's public key in this directory (or `--public-key`), so that it never prints a key the server would refuse.

The private keys and their passphrases never enter this repository or an instance: whoever holds a key and its passphrase issues licences (threat model, A37).

## Rotate the signing key

A server trusts the key ids of its own release only. So a new key ships before it signs anything, and an old one leaves after nothing needs it:

1. Make the new pair with `keygen`, and commit its public key beside the old one.
2. Release. Customers who upgrade trust both keys.
3. Sign new licence keys with the new `--key-id` only once the customers who receive them run that release or a later one. Until then, sign with the old key.
4. When every licence key signed with the old key has ended, or was issued again under the new key id (the same `--id`, the next `--rev`), remove the old public key and release.

A licence key's `kid` is in its signed payload, so a key cannot be moved to another signer without breaking its signature.

## Retire a compromised key

Whoever holds a private key and its passphrase can sign keys that every release trusting its key id accepts. Retire it:

1. Make a new pair, commit its public key, and release, as for a rotation. If a second pair is already trusted (see below), skip this step.
2. Issue every customer's licence again, signed with the new key: the same licence id, the next revision, the same seats and dates. Send the new keys, and ask the customers to paste them.
3. Remove the compromised public key and release. On that release, every key it signed puts nothing in force, forged ones included: the server logs each at `ERROR`, and the instance runs as Community until a key of a trusted id is pasted. A customer who pasted the new key first notices nothing.
4. Ask the customers to upgrade to that release. An instance that stays on an older one keeps trusting the compromised key: the contract, not the key, covers it.

To make step 1 unnecessary, keep a second pair from the start, offline and apart from the first, its public key bundled but never used to sign: after a compromise, its key id signs the new keys at once, and every customer can paste them on the release they run. That pair is `backup`: the next section says how to move to it.

## Move to `backup`, then add a new standby

When `master` is lost, compromised or retired:

1. **Sign with `backup`.** Every release trusts it, so nothing has to be released first: `sign --private-key /secure/place/backup-key.pem --key-id backup …`. Issue every customer's licence again with it: the same `--id`, the next `--rev`, the same seats and dates. Customers paste the new keys on the release they run.
2. **Add a new standby.** Make a new pair offline, apart from `backup`, under a new id such as `backup-2`: `keygen /secure/place/backup-2-key.pem > ee/src/main/resources/ee/licence-public-keys/backup-2.pub`. Commit its public key and release: the releases from then on trust it.
3. **Remove `master.pub`** in a release once every key it signed was issued again under `backup`. After a compromise, do it in the same release as step 2: from that release, every key `master` signed, forged ones included, puts nothing in force. For a planned retirement, wait until the keys `master` signed have ended or were replaced.
4. **Update the table** at the top of [Signing keys](#signing-keys): `backup` signs, `backup-2` is the standby. The ids keep their names; only their roles change.
