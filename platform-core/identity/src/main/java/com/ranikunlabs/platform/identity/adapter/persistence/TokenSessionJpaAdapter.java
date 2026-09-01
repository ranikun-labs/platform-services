package com.ranikunlabs.platform.identity.adapter.persistence;

import com.ranikunlabs.platform.identity.adapter.persistence.entity.RefreshTokenEntity;
import com.ranikunlabs.platform.identity.adapter.persistence.repository.PlatformAccountJpaRepository;
import com.ranikunlabs.platform.identity.adapter.persistence.repository.RefreshTokenJpaRepository;
import com.ranikunlabs.platform.identity.application.port.IdentityAccountNotFoundException;
import com.ranikunlabs.platform.identity.application.port.RefreshSession;
import com.ranikunlabs.platform.identity.application.port.TokenSessionPort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/** Maps refresh-session storage to the framework-neutral token-session port. */
@Component
@Transactional
public class TokenSessionJpaAdapter implements TokenSessionPort {

    private final RefreshTokenJpaRepository repository;
    private final PlatformAccountJpaRepository accountRepository;

    public TokenSessionJpaAdapter(
            RefreshTokenJpaRepository repository,
            PlatformAccountJpaRepository accountRepository
    ) {
        this.repository = repository;
        this.accountRepository = accountRepository;
    }

    @Override
    public void replaceForAccount(UUID accountId, String newToken, OffsetDateTime expiresAt) {
        accountRepository.findByIdForSessionReplacement(accountId)
                .orElseThrow(IdentityAccountNotFoundException::new);
        repository.findByAccountId(accountId)
                .ifPresentOrElse(
                        session -> session.updateToken(newToken, expiresAt),
                        () -> repository.save(RefreshTokenEntity.create(newToken, accountId, expiresAt))
                );
        repository.flush();
    }

    @Override
    public Optional<RefreshSession> findByToken(String rawToken) {
        return repository.findByRefreshTokenForUpdate(rawToken).map(this::toSession);
    }

    @Override
    public void rotate(RefreshSession session, String newToken, OffsetDateTime newExpiresAt) {
        RefreshTokenEntity entity = repository.findByRefreshTokenForUpdate(session.tokenValue())
                .orElseThrow(() -> new IllegalStateException(
                        "Refresh session rotation target is no longer available"));
        entity.updateToken(newToken, newExpiresAt);
        repository.flush();
    }

    @Override
    public void deleteForAccount(UUID accountId) {
        repository.deleteByAccountId(accountId);
    }

    @Override
    public void delete(RefreshSession session) {
        repository.findByRefreshToken(session.tokenValue()).ifPresent(repository::delete);
    }

    private RefreshSession toSession(RefreshTokenEntity entity) {
        return new RefreshSession(entity.getRefreshToken(), entity.getAccountId(), entity.getTokenExpiresAt());
    }
}
