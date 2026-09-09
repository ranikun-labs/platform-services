package com.ranikunlabs.platform.identity.adapter.jdbc;

import com.ranikunlabs.platform.identity.reconciliation.ReconciliationModel.SourceKind;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Connection details for one offline source inspection.
 *
 * <p>The password is retained only as a defensive copy and is never included in the textual
 * representation of this value. This configuration is deliberately not a Spring-managed
 * connection-pool contract so an inspector can be invoked without normal application startup.
 */
public final class JdbcSourceConfiguration {

    private static final Set<String> CREDENTIAL_URL_PROPERTIES = Set.of(
            "password",
            "user",
            "sslpassword",
            "sslkeypassword",
            "gsscredential",
            "oauthaccesstoken"
    );

    private final String logicalName;
    private final SourceKind sourceKind;
    private final String jdbcUrl;
    private final String username;
    private final char[] password;

    public JdbcSourceConfiguration(
            String logicalName,
            SourceKind sourceKind,
            String jdbcUrl,
            String username,
            char[] password
    ) {
        this.logicalName = requireText(logicalName, "logicalName");
        this.sourceKind = Objects.requireNonNull(sourceKind, "sourceKind must not be null");
        this.jdbcUrl = requireText(jdbcUrl, "jdbcUrl");
        rejectCredentialBearingUrl(this.jdbcUrl);
        this.username = requireText(username, "username");
        this.password = Objects.requireNonNull(password, "password must not be null").clone();
    }

    public String logicalName() {
        return logicalName;
    }

    public SourceKind sourceKind() {
        return sourceKind;
    }

    String jdbcUrl() {
        return jdbcUrl;
    }

    String username() {
        return username;
    }

    char[] passwordCopy() {
        return password.clone();
    }

    @Override
    public String toString() {
        return "JdbcSourceConfiguration[logicalName=" + logicalName + ", sourceKind=" + sourceKind + "]";
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private static void rejectCredentialBearingUrl(String url) {
        int authorityStart = url.indexOf("://");
        if (authorityStart >= 0) {
            int authorityEnd = url.length();
            for (char delimiter : new char[]{'/', '?', '#'}) {
                int candidate = url.indexOf(delimiter, authorityStart + 3);
                if (candidate >= 0) {
                    authorityEnd = Math.min(authorityEnd, candidate);
                }
            }
            if (url.substring(authorityStart + 3, authorityEnd).contains("@")) {
                throw credentialUrlRejected();
            }
        }

        int queryStart = url.indexOf('?');
        if (queryStart < 0 || queryStart == url.length() - 1) {
            return;
        }
        String query = url.substring(queryStart + 1);
        int fragmentStart = query.indexOf('#');
        if (fragmentStart >= 0) {
            query = query.substring(0, fragmentStart);
        }
        final String decodedQuery;
        try {
            decodedQuery = URLDecoder.decode(query, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException malformedEncoding) {
            throw credentialUrlRejected();
        }
        for (String property : decodedQuery.split("[&;]")) {
            int separator = property.indexOf('=');
            String key = separator >= 0 ? property.substring(0, separator) : property;
            if (CREDENTIAL_URL_PROPERTIES.contains(key.trim().toLowerCase(Locale.ROOT))) {
                throw credentialUrlRejected();
            }
        }
    }

    private static IllegalArgumentException credentialUrlRejected() {
        return new IllegalArgumentException(
                "jdbcUrl must not contain credential-bearing properties or user information");
    }
}
