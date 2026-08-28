package com.ranikunlabs.platform.identity.adapter.oauth.kakao;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

import java.util.List;

/** Enables Kakao adapters only when the complete legacy provider configuration is present. */
public class KakaoOAuthConfiguredCondition implements Condition {

    private static final List<String> REQUIRED_PROPERTIES = List.of(
            "carelog.auth.oauth.kakao.client-id",
            "carelog.auth.oauth.kakao.authorization-uri",
            "carelog.auth.oauth.kakao.token-uri",
            "carelog.auth.oauth.kakao.user-info-uri",
            "oauth.redirect-uris.kakao.WEB",
            "oauth.redirect-uris.kakao.MOBILE",
            "carelog.auth.oauth.kakao.client-secret",
            "carelog.auth.oauth.kakao.connect-timeout",
            "carelog.auth.oauth.kakao.read-timeout"
    );

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        long configured = REQUIRED_PROPERTIES.stream()
                .filter(key -> hasText(context.getEnvironment().getProperty(key)))
                .count();
        if (configured == 0) {
            return false;
        }
        long requiredConfigured = REQUIRED_PROPERTIES.subList(0, 6).stream()
                .filter(key -> hasText(context.getEnvironment().getProperty(key)))
                .count();
        if (requiredConfigured != 6) {
            throw new IllegalStateException("Kakao OAuth settings must be provided together or omitted together");
        }
        return true;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
