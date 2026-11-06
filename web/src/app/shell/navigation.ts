import {
  lucideArchive,
  lucideBot,
  lucideBrainCircuit,
  lucideBug,
  lucideBuilding2,
  lucideHistory,
  lucideIdCard,
  lucideInbox,
  lucideKeyRound,
  lucideLibraryBig,
  lucideMessagesSquare,
  lucideOctagonPause,
  lucidePlug,
  lucideScrollText,
  lucideServer,
  lucideSettings,
  lucideUserRoundKey,
  lucideUsers,
  lucideZap,
} from '@ng-icons/lucide';
import { type CompanyAccess, managesCompany, mayWork } from '../core/permissions';

/** The groups of the sidebar (plan §3): the work every member does, and the company's setup. */
export type NavGroup = 'work' | 'company';

export interface NavItem {
  /** The path under `/c/:companyId`, and the first segment of every page of the item. */
  readonly path: string;
  readonly label: string;
  readonly icon: string;
  readonly group: NavGroup;
  /** Who sees the item; the route guards say the same (`core/company-guards`). */
  readonly visible: (access: CompanyAccess) => boolean;
}

export const NAV_GROUP_LABELS: Record<NavGroup, string> = {
  work: 'Work',
  company: 'Company',
};

const everyone = () => true;

/**
 * The company's navigation (plan §3). A member sees the six items of the work, the library of
 * skills (ADR-0064) beside the agents; OWNER, ADMIN and an instance admin also see the company's
 * setup. An instance admin who is not a member has no accounts, budget or hosts of their own, so
 * no Accounts; nor has a Viewer, who never works (ADR-0077).
 */
export const COMPANY_NAV: readonly NavItem[] = [
  { path: 'inbox', label: 'Inbox', icon: 'lucideInbox', group: 'work', visible: everyone },
  {
    path: 'conversations',
    label: 'Conversations',
    icon: 'lucideMessagesSquare',
    group: 'work',
    visible: everyone,
  },
  { path: 'agents', label: 'Agents', icon: 'lucideBot', group: 'work', visible: everyone },
  {
    path: 'skills',
    label: 'Skills',
    icon: 'lucideLibraryBig',
    group: 'work',
    visible: everyone,
  },
  { path: 'triggers', label: 'Triggers', icon: 'lucideZap', group: 'work', visible: everyone },
  {
    path: 'accounts',
    label: 'Accounts',
    icon: 'lucideUserRoundKey',
    group: 'work',
    visible: mayWork,
  },
  {
    path: 'systems',
    label: 'Systems',
    icon: 'lucidePlug',
    group: 'company',
    visible: managesCompany,
  },
  {
    path: 'service-identities',
    label: 'Service accounts',
    icon: 'lucideIdCard',
    group: 'company',
    visible: managesCompany,
  },
  {
    path: 'model-access',
    label: 'Model access',
    icon: 'lucideBrainCircuit',
    group: 'company',
    visible: managesCompany,
  },
  {
    path: 'runner-hosts',
    label: 'Runner hosts',
    icon: 'lucideServer',
    group: 'company',
    visible: managesCompany,
  },
  {
    path: 'members',
    label: 'Members',
    icon: 'lucideUsers',
    group: 'company',
    visible: managesCompany,
  },
  {
    path: 'kill-switches',
    label: 'Kill switches',
    icon: 'lucideOctagonPause',
    group: 'company',
    visible: managesCompany,
  },
  {
    path: 'audit',
    label: 'Audit log',
    icon: 'lucideScrollText',
    group: 'company',
    visible: managesCompany,
  },
  {
    path: 'retention',
    label: 'Retention',
    icon: 'lucideArchive',
    group: 'company',
    visible: managesCompany,
  },
  {
    path: 'settings',
    label: 'Settings',
    icon: 'lucideSettings',
    group: 'company',
    visible: managesCompany,
  },
];

/** The items the caller sees, by group, in order; a group without items is left out. */
export function companyNav(
  access: CompanyAccess,
): { group: NavGroup; label: string; items: NavItem[] }[] {
  return (Object.keys(NAV_GROUP_LABELS) as NavGroup[])
    .map((group) => ({
      group,
      label: NAV_GROUP_LABELS[group],
      items: COMPANY_NAV.filter((item) => item.group === group && item.visible(access)),
    }))
    .filter((section) => section.items.length > 0);
}

/** The company section a URL is in (`/c/{id}/agents/…` → `agents`), when it is one of the nav's. */
export function navSectionOf(url: string): NavItem | undefined {
  const match = /^\/c\/[^/]+\/([a-z-]+)/.exec(url);
  return COMPANY_NAV.find((item) => item.path === match?.[1]);
}

export const ADMIN_NAV: readonly { path: string; label: string; icon: string }[] = [
  { path: '/admin/users', label: 'Users', icon: 'lucideUsers' },
  { path: '/admin/companies', label: 'Companies', icon: 'lucideBuilding2' },
  { path: '/admin/audit', label: 'Instance audit log', icon: 'lucideHistory' },
  { path: '/admin/errors', label: 'Errors', icon: 'lucideBug' },
  { path: '/admin/licence', label: 'Licence', icon: 'lucideKeyRound' },
];

/** Every icon the navigation draws, for `provideIcons`. */
export const NAV_ICONS = {
  lucideArchive,
  lucideBot,
  lucideBrainCircuit,
  lucideBug,
  lucideBuilding2,
  lucideHistory,
  lucideIdCard,
  lucideInbox,
  lucideKeyRound,
  lucideLibraryBig,
  lucideMessagesSquare,
  lucideOctagonPause,
  lucidePlug,
  lucideScrollText,
  lucideServer,
  lucideSettings,
  lucideUserRoundKey,
  lucideUsers,
  lucideZap,
};
