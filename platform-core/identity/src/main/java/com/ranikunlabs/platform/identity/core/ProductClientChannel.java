package com.ranikunlabs.platform.identity.core;

/** OAuth request channel allowed for a registered product client. */
public enum ProductClientChannel {
    WEB,
    /** Compatibility channel for the existing generic mobile authorization API. */
    MOBILE,
    /** Explicit iOS channel for registered clients. */
    IOS,
    /** Explicit Android channel for registered clients. */
    ANDROID
}
