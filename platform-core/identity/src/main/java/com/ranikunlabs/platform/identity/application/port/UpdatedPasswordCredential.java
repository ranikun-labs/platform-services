package com.ranikunlabs.platform.identity.application.port;

/**
 * Password update result without exposing a persistence entity.
 *
 * <p>The encoded value is retained for the legacy product projection only; raw passwords never
 * cross this contract.
 */
public record UpdatedPasswordCredential(String encodedPassword) {
}
