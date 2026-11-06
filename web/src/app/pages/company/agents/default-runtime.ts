import type { AgentRuntime, ModelSubscription } from '../../../api/generated/models';

/** A new agent's first runtime, and why: what the editor checks before anyone picks one. */
export interface SuggestedRuntime {
  readonly runtime: AgentRuntime;
  /** The sentence under the runtimes, after "{runtime} is the default: ". */
  readonly reason: string;
}

/** A model of Anthropic's, by its gateway name: `claude-sonnet-4-5`, `bedrock/anthropic.claude-…`. */
const CLAUDE = /claude/i;
/** A model of OpenAI's: `gpt-5`, `o3`, `codex-mini`, `azure/gpt-4.1`, `openai/o4-mini`. */
const OPENAI = /^(?:[a-z0-9_-]+\/)*(?:gpt|chatgpt|codex|o\d)(?:[-._]|$)/i;

/**
 * The runtime a new agent starts on, from how the company pays for models (the gateway's models,
 * ADR-0059) or else the reader's own subscriptions (ADR-0085). Claude Code and Codex are their
 * vendors' own agents, tuned for their models; pi reaches any provider, so it suits a gateway that
 * serves neither vendor's models (ADR-0041). Without either, Claude Code.
 *
 * `gatewayModels` is null when the gateway is not configured or did not answer.
 */
export function suggestedRuntime(
  gatewayModels: readonly string[] | null,
  subscriptions: readonly ModelSubscription[],
): SuggestedRuntime {
  if (gatewayModels && gatewayModels.length > 0) {
    if (gatewayModels.some((m) => CLAUDE.test(m))) {
      return { runtime: 'CLAUDE_CODE', reason: "your company's gateway serves Claude models." };
    }
    if (gatewayModels.every((m) => OPENAI.test(m))) {
      return { runtime: 'CODEX', reason: "your company's gateway serves OpenAI models only." };
    }
    return {
      runtime: 'PI',
      reason: "your company's gateway serves other providers' models, which pi works with.",
    };
  }
  const subscription = subscriptions.find((s) => s.status !== 'INVALID' && s.runtimes.length > 0);
  if (subscription) return SUBSCRIPTIONS[subscription.kind];
  return { runtime: 'CLAUDE_CODE', reason: 'no gateway or subscription says otherwise.' };
}

const SUBSCRIPTIONS: Record<ModelSubscription['kind'], SuggestedRuntime> = {
  CLAUDE_LOGIN: { runtime: 'CLAUDE_CODE', reason: 'your Claude subscription runs Claude Code.' },
  ANTHROPIC_API_KEY: { runtime: 'CLAUDE_CODE', reason: 'your runner host has an Anthropic key.' },
  CODEX_CHATGPT_AUTH: { runtime: 'CODEX', reason: 'your ChatGPT subscription runs Codex.' },
  OPENAI_API_KEY: { runtime: 'CODEX', reason: 'your runner host has an OpenAI key.' },
};
