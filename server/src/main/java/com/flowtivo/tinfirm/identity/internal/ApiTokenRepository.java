package com.flowtivo.tinfirm.identity.internal;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface ApiTokenRepository extends JpaRepository<ApiTokenEntity, UUID> {

    Optional<ApiTokenEntity> findByTokenHash(String tokenHash);

    Optional<ApiTokenEntity> findByIdAndUserId(UUID id, UUID userId);

    /** The user's tokens that are not revoked, for a revocation of them all. */
    List<ApiTokenEntity> findByUserIdAndRevokedAtIsNull(UUID userId);

    /** The user's tokens that are not revoked, newest first. */
    List<ApiTokenEntity> findByUserIdAndRevokedAtIsNullOrderByCreatedAtDescIdDesc(UUID userId, Limit limit);

    /** The user's tokens that are neither revoked nor expired at {@code now}. */
    @Query("""
            SELECT count(t) FROM ApiTokenEntity t
             WHERE t.userId = :userId AND t.revokedAt IS NULL AND (t.expiresAt IS NULL OR t.expiresAt > :now)
            """)
    long countActive(@Param("userId") UUID userId, @Param("now") Instant now);

    /**
     * Records a use of the token, unless a use at most a minute ago is already recorded ({@code staleBefore} is
     * {@code now} minus a minute). Concurrent requests write at most once.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = false)
    @Query(value = """
            UPDATE api_token SET last_used_at = :now
             WHERE id = :id AND (last_used_at IS NULL OR last_used_at < :staleBefore)
            """, nativeQuery = true)
    int touch(@Param("id") UUID id, @Param("now") Instant now, @Param("staleBefore") Instant staleBefore);
}
