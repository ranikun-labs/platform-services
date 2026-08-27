package com.ranikunlabs.platform.identity;

import com.ranikunlabs.platform.identity.adapter.persistence.entity.PasswordCredentialEntity;
import com.ranikunlabs.platform.identity.adapter.persistence.repository.PasswordCredentialJpaRepository;
import com.ranikunlabs.platform.identity.adapter.security.IdentityCredentialAdapter;
import com.ranikunlabs.platform.identity.application.port.AccountStatusPort;
import com.ranikunlabs.platform.identity.application.port.AuthenticatedIdentity;
import com.ranikunlabs.platform.identity.application.port.InvalidCredentialsException;
import com.ranikunlabs.platform.identity.core.AccountStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IdentityCredentialAdapterTest {

    private static final UUID ACCOUNT_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Mock
    private PasswordCredentialJpaRepository credentialRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private AccountStatusPort accountStatusPort;

    private IdentityCredentialAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new IdentityCredentialAdapter(credentialRepository, passwordEncoder, accountStatusPort);
    }

    @Test
    void validCredentialReturnsMinimalAuthenticatedIdentity() {
        PasswordCredentialEntity credential = PasswordCredentialEntity.create(
                ACCOUNT_ID, "manager@example.com", "encoded-hash");
        when(credentialRepository.findByLoginId("manager@example.com"))
                .thenReturn(Optional.of(credential));
        when(passwordEncoder.matches("raw-password", "encoded-hash")).thenReturn(true);
        when(accountStatusPort.findStatus(ACCOUNT_ID)).thenReturn(Optional.of(AccountStatus.ACTIVE));

        AuthenticatedIdentity result = adapter.authenticate("manager@example.com", "raw-password");

        assertThat(result).isEqualTo(new AuthenticatedIdentity(ACCOUNT_ID, "manager@example.com"));
    }

    @Test
    void missingCredentialAndWrongPasswordShareEnumerationSafeFailure() {
        when(credentialRepository.findByLoginId("missing@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> adapter.authenticate("missing@example.com", "password"))
                .isInstanceOf(InvalidCredentialsException.class)
                .hasMessage(null);
        verifyNoInteractions(passwordEncoder, accountStatusPort);

        PasswordCredentialEntity credential = PasswordCredentialEntity.create(
                ACCOUNT_ID, "manager@example.com", "encoded-hash");
        when(credentialRepository.findByLoginId("manager@example.com"))
                .thenReturn(Optional.of(credential));
        when(passwordEncoder.matches("wrong-password", "encoded-hash")).thenReturn(false);

        assertThatThrownBy(() -> adapter.authenticate("manager@example.com", "wrong-password"))
                .isInstanceOf(InvalidCredentialsException.class)
                .hasMessage(null);
        verifyNoInteractions(accountStatusPort);
    }

    @Test
    void inactiveOrMissingAccountIsRejectedAfterPasswordVerification() {
        PasswordCredentialEntity credential = PasswordCredentialEntity.create(
                ACCOUNT_ID, "manager@example.com", "encoded-hash");
        when(credentialRepository.findByLoginId("manager@example.com"))
                .thenReturn(Optional.of(credential));
        when(passwordEncoder.matches("raw-password", "encoded-hash")).thenReturn(true);
        when(accountStatusPort.findStatus(ACCOUNT_ID)).thenReturn(Optional.of(AccountStatus.INACTIVE));

        assertThatThrownBy(() -> adapter.authenticate("manager@example.com", "raw-password"))
                .isInstanceOf(InvalidCredentialsException.class);

        when(accountStatusPort.findStatus(ACCOUNT_ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> adapter.authenticate("manager@example.com", "raw-password"))
                .isInstanceOf(InvalidCredentialsException.class);
        verify(passwordEncoder, org.mockito.Mockito.times(2))
                .matches("raw-password", "encoded-hash");
    }
}
