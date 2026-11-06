# ADR-0011: Envelope-encrypted credential vault

- Status: Accepted
- Date: 2026-09-26
- Sources: CONVENTIONS §4, §8, §9

## Context

- The server stores API keys and integration tokens for each company.
- Secrets in environment variables leak into logs and child processes.
- The vault needs AES-GCM envelope encryption, write-only secrets and an audit event for every access.
- Master-key rotation must re-wrap data keys online.

## Decision

- **Envelope encryption.** Each secret version gets a random 256-bit data key (DEK).
  - The value is encrypted with AES-256-GCM under the DEK, with a 96-bit random nonce and the AAD `companyId|secretId|version`.
  - The DEK is wrapped with AES-256-GCM under the active master key.
  - The server stores the `keyId`, the wrapped DEK, the nonces and the ciphertext.
- **Master keys** come only from `TINFIRM_VAULT_KEYS` (comma-separated `keyId:base64(32 bytes)`) and `TINFIRM_VAULT_ACTIVE_KEY`. The server does not start without them.
- **Rewrap.** `POST /api/admin/vault/rewrap` rewraps every DEK that is not under the active key. It is idempotent and audited as `vault.rewrapped`.
- **Write-only.** No API returns a value.
- **Internal access.** `VaultService.reveal(companyId, secretId, purpose)` exists for M1. It is audited as `secret.accessed` and not exposed over HTTP.
- **Extension point.** HashiCorp Vault or a KMS can supply master keys later, behind a `MasterKeyProvider` interface.

## Consequences

- Key rotation is cheap: rewrap touches only the wrapped DEKs, not the values.
- The AAD binds each ciphertext to its company, secret and version. Moving ciphertext to another row or company makes decryption fail.
- A database copy alone does not reveal values.
- The operator must back up the master keys separately, and keep an old key while any backup still needs it ([operations/external-database.md](../operations/external-database.md)).
- Rewrap does not protect data that an attacker copied together with the old master key. After such a compromise, every secret must be rotated at its source (threat model, §6.2).
- Master keys in environment variables can leak through the platform (threat model, 4.1).
- Adding a master key needs a restart, because keys come from the environment. CONVENTIONS makes Vault a later extension point, not an M0 key source.

## Alternatives considered

- **HashiCorp Vault or a KMS as a required dependency.** Not chosen: it stays an optional extension point.
- **One master key that encrypts every value directly.** Not discussed. Rotation would re-encrypt every value, and one key would protect all data with no per-version key.
