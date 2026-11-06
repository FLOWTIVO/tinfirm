import type { GroupUsage } from '../../../api/generated/models';
import { aGrant, aGroupUsage } from '../../../testing/fixtures';
import { accountLines, grantPhrase, usageSummary } from './group-usage';

const NOTHING: GroupUsage = { agents: [], grants: [], serviceIdentities: [] };

const MORE: GroupUsage = aGroupUsage({
  agents: [
    { id: 'a-1', name: 'Analyst' },
    { id: 'a-2', name: 'Reviewer' },
  ],
  grants: [aGrant(), aGrant({ id: 'grant-jira', systemName: 'Jira', access: 'WRITE' })],
});

describe('what a group may use', () => {
  it.each<[string, GroupUsage, string]>([
    ['two agents and two credentials', MORE, '2 agents, 2 credentials, 1 service account'],
    ['one of each', aGroupUsage(), '1 agent, 1 credential, 1 service account'],
    ['a new group', NOTHING, 'Nothing shared with it or lent to it yet'],
  ])('sums up %s', (_, usage, summary) => {
    expect(usageSummary(usage)).toBe(summary);
  });

  it('reads a grant as its members act through it', () => {
    expect(grantPhrase(aGrant())).toBe('GitHub with svc-reader, read-only');
  });

  it('names the systems of one credential name and access in one line of the row', () => {
    expect(accountLines(MORE)).toEqual([
      'GitHub with svc-reader, read-only',
      'Jira with svc-reader, read and write',
    ]);
    const same = aGroupUsage({
      grants: [aGrant({ id: 'grant-jira', systemName: 'Jira' }), aGrant()],
    });
    expect(accountLines(same)).toEqual(['GitHub and Jira with svc-reader, read-only']);
    expect(accountLines(NOTHING)).toEqual([]);
  });
});
