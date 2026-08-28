package com.ranikunlabs.platform.identity.application.oauth;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/** Configured OAuth callback URI per provider and product-client combination. */
@ConfigurationProperties("oauth")
public class OAuthProviderCallbackProperties {

    private Map<String, Map<String, String>> providerCallbacks = new LinkedHashMap<>();

    public Map<String, Map<String, String>> getProviderCallbacks() {
        return providerCallbacks;
    }

    public void setProviderCallbacks(Map<String, Map<String, String>> providerCallbacks) {
        this.providerCallbacks = providerCallbacks;
    }

    public String find(String provider, String clientId) {
        return providerCallbacks.getOrDefault(provider, Map.of()).get(clientId);
    }
}
