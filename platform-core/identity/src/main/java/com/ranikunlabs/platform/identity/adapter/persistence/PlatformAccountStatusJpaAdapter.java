package com.ranikunlabs.platform.identity.adapter.persistence;

import com.ranikunlabs.platform.identity.adapter.persistence.repository.PlatformAccountJpaRepository;
import com.ranikunlabs.platform.identity.application.port.AccountStatusPort;
import com.ranikunlabs.platform.identity.core.AccountStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/** Maps Identity-owned account status persistence to the authentication port. */
@Component
@Transactional(readOnly = true)
public class PlatformAccountStatusJpaAdapter implements AccountStatusPort {

    private final PlatformAccountJpaRepository repository;

    public PlatformAccountStatusJpaAdapter(PlatformAccountJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<AccountStatus> findStatus(UUID accountId) {
        return repository.findById(accountId).map(account -> account.getStatus());
    }
}
