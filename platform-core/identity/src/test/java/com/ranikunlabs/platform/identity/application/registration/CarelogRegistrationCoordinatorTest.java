package com.ranikunlabs.platform.identity.application.registration;

import static com.ranikunlabs.platform.identity.application.registration.CarelogRegistrationCoordinator.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CarelogRegistrationCoordinatorTest {
    private static final String KEY = "abcdef12-3333-4333-8333-333333333333";
    private static final UUID ACCOUNT = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private final RegistrationStore store = mock(RegistrationStore.class);
    private final CarelogEnrollment enrollment = mock(CarelogEnrollment.class);
    private CarelogRegistrationCoordinator coordinator;

    @BeforeEach void setup() { coordinator = new CarelogRegistrationCoordinator(store, enrollment); }

    @Test void reservesCanonicalFieldsThenCompletesOnlyAfterEnrollment() throws Exception {
        when(store.reserveOrLoad(eq(KEY), any(), eq(" pass Word "))).thenReturn(new Reservation(State.PENDING, ACCOUNT));
        when(store.complete(KEY, ACCOUNT)).thenReturn(Outcome.COMPLETED);
        assertThat(coordinator.register(KEY, "  User  ", "  Mail@Example.test ", " pass Word ", " Name "))
                .isEqualTo(Outcome.COMPLETED);
        var details = org.mockito.ArgumentCaptor.forClass(AccountDetails.class);
        var order = inOrder(store, enrollment);
        order.verify(store).reserveOrLoad(eq(KEY), details.capture(), eq(" pass Word "));
        assertThat(details.getValue().userId()).isEqualTo("User");
        assertThat(details.getValue().email()).isEqualTo("Mail@Example.test");
        assertThat(details.getValue().name()).isEqualTo("Name");
        assertThat(details.getValue().toString()).doesNotContain("User", "Mail", "Name");
        var productDetails = org.mockito.ArgumentCaptor.forClass(EnrollmentDetails.class);
        order.verify(enrollment).ensureEnrolled(eq(ACCOUNT), productDetails.capture());
        assertThat(productDetails.getValue()).isEqualTo(new EnrollmentDetails("User", "Mail@Example.test", "Name"));
        assertThat(productDetails.getValue().toString()).doesNotContain("User", "Mail", "Name");
        order.verify(store).complete(KEY, ACCOUNT);
    }

    @Test void unavailableEnrollmentKeepsPendingAndRetryCompletes() throws Exception {
        when(store.reserveOrLoad(eq(KEY), any(), anyString())).thenReturn(new Reservation(State.PENDING, ACCOUNT));
        doThrow(new EnrollmentUnavailableException()).doNothing().when(enrollment).ensureEnrolled(eq(ACCOUNT), any(EnrollmentDetails.class));
        when(store.complete(KEY, ACCOUNT)).thenReturn(Outcome.COMPLETED);
        assertThat(coordinator.register(KEY, "User", "a@b", "password", "Name")).isEqualTo(Outcome.PENDING);
        verify(store, never()).complete(anyString(), any());
        assertThat(coordinator.register(KEY, "User", "a@b", "password", "Name")).isEqualTo(Outcome.COMPLETED);
        verify(enrollment, times(2)).ensureEnrolled(eq(ACCOUNT), any(EnrollmentDetails.class));
    }

    @Test void completedAndConflictNeverCallEnrollment() {
        when(store.reserveOrLoad(eq(KEY), any(), anyString()))
                .thenReturn(new Reservation(State.COMPLETED, ACCOUNT), new Reservation(State.CONFLICT, null));
        assertThat(coordinator.register(KEY, "User", "a@b", "password", "Name")).isEqualTo(Outcome.COMPLETED);
        assertThat(coordinator.register(KEY, "User", "a@b", "password", "Name")).isEqualTo(Outcome.CONFLICT);
        verifyNoInteractions(enrollment);
        verify(store, never()).complete(anyString(), any());
    }

    @Test void pendingCompletionCannotBeReportedAsSuccess() {
        when(store.reserveOrLoad(eq(KEY), any(), anyString())).thenReturn(new Reservation(State.PENDING, ACCOUNT));
        when(store.complete(KEY, ACCOUNT)).thenReturn(Outcome.PENDING);
        assertThat(coordinator.register(KEY, "User", "a@b", "password", "Name")).isEqualTo(Outcome.PENDING);
    }

    @Test void authenticatedEnrollmentConflictIsDistinctFromUnavailable() throws Exception {
        when(store.reserveOrLoad(eq(KEY), any(), anyString()))
                .thenReturn(new Reservation(State.PENDING, ACCOUNT), new Reservation(State.CONFLICT, ACCOUNT));
        doThrow(new EnrollmentConflictException()).when(enrollment).ensureEnrolled(eq(ACCOUNT), any(EnrollmentDetails.class));
        when(store.markConflict(KEY, ACCOUNT)).thenReturn(Outcome.CONFLICT);
        assertThat(coordinator.register(KEY, "User", "a@b", "password", "Name")).isEqualTo(Outcome.CONFLICT);
        assertThat(coordinator.register(KEY, "User", "a@b", "password", "Name")).isEqualTo(Outcome.CONFLICT);
        verify(enrollment, times(1)).ensureEnrolled(eq(ACCOUNT), any(EnrollmentDetails.class));
        verify(store).markConflict(KEY, ACCOUNT);
        verify(store, never()).complete(anyString(), any());
    }

    @Test void failedConflictWriteDoesNotClaimADurableConflict() throws Exception {
        when(store.reserveOrLoad(eq(KEY), any(), anyString())).thenReturn(new Reservation(State.PENDING, ACCOUNT));
        doThrow(new EnrollmentConflictException()).when(enrollment).ensureEnrolled(eq(ACCOUNT), any(EnrollmentDetails.class));
        when(store.markConflict(KEY, ACCOUNT)).thenThrow(new IllegalStateException("store unavailable"));
        assertThatThrownBy(() -> coordinator.register(KEY, "User", "a@b", "password", "Name"))
                .isInstanceOf(IllegalStateException.class);
        verify(store, never()).complete(anyString(), any());
    }

    @Test void concurrentCompletionWinsOverTerminalConflict() throws Exception {
        when(store.reserveOrLoad(eq(KEY), any(), anyString())).thenReturn(new Reservation(State.PENDING, ACCOUNT));
        doThrow(new EnrollmentConflictException()).when(enrollment).ensureEnrolled(eq(ACCOUNT), any(EnrollmentDetails.class));
        when(store.markConflict(KEY, ACCOUNT)).thenReturn(Outcome.COMPLETED);
        assertThat(coordinator.register(KEY, "User", "a@b", "password", "Name")).isEqualTo(Outcome.COMPLETED);
    }

    @Test void failureToPersistCompletionDoesNotReturnSuccessAndRetryCanComplete() throws Exception {
        when(store.reserveOrLoad(eq(KEY), any(), anyString())).thenReturn(new Reservation(State.PENDING, ACCOUNT));
        when(store.complete(KEY, ACCOUNT))
                .thenThrow(new IllegalStateException("store unavailable"))
                .thenReturn(Outcome.COMPLETED);
        assertThatThrownBy(() -> coordinator.register(KEY, "User", "a@b", "password", "Name"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(coordinator.register(KEY, "User", "a@b", "password", "Name"))
                .isEqualTo(Outcome.COMPLETED);
        verify(enrollment, times(2)).ensureEnrolled(eq(ACCOUNT), any(EnrollmentDetails.class));
    }

    @Test void trimsJavascriptWhitespaceWithoutChangingCase() throws Exception {
        when(store.reserveOrLoad(eq(KEY), any(), anyString())).thenReturn(new Reservation(State.PENDING, ACCOUNT));
        when(store.complete(KEY, ACCOUNT)).thenReturn(Outcome.COMPLETED);
        coordinator.register(KEY, "\u00a0User\u3000", "\ufeffMail@Example.test\u2028", "password", "\u2003Name\u202f");
        var details = org.mockito.ArgumentCaptor.forClass(AccountDetails.class);
        verify(store).reserveOrLoad(eq(KEY), details.capture(), eq("password"));
        assertThat(details.getValue().userId()).isEqualTo("User");
        assertThat(details.getValue().email()).isEqualTo("Mail@Example.test");
        assertThat(details.getValue().name()).isEqualTo("Name");
    }

    @Test void rejectsInvalidKeysAndPasswordsBeforeStoreAccess() {
        for (String key : new String[]{KEY.toUpperCase(), "33333333-3333-1333-8333-333333333333", "bad"}) {
            assertThatThrownBy(() -> coordinator.register(key, "u", "a@b", "password", "Name"))
                    .isInstanceOf(InvalidRequestException.class);
        }
        for (String password : new String[]{"1234567", "a".repeat(21), "😀".repeat(19), "1234567\ud800", "pass\nword"}) {
            assertThatThrownBy(() -> coordinator.register(KEY, "u", "a@b", password, "Name"))
                    .isInstanceOf(InvalidRequestException.class);
        }
        assertThatThrownBy(() -> coordinator.register(KEY, "  ", "a@b", "password", "Name"))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> coordinator.register(KEY, "u\u0000", "a@b", "password", "Name"))
                .isInstanceOf(InvalidRequestException.class);
        verifyNoInteractions(store, enrollment);
    }
}
