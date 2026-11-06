import type {
  ModelAccessView,
  ModelSettings,
  ModelSubscription,
  RunnerHost,
} from '../../api/generated/models';
import { AT, IDS } from './ids';

/** Tags `ModelSettings`, `ModelAccess` and `RunnerHosts`: who pays, and where work runs. */

/** Alice's model access: $38.20 of a $50 monthly budget left, resetting on 1 November. */
export function aModelAccess(overrides: Partial<ModelAccessView> = {}): ModelAccessView {
  return {
    principalId: IDS.alice,
    gatewayConfigured: true,
    keyStatus: 'OK',
    budgetUsd: 50,
    budgetPeriod: '30d',
    budgetResetsAt: AT.resets,
    budgetOverridden: false,
    spentUsd: 11.8,
    models: [],
    modelsOverridden: false,
    subscriptions: [],
    syncedAt: AT.start,
    keyProvided: false,
    keyProvidedBySelf: false,
    canEnterOwnKey: false,
    ...overrides,
  };
}

export function aSubscription(overrides: Partial<ModelSubscription> = {}): ModelSubscription {
  return {
    kind: 'CLAUDE_LOGIN',
    label: 'claude-max',
    runnerHostId: IDS.personalHost,
    runnerHostName: 'alice-macbook',
    runtimes: ['CLAUDE_CODE'],
    status: 'AVAILABLE',
    reported: true,
    ...overrides,
  };
}

export function aModelSettings(overrides: Partial<ModelSettings> = {}): ModelSettings {
  return {
    gateway: 'BUNDLED',
    vendor: 'LITELLM',
    memberKeys: false,
    bundledAvailable: true,
    configured: true,
    adminKeySet: true,
    relayEnabled: true,
    relayBaseUrl: 'https://tinfirm.acme.test/api/model-relay',
    defaultBudgetUsd: 50,
    budgetPeriod: '30d',
    defaultModels: [],
    keyRotationInterval: 'P30D',
    keyRotationGrace: 'PT10M',
    testedAt: AT.earlier,
    ...overrides,
  };
}

/** A company runner host, online with a free slot. */
export function aRunnerHost(overrides: Partial<RunnerHost> = {}): RunnerHost {
  return {
    id: IDS.companyHost,
    name: 'office',
    scope: 'COMPANY',
    ownerPrincipalId: null,
    ownerDisplay: null,
    online: true,
    draining: false,
    version: '0.2.0',
    os: 'linux',
    arch: 'amd64',
    maxConcurrentRuns: 2,
    maxConcurrentRunsCeiling: 4,
    desiredMaxConcurrentRuns: 2,
    configPending: false,
    activeRuns: 1,
    credentials: [],
    lastSeenAt: AT.start,
    createdAt: AT.earlier,
    ...overrides,
  };
}

/**
 * A company host with a VM per run that keeps ready sandboxes (ADR-0099): working days 08:00 to
 * 18:00 in Prague two, else none; Monday 19 October 2026 at 07:00 there, so the next change is at
 * 08:00 (06:00 UTC).
 */
export function aPoolRunnerHost(overrides: Partial<RunnerHost> = {}): RunnerHost {
  return aRunnerHost({
    id: IDS.companyHost,
    name: 'droplets',
    version: '0.3.0',
    maxConcurrentRuns: 8,
    maxConcurrentRunsCeiling: 8,
    desiredMaxConcurrentRuns: null,
    pool: {
      settings: {
        zone: 'Europe/Prague',
        readyDefault: 0,
        windows: [
          { days: ['MON', 'TUE', 'WED', 'THU', 'FRI'], from: '08:00', to: '18:00', ready: 2 },
        ],
      },
      maxReadyCeiling: 4,
      readyTarget: 0,
      next: { at: '2026-10-19T06:00:00Z', readyTarget: 2 },
      week: [
        { at: '2026-10-19T05:00:00Z', readyTarget: 0 },
        { at: '2026-10-19T06:00:00Z', readyTarget: 2 },
        { at: '2026-10-19T16:00:00Z', readyTarget: 0 },
      ],
      status: {
        ready: 0,
        starting: 1,
        busy: 2,
        coldStartP90Seconds: 95,
        servedReady: 7,
        servedCold: 2,
        reportedAt: AT.start,
      },
    },
    ...overrides,
  });
}

/** Alice's personal runner host with her Claude subscription. */
export function aPersonalRunnerHost(overrides: Partial<RunnerHost> = {}): RunnerHost {
  return aRunnerHost({
    id: IDS.personalHost,
    name: 'alice-macbook',
    scope: 'PERSONAL',
    ownerPrincipalId: IDS.alice,
    ownerDisplay: 'Alice Novak',
    os: 'darwin',
    arch: 'arm64',
    maxConcurrentRuns: 1,
    maxConcurrentRunsCeiling: 1,
    desiredMaxConcurrentRuns: null,
    activeRuns: 0,
    credentials: [
      {
        kind: 'CLAUDE_LOGIN',
        label: 'claude-max',
        runtimes: ['CLAUDE_CODE'],
        status: 'AVAILABLE',
        reported: true,
      },
    ],
    ...overrides,
  });
}
