package com.ranikunlabs.platform.identity.application.port.productclient;

import java.util.Objects;

/** Typed failure for the fail-closed product-client registry lookup. */
public final class ProductClientLookupException extends RuntimeException {

    public enum Reason {
        INVALID_PRODUCT_CLIENT_ID,
        UNKNOWN_PRODUCT_CLIENT,
        DISABLED_PRODUCT_CLIENT
    }

    private final Reason reason;

    public ProductClientLookupException(Reason reason) {
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public Reason reason() {
        return reason;
    }
}
