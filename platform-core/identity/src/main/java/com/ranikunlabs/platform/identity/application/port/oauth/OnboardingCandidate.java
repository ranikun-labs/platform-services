package com.ranikunlabs.platform.identity.application.port.oauth;

/** Minimal external-identity candidate handed to a product-owned onboarding flow. */
public record OnboardingCandidate(
        String provider,
        String providerSubject,
        String email,
        boolean emailVerified,
        String displayNameHint,
        String returnTo
) {
}
