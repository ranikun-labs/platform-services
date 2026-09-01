package com.ranikunlabs.platform.identity.adapter.http;

import java.time.OffsetDateTime;

/** Identity-owned equivalent of the shared authentication error body. */
public record IdentityErrorResponse(
        OffsetDateTime timestamp,
        int status,
        String error,
        String message,
        String path
) {
}
