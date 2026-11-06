-- The names of the documents a run leaves in /output (package 3c, part 3; runner protocol v2's ArtifactResult.name in
-- run.finished.artifacts, and the upload's name with artifact=true): a path relative to the folder of at most 1024
-- characters, as the protocol allows. V008 kept 500, so a longer path the protocol accepts was refused at its upload.
--
--   attachment.file_name   the path, as V008 has it (segments separated by '/', none of them empty, '.' or '..' or
--                          longer than 255 characters, no '\', no leading or trailing '/'), now of 1 to 1024
--                          characters. A person's file keeps a name of one segment of at most 255 (the server).
--   attachment_name_uq     one name per conversation, now kept on the name's MD5: a B-tree entry holds at most 2704
--                          bytes, and 1024 characters take up to 4096 in UTF-8. The server checks the name under the
--                          conversation's lock before it inserts; the index keeps the invariant against anything else.
--                          MD5 serves as a key here, not as a protection.
--
-- The conventions of V001 apply: every tenant table has company_id, ENABLE plus FORCE ROW LEVEL SECURITY, a policy on
-- app_company_id(), composite foreign keys with the company, and grants to the runtime role ${app_role}.

ALTER TABLE attachment DROP CONSTRAINT attachment_name_uq;
ALTER TABLE attachment DROP CONSTRAINT attachment_name_ck;
ALTER TABLE attachment ALTER COLUMN file_name TYPE varchar(1024);
ALTER TABLE attachment ADD CONSTRAINT attachment_name_ck CHECK (length(file_name) BETWEEN 1 AND 1024
    AND position('\' IN file_name) = 0
    AND left(file_name, 1) <> '/' AND right(file_name, 1) <> '/' AND position('//' IN file_name) = 0
    AND file_name !~ '(^|/)\.\.?(/|$)'
    AND file_name !~ '[^/]{255}[^/]');
CREATE UNIQUE INDEX attachment_name_uq ON attachment (conversation_id, md5(file_name));
