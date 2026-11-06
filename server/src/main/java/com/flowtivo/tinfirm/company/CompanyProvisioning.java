package com.flowtivo.tinfirm.company;

import java.util.UUID;

/**
 * Creates companies on behalf of trusted callers that set them up themselves, such as the tests' support. Callers are
 * responsible for authorisation; no HTTP endpoint creates companies, which the deployment declares
 * ({@link DeclaredCompanies}, ADR-0075). Audited with the current actor.
 */
public interface CompanyProvisioning {

    /**
     * Creates a company with {@code ownerUserId} as its OWNER, without a key.
     * @return the new company id
     */
    UUID createCompany(String name, UUID ownerUserId);
}
