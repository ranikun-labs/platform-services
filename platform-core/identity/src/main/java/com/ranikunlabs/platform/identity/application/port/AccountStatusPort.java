package com.ranikunlabs.platform.identity.application.port;

import com.ranikunlabs.platform.identity.core.AccountStatus;

import java.util.Optional;
import java.util.UUID;

/** Reads the lifecycle state of an Identity-owned platform account. */
public interface AccountStatusPort {

    Optional<AccountStatus> findStatus(UUID accountId);
}
