package com.ranikunlabs.platform.identity.adapter.persistence;

import com.ranikunlabs.platform.identity.adapter.persistence.repository.ExternalIdentityJpaRepository;
import com.ranikunlabs.platform.identity.application.port.oauth.ExternalIdentityLookupPort;
import com.ranikunlabs.platform.identity.application.port.oauth.LinkedAccountStatus;
import com.ranikunlabs.platform.identity.application.port.oauth.LinkedAccountView;
import com.ranikunlabs.platform.identity.core.AccountStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/** Maps the internal external-identity JPA projection to the public lookup port. */
@Component
@Transactional(readOnly = true)
public class ExternalIdentityLookupJpaAdapter implements ExternalIdentityLookupPort {

    private final ExternalIdentityJpaRepository repository;

    public ExternalIdentityLookupJpaAdapter(ExternalIdentityJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<LinkedAccountView> findByProviderSubject(String provider, String providerSubject) {
        return repository.findLinkedAccountByProviderAndProviderSubject(provider, providerSubject)
                .map(projection -> new LinkedAccountView(
                        projection.getAccountId(),
                        toLinkedAccountStatus(projection.getStatus())
                ));
    }

    private LinkedAccountStatus toLinkedAccountStatus(AccountStatus status) {
        return status == AccountStatus.ACTIVE ? LinkedAccountStatus.ACTIVE : LinkedAccountStatus.INACTIVE;
    }
}
