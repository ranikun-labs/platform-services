package com.ranikunlabs.platform.identity.adapter.persistence;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

class IdentityConstraintViolationTranslatorTest {

    @Test
    void identifiesTheNamedLoginIdConstraintThroughSpringAndHibernateCauses() {
        SQLException sqlException = new SQLException(
                "duplicate key value violates unique constraint \"password_credentials_login_id_key\"",
                "23505");
        ConstraintViolationException hibernateException = new ConstraintViolationException(
                "duplicate login id", sqlException,
                IdentityConstraintViolationTranslator.PASSWORD_CREDENTIAL_LOGIN_ID_CONSTRAINT);

        assertThat(IdentityConstraintViolationTranslator.isPasswordCredentialLoginIdViolation(
                new DataIntegrityViolationException("identity integrity failure", hibernateException)))
                .isTrue();
    }

    @Test
    void doesNotClassifyAnotherUniqueConstraintAsDuplicateLoginId() {
        SQLException sqlException = new SQLException(
                "duplicate key value violates unique constraint \"platform_accounts_pkey\"",
                "23505");
        ConstraintViolationException hibernateException = new ConstraintViolationException(
                "other integrity failure", sqlException, "platform_accounts_pkey");

        assertThat(IdentityConstraintViolationTranslator.isPasswordCredentialLoginIdViolation(
                new DataIntegrityViolationException("identity integrity failure", hibernateException)))
                .isFalse();
    }

    @Test
    void doesNotClassifyAConstraintNameWithoutPostgresUniqueViolation() {
        SQLException sqlException = new SQLException(
                "not-null violation mentioning \"password_credentials_login_id_key\"",
                "23502");

        assertThat(IdentityConstraintViolationTranslator.isPasswordCredentialLoginIdViolation(sqlException))
                .isFalse();
    }
}
