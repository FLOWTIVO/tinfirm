package com.flowtivo.tinfirm.identity.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface UserRepository extends JpaRepository<UserEntity, UUID> {

    Optional<UserEntity> findByEmail(String email);

    boolean existsByEmail(String email);

    Optional<UserEntity> findByOidcIssuerAndOidcSubject(String oidcIssuer, String oidcSubject);

    /** The user whose last Entra v2.0 login named this tenant and object (the package 5 contract, §3.8). */
    Optional<UserEntity> findByOidcTenantIdAndOidcObjectId(String oidcTenantId, String oidcObjectId);

    List<UserEntity> findAllByOrderByEmailAsc(Limit limit);

    List<UserEntity> findByEmailGreaterThanOrderByEmailAsc(String email, Limit limit);

    /** Locks all active instance admins (in id order, to avoid deadlocks) and returns their ids. */
    @Query(value = "SELECT id FROM app_user WHERE instance_admin AND active ORDER BY id FOR UPDATE", nativeQuery = true)
    List<UUID> lockActiveInstanceAdmins();

    /** Locks the user's row (serializes per-user limits such as the cap of personal access tokens). */
    @Query(value = "SELECT id FROM app_user WHERE id = :id FOR UPDATE", nativeQuery = true)
    Optional<UUID> lockById(@Param("id") UUID id);
}
