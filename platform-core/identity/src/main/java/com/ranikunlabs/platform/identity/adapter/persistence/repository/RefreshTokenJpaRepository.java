package com.ranikunlabs.platform.identity.adapter.persistence.repository;

import com.ranikunlabs.platform.identity.adapter.persistence.entity.RefreshTokenEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/** Internal JPA repository for refresh-token sessions. */
public interface RefreshTokenJpaRepository extends JpaRepository<RefreshTokenEntity, Long> {

    Optional<RefreshTokenEntity> findByRefreshToken(String refreshToken);

    void deleteByAccountId(UUID accountId);
}
