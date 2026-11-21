package com.ranikunlabs.platform.identity.adapter.persistence.repository;

import com.ranikunlabs.platform.identity.adapter.persistence.entity.PasswordCredentialEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.util.Optional;
import java.util.UUID;

/** Internal JPA repository for password credentials. */
public interface PasswordCredentialJpaRepository extends JpaRepository<PasswordCredentialEntity, UUID> {

    Optional<PasswordCredentialEntity> findByLoginId(String loginId);

    boolean existsByLoginId(String loginId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select credential from PasswordCredentialEntity credential where credential.accountId = :accountId")
    Optional<PasswordCredentialEntity> findByIdForRegistration(@Param("accountId") UUID accountId);
}
