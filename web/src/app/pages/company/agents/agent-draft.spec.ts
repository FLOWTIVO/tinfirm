import type { Agent } from '../../../api/generated/models';
import { anAgent, anAgentSkill, aSystemTool, IDS } from '../../../testing/fixtures';
import {
  AGENT_SYSTEM_KINDS,
  type AgentDraft,
  asksSinceItWrites,
  changedSections,
  changeOf,
  createRequest,
  draftOf,
  EMPTY_DRAFT,
  effectiveMode,
  hostCovers,
  hostProblem,
  normalizeHost,
  skillInputs,
  toolsForNewSystem,
  turnTimeLimitProblem,
  canAct,
  wideningOf,
} from './agent-draft';

const agent = anAgent();
const saved = draftOf(agent);

/** The saved agent with one change. */
function changed(fn: (d: AgentDraft) => Partial<AgentDraft>): AgentDraft {
  return { ...saved, ...fn(saved) };
}

const confluence = {
  systemId: 'sys-confluence',
  name: 'Confluence',
  handle: 'confluence',
  kind: 'MCP' as const,
  tools: [
    {
      name: 'read_page',
      title: 'Read a page',
      mode: 'ALLOW' as const,
      effect: 'READ' as const,
      offered: true,
      chosenForEffect: null,
      picked: null,
    },
    {
      name: 'write_page',
      title: 'Write a page',
      mode: 'ASK' as const,
      effect: 'UNKNOWN' as const,
      offered: true,
      chosenForEffect: null,
      picked: null,
    },
  ],
};

