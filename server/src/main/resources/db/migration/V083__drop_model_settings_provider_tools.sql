-- No model provider runs a tool itself for a Tinfirm run (ADR-0108, the owner's decision of 2026-11-06): every web
-- search and every MCP call goes through Tinfirm, and the relay and the model port refuse a request that asks a
-- provider for one, always. So a company's choice of the providers' web search (V076, M1 of the security review of
-- 2026-10-27) is gone, and with it its column; what a company had chosen no longer applies.

ALTER TABLE model_settings
    DROP CONSTRAINT model_settings_provider_tools_ck,
    DROP COLUMN provider_tools;
