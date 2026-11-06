# Review: does a follow-up run resume its session, and what does it cost?

2026-10-07 · status: **method and cost model ready; measured numbers not collected yet**

This review covers recording whether a run resumed its session on the platform design
([ADR-0037](../adr/0037-restart-on-the-platform-design.md)): threads (jobs) and turns instead of the v1 domain's tasks,
and protocol v2 instead of v1.

A follow-up run of an agent on a work item tries `session/load(resumeSessionId)` and sends the short `resumePrompt`.
When the session files are not on the runner host that runs it, the host falls back to `session/new` with the full
prompt (archived v1 CONVENTIONS §15.4 steps 4 and 5). On the platform design this is the normal case: every turn after
the first one of a thread resumes the session of the turn before it (archived [PLATFORM.md](../archive/v2/PLATFORM.md)
§4.6). This work adds the outcome to `run.finished` (`resumed`, `resumeOutcome`, protocol v2), to the `run` table and
the `Run` API, to the run page, and to the counter `tinfirm_runs_finished_total{resumed, resume_outcome, runtime}`
([CONVENTIONS §14.10](../CONVENTIONS.md#1410-runner-protocol-v2)). This review sets out how to compare resumed and
not-resumed follow-up runs, and what we can already say about the cost.

## Why the numbers are not in this review yet

The controlled pairs need two runner hosts and a member to start the runs, and have not been run yet. The queries and
the steps below are ready to run. The historical query does not need this work deployed: it tells resumed from
not-resumed runs by the host's LOG lines, which every run has, and a v1 host never reports `resumed`.

## 1. Every follow-up run so far

A follow-up run is one for which an earlier run of the same agent on the same work item reported a session id, so
`run.assign` carried a `resumeSessionId`. This is the condition that `RunStore.previousWithSession` uses: it holds for
every turn after the first of a thread, and for a new thread of the agent on a work item it already worked on. A
resumed run logs `resumed session <id> (session/load)`, and a run that fell back logs `started session <id>`. With
this work deployed and a v2 host, `run.resumed` says it directly.

```sql
-- Run as the database owner (RLS), on a read replica or at a quiet time.
WITH follow_up AS (
    SELECT r.*
      FROM run r
     WHERE r.status IN ('SUCCEEDED', 'FAILED', 'CANCELLED')
       AND EXISTS (SELECT 1 FROM run p
                    WHERE p.work_item_id = r.work_item_id AND p.agent_id = r.agent_id AND p.id <> r.id
                      AND p.created_at <= r.created_at AND p.session_id IS NOT NULL)
), classified AS (
    SELECT f.*,
           coalesce(f.resumed,
                    CASE WHEN EXISTS (SELECT 1 FROM run_event e WHERE e.run_id = f.id AND e.type = 'LOG'
                                         AND e.payload ->> 'message' LIKE 'resumed session %') THEN true
                         WHEN EXISTS (SELECT 1 FROM run_event e WHERE e.run_id = f.id AND e.type = 'LOG'
                                         AND e.payload ->> 'message' LIKE 'started session %') THEN false
                    END) AS did_resume,
           (SELECT count(*) FROM run_event e WHERE e.run_id = f.id AND e.type = 'TOOL_CALL') AS tool_calls,
           -- Time since the run before it on the work item ended: decides whether the provider's prompt cache is warm.
           f.started_at - (SELECT max(p.finished_at) FROM run p
                            WHERE p.work_item_id = f.work_item_id AND p.agent_id = f.agent_id
                              AND p.finished_at <= f.started_at) AS gap
      FROM follow_up f
)
SELECT did_resume,
       coalesce(resume_outcome, '-')                                             AS resume_outcome,
       count(*)                                                                  AS runs,
       percentile_cont(0.5) WITHIN GROUP (ORDER BY input_tokens + output_tokens + coalesce(cached_input_tokens, 0)
                                                     + coalesce(cache_write_tokens, 0))
                                                                                 AS median_total_tokens,
       percentile_cont(0.5) WITHIN GROUP (ORDER BY input_tokens)                 AS median_uncached_input,
       percentile_cont(0.5) WITHIN GROUP (ORDER BY cached_input_tokens)          AS median_cache_reads,
       percentile_cont(0.5) WITHIN GROUP (ORDER BY cache_write_tokens)           AS median_cache_writes,
       percentile_cont(0.5) WITHIN GROUP (ORDER BY output_tokens)                AS median_output,
       percentile_cont(0.5) WITHIN GROUP (ORDER BY extract(epoch FROM finished_at - started_at))
                                                                                 AS median_wall_clock_s,
       percentile_cont(0.5) WITHIN GROUP (ORDER BY tool_calls)                   AS median_tool_calls,
       percentile_cont(0.5) WITHIN GROUP (ORDER BY extract(epoch FROM gap))      AS median_gap_s,
       count(*) FILTER (WHERE result_outcome = 'COMPLETED')                      AS completed,
       count(*) FILTER (WHERE result_outcome = 'BLOCKED')                        AS blocked,
       count(*) FILTER (WHERE result_outcome IS NULL)                            AS no_finish,
       count(*) FILTER (WHERE status = 'FAILED')                                 AS failed
  FROM classified
 GROUP BY 1, 2
 ORDER BY 1, 2;
```

How to read the columns:

- `run.started_at` is set by `run.accepted`, so `finished_at - started_at` is the wall clock from `run.accepted` to
  `run.finished`.
- The `USAGE` events are cumulative and exact, and `run.finished.usage` repeats the final totals, which are stored on
  the run. `input_tokens` is the input neither read from nor written to the cache, `cached_input_tokens` is cache
  reads and `cache_write_tokens` cache writes, and the four parts add up to the total (runner README, "Usage").
  A run of a v1 host, or from before this split, has `cache_write_tokens` null and its cache writes in
  `input_tokens`: compare `input_tokens + coalesce(cache_write_tokens, 0)` when such runs are in the set.
- Runs with `did_resume` null ended before they opened a session, for example because the clone failed. Leave them
  out of the comparison.
- Compare medians within bands of `gap` (below 5 minutes, 5 to 60 minutes, above 1 hour), not over all runs. The gap
  decides whether a resume's replay is billed as a cache read or as a new cache write (section 4).

## 2. Controlled pairs

Use the same ask-and-answer flow on one work item each time, with the same agent, runtime and model credential, on
v2 runner hosts:

1. Start a thread whose agent has to ask one question with `ask` before it can finish.
2. Answer the question. This starts the thread's DECIDED turn.
3. **Resume:** let the turn run on the host that ran the first turn.
   **Fallback:** before you answer, drain the first host (`tinfirm-runner drain`, or SIGHUP) so that the dispatcher
   sends the turn to the second host. A model credential run goes to any host with a free slot (ADR-0027).
4. Note the time between the end of the first turn and the start of the next. Run each variant twice: once
   answering within 5 minutes, once after more than 1 hour.

Do this for two first sessions:

- **Short:** the first turn makes a few tool calls (about 5) before it asks.
- **Long:** the first turn works near `maxTurns` (about 150 of 200 tool calls) before it asks. For example, ask it to
  read and summarise every file of a module first.

Record for each follow-up run the `Run` fields `resumed`, `resumeOutcome`, `usage` and `counts`, the duration, the
`finish` outcome, and the gap. The result table to fill in:

| Pair | First session | Gap | Resumed | Total tokens | Uncached input | Cache reads | Cache writes | Output | Wall clock | Tool calls | Outcome |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 1a | short | < 5 min | yes | | | | | | | | |
| 1b | short | < 5 min | no (`NO_SESSION`) | | | | | | | | |
| 2a | long | < 5 min | yes | | | | | | | | |
| 2b | long | < 5 min | no (`NO_SESSION`) | | | | | | | | |
| 3a | long | > 1 h | yes | | | | | | | | |
| 3b | long | > 1 h | no (`NO_SESSION`) | | | | | | | | |

## 3. What already happens on `main`

- The host knows each outcome. Before this work it only logged it, so nobody could count the fallbacks.
- The session home is per agent and work item on the host that ran the turn. With one runner host, a follow-up run
  loses its session only when maintenance deleted the home after `TINFIRM_RUNNER_HOME_MAX_IDLE_DAYS` (default 30).
  With two or more hosts and a model credential, the dispatcher picks the host with the most free slots and does not
  prefer the host of the previous turn (`HostSelection`). About 1/n of follow-ups land on the right host by chance
  when n hosts are equally free. On-demand runners start a new host with an empty disk, so there almost every
  follow-up run falls back.
- A runner credential run always goes to the host of its credential, so it keeps its session.
- A v1 host does not report `resumed`; its runs stay "not known" on the run page and `unknown` in the counter.

## 4. Cost model: a resume is not automatically cheaper

Prompt caching on the Anthropic API bills a cache read at 0.1× the base input price, and a cache write at 1.25× (5
minute entry) or 2× (1 hour entry). Uncached input is billed at 1×. Claude Code writes its prompt cache with the
1 hour TTL on a Claude subscription (the session below shows only `ephemeral_1h_input_tokens` writes) and with the
5 minute TTL on an Anthropic API key ([operations/prompt-cache.md](../operations/prompt-cache.md) §3). The figures
below are for a subscription; on an API key "within 1 hour" reads "within 5 minutes", and a cold replay costs 1.25×.

**An example: a long Claude Code session.** It made 101 model requests. Its context grew from about 18k tokens on the
first request to **206k** on the last. Across the requests it read 12.1M tokens from the cache, wrote 196k to the
cache, sent 202 uncached input tokens and produced 57k output tokens. So "total tokens" for a long session is almost
all cache reads: here 98 % of 12.4M.

What a follow-up of a session like this costs, in units of base input price (a model with $5 per 1M input tokens:
1M units = $5):

- **Resume after more than 1 hour (cache expired).** The first request replays the whole transcript, about 206k
  tokens, as a new cache write at 2×, which is 412k units. Each later request reads the transcript from the cache:
  with k requests, k × 206k × 0.1. For a short follow-up (k = 10) that is about 206k units. Total about 620k units
  before any new work.
- **Resume within 1 hour (cache warm).** The replay is a cache read: 206k × 0.1 = 21k units on the first request,
  then the same k × 21k. For k = 10, about 230k units. This is the cheapest case.
- **New session with the full prompt (fallback).** The prompt and the tool definitions, about 18k tokens, are
  written once (36k units). After that the agent must find again what it already knew: it reads the files again and
  runs the commands again, and its context grows as the first session's did. If it needs half of what the first
  session read, its context reaches about 100k, and k requests read on average about 60k each: k × 6k. The cost is
  dominated by how much rediscovery the follow-up needs. It is also slower (more tool calls, longer wall clock), and
  it may answer differently because it lost the earlier reasoning. On the platform design the full prompt carries
  the thread's newest 20 comments and the answers (archived PLATFORM.md §7.1), which limits what it loses, but not the
  rediscovery in the repositories.

Conclusions to check against the pairs:

1. With a warm cache, resuming is cheaper than any fallback that needs more than a few tool calls of
   rediscovery. Host affinity matters most here, because answers to `ask` often come within the hour (on a
   subscription; on an API key, the cache is warm only for five minutes).
2. After the cache expired, resuming a **long** session costs about 2× its context in the first request (above
   400k units here), whatever the follow-up does. A fallback that needs little rediscovery (for example "fix the
   typo in the change request") can then be cheaper than the resume. So "resume means fewer tokens" does not hold
   after the cache window.
3. Comparing "total tokens" is misleading, because cache reads dominate it and cost a tenth. Compare **uncached
   input** and **cache writes**, output, and `costUsd` where the runtime reports it. Or weight the parts: uncached
   input × 1, cache writes × 2 (1 hour TTL) or × 1.25 (5 minute TTL), cache reads × 0.1, output × 5.

## 5. What the numbers decide

- **Host affinity in the dispatcher** (prefer the host of the previous turn when it has a free slot): worth it when
  the fallback share of follow-up runs on hosts with two or more runners is high and the pairs confirm conclusion 1.
- **Server-side session bundles** (upload the session home at the end of a run, download it on the next host): the
  only fix for on-demand runners. It is worth it in proportion to the share of `NO_SESSION` runs, discounted by
  conclusion 2 for long sessions that resume after the cache expired.
- **A rule for long sessions:** if the pairs confirm conclusion 2, a follow-up of a long session after more than an
  hour could start new on purpose, with a summary of the earlier session in the prompt instead of the full replay.
