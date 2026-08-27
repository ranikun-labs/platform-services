package com.ranikunlabs.platform.identity.adapter.persistence;

import com.ranikunlabs.platform.identity.adapter.persistence.entity.RefreshTokenEntity;
import com.ranikunlabs.platform.identity.adapter.persistence.repository.RefreshTokenJpaRepository;
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

    public TokenSessionJpaAdapter(RefreshTokenJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public void replaceForAccount(UUID accountId, String newToken, OffsetDateTime expiresAt) {
        repository.deleteByAccountId(accountId);
        repository.save(RefreshTokenEntity.create(newToken, accountId, expiresAt));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<RefreshSession> findByToken(String rawToken) {
        return repository.findByRefreshToken(rawToken).map(this::toSession);
    }

    @Override
    public void rotate(RefreshSession session, String newToken, OffsetDateTime newExpiresAt) {
        RefreshTokenEntity entity = repository.findByRefreshToken(session.tokenValue())
                .orElseThrow(() -> new IllegalStateException(
                        "rotate target refresh token was not found: " + session.tokenValue()));
        entity.updateToken(newToken, newExpiresAt);
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
