import type {
  Group,
  GroupRef,
  GroupSummary,
  Member,
  MemberAccount,
} from '../../api/generated/models';
import { AT, IDS } from './ids';

/** Tags `Members` and `Groups`. */

export function aMember(overrides: Partial<Member> = {}): Member {
  return {
    userId: IDS.aliceUser,
    displayName: 'Alice Novak',
    email: 'alice@acme.test',
    role: 'MEMBER',
    addedAt: AT.earlier,
    suspended: false,
    ...overrides,
  };
}

/** Which system a member holds an account on, and its status (I4: never more). */
export function aMemberAccount(overrides: Partial<MemberAccount> = {}): MemberAccount {
  return {
    systemId: IDS.github,
    systemName: 'GitHub',
    systemKind: 'GITHUB',
    status: 'OK',
    ...overrides,
  };
}

export function aGroupRef(overrides: Partial<GroupRef> = {}): GroupRef {
  return { id: IDS.analysts, name: 'Analysts', ...overrides };
}

export function aGroupSummary(overrides: Partial<GroupSummary> = {}): GroupSummary {
  return {
    id: IDS.analysts,
    name: 'Analysts',
    description: 'Business analysts of the shop',
    memberCount: 2,
    ...overrides,
  };
}

export function aGroup(overrides: Partial<Group> = {}): Group {
  return {
    id: IDS.analysts,
    name: 'Analysts',
    description: 'Business analysts of the shop',
    members: [
      { userId: IDS.carolUser, displayName: 'Carol Dvořák', email: 'carol@acme.test' },
      { userId: IDS.daveUser, displayName: 'Dave Malý', email: 'dave@acme.test' },
    ],
    createdAt: AT.earlier,
    updatedAt: AT.earlier,
    ...overrides,
  };
}
