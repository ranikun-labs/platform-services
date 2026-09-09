package com.ranikunlabs.platform.identity.adapter.jdbc;

/** Safe, source-scoped failure for an offline inspection operation. */
public final class SourceInspectionException extends RuntimeException {

    private final String logicalName;

    public SourceInspectionException(String logicalName, Throwable cause) {
        super("Unable to inspect configured source '" + logicalName + "'", cause);
        this.logicalName = logicalName;
    }

    public String logicalName() {
        return logicalName;
    }
}
