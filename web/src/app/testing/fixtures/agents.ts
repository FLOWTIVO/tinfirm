import type {
  Agent,
  AgentReadiness,
  AgentReadinessSystem,
  AgentSettings,
  AgentSummary,
  AgentWidening,
  MemoryNote,
  MemoryNotes,
} from '../../api/generated/models';
import { aGroupRef } from './members';
import { AT, IDS } from './ids';

/**
 * Tag `Agents`: the Business analyst, owned by Bob and shared with Analysts. It uses GitHub (one
 * tool that runs, one that asks) and Jira, the repository acme/shop read-only and acme/docs with
 * writes, and one network host.
 */

export function anAgentSummary(overrides: Partial<AgentSummary> = {}): AgentSummary {
  return {
    id: IDS.agent,
    name: 'Business analyst',
    handle: 'ba',
    description: 'Prepares tickets: reads the code and writes the brief.',
    runtimes: ['CLAUDE_CODE', 'CODEX'],
    status: 'ACTIVE',
    ownerPrincipalId: IDS.bob,
    ownerName: 'Bob Svoboda',
    version: 4,
    usableByMe: true,
    editableByMe: false,
    wideningPending: false,
    skillUpdates: 0,
    createdAt: AT.earlier,
    updatedAt: AT.earlier,
    ...overrides,
  };
}

export function anAgent(overrides: Partial<Agent> = {}): Agent {
  return {
    id: IDS.agent,
    name: 'Business analyst',
    handle: 'ba',
    description: 'Prepares tickets: reads the code and writes the brief.',
    instructions: 'Read the issue and the code, then write a brief.',
    runtimes: ['CLAUDE_CODE', 'CODEX'],
    turnTimeLimitMinutes: null,
    defaultTurnTimeLimitMinutes: 60,
    status: 'ACTIVE',
    ownerPrincipalId: IDS.bob,
    ownerName: 'Bob Svoboda',
    version: 4,
    acceptedVersion: 4,
    usableByMe: true,
    editableByMe: false,
    wideningPending: false,
    widening: null,
    memoryAllowed: true,
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
            effect: 'READ',
            mode: 'ALLOW',
            chosenForEffect: 'READ',
            offered: true,
          },
          {
            name: 'create_pull_request',
            title: 'Open a pull request',
            effect: 'WRITE',
            mode: 'ASK',
            chosenForEffect: 'WRITE',
            offered: true,
          },
        ],
      },
      {
        systemId: IDS.jira,
        name: 'Jira',
        handle: 'jira',
        kind: 'JIRA_CLOUD',
        tools: [
          {
            name: 'add_comment',
            title: 'Comment on an issue',
            effect: 'WRITE',
            mode: 'ALLOW',
            chosenForEffect: 'WRITE',
            offered: true,
          },
        ],
      },
    ],
    repositories: [
      {
        repositoryId: IDS.shopRepo,
        systemId: IDS.github,
        name: 'acme/shop',
        url: 'https://github.com/acme/shop',
        access: 'READ',
      },
      {
        repositoryId: IDS.docsRepo,
        systemId: IDS.github,
        name: 'acme/docs',
        url: 'https://github.com/acme/docs',
        access: 'WRITE',
      },
    ],
    hosts: [{ host: 'registry.npmjs.org', addedAt: AT.earlier, addedBy: 'Bob Svoboda' }],
    shares: [aGroupRef()],
    memberShares: [],
    skills: [],
    createdAt: AT.earlier,
    updatedAt: AT.earlier,
    ...overrides,
  };
}

/**
 * What version 6 adds over the version 4 the person accepted: Jira, a tool that now runs without
 * asking, acme/docs with writes, and a network host.
 */
export function aWidening(overrides: Partial<AgentWidening> = {}): AgentWidening {
  return {
    acceptedVersion: 4,
    currentVersion: 6,
    systems: [IDS.jira],
    tools: [
      { systemId: IDS.jira, tool: 'add_comment', mode: 'ALLOW', was: null },
      { systemId: IDS.github, tool: 'create_pull_request', mode: 'ALLOW', was: 'ASK' },
    ],
    repositories: [{ repositoryId: IDS.docsRepo, access: 'WRITE', was: 'READ' }],
    hosts: ['pypi.org'],
    ...overrides,
  };
}

export function anAgentSettings(overrides: Partial<AgentSettings> = {}): AgentSettings {
  return {
    agentId: IDS.agent,
    acceptedVersion: 4,
    currentVersion: 4,
    stored: false,
    runtime: 'CLAUDE_CODE',
    chosenRuntime: null,
    modelAccess: 'BUDGET',
    subscription: null,
    model: null,
    effort: 'DEFAULT',
    instructions: null,
    memory: false,
    autoRetry: false,
    defaultRepositoryIds: [],
    defaultProjects: [],
    systemsOff: [],
    toolModes: [],
    ownServers: [],
    skillsOff: [],
    widening: null,
    ...overrides,
  };
}

export function aReadinessSystem(
  overrides: Partial<AgentReadinessSystem> = {},
): AgentReadinessSystem {
  return {
    systemId: IDS.github,
    name: 'GitHub',
    kind: 'GITHUB',
    binding: 'OWN',
    access: 'WRITE',
    grantId: null,
    own: false,
    ...overrides,
  };
}

/** Alice's readiness: her own GitHub account, Jira to connect, her budget, a host can run it. */
export function aReadiness(overrides: Partial<AgentReadiness> = {}): AgentReadiness {
  return {
    settings: anAgentSettings(),
    systems: [
      aReadinessSystem(),
      aReadinessSystem({
        systemId: IDS.jira,
        name: 'Jira',
        kind: 'JIRA_CLOUD',
        binding: 'CONNECT',
        access: null,
      }),
    ],
    payer: { state: 'READY', modelAccess: 'BUDGET', models: [], reason: null, until: null },
    hosts: { runnable: true, eligible: 2, reason: null },
    ...overrides,
  };
}

/** A note the Business analyst keeps: about its owner in general, used a week ago. */
export function aMemoryNote(overrides: Partial<MemoryNote> = {}): MemoryNote {
  const number = overrides.number ?? 1;
  return {
    number,
    id: `m${number}`,
    state: 'ACTIVE',
    about: 'GENERAL',
    systemId: null,
    repositoryId: null,
    text: 'Alice wants one commit per pull request.',
    replaces: null,
    conversationId: null,
    createdAt: AT.earlier,
    updatedAt: AT.earlier,
    usedAt: AT.earlier,
    ...overrides,
  };
}

/** A memory in effect: a note about Alice, one about acme/shop and one about Jira. */
export function someMemoryNotes(overrides: Partial<MemoryNotes> = {}): MemoryNotes {
  const notes = overrides.notes ?? [
    aMemoryNote(),
    aMemoryNote({
      number: 2,
      about: 'REPOSITORY',
      repositoryId: IDS.shopRepo,
      text: 'The tests run with make test-fast.',
    }),
    aMemoryNote({
      number: 3,
      about: 'SYSTEM',
      systemId: IDS.jira,
      text: 'SHOP issues use the component field for the team.',
    }),
  ];
  return {
    inEffect: true,
    characters: notes
      .filter((n) => n.state === 'ACTIVE')
      .reduce((sum, n) => sum + n.text.length, 0),
    maxCharacters: 20000,
    compactedAt: null,
    ...overrides,
    notes,
  };
}
