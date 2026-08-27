package com.ranikunlabs.platform.sharedai.adapters.model

import com.openai.errors.BadRequestException
import com.openai.errors.InternalServerException
import com.openai.errors.OpenAIIoException
import com.openai.errors.OpenAIServiceException
import com.openai.errors.RateLimitException
import com.openai.errors.UnauthorizedException
import com.ranikunlabs.platform.sharedai.ports.ModelFailureCategory
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.net.http.HttpTimeoutException
import java.util.concurrent.CancellationException

class ModelProviderFailureMapperTest {
    @Test
    fun `maps OpenAI authentication failure`() {
        assertThat(ModelProviderFailureMapper.map(serviceFailure(UnauthorizedException::class.java, 401)))
            .isEqualTo(ModelFailureCategory.AUTHENTICATION)
    }

    @Test
    fun `maps OpenAI rate limited failure`() {
        assertThat(ModelProviderFailureMapper.map(serviceFailure(RateLimitException::class.java, 429)))
            .isEqualTo(ModelFailureCategory.RATE_LIMITED)
    }

    @Test
    fun `maps timeout failure`() {
        assertThat(ModelProviderFailureMapper.map(HttpTimeoutException("timed out")))
            .isEqualTo(ModelFailureCategory.TIMEOUT)
    }

    @Test
    fun `maps OpenAI server failure`() {
        val failure = serviceFailure(InternalServerException::class.java, 503)
        assertThat(ModelProviderFailureMapper.map(failure))
            .isEqualTo(ModelFailureCategory.PROVIDER_UNAVAILABLE)
    }

    @Test
    fun `maps OpenAI connection failure`() {
        assertThat(ModelProviderFailureMapper.map(OpenAIIoException("connection failed")))
            .isEqualTo(ModelFailureCategory.PROVIDER_UNAVAILABLE)
    }

    @Test
    fun `maps OpenAI invalid request failure`() {
        assertThat(ModelProviderFailureMapper.map(serviceFailure(BadRequestException::class.java, 400)))
            .isEqualTo(ModelFailureCategory.INVALID_REQUEST)
    }

    @Test
    fun `maps cancellation failure`() {
        assertThat(ModelProviderFailureMapper.map(CancellationException()))
            .isEqualTo(ModelFailureCategory.CANCELLED)
    }

    private fun <T : OpenAIServiceException> serviceFailure(type: Class<T>, statusCode: Int): T {
        val failure = mock(type)
        `when`(failure.statusCode()).thenReturn(statusCode)
        return failure
    }
}
