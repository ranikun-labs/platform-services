package com.ranikunlabs.platform.identity.adapter.persistence;

import com.ranikunlabs.platform.identity.adapter.persistence.entity.PasswordCredentialEntity;
import com.ranikunlabs.platform.identity.adapter.persistence.entity.PlatformAccountEntity;
import com.ranikunlabs.platform.identity.adapter.persistence.repository.PasswordCredentialJpaRepository;
import com.ranikunlabs.platform.identity.adapter.persistence.repository.PlatformAccountJpaRepository;
import com.ranikunlabs.platform.identity.application.port.DuplicateLoginIdException;
import com.ranikunlabs.platform.identity.application.port.IdentityAccount;
import com.ranikunlabs.platform.identity.application.port.IdentityAccountRegistrationPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Persistence-backed implementation of the password-account registration port. */
@Component
@ConditionalOnProperty(name = "platform.identity.auth.enabled", havingValue = "true")
@Transactional
public class IdentityAccountRegistrationJpaAdapter implements IdentityAccountRegistrationPort {

    private final PlatformAccountJpaRepository platformAccountRepository;
    private final PasswordCredentialJpaRepository passwordCredentialRepository;
    private final PasswordEncoder passwordEncoder;

    public IdentityAccountRegistrationJpaAdapter(
            PlatformAccountJpaRepository platformAccountRepository,
            PasswordCredentialJpaRepository passwordCredentialRepository,
            PasswordEncoder passwordEncoder
    ) {
        this.platformAccountRepository = platformAccountRepository;
        this.passwordCredentialRepository = passwordCredentialRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public IdentityAccount registerPasswordAccount(String loginId, String email, String rawPassword)
            throws DuplicateLoginIdException {
        if (passwordCredentialRepository.existsByLoginId(loginId)) {
            throw new DuplicateLoginIdException();
        }

        String encodedPasswordHash = passwordEncoder.encode(rawPassword);
        PlatformAccountEntity account = platformAccountRepository.save(
                PlatformAccountEntity.create(email));
        passwordCredentialRepository.save(
                PasswordCredentialEntity.create(account.getId(), loginId, encodedPasswordHash));

        return new IdentityAccount(account.getId(), loginId, encodedPasswordHash);
    }
}
