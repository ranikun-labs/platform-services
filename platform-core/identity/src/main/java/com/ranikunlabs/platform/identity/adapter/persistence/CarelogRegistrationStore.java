package com.ranikunlabs.platform.identity.adapter.persistence;

import com.ranikunlabs.platform.identity.adapter.persistence.entity.PasswordCredentialEntity;
import com.ranikunlabs.platform.identity.adapter.persistence.entity.PlatformAccountEntity;
import com.ranikunlabs.platform.identity.adapter.persistence.repository.PasswordCredentialJpaRepository;
import com.ranikunlabs.platform.identity.adapter.persistence.repository.PlatformAccountJpaRepository;
import com.ranikunlabs.platform.identity.application.registration.CarelogRegistrationCoordinator.AccountDetails;
import com.ranikunlabs.platform.identity.application.registration.CarelogRegistrationCoordinator.Outcome;
import com.ranikunlabs.platform.identity.application.registration.CarelogRegistrationCoordinator.RegistrationStore;
import com.ranikunlabs.platform.identity.application.registration.CarelogRegistrationCoordinator.Reservation;
import com.ranikunlabs.platform.identity.application.registration.CarelogRegistrationCoordinator.State;
import com.ranikunlabs.platform.identity.core.AccountStatus;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Unwired PostgreSQL adapter; each operation commits independently before returning. */
public final class CarelogRegistrationStore implements RegistrationStore {
    private final PlatformAccountJpaRepository accounts;
    private final PasswordCredentialJpaRepository credentials;
    private final PasswordEncoder passwords;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public CarelogRegistrationStore(PlatformAccountJpaRepository accounts,
            PasswordCredentialJpaRepository credentials, PasswordEncoder passwords,
            JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.accounts = Objects.requireNonNull(accounts);
        this.credentials = Objects.requireNonNull(credentials);
        this.passwords = Objects.requireNonNull(passwords);
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transaction = new TransactionTemplate(Objects.requireNonNull(manager));
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public Reservation reserveOrLoad(String key, AccountDetails details, String rawPassword) {
        try {
            return Objects.requireNonNull(transaction.execute(status -> {
                UUID operationKey = UUID.fromString(key);
                lock(operationKey);
                List<Row> rows = rows(operationKey);
                if (!rows.isEmpty()) return loadMatching(rows.getFirst(), details, rawPassword);
                if (credentials.existsByLoginId(details.userId())) return new Reservation(State.CONFLICT, null);
                String encoded = passwords.encode(rawPassword);
                PlatformAccountEntity account = accounts.saveAndFlush(PlatformAccountEntity.createPending(details.email()));
                PasswordCredentialEntity credential = credentials.saveAndFlush(
                        PasswordCredentialEntity.create(account.getId(), details.userId(), encoded));
                jdbc.update("insert into identity.carelog_registration_operation "
                                + "(operation_key, account_id, display_name, original_login_id, original_email, original_credential_revision, state) "
                                + "values (?, ?, ?, ?, ?, ?, 'PENDING')",
                        operationKey, account.getId(), details.name(), details.userId(), details.email(), credential.getCredentialRevision());
                return new Reservation(State.PENDING, account.getId());
            }));
        } catch (DataIntegrityViolationException failure) {
            if (IdentityConstraintViolationTranslator.isPasswordCredentialLoginIdViolation(failure)) {
                return new Reservation(State.CONFLICT, null);
            }
            throw failure;
        }
    }

    @Override
    public Outcome complete(String key, UUID accountId) {
        return transition(key, accountId, true);
    }

    @Override
    public Outcome markConflict(String key, UUID accountId) {
        return transition(key, accountId, false);
    }

    private Outcome transition(String key, UUID accountId, boolean complete) {
        return Objects.requireNonNull(transaction.execute(status -> {
            UUID operationKey = UUID.fromString(key);
            lock(operationKey);
            List<Row> rows = rows(operationKey);
            if (rows.isEmpty() || !rows.getFirst().accountId().equals(accountId)) return Outcome.CONFLICT;
            Row row = rows.getFirst();
            if (row.state() != State.PENDING) return Outcome.valueOf(row.state().name());
            PlatformAccountEntity account = accounts.findByIdForSessionReplacement(accountId)
                    .orElseThrow(() -> new IllegalStateException("Registration account missing"));
            if (account.getStatus() != AccountStatus.INACTIVE) {
                throw new IllegalStateException("Registration account is not pending");
            }
            PasswordCredentialEntity credential = credentials.findByIdForRegistration(accountId)
                    .orElseThrow(() -> new IllegalStateException("Registration credential missing"));
            boolean activate = complete && credential.getCredentialRevision() == row.credentialRevision();
            if (activate) {
                account.activatePendingRegistration();
                accounts.saveAndFlush(account);
            }
            String state = activate ? "COMPLETED" : "CONFLICT";
            int updated = jdbc.update("update identity.carelog_registration_operation set state = ?, updated_at = CURRENT_TIMESTAMP "
                    + "where operation_key = ? and account_id = ? and state = 'PENDING'", state, operationKey, accountId);
            if (updated != 1) throw new IllegalStateException("Registration transition failed");
            return Outcome.valueOf(state);
        }));
    }

    private Reservation loadMatching(Row row, AccountDetails details, String rawPassword) {
        accounts.findByIdForSessionReplacement(row.accountId())
                .orElseThrow(() -> new IllegalStateException("Registration account missing"));
        PasswordCredentialEntity credential = credentials.findByIdForRegistration(row.accountId())
                .orElseThrow(() -> new IllegalStateException("Registration credential missing"));
        if (!row.originalLogin().equals(details.userId()) || !row.originalEmail().equals(details.email())
                || !row.name().equals(details.name()) || credential.getCredentialRevision() != row.credentialRevision()
                || !passwords.matches(rawPassword, credential.getPasswordHash())) {
            return new Reservation(State.CONFLICT, null);
        }
        return new Reservation(row.state(), row.accountId());
    }

    private void lock(UUID key) {
        jdbc.execute("select pg_advisory_xact_lock(hashtextextended(?, 0))", (org.springframework.jdbc.core.PreparedStatementCallback<Void>) statement -> {
            statement.setString(1, key.toString());
            statement.execute();
            return null;
        });
    }

    private List<Row> rows(UUID key) {
        return jdbc.query("select account_id, display_name, state, original_login_id, original_email, original_credential_revision from identity.carelog_registration_operation where operation_key = ?",
                (result, rowNum) -> new Row(result.getObject(1, UUID.class), result.getString(2), State.valueOf(result.getString(3)),
                        result.getString(4), result.getString(5), result.getLong(6)), key);
    }

    private record Row(UUID accountId, String name, State state, String originalLogin,
                       String originalEmail, long credentialRevision) {
        @Override public String toString() { return "RegistrationRow[redacted]"; }
    }
}
