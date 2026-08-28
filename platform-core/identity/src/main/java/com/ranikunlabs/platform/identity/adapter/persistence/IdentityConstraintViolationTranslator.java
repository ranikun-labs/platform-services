package com.ranikunlabs.platform.identity.adapter.persistence;

import org.hibernate.exception.ConstraintViolationException;

import java.sql.SQLException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Identifies only the Identity login-id constraint that has a typed application contract. */
final class IdentityConstraintViolationTranslator {

    static final String PASSWORD_CREDENTIAL_LOGIN_ID_CONSTRAINT =
            "password_credentials_login_id_key";

    private static final String POSTGRES_UNIQUE_VIOLATION = "23505";

    private IdentityConstraintViolationTranslator() {
    }

    static boolean isPasswordCredentialLoginIdViolation(Throwable failure) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable current = failure;
             current != null && visited.add(current);
             current = current.getCause()) {
            if (current instanceof ConstraintViolationException constraintViolation
                    && PASSWORD_CREDENTIAL_LOGIN_ID_CONSTRAINT.equals(
                    constraintViolation.getConstraintName())) {
                return true;
            }

            if (current instanceof SQLException sqlException
                    && isPostgresLoginIdConstraintViolation(sqlException)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isPostgresLoginIdConstraintViolation(SQLException exception) {
        if (!POSTGRES_UNIQUE_VIOLATION.equals(exception.getSQLState())) {
            return false;
        }

        String message = exception.getMessage();
        return message != null
                && message.contains('"' + PASSWORD_CREDENTIAL_LOGIN_ID_CONSTRAINT + '"');
    }
}
