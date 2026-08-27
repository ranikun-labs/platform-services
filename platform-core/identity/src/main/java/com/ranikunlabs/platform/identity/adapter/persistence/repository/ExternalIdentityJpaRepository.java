package com.ranikunlabs.platform.identity.adapter.persistence.repository;

import com.ranikunlabs.platform.identity.adapter.persistence.entity.ExternalIdentityEntity;
import com.ranikunlabs.platform.identity.core.AccountStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

/** Internal JPA repository for external identity links. */
public interface ExternalIdentityJpaRepository extends JpaRepository<ExternalIdentityEntity, Long> {

    Optional<ExternalIdentityEntity> findByProviderAndProviderSubject(String provider, String providerSubject);

    @Query("""
            select e.accountId as accountId, a.status as status
            from ExternalIdentityEntity e
            join PlatformAccountEntity a on a.id = e.accountId
            where e.provider = :provider and e.providerSubject = :providerSubject
            """)
    Optional<LinkedAccountProjection> findLinkedAccountByProviderAndProviderSubject(
            @Param("provider") String provider,
            @Param("providerSubject") String providerSubject
    );

    interface LinkedAccountProjection {
        UUID getAccountId();

        AccountStatus getStatus();
    }
}
