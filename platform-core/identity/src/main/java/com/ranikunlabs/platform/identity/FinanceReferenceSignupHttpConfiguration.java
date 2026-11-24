package com.ranikunlabs.platform.identity;

import com.ranikunlabs.platform.identity.adapter.http.FinanceReferenceSignupController;
import com.ranikunlabs.platform.identity.application.FinanceReferenceSignupService;
import com.ranikunlabs.platform.identity.application.port.FinanceReferenceAccountRegistrationPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** Explicit DEV opt-in for public Finance signup. Absent unless signup is enabled on the fixture composition. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "platform.identity.finance-reference.signup.enabled", havingValue = "true")
@Import(FinanceReferenceSignupController.class)
class FinanceReferenceSignupHttpConfiguration {

    @Bean
    FinanceReferenceSignupService financeReferenceSignupService(FinanceReferenceAccountRegistrationPort accounts) {
        return new FinanceReferenceSignupService(accounts);
    }
}
