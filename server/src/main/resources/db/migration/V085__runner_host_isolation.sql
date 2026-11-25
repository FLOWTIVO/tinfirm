-- ADR-0113, decisions 1 to 3: what a runner host reports about its isolation, stored at every hello and shown as
-- reported. The server checks none of it.
--
--   isolation_level      the level of hello.isolationTiers (MACHINE, VM_JAIL, CONTAINER), the weakest when a hello
--                        carries several; gvisor is CONTAINER, and a hello of protocol 1 is CONTAINER. NULL only
--                        before the host's first hello to a server of this release, and such a host is placed as
--                        CONTAINER
--   isolation_gvisor     the tier was gvisor: a container host whose runtime is runsc
--   isolation_hardened   hello.hardened (YES, NO, NONE; absent is NO), exactly with a level
--   isolation_stated     hello.isolationStated: the level rests on the operator's statement, not on what the host
--                        can see; false without a level
--
-- The tiers themselves are not kept: a host sends one, and the level and isolation_gvisor say all of it.
--
-- The facts live where the other facts of a hello do (V064): in a company host's runner_host row, and in a member's
-- machine (personal_runner_host), which shows them in each company through its presences. A presence's own columns
-- stay empty, as its version and runtimes do.

ALTER TABLE runner_host
    ADD COLUMN isolation_level    varchar(16),
    ADD COLUMN isolation_gvisor   boolean NOT NULL DEFAULT false,
    ADD COLUMN isolation_hardened varchar(8),
    ADD COLUMN isolation_stated   boolean NOT NULL DEFAULT false,
    ADD CONSTRAINT runner_host_isolation_level_ck
        CHECK (isolation_level IS NULL OR isolation_level IN ('MACHINE', 'VM_JAIL', 'CONTAINER')),
    ADD CONSTRAINT runner_host_isolation_hardened_ck
        CHECK (isolation_hardened IS NULL OR isolation_hardened IN ('YES', 'NO', 'NONE')),
    ADD CONSTRAINT runner_host_isolation_ck
        CHECK ((isolation_level IS NULL) = (isolation_hardened IS NULL)
            AND (isolation_level IS NOT NULL OR NOT isolation_stated)
            AND (NOT isolation_gvisor OR isolation_level IS NOT DISTINCT FROM 'CONTAINER'));

ALTER TABLE personal_runner_host
    ADD COLUMN isolation_level    varchar(16),
    ADD COLUMN isolation_gvisor   boolean NOT NULL DEFAULT false,
    ADD COLUMN isolation_hardened varchar(8),
    ADD COLUMN isolation_stated   boolean NOT NULL DEFAULT false,
    ADD CONSTRAINT personal_runner_host_isolation_level_ck
        CHECK (isolation_level IS NULL OR isolation_level IN ('MACHINE', 'VM_JAIL', 'CONTAINER')),
    ADD CONSTRAINT personal_runner_host_isolation_hardened_ck
        CHECK (isolation_hardened IS NULL OR isolation_hardened IN ('YES', 'NO', 'NONE')),
    ADD CONSTRAINT personal_runner_host_isolation_ck
        CHECK ((isolation_level IS NULL) = (isolation_hardened IS NULL)
            AND (isolation_level IS NOT NULL OR NOT isolation_stated)
            AND (NOT isolation_gvisor OR isolation_level IS NOT DISTINCT FROM 'CONTAINER'));
