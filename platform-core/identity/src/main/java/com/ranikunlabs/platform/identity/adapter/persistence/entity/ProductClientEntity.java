package com.ranikunlabs.platform.identity.adapter.persistence.entity;

import com.ranikunlabs.platform.identity.core.Product;
import com.ranikunlabs.platform.identity.core.ProductClientChannel;
import com.ranikunlabs.platform.identity.core.ProductClientIdPolicy;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.OffsetDateTime;
import java.util.Objects;

/** Persistence model for the Identity-owned product client registry. */
@Entity
@Table(name = "product_clients")
@EntityListeners(AuditingEntityListener.class)
public class ProductClientEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", updatable = false, nullable = false)
    private Long id;

    @Column(name = "client_id", length = 100, nullable = false, unique = true)
    private String clientId;

    @Enumerated(EnumType.STRING)
    @Column(name = "product", length = 50, nullable = false)
    private Product product;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", length = 50, nullable = false)
    private ProductClientChannel channel;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @CreatedDate
    @Column(name = "created_at", updatable = false, nullable = false)
    private OffsetDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected ProductClientEntity() {
    }

    private ProductClientEntity(
            String clientId, Product product, ProductClientChannel channel, boolean enabled
    ) {
        this.clientId = ProductClientIdPolicy.requireValid(clientId);
        this.product = Objects.requireNonNull(product, "product must not be null");
        this.channel = Objects.requireNonNull(channel, "channel must not be null");
        this.enabled = enabled;
    }

    public static ProductClientEntity create(
            String clientId, Product product, ProductClientChannel channel, boolean enabled
    ) {
        return new ProductClientEntity(clientId, product, channel, enabled);
    }

    public Long getId() {
        return id;
    }

    public String getClientId() {
        return clientId;
    }

    public Product getProduct() {
        return product;
    }

    public ProductClientChannel getChannel() {
        return channel;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }
}
