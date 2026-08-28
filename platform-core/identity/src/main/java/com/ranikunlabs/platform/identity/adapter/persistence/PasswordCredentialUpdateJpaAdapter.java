package com.ranikunlabs.platform.identity.adapter.persistence;

import com.ranikunlabs.platform.identity.adapter.persistence.entity.PasswordCredentialEntity;
import com.ranikunlabs.platform.identity.adapter.persistence.repository.PasswordCredentialJpaRepository;
import com.ranikunlabs.platform.identity.application.port.IdentityAccountNotFoundException;
import com.ranikunlabs.platform.identity.application.port.PasswordCredentialUpdatePort;
import com.ranikunlabs.platform.identity.application.port.UpdatedPasswordCredential;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** Persistence-backed implementation of the account password update port. */
@Component
@ConditionalOnProperty(name = "platform.identity.auth.enabled", havingValue = "true")
@Transactional
public class PasswordCredentialUpdateJpaAdapter implements PasswordCredentialUpdatePort {

    private final PasswordCredentialJpaRepository passwordCredentialRepository;
    private final PasswordEncoder passwordEncoder;

    public PasswordCredentialUpdateJpaAdapter(
            PasswordCredentialJpaRepository passwordCredentialRepository,
            PasswordEncoder passwordEncoder
    ) {
        this.passwordCredentialRepository = passwordCredentialRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public UpdatedPasswordCredential updatePassword(UUID accountId, String rawPassword)
            throws IdentityAccountNotFoundException {
        PasswordCredentialEntity credential = passwordCredentialRepository.findById(accountId)
                .orElseThrow(IdentityAccountNotFoundException::new);

        String encodedPassword = passwordEncoder.encode(rawPassword);
        credential.updatePasswordHash(encodedPassword);
        passwordCredentialRepository.save(credential);

        return new UpdatedPasswordCredential(encodedPassword);
    }
}