describe('wideningOf (plan §4.3: the notice before Save)', () => {
  const widening: [string, AgentDraft, string[]][] = [
    [
      'a new system',
      changed((d) => ({ systems: [...d.systems, confluence] })),
      ['Confluence (new), with 2 tools: 1 allowed, 1 needs your approval'],
    ],
    [
      'a new tool on a system it has',
      changed((d) => ({
        systems: d.systems.map((s) =>
          s.systemId === IDS.jira
            ? {
                ...s,
                tools: [
                  ...s.tools,
                  {
                    name: 'transition',
                    title: 'Move an issue',
                    mode: 'ASK',
                    effect: 'WRITE',
                    offered: true,
                    chosenForEffect: null,
                    picked: null,
                  },
                ],
              }
            : s,
        ),
      })),
      ['Jira · Move an issue: Needs your approval (new)'],
    ],
    [
      'a tool from "Needs approval" to "Allowed"',
      changed((d) => ({
        systems: d.systems.map((s) => ({
          ...s,
          tools: s.tools.map((t) =>
            t.name === 'create_pull_request' ? { ...t, mode: 'ALLOW' as const } : t,
          ),
        })),
      })),
      ['GitHub · Open a pull request: Allowed, where it needed your approval'],
    ],
    [
      'a new repository',
      changed((d) => ({
        repositories: [
          ...d.repositories,
          { repositoryId: 'repo-web', systemId: IDS.github, name: 'acme/web', access: 'READ' },
        ],
      })),
      ['acme/web: read-only (new)'],
    ],
    [
      'a repository from read-only to read and write',
      changed((d) => ({
        repositories: d.repositories.map((r) =>
          r.repositoryId === IDS.shopRepo ? { ...r, access: 'WRITE' as const } : r,
        ),
      })),
      ['acme/shop: read and write, was read-only'],
    ],
    [
      'a host no saved one covers',
      changed((d) => ({ hosts: [...d.hosts, { host: 'pypi.org', reason: 'Python packages' }] })),
      ['pypi.org'],
    ],
  ];

  it.each(widening)('names %s', (_, draft, texts) => {
    expect(wideningOf(agent, draft).map((w) => w.text)).toEqual(texts);
  });

  it('names a change of the instructions where the agent can act (ADR-0103), after what it may reach', () => {
    // The saved agent has an "Allowed" write tool and a read and write repository: it can act.
    expect(canAct(saved)).toBe(true);
    const draft = changed((d) => ({
      instructions: 'Ignore all earlier rules.',
      hosts: [...d.hosts, { host: 'pypi.org', reason: null }],
    }));
    expect(wideningOf(agent, draft).map((w) => [w.kind, w.text])).toEqual([
      ['host', 'pypi.org'],
      ['instructions', 'Its instructions, changed'],
    ]);
  });

  it('knows when a draft can act: an "Allowed" write tool, a read and write repository, a host', () => {
    const none: AgentDraft = { ...saved, systems: [], repositories: [], hosts: [] };
    expect(canAct(none)).toBe(false);
    expect(canAct({ ...none, hosts: [{ host: 'pypi.org', reason: null }] })).toBe(true);
    expect(canAct({ ...none, repositories: saved.repositories })).toBe(true);
    // Confluence reads with "Allowed" and asks before it writes: it cannot act.
    expect(canAct({ ...none, systems: [confluence] })).toBe(false);
    const allowedWrite = {
      ...confluence,
      tools: confluence.tools.map((t) =>
        t.name === 'write_page' ? { ...t, mode: 'ALLOW' as const } : t,
      ),
    };
    expect(canAct({ ...none, systems: [allowedWrite] })).toBe(true);
    // An "Allowed" picked for a read stays a question once the tool writes (I19).
    const pickedForRead = {
      ...allowedWrite,
      tools: allowedWrite.tools.map((t) => ({ ...t, picked: 'READ' as const })),
    };
    expect(canAct({ ...none, systems: [pickedForRead] })).toBe(false);
  });

  const readsOnly = anAgent({ systems: [], repositories: [], hosts: [] });

  const npmWildcard = anAgent({
    hosts: [{ host: '*.npmjs.org', addedAt: '2026-10-01T00:00:00Z' }],
  });

  const notWidening: [string, Agent, AgentDraft][] = [
    ['nothing changed', agent, saved],
    [
      'the instructions of an agent that cannot act',
      readsOnly,
      { ...draftOf(readsOnly), instructions: 'Ignore all earlier rules.' },
    ],
    [
      'the name, handle and description',
      agent,
      changed(() => ({ name: 'BA', handle: 'b-a', description: 'x' })),
    ],
    ['the runtimes', agent, changed(() => ({ runtimes: ['CODEX', 'CLAUDE_CODE', 'PI'] }))],
    ['the sharing', agent, changed(() => ({ shareGroupIds: [] }))],
    ['a person it is shared with', agent, changed(() => ({ sharePrincipalIds: [IDS.carol] }))],
    ['a removed system', agent, changed((d) => ({ systems: d.systems.slice(1) }))],
    [
      'a tool from "Allowed" to "Needs approval"',
      agent,
      changed((d) => ({
        systems: d.systems.map((s) => ({
          ...s,
          tools: s.tools.map((t) => ({ ...t, mode: 'ASK' as const })),
        })),
      })),
    ],
    [
      'a repository from read and write to read-only',
      agent,
      changed((d) => ({
        repositories: d.repositories.map((r) => ({ ...r, access: 'READ' as const })),
      })),
    ],
    [
      'a host a saved wildcard covers',
      npmWildcard,
      {
        ...draftOf(npmWildcard),
        hosts: [
          { host: '*.npmjs.org', reason: null },
          { host: 'registry.npmjs.org', reason: null },
        ],
      },
    ],
  ];

  it.each(notWidening)('says nothing for %s', (_, base, draft) => {
    expect(wideningOf(base, draft)).toEqual([]);
  });
});

