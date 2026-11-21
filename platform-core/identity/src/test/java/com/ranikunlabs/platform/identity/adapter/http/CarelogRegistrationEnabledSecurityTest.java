package com.ranikunlabs.platform.identity.adapter.http;

import com.ranikunlabs.platform.identity.application.AuthService;
import com.ranikunlabs.platform.identity.application.oauth.OAuthAuthorizationService;
import com.ranikunlabs.platform.identity.application.oauth.OAuthLoginService;
import com.ranikunlabs.platform.identity.application.port.TokenProviderPort;
import com.ranikunlabs.platform.identity.application.registration.CarelogRegistrationCoordinator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.filter.DelegatingFilterProxy;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import tools.jackson.databind.json.JsonMapper;
import java.time.Clock;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringJUnitWebConfig(CarelogRegistrationEnabledSecurityTest.TestConfiguration.class)
@ActiveProfiles("dev")
@TestPropertySource(properties = "platform.identity.carelog-registration.enabled=true")
class CarelogRegistrationEnabledSecurityTest {
    @Autowired WebApplicationContext context;
    @Autowired CarelogRegistrationCoordinator coordinator;
    MockMvc mvc;
    @BeforeEach void setup() {
        reset(coordinator);
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilter(new DelegatingFilterProxy("springSecurityFilterChain", context)).build();
    }
    @Test void onlyExactPostIsPublicUnderExplicitDevConfiguration() throws Exception {
        when(coordinator.register(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(CarelogRegistrationCoordinator.Outcome.COMPLETED);
        mvc.perform(post(CarelogRegistrationController.PATH)
                        .header("Idempotency-Key", "abcdef12-3333-4333-8333-333333333333")
                        .contentType("application/json")
                        .content("{\"userId\":\"user\",\"email\":\"a@example.test\",\"password\":\"password\",\"name\":\"Name\"}"))
                .andExpect(status().isCreated());
        mvc.perform(get(CarelogRegistrationController.PATH)).andExpect(status().isUnauthorized());
        mvc.perform(post(CarelogRegistrationController.PATH + "/other")).andExpect(status().isUnauthorized());
    }
    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @Import(IdentityHttpConfiguration.class)
    static class TestConfiguration {
        @Bean AuthService authService() { return mock(AuthService.class); }
        @Bean OAuthAuthorizationService oauthAuthorizationService() { return mock(OAuthAuthorizationService.class); }
        @Bean OAuthLoginService oauthLoginService() { return mock(OAuthLoginService.class); }
        @Bean TokenProviderPort tokenProvider() { return mock(TokenProviderPort.class); }
        @Bean CarelogRegistrationCoordinator coordinator() { return mock(CarelogRegistrationCoordinator.class); }
        @Bean Clock clock() { return Clock.systemUTC(); }
        @Bean tools.jackson.databind.ObjectMapper objectMapper() { return JsonMapper.builder().build(); }
    }
}
