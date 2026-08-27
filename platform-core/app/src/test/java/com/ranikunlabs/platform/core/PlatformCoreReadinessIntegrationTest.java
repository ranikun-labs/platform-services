package com.ranikunlabs.platform.core;

import com.ranikunlabs.platform.identity.IdentityRuntimeProperties;
import com.ranikunlabs.platform.sharedai.SharedAiModuleConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        classes = PlatformCoreApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "platform.identity.datasource.url=jdbc:postgresql://postgres:5432/platform",
                "platform.identity.datasource.username=identity",
                "platform.identity.datasource.password=provided-at-runtime",
                "platform.identity.redis-host=redis",
                "platform.identity.redis-port=6379",
                "platform.identity.redis-key-prefix=platform:identity:",
                "platform.identity.auth.enabled=false",
                // This host-readiness test intentionally has no database dependency. The
                // Identity persistence slice is exercised by its PostgreSQL integration tests.
                "platform.identity.persistence.enabled=false",
                "spring.autoconfigure.exclude="
                        + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
                        + "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,"
                        + "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration"
        })
@AutoConfigureMockMvc
class PlatformCoreReadinessIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private IdentityRuntimeProperties identityRuntimeProperties;

    @Autowired
    private SharedAiModuleConfiguration sharedAiModuleConfiguration;

    @Test
    void hostsIdentityAndSharedAiAndExposesPlatformCoreReadiness() throws Exception {
        assertThat(identityRuntimeProperties.redisKeyPrefix()).isEqualTo("platform:identity:");
        assertThat(sharedAiModuleConfiguration).isNotNull();

        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }
}
