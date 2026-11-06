# ADR-0093: A Jira Cloud credential may be an Atlassian service account's OAuth 2.0 credential

- Status: Accepted
- Date: 2026-10-26
- Decided by: the owner, 2026-10-26
- Sources: [ADR-0072](0072-system-credentials-lent-to-groups-and-service-accounts.md) (a system's credentials), [ADR-0058](0058-authority-from-the-audience.md) (accounts and their OAuth grants); [CONVENTIONS.md](../CONVENTIONS.md) §10, §15.3 and §15.23; RFC 6749 §4.4; Atlassian's *Create OAuth 2.0 credential for service accounts*
- Changes: migration `V065` (`account_kind_ck`, `account_holder_ck`); the account kind `CLIENT` (`AccountInfo.Kind`), `AccountService.storeClient`, `Credentials.addClient` and `storeClient`, the mint in `AccountCredentials`; the system module's `SystemCalls.clientToken` and `clientIdentity`, `ClientCredentials`, `OAuthClient.clientCredentials`, `SystemKind.clientCredentials`, a test's `myself` through Atlassian's gateway; the API (`addSystemCredentialClient`, `storeSystemCredentialClient`, `CLIENT` in the kinds of `Account`, `SystemCredential`, `SystemCredentialRef`, `ConversationBinding.credentialKind`, `DepartureCredential`); the web app's credential dialog; the admin guide; CONVENTIONS.md §15.23

## Context

- A Jira Cloud credential is either an Atlassian account's `email:api-token`, sent with Basic authentication to the site, or a 3LO sign-in through the system's app, whose access token calls Jira through Atlassian's gateway (`https://api.atlassian.com/ex/jira/{cloudId}`).
- Neither fits an Atlassian service account, which organizations create for integrations. Its API tokens are scoped tokens, valid only through the gateway, which a stored token is never sent to; and it cannot sign in with a browser. Its OAuth 2.0 credential (a client id and secret, with scopes chosen when it is created) mints access tokens with the `client_credentials` grant at `https://auth.atlassian.com/oauth/token`: valid an hour, with no refresh token.
- A company that wants its agents to act as a service account had to keep a regular Atlassian user as a machine user instead.

## Decision

**On Jira Cloud, a system's credential may be an OAuth client's own credentials: an Atlassian service account's OAuth 2.0 credential (kind `CLIENT`). Tinfirm mints its access tokens itself and calls Jira through Atlassian's gateway with them.**

1. **What is kept.** The credential's secret holds `{"clientId","clientSecret"}` and the access token minted last (`accessToken`, `tokenType`, `expiresAt`, `scope`, the members of a sign-in's grant). The account row keeps the identity, the gateway (`api_base_url`), the scopes and when the token expires, as a sign-in's does. Only a system's credential is one (`account_holder_ck`), never a member's own account, and only on Jira Cloud (`400 SYSTEM_KIND_INVALID` elsewhere). The system's app plays no part.
2. **Connecting.** `POST …/credentials/client` (`name`, `clientId`, `clientSecret`) and `PUT …/credentials/{credentialId}/client` mint a token, read the site's cloud id from its public `/_edge/tenant_info`, and ask `myself` through the gateway with the token, which proves that the token reaches the site and names the service account (its `accountId`, I2 as any credential). A refusal of either is `400 ACCOUNT_REFUSED`, audited as `account.refused`; the stored credential is audited as `account.connected` or `account.reconnected` with `kind` `CLIENT`, never with the secret. A credential may change between a token, a sign-in and client credentials by storing the other.
3. **Using it.** A reveal uses the token minted last while it does not expire within a minute; else it mints the next one, outside any transaction and under the account's refresh lock (CONVENTIONS §10, as a sign-in's refresh), and stores it with the client's credentials. A refusal of the client (`invalid_client`, `invalid_grant`, `unauthorized_client`, `access_denied`) turns the credential `RECONNECT`, audited and announced as a refused refresh is; another failure leaves it `OK`, and the last token is used while it has not expired. The revealed credential is a bearer token with the gateway, so the trackers, the polls and the outbox call Jira as they call it for a sign-in, unchanged.
4. **Testing it.** **Test** asks `myself` through the gateway for any Jira Cloud bearer token that names one (a client's, a sign-in's), rather than Atlassian's `/me`, which a service account's token may not answer.
5. **Removing it.** Nothing is revoked at Atlassian: an admin revokes the OAuth 2.0 credential in Atlassian Administration.

## Consequences

- A company acts on Jira Cloud as an Atlassian service account, with the scopes its OAuth 2.0 credential was created with, and no person's account.
- The client secret, a long-lived credential, is in the vault as a token is; a token Tinfirm minted is kept beside it for its hour.
- Atlassian's token endpoint, gateway and `tenant_info` are fixed addresses, as the 3LO ones were: the integration tests cannot stub them. The grant, the secret's format, the API's refusals and the constraints are tested; the mint and the gateway's `myself` against Atlassian are not.
- A service account's scoped API token stays unusable: a stored token goes to the site, never to the gateway. It would need a fourth way, a token sent through the gateway.
