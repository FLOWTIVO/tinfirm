-- Company runner hosts that the deployment declares (ADR-0101, decision 8): TINFIRM_COMPANIES_<KEY>_RUNNER_HOST_NAME
-- and _TOKEN, such as the Helm chart's runner pod. The server registers the host at its start, with the declared
-- token's hash, and replaces the hash when the declared token changes.
--
--   declared  the host was registered by a declaration; a host registered in the app never is, and a declaration
--             never takes one over (the start fails instead)

ALTER TABLE runner_host
    ADD COLUMN declared boolean NOT NULL DEFAULT false,
    ADD CONSTRAINT runner_host_declared_ck CHECK (NOT declared OR scope = 'COMPANY');
