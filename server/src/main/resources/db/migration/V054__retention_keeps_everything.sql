-- Community's retention keeps everything (ADR-0077, decision 8; CONVENTIONS §7, §15.11): a run's events and a chat's
-- content may be kept for good, as content and files already may. NULL keeps them. Today's periods stay the
-- defaults, the starting values once a Pro or Enterprise key first comes into force; a company without a row has
-- them then, and under Community the sweep keeps everything whatever the row says (the server's RetentionPolicy).
--
--   run_events_days     7 to 3650, or NULL (keep)
--   chat_content_days   1 to 3650 and at most content_days, or NULL (keep) only while content_days keeps all content
ALTER TABLE retention_settings ALTER COLUMN run_events_days DROP NOT NULL;
ALTER TABLE retention_settings ALTER COLUMN chat_content_days DROP NOT NULL;
ALTER TABLE retention_settings DROP CONSTRAINT retention_run_events_ck;
ALTER TABLE retention_settings ADD CONSTRAINT retention_run_events_ck
    CHECK (run_events_days IS NULL OR run_events_days BETWEEN 7 AND 3650);
ALTER TABLE retention_settings DROP CONSTRAINT retention_chat_content_ck;
ALTER TABLE retention_settings ADD CONSTRAINT retention_chat_content_ck CHECK (
    (chat_content_days IS NULL AND content_days IS NULL)
    OR (chat_content_days IS NOT NULL AND chat_content_days BETWEEN 1 AND 3650
        AND (content_days IS NULL OR chat_content_days <= content_days)));
