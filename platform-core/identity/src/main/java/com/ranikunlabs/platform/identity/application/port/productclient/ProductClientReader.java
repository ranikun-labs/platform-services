package com.ranikunlabs.platform.identity.application.port.productclient;

/** Read-only boundary for an enabled product authentication client. */
public interface ProductClientReader {

    /**
     * Resolves an enabled client and fails closed for malformed, unknown, or disabled IDs.
     */
    RegisteredProductClient requireEnabled(String clientId) throws ProductClientLookupException;
}
