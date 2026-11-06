/**
 * The cast of the fixtures, after the e2e seed (plan §8): the company Acme; Alice, Bob, Carol and
 * Dave; the group Analysts; the service account svc-reader; GitHub and Jira; the agent Business
 * analyst, shared with Analysts. Ids are short and readable rather than UUIDs, so a failing spec
 * says what it is about.
 */
export const IDS = {
  company: 'c1',
  /** Users (the instance's ids) and their principals in Acme. */
  aliceUser: 'u-alice',
  alice: 'p-alice',
  bobUser: 'u-bob',
  bob: 'p-bob',
  carolUser: 'u-carol',
  carol: 'p-carol',
  daveUser: 'u-dave',
  dave: 'p-dave',
  ownerUser: 'u-olga',
  owner: 'p-olga',
  analysts: 'g-analysts',
  svcReader: 'p-svc-reader',
  github: 'sys-github',
  jira: 'sys-jira',
  crm: 'sys-crm',
  slack: 'sys-slack',
  teams: 'sys-teams',
  hook: 'sys-hook',
  svcSlackBot: 'p-svc-slack-bot',
  slackBotAccount: 'acc-svc-slack-bot',
  shopRepo: 'repo-shop',
  docsRepo: 'repo-docs',
  readerGithubAccount: 'acc-svc-reader-github',
  analystsGrant: 'grant-analysts-github',
  agent: 'a-ba',
  /** Skills of the library: Bob's, shared with Analysts, and Carol's. */
  skill: 'sk-jira-ticket',
  otherSkill: 'sk-release-notes',
  conversation: 'cv1',
  run: 'run1',
  companyHost: 'h-office',
  personalHost: 'h-alice-macbook',
} as const;

/** The fixtures' clock: the day of the plan. */
export const AT = {
  earlier: '2026-10-05T08:00:00Z',
  start: '2026-10-12T08:13:00Z',
  started: '2026-10-12T08:13:48Z',
  finished: '2026-10-12T08:20:00Z',
  resets: '2026-11-01T00:00:00Z',
} as const;
