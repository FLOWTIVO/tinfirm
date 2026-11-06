import { aSubscription } from '../../../testing/fixtures';
import { suggestedRuntime } from './default-runtime';

describe('suggestedRuntime', () => {
  it('starts on Claude Code when the gateway serves a Claude model, whatever else it serves', () => {
    expect(suggestedRuntime(['gpt-5', 'claude-sonnet-4-5'], []).runtime).toBe('CLAUDE_CODE');
    expect(suggestedRuntime(['bedrock/anthropic.claude-sonnet-4-5'], []).runtime).toBe(
      'CLAUDE_CODE',
    );
  });

  it("starts on Codex when the gateway serves OpenAI's models only", () => {
    const suggested = suggestedRuntime(['gpt-5', 'o3', 'azure/gpt-4.1', 'codex-mini'], []);
    expect(suggested).toEqual({
      runtime: 'CODEX',
      reason: "your company's gateway serves OpenAI models only.",
    });
  });

  it('starts on pi when the gateway serves neither vendor, or a mix without Claude', () => {
    expect(suggestedRuntime(['gemini-2.5-pro', 'mistral-large'], []).runtime).toBe('PI');
    expect(suggestedRuntime(['gpt-5', 'gemini-2.5-pro'], []).runtime).toBe('PI');
    // A name that only starts like an OpenAI one is another provider's.
    expect(suggestedRuntime(['ollama/gptoss-local'], []).runtime).toBe('PI');
  });

  it("takes the reader's subscription without a gateway, skipping an invalid one", () => {
    const codex = aSubscription({ kind: 'CODEX_CHATGPT_AUTH', runtimes: ['CODEX'] });
    const invalid = aSubscription({ status: 'INVALID' });
    expect(suggestedRuntime(null, [invalid, codex])).toEqual({
      runtime: 'CODEX',
      reason: 'your ChatGPT subscription runs Codex.',
    });
    expect(suggestedRuntime([], [aSubscription()]).runtime).toBe('CLAUDE_CODE');
  });

  it('stays on Claude Code with neither', () => {
    expect(suggestedRuntime(null, []).runtime).toBe('CLAUDE_CODE');
  });
});
