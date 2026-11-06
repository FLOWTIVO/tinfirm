-- Shared agents' instructions and skills (the security review of 2026-10-27, D1 and I5; the owner's decision Q4). The
-- schema owner runs this file.
--
--   agent_version.instructions          the agent's instructions as of the version: a person or a trigger's author
--                                       who accepted an earlier version keeps its instructions while a change of
--                                       them waits for acceptance, and the acceptance shows the diff
--   agent_version.skills                the skills the agent pins as of the version, [{skillId, version}] by skill id
--   conversation.agent_content_version  the version of its agent whose instructions and skills its latest turn used;
--                                       a turn that uses others posts that they changed. NULL until its first turn
--
-- A change of the instructions or skills widens when the ceiling after it has a tool ALLOW chosen for a write, a
-- repository at WRITE or a network host; otherwise it reaches everyone's next turn and is announced there. The
-- versions made before this file take the agent's instructions and pins as they are now (they followed the current
-- version until now), and the open conversations the agent's current version, so that nothing reads as changed.

ALTER TABLE agent_version ADD COLUMN instructions text NOT NULL DEFAULT '';
ALTER TABLE agent_version ADD COLUMN skills jsonb NOT NULL DEFAULT '[]'::jsonb;
ALTER TABLE agent_version ADD CONSTRAINT agent_version_skills_ck CHECK (jsonb_typeof(skills) = 'array');

ALTER TABLE conversation ADD COLUMN agent_content_version integer;
ALTER TABLE conversation ADD CONSTRAINT conversation_agent_content_version_ck
    CHECK (agent_content_version IS NULL OR agent_content_version >= 1);

-- The schema owner bypasses row-level security for this step only, as in V033, on the tables it changes and reads.
-- Nothing checks a changed row of these tables against another table.
ALTER TABLE agent_version NO FORCE ROW LEVEL SECURITY;
ALTER TABLE agent NO FORCE ROW LEVEL SECURITY;
ALTER TABLE agent_skill NO FORCE ROW LEVEL SECURITY;
ALTER TABLE conversation NO FORCE ROW LEVEL SECURITY;

UPDATE agent_version v
   SET instructions = a.instructions,
       skills = coalesce((SELECT jsonb_agg(jsonb_build_object('skillId', s.skill_id, 'version', s.version)
                                           ORDER BY s.skill_id)
                            FROM agent_skill s
                           WHERE s.agent_id = a.id AND s.company_id = a.company_id), '[]'::jsonb)
  FROM agent a
 WHERE a.id = v.agent_id AND a.company_id = v.company_id;

UPDATE conversation c
   SET agent_content_version = a.version
  FROM agent a
 WHERE a.id = c.agent_id AND a.company_id = c.company_id AND c.state = 'OPEN';

ALTER TABLE conversation FORCE ROW LEVEL SECURITY;
ALTER TABLE agent_skill FORCE ROW LEVEL SECURITY;
ALTER TABLE agent FORCE ROW LEVEL SECURITY;
ALTER TABLE agent_version FORCE ROW LEVEL SECURITY;
