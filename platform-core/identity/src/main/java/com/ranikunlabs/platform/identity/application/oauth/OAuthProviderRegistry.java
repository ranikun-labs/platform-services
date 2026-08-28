package com.ranikunlabs.platform.identity.application.oauth;

import com.ranikunlabs.platform.identity.application.port.oauth.OAuthProviderPort;
import com.ranikunlabs.platform.identity.application.port.oauth.OAuthRequestException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Resolves registered provider ports by their canonical, lower-case provider code. */
public class OAuthProviderRegistry {

    private final Map<String, OAuthProviderPort> providers;

    public OAuthProviderRegistry(List<OAuthProviderPort> providerPorts) {
        Map<String, OAuthProviderPort> resolved = new HashMap<>();
        for (OAuthProviderPort providerPort : providerPorts) {
            String code = normalize(providerPort.providerCode());
            if (resolved.putIfAbsent(code, providerPort) != null) {
                throw new IllegalStateException("Duplicate OAuth provider registration");
            }
        }
        this.providers = Map.copyOf(resolved);
    }

    public OAuthProviderPort resolve(String providerCode) {
        OAuthProviderPort provider = providers.get(normalize(providerCode));
        if (provider == null) {
            throw new OAuthRequestException(OAuthRequestException.Reason.UNSUPPORTED_OAUTH_PROVIDER);
        }
        return provider;
    }

    public static String normalize(String providerCode) {
        if (providerCode == null || providerCode.isBlank()) {
            return "";
        }
        return providerCode.trim().toLowerCase(Locale.ROOT);
    }
}
