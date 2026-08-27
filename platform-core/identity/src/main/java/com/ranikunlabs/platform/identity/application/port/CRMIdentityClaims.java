package com.ranikunlabs.platform.identity.application.port;

import java.util.UUID;

/**
 * Minimal snapshot of claims owned by the Carelog product.
 *
 * <p>This is a compatibility projection, not a copy of a Carelog entity and not an
 * authorization policy owned by Shared Identity.
 */
public record CRMIdentityClaims(
        UUID organizationId,
        String role,
        UUID publicId
) {
}
