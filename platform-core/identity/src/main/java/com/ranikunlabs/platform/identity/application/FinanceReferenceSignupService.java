package com.ranikunlabs.platform.identity.application;

import com.ranikunlabs.platform.identity.application.port.FinanceReferenceAccountRegistrationPort;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Public self-service signup for DEV Finance accounts. The login identifier is the normalized
 * email (trimmed, lowercased), so the existing password-login lookup reuses it unchanged.
 */
public final class FinanceReferenceSignupService {

    static final int MIN_PASSWORD_BYTES = 10;
    /** BCrypt silently truncates input beyond 72 bytes, so longer passwords are rejected instead. */
    static final int MAX_PASSWORD_BYTES = 72;
    static final int MAX_EMAIL_BYTES = 254;
    private static final Pattern EMAIL = Pattern.compile("^[a-z0-9._%+-]+@[a-z0-9-]+(\\.[a-z0-9-]+)*\\.[a-z]{2,}$");

    private final FinanceReferenceAccountRegistrationPort accounts;

    public FinanceReferenceSignupService(FinanceReferenceAccountRegistrationPort accounts) {
        this.accounts = Objects.requireNonNull(accounts, "accounts");
    }

    /**
     * @throws InvalidFinanceSignupException when the email or password violates the signup policy.
     * @throws com.ranikunlabs.platform.identity.application.port.DuplicateLoginIdException when the email is taken.
     */
    public UUID register(String rawEmail, String rawPassword) {
        String email = normalizeEmail(rawEmail);
        requirePasswordPolicy(email, rawPassword);
        return accounts.registerFinanceAccount(email, rawPassword);
    }

    static String normalizeEmail(String rawEmail) {
        if (rawEmail == null) {
            throw new InvalidFinanceSignupException();
        }
        String email = rawEmail.trim().toLowerCase(Locale.ROOT);
        if (email.getBytes(StandardCharsets.UTF_8).length > MAX_EMAIL_BYTES || !EMAIL.matcher(email).matches()) {
            throw new InvalidFinanceSignupException();
        }
        return email;
    }

    static void requirePasswordPolicy(String email, String password) {
        if (password == null) {
            throw new InvalidFinanceSignupException();
        }
        int bytes = password.getBytes(StandardCharsets.UTF_8).length;
        if (bytes < MIN_PASSWORD_BYTES || bytes > MAX_PASSWORD_BYTES) {
            throw new InvalidFinanceSignupException();
        }
        if (password.chars().anyMatch(Character::isISOControl)) {
            throw new InvalidFinanceSignupException();
        }
        boolean hasLetter = password.chars().anyMatch(c -> c < 128 && Character.isLetter(c));
        boolean hasDigit = password.chars().anyMatch(c -> c < 128 && Character.isDigit(c));
        if (!hasLetter || !hasDigit) {
            throw new InvalidFinanceSignupException();
        }
        if (password.toLowerCase(Locale.ROOT).equals(email)) {
            throw new InvalidFinanceSignupException();
        }
    }

    /** Input rejected by the signup policy; the message is deliberately generic at the HTTP boundary. */
    public static final class InvalidFinanceSignupException extends RuntimeException {

        public InvalidFinanceSignupException() {
            super("Invalid Finance signup request.");
        }
    }
}
