package com.ranikunlabs.platform.identity.adapter.security;

import com.ranikunlabs.platform.identity.adapter.persistence.entity.PasswordCredentialEntity;
import com.ranikunlabs.platform.identity.adapter.persistence.repository.PasswordCredentialJpaRepository;
import com.ranikunlabs.platform.identity.application.port.AccountStatusPort;
import com.ranikunlabs.platform.identity.application.port.AuthenticatedIdentity;
import com.ranikunlabs.platform.identity.application.port.CredentialPort;
import com.ranikunlabs.platform.identity.application.port.InvalidCredentialsException;
import com.ranikunlabs.platform.identity.core.AccountStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/** JPA/password-encoder implementation of the Identity credential port. */
@Component
@Transactional(readOnly = true)
public class IdentityCredentialAdapter implements CredentialPort {

    private static final Logger log = LoggerFactory.getLogger(IdentityCredentialAdapter.class);

    private final PasswordCredentialJpaRepository passwordCredentialRepository;
    private final PasswordEncoder passwordEncoder;
    private final AccountStatusPort accountStatusPort;

    public IdentityCredentialAdapter(
            PasswordCredentialJpaRepository passwordCredentialRepository,
            PasswordEncoder passwordEncoder,
            AccountStatusPort accountStatusPort
    ) {
        this.passwordCredentialRepository = passwordCredentialRepository;
        this.passwordEncoder = passwordEncoder;
        this.accountStatusPort = accountStatusPort;
    }

    @Override
    public AuthenticatedIdentity authenticate(String loginId, String password)
            throws InvalidCredentialsException {
        Optional<PasswordCredentialEntity> credential = passwordCredentialRepository.findByLoginId(loginId);
        if (credential.isEmpty() || !passwordEncoder.matches(password, credential.get().getPasswordHash())) {
            // loginId is deliberately not logged: it is PII and the failure reason is not user-distinguishable.
            log.warn("Identity login failed");
            throw new InvalidCredentialsException();
        }

        PasswordCredentialEntity storedCredential = credential.get();
        if (accountStatusPort.findStatus(storedCredential.getAccountId())
                .filter(AccountStatus.ACTIVE::equals)
                .isEmpty()) {
            throw new InvalidCredentialsException();
        }

        return new AuthenticatedIdentity(storedCredential.getAccountId(), storedCredential.getLoginId());
    }
}
