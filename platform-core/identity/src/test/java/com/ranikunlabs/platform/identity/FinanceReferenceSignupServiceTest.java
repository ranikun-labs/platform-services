package com.ranikunlabs.platform.identity;

import com.ranikunlabs.platform.identity.application.FinanceReferenceSignupService;
import com.ranikunlabs.platform.identity.application.FinanceReferenceSignupService.InvalidFinanceSignupException;
import com.ranikunlabs.platform.identity.application.port.DuplicateLoginIdException;
import com.ranikunlabs.platform.identity.application.port.FinanceReferenceAccountRegistrationPort;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FinanceReferenceSignupServiceTest {

    private static final UUID ACCOUNT = UUID.fromString("66666666-6666-6666-6666-666666666666");

    @Test
    void normalizesEmailAndRegistersWithRawPasswordBehindThePort() {
        FinanceReferenceAccountRegistrationPort accounts = mock(FinanceReferenceAccountRegistrationPort.class);
        when(accounts.registerFinanceAccount("dev.user@example.test", "Synthetic-Pass123")).thenReturn(ACCOUNT);

        UUID result = new FinanceReferenceSignupService(accounts)
                .register("  Dev.User@Example.TEST ", "Synthetic-Pass123");

        assertThat(result).isEqualTo(ACCOUNT);
        verify(accounts).registerFinanceAccount("dev.user@example.test", "Synthetic-Pass123");
    }

    @Test
    void policyViolationsAreRejectedBeforeTheStoreIsTouched() {
        FinanceReferenceAccountRegistrationPort accounts = mock(FinanceReferenceAccountRegistrationPort.class);
        FinanceReferenceSignupService service = new FinanceReferenceSignupService(accounts);
        String[][] cases = {
                {null, "Synthetic-Pass123"},
                {"", "Synthetic-Pass123"},
                {"no-at-sign.example.test", "Synthetic-Pass123"},
                {"a@b", "Synthetic-Pass123"},
                {"dev@example.test", null},
                {"dev@example.test", "short1"},
                {"dev@example.test", "onlyletterspw"},
                {"dev@example.test", "1234567890123"},
                {"dev@example.test", "Synthetic-Pass123\n"},
                {"dev@example.test", "dev@example.test"},
                {"dev@example.test", "Aa1" + "x".repeat(70)},
                {"dev@example.test", "한글비밀번호는바이트가길어서거절되어야한다1234"},
        };
        for (String[] invalid : cases) {
            assertThatThrownBy(() -> service.register(invalid[0], invalid[1]))
                    .as("email=%s", invalid[0])
                    .isInstanceOf(InvalidFinanceSignupException.class);
        }
        verify(accounts, never()).registerFinanceAccount(anyString(), anyString());
    }

    @Test
    void duplicateLoginIdFromStoreIsPropagatedAsDuplicateNotGenericFailure() {
        FinanceReferenceAccountRegistrationPort accounts = mock(FinanceReferenceAccountRegistrationPort.class);
        when(accounts.registerFinanceAccount(anyString(), anyString())).thenThrow(new DuplicateLoginIdException());

        assertThatThrownBy(() -> new FinanceReferenceSignupService(accounts)
                .register("dev@example.test", "Synthetic-Pass123"))
                .isInstanceOf(DuplicateLoginIdException.class);
    }
}
