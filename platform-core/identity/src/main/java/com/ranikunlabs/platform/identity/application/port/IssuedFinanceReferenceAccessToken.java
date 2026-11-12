package com.ranikunlabs.platform.identity.application.port;

import java.time.Instant;

public record IssuedFinanceReferenceAccessToken(String value, Instant expiresAt) {
}
