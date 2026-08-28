package com.ranikunlabs.platform.identity.application.port;

import java.util.UUID;

/**
 * Minimal result of credential verification.
 *
 * <p>Carelog profile claims are deliberately not part of this identity-owned value. They are
 * obtained through {@link CRMIdentityProjectionPort}, whose implementation remains product-owned.
 */
public record AuthenticatedIdentity(
        UUID accountId,
        String loginId
) {
}
