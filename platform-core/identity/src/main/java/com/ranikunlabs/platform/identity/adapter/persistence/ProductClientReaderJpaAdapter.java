package com.ranikunlabs.platform.identity.adapter.persistence;

import com.ranikunlabs.platform.identity.adapter.persistence.entity.ProductClientEntity;
import com.ranikunlabs.platform.identity.adapter.persistence.repository.ProductClientJpaRepository;
import com.ranikunlabs.platform.identity.application.port.productclient.ProductClientLookupException;
import com.ranikunlabs.platform.identity.application.port.productclient.ProductClientReader;
import com.ranikunlabs.platform.identity.application.port.productclient.RegisteredProductClient;
import com.ranikunlabs.platform.identity.core.ProductClientIdPolicy;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Maps the Identity-owned product-client registry to its framework-neutral reader port. */
@Component
@Transactional(readOnly = true)
public class ProductClientReaderJpaAdapter implements ProductClientReader {

    private final ProductClientJpaRepository repository;

    public ProductClientReaderJpaAdapter(ProductClientJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public RegisteredProductClient requireEnabled(String clientId) throws ProductClientLookupException {
        if (!ProductClientIdPolicy.isValid(clientId)) {
            throw new ProductClientLookupException(
                    ProductClientLookupException.Reason.INVALID_PRODUCT_CLIENT_ID);
        }

        ProductClientEntity client = repository.findByClientId(clientId)
                .orElseThrow(() -> new ProductClientLookupException(
                        ProductClientLookupException.Reason.UNKNOWN_PRODUCT_CLIENT));
        if (!client.isEnabled()) {
            throw new ProductClientLookupException(
                    ProductClientLookupException.Reason.DISABLED_PRODUCT_CLIENT);
        }

        return new RegisteredProductClient(client.getClientId(), client.getProduct(), client.getChannel());
    }
}
