package com.ranikunlabs.platform.identity.application.port;

import java.time.Duration;

/** Boundary for registering a revoked access token for a bounded TTL. */
public interface TokenBlacklistPort {

    void addToBlacklist(String token, Duration ttl);
}
