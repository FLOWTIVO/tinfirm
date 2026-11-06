-- Which tools the model's provider may run itself for a company's relayed model requests (the security review of
-- 2026-10-27, M1 and D8): OFF, none, so that a request that names a provider's server-side tool (web search or fetch,
-- code execution, MCP) is refused 400; WEB_SEARCH, the providers' web search and nothing else. An admin's choice on the
-- model settings; every existing company starts with OFF.

ALTER TABLE model_settings
    ADD COLUMN provider_tools varchar(16) NOT NULL DEFAULT 'OFF',
    ADD CONSTRAINT model_settings_provider_tools_ck CHECK (provider_tools IN ('OFF', 'WEB_SEARCH'));
