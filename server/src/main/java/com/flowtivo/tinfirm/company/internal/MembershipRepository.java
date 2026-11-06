package com.flowtivo.tinfirm.company.internal;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.flowtivo.tinfirm.shared.access.CompanyRole;

interface MembershipRepository extends JpaRepository<MembershipEntity, MembershipEntity.Key> {

    Optional<MembershipEntity> findByCompanyIdAndUserId(UUID companyId, UUID userId);

    boolean existsByCompanyIdAndUserId(UUID companyId, UUID userId);

    /** The member the company's SCIM gave this {@code externalId} (ADR-0087, decision 4). */
    Optional<MembershipEntity> findByCompanyIdAndScimExternalId(UUID companyId, String scimExternalId);

    @Query("select count(m) from MembershipEntity m where m.companyId = :companyId and m.role = :role")
    long countByCompanyIdAndRole(UUID companyId, CompanyRole role);
}
