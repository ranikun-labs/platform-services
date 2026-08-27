package com.ranikunlabs.platform.identity.core;

/**
 * Provider-neutral result returned after an external provider has verified an identity.
 *
 * <p>The provider name remains data rather than a fixed provider implementation so that
 * provider selection and adapters stay outside the identity core.
 */
public record ExternalIdentityAssertion(
        String provider,
        String providerSubject,
        String emailSnapshot
) {
}
