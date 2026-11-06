-- Package 5, part b: the generic webhook (the package 5 contract, docs/reviews/2026-10-12-package-5-contract.md §3.1,
-- §3.9, §3.10, §3.12, §5.12, open points 15 and 17).
--
-- The trigger's source WEBHOOK, the place WEBHOOK and the firing's event DELIVERED are V030's, with the channel
-- triggers' checks they share.
--
--   external_system      a WEBHOOK system's secret (WEBHOOK_SECRET, a managed company secret Tinfirm generates), and
--                        for 24 hours after a rotation the previous one (WEBHOOK_SECRET_PREVIOUS); its settings
--                        (scheme, secretHeader, mapping, maxPerMinute) are in settings, as a chat system's are.
--   webhook_delivery     each verified delivery once (C3): its id, when it was signed and received, what it did
--                        (FIRED, NO_TRIGGER, MAPPING_FAILED), its mapped event and subject, its firings, and its body for
--                        24 hours; rows kept 7 days. A refused delivery stores nothing.
--   webhook_system_by_id the company of an inbound request's webhook system (C2), across companies, ids only.
--   trigger              a webhook trigger's conditions on the raw body: at most 5 {pointer, op, values}.
--   trigger_setting      the company's webhook deliveries a minute per system: a default and a maximum (open point 11).
--   conversation         one open conversation per webhook trigger and mapped subject key
--                        (conversation_trigger_webhook_open_uq).
--   message              a webhook conversation's EXTERNAL message has no member (C13): message_place_ck.
--
-- The conventions of V001 apply: company_id, ENABLE plus FORCE ROW LEVEL SECURITY, a policy on app_company_id(),
-- composite foreign keys with the company id, and grants to the runtime role ${app_role}.

-- ---- external_system (contract §3.1) -------------------------------------------------------------------------------

ALTER TABLE external_system ADD COLUMN webhook_secret_id uuid;
ALTER TABLE external_system ADD COLUMN webhook_previous_secret_id uuid;
ALTER TABLE external_system ADD COLUMN webhook_secret_set_at timestamptz;
ALTER TABLE external_system ADD COLUMN webhook_previous_until timestamptz;
ALTER TABLE external_system ADD CONSTRAINT external_system_webhook_secret_fk
    FOREIGN KEY (webhook_secret_id, company_id)
    REFERENCES secret (id, company_id) ON DELETE SET NULL (webhook_secret_id);
ALTER TABLE external_system ADD CONSTRAINT external_system_webhook_previous_secret_fk
    FOREIGN KEY (webhook_previous_secret_id, company_id)
    REFERENCES secret (id, company_id) ON DELETE SET NULL (webhook_previous_secret_id);
ALTER TABLE external_system ADD CONSTRAINT external_system_webhook_secret_ck CHECK (
    (webhook_secret_id IS NULL AND webhook_previous_secret_id IS NULL AND webhook_secret_set_at IS NULL
     AND webhook_previous_until IS NULL) OR kind = 'WEBHOOK');
CREATE INDEX external_system_webhook_secret_idx ON external_system (webhook_secret_id)
    WHERE webhook_secret_id IS NOT NULL;
CREATE INDEX external_system_webhook_previous_secret_idx ON external_system (webhook_previous_secret_id)
    WHERE webhook_previous_secret_id IS NOT NULL;

-- The company and kind of an inbound request's webhook system (C2): ids only, across companies.
CREATE FUNCTION webhook_system_by_id(p_system_id uuid)
    RETURNS TABLE (system_id uuid, company_id uuid, kind varchar)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
    SELECT s.id, s.company_id, s.kind
      FROM ${flyway:defaultSchema}.external_system s
     WHERE s.id = p_system_id AND s.kind = 'WEBHOOK'
$$;

