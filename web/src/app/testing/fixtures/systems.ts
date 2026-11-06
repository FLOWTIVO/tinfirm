import type {
  ExternalSystem,
  SystemRef,
  SystemKind,
  SystemRepository,
  SystemTool,
} from '../../api/generated/models';
import { AT, IDS } from './ids';

/** Tag `Systems`: company systems, their repositories and tools; one personal MCP server. */

/** The kinds an agent may call tools on, which receive what it passes (the server's `SystemKind.receivesData`). */
const RECEIVES_DATA: readonly SystemKind[] = [
  'JIRA_DC',
  'JIRA_CLOUD',
  'GITHUB',
  'GITLAB',
  'MCP',
  'SLACK',
];

export function aSystem(overrides: Partial<ExternalSystem> = {}): ExternalSystem {
  return {
    receivesData: RECEIVES_DATA.includes(overrides.kind ?? 'GITHUB'),
    internalHost: false,
    id: IDS.github,
    name: 'GitHub',
    handle: 'github',
    kind: 'GITHUB',
    baseUrl: 'https://api.github.com',
    status: 'OK',
    personal: false,
    oauthAvailable: true,
    accountsRequired: true,
    app: {
      clientId: 'Iv1.acme',
      clientSecretSet: true,
      callbackUrl: 'https://tinfirm.acme.test/api/oauth/callback',
    },
    oauthCallbackUrl: 'https://tinfirm.acme.test/api/oauth/callback',
    createdAt: AT.earlier,
    updatedAt: AT.earlier,
    ...overrides,
  };
}

export function aJiraSystem(overrides: Partial<ExternalSystem> = {}): ExternalSystem {
  return aSystem({
    id: IDS.jira,
    name: 'Jira',
    handle: 'jira',
    kind: 'JIRA_CLOUD',
    baseUrl: 'https://acme.atlassian.net',
    oauthAvailable: false,
    app: null,
    ...overrides,
  });
}

/** A member's own MCP server (PLATFORM.md §5.1). */
export function aPersonalMcpServer(overrides: Partial<ExternalSystem> = {}): ExternalSystem {
  return aSystem({
    id: IDS.crm,
    name: 'My CRM',
    handle: 'crm',
    kind: 'MCP',
    baseUrl: 'https://crm.acme.test/mcp',
    personal: true,
    oauthAvailable: false,
    app: null,
    ...overrides,
  });
}

export function aSystemRef(overrides: Partial<SystemRef> = {}): SystemRef {
  return {
    id: IDS.github,
    name: 'GitHub',
    handle: 'github',
    kind: 'GITHUB',
    personal: false,
    ...overrides,
  };
}

export function aSystemRepository(overrides: Partial<SystemRepository> = {}): SystemRepository {
  return {
    id: IDS.shopRepo,
    systemId: IDS.github,
    name: 'acme/shop',
    url: 'https://github.com/acme/shop',
    defaultBranch: 'main',
    ...overrides,
  };
}

export function aSystemTool(overrides: Partial<SystemTool> = {}): SystemTool {
  return {
    name: 'create_pull_request',
    title: 'Open a pull request',
    description: 'Opens a pull request from a branch.',
    effect: 'WRITE',
    enabled: true,
    fixed: true,
    inputSchema: { type: 'object' },
    ...overrides,
  };
}
