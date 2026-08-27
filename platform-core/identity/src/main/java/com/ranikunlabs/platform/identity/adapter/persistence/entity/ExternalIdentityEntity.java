package com.ranikunlabs.platform.identity.adapter.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Persistence model for a provider-neutral external identity link. */
@Entity
@Table(name = "external_identities")
@EntityListeners(AuditingEntityListener.class)
public class ExternalIdentityEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", updatable = false, nullable = false)
    private Long id;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "provider", length = 50, nullable = false)
    private String provider;

    @Column(name = "provider_subject", length = 255, nullable = false)
    private String providerSubject;

    @Column(name = "email_snapshot", length = 255)
    private String emailSnapshot;

    @CreatedDate
    @Column(name = "created_at", updatable = false, nullable = false)
    private OffsetDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected ExternalIdentityEntity() {
    }

    private ExternalIdentityEntity(UUID accountId, String provider, String providerSubject, String emailSnapshot) {
        this.accountId = accountId;
        this.provider = provider;
        this.providerSubject = providerSubject;
        this.emailSnapshot = emailSnapshot;
    }

    public static ExternalIdentityEntity create(
            UUID accountId, String provider, String providerSubject, String emailSnapshot
    ) {
        return new ExternalIdentityEntity(accountId, provider, providerSubject, emailSnapshot);
    }

    public Long getId() {
        return id;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public String getProvider() {
        return provider;
    }

    public String getProviderSubject() {
        return providerSubject;
    }

    public String getEmailSnapshot() {
        return emailSnapshot;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }
}
