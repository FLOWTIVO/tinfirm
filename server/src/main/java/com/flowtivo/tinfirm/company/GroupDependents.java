package com.flowtivo.tinfirm.company;

import java.util.UUID;

/**
 * Port: what other modules give a group (who may use a service identity, the grants of accounts), implemented by
 * each of them; the company module does not depend on them. Every implementation is called in the transaction that
 * deletes the group, in the company's tenant context, as the actor of the deletion, before the group goes, so that
 * each module removes, audits and announces its own rows instead of a foreign key's cascade doing it silently.
 */
public interface GroupDependents {

    void groupDeleting(UUID companyId, UUID groupId);
}
