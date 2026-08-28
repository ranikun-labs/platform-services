package com.ranikunlabs.platform.identity;

import com.ranikunlabs.platform.identity.application.port.AuthTokenBundle;
import com.ranikunlabs.platform.identity.application.port.AuthenticatedIdentity;
import com.ranikunlabs.platform.identity.application.port.CredentialPort;
import com.ranikunlabs.platform.identity.application.port.DuplicateLoginIdException;
import com.ranikunlabs.platform.identity.application.port.IdentityAccountNotFoundException;
import com.ranikunlabs.platform.identity.application.port.IdentityAccountRegistrationPort;
import com.ranikunlabs.platform.identity.application.port.InvalidCredentialsException;
import com.ranikunlabs.platform.identity.application.port.PasswordCredentialUpdatePort;
import com.ranikunlabs.platform.identity.application.port.oauth.ClientChannel;
import com.ranikunlabs.platform.identity.application.port.oauth.OAuthAuthorizationCommand;
import com.ranikunlabs.platform.identity.application.port.oauth.OAuthLoginResult;
import com.ranikunlabs.platform.identity.application.port.oauth.PkceChallenge;
import com.ranikunlabs.platform.identity.application.port.productclient.ProductClientLookupException;
import com.ranikunlabs.platform.identity.application.port.productclient.ProductClientReader;
import com.ranikunlabs.platform.identity.core.AccountStatus;
import com.ranikunlabs.platform.identity.core.ExternalIdentityAssertion;
import com.ranikunlabs.platform.identity.core.Product;
import com.ranikunlabs.platform.identity.core.ProductClientChannel;
import com.ranikunlabs.platform.identity.core.ProductClientIdPolicy;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IdentityCoreContractTest {

    @Test
    void coreEnumsAndValuesRetainSourceSemantics() {
        assertArrayEquals(new AccountStatus[]{AccountStatus.ACTIVE, AccountStatus.INACTIVE}, AccountStatus.values());
        assertArrayEquals(new Product[]{Product.CARELOG, Product.FINANCE_HARNESS, Product.DEV_HARNESS}, Product.values());
        assertArrayEquals(
                new ProductClientChannel[]{
                        ProductClientChannel.WEB,
                        ProductClientChannel.MOBILE,
                        ProductClientChannel.IOS,
                        ProductClientChannel.ANDROID
                },
                ProductClientChannel.values()
        );

        UUID accountId = UUID.randomUUID();
        ExternalIdentityAssertion assertion =
                new ExternalIdentityAssertion("kakao", "provider-subject", "person@example.com");
        assertEquals("kakao", assertion.provider());
        assertEquals("provider-subject", assertion.providerSubject());
        assertEquals("person@example.com", assertion.emailSnapshot());

        AuthenticatedIdentity authenticated = new AuthenticatedIdentity(accountId, "manager01");
        assertEquals(accountId, authenticated.accountId());
        assertEquals("manager01", authenticated.loginId());
    }

    @Test
    void productClientIdentifierPolicyMatchesCanonicalRules() {
        assertTrue(ProductClientIdPolicy.isValid("carelog-web"));
        assertTrue(ProductClientIdPolicy.isValid("a"));
        assertTrue(ProductClientIdPolicy.isValid("a".repeat(100)));
        assertFalse(ProductClientIdPolicy.isValid(null));
        assertFalse(ProductClientIdPolicy.isValid("Carelog-web"));
        assertFalse(ProductClientIdPolicy.isValid("-carelog-web"));
        assertFalse(ProductClientIdPolicy.isValid("a".repeat(101)));
        assertEquals("carelog-web", ProductClientIdPolicy.requireValid("carelog-web"));
        assertThrows(IllegalArgumentException.class, () -> ProductClientIdPolicy.requireValid("bad id"));
    }

    @Test
    void credentialPortDoesNotExposeProductPrincipal() throws NoSuchMethodException {
        Method authenticate = CredentialPort.class.getMethod("authenticate", String.class, String.class);

        assertEquals(AuthenticatedIdentity.class, authenticate.getReturnType());
        assertArrayEquals(new Class<?>[]{InvalidCredentialsException.class}, authenticate.getExceptionTypes());
    }

    @Test
    void identityPortsExposeCanonicalTypedFailures() throws NoSuchMethodException {
        Method register = IdentityAccountRegistrationPort.class.getMethod(
                "registerPasswordAccount", String.class, String.class, String.class);
        Method update = PasswordCredentialUpdatePort.class.getMethod(
                "updatePassword", UUID.class, String.class);
        Method clientLookup = ProductClientReader.class.getMethod("requireEnabled", String.class);

        assertArrayEquals(new Class<?>[]{DuplicateLoginIdException.class}, register.getExceptionTypes());
        assertArrayEquals(new Class<?>[]{IdentityAccountNotFoundException.class}, update.getExceptionTypes());
        assertArrayEquals(new Class<?>[]{ProductClientLookupException.class}, clientLookup.getExceptionTypes());
    }

    @Test
    void credentialFailureHasOneEnumerationSafeMeaning() {
        InvalidCredentialsException missingLogin = new InvalidCredentialsException();
        InvalidCredentialsException wrongPassword = new InvalidCredentialsException();

        assertEquals(missingLogin.getClass(), wrongPassword.getClass());
        assertNull(missingLogin.getMessage());
        assertNull(wrongPassword.getMessage());
    }

    @Test
    void productClientFailureReasonsMatchCanonicalSource() {
        assertArrayEquals(
                new ProductClientLookupException.Reason[]{
                        ProductClientLookupException.Reason.INVALID_PRODUCT_CLIENT_ID,
                        ProductClientLookupException.Reason.UNKNOWN_PRODUCT_CLIENT,
                        ProductClientLookupException.Reason.DISABLED_PRODUCT_CLIENT
                },
                ProductClientLookupException.Reason.values()
        );

        ProductClientLookupException failure = new ProductClientLookupException(
                ProductClientLookupException.Reason.DISABLED_PRODUCT_CLIENT);
        assertEquals(ProductClientLookupException.Reason.DISABLED_PRODUCT_CLIENT, failure.reason());
        assertThrows(NullPointerException.class, () -> new ProductClientLookupException(null));
    }

    @Test
    void oauthAuthorizationCompatibilityConstructorPreservesMissingClientId() {
        OAuthAuthorizationCommand command = new OAuthAuthorizationCommand("kakao", ClientChannel.WEB, "/home");

        assertEquals("kakao", command.provider());
        assertEquals(ClientChannel.WEB, command.clientChannel());
        assertEquals("/home", command.returnTo());
        assertNull(command.clientId());
    }

    @Test
    void oauthResultCarriesTokensWithoutFrameworkTypes() {
        AuthTokenBundle tokens = new AuthTokenBundle("access", "refresh");
        UUID accountId = UUID.randomUUID();
        OAuthLoginResult result = new OAuthLoginResult.ExistingAccountAuthenticated(accountId, tokens);

        assertEquals(accountId, ((OAuthLoginResult.ExistingAccountAuthenticated) result).accountId());
        assertEquals(tokens, ((OAuthLoginResult.ExistingAccountAuthenticated) result).tokens());
    }

    @Test
    void generatedPkceUsesRfc7636S256() throws Exception {
        PkceChallenge challenge = PkceChallenge.generate();

        assertEquals("S256", challenge.method());
        assertEquals(43, challenge.codeVerifier().length());
        assertEquals(43, challenge.codeChallenge().length());
        assertTrue(challenge.codeVerifier().matches("[A-Za-z0-9_-]+"));
        assertTrue(challenge.codeChallenge().matches("[A-Za-z0-9_-]+"));
        assertEquals(expectedS256(challenge.codeVerifier()), challenge.codeChallenge());
        assertNotEquals(challenge, PkceChallenge.generate());
    }

    private static String expectedS256(String verifier) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(verifier.getBytes(StandardCharsets.US_ASCII));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    }
}
