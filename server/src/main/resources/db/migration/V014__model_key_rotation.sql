-- The rotation of principals' LiteLLM keys (security review row 2 of the platform redesign: keys rotate on a schedule
-- and at once on suspicion; CONVENTIONS §15.4).
--
-- LiteLLM v1.103.0 regenerates a key only for enterprise licences (/key/regenerate), and a key alias is unique there,
-- so Tinfirm rotates a key itself: it creates the next key with the next alias (tinfirm-{companyId}-{principalId} for
-- generation 0, ...-r{n} after it) and the same team, budget, period, models and metadata, carrying the old key's
-- spend over; the relay switches to it in the transaction that records it; the old key stays usable for a grace while
-- relayed streams that hold it finish, is then blocked, and is deleted a minute later, once LiteLLM has written the
-- spend of its last requests, which goes over to the new key. Every step is recorded here before the next one runs,
-- so that a sync that died between two steps is finished by the next one (the reconciliation).
--
--   key_generation         the generation of the current key, which names its alias; 0 until the first rotation
--   key_created_at         when LiteLLM created the current key (its age; it rotates key-rotation-interval later)
--   key_rotated_at         when the current key replaced an earlier one by a rotation; NULL for a key never rotated
--   rotation_requested_at  an admin asked to rotate the key at once (one principal, or every key of the company); the
--                          old key gets no grace. Cleared once the current key is newer than the request
--   rotation_error         why the last attempt to rotate failed (LiteLLM's reason), until a rotation succeeds; the
--                          current key keeps working meanwhile
--   retiring_key_id        the previous key (LiteLLM's id, never the key), still in LiteLLM after a rotation; its
--                          secret is gone with the rotation
--   retiring_until         the previous key serves in-flight requests until then (the grace; the rotation's own time
--                          for a rotation an admin asked for), and is blocked then
--   retiring_blocked_at    when LiteLLM blocked the previous key; it is deleted a minute later
--   retiring_spend_usd,    the previous key's spend, and its reset time, when the current key was created with that
--   retiring_resets_at     spend: what the previous key spends after that goes over to the current key
ALTER TABLE principal_model_key
    ADD COLUMN key_generation        integer       NOT NULL DEFAULT 0,
    ADD COLUMN key_created_at        timestamptz,
    ADD COLUMN key_rotated_at        timestamptz,
    ADD COLUMN rotation_requested_at timestamptz,
    ADD COLUMN rotation_error        varchar(500),
    ADD COLUMN retiring_key_id       varchar(128),
    ADD COLUMN retiring_until        timestamptz,
    ADD COLUMN retiring_blocked_at   timestamptz,
    ADD COLUMN retiring_spend_usd    numeric(16,6),
    ADD COLUMN retiring_resets_at    timestamptz;

-- The keys that exist were created with their row: their age counts from there, so that the upgrade does not rotate
-- every key at once. The schema owner bypasses row-level security for this step only, as in V002.
ALTER TABLE principal_model_key NO FORCE ROW LEVEL SECURITY;
UPDATE principal_model_key SET key_created_at = created_at WHERE litellm_key_id IS NOT NULL;
ALTER TABLE principal_model_key FORCE ROW LEVEL SECURITY;

ALTER TABLE principal_model_key
    ADD CONSTRAINT principal_model_key_generation_ck CHECK (key_generation >= 0),
    ADD CONSTRAINT principal_model_key_created_ck CHECK (litellm_key_id IS NULL OR key_created_at IS NOT NULL),
    ADD CONSTRAINT principal_model_key_retiring_ck CHECK ((retiring_key_id IS NULL) = (retiring_until IS NULL)),
    ADD CONSTRAINT principal_model_key_retiring_blocked_ck
        CHECK (retiring_blocked_at IS NULL OR retiring_key_id IS NOT NULL),
    ADD CONSTRAINT principal_model_key_retiring_other_ck
        CHECK (retiring_key_id IS NULL OR retiring_key_id IS DISTINCT FROM litellm_key_id);

-- M1: a previous key is never another principal's either.
CREATE UNIQUE INDEX principal_model_key_retiring_uq ON principal_model_key (company_id, retiring_key_id)
    WHERE retiring_key_id IS NOT NULL;
