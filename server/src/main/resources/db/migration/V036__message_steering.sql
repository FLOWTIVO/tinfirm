-- Steering (the runner protocol 2 feature run.steer): a person's message written while a turn runs goes to that
-- turn's agent, which takes it after its current step, instead of waiting for the next turn.
--
--   message.steering  SENDING: sent to the running turn's runner host, whose answer is due; the message still waits
--                     (run_id NULL), and the next turn takes it if the running one does not. DELIVERED: the running
--                     turn took it, and run_id is that turn's run. NULL for every other message, and for one the
--                     running turn did not take, which waits for the next turn as before.
--
-- A message that stops waiting (a turn takes it, a cancel or an end leaves it unanswered) is no longer SENDING, and
-- a retry, which takes the request of the turn it retries, no longer marks it DELIVERED.

ALTER TABLE message ADD COLUMN steering varchar(9);

ALTER TABLE message ADD CONSTRAINT message_steering_ck CHECK (
    steering IS NULL
    OR (kind = 'PERSON' AND steering = 'SENDING' AND run_id IS NULL AND NOT unanswered)
    OR (kind = 'PERSON' AND steering = 'DELIVERED' AND run_id IS NOT NULL));