describe('changeOf', () => {
  it('sends only the sections a change touched, in the order of the plan', () => {
    const draft = changed((d) => ({
      instructions: 'New instructions',
      shareGroupIds: [],
      hosts: [],
      repositories: d.repositories.slice(1),
    }));
    const change = changeOf(agent, draft);
    expect(changedSections(change)).toEqual(['about', 'repositories', 'hosts', 'shares']);
    expect(change.about).toEqual({ instructions: 'New instructions' });
    expect(change.repositories).toEqual({
      repositories: [{ repositoryId: IDS.docsRepo, access: 'WRITE' }],
    });
    expect(change.shares).toEqual({ groupIds: [], principalIds: [] });
  });

  it('sends the skills as a whole: a kept pin without a version, a moved or new one with it', () => {
    const pinned = anAgent({
      skills: [
        anAgentSkill({
          skillId: 'sk-wiki',
          name: 'ask-the-wiki',
          version: 1,
          latestVersion: 1,
          deleted: true,
        }),
        anAgentSkill(),
      ],
    });
    const base = draftOf(pinned);
    expect(changedSections(changeOf(pinned, base))).toEqual([]);
    const draft: AgentDraft = {
      ...base,
      skills: [
        base.skills[0],
        { ...base.skills[1], version: 3 },
        {
          skillId: IDS.otherSkill,
          name: 'release-notes',
          description: 'Writes the release notes.',
          version: 1,
          latestVersion: 1,
          deleted: false,
        },
      ],
    };
    const change = changeOf(pinned, draft);
    expect(changedSections(change)).toEqual(['skills']);
    expect(change.skills).toEqual({
      skills: [
        { skillId: 'sk-wiki' },
        { skillId: IDS.skill, version: 3 },
        { skillId: IDS.otherSkill, version: 1 },
      ],
    });
    // A new agent's skills each name their version.
    expect(skillInputs(draft)).toEqual([
      { skillId: 'sk-wiki', version: 1 },
      { skillId: IDS.skill, version: 3 },
      { skillId: IDS.otherSkill, version: 1 },
    ]);
    // The agent can act: a moved pin and a new skill widen it (ADR-0103); without a way to act
    // they do not.
    expect(wideningOf(pinned, draft).map((w) => w.kind)).toEqual(['skill', 'skill']);
    expect(wideningOf(pinned, draft).map((w) => w.text)[1]).toBe(
      'Skill release-notes: version 1 (new)',
    );
    const unable = anAgent({ ...pinned, systems: [], repositories: [], hosts: [] });
    expect(wideningOf(unable, { ...draft, systems: [], repositories: [], hosts: [] })).toEqual([]);
    // A removed skill is a change too.
    expect(changeOf(pinned, { ...base, skills: [base.skills[1]] }).skills).toEqual({
      skills: [{ skillId: IDS.skill }],
    });
  });

  it('sends the groups and the people as a whole when only a person changed', () => {
    const change = changeOf(
      agent,
      changed(() => ({ sharePrincipalIds: [IDS.carol] })),
    );
    expect(changedSections(change)).toEqual(['shares']);
    expect(change.shares).toEqual({ groupIds: [IDS.analysts], principalIds: [IDS.carol] });
    const shared = { ...agent, memberShares: [{ principalId: IDS.carol, name: 'Carol Dvorak' }] };
    expect(changedSections(changeOf(shared, draftOf(shared)))).toEqual([]);
  });

  it('sends nothing for the saved agent, and the whole systems list for a mode', () => {
    expect(changedSections(changeOf(agent, saved))).toEqual([]);
    const draft = changed((d) => ({
      systems: d.systems.map((s) => ({
        ...s,
        tools: s.tools.map((t) => ({ ...t, mode: 'ASK' as const })),
      })),
    }));
    expect(changeOf(agent, draft).systems).toEqual({
      systems: [
        {
          systemId: IDS.github,
          tools: [
            { name: 'get_file_contents', mode: 'ASK' },
            { name: 'create_pull_request', mode: 'ASK' },
          ],
        },
        { systemId: IDS.jira, tools: [{ name: 'add_comment', mode: 'ASK' }] },
      ],
    });
  });
});

