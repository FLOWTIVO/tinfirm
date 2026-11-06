-- Agent memory (ADR-0063, CONVENTIONS §15.18): notes an agent keeps for a person or a trigger, written by the agent
-- with its tools remember and forget, read progressively (a hint, general and repository notes at a session's start, a
-- system's notes with its first tool result, recall), a trigger's reviewed by its team, compacted every 30 days on the
-- company's platform credential, and kept two years from last use.
--
--   agent.memory_allowed            whether the agent may keep notes at all: its editor's choice, allowed by default
--   agent_settings.memory           the opt-in of the settings' owner (a person, or a trigger's author), off by default
--   agent_settings.memory_*         the memory's next note number, last change and last compaction
--   agent_memory_note               one note, on the settings row of the memory it belongs to
--   retention_settings.memory_days  how long a note not used is kept (two years by default)
--   model_settings.platform_*       the company's platform credential: a service identity and a model, for the server's
--                                   own model requests (a memory's compaction)
--
-- The conventions of V001 apply: company_id, ENABLE plus FORCE ROW LEVEL SECURITY, a policy on app_company_id(),
-- composite foreign keys with the company id, and grants to ${app_role}. A note holds the agent's words, never a
-- credential: the service scans each note with the vault before it writes it (ADR-0014).

ALTER TABLE agent
    ADD COLUMN memory_allowed boolean NOT NULL DEFAULT true;

-- memory_next_number: the number the next note gets, so that a number is never reused in the row; memory_changed_at:
-- the last note saved, accepted or deleted; memory_compacted_at: the last compaction (at most once in 30 days).
ALTER TABLE agent_settings
    ADD COLUMN memory              boolean     NOT NULL DEFAULT false,
    ADD COLUMN memory_next_number  integer     NOT NULL DEFAULT 1,
    ADD COLUMN memory_changed_at   timestamptz,
    ADD COLUMN memory_compacted_at timestamptz,
    ADD CONSTRAINT agent_settings_memory_next_ck CHECK (memory_next_number >= 1);

-- The memories due for a compaction: changed since the last one.
CREATE INDEX agent_settings_memory_due_idx ON agent_settings (memory_changed_at)
    WHERE memory_changed_at IS NOT NULL;

-- One note of a memory.
--
--   settings_id      the memory it belongs to: one person's settings of one agent, or one trigger's; the note goes
--                    with the row (a member who leaves, a deleted trigger, a deleted agent)
--   number           its id in the prompt and the tools, m{number}: unique in its memory and never reused there
--   state            ACTIVE: conversations read it; PROPOSED: a trigger's note its audience has not accepted yet
--   about            GENERAL, SYSTEM (system_id: a system the agent uses) or REPOSITORY (repository_id: one of the
--                    agent's repositories); a note about a system or repository goes with it
--   body             the note, 1 to 300 characters, on one line: no control character (a line break included), so that
--                    a note can never start a section of the prompt it is quoted in
--   replaces_number  a proposal's: the note it replaces once accepted
--   conversation_id  the conversation it was last saved in; NULL when written otherwise or once that one is gone
--   learned_in       a note a compaction merged: the conversations its notes were learned in, which an erasure and a
--                    hold reach as they reach conversation_id
--   request_id       the run.request that last saved it, so that a request sent again saves nothing twice
--   used_at          when it was last saved, shown to an agent or recalled: retention purges it memory_days after,
--                    and the tiers of the prompt show the most recently used first
--   search           its words for recall (the 'simple' configuration: no language's stemming)
CREATE TABLE agent_memory_note (
    id              uuid        PRIMARY KEY,
    company_id      uuid        NOT NULL,
    settings_id     uuid        NOT NULL,
    number          integer     NOT NULL,
    state           varchar(8)  NOT NULL,
    about           varchar(10) NOT NULL,
    system_id       uuid,
    repository_id   uuid,
    body            text        NOT NULL,
    replaces_number integer,
    conversation_id uuid,
    learned_in      uuid[]      NOT NULL DEFAULT '{}',
    request_id      uuid,
    created_at      timestamptz NOT NULL,
    updated_at      timestamptz NOT NULL,
    used_at         timestamptz NOT NULL,
    search          tsvector    GENERATED ALWAYS AS (to_tsvector('simple', body)) STORED,
    CONSTRAINT agent_memory_note_settings_fk FOREIGN KEY (settings_id, company_id)
        REFERENCES agent_settings (id, company_id) ON DELETE CASCADE,
    CONSTRAINT agent_memory_note_system_fk FOREIGN KEY (system_id, company_id)
        REFERENCES external_system (id, company_id) ON DELETE CASCADE,
    CONSTRAINT agent_memory_note_repository_fk FOREIGN KEY (repository_id, company_id)
        REFERENCES system_repository (id, company_id) ON DELETE CASCADE,
    CONSTRAINT agent_memory_note_conversation_fk FOREIGN KEY (conversation_id, company_id)
        REFERENCES conversation (id, company_id) ON DELETE SET NULL (conversation_id),
    CONSTRAINT agent_memory_note_number_uq UNIQUE (settings_id, number),
    CONSTRAINT agent_memory_note_number_ck CHECK (number >= 1 AND (replaces_number IS NULL OR replaces_number >= 1)),
    CONSTRAINT agent_memory_note_learned_ck CHECK (cardinality(learned_in) <= 1000),
    CONSTRAINT agent_memory_note_state_ck CHECK (state IN ('ACTIVE', 'PROPOSED')),
    CONSTRAINT agent_memory_note_replaces_ck CHECK (state = 'PROPOSED' OR replaces_number IS NULL),
    CONSTRAINT agent_memory_note_about_ck CHECK (
        (about = 'GENERAL' AND system_id IS NULL AND repository_id IS NULL)
        OR (about = 'SYSTEM' AND system_id IS NOT NULL AND repository_id IS NULL)
        OR (about = 'REPOSITORY' AND system_id IS NULL AND repository_id IS NOT NULL)),
    CONSTRAINT agent_memory_note_body_ck
        CHECK (char_length(body) BETWEEN 1 AND 300 AND body = btrim(body) AND body !~ '[[:cntrl:]]')
);

-- A request sent again finds the note it saved.
CREATE UNIQUE INDEX agent_memory_note_request_uq ON agent_memory_note (settings_id, request_id)
    WHERE request_id IS NOT NULL;
-- A memory's notes by what they are about: the tiers of the prompt and a system's notes with its tools.
CREATE INDEX agent_memory_note_about_idx ON agent_memory_note (settings_id, state, about, used_at DESC);
-- recall.
CREATE INDEX agent_memory_note_search_idx ON agent_memory_note USING gin (search);
-- The retention sweep: the notes last used before a time, per company.
CREATE INDEX agent_memory_note_used_idx ON agent_memory_note (company_id, used_at);
-- An erasure: the notes learned in a conversation; and the foreign keys.
CREATE INDEX agent_memory_note_conversation_idx ON agent_memory_note (conversation_id)
    WHERE conversation_id IS NOT NULL;
CREATE INDEX agent_memory_note_learned_idx ON agent_memory_note USING gin (learned_in)
    WHERE cardinality(learned_in) > 0;
CREATE INDEX agent_memory_note_system_idx ON agent_memory_note (system_id) WHERE system_id IS NOT NULL;
CREATE INDEX agent_memory_note_repository_idx ON agent_memory_note (repository_id) WHERE repository_id IS NOT NULL;

ALTER TABLE agent_memory_note ENABLE ROW LEVEL SECURITY;
ALTER TABLE agent_memory_note FORCE ROW LEVEL SECURITY;

CREATE POLICY agent_memory_note_tenant ON agent_memory_note FOR ALL
    USING (company_id = app_company_id())
    WITH CHECK (company_id = app_company_id());

-- Written by the agent's tools and the compaction, changed in place when replaced, accepted or used, deleted by its
-- owner, a review, an erasure, the compaction or retention.
GRANT SELECT, INSERT, UPDATE, DELETE ON agent_memory_note TO ${app_role};

-- How long a note not used is kept, in days: two years by default; NULL keeps notes until they are deleted.
ALTER TABLE retention_settings
    ADD COLUMN memory_days integer DEFAULT 730,
    ADD CONSTRAINT retention_memory_ck CHECK (memory_days IS NULL OR memory_days BETWEEN 30 AND 3650);

-- The company's platform credential: a service identity whose LiteLLM key and budget pay for the server's own model
-- requests, never a person's or a trigger's payer, and the model they use. NULL: none, and a compaction runs without a
-- model. A deleted service identity leaves none.
ALTER TABLE model_settings
    ADD COLUMN platform_principal_id   uuid,
    ADD COLUMN platform_principal_kind varchar(8) NOT NULL DEFAULT 'SERVICE',
    ADD COLUMN platform_model          varchar(200),
    ADD CONSTRAINT model_settings_platform_kind_ck CHECK (platform_principal_kind = 'SERVICE'),
    ADD CONSTRAINT model_settings_platform_fk FOREIGN KEY (platform_principal_id, company_id, platform_principal_kind)
        REFERENCES principal (id, company_id, kind) ON DELETE SET NULL (platform_principal_id),
    ADD CONSTRAINT model_settings_platform_ck CHECK (platform_principal_id IS NULL OR platform_model IS NOT NULL),
    ADD CONSTRAINT model_settings_platform_model_ck
        CHECK (platform_model IS NULL OR platform_model ~ '^[A-Za-z0-9][A-Za-z0-9._:/@+\[\]-]{0,199}$');

CREATE INDEX model_settings_platform_idx ON model_settings (platform_principal_id) WHERE platform_principal_id IS NOT NULL;
