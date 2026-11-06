-- More than one model gateway (ADR-0094, phase 0): which product a company's gateway is, and a key's id at the gateway
-- in a name of no vendor's. The schema owner runs this file.
--
--   model_settings.vendor        the product the company's gateway is, whose adapter speaks to it: LITELLM, the only
--                                one so far, for every row
--   *_model_key.gateway_key_id   the key's id at the gateway, renamed from litellm_key_id (for LiteLLM the SHA-256 of
--                                the key, its "token"); the checks that name it follow the column

ALTER TABLE model_settings
    ADD COLUMN vendor varchar(16) NOT NULL DEFAULT 'LITELLM',
    ADD CONSTRAINT model_settings_vendor_ck CHECK (vendor IN ('LITELLM'));

ALTER TABLE principal_model_key RENAME COLUMN litellm_key_id TO gateway_key_id;
ALTER INDEX principal_model_key_litellm_uq RENAME TO principal_model_key_gateway_uq;

ALTER TABLE company_model_key RENAME COLUMN litellm_key_id TO gateway_key_id;
