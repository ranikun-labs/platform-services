package com.ranikunlabs.platform.identity;

import com.ranikunlabs.platform.identity.adapter.persistence.PasswordCredentialUpdateJpaAdapter;
import com.ranikunlabs.platform.identity.adapter.persistence.entity.PasswordCredentialEntity;
import com.ranikunlabs.platform.identity.adapter.persistence.repository.PasswordCredentialJpaRepository;
import com.ranikunlabs.platform.identity.application.port.IdentityAccountNotFoundException;
import com.ranikunlabs.platform.identity.application.port.UpdatedPasswordCredential;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PasswordCredentialUpdateJpaAdapterTest {

    @Mock
    private PasswordCredentialJpaRepository passwordCredentialRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    private PasswordCredentialUpdateJpaAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new PasswordCredentialUpdateJpaAdapter(passwordCredentialRepository, passwordEncoder);
    }

    @Test
    void updatesCredentialByAccountIdAndEncodesExactlyOnce() {
        UUID accountId = UUID.randomUUID();
        PasswordCredentialEntity credential =
                PasswordCredentialEntity.create(accountId, "manager1", "old-hash");
        when(passwordCredentialRepository.findById(accountId)).thenReturn(Optional.of(credential));
        when(passwordEncoder.encode("new-password")).thenReturn("new-hash");

        UpdatedPasswordCredential result = adapter.updatePassword(accountId, "new-password");

        assertThat(result).isEqualTo(new UpdatedPasswordCredential("new-hash"));
        assertThat(credential.getPasswordHash()).isEqualTo("new-hash");
        verify(passwordEncoder).encode("new-password");
        verify(passwordCredentialRepository).save(credential);
    }

    @Test
    void missingCredentialFailsBeforeEncodingOrSave() {
        UUID accountId = UUID.randomUUID();
        when(passwordCredentialRepository.findById(accountId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> adapter.updatePassword(accountId, "new-password"))
                .isInstanceOf(IdentityAccountNotFoundException.class);

        verifyNoInteractions(passwordEncoder);
        verify(passwordCredentialRepository, never()).save(any(PasswordCredentialEntity.class));
    }
}
