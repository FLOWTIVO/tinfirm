-- Package 6, spend and budget alerts (docs/reviews/2026-10-12-package-6-contract.md §3.1, §3.2, §3.8; G2, G3).
--
-- A run's cost comes from LiteLLM's spend logs of the requests the model relay tagged with the run, else from the
-- relay's own count of each answer's usage; the sandbox's USAGE totals on run (V004) stay an estimate and never set a
-- cost. Ownership:
--   run.model            the run module's: the settings' model at assignment (NULL: the runtime's default)
--   relay_usage          the model module's: what the relay saw of a run's answers, per model and key
--   budget_alert         the model module's: one threshold crossed in one budget period
--   model_settings       (changed) the thresholds and the company's monthly alert amount
--   spend_settlement     the governance module's: one row per ended run that relayed a request or ran on a
--                        subscription, until it is settled
--   run_spend            the governance module's: what one run spent on one model, written once (G3)
--   company_spend_alert  the governance module's: the company's monthly amount crossed
--
-- Package 6's migrations follow package 5's (V017 to V019).

-- ---- run (changed) ----------------------------------------------------------------------------------------------

ALTER TABLE run ADD COLUMN model varchar(200);
ALTER TABLE run ADD CONSTRAINT run_model_ck CHECK (model IS NULL OR length(model) BETWEEN 1 AND 200);

-- ---- relay_usage ------------------------------------------------------------------------------------------------

-- Each answer's usage as its format reports it, added when the answer ends; an answer without usage counts a
-- request and no tokens. key_id is LiteLLM's id of the key the relay forwarded with (a rotation may fall within a
-- run). No foreign key on the run, as run_model_token has none; rows are deleted 30 days after their last update.
CREATE TABLE relay_usage (
    company_id          uuid          NOT NULL,
    run_id              uuid          NOT NULL,
    model               varchar(200)  NOT NULL,
    key_id              varchar(64)   NOT NULL,
    requests            bigint        NOT NULL DEFAULT 0,
    input_tokens        bigint        NOT NULL DEFAULT 0,
    output_tokens       bigint        NOT NULL DEFAULT 0,
    cached_input_tokens bigint        NOT NULL DEFAULT 0,
    cache_write_tokens  bigint        NOT NULL DEFAULT 0,
    updated_at          timestamptz   NOT NULL,
    PRIMARY KEY (run_id, model, key_id),
    CONSTRAINT relay_usage_company_fk FOREIGN KEY (company_id) REFERENCES company (id) ON DELETE CASCADE,
    CONSTRAINT relay_usage_counts_ck CHECK (requests >= 0 AND input_tokens >= 0 AND output_tokens >= 0
        AND cached_input_tokens >= 0 AND cache_write_tokens >= 0),
    CONSTRAINT relay_usage_model_ck CHECK (length(model) >= 1)
);

CREATE INDEX relay_usage_company_updated_idx ON relay_usage (company_id, updated_at);

ALTER TABLE relay_usage ENABLE ROW LEVEL SECURITY;
ALTER TABLE relay_usage FORCE ROW LEVEL SECURITY;
CREATE POLICY relay_usage_tenant ON relay_usage FOR ALL
    USING (company_id = app_company_id())
    WITH CHECK (company_id = app_company_id());
GRANT SELECT, INSERT, UPDATE, DELETE ON relay_usage TO ${app_role};

-- ---- model_settings (changed), budget_alert ---------------------------------------------------------------------

ALTER TABLE model_settings ADD COLUMN budget_alert_thresholds smallint[] NOT NULL DEFAULT '{80,100}';
ALTER TABLE model_settings ADD COLUMN monthly_alert_usd numeric(16,4);
ALTER TABLE model_settings ADD CONSTRAINT model_settings_alert_thresholds_ck CHECK (
    cardinality(budget_alert_thresholds) BETWEEN 1 AND 3
    AND array_position(budget_alert_thresholds, NULL) IS NULL
    AND 1 <= ALL (budget_alert_thresholds) AND 100 >= ALL (budget_alert_thresholds));
ALTER TABLE model_settings ADD CONSTRAINT model_settings_monthly_alert_ck CHECK (
    monthly_alert_usd IS NULL OR monthly_alert_usd >= 0);

