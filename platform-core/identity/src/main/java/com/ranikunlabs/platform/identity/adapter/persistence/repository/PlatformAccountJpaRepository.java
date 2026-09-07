package com.ranikunlabs.platform.identity.adapter.persistence.repository;

import com.ranikunlabs.platform.identity.adapter.persistence.entity.PlatformAccountEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

/** Internal JPA repository for platform accounts. */
public interface PlatformAccountJpaRepository extends JpaRepository<PlatformAccountEntity, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select account from PlatformAccountEntity account where account.id = :accountId")
    Optional<PlatformAccountEntity> findByIdForSessionReplacement(@Param("accountId") UUID accountId);
}
