package com.ranikunlabs.platform.identity.application;

import com.ranikunlabs.platform.identity.application.port.AuthTokenBundle;

import java.util.UUID;

/** Identity authentication use cases independent of HTTP transport contracts. */
public interface AuthService {

    AuthTokenBundle login(String loginId, String password);

    AuthTokenBundle refreshToken(String rawRefreshToken);

    void logout(UUID accountId, String accessToken);
}
