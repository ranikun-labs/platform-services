package com.ranikunlabs.platform.identity.application.oauth;

import com.ranikunlabs.platform.identity.application.port.oauth.OAuthAuthorizationRequestException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReturnToValidatorTest {

    private ReturnToValidator validatorWithAllowedOrigin(String origin) {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("oauth.allowed-return-origins[0]", origin);
        return new ReturnToValidator(environment);
    }

    @Test
    void allowsASafeRelativePath() {
        ReturnToValidator validator = validatorWithAllowedOrigin("https://carelog.example");

        assertThat(validator.validate("/dashboard")).isEqualTo("/dashboard");
    }

    @Test
    void allowsAnAbsoluteUriOnTheConfiguredOrigin() {
        ReturnToValidator validator = validatorWithAllowedOrigin("https://carelog.example");

        assertThat(validator.validate("https://carelog.example/dashboard")).isEqualTo("https://carelog.example/dashboard");
    }

    @Test
    void rejectsAnAbsoluteUriOnAnUnlistedOrigin() {
        ReturnToValidator validator = validatorWithAllowedOrigin("https://carelog.example");

        assertThatThrownBy(() -> validator.validate("https://evil.example/dashboard"))
                .isInstanceOf(OAuthAuthorizationRequestException.class)
                .satisfies(e -> assertThat(((OAuthAuthorizationRequestException) e).reason())
                        .isEqualTo(OAuthAuthorizationRequestException.Reason.INVALID_OAUTH_RETURN_TO));
    }

    @Test
    void rejectsProtocolRelativePathsThatWouldRedirectOffOrigin() {
        ReturnToValidator validator = validatorWithAllowedOrigin("https://carelog.example");

        assertThatThrownBy(() -> validator.validate("//evil.example/dashboard"))
                .isInstanceOf(OAuthAuthorizationRequestException.class);
    }

    @Test
    void rejectsEncodedSlashesUsedToDisguiseAnAbsoluteRedirect() {
        ReturnToValidator validator = validatorWithAllowedOrigin("https://carelog.example");

        assertThatThrownBy(() -> validator.validate("/%2f evil.example"))
                .isInstanceOf(OAuthAuthorizationRequestException.class);
    }

    @Test
    void rejectsBackslashesUsedToDisguiseAnAbsoluteRedirect() {
        ReturnToValidator validator = validatorWithAllowedOrigin("https://carelog.example");

        assertThatThrownBy(() -> validator.validate("/\\evil.example"))
                .isInstanceOf(OAuthAuthorizationRequestException.class);
    }

    @Test
    void rejectsBlankReturnTo() {
        ReturnToValidator validator = validatorWithAllowedOrigin("https://carelog.example");

        assertThatThrownBy(() -> validator.validate(" "))
                .isInstanceOf(OAuthAuthorizationRequestException.class);
    }

    @Test
    void rejectsNullReturnTo() {
        ReturnToValidator validator = validatorWithAllowedOrigin("https://carelog.example");

        assertThatThrownBy(() -> validator.validate(null))
                .isInstanceOf(OAuthAuthorizationRequestException.class);
    }
}
