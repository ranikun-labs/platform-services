package com.ranikunlabs.platform.identity.application.port;

import java.util.UUID;

/** Answers whether an Identity account is enrolled for the Finance reference product. */
@FunctionalInterface
public interface FinanceReferenceEnrollmentPort {

    boolean isFinanceEnrolled(UUID accountId);
}
