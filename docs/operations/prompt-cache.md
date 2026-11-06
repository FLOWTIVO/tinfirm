# Prompt cache: what a run's tokens mean and what a follow-up turn costs

The public guide is [Run the model gateway, Prompt caching](../../docs-site/content/operator-guide/model-gateway.md#prompt-caching) (https://docs.tinfirm.com/operator-guide/model-gateway/#prompt-caching), for operators: the four token counts, the TTL Claude Code uses, and how to set the 1-hour one. This page keeps what the people who build Tinfirm need beyond it: the API fields and how each runtime reports the counts, the cost model of a follow-up turn, and the measurements of the TTL with how to repeat them. The cost model of resuming a session against starting a new one is in [reviews/2026-10-07-session-resume.md](../reviews/2026-10-07-session-resume.md) §4.

## 1. The four token counts

Every run reports four counts, cumulative over the whole run. They add up to the run's total:

`total = input (uncached) + cache read + cache write + output`

| Count | API field (`Run.usage`, USAGE event) | What it is | Anthropic price, relative to base input |
|---|---|---|---|
| Input | `inputTokens` | Input tokens that were neither read from nor written to the cache | 1× |
| Cache read | `cachedInputTokens` | Input tokens served from the prompt cache | 0.1× (0.05× on Claude Opus 5.5, 0.025× on Claude Fable 5.1) |
| Cache write | `cacheWriteTokens` | Input tokens written to the prompt cache | 1.25× with the 5-minute TTL, 2× with the 1-hour TTL |
| Output | `outputTokens` | Generated tokens | the model's output price |

- **Claude Code** and **pi** report the split per prompt turn, so it is exact.
- **Codex** reports the split of its last model request only. The runner host divides the earlier requests in the same proportions and marks the usage `estimated`; the total stays exact. OpenAI bills no cache writes, so Codex runs show 0.
- **OpenCode** 2 reports the split per prompt turn, so it is exact. OpenCode 1, in a runner image not rebuilt yet, reports only the last model request of each turn, so its counts are a lower bound, also marked `estimated` ([ADR-0061](../adr/0061-opencode-2.md)).
- **Protocol versions.** Runner protocol v2 reports the cache writes apart (`cacheWriteTokens`, [runner-protocol-v2.md](runner-protocol-v2.md)); v1 counted them as input. A run whose `cacheWriteTokens` is null shows no cache-write count, and its tooltip says the input includes the cache writes.

## 2. Why a follow-up turn writes the cache again

Each run starts in a fresh sandbox, but the prompt cache lives at the model provider and is keyed on the prompt prefix, not on the sandbox. Within a run the cache works: every request reads the history of the previous one.

A later turn of a conversation (a message, an answer to `ask`, a decision, an event) resumes the agent's session of the turn before when its runner host has it (`Run.resumed`), so its first request sends the whole earlier transcript again. If the cache entry for that history is still alive, the request reads it (0.1×, 0.05× on Claude Opus 5.5). If it has expired, the request writes it again (1.25× or 2×). Whether a turn started warm or cold shows on the run: a cold start writes about the whole history to the cache, a warm one writes only what is new. A change of the agent's effort ([PLATFORM.md §6](../PLATFORM.md#6-agents-and-settings)) between two turns makes the next one write its conversation again even with the entry alive: the API keeps the cached tools and system prompt but not the messages when the thinking or effort settings change.

What a cold start costs, for a resumed history of `H` tokens at a base input price `p`:

| First request of the follow-up | Cost of the history |
|---|---|
| Warm (entry alive) | `0.1 × H × p` (`0.05 × H × p` on Claude Opus 5.5) |
| Cold, 5-minute TTL | `1.25 × H × p` |
| Cold, 1-hour TTL | `2 × H × p` |

Example: a 150K-token history on Claude Opus 5.5 ($4 per million input tokens) costs $0.03 warm, $0.75 cold with the 5-minute TTL and $1.20 cold with the 1-hour TTL, before the follow-up does any work.

## 3. The TTL Claude Code uses

**One hour on a Claude subscription, five minutes on an Anthropic API key**, measured on 2026-10-07 and 2026-10-08 with Claude Code 2.1.280 (claude-agent-acp 0.81.2). These are Claude Code's documented defaults for the main conversation ([Claude Code: prompt caching](https://code.claude.com/docs/en/prompt-caching#which-ttl-each-request-gets)). Tinfirm does not set the TTL ([below](#what-the-result-decides) for how to). The session measured for the resume review also wrote its cache only with the 1-hour TTL (`ephemeral_1h_input_tokens`, [reviews/2026-10-07-session-resume.md](../reviews/2026-10-07-session-resume.md) §4).

### Results, 2026-10-08

These measurements and those of 2026-10-07 were taken on the design of 2026-10-05: its threads are today's conversations, and its company model credentials gave a run a provider's key directly, where runs now go through the company's gateway.

Three threads were started together on one instance. Each turn asked for a one-sentence reply without tools. All three ran on one runner host of protocol v2 (so `cacheWriteTokens` is reported apart), in one read-only repository at the same commit:

- **TF-14**: Claude Code, Claude Opus 5.5, a Claude subscription token;
- **TF-15**: Claude Code, Claude Sonnet 5.5, an Anthropic API key (a company model credential);
- **TF-16**: pi, Kimi K3 through OpenRouter (a company model credential).

Every follow-up resumed its session (`Run.resumed`). The gap runs from the end of the previous run to the start of this one. The first model request usually comes about 15 seconds after the start (90 seconds for TF-14's turn 4), and the cache entry was last used about a second before the previous run ended.

| Follow-up | Gap after the previous turn | `H` | Cache write | Cache read | Uncached input | Reading |
|---|---|---|---|---|---|---|
| TF-14, turn 2 | 1 min 10 s | 16,956 | 3,687 | 13,364 | 2 | Warm (the first follow-up, below) |
| TF-14, turn 3 | 10 min 27 s | 17,089 | 158 | 17,026 | 2 | Warm |
| TF-14, turn 4 | 65 min 21 s | 17,222 | 7,224 | 10,093 | 2 | Cold past the prompt's start (a new commit, below) |
| TF-15, turn 2 | 1 min 08 s | 28,935 | 3,597 | 25,433 | 2 | Warm (the first follow-up, below) |
| TF-15, turn 3 | 10 min 25 s | 29,068 | 29,163 | 0 | 2 | Cold: the whole history written again |
| TF-15, turn 4 | 65 min 20 s | 29,201 | 29,296 | 0 | 2 | Cold |
| TF-16, turn 2 | 1 min 14 s | 3,939 | 0 | 7,552 | 654 | Warm |
| TF-16, turn 3 | 10 min 29 s | 4,224 | 0 | 8,064 | 704 | Warm |
| TF-16, turn 4 | 66 min 49 s | 4,501 | 0 | 4,608 | 4,734 | Cold: the first request read nothing |

- **The cost names the TTL.** Claude Code prices each run from the API's usage, which counts 5-minute and 1-hour writes apart. Every run of TF-14 fits $8.00 per million written tokens (2× its $4 input), so its writes were 1-hour writes. Every run of TF-15 fits $5.00 per million written tokens (1.25×), so its writes were 5-minute writes. Both fits are exact to the last digit Claude Code reports. The session files on the runner host confirm both from the API's own usage: every write of TF-14 is `ephemeral_1h_input_tokens`, every write of TF-15 `ephemeral_5m_input_tokens`. Claude Code 2.1.280 prices Claude Sonnet 5.5 at Claude Opus 5.5 rates ($4 input, $0.20 cache read, $20 output), so the cost it reports for TF-15 is about twice the real one; the 1.25× ratio still names the tier.
- **Turn 4 found a new commit.** A new commit reached `main` between turn 3 and turn 4, so turn 4 cloned another `HEAD`. Claude Code's system prompt shows the repository's recent commits, so a new commit alone makes a follow-up write the rest of its prompt again, whatever the TTL. Turn 4 therefore does not separate the TTL from the commit. The TTLs rest on the write tiers above, turn 3 of TF-15 and TF-10 below.
- **A follow-up after replies without any thinking writes the conversation again.** The second turn of TF-14 and of TF-15 wrote about 3.5K tokens again, everything after the system prompt, although the cache was warm; later turns wrote only what was new (158 tokens for TF-14, turn 3). The session files on the runner host show what set them apart: none of their replies had a thinking block. TF-17's first turn used no tools, like TF-14's, but its reply had a thinking block, and its follow-up wrote only its 164 new tokens. TF-18's first reply had a thinking block and its two later replies did not; nothing was written again, within the turn or on the follow-up (177). The follow-ups of TF-14 and TF-15 read the cached tools and system prompt and wrote every message again, which is what the API does when the thinking or effort settings change between requests ([Anthropic: prompt caching](https://platform.claude.com/docs/en/build-with-claude/prompt-caching)): Claude Code most likely sends other settings once the conversation holds replies but no thinking block. A change in the first message would look the same; the requests themselves are not logged. One thinking block anywhere in the conversation prevents it, and real tasks usually think in their first request, so it should be rare. Here it cost about 3.5K × 2 × `p` once per thread: the conversation up to that point, written again.
- **pi** reported no cache writes for Kimi K3 through OpenRouter. Each pi turn makes two model requests: the reply, then the `finish` call. Its first request read the history at 1 minute and at 10 minutes alike, so the uncached input stayed at about 700 tokens per turn. After 67 minutes its first request read nothing (4.7K uncached, about `H`), and only the second request read the first: the provider's cache had expired, or the new commit changed pi's prompt.

### Results, 2026-10-07

The threads of one agent on the same instance and runner host: Claude Code 2.1.280 (claude-agent-acp 0.81.2), Claude Opus 5.5, a Claude subscription token. These runs count their cache writes in `inputTokens`. Both follow-ups resumed their session (their logs say `resumed session … (session/load)`) on a history of about 19K tokens:

| Follow-up | Gap after the previous turn | `H` | Input, cache writes included | Cache read | Reading |
|---|---|---|---|---|---|
| TF-8, turn 3 | 21 min 26 s | 19,115 | 861 | 57,267 | Warm: the history was read, not written again |
| TF-10, turn 3 | 60 min 00 s | 19,123 | 9,450 | 48,445 | Cold: about 9.5K of the history was written again |

The turns that followed within a minute (TF-8 turn 2, TF-10 turn 2) were warm too, with 628 and 705 input tokens.

The cost the runtime reported confirms it. Claude Code prices each run from the API's usage, which counts 5-minute and 1-hour writes apart. The costs of all 12 runs of TF-4, TF-8, TF-9 and TF-10 fit $8.00 per million written tokens, $0.20 per million read and $20.00 per million output, to within $0.000003 per run. On Claude Opus 5.5 ($4 base input) a 1-hour write costs 2× ($8.00) and a 5-minute write 1.25× ($5.00), so every write was a 1-hour write.

### Measuring it again

Measure again after a Claude Code update, and after changing the TTL ([below](#what-the-result-decides)). Runs report their cache writes apart, so a follow-up reads directly. Use the same model, and a conversation in a small repository whose default branch does not move during the test (a new commit changes Claude Code's system prompt).

1. Start the conversation and let its first turn finish. Note the `contextUsed` of the run's last USAGE event (`GET /api/companies/{companyId}/runs/{runId}/events`): it is about the history `H` that the next turn resumes.
2. Within 5 minutes of the end of the turn, write in the conversation. Note when turn 1 ended and turn 2 started.
3. Between 5 and 60 minutes after turn 2 ends (aim for about 15 minutes, about the delay of an event after a push), write again.
4. For each follow-up run, check that `Run.resumed` is true: a run that started a new session tells nothing about the cache of the earlier one. Record its `cacheWriteTokens` and `cachedInputTokens` (`GET /api/companies/{companyId}/runs/{runId}`), and the `H` of the turn before.
5. Read the result: cache writes far below `H` mean the entry was alive; cache writes of about `H` or more mean it had expired. Every later request of the run reads the history from the cache either way, so the cache reads alone do not tell.

| Follow-up | Gap after the previous run | Expected with the 5-minute TTL | Expected with the 1-hour TTL |
|---|---|---|---|
| Turn 2 | under 5 minutes | warm | warm |
| Turn 3 | 5 to 60 minutes | cold | warm |

Add the follow-ups to the results above, with their `cacheWriteTokens` in place of the input.

### What the result decides

On a **Claude subscription**:

- A follow-up within the hour, such as an answer to `ask` or an event about 10 minutes after a push, finds the cache warm: its first request costs about `0.05 × H × p` on Claude Opus 5.5.
- A follow-up after more than an hour writes the history again, `2 × H × p` in its first request, before it does any work. For a long session that is the cost to weigh against starting a new session ([the resume review](../reviews/2026-10-07-session-resume.md) §4 and §5).

The cost is not billed in USD but counts against the plan's usage limits, so the cold start matters there too.

On an **Anthropic API key**, and on a budget, where Claude Code reaches the company's gateway with a key rather than a subscription (not measured through the gateway yet), a follow-up more than five minutes after the turn before writes the history again: `1.25 × H × p` in its first request instead of `0.1 × H × p`. That covers most answers to `ask` and an event about 10 minutes after a push.

Claude Code can keep the main conversation in the cache for an hour on an API key too. Set `CLAUDE_CODE_PROMPT_CACHE_TTL=1h` (Claude Code 2.1.242 or later; the setting `promptCacheTtl`) ([Claude Code: prompt caching](https://code.claude.com/docs/en/prompt-caching#choose-the-ttl-yourself)). `ENABLE_PROMPT_CACHING_1H=1` does the same and also extends the subagent and helper requests, which no later turn resumes. How a runner host sets it is in the public guide. It is not measured here yet: check that the cost of a run then fits 2× writes, as in the results above.

The 1-hour TTL is not free. Every cache write then costs 2× instead of 1.25×. A run's requests follow each other within seconds, so the extra `0.75 × p` is paid on every token the run writes, whether or not a follow-up comes. A follow-up between 5 and 60 minutes later saves `1.15 × H × p`.

- **A first turn** writes about its whole `H`, so the 1-hour TTL pays off when about two of three first turns get a follow-up within that window.
- **A later turn** writes only what is new, so it pays off sooner.
