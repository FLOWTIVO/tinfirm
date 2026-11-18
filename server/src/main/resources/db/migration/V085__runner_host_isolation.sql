-- ADR-0113, decisions 1 to 3: what a runner host reports about its isolation, stored at every hello and shown as
-- reported. The server checks none of it.
--
--   isolation_tiers      the tiers of hello.isolationTiers (CONTAINER, GVISOR, MICROVM); NULL before the first hello
--                        of a server that stores them
--   isolation_level      hello.isolation.level of a host that lists the feature host.isolation (MACHINE, VM_JAIL,
--                        CONTAINER); NULL for a host that reports no level, and a later hello without the feature
--                        clears it
--   isolation_hardened   hello.isolation.hardened (YES, NO, NONE), exactly with a level
--   isolation_stated     hello.isolation.stated: the level rests on the operator's statement, not on what the host
--                        can see; false without a level
--
-- The facts live where the other facts of a hello do (V064): in a company host's runner_host row, and in a member's
-- machine (personal_runner_host), which shows them in each company through its presences. A presence's own columns
-- stay empty, as its version and runtimes do.

ALTER TABLE runner_host
    ADD COLUMN isolation_tiers    varchar(16)[],
    ADD COLUMN isolation_level    varchar(16),
    ADD COLUMN isolation_hardened varchar(8),
    ADD COLUMN isolation_stated   boolean NOT NULL DEFAULT false,
    ADD CONSTRAINT runner_host_isolation_tiers_ck
        CHECK (isolation_tiers IS NULL OR (cardinality(isolation_tiers) BETWEEN 1 AND 3
            AND isolation_tiers <@ ARRAY['CONTAINER', 'GVISOR', 'MICROVM']::varchar(16)[])),
    ADD CONSTRAINT runner_host_isolation_level_ck
        CHECK (isolation_level IS NULL OR isolation_level IN ('MACHINE', 'VM_JAIL', 'CONTAINER')),
    ADD CONSTRAINT runner_host_isolation_hardened_ck
        CHECK (isolation_hardened IS NULL OR isolation_hardened IN ('YES', 'NO', 'NONE')),
    ADD CONSTRAINT runner_host_isolation_ck
        CHECK ((isolation_level IS NULL) = (isolation_hardened IS NULL)
            AND (isolation_level IS NOT NULL OR NOT isolation_stated)
            AND (isolation_level IS NULL OR isolation_tiers IS NOT NULL));

ALTER TABLE personal_runner_host
    ADD COLUMN isolation_tiers    varchar(16)[],
    ADD COLUMN isolation_level    varchar(16),
    ADD COLUMN isolation_hardened varchar(8),
    ADD COLUMN isolation_stated   boolean NOT NULL DEFAULT false,
    ADD CONSTRAINT personal_runner_host_isolation_tiers_ck
        CHECK (isolation_tiers IS NULL OR (cardinality(isolation_tiers) BETWEEN 1 AND 3
            AND isolation_tiers <@ ARRAY['CONTAINER', 'GVISOR', 'MICROVM']::varchar(16)[])),
    ADD CONSTRAINT personal_runner_host_isolation_level_ck
        CHECK (isolation_level IS NULL OR isolation_level IN ('MACHINE', 'VM_JAIL', 'CONTAINER')),
    ADD CONSTRAINT personal_runner_host_isolation_hardened_ck
        CHECK (isolation_hardened IS NULL OR isolation_hardened IN ('YES', 'NO', 'NONE')),
    ADD CONSTRAINT personal_runner_host_isolation_ck
        CHECK ((isolation_level IS NULL) = (isolation_hardened IS NULL)
            AND (isolation_level IS NOT NULL OR NOT isolation_stated)
            AND (isolation_level IS NULL OR isolation_tiers IS NOT NULL));
