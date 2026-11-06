import type { Role } from '../api/generated/models';
import {
  assignableRoles,
  can,
  canDecide,
  canEditAgent,
  canModifyMember,
  canUseAgent,
  type CompanyAccess,
  holdsSeat,
  isBreakGlass,
  isMember,
  isViewer,
  managesCompany,
  mayWork,
} from './permissions';

const as = (role: Role | null, instanceAdmin = false): CompanyAccess => ({ role, instanceAdmin });

describe('authorization matrix (CONVENTIONS §6)', () => {
  it.each`
    access                | setup    | member   | works    | owners   | everyone | ack
    ${as('OWNER')}        | ${true}  | ${true}  | ${true}  | ${true}  | ${true}  | ${true}
    ${as('ADMIN')}        | ${true}  | ${true}  | ${true}  | ${false} | ${false} | ${true}
    ${as('MEMBER')}       | ${false} | ${true}  | ${true}  | ${false} | ${false} | ${false}
    ${as('VIEWER')}       | ${false} | ${true}  | ${false} | ${false} | ${false} | ${false}
    ${as(null, true)}     | ${true}  | ${false} | ${false} | ${true}  | ${false} | ${false}
    ${as('OWNER', true)}  | ${true}  | ${true}  | ${true}  | ${true}  | ${true}  | ${true}
    ${as('ADMIN', true)}  | ${true}  | ${true}  | ${true}  | ${false} | ${false} | ${true}
    ${as('MEMBER', true)} | ${false} | ${true}  | ${true}  | ${false} | ${false} | ${false}
    ${as('VIEWER', true)} | ${false} | ${true}  | ${false} | ${false} | ${false} | ${false}
  `('$access.role / instance admin $access.instanceAdmin', (row) => {
    const a = row.access as CompanyAccess;
    expect(managesCompany(a)).toBe(row.setup);
    for (const right of [
      can.renameCompany,
      can.manageMembers,
      can.manageGroups,
      can.viewAudit,
      can.viewSecrets,
      can.manageSecrets,
      can.manageSystems,
      can.manageServiceIdentities,
      can.manageGrants,
      can.manageModelAccess,
      can.manageRunnerHosts,
    ]) {
      expect(right(a)).toBe(row.setup);
    }
    expect(isMember(a)).toBe(row.member);
    expect(can.actAsMember(a)).toBe(row.works);
    expect(mayWork(a)).toBe(row.works);
    expect(isViewer(a)).toBe(a.role === 'VIEWER');
    expect(can.manageOwners(a)).toBe(row.owners);
    expect(can.readEveryonesConversations(a)).toBe(row.everyone);
    expect(can.acknowledgeCredentialFormats(a)).toBe(row.ack);
  });

  it('admins assign only non-owner roles and leave owners alone', () => {
    expect(assignableRoles(as('ADMIN'))).toEqual(['ADMIN', 'MEMBER', 'VIEWER']);
    expect(assignableRoles(as('OWNER'))).toEqual(['OWNER', 'ADMIN', 'MEMBER', 'VIEWER']);
    expect(assignableRoles(as('MEMBER'))).toEqual([]);
    expect(canModifyMember(as('ADMIN'), { role: 'OWNER' })).toBe(false);
    expect(canModifyMember(as('ADMIN'), { role: 'MEMBER' })).toBe(true);
    expect(canModifyMember(as('OWNER'), { role: 'OWNER' })).toBe(true);
    expect(canModifyMember(as('MEMBER'), { role: 'MEMBER' })).toBe(false);
    expect(canModifyMember(as('ADMIN'), { role: 'VIEWER' })).toBe(true);
  });

  it('lets an instance admin in break-glass appoint an owner, never demote or remove one', () => {
    const breakGlass = as(null, true);
    expect(can.manageOwners(breakGlass)).toBe(true);
    expect(assignableRoles(breakGlass)).toEqual(['OWNER', 'ADMIN', 'MEMBER', 'VIEWER']);
    expect(canModifyMember(breakGlass, { role: 'OWNER' })).toBe(false);
    expect(canModifyMember(breakGlass, { role: 'MEMBER' })).toBe(true);
    expect(can.readEveryonesConversations(breakGlass)).toBe(false);
  });

  it('gives an instance admin who is a member the rights of their role alone', () => {
    const admin = as('ADMIN', true);
    expect(can.manageOwners(admin)).toBe(false);
    expect(can.readEveryonesConversations(admin)).toBe(false);
    expect(canModifyMember(admin, { role: 'OWNER' })).toBe(false);
    const owner = as('OWNER', true);
    expect(can.manageOwners(owner)).toBe(true);
    expect(can.readEveryonesConversations(owner)).toBe(true);
    expect(canModifyMember(owner, { role: 'OWNER' })).toBe(true);
  });

  it('is break-glass only for an instance admin who is not a member', () => {
    expect(isBreakGlass(as(null, true))).toBe(true);
    for (const role of ['OWNER', 'ADMIN', 'MEMBER', 'VIEWER'] as Role[]) {
      expect(isBreakGlass(as(role, true))).toBe(false);
    }
    expect(isBreakGlass(as(null))).toBe(false);
    // A MEMBER or a VIEWER who is an instance admin sets nothing up: the server answers 403.
    expect(assignableRoles(as('MEMBER', true))).toEqual([]);
    expect(canModifyMember(as('VIEWER', true), { role: 'MEMBER' })).toBe(false);
  });

  it('gives a seat to every role but VIEWER (ADR-0077)', () => {
    expect((['OWNER', 'ADMIN', 'MEMBER', 'VIEWER'] as Role[]).map(holdsSeat)).toEqual([
      true,
      true,
      true,
      false,
    ]);
  });
});

describe('rights on objects come from the server', () => {
  it('repeats usableByMe, editableByMe and mayDecide', () => {
    expect(canUseAgent({ usableByMe: true })).toBe(true);
    expect(canUseAgent({ usableByMe: false })).toBe(false);
    expect(canEditAgent({ editableByMe: true })).toBe(true);
    expect(canEditAgent({ editableByMe: false })).toBe(false);
    expect(canDecide({ mayDecide: true })).toBe(true);
    expect(canDecide({ mayDecide: false })).toBe(false);
  });
});
