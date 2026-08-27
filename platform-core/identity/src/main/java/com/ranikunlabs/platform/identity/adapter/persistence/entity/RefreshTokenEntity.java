package com.ranikunlabs.platform.identity.adapter.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Persistence model for Identity-owned refresh-token sessions. */
@Entity
@Table(name = "refresh_token")
@EntityListeners(AuditingEntityListener.class)
public class RefreshTokenEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "refresh_token_id", updatable = false, nullable = false)
    private Long id;

    @Column(name = "refresh_token", length = 500, nullable = false, unique = true)
    private String refreshToken;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    /** Legacy login-id mirror retained for compatibility; it is not a lookup key. */
    @Column(name = "user_id", length = 255)
    private String userId;

    @Column(name = "token_expires_at", nullable = false)
    private OffsetDateTime tokenExpiresAt;

    @CreatedDate
    @Column(name = "created_at", updatable = false, nullable = false)
    private OffsetDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @CreatedBy
    @Column(name = "created_by", updatable = false, length = 255)
    private String createdBy;

    @LastModifiedBy
    @Column(name = "updated_by", length = 255)
    private String updatedBy;

    protected RefreshTokenEntity() {
    }

    public RefreshTokenEntity(String refreshToken, UUID accountId, OffsetDateTime tokenExpiresAt) {
        this.refreshToken = refreshToken;
        this.accountId = accountId;
        this.tokenExpiresAt = tokenExpiresAt;
    }

    public static RefreshTokenEntity create(
            String refreshToken, UUID accountId, OffsetDateTime tokenExpiresAt
    ) {
        return new RefreshTokenEntity(refreshToken, accountId, tokenExpiresAt);
    }

    public boolean isExpired(OffsetDateTime now) {
        return now.isAfter(this.tokenExpiresAt);
    }

    public void updateToken(String refreshToken, OffsetDateTime tokenExpiresAt) {
        this.refreshToken = refreshToken;
        this.tokenExpiresAt = tokenExpiresAt;
    }

    public Long getId() {
        return id;
    }

    public String getRefreshToken() {
        return refreshToken;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public String getUserId() {
        return userId;
    }

    public OffsetDateTime getTokenExpiresAt() {
        return tokenExpiresAt;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }
}
