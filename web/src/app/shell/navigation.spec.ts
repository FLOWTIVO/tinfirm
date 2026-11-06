import type { Role } from '../api/generated/models';
import type { CompanyAccess } from '../core/permissions';
import { companyNav, navSectionOf } from './navigation';

const as = (role: Role | null, instanceAdmin = false): CompanyAccess => ({ role, instanceAdmin });

const labels = (access: CompanyAccess) =>
  companyNav(access).map((section) => [section.label, section.items.map((i) => i.label)]);

describe('the company navigation (plan §3)', () => {
  it('shows a member the six items of the work, the skills beside the agents', () => {
    expect(labels(as('MEMBER'))).toEqual([
      ['Work', ['Inbox', 'Conversations', 'Agents', 'Skills', 'Triggers', 'Accounts']],
    ]);
  });

  it.each(['OWNER', 'ADMIN'] as const)('adds the company’s setup for %s', (role) => {
    expect(labels(as(role))).toEqual([
      ['Work', ['Inbox', 'Conversations', 'Agents', 'Skills', 'Triggers', 'Accounts']],
      [
        'Company',
        [
          'Systems',
          'Service accounts',
          'Model access',
          'Runner hosts',
          'Members',
          'Kill switches',
          'Audit log',
          'Retention',
          'Settings',
        ],
      ],
    ]);
  });

  it('gives an instance admin who is not a member the setup, but no Accounts', () => {
    const [work, company] = labels(as(null, true));
    expect(work).toEqual(['Work', ['Inbox', 'Conversations', 'Agents', 'Skills', 'Triggers']]);
    expect(company[0]).toBe('Company');
  });

  it('finds the section of a URL, for the company switcher', () => {
    expect(navSectionOf('/c/c1/agents/a1/settings')?.path).toBe('agents');
    expect(navSectionOf('/c/c1/skills/s1')?.path).toBe('skills');
    expect(navSectionOf('/c/c1/service-identities?tab=grants')?.path).toBe('service-identities');
    expect(navSectionOf('/c/c1/runs/r1')).toBeUndefined();
    expect(navSectionOf('/me')).toBeUndefined();
  });
});
