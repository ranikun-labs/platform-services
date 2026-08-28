package com.ranikunlabs.platform.identity.adapter.persistence.repository;

import com.ranikunlabs.platform.identity.adapter.persistence.entity.ProductClientEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** Internal JPA repository for the product client registry. */
public interface ProductClientJpaRepository extends JpaRepository<ProductClientEntity, Long> {

    Optional<ProductClientEntity> findByClientId(String clientId);
}
