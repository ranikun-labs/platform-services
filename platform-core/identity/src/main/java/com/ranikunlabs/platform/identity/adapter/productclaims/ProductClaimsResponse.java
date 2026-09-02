package com.ranikunlabs.platform.identity.adapter.productclaims;

import java.util.UUID;

/** Transport shape of the private Carelog Product claims read API. */
record ProductClaimsResponse(
        UUID organizationId,
        String role,
        UUID publicId
) {
}
