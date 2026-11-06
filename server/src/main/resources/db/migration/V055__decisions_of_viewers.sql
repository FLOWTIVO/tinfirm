-- The open decisions whose only candidates are Viewers go to the company's Owners and Admins, and those with the
-- Owners and Admins whose candidates hold a seat again go back to them (ADR-0077, decision 5; CONVENTIONS §15.24). A
-- decision is routed so when it is raised and whenever a role or a group's members change (DecisionRouting). This
-- reconciles those raised before that: their approver was made a Viewer, or every member of their approver group was,
-- after the decision was raised, or the other way. The rule is DecisionOps.approverOf, with the trigger's team and
-- author as they are now:
--
--   a personal conversation's decision      its person is a Viewer
--   a trigger's approval                    its approver (the author) is a Viewer, or its approver group has members
--                                           and every one of them is a Viewer
--   a trigger's question                    its trigger's author is a Viewer, and its team is none, empty or only
--                                           Viewers
--
-- No audit event is written: the migration is not an actor. The next change of a role or a group in the company
-- routes them again as the server does. Row-level security is forced on these tables, the owner's too: each company's
-- rows are routed in its own context (app.company_id), the companies read through company_owner_lookup (V023).
DO $$
DECLARE
    v_company uuid;
BEGIN
    FOR v_company IN SELECT id FROM company ORDER BY id LOOP
        PERFORM set_config('app.company_id', v_company::text, true);
        WITH viewer_principal AS (
            SELECT p.id
              FROM principal p
              JOIN company_membership m ON m.company_id = p.company_id AND m.user_id = p.user_id
             WHERE p.kind = 'MEMBER' AND m.role = 'VIEWER'
        ), viewer_group AS (
            SELECT g.group_id
              FROM user_group_member g
              JOIN company_membership m ON m.company_id = g.company_id AND m.user_id = g.user_id
             GROUP BY g.group_id
            HAVING bool_and(m.role = 'VIEWER')
        )
        UPDATE decision d
           SET approver_principal_id = NULL, approver_group_id = NULL, approver_admins = true
          FROM conversation c
          LEFT JOIN trigger t ON t.id = c.trigger_id AND t.company_id = c.company_id
         WHERE c.id = d.conversation_id AND c.company_id = d.company_id
           AND d.status = 'OPEN' AND NOT d.approver_admins
           AND ((c.trigger_id IS NULL AND c.person_principal_id IN (SELECT id FROM viewer_principal))
                OR (c.trigger_id IS NOT NULL AND d.kind = 'APPROVAL'
                    AND (d.approver_principal_id IN (SELECT id FROM viewer_principal)
                         OR d.approver_group_id IN (SELECT group_id FROM viewer_group)))
                OR (c.trigger_id IS NOT NULL AND d.kind = 'QUESTION'
                    AND coalesce(t.author_principal_id, c.author_principal_id) IN (SELECT id FROM viewer_principal)
                    AND (t.team_group_id IS NULL
                         OR t.team_group_id NOT IN (SELECT group_id FROM user_group_member)
                         OR t.team_group_id IN (SELECT group_id FROM viewer_group))));
        -- Back from the Owners and Admins: its person, its trigger's team, its trigger's author, or nobody (a trigger's
        -- question), once they are not only Viewers.
        WITH viewer_principal AS (
            SELECT p.id
              FROM principal p
              JOIN company_membership m ON m.company_id = p.company_id AND m.user_id = p.user_id
             WHERE p.kind = 'MEMBER' AND m.role = 'VIEWER'
        ), viewer_group AS (
            SELECT g.group_id
              FROM user_group_member g
              JOIN company_membership m ON m.company_id = g.company_id AND m.user_id = g.user_id
             GROUP BY g.group_id
            HAVING bool_and(m.role = 'VIEWER')
        )
        UPDATE decision d
           SET approver_admins = false,
               approver_principal_id = CASE
                   WHEN c.trigger_id IS NULL THEN c.person_principal_id
                   WHEN d.kind = 'APPROVAL' AND t.team_group_id IS NULL
                       THEN coalesce(t.author_principal_id, c.author_principal_id)
               END,
               approver_group_id = CASE WHEN c.trigger_id IS NOT NULL AND d.kind = 'APPROVAL' THEN t.team_group_id END
          FROM conversation c
          LEFT JOIN trigger t ON t.id = c.trigger_id AND t.company_id = c.company_id
         WHERE c.id = d.conversation_id AND c.company_id = d.company_id
           AND d.status = 'OPEN' AND d.approver_admins
           AND ((c.trigger_id IS NULL AND c.person_principal_id NOT IN (SELECT id FROM viewer_principal))
                OR (c.trigger_id IS NOT NULL AND d.kind = 'APPROVAL' AND t.team_group_id IS NOT NULL
                    AND t.team_group_id NOT IN (SELECT group_id FROM viewer_group))
                OR (c.trigger_id IS NOT NULL AND d.kind = 'APPROVAL' AND t.team_group_id IS NULL
                    AND coalesce(t.author_principal_id, c.author_principal_id) NOT IN (SELECT id FROM viewer_principal))
                OR (c.trigger_id IS NOT NULL AND d.kind = 'QUESTION'
                    AND NOT (coalesce(t.author_principal_id, c.author_principal_id) IN (SELECT id FROM viewer_principal)
                             AND (t.team_group_id IS NULL
                                  OR t.team_group_id NOT IN (SELECT group_id FROM user_group_member)
                                  OR t.team_group_id IN (SELECT group_id FROM viewer_group)))));
    END LOOP;
    PERFORM set_config('app.company_id', '', true);
END
$$;