-- One threshold crossed in one budget period. resets_at is the period's end as LiteLLM last said it ('infinity' when
-- it named none), so the next period's crossing is a new row. Deleted after 400 days.
CREATE TABLE budget_alert (
    company_id    uuid           NOT NULL,
    principal_id  uuid           NOT NULL,
    resets_at     timestamptz    NOT NULL,
    threshold     smallint       NOT NULL,
    spent_usd     numeric(16,4)  NOT NULL,
    budget_usd    numeric(16,4)  NOT NULL,
    crossed_at    timestamptz    NOT NULL,
    PRIMARY KEY (principal_id, resets_at, threshold),
    CONSTRAINT budget_alert_principal_fk FOREIGN KEY (principal_id, company_id)
        REFERENCES principal (id, company_id) ON DELETE CASCADE,
    CONSTRAINT budget_alert_threshold_ck CHECK (threshold BETWEEN 1 AND 100),
    CONSTRAINT budget_alert_amounts_ck CHECK (spent_usd >= 0 AND budget_usd >= 0)
);

CREATE INDEX budget_alert_company_idx ON budget_alert (company_id, crossed_at);

ALTER TABLE budget_alert ENABLE ROW LEVEL SECURITY;
ALTER TABLE budget_alert FORCE ROW LEVEL SECURITY;
CREATE POLICY budget_alert_tenant ON budget_alert FOR ALL
    USING (company_id = app_company_id())
    WITH CHECK (company_id = app_company_id());
GRANT SELECT, INSERT, DELETE ON budget_alert TO ${app_role};

-- ---- spend_settlement -------------------------------------------------------------------------------------------

-- An ended run whose spend is still to be read from LiteLLM's logs. PENDING rows are claimed with a lease
-- (lease_until), so that two workers never read the same logs; attempts back off 2, 4, 8... minutes, and after an hour
-- the relay's count settles the run. last_error is LiteLLM's reason when its logs could not be read. A row is
-- deleted 30 days after it was settled.
CREATE TABLE spend_settlement (
    run_id              uuid          PRIMARY KEY,
    company_id          uuid          NOT NULL,
    payer_principal_id  uuid          NOT NULL,
    started_at          timestamptz   NOT NULL,
    ended_at            timestamptz   NOT NULL,
    state               varchar(16)   NOT NULL,
    attempts            integer       NOT NULL DEFAULT 0,
    due_at              timestamptz   NOT NULL,
    lease_until         timestamptz,
    last_error          varchar(500),
    failing_since       timestamptz,
    settled_at          timestamptz,
    CONSTRAINT spend_settlement_run_fk FOREIGN KEY (run_id, company_id) REFERENCES run (id, company_id),
    CONSTRAINT spend_settlement_state_ck CHECK (state IN ('PENDING', 'SETTLED')),
    CONSTRAINT spend_settlement_settled_ck CHECK ((state = 'SETTLED') = (settled_at IS NOT NULL)),
    CONSTRAINT spend_settlement_span_ck CHECK (ended_at >= started_at),
    CONSTRAINT spend_settlement_attempts_ck CHECK (attempts >= 0),
    CONSTRAINT spend_settlement_failing_ck CHECK ((last_error IS NULL) = (failing_since IS NULL))
);

CREATE INDEX spend_settlement_due_idx ON spend_settlement (due_at) WHERE state = 'PENDING';
CREATE INDEX spend_settlement_company_idx ON spend_settlement (company_id, state, settled_at);

ALTER TABLE spend_settlement ENABLE ROW LEVEL SECURITY;
ALTER TABLE spend_settlement FORCE ROW LEVEL SECURITY;
CREATE POLICY spend_settlement_tenant ON spend_settlement FOR ALL
    USING (company_id = app_company_id())
    WITH CHECK (company_id = app_company_id());
GRANT SELECT, INSERT, UPDATE, DELETE ON spend_settlement TO ${app_role};

-- ---- run_spend --------------------------------------------------------------------------------------------------

