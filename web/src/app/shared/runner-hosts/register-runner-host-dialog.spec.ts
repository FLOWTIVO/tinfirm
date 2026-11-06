import { serverOrigin, setupSteps } from './register-runner-host-dialog';

describe('serverOrigin', () => {
  it('derives the HTTP origin from the WebSocket connect URL', () => {
    expect(serverOrigin('wss://t.example.com/api/runner/v1/connect', 'x')).toBe(
      'https://t.example.com',
    );
    expect(serverOrigin('ws://localhost:8080/api/runner/v1/connect', 'x')).toBe(
      'http://localhost:8080',
    );
    expect(serverOrigin('not a url', 'http://fallback')).toBe('http://fallback');
    expect(serverOrigin(null, 'http://fallback')).toBe('http://fallback');
  });
});

describe('setupSteps', () => {
  it('adds the owner’s subscriptions on a personal host', () => {
    expect(setupSteps('PERSONAL', 'https://t.example.com', 'tfr_x').map((s) => s.command)).toEqual([
      'tinfirm-runner-host login --server https://t.example.com --token tfr_x',
      'tinfirm-runner-host credential add claude --label claude-max',
      'tinfirm-runner-host credential add codex --label chatgpt',
      'tinfirm-runner-host images build',
      'tinfirm-runner-host doctor',
      'tinfirm-runner-host run',
    ]);
  });

  it('adds no credential on a company host', () => {
    expect(setupSteps('COMPANY', 'https://t.example.com', 'tfr_x').map((s) => s.command)).toEqual([
      'tinfirm-runner-host login --server https://t.example.com --token tfr_x',
      'tinfirm-runner-host images build',
      'tinfirm-runner-host doctor',
      'tinfirm-runner-host run',
    ]);
  });
});
