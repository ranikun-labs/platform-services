package com.ranikunlabs.platform.identity.application.port;

import java.util.UUID;

/**
 * Creates a Finance-enrolled password account in the shared Identity store: platform account,
 * password credential, and Finance enrollment in one transaction. Encoding happens behind the port.
 */
public interface FinanceReferenceAccountRegistrationPort {

    /** @throws DuplicateLoginIdException when the login identifier already exists. */
    UUID registerFinanceAccount(String loginId, String rawPassword) throws DuplicateLoginIdException;
}