REVOKE ALL ON FUNCTION webhook_system_by_id(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION webhook_system_by_id(uuid) TO ${app_role};

-- ---- webhook_delivery (contract §3.12) -----------------------------------------------------------------------------

CREATE TABLE webhook_delivery (
    id           uuid          PRIMARY KEY,
    company_id   uuid          NOT NULL,
    system_id    uuid          NOT NULL,
    delivery_id  varchar(200)  NOT NULL,
    signed_at    timestamptz,
    received_at  timestamptz   NOT NULL,
    status       varchar(16)   NOT NULL,
    event        varchar(200),
    subject_key  varchar(200),
    firings      integer       NOT NULL DEFAULT 0,
    failure      varchar(500),
    body         jsonb,
    body_bytes   integer       NOT NULL,
    CONSTRAINT webhook_delivery_id_company_uq UNIQUE (id, company_id),
    CONSTRAINT webhook_delivery_system_fk FOREIGN KEY (system_id, company_id)
        REFERENCES external_system (id, company_id) ON DELETE CASCADE,
    -- C3: each delivery once per system.
    CONSTRAINT webhook_delivery_uq UNIQUE (system_id, delivery_id),
    CONSTRAINT webhook_delivery_id_ck CHECK (delivery_id ~ '^[\x21-\x7e]{1,200}$'),
    CONSTRAINT webhook_delivery_status_ck CHECK (status IN ('FIRED', 'NO_TRIGGER', 'MAPPING_FAILED')),
    CONSTRAINT webhook_delivery_firings_ck CHECK (firings >= 0 AND (status = 'FIRED') = (firings > 0)),
    CONSTRAINT webhook_delivery_failure_ck CHECK ((status = 'MAPPING_FAILED') = (failure IS NOT NULL)),
    CONSTRAINT webhook_delivery_bytes_ck CHECK (body_bytes BETWEEN 0 AND 1048576)
);

-- The admins' list, newest first; the retention's sweeps.
CREATE INDEX webhook_delivery_list_idx ON webhook_delivery (system_id, received_at DESC, id DESC);
CREATE INDEX webhook_delivery_received_idx ON webhook_delivery (received_at);
CREATE INDEX webhook_delivery_company_idx ON webhook_delivery (company_id);

ALTER TABLE webhook_delivery ENABLE ROW LEVEL SECURITY;
ALTER TABLE webhook_delivery FORCE ROW LEVEL SECURITY;

CREATE POLICY webhook_delivery_tenant ON webhook_delivery FOR ALL
    USING (company_id = app_company_id())
    WITH CHECK (company_id = app_company_id());

GRANT SELECT, INSERT, UPDATE, DELETE ON webhook_delivery TO ${app_role};

-- ---- trigger (contract §3.10, §5.12) -------------------------------------------------------------------------------

-- A webhook trigger's conditions on the raw body: at most 5 {pointer, op: EQUALS | IN | PRESENT | ABSENT, values}.
ALTER TABLE trigger ADD COLUMN webhook_conditions jsonb NOT NULL DEFAULT '[]'::jsonb;
ALTER TABLE trigger ADD CONSTRAINT trigger_webhook_conditions_ck CHECK (
    jsonb_typeof(webhook_conditions) = 'array' AND jsonb_array_length(webhook_conditions) <= 5
    AND (jsonb_array_length(webhook_conditions) = 0 OR source = 'WEBHOOK'));

-- The company's webhook deliveries a minute per system (open point 11): null until admins set them, the instance's
-- configuration meanwhile.
ALTER TABLE trigger_setting ADD COLUMN webhook_per_minute_default integer;
ALTER TABLE trigger_setting ADD COLUMN webhook_per_minute_max integer;
ALTER TABLE trigger_setting ADD CONSTRAINT trigger_setting_webhook_ck CHECK (
    (webhook_per_minute_default IS NULL AND webhook_per_minute_max IS NULL)
    OR (webhook_per_minute_default >= 1 AND webhook_per_minute_max <= 6000
        AND webhook_per_minute_default <= webhook_per_minute_max));

-- ---- conversation and message (contract §3.9) ----------------------------------------------------------------------

-- One open conversation per webhook trigger and mapped subject key; without a key, one per delivery.
CREATE UNIQUE INDEX conversation_trigger_webhook_open_uq ON conversation (trigger_id, (place_ref ->> 'subjectKey'))
    WHERE state = 'OPEN' AND place = 'WEBHOOK';

-- C13: a webhook's words are the sender's, never a member's.
CREATE FUNCTION message_place_ck() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.kind = 'EXTERNAL' AND NEW.author_principal_id IS NOT NULL AND EXISTS (
            SELECT 1 FROM conversation c
             WHERE c.id = NEW.conversation_id AND c.company_id = NEW.company_id AND c.place = 'WEBHOOK') THEN
        RAISE EXCEPTION 'A webhook conversation''s EXTERNAL message has no member as its author'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'message_place_ck';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER message_place_ck_trg BEFORE INSERT OR UPDATE ON message
    FOR EACH ROW EXECUTE FUNCTION message_place_ck();
