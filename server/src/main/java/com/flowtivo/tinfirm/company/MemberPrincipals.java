package com.flowtivo.tinfirm.company;

import java.util.UUID;

/**
 * Port: a member's principal lives with the membership (the package 1 contract, §3.1), implemented by the principal
 * module, which keeps principals; the company module does not depend on it. Both calls run in the transaction that
 * adds or ends the membership, in the company's tenant context, as the actor of that change.
 */
public interface MemberPrincipals {

    /**
     * The membership was just created (a member added, a company's declared owner, a user an OIDC provider
     * provisioned into its company): the member gets a principal of their own, a new one also when they were a member
     * before.
     */
    void joined(UUID companyId, UUID userId);

    /**
     * The membership is about to end: the member's principal becomes {@code LEFT}, before the membership row goes
     * (I8).
     */
    void leaving(UUID companyId, UUID userId);
}
