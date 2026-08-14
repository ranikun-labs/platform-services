package com.ranikunlabs.platform.gateway.config

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import org.springframework.mock.env.MockEnvironment
import org.springframework.boot.SpringApplication
import org.springframework.boot.WebApplicationType
import com.ranikunlabs.platform.gateway.GatewayApplication

class GatewayEnvironmentAliasesTest {

    @Test
    fun `canonical value is selected when legacy alias is absent`() {
        val environment = requiredEnvironment()
            .withProperty("PLATFORM_GATEWAY_REDIS_HOST", "canonical-redis")

        val resolved = GatewayEnvironmentAliases.resolve(environment)

        assertThat(resolved["PLATFORM_GATEWAY_REDIS_HOST"]).isEqualTo("canonical-redis")
    }

    @Test
    fun `legacy value is selected only as an explicit migration alias`() {
        val environment = requiredEnvironment()
            .withProperty("REDIS_HOST", "legacy-redis")

        val resolved = GatewayEnvironmentAliases.resolve(environment)

        assertThat(resolved["PLATFORM_GATEWAY_REDIS_HOST"]).isEqualTo("legacy-redis")
    }

    @Test
    fun `equal canonical and legacy values are accepted`() {
        val environment = requiredEnvironment()
            .withProperty("PLATFORM_GATEWAY_REDIS_HOST", "same-redis")
            .withProperty("REDIS_HOST", "same-redis")

        assertThat(GatewayEnvironmentAliases.resolve(environment)["PLATFORM_GATEWAY_REDIS_HOST"])
            .isEqualTo("same-redis")
    }

    @Test
    fun `different canonical and legacy values fail without exposing either value`() {
        val environment = requiredEnvironment()
            .withProperty("PLATFORM_GATEWAY_REDIS_HOST", "canonical-secret-host")
            .withProperty("REDIS_HOST", "legacy-secret-host")

        val thrown = catchThrowable { GatewayEnvironmentAliases.resolve(environment) }

        assertThat(thrown).isInstanceOf(IllegalStateException::class.java)
        assertThat(thrown.message)
            .contains("CONFIG_ALIAS_CONFLICT")
            .doesNotContain("canonical-secret-host")
            .doesNotContain("legacy-secret-host")
    }

    @Test
    fun `raw alias conflict is detected before placeholder resolution`() {
        val environment = requiredEnvironment()
            .withProperty("PLATFORM_GATEWAY_REDIS_HOST", "\${RPL53_ALIAS_VALUE}")
            .withProperty("REDIS_HOST", "127.0.0.1")
            .withProperty("RPL53_ALIAS_VALUE", "127.0.0.1")

        val thrown = catchThrowable { GatewayEnvironmentAliases.resolve(environment) }

        assertThat(thrown)
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("CONFIG_ALIAS_CONFLICT")
    }

    @Test
    fun `identical raw aliases are accepted and resolved only after comparison`() {
        val rawValue = "\${RPL53_ALIAS_VALUE}"
        val environment = requiredEnvironment()
            .withProperty("PLATFORM_GATEWAY_REDIS_HOST", rawValue)
            .withProperty("REDIS_HOST", rawValue)
            .withProperty("RPL53_ALIAS_VALUE", "127.0.0.1")

        assertThat(GatewayEnvironmentAliases.resolve(environment)["PLATFORM_GATEWAY_REDIS_HOST"])
            .isEqualTo("127.0.0.1")
    }

    @Test
    fun `missing required values fail before runtime binding`() {
        val environment = MockEnvironment()

        assertThatThrownBy { GatewayEnvironmentAliases.resolve(environment) }
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `missing required values stop Spring startup before application context`() {
        val application = SpringApplication(GatewayApplication::class.java).apply {
            webApplicationType = WebApplicationType.NONE
        }

        assertThatThrownBy {
            application.run(
                "--PLATFORM_GATEWAY_PORT=0",
                "--PLATFORM_GATEWAY_REDIS_HOST=127.0.0.1",
                "--PLATFORM_GATEWAY_REDIS_PORT=6379",
                "--PLATFORM_GATEWAY_REDIS_KEY_PREFIX=platform:gateway:",
                "--PLATFORM_GATEWAY_CARELOG_BE_URI=http://127.0.0.1:8080",
                "--PLATFORM_GATEWAY_INTERNAL_SECRET=internal-secret",
                "--PLATFORM_GATEWAY_JWT_VERIFICATION_SECRET=platform-gateway-secret-key-long-enough"
            )
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("CONFIG_ALIAS_MISSING")
    }

    @Test
    fun `environment postprocessor publishes the resolved canonical aliases`() {
        val environment = requiredEnvironment()
            .withProperty("REDIS_HOST", "legacy-redis")

        GatewayEnvironmentPostProcessor().postProcessEnvironment(
            environment,
            SpringApplication(GatewayApplication::class.java)
        )

        assertThat(environment.getProperty("PLATFORM_GATEWAY_REDIS_HOST"))
            .isEqualTo("legacy-redis")
    }

    @Test
    fun `conflicting aliases stop Spring startup before the application context`() {
        val application = SpringApplication(GatewayApplication::class.java).apply {
            webApplicationType = WebApplicationType.NONE
        }

        assertThatThrownBy {
            application.run(
                "--PLATFORM_GATEWAY_PORT=9000",
                "--PLATFORM_GATEWAY_REDIS_HOST=canonical-redis",
                "--REDIS_HOST=legacy-redis",
                "--PLATFORM_GATEWAY_REDIS_PORT=6379",
                "--PLATFORM_GATEWAY_REDIS_KEY_PREFIX=platform:gateway:",
                "--PLATFORM_GATEWAY_CARELOG_BE_URI=http://carelog-be",
                "--PLATFORM_GATEWAY_CARELOG_RAG_URI=http://carelog-rag",
                "--PLATFORM_GATEWAY_INTERNAL_SECRET=internal-secret",
                "--PLATFORM_GATEWAY_JWT_VERIFICATION_SECRET=platform-gateway-secret-key-long-enough"
            )
        }.isInstanceOf(IllegalStateException::class.java)
    }

    private fun requiredEnvironment(): MockEnvironment = MockEnvironment()
        .withProperty("PLATFORM_GATEWAY_PORT", "9000")
        .withProperty("PLATFORM_GATEWAY_REDIS_PORT", "6379")
        .withProperty("PLATFORM_GATEWAY_REDIS_KEY_PREFIX", "platform:gateway:")
        .withProperty("PLATFORM_GATEWAY_CARELOG_BE_URI", "http://carelog-be")
        .withProperty("PLATFORM_GATEWAY_CARELOG_RAG_URI", "http://carelog-rag")
        .withProperty("PLATFORM_GATEWAY_INTERNAL_SECRET", "internal-secret")
        .withProperty("PLATFORM_GATEWAY_JWT_VERIFICATION_SECRET", "jwt-secret")
}
