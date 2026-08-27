package com.ranikunlabs.platform.identity.application.port.oauth;

import java.util.UUID;

/** Provider-neutral lookup result for an account linked to an external identity. */
public record LinkedAccountView(
        UUID accountId,
        LinkedAccountStatus status
) {
}
