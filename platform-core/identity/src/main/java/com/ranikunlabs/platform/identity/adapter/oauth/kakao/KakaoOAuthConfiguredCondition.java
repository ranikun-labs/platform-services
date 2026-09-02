package com.ranikunlabs.platform.identity.adapter.oauth.kakao;

import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;


/**
 * Distinguishes "no Kakao configuration at all" from "some Kakao configuration".
 *
 * <p>Gating on a single key would let a deployment that set, say, only {@code oauth.kakao.token-uri}
 * boot with Kakao quietly absent, turning a misconfiguration into a silently disabled login. Any
 * externally supplied {@code oauth.kakao.*} property is treated as intent to run Kakao, after which
 * {@link KakaoOAuthProperties} validation decides whether the configuration is usable — so an
 * incomplete one stops startup.
 *
 * <p>The check binds the property prefix rather than reading the bound properties object, because
 * the latter carries this module's own endpoint and timeout defaults and would make an entirely
 * unconfigured deployment look operator-configured. Binding also applies relaxed name matching, so
 * environment-variable forms such as {@code OAUTH_KAKAO_CLIENT_ID} count as configuration.
 */
class KakaoOAuthConfiguredCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return Binder.get(context.getEnvironment())
                .bind("oauth.kakao", Bindable.mapOf(String.class, String.class))
                .map(values -> !values.isEmpty())
                .orElse(false);
    }
}
