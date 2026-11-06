package com.flowtivo.tinfirm.vault;

import java.util.Optional;
import java.util.UUID;

/**
 * Port: what keeps a secret of the company that another module wrote through {@link SecretWriter} (the package 1
 * contract, §3.3 and §3.6; the package 2 contract, §3.1): a service identity's account keeps its token or OAuth grant,
 * a system's app its client secret, the model settings the admin key of a company's own LiteLLM. The Secrets API
 * neither replaces nor deletes a secret one of them keeps ({@code 409 SECRET_MANAGED}; a new value under a managed
 * name is refused first, with {@code 400}, kept or not): it changes or goes on the keeper's own page, which audits and
 * announces what went with it (a new admin key turns the model relay off until the gateway passes its test again). Runs
 * in the caller's transaction and tenant context.
 */
public interface SecretKeeper {

    /**
     * What keeps the secret and where it is changed or removed, for the problem's detail (names of Tinfirm's objects,
     * never a value); empty when nothing of this module keeps it.
     */
    Optional<String> keeperOf(UUID companyId, UUID secretId);
}