describe('a mode the owner picks (chosenForEffect, I19)', () => {
  /** The agent with GitHub's read tool allowed while it read, and writing now. */
  const writesNow = anAgent({
    systems: [
      {
        systemId: IDS.github,
        name: 'GitHub',
        handle: 'github',
        kind: 'GITHUB',
        tools: [
          {
            name: 'get_file_contents',
            title: 'Read a file',
            mode: 'ALLOW',
            effect: 'WRITE',
            chosenForEffect: 'READ',
            offered: true,
          },
          {
            name: 'create_pull_request',
            title: 'Open a pull request',
            mode: 'ASK',
            effect: 'WRITE',
            chosenForEffect: 'WRITE',
            offered: true,
          },
        ],
      },
    ],
  });
  const base = draftOf(writesNow);
  const pick = (name: string, mode: 'ALLOW' | 'ASK', picked: 'READ' | 'WRITE') => ({
    ...base,
    systems: base.systems.map((s) => ({
      ...s,
      tools: s.tools.map((t) => (t.name === name ? { ...t, mode, picked } : t)),
    })),
  });

  it('counts "Allowed" for a read as "Needs approval" once it writes, until picked again', () => {
    const [get] = base.systems[0].tools;
    expect(effectiveMode(get)).toBe('ASK');
    expect(asksSinceItWrites(get)).toBe(true);
    const picked = pick('get_file_contents', 'ALLOW', 'WRITE').systems[0].tools[0];
    expect(effectiveMode(picked)).toBe('ALLOW');
    expect(asksSinceItWrites(picked)).toBe(false);
  });

  it('confirms "Allowed" for writes in one save, with chosenForEffect for that tool only', () => {
    const draft = pick('get_file_contents', 'ALLOW', 'WRITE');
    expect(changeOf(writesNow, draft).systems).toEqual({
      systems: [
        {
          systemId: IDS.github,
          tools: [
            { name: 'get_file_contents', mode: 'ALLOW', chosenForEffect: 'WRITE' },
            { name: 'create_pull_request', mode: 'ASK' },
          ],
        },
      ],
    });
    expect(wideningOf(writesNow, draft).map((w) => w.text)).toEqual([
      'GitHub · Read a file: Allowed, where it needed your approval',
    ]);
  });

  it('sends nothing for a pick that chooses nothing anew, and nothing silently on another save', () => {
    // Picked back to what is stored, for the effect it was chosen for: no change.
    const same = pick('create_pull_request', 'ASK', 'WRITE');
    expect(changedSections(changeOf(writesNow, same))).toEqual([]);
    // Another section saved: the tools are not sent, so the "Allowed" that asks keeps asking.
    const other = { ...base, instructions: 'New instructions' };
    expect(changedSections(changeOf(writesNow, other))).toEqual(['about']);
    // Nothing of the tools; the instructions themselves widen it, as it can act (ADR-0103).
    expect(wideningOf(writesNow, other).map((w) => w.kind)).toEqual(['instructions']);
  });

  it('sends the picks of a new agent', () => {
    const draft: AgentDraft = {
      ...EMPTY_DRAFT,
      name: 'New',
      systems: [
        {
          ...base.systems[0],
          tools: [{ ...base.systems[0].tools[1], mode: 'ALLOW', picked: 'WRITE' }],
        },
      ],
    };
    expect(createRequest(draft).systems).toEqual([
      {
        systemId: IDS.github,
        tools: [{ name: 'create_pull_request', mode: 'ALLOW', chosenForEffect: 'WRITE' }],
      },
    ]);
  });
});

describe('createRequest', () => {
  it('makes version 1 in one call, a blank handle made from the name', () => {
    const body = createRequest({ ...saved, handle: '  ', description: '' });
    expect(body).toMatchObject({
      name: 'Business analyst',
      handle: null,
      description: null,
      runtimes: ['CLAUDE_CODE', 'CODEX'],
      shareGroupIds: [IDS.analysts],
      sharePrincipalIds: [],
      hosts: [{ host: 'registry.npmjs.org', reason: null }],
    });
    expect(body.systems?.map((s) => s.systemId)).toEqual([IDS.github, IDS.jira]);
    // No time limit of its own: the deployment's.
    expect(body).not.toHaveProperty('turnTimeLimitMinutes');
    expect(createRequest({ ...saved, turnTimeLimitMinutes: ' 120 ' }).turnTimeLimitMinutes).toBe(
      120,
    );
  });
});

describe('the time limit of a turn', () => {
  it('takes 5 to 1440 whole minutes, or empty for the default', () => {
    for (const typed of ['', '  ', '5', '60', '1440']) {
      expect(turnTimeLimitProblem(typed)).toBeNull();
    }
    for (const typed of ['4', '1441', '0', '90.5', 'an hour']) {
      expect(turnTimeLimitProblem(typed)).toBe(
        'Enter 5 to 1440 minutes, or leave it empty for the default.',
      );
    }
  });

  it('sends a change with the other fields of the agent, and 0 to return to the default', () => {
    const limited: Agent = { ...agent, turnTimeLimitMinutes: 180 };
    expect(draftOf(limited).turnTimeLimitMinutes).toBe('180');
    expect(draftOf(agent).turnTimeLimitMinutes).toBe('');
    expect(changeOf(agent, { ...saved, turnTimeLimitMinutes: '240' }).about).toEqual({
      turnTimeLimitMinutes: 240,
    });
    expect(changeOf(limited, { ...draftOf(limited), turnTimeLimitMinutes: '' }).about).toEqual({
      turnTimeLimitMinutes: 0,
    });
    expect(changedSections(changeOf(limited, draftOf(limited)))).toEqual([]);
    // It never widens: people need not accept it.
    expect(wideningOf(agent, { ...saved, turnTimeLimitMinutes: '1440' })).toEqual([]);
  });
});

