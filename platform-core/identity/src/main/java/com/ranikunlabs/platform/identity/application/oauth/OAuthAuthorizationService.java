package com.ranikunlabs.platform.identity.application.oauth;

import com.ranikunlabs.platform.identity.application.port.oauth.OAuthAuthorizationCommand;
import com.ranikunlabs.platform.identity.application.port.oauth.OAuthAuthorizationRequest;
import com.ranikunlabs.platform.identity.application.port.oauth.OAuthProviderPort;
import com.ranikunlabs.platform.identity.application.port.oauth.OAuthStateRecord;
import com.ranikunlabs.platform.identity.application.port.oauth.OAuthStateStore;
import com.ranikunlabs.platform.identity.application.port.oauth.PkceChallenge;
import com.ranikunlabs.platform.identity.application.port.productclient.RegisteredProductClient;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

/** Generates and stores the server-side state and PKCE material needed to start an OAuth authorization. */
@Service
public class OAuthAuthorizationService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final OAuthProviderRegistry providerRegistry;
    private final OAuthRedirectUriResolver redirectUriResolver;
    private final OAuthProductClientCompatibilityResolver productClientCompatibilityResolver;
    private final ReturnToValidator returnToValidator;
    private final OAuthStateStore stateStore;
    private final Environment environment;
    private final Clock clock;

    public OAuthAuthorizationService(
            OAuthProviderRegistry providerRegistry,
            OAuthRedirectUriResolver redirectUriResolver,
            OAuthProductClientCompatibilityResolver productClientCompatibilityResolver,
            ReturnToValidator returnToValidator,
            OAuthStateStore stateStore,
            Environment environment,
            Clock clock
    ) {
        this.providerRegistry = providerRegistry;
        this.redirectUriResolver = redirectUriResolver;
        this.productClientCompatibilityResolver = productClientCompatibilityResolver;
        this.returnToValidator = returnToValidator;
        this.stateStore = stateStore;
        this.environment = environment;
        this.clock = clock;
    }

    public AuthorizationUrlResult startAuthorization(OAuthAuthorizationCommand command) {
        // Validate the client fail-closed before selecting a provider adapter or building any external URL.
        RegisteredProductClient client = productClientCompatibilityResolver.resolve(command);
        OAuthProviderPort provider = providerRegistry.resolve(command.provider());
        String providerCode = OAuthProviderRegistry.normalize(provider.providerCode());
        URI redirectUri = redirectUriResolver.resolve(providerCode, client);
        String returnTo = returnToValidator.validate(command.returnTo());
        String state = generateState();
        PkceChallenge pkce = provider.supportsPkce() ? PkceChallenge.generate() : null;
        String nonce = provider.requiresNonce() ? generateRandomValue() : null;

        OAuthStateRecord record = new OAuthStateRecord(
                providerCode,
                redirectUri,
                returnTo,
                pkce != null ? pkce.codeVerifier() : null,
                nonce,
                Instant.now(clock)
        );
        stateStore.save(state, record, stateTtl());

        URI authorizationUrl = provider.buildAuthorizationUrl(new OAuthAuthorizationRequest(
                providerCode,
                redirectUri,
                state,
                pkce != null ? pkce.codeChallenge() : null,
                pkce != null ? pkce.method() : null,
                nonce
        ));
        return new AuthorizationUrlResult(authorizationUrl, state);
    }

    private String generateState() {
        return generateRandomValue();
    }

    private String generateRandomValue() {
        byte[] stateBytes = new byte[32];
        SECURE_RANDOM.nextBytes(stateBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(stateBytes);
    }

    private Duration stateTtl() {
        return DurationStyle.detectAndParse(environment.getProperty("oauth.state.ttl", "5m"));
    }

    /** Authorization-start result safe to expose to an API response. */
    public record AuthorizationUrlResult(URI authorizationUrl, String state) {
    }
}
