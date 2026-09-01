package com.ranikunlabs.platform.identity.application;

import com.ranikunlabs.platform.identity.application.port.CRMIdentityClaims;
import com.ranikunlabs.platform.identity.application.port.CRMIdentityProjectionException;

/** Structural and Shared Identity principal checks for the Carelog claims projection. */
public final class CRMIdentityClaimsPolicy {

    private static final String ELIGIBLE_ROLE = "MANAGER";

    private CRMIdentityClaimsPolicy() {
    }

    /**
     * Validates the claims before they can be used as a Shared Identity principal or token input.
     * Structural failures and product-role ineligibility remain distinct meanings.
     */
    public static CRMIdentityClaims requireEligible(CRMIdentityClaims claims) {
        if (claims == null
                || claims.organizationId() == null
                || claims.publicId() == null
                || claims.role() == null
                || claims.role().isBlank()) {
            throw new CRMIdentityProjectionException(
                    CRMIdentityProjectionException.Reason.DEPENDENCY_CONTRACT_FAILURE);
        }
        if (!ELIGIBLE_ROLE.equals(claims.role())) {
            throw new CRMIdentityProjectionException(
                    CRMIdentityProjectionException.Reason.PRODUCT_ROLE_NOT_ELIGIBLE);
        }
        return claims;
    }
}
