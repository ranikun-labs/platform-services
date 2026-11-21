package com.ranikunlabs.platform.identity.application.registration;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** Internal orchestration only. No controller, persistence adapter or transaction is registered here. */
public final class CarelogRegistrationCoordinator {
    private static final Pattern KEY = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
    private final RegistrationStore store;
    private final CarelogEnrollment enrollment;

    public CarelogRegistrationCoordinator(RegistrationStore store, CarelogEnrollment enrollment) {
        this.store = Objects.requireNonNull(store);
        this.enrollment = Objects.requireNonNull(enrollment);
    }

    /** A successful result is returned only after durable completion. */
    public Outcome register(String key, String userId, String email, String password, String name) {
        if (key == null || !KEY.matcher(key).matches()) throw new InvalidRequestException();
        var request = new AccountDetails(trimRequired(userId), trimRequired(email), trimRequired(name));
        validatePassword(password);
        Reservation reservation = Objects.requireNonNull(store.reserveOrLoad(key, request, password));
        if (reservation.state() == State.CONFLICT) return Outcome.CONFLICT;
        if (reservation.state() == State.COMPLETED) return Outcome.COMPLETED;
        UUID accountId = Objects.requireNonNull(reservation.accountId());
        try {
            // This call must run outside the store's transaction and be idempotent for accountId.
            enrollment.ensureEnrolled(accountId,
                    new EnrollmentDetails(request.userId(), request.email(), request.name()));
        } catch (EnrollmentConflictException conflict) {
            return Objects.requireNonNull(store.markConflict(key, accountId));
        } catch (EnrollmentUnavailableException unavailable) {
            return Outcome.PENDING;
        }
        return Objects.requireNonNull(store.complete(key, accountId));
    }

    private static String trimRequired(String value) {
        if (value == null) throw new InvalidRequestException();
        if (value.codePoints().anyMatch(Character::isISOControl)) throw new InvalidRequestException();
        int start = 0;
        int end = value.length();
        while (start < end && isEcmaWhitespace(value.codePointAt(start))) {
            start += Character.charCount(value.codePointAt(start));
        }
        while (end > start && isEcmaWhitespace(value.codePointBefore(end))) {
            end -= Character.charCount(value.codePointBefore(end));
        }
        String result = value.substring(start, end);
        if (result.isEmpty()) throw new InvalidRequestException();
        validateUtf8(result);
        return result;
    }

    private static boolean isEcmaWhitespace(int codePoint) {
        return (codePoint >= 0x09 && codePoint <= 0x0d) || codePoint == 0x20
                || codePoint == 0xa0 || codePoint == 0x1680
                || (codePoint >= 0x2000 && codePoint <= 0x200a)
                || codePoint == 0x2028 || codePoint == 0x2029 || codePoint == 0x202f
                || codePoint == 0x205f || codePoint == 0x3000 || codePoint == 0xfeff;
    }

    private static void validatePassword(String password) {
        if (password == null) throw new InvalidRequestException();
        int length = password.codePointCount(0, password.length());
        if (length < 8 || length > 20) throw new InvalidRequestException();
        if (password.codePoints().anyMatch(Character::isISOControl)) throw new InvalidRequestException();
        if (validateUtf8(password) > 72) throw new InvalidRequestException();
    }

    private static int validateUtf8(String value) {
        try {
            ByteBuffer bytes = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(value));
            return bytes.remaining();
        } catch (CharacterCodingException exception) {
            throw new InvalidRequestException();
        }
    }

    public enum Outcome { PENDING, COMPLETED, CONFLICT }
    public enum State { PENDING, COMPLETED, CONFLICT }

    public record Reservation(State state, UUID accountId) {
        public Reservation {
            Objects.requireNonNull(state);
            if (state == State.PENDING) Objects.requireNonNull(accountId);
        }
    }

    /** Contains personal data. Avoid logging this object. */
    public static final class AccountDetails {
        private final String userId;
        private final String email;
        private final String name;
        private AccountDetails(String userId, String email, String name) {
            this.userId = userId;
            this.email = email;
            this.name = name;
        }
        public String userId() { return userId; }
        public String email() { return email; }
        public String name() { return name; }
        @Override public String toString() { return "AccountDetails[redacted]"; }
    }

    /** Product-facing fields only; never includes a password or hash. */
    public record EnrollmentDetails(String userId, String email, String name) {
        public EnrollmentDetails {
            Objects.requireNonNull(userId);
            Objects.requireNonNull(email);
            Objects.requireNonNull(name);
        }
        @Override public String toString() { return "EnrollmentDetails[redacted]"; }
    }

    /**
     * The store must atomically reserve/load and compare safely without retaining a raw password.
     * A newly reserved account and operation are durable together and the account is not
     * login-eligible while pending. Replays compare the same request and return conflict on a
     * mismatch. The methods below are durable compare-and-set operations, not in-memory flags.
     */
    public interface RegistrationStore {
        Reservation reserveOrLoad(String key, AccountDetails details, String rawPassword);
        /**
         * Atomically complete only the matching pending key/account and make that account
         * login-eligible in the same transaction. Return the actual durable outcome if another
         * caller has already completed or marked a terminal conflict.
         */
        Outcome complete(String key, UUID accountId);
        /**
         * Atomically mark only the matching pending key/account as terminal conflict, leaving it
         * ineligible to log in. Return the actual durable outcome if a concurrent transition won.
         * A failed or ambiguous persistence operation must throw, never claim a durable conflict.
         */
        Outcome markConflict(String key, UUID accountId);
    }

    public interface CarelogEnrollment {
        /** Separately authenticated transport; same accountId must be idempotent across retries.
         * Throw conflict only for an authenticated, definitive product conflict; ambiguous
         * transport failures and timeouts must be mapped to unavailable.
         */
        void ensureEnrolled(UUID accountId, EnrollmentDetails details)
                throws EnrollmentUnavailableException, EnrollmentConflictException;
    }

    public static final class EnrollmentUnavailableException extends Exception { }
    public static final class EnrollmentConflictException extends Exception { }
    public static final class InvalidRequestException extends IllegalArgumentException {
        public InvalidRequestException() { super("Invalid registration request"); }
    }
}
