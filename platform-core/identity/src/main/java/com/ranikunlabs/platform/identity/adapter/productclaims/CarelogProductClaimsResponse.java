package com.ranikunlabs.platform.identity.adapter.productclaims;

import java.util.UUID;

/** Transport-only response from Carelog's private Product claims API. */
public record CarelogProductClaimsResponse(
        UUID organizationId,
        String role,
        UUID publicId
) {
}
