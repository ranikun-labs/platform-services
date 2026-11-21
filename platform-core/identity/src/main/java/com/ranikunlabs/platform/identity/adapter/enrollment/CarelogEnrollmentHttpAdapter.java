package com.ranikunlabs.platform.identity.adapter.enrollment;

import com.ranikunlabs.platform.identity.application.registration.CarelogRegistrationCoordinator.CarelogEnrollment;
import com.ranikunlabs.platform.identity.application.registration.CarelogRegistrationCoordinator.EnrollmentConflictException;
import com.ranikunlabs.platform.identity.application.registration.CarelogRegistrationCoordinator.EnrollmentDetails;
import com.ranikunlabs.platform.identity.application.registration.CarelogRegistrationCoordinator.EnrollmentUnavailableException;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.util.Objects;
import java.util.UUID;

/** Product enrollment over an independently authenticated, idempotent endpoint. */
public final class CarelogEnrollmentHttpAdapter implements CarelogEnrollment {
    static final String PATH = "/api/v1/internal/v1/identity/carelog/enrollments/{accountId}";
    private final RestClient client;
    private final String credential;

    public CarelogEnrollmentHttpAdapter(RestClient client, String credential) {
        this.client = Objects.requireNonNull(client);
        if (credential == null || credential.isBlank() || credential.length() < 32
                || credential.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("enrollment credential must be a nonblank bearer token");
        }
        this.credential = credential;
    }

    @Override
    public void ensureEnrolled(UUID accountId, EnrollmentDetails details)
            throws EnrollmentUnavailableException, EnrollmentConflictException {
        if (accountId == null || details == null) throw new EnrollmentUnavailableException();
        try {
            Result result = client.put().uri(PATH, accountId)
                    .headers(headers -> headers.setBearerAuth(credential))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(details)
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        if (status == 409) return Result.CONFLICT;
                        if (status != 201) return Result.UNAVAILABLE;
                        try {
                            return response.getBody().read() == -1 ? Result.SUCCESS : Result.UNAVAILABLE;
                        } catch (IOException exception) {
                            return Result.UNAVAILABLE;
                        }
                    });
            if (result == Result.CONFLICT) throw new EnrollmentConflictException();
            if (result != Result.SUCCESS) throw new EnrollmentUnavailableException();
        } catch (EnrollmentConflictException | EnrollmentUnavailableException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            // Deliberately exclude response text, request data, and token from propagated errors.
            throw new EnrollmentUnavailableException();
        }
    }

    private enum Result { SUCCESS, CONFLICT, UNAVAILABLE }
}
