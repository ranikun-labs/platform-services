package com.ranikunlabs.platform.identity.application.port;

import java.util.UUID;

/**
 * Minimal result of credential verification.
 *
 * <p>Carelog profile claims are deliberately not part of this identity-owned value. They are
 * obtained through {@link CRMIdentityProjectionPort}. The claims meaning and source remain
 * product-owned, while the port implementation may be provided by an Identity-owned adapter.
 */
public record AuthenticatedIdentity(
        UUID accountId,
        String loginId
) {
}
