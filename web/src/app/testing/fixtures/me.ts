import type { Company, CurrentUser, Membership, Role } from '../../api/generated/models';
import { AT, IDS } from './ids';

/** Tags `Me` and `Companies`: who is signed in, and the company. */

export function aMembership(
  role: Role = 'MEMBER',
  overrides: Partial<Membership> = {},
): Membership {
  return { companyId: IDS.company, companyName: 'Acme', role, ...overrides };
}

/** Alice, a member of Acme. */
export function aCurrentUser(overrides: Partial<CurrentUser> = {}): CurrentUser {
  return {
    id: IDS.aliceUser,
    email: 'alice@acme.test',
    displayName: 'Alice Novak',
    instanceAdmin: false,
    authSource: 'LOCAL',
    memberships: [aMembership('MEMBER')],
    ...overrides,
  };
}

/** Olga, the company's owner. */
export function anOwner(overrides: Partial<CurrentUser> = {}): CurrentUser {
  return aCurrentUser({
    id: IDS.ownerUser,
    email: 'olga@acme.test',
    displayName: 'Olga Owner',
    memberships: [aMembership('OWNER')],
    ...overrides,
  });
}

/** Bob, an admin of Acme. */
export function anAdmin(overrides: Partial<CurrentUser> = {}): CurrentUser {
  return aCurrentUser({
    id: IDS.bobUser,
    email: 'bob@acme.test',
    displayName: 'Bob Svoboda',
    memberships: [aMembership('ADMIN')],
    ...overrides,
  });
}

export function aCompany(overrides: Partial<Company> = {}): Company {
  return { id: IDS.company, name: 'Acme', createdAt: AT.earlier, myRole: 'MEMBER', ...overrides };
}