describe('memory (ADR-0063)', () => {
  it('is allowed by default, sent with a new agent, and forbidden or allowed again as a change', () => {
    expect(EMPTY_DRAFT.memoryAllowed).toBe(true);
    expect(draftOf(agent).memoryAllowed).toBe(true);
    expect(createRequest({ ...saved, memoryAllowed: false }).memoryAllowed).toBe(false);

    expect(changeOf(agent, { ...saved, memoryAllowed: false }).about).toEqual({
      memoryAllowed: false,
    });
    const forbidden: Agent = { ...agent, memoryAllowed: false };
    expect(draftOf(forbidden).memoryAllowed).toBe(false);
    expect(changeOf(forbidden, { ...draftOf(forbidden), memoryAllowed: true }).about).toEqual({
      memoryAllowed: true,
    });
    expect(changedSections(changeOf(forbidden, draftOf(forbidden)))).toEqual([]);
  });

  it('never widens: memory reaches nothing a conversation could not reach already', () => {
    const forbidden: Agent = { ...agent, memoryAllowed: false };
    expect(wideningOf(forbidden, { ...draftOf(forbidden), memoryAllowed: true })).toEqual([]);
  });
});

describe('the tools of a system', () => {
  it('lists the kinds the server lets an agent list: Slack for its history, never Teams', () => {
    expect([...AGENT_SYSTEM_KINDS].sort()).toEqual(
      ['GIT', 'GITHUB', 'GITLAB', 'JIRA_CLOUD', 'JIRA_DC', 'MCP', 'SLACK'].sort(),
    );
    expect(AGENT_SYSTEM_KINDS.has('TEAMS')).toBe(false);
    expect(AGENT_SYSTEM_KINDS.has('WEBHOOK')).toBe(false);
  });

  it('fills a new system as the server does: reads run, writes and unconfirmed ask', () => {
    const tools = toolsForNewSystem([
      aSystemTool({ name: 'get', effect: 'READ' }),
      aSystemTool({ name: 'comment', effect: 'WRITE' }),
      aSystemTool({ name: 'mystery', effect: 'UNKNOWN' }),
      aSystemTool({ name: 'off', effect: 'READ', enabled: false }),
    ]);
    expect(tools.map((t) => [t.name, t.mode])).toEqual([
      ['get', 'ALLOW'],
      ['comment', 'ASK'],
      ['mystery', 'ASK'],
    ]);
  });

  it('counts "Allowed" chosen for a read as "Needs approval" once the tool writes (I19)', () => {
    const tool = {
      name: 't',
      title: null,
      mode: 'ALLOW' as const,
      offered: true,
      chosenForEffect: 'READ' as const,
    };
    expect(effectiveMode({ ...tool, effect: 'READ' })).toBe('ALLOW');
    expect(effectiveMode({ ...tool, effect: 'WRITE' })).toBe('ASK');
    expect(effectiveMode({ ...tool, effect: 'UNKNOWN' })).toBe('ASK');
    expect(effectiveMode({ ...tool, effect: 'WRITE', chosenForEffect: 'WRITE' })).toBe('ALLOW');
  });
});

describe('network hosts', () => {
  it.each([
    ['Registry.NPMjs.org.', 'registry.npmjs.org'],
    ['example.com:443', 'example.com'],
    ['example.com:8443', 'example.com:8443'],
  ])('stores %s as %s', (raw, host) => {
    expect(normalizeHost(raw)).toBe(host);
  });

  it.each([
    ['https://example.com', 'without a scheme'],
    ['example.com/path', 'without a scheme'],
    ['10.0.0.1', 'IP addresses'],
    ['*.com', 'two labels after it'],
    ['example.com:99999', 'port'],
    ['', 'Name the host'],
  ])('refuses %s', (raw, words) => {
    expect(hostProblem(raw)).toContain(words);
  });

  it('accepts hosts and wildcards, and a wildcard covers its subdomains', () => {
    expect(hostProblem('registry.npmjs.org')).toBeNull();
    expect(hostProblem('*.example.com:8080')).toBeNull();
    expect(hostCovers('*.example.com', 'a.b.example.com')).toBe(true);
    expect(hostCovers('*.example.com', 'a.example.com:8080')).toBe(false);
    expect(hostCovers('example.com', 'a.example.com')).toBe(false);
  });
});
