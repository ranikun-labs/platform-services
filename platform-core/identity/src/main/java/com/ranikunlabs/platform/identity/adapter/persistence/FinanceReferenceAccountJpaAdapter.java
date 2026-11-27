package com.ranikunlabs.platform.identity.adapter.persistence;

import com.ranikunlabs.platform.identity.adapter.persistence.entity.ExternalIdentityEntity;
import com.ranikunlabs.platform.identity.adapter.persistence.entity.PasswordCredentialEntity;
import com.ranikunlabs.platform.identity.adapter.persistence.entity.PlatformAccountEntity;
import com.ranikunlabs.platform.identity.adapter.persistence.repository.ExternalIdentityJpaRepository;
import com.ranikunlabs.platform.identity.adapter.persistence.repository.PasswordCredentialJpaRepository;
import com.ranikunlabs.platform.identity.adapter.persistence.repository.PlatformAccountJpaRepository;
import com.ranikunlabs.platform.identity.application.port.DuplicateLoginIdException;
import com.ranikunlabs.platform.identity.application.port.FinanceReferenceAccountRegistrationPort;
import com.ranikunlabs.platform.identity.application.port.FinanceReferenceEnrollmentPort;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Shared-store adapter for the Finance reference. The account, password credential, and Finance
 * enrollment are written in one transaction. The enrollment is the existing provider-neutral
 * external identity link with provider {@link #ENROLLMENT_PROVIDER} and the account id as subject,
 * so the Identity schema and its canonical structure pins stay unchanged.
 */
@Transactional
public class FinanceReferenceAccountJpaAdapter
        implements FinanceReferenceAccountRegistrationPort, FinanceReferenceEnrollmentPort {

    static final String ENROLLMENT_PROVIDER = "finance-reference";

    private final PlatformAccountJpaRepository platformAccountRepository;
    private final PasswordCredentialJpaRepository passwordCredentialRepository;
    private final ExternalIdentityJpaRepository externalIdentityRepository;
    private final PasswordEncoder passwordEncoder;

    public FinanceReferenceAccountJpaAdapter(
            PlatformAccountJpaRepository platformAccountRepository,
            PasswordCredentialJpaRepository passwordCredentialRepository,
            ExternalIdentityJpaRepository externalIdentityRepository,
            PasswordEncoder passwordEncoder
    ) {
        this.platformAccountRepository = platformAccountRepository;
        this.passwordCredentialRepository = passwordCredentialRepository;
        this.externalIdentityRepository = externalIdentityRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public UUID registerFinanceAccount(String loginId, String rawPassword) throws DuplicateLoginIdException {
        // Perform the expensive password hash for both new and existing IDs to reduce timing leakage.
        String encodedPasswordHash = passwordEncoder.encode(rawPassword);
        if (passwordCredentialRepository.existsByLoginId(loginId)) {
            throw new DuplicateLoginIdException();
        }
        try {
            PlatformAccountEntity account = platformAccountRepository.saveAndFlush(
                    PlatformAccountEntity.create(loginId));
            passwordCredentialRepository.saveAndFlush(
                    PasswordCredentialEntity.create(account.getId(), loginId, encodedPasswordHash));
            externalIdentityRepository.saveAndFlush(ExternalIdentityEntity.create(
                    account.getId(), ENROLLMENT_PROVIDER, account.getId().toString(), loginId));
            return account.getId();
        } catch (DataIntegrityViolationException exception) {
            if (IdentityConstraintViolationTranslator.isPasswordCredentialLoginIdViolation(exception)) {
                throw new DuplicateLoginIdException();
            }
            throw exception;
        }
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isFinanceEnrolled(UUID accountId) {
        return externalIdentityRepository
                .findByProviderAndProviderSubject(ENROLLMENT_PROVIDER, accountId.toString())
                .map(link -> accountId.equals(link.getAccountId()))
                .orElse(false);
    }
}
