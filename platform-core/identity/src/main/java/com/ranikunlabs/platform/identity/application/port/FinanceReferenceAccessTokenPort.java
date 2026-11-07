package com.ranikunlabs.platform.identity.application.port;

/** Signs one Finance reference access token without creating a refresh session. */
public interface FinanceReferenceAccessTokenPort {

    IssuedFinanceReferenceAccessToken issue(String opaqueSubject);
}
