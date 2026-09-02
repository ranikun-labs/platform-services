package com.ranikunlabs.platform.identity.application.port;

import java.util.Objects;

/**
 * Typed failure contract for reading the Product-owned claim snapshot.
 *
 * <p>Reading claims became a service-to-service call during the Identity extraction, so the port
 * must distinguish "this account has no Product claims" from "the Product claims dependency could
 * not answer". Collapsing the second case into an absent claim snapshot would let an infrastructure
 * outage read as a legitimate account state.
 */
public final class ProductClaimsException extends RuntimeException {

    public enum Reason {
        /** The Product has no claim snapshot for this account. */
        PRODUCT_CLAIMS_NOT_FOUND,
        /** The Product rejected Identity's service credential; a configuration fault, not a user fault. */
        PRODUCT_CLAIMS_ACCESS_DENIED,
        /** Timeout, connection failure, or a Product-side server error. */
        PRODUCT_CLAIMS_UNAVAILABLE,
        /** The Product answered with a body that does not satisfy the claims contract. */
        PRODUCT_CLAIMS_INVALID_RESPONSE
    }

    private final Reason reason;

    public ProductClaimsException(Reason reason) {
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public Reason reason() {
        return reason;
    }
}
