package com.ranikunlabs.platform.identity.adapter.persistence.repository;

import com.ranikunlabs.platform.identity.adapter.persistence.entity.PlatformAccountEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/** Internal JPA repository for platform accounts. */
public interface PlatformAccountJpaRepository extends JpaRepository<PlatformAccountEntity, UUID> {
}
