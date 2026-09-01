package com.ranikunlabs.platform.identity.adapter.persistence.repository;

import com.ranikunlabs.platform.identity.adapter.persistence.entity.RefreshTokenEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

/** Internal JPA repository for refresh-token sessions. */
public interface RefreshTokenJpaRepository extends JpaRepository<RefreshTokenEntity, Long> {

    Optional<RefreshTokenEntity> findByRefreshToken(String refreshToken);

    Optional<RefreshTokenEntity> findByAccountId(UUID accountId);

    /** Acquires the database row lock used to make refresh consumption one-time. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select session from RefreshTokenEntity session where session.refreshToken = :refreshToken")
    Optional<RefreshTokenEntity> findByRefreshTokenForUpdate(@Param("refreshToken") String refreshToken);

    void deleteByAccountId(UUID accountId);
}
