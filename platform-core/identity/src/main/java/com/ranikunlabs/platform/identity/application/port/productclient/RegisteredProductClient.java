package com.ranikunlabs.platform.identity.application.port.productclient;

import com.ranikunlabs.platform.identity.core.Product;
import com.ranikunlabs.platform.identity.core.ProductClientChannel;

/** Active product-client contract used by OAuth core without exposing persistence entities. */
public record RegisteredProductClient(
        String clientId,
        Product product,
        ProductClientChannel channel
) {
}
