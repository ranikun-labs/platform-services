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

import java.net.URI;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

/** Creates server-side OAuth state and provider authorization requests. */
public class OAuthAuthorizationService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final OAuthProviderRegistry providerRegistry;
    private final OAuthRedirectUriResolver redirectUriResolver;
    private final OAuthProductClientCompatibilityResolver productClientCompatibilityResolver;
    private final ReturnToValidator returnToValidator;
    private final OAuthStateStore stateStore;
    private final Environment environment;
    private final Clock clock;

    public OAuthAuthorizationService(OAuthProviderRegistry providerRegistry,
                                     OAuthRedirectUriResolver redirectUriResolver,
                                     OAuthProductClientCompatibilityResolver productClientCompatibilityResolver,
                                     ReturnToValidator returnToValidator,
                                     OAuthStateStore stateStore,
                                     Environment environment,
                                     Clock clock) {
        this.providerRegistry = providerRegistry;
        this.redirectUriResolver = redirectUriResolver;
        this.productClientCompatibilityResolver = productClientCompatibilityResolver;
        this.returnToValidator = returnToValidator;
        this.stateStore = stateStore;
        this.environment = environment;
        this.clock = clock;
    }

    public AuthorizationUrlResult startAuthorization(OAuthAuthorizationCommand command) {
        // Resolve the registered client before selecting a provider or constructing its URL.
        RegisteredProductClient client = productClientCompatibilityResolver.resolve(command);
        OAuthProviderPort provider = providerRegistry.resolve(command.provider());
        String providerCode = OAuthProviderRegistry.normalize(provider.providerCode());
        URI redirectUri = redirectUriResolver.resolve(providerCode, client);
        String returnTo = returnToValidator.validate(command.returnTo());
        String state = generateRandomValue();
        PkceChallenge pkce = provider.supportsPkce() ? PkceChallenge.generate() : null;
        String nonce = provider.requiresNonce() ? generateRandomValue() : null;

        OAuthStateRecord record = new OAuthStateRecord(
                providerCode,
                redirectUri,
                returnTo,
                pkce == null ? null : pkce.codeVerifier(),
                nonce,
                Instant.now(clock)
        );
        stateStore.save(state, record, stateTtl());

        URI authorizationUrl = provider.buildAuthorizationUrl(new OAuthAuthorizationRequest(
                providerCode,
                redirectUri,
                state,
                pkce == null ? null : pkce.codeChallenge(),
                pkce == null ? null : pkce.method(),
                nonce
        ));
        return new AuthorizationUrlResult(authorizationUrl, state);
    }

    private String generateRandomValue() {
        byte[] value = new byte[32];
        SECURE_RANDOM.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private Duration stateTtl() {
        return DurationStyle.detectAndParse(environment.getProperty("oauth.state.ttl", "5m"));
    }

    /** Safe result for an authorization-start response; the PKCE verifier stays server-side. */
    public record AuthorizationUrlResult(URI authorizationUrl, String state) {
    }
}
