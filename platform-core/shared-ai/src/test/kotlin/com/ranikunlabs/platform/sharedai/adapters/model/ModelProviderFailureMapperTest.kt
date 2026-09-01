package com.ranikunlabs.platform.sharedai.adapters.model

import com.ranikunlabs.platform.sharedai.ports.ModelFailureCategory
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import software.amazon.awssdk.core.exception.ApiCallTimeoutException
import software.amazon.awssdk.core.exception.SdkClientException
import software.amazon.awssdk.services.bedrockruntime.model.ServiceUnavailableException
import software.amazon.awssdk.services.bedrockruntime.model.ValidationException
import java.util.concurrent.CancellationException

class ModelProviderFailureMapperTest {
    @Test
    fun `maps authentication failure`() {
        val failure = SdkClientException.builder().message("Unable to load credentials from chain").build()
        assertThat(ModelProviderFailureMapper.map(failure)).isEqualTo(ModelFailureCategory.AUTHENTICATION)
    }

    @Test
    fun `maps timeout failure`() {
        val failure = ApiCallTimeoutException.builder().message("timed out").build()
        assertThat(ModelProviderFailureMapper.map(failure)).isEqualTo(ModelFailureCategory.TIMEOUT)
    }

    @Test
    fun `maps provider unavailable failure`() {
        val failure = ServiceUnavailableException.builder().statusCode(503).build()
        assertThat(ModelProviderFailureMapper.map(failure)).isEqualTo(ModelFailureCategory.PROVIDER_UNAVAILABLE)
    }

    @Test
    fun `maps invalid request failure`() {
        val failure = ValidationException.builder().statusCode(400).build()
        assertThat(ModelProviderFailureMapper.map(failure)).isEqualTo(ModelFailureCategory.INVALID_REQUEST)
    }

    @Test
    fun `maps cancellation failure`() {
        assertThat(ModelProviderFailureMapper.map(CancellationException()))
            .isEqualTo(ModelFailureCategory.CANCELLED)
    }
}
