package com.ranikunlabs.platform.identity.adapter.http;

import com.ranikunlabs.platform.identity.application.registration.CarelogRegistrationCoordinator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static com.ranikunlabs.platform.identity.application.registration.CarelogRegistrationCoordinator.Outcome;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CarelogRegistrationControllerTest {
    private static final String KEY = "abcdef12-3333-4333-8333-333333333333";
    private final CarelogRegistrationCoordinator coordinator = mock(CarelogRegistrationCoordinator.class);
    private MockMvc mvc;
    @BeforeEach void setup() { mvc = MockMvcBuilders.standaloneSetup(new CarelogRegistrationController(coordinator)).build(); }

    @Test void completedPendingAndConflictReturnGenericNoStoreResponses() throws Exception {
        when(coordinator.register(eq(KEY), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Outcome.COMPLETED, Outcome.PENDING, Outcome.CONFLICT);
        for (int expected : new int[]{201, 503, 409}) {
            mvc.perform(post(CarelogRegistrationController.PATH).header("Idempotency-Key", KEY)
                    .contentType("application/json").content(body("password")))
                    .andExpect(status().is(expected)).andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("password"))));
        }
    }

    @Test void normalizesFieldsBeforeEmailValidationAndReservation() throws Exception {
        when(coordinator.register(eq(KEY), anyString(), anyString(), anyString(), anyString())).thenReturn(Outcome.COMPLETED);
        mvc.perform(post(CarelogRegistrationController.PATH).header("Idempotency-Key", KEY)
                .contentType("application/json")
                .content("{\"userId\":\"  User \",\"email\":\"  Person@example.test  \",\"password\":\" password \",\"name\":\" Name \"}"))
                .andExpect(status().isCreated());
        verify(coordinator).register(KEY, "User", "Person@example.test", " password ", "Name");
    }

    @Test void invalidEmailIsRejectedBeforeReservation() throws Exception {
        mvc.perform(post(CarelogRegistrationController.PATH).header("Idempotency-Key", KEY)
                .contentType("application/json")
                .content("{\"userId\":\"User\",\"email\":\"not an email\",\"password\":\"password\",\"name\":\"Name\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(coordinator);
    }

    @Test void missingOrRepeatedKeysAreRejectedWithoutCallingCoordinator() throws Exception {
        mvc.perform(post(CarelogRegistrationController.PATH).contentType("application/json").content(body("password")))
                .andExpect(status().isBadRequest());
        mvc.perform(post(CarelogRegistrationController.PATH).header("Idempotency-Key", KEY, KEY)
                .contentType("application/json").content(body("password"))).andExpect(status().isBadRequest());
        verifyNoInteractions(coordinator);
    }

    @Test void invalidInputAndFailureDoNotEchoSecrets() throws Exception {
        when(coordinator.register(eq(KEY), anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new CarelogRegistrationCoordinator.InvalidRequestException())
                .thenThrow(new IllegalStateException("sensitive password"));
        for (int expected : new int[]{400, 503}) {
            mvc.perform(post(CarelogRegistrationController.PATH).header("Idempotency-Key", KEY)
                    .contentType("application/json").content(body("password")))
                    .andExpect(status().is(expected)).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("password"))));
        }
    }

    @Test void malformedAndOversizedFieldsReturnGenericBadRequest() throws Exception {
        mvc.perform(post(CarelogRegistrationController.PATH).header("Idempotency-Key", KEY)
                .contentType("application/json").content("{")) .andExpect(status().isBadRequest());
        mvc.perform(post(CarelogRegistrationController.PATH).header("Idempotency-Key", KEY)
                .contentType("application/json").content(body("a".repeat(41)))) .andExpect(status().isBadRequest());
        verifyNoInteractions(coordinator);
    }

    private static String body(String password) {
        return "{\"userId\":\"user\",\"email\":\"a@b\",\"password\":\"" + password + "\",\"name\":\"Name\"}";
    }
}
