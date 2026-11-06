import type { AgentSummary, Decision, Member, Role } from '../api/generated/models';

/**
 * What the caller is in one company: their role (null when an instance admin looks at a company
 * they are not a member of) and the instance-admin flag. The server enforces the same matrix
 * (CONVENTIONS §6); the UI uses it only to hide what would fail, and repeats the server's answers
 * on each object (`usableByMe`, `editableByMe`, `mayDecide`) instead of deriving rights itself.
 */
export interface CompanyAccess {
  readonly role: Role | null;
  readonly instanceAdmin: boolean;
}

/** The four roles of a company (ADR-0058, ADR-0077), most rights first. */
export const ROLES: readonly Role[] = ['OWNER', 'ADMIN', 'MEMBER', 'VIEWER'];

export const ROLE_LABELS: Record<Role, string> = {
  OWNER: 'Owner',
  ADMIN: 'Admin',
  MEMBER: 'Member',
  VIEWER: 'Viewer',
};

/** The roles that hold a seat under a Pro licence (ADR-0077 decision 5): every role but VIEWER. */
export function holdsSeat(role: Role): boolean {
  return role !== 'VIEWER';
}

/**
 * An instance admin who is not a member of the company: the audited break-glass, which gives
 * ADMIN's view and, beyond it, only the appointing of an owner. A member who is an instance admin
 * acts by their role, as anyone does (CONVENTIONS §6): every helper here asks this, never the
 * instance-admin flag alone.
 */
export function isBreakGlass(a: CompanyAccess): boolean {
  return a.instanceAdmin && a.role === null;
}

/** One line per role, as the members page and the role picker say it (PLATFORM.md §4). */
export const ROLE_DESCRIPTIONS: Record<Role, string> = {
  OWNER: 'Everything an admin does, and the company’s retention and who else is an owner.',
  ADMIN:
    'Sets the company up: systems and their credentials, service accounts, groups, budgets, runner hosts and the model gateway.',
  MEMBER:
    'Talks to agents, defines agents and triggers, and connects their own accounts and runner host.',
  VIEWER:
    'Reads the conversations and results shared with their groups and sees their inbox, but starts no work and answers nothing. Holds no seat.',
};

/**
 * OWNER or ADMIN, or an instance admin who is not a member (the audited break-glass): who sets the
 * company up. The sidebar's "Company" section and its pages are theirs. An instance admin who is a
 * member has the rights of their role alone: as a MEMBER or a VIEWER, none of these (CONVENTIONS
 * §6; the security review of 2026-10-27, I9).
 */
export function managesCompany(a: CompanyAccess): boolean {
  return isBreakGlass(a) || a.role === 'OWNER' || a.role === 'ADMIN';
}

/**
 * A member of the company, whatever the role: they have a principal there, so they hold accounts,
 * a budget and personal runner hosts, start conversations and own agents. An instance admin who is
 * not a member has none of these.
 */
export function isMember(a: CompanyAccess): boolean {
  return a.role !== null;
}

/**
 * A member who may do work (ADR-0077 decision 5): start and continue conversations, own and edit
 * agents, author triggers, answer and decide requests. Every role but VIEWER; never an instance
 * admin who is not a member, who has no principal there.
 */
export function mayWork(a: CompanyAccess): boolean {
  return a.role !== null && holdsSeat(a.role);
}

/** A member who only reads: VIEWER. */
export function isViewer(a: CompanyAccess): boolean {
  return a.role === 'VIEWER';
}

export const can = {
  renameCompany: managesCompany,
  /** Add members, change roles and remove members; OWNER alone for the OWNER role. */
  manageMembers: managesCompany,
  /**
   * Grant the OWNER role: an owner, or an instance admin who is not a member, to appoint one (the
   * remedy for a company without a reachable owner). Changing or removing an owner is
   * {@link canModifyMember}'s: an owner's alone.
   */
  manageOwners: (a: CompanyAccess) => a.role === 'OWNER' || isBreakGlass(a),
  /** Create, rename, fill and delete groups and list their members (`MANAGE_GROUPS`). */
  manageGroups: managesCompany,
  viewAudit: managesCompany,
  /** List the secrets' names (`VIEW_SECRETS`). */
  viewSecrets: managesCompany,
  /** Create, update or delete a secret (`MANAGE_SECRETS`). */
  manageSecrets: managesCompany,
  /** Register and change company systems, their apps, repositories and tools (`MANAGE_SYSTEMS`). */
  manageSystems: managesCompany,
  /** Service accounts and who may use them (`MANAGE_SERVICE_IDENTITIES`). */
  manageServiceIdentities: managesCompany,
  /** Lend a system's credential to a group (`MANAGE_GRANTS`; ADR-0072). */
  manageGrants: managesCompany,
  /** The model gateway and every principal's budget (`MANAGE_MODEL_ACCESS`). */
  manageModelAccess: managesCompany,
  /** The company's runner hosts (`MANAGE_RUNNER_HOSTS`); a member registers personal ones only. */
  manageRunnerHosts: managesCompany,
  /**
   * One's own accounts, budget and personal runner hosts (the Accounts page); define agents and
   * start conversations. Every member but a Viewer, who never works (ADR-0077); an instance admin
   * who is not one has no principal there.
   */
  actAsMember: mayWork,
  /** Store a file that looks like a credential in one's own conversation (`MANAGE_SECRETS`). */
  acknowledgeCredentialFormats: (a: CompanyAccess) =>
    isMember(a) && (a.role === 'OWNER' || a.role === 'ADMIN'),
  /**
   * Read every member's conversations in full ("Everyone's", PLATFORM.md §9): OWNER only, never an
   * instance admin's break-glass (another person's conversation is `404` to it).
   */
  readEveryonesConversations: (a: CompanyAccess) => a.role === 'OWNER',
} as const;

/** Whether the caller may use the agent: talk to it, keep settings of it (`usableByMe`). */
export function canUseAgent(agent: Pick<AgentSummary, 'usableByMe'>): boolean {
  return agent.usableByMe;
}

/** Whether the caller may edit, share, pause or delete the agent (`editableByMe`). */
export function canEditAgent(agent: Pick<AgentSummary, 'editableByMe'>): boolean {
  return agent.editableByMe;
}

/** Whether the caller decides this decision: its approver, while it is open (`mayDecide`). */
export function canDecide(decision: Pick<Decision, 'mayDecide'>): boolean {
  return decision.mayDecide;
}

/** Roles the caller may give to someone (when adding or changing a member). */
export function assignableRoles(a: CompanyAccess): Role[] {
  if (!can.manageMembers(a)) return [];
  return can.manageOwners(a) ? [...ROLES] : ROLES.filter((r) => r !== 'OWNER');
}

/**
 * Whether the caller may change this member's role or remove them. An OWNER is changed or removed
 * by an owner only: not by an admin, and not by an instance admin's break-glass, which appoints
 * owners but never demotes or removes one.
 */
export function canModifyMember(a: CompanyAccess, member: Pick<Member, 'role'>): boolean {
  if (!can.manageMembers(a)) return false;
  return member.role !== 'OWNER' || a.role === 'OWNER';
}