-- What one run spent on one model (G2, G3). The run's facts are copied so that views need no join and outlive a
-- conversation's purge; trigger_id has no foreign key. cost_usd is LiteLLM's spend for GATEWAY, the relay's tokens at
-- LiteLLM's prices for RELAY (NULL when LiteLLM names no price for the model), and NULL for SUBSCRIPTION. Rows hold no
-- content and are kept for good; only SpendSync writes them, once.
CREATE TABLE run_spend (
    company_id           uuid           NOT NULL,
    run_id               uuid           NOT NULL,
    model                varchar(200)   NOT NULL,
    conversation_id      uuid           NOT NULL,
    agent_id             uuid           NOT NULL,
    trigger_id           uuid,
    payer_principal_id   uuid           NOT NULL,
    acting_principal_id  uuid           NOT NULL,
    personal             boolean        NOT NULL,
    day                  date           NOT NULL,
    ended_at             timestamptz    NOT NULL,
    requests             bigint         NOT NULL DEFAULT 0,
    input_tokens         bigint         NOT NULL DEFAULT 0,
    output_tokens        bigint         NOT NULL DEFAULT 0,
    cached_input_tokens  bigint         NOT NULL DEFAULT 0,
    cache_write_tokens   bigint         NOT NULL DEFAULT 0,
    cost_usd             numeric(14,6),
    source               varchar(16)    NOT NULL,
    recorded_at          timestamptz    NOT NULL,
    PRIMARY KEY (run_id, model),
    CONSTRAINT run_spend_run_fk FOREIGN KEY (run_id, company_id) REFERENCES run (id, company_id),
    CONSTRAINT run_spend_source_ck CHECK (source IN ('GATEWAY', 'RELAY', 'SUBSCRIPTION')),
    CONSTRAINT run_spend_cost_ck CHECK (
        (source = 'GATEWAY' AND cost_usd IS NOT NULL)
        OR source = 'RELAY'
        OR (source = 'SUBSCRIPTION' AND cost_usd IS NULL)),
    CONSTRAINT run_spend_counts_ck CHECK (requests >= 0 AND input_tokens >= 0 AND output_tokens >= 0
        AND cached_input_tokens >= 0 AND cache_write_tokens >= 0 AND (cost_usd IS NULL OR cost_usd >= 0)),
    CONSTRAINT run_spend_model_ck CHECK (length(model) >= 1),
    CONSTRAINT run_spend_day_ck CHECK (day = (ended_at AT TIME ZONE 'UTC')::date)
);

CREATE INDEX run_spend_company_day_idx ON run_spend (company_id, day);
CREATE INDEX run_spend_company_ended_idx ON run_spend (company_id, ended_at);
CREATE INDEX run_spend_payer_idx ON run_spend (company_id, payer_principal_id, day);
CREATE INDEX run_spend_actor_idx ON run_spend (company_id, acting_principal_id, day);
CREATE INDEX run_spend_agent_idx ON run_spend (company_id, agent_id, day);
CREATE INDEX run_spend_trigger_idx ON run_spend (company_id, trigger_id, day) WHERE trigger_id IS NOT NULL;
CREATE INDEX run_spend_conversation_idx ON run_spend (company_id, conversation_id, day);

ALTER TABLE run_spend ENABLE ROW LEVEL SECURITY;
ALTER TABLE run_spend FORCE ROW LEVEL SECURITY;
CREATE POLICY run_spend_tenant ON run_spend FOR ALL
    USING (company_id = app_company_id())
    WITH CHECK (company_id = app_company_id());
-- Written once, never changed or deleted.
GRANT SELECT, INSERT ON run_spend TO ${app_role};

-- ---- company_spend_alert ----------------------------------------------------------------------------------------

-- The company's run_spend crossed its monthly alert amount (model_settings.monthly_alert_usd) in a calendar month
-- (UTC; month is its first day). Deleted after 400 days.
CREATE TABLE company_spend_alert (
    company_id  uuid           NOT NULL REFERENCES company (id) ON DELETE CASCADE,
    month       date           NOT NULL,
    crossed_at  timestamptz    NOT NULL,
    total_usd   numeric(16,4)  NOT NULL,
    alert_usd   numeric(16,4)  NOT NULL,
    PRIMARY KEY (company_id, month),
    CONSTRAINT company_spend_alert_month_ck CHECK (extract(day FROM month) = 1)
);

ALTER TABLE company_spend_alert ENABLE ROW LEVEL SECURITY;
ALTER TABLE company_spend_alert FORCE ROW LEVEL SECURITY;
CREATE POLICY company_spend_alert_tenant ON company_spend_alert FOR ALL
    USING (company_id = app_company_id())
    WITH CHECK (company_id = app_company_id());
GRANT SELECT, INSERT, DELETE ON company_spend_alert TO ${app_role};

-- ---- spend_due --------------------------------------------------------------------------------------------------

-- The due PENDING settlements across companies, oldest first, ids only, for SpendSync on worker replicas, which
-- claims each row again in its company's tenant context (FOR UPDATE SKIP LOCKED) and leases it.
CREATE POLICY spend_settlement_owner_lookup ON spend_settlement FOR SELECT TO CURRENT_USER USING (true);

CREATE FUNCTION spend_due(p_now timestamptz, p_limit integer)
    RETURNS TABLE (company_id uuid, run_id uuid)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
    SELECT s.company_id, s.run_id
      FROM ${flyway:defaultSchema}.spend_settlement s
     WHERE s.state = 'PENDING'
       AND s.due_at <= p_now
       AND (s.lease_until IS NULL OR s.lease_until < p_now)
     ORDER BY s.due_at, s.run_id
     LIMIT least(greatest(p_limit, 1), 500)
$$;

REVOKE ALL ON FUNCTION spend_due(timestamptz, integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION spend_due(timestamptz, integer) TO ${app_role};
