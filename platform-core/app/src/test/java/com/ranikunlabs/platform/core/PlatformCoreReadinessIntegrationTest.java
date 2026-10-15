package com.ranikunlabs.platform.core;

import com.ranikunlabs.platform.identity.adapter.http.AuthController;
import com.ranikunlabs.platform.identity.IdentityRuntimeProperties;
import com.ranikunlabs.platform.sharedai.SharedAiModuleConfiguration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;

import static org.assertj.core.api.Assertions.assertThat;

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
                        + "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration",
                "management.server.port=0"
        })
class PlatformCoreReadinessIntegrationTest {

    @Autowired
    private Environment environment;

    @Autowired
    private IdentityRuntimeProperties identityRuntimeProperties;

    @Autowired
    private SharedAiModuleConfiguration sharedAiModuleConfiguration;

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void hostsIdentityAndSharedAiAndExposesReadinessOnlyOnTheManagementListener() throws Exception {
        assertThat(identityRuntimeProperties.redisKeyPrefix()).isEqualTo("platform:identity:");
        assertThat(sharedAiModuleConfiguration).isNotNull();
        assertThat(applicationContext.getBeansOfType(AuthController.class)).isEmpty();

        int applicationPort = environment.getRequiredProperty("local.server.port", Integer.class);
        int managementPort = environment.getRequiredProperty("local.management.port", Integer.class);
        HttpClient client = HttpClient.newHttpClient();

        HttpResponse<String> readiness = get(client, managementPort);
        HttpResponse<String> applicationPortProbe = get(client, applicationPort);

        assertThat(readiness.statusCode()).isEqualTo(200);
        assertThat(readiness.body()).contains("\"status\":\"UP\"");
        assertThat(applicationPortProbe.statusCode()).isBetween(400, 499);
    }

    private static HttpResponse<String> get(HttpClient client, int port) throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                        + "/actuator/health/readiness")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
