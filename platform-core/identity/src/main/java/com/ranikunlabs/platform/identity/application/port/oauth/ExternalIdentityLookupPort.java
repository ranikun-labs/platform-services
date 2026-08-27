package com.ranikunlabs.platform.identity.application.port.oauth;

import java.util.Optional;

/** Read-only boundary for finding an account linked to an external provider subject. */
public interface ExternalIdentityLookupPort {

    Optional<LinkedAccountView> findByProviderSubject(String provider, String providerSubject);
}
