package com.ranikunlabs.platform.gateway.config

import com.ranikunlabs.platform.gateway.GatewayApplication
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.SpringApplication
import org.springframework.boot.WebApplicationType
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension

@ExtendWith(OutputCaptureExtension::class)
class GatewaySecretConfigurationTest {

    @Test
    fun `weak JWT secret fails with an application-owned safe error`() {
        val weakSecret = "weak-secret-for-regression"

        assertThatThrownBy { GatewayJwtKeyFactory.create(weakSecret) }
            .isInstanceOf(GatewaySecretConfigurationException::class.java)
            .hasMessage("Invalid configuration for PLATFORM_GATEWAY_JWT_VERIFICATION_SECRET: invalid secret")
            .doesNotHaveToString(weakSecret)
    }

    @Test
    fun `startup failure does not disclose the secret or derived key size`(output: CapturedOutput) {
        val weakSecret = "weak-secret-startup-regression"
        val application = SpringApplication(GatewayApplication::class.java).apply {
            webApplicationType = WebApplicationType.NONE
        }

        val failure = catchThrowable {
            application.run(
                "--PLATFORM_GATEWAY_PORT=0",
                "--PLATFORM_GATEWAY_REDIS_HOST=127.0.0.1",
                "--PLATFORM_GATEWAY_REDIS_PORT=6379",
                "--PLATFORM_GATEWAY_REDIS_KEY_PREFIX=platform:gateway:",
                "--PLATFORM_GATEWAY_CARELOG_BE_URI=http://127.0.0.1:8080",
                "--PLATFORM_GATEWAY_FINANCE_BE_URI=http://localhost:8082",
                "--PLATFORM_GATEWAY_CARELOG_RAG_URI=http://127.0.0.1:8081",
                "--PLATFORM_GATEWAY_INTERNAL_SECRET=internal-secret",
                "--PLATFORM_GATEWAY_JWT_VERIFICATION_SECRET=$weakSecret"
            )
        }

        assertThat(failure)
            .hasRootCauseInstanceOf(GatewaySecretConfigurationException::class.java)
        assertThat(output.all)
            .doesNotContain(weakSecret)
            .doesNotContain("WeakKeyException")
            .doesNotContain("bit length")
            .doesNotContain("key length")
    }
}
