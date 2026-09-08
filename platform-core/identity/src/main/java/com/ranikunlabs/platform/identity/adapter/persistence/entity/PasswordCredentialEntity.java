package com.ranikunlabs.platform.identity.adapter.persistence.entity;

import com.ranikunlabs.platform.identity.adapter.persistence.IdentityDatabaseSchema;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Persistence model for the one-to-one password credential owned by Identity. */
@Entity
@Table(name = "password_credentials", schema = IdentityDatabaseSchema.NAME)
@EntityListeners(AuditingEntityListener.class)
public class PasswordCredentialEntity {

    @Id
    @Column(name = "account_id", updatable = false, nullable = false)
    private UUID accountId;

    @Column(name = "login_id", length = 255, nullable = false)
    private String loginId;

    @Column(name = "password_hash", length = 255, nullable = false)
    private String passwordHash;

    @CreatedDate
    @Column(name = "created_at", updatable = false, nullable = false)
    private OffsetDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected PasswordCredentialEntity() {
    }

    private PasswordCredentialEntity(UUID accountId, String loginId, String passwordHash) {
        this.accountId = accountId;
        this.loginId = loginId;
        this.passwordHash = passwordHash;
    }

    public static PasswordCredentialEntity create(UUID accountId, String loginId, String passwordHash) {
        return new PasswordCredentialEntity(accountId, loginId, passwordHash);
    }

    public void updatePasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public String getLoginId() {
        return loginId;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }
}
