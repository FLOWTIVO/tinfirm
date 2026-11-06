import type {
  Account,
  AccountGrant,
  BorrowedCredential,
  CredentialGrant,
  CredentialLending,
  GrantChoice,
  GroupUsage,
  MemberAccountCount,
  MyAccount,
  ServiceIdentity,
  SystemCredential,
  SystemCredentialList,
} from '../../api/generated/models';
import { aGroupRef } from './members';
import { AT, IDS } from './ids';
import { aSystemRef } from './systems';

/** Tags `Accounts`, `Credentials`, `Grants` and `ServiceIdentities`. */

export function anAccount(overrides: Partial<Account> = {}): Account {
  return {
    id: 'acc-alice-github',
    systemId: IDS.github,
    kind: 'OAUTH',
    login: 'alice-novak',
    status: 'OK',
    connectedAt: AT.earlier,
    lastUsedAt: AT.start,
    ...overrides,
  };
}

/** A grant the caller may act with: GitHub's credential svc-reader, lent to Analysts read-only. */
export function aGrantChoice(overrides: Partial<GrantChoice> = {}): GrantChoice {
  return {
    grantId: IDS.analystsGrant,
    groupId: IDS.analysts,
    groupName: 'Analysts',
    credentialId: IDS.readerGithubAccount,
    credentialName: 'svc-reader',
    access: 'READ',
    ...overrides,
  };
}

/** The caller's standing on one system: by default, their own GitHub account. */
export function aMyAccount(overrides: Partial<MyAccount> = {}): MyAccount {
  return {
    system: aSystemRef(),
    account: anAccount(),
    binding: 'OWN',
    grants: [],
    oauthAvailable: true,
    ...overrides,
  };
}

export function aGrant(overrides: Partial<AccountGrant> = {}): AccountGrant {
  return {
    id: IDS.analystsGrant,
    credentialId: IDS.readerGithubAccount,
    credentialName: 'svc-reader',
    systemId: IDS.github,
    systemName: 'GitHub',
    groupId: IDS.analysts,
    groupName: 'Analysts',
    access: 'READ',
    createdAt: AT.earlier,
    updatedAt: AT.earlier,
    ...overrides,
  };
}

export function aServiceIdentity(overrides: Partial<ServiceIdentity> = {}): ServiceIdentity {
  return {
    id: IDS.svcReader,
    name: 'svc-reader',
    description: 'Reads the shop’s repositories for analysts',
    status: 'ACTIVE',
    usableByMe: false,
    userGroups: [aGroupRef()],
    createdAt: AT.earlier,
    updatedAt: AT.earlier,
    ...overrides,
  };
}

/** What Analysts may use (`getGroupUsage`): the business analyst, svc-reader's GitHub, svc-reader. */
export function aGroupUsage(overrides: Partial<GroupUsage> = {}): GroupUsage {
  return {
    agents: [{ id: IDS.agent, name: 'Business analyst' }],
    grants: [aGrant()],
    serviceIdentities: [{ id: IDS.svcReader, name: 'svc-reader' }],
    ...overrides,
  };
}

/** Analysts' grant of GitHub's credential svc-reader, read-only, as the credential lists it. */
export function aCredentialGrant(overrides: Partial<CredentialGrant> = {}): CredentialGrant {
  return {
    id: IDS.analystsGrant,
    groupId: IDS.analysts,
    groupName: 'Analysts',
    access: 'READ',
    createdAt: AT.earlier,
    updatedAt: AT.earlier,
    ...overrides,
  };
}

/** svc-reader borrowing a credential, with writes, for its two triggers. */
export function aCredentialLending(overrides: Partial<CredentialLending> = {}): CredentialLending {
  return {
    serviceIdentityId: IDS.svcReader,
    name: 'svc-reader',
    status: 'ACTIVE',
    access: 'WRITE',
    triggers: 2,
    createdAt: AT.earlier,
    updatedAt: AT.earlier,
    ...overrides,
  };
}

/**
 * A system's credential (ADR-0072): GitHub's svc-reader, the machine user's token, lent to Analysts
 * read-only and borrowed by the service account svc-reader (the migration names a service
 * identity's account after it).
 */
export function aSystemCredential(overrides: Partial<SystemCredential> = {}): SystemCredential {
  return {
    id: IDS.readerGithubAccount,
    systemId: IDS.github,
    name: 'svc-reader',
    kind: 'TOKEN',
    status: 'OK',
    enabled: true,
    login: 'svc-reader',
    lastError: null,
    scopes: null,
    connectedAt: AT.earlier,
    connectedByName: 'Olga Owner',
    lastUsedAt: AT.start,
    bot: false,
    grants: [aCredentialGrant()],
    serviceAccounts: [aCredentialLending()],
    ...overrides,
  };
}

/** What svc-reader borrows on GitHub: the credential svc-reader, with writes. */
export function aBorrowedCredential(
  overrides: Partial<BorrowedCredential> = {},
): BorrowedCredential {
  return {
    system: aSystemRef(),
    credential: {
      id: IDS.readerGithubAccount,
      name: 'svc-reader',
      kind: 'TOKEN',
      status: 'OK',
      enabled: true,
      login: 'svc-reader',
    },
    access: 'WRITE',
    bot: false,
    triggers: 2,
    createdAt: AT.earlier,
    updatedAt: AT.earlier,
    ...overrides,
  };
}

/** How many members hold their own account on a system: by default, three, one to sign in again. */
export function aMemberAccountCount(
  overrides: Partial<MemberAccountCount> = {},
): MemberAccountCount {
  return { ok: 2, notOk: 1, ...overrides };
}

/** A system's credentials as `listSystemCredentials` answers: these, and the members' own accounts. */
export function aSystemCredentialList(
  items: SystemCredential[] = [aSystemCredential()],
  memberAccounts: MemberAccountCount = aMemberAccountCount(),
): SystemCredentialList {
  return { items, memberAccounts };
}
