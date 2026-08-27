package com.ranikunlabs.platform.identity.adapter.persistence.repository;

import com.ranikunlabs.platform.identity.adapter.persistence.entity.PasswordCredentialEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/** Internal JPA repository for password credentials. */
public interface PasswordCredentialJpaRepository extends JpaRepository<PasswordCredentialEntity, UUID> {

    Optional<PasswordCredentialEntity> findByLoginId(String loginId);

    boolean existsByLoginId(String loginId);
}
