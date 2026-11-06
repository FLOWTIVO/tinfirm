-- Package 6's kill switches in the chats (the package 6 contract, §5.4, G4 to G6; CONVENTIONS §15.16). A chat
-- event's reasons gain SYSTEM_PAUSED (a paused chat system's events are verified and dropped), COMPANY_STOPPED (a
-- direct message that would start a conversation while the company's runs are stopped; a press that approves then)
-- and SUSPENDED (a suspended member's events, refused as a non-member's). A chat user is told of the stop at most once
-- an hour, apart from the refusals.

ALTER TABLE chat_event DROP CONSTRAINT chat_event_reason_ck;
ALTER TABLE chat_event ADD CONSTRAINT chat_event_reason_ck CHECK (reason IS NULL OR reason IN (
    'GUEST', 'EXTERNAL', 'BOT', 'DELETED', 'UNLINKED', 'NOT_YOURS', 'RATE_LIMITED', 'NO_TRIGGER', 'OTHER_AGENT',
    'THREAD_ENDED', 'TRIGGER_PAUSED', 'AGENT_PAUSED', 'NO_BOT', 'NOT_ADDRESSED', 'NO_AGENT_CHOSEN',
    'LINK_EXPIRED', 'NOT_USABLE', 'NOT_SERVED', 'START_REFUSED', 'FAILED', 'EDITED', 'OTHER_TEAM',
    'SYSTEM_PAUSED', 'COMPANY_STOPPED', 'SUSPENDED'));

-- stop_told_at: the last time the chat user was told that the company's runs are stopped, at most once an hour.
ALTER TABLE chat_user ADD COLUMN stop_told_at timestamptz;
