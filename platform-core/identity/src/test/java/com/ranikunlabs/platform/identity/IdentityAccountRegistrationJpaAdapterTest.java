package com.ranikunlabs.platform.identity;

import com.ranikunlabs.platform.identity.adapter.persistence.IdentityAccountRegistrationJpaAdapter;
import com.ranikunlabs.platform.identity.adapter.persistence.entity.PasswordCredentialEntity;
import com.ranikunlabs.platform.identity.adapter.persistence.entity.PlatformAccountEntity;
import com.ranikunlabs.platform.identity.adapter.persistence.repository.PasswordCredentialJpaRepository;
import com.ranikunlabs.platform.identity.adapter.persistence.repository.PlatformAccountJpaRepository;
import com.ranikunlabs.platform.identity.application.port.DuplicateLoginIdException;
import com.ranikunlabs.platform.identity.application.port.IdentityAccount;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IdentityAccountRegistrationJpaAdapterTest {

    @Mock
    private PlatformAccountJpaRepository platformAccountRepository;

    @Mock
    private PasswordCredentialJpaRepository passwordCredentialRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    private IdentityAccountRegistrationJpaAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new IdentityAccountRegistrationJpaAdapter(
                platformAccountRepository, passwordCredentialRepository, passwordEncoder);
    }

    @Test
    void registersAccountAndCredentialWithOneEncodedPassword() {
        PlatformAccountEntity account = PlatformAccountEntity.create("manager@example.com");

        when(passwordCredentialRepository.existsByLoginId("manager1")).thenReturn(false);
        when(passwordEncoder.encode("raw-password")).thenReturn("encoded-hash");
        when(platformAccountRepository.save(any(PlatformAccountEntity.class))).thenReturn(account);

        IdentityAccount result = adapter.registerPasswordAccount(
                "manager1", "manager@example.com", "raw-password");

        // The entity owns its generated identifier; the returned contract must carry that same ID.
        assertThat(account.getId()).isNotNull();
        assertThat(result).isEqualTo(new IdentityAccount(account.getId(), "manager1", "encoded-hash"));
        verify(passwordEncoder).encode("raw-password");

        ArgumentCaptor<PlatformAccountEntity> accountCaptor =
                ArgumentCaptor.forClass(PlatformAccountEntity.class);
        verify(platformAccountRepository).save(accountCaptor.capture());
        assertThat(accountCaptor.getValue().getPrimaryEmail()).isEqualTo("manager@example.com");

        ArgumentCaptor<PasswordCredentialEntity> credentialCaptor =
                ArgumentCaptor.forClass(PasswordCredentialEntity.class);
        verify(passwordCredentialRepository).save(credentialCaptor.capture());
        assertThat(credentialCaptor.getValue().getAccountId()).isEqualTo(account.getId());
        assertThat(credentialCaptor.getValue().getLoginId()).isEqualTo("manager1");
        assertThat(credentialCaptor.getValue().getPasswordHash()).isEqualTo("encoded-hash");
        verify(passwordCredentialRepository).flush();
    }

    @Test
    void duplicateLoginIdFailsBeforeEncodingOrPersistence() {
        when(passwordCredentialRepository.existsByLoginId("manager1")).thenReturn(true);

        assertThatThrownBy(() -> adapter.registerPasswordAccount(
                "manager1", "manager@example.com", "raw-password"))
                .isInstanceOf(DuplicateLoginIdException.class);

        verifyNoInteractions(passwordEncoder);
        verify(platformAccountRepository, never()).save(any(PlatformAccountEntity.class));
        verify(passwordCredentialRepository, never()).save(any(PasswordCredentialEntity.class));
    }
}
