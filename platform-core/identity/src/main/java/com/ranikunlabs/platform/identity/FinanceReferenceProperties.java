package com.ranikunlabs.platform.identity;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/** Opt-in switches and a fixture-only allowlist for the Finance reference issuer. */
@ConfigurationProperties(prefix = "platform.identity.finance-reference")
public record FinanceReferenceProperties(boolean enabled, List<String> selectedAccountIds) {
}
