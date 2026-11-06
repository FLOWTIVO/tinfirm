package com.flowtivo.tinfirm.vault.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

/**
 * The secrets' metadata. The Secrets API and the company's own writers see the company's secrets only
 * ({@code ownerUserId} null, not {@code internal}); a secret a person owns (ADR-0049 §5) is found by its owner, an
 * internal one (the package 2 contract, §3.2) by the module that keeps it, and both by the reveal.
 */
interface SecretRepository extends JpaRepository<SecretEntity, UUID> {

    Optional<SecretEntity> findByIdAndCompanyId(UUID id, UUID companyId);

    /** Any secret of the company, owned or not: for the reveal, which then checks the owner. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from SecretEntity s where s.id = :id and s.companyId = :companyId")
    Optional<SecretEntity> lockByIdAndCompanyId(UUID id, UUID companyId);

    /** A secret of the company that no person owns and Tinfirm does not keep for itself. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from SecretEntity s where s.id = :id and s.companyId = :companyId and s.ownerUserId is null "
            + "and s.internal = false")
    Optional<SecretEntity> lockCompanySecret(UUID id, UUID companyId);

    /** An internal secret of the company (the package 2 contract, §3.2). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from SecretEntity s where s.id = :id and s.companyId = :companyId and s.internal = true")
    Optional<SecretEntity> lockInternal(UUID id, UUID companyId);

    /** A secret of the company that this person owns. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from SecretEntity s where s.id = :id and s.companyId = :companyId "
            + "and s.ownerUserId = :ownerUserId")
    Optional<SecretEntity> lockOwned(UUID id, UUID companyId, UUID ownerUserId);

    /** Every secret of the company that this person owns, locked. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from SecretEntity s where s.companyId = :companyId and s.ownerUserId = :ownerUserId "
            + "order by s.id")
    List<SecretEntity> lockAllOwned(UUID companyId, UUID ownerUserId);

    /** Whether the name is taken, by a secret of the company or one a person owns: names are unique in the company. */
    boolean existsByCompanyIdAndName(UUID companyId, String name);

    List<SecretEntity> findByCompanyIdAndOwnerUserIdIsNullAndInternalFalseOrderByNameAsc(UUID companyId,
            Limit limit);

    List<SecretEntity> findByCompanyIdAndOwnerUserIdIsNullAndInternalFalseAndNameGreaterThanOrderByNameAsc(
            UUID companyId, String name, Limit limit);
}
