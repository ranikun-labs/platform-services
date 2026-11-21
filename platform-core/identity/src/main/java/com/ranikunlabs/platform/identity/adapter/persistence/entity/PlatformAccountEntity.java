package com.ranikunlabs.platform.identity.adapter.persistence.entity;

import com.ranikunlabs.platform.identity.core.AccountStatus;
import com.ranikunlabs.platform.identity.adapter.persistence.IdentityDatabaseSchema;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Persistence model for the Identity-owned platform account table. */
@Entity
@Table(name = "platform_accounts", schema = IdentityDatabaseSchema.NAME)
@EntityListeners(AuditingEntityListener.class)
public class PlatformAccountEntity {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private AccountStatus status;

    @Column(name = "primary_email", length = 255)
    private String primaryEmail;

    @CreatedDate
    @Column(name = "created_at", updatable = false, nullable = false)
    private OffsetDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected PlatformAccountEntity() {
    }

    private PlatformAccountEntity(String primaryEmail, AccountStatus status) {
        this.id = UUID.randomUUID();
        this.status = status;
        this.primaryEmail = primaryEmail;
    }

    public static PlatformAccountEntity create(String primaryEmail) {
        return new PlatformAccountEntity(primaryEmail, AccountStatus.ACTIVE);
    }

    public static PlatformAccountEntity createPending(String primaryEmail) {
        return new PlatformAccountEntity(primaryEmail, AccountStatus.INACTIVE);
    }

    public void activatePendingRegistration() {
        if (status != AccountStatus.INACTIVE) throw new IllegalStateException("Registration account is not pending");
        status = AccountStatus.ACTIVE;
    }

    public UUID getId() {
        return id;
    }

    public AccountStatus getStatus() {
        return status;
    }

    public String getPrimaryEmail() {
        return primaryEmail;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }
}
