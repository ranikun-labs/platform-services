package com.ranikunlabs.platform.identity.adapter.http.dto.response;

import java.time.OffsetDateTime;

/** Security-filter error body matching the canonical Carelog entry point/denied handler. */
public record SecurityErrorResponse(
        OffsetDateTime timestamp,
        int status,
        String error,
        String message,
        String path
) {
}
