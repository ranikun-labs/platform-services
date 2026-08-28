package com.ranikunlabs.platform.identity.application.port;

import java.util.Optional;
import java.util.UUID;

/**
 * Typed compatibility boundary for reading the current product claim snapshot by account ID.
 *
 * <p>The implementation is Carelog-owned. Identity must not depend on a Carelog repository or
 * entity to satisfy this port.
 */
public interface CRMIdentityProjectionPort {

    CRMIdentityClaims getIdentityClaims(UUID accountId);

    Optional<CRMIdentityClaims> findIdentityClaims(UUID accountId);
}
