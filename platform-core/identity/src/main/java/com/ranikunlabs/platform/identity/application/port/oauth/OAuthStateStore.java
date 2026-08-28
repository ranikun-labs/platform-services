package com.ranikunlabs.platform.identity.application.port.oauth;

import java.time.Duration;
import java.util.Optional;

/** Boundary for saving and atomically consuming one-time OAuth state. */
public interface OAuthStateStore {

    void save(String state, OAuthStateRecord record, Duration ttl);

    /** Empty means missing, expired, or already consumed; store outages use the typed exception. */
    Optional<OAuthStateRecord> consume(String state);
}
