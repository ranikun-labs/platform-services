package com.ranikunlabs.platform.sharedai.adapters.model

import com.openai.errors.BadRequestException
import com.openai.errors.InternalServerException
import com.openai.errors.OpenAIIoException
import com.openai.errors.OpenAIServiceException
import com.openai.errors.RateLimitException
import com.openai.errors.UnauthorizedException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.http.HttpTimeoutException
import java.util.concurrent.CancellationException

class OpenAiProviderFailureMapperTest {
    @Test
    fun `maps authentication status`() {
        assertThat(OpenAiProviderFailureMapper.map(serviceFailure(UnauthorizedException::class.java, 401)))
            .isEqualTo(OpenAiFailureReason.AUTHENTICATION)
    }

    @Test
    fun `maps rate limiting status`() {
        assertThat(OpenAiProviderFailureMapper.map(serviceFailure(RateLimitException::class.java, 429)))
            .isEqualTo(OpenAiFailureReason.RATE_LIMITED)
    }

    @Test
    fun `maps typed timeout causes`() {
        assertThat(OpenAiProviderFailureMapper.map(HttpTimeoutException("timed out")))
            .isEqualTo(OpenAiFailureReason.TIMEOUT)
        assertThat(OpenAiProviderFailureMapper.map(SocketTimeoutException("timed out")))
            .isEqualTo(OpenAiFailureReason.TIMEOUT)
    }

    @Test
    fun `maps provider server and network failures`() {
        assertThat(OpenAiProviderFailureMapper.map(serviceFailure(InternalServerException::class.java, 503)))
            .isEqualTo(OpenAiFailureReason.PROVIDER_UNAVAILABLE)
        assertThat(OpenAiProviderFailureMapper.map(OpenAIIoException("connection failed")))
            .isEqualTo(OpenAiFailureReason.PROVIDER_UNAVAILABLE)
        assertThat(OpenAiProviderFailureMapper.map(IOException("connection failed")))
            .isEqualTo(OpenAiFailureReason.PROVIDER_UNAVAILABLE)
    }

    @Test
    fun `maps invalid request status`() {
        assertThat(OpenAiProviderFailureMapper.map(serviceFailure(BadRequestException::class.java, 400)))
            .isEqualTo(OpenAiFailureReason.INVALID_REQUEST)
    }

    @Test
    fun `maps cancellation`() {
        assertThat(OpenAiProviderFailureMapper.map(CancellationException()))
            .isEqualTo(OpenAiFailureReason.CANCELLED)
    }

    @Test
    fun `uses unknown for unclassified failures rather than class-name heuristics`() {
        assertThat(OpenAiProviderFailureMapper.map(RuntimeException("TimeoutException-like detail")))
            .isEqualTo(OpenAiFailureReason.UNKNOWN_PROVIDER_FAILURE)
    }

    private fun <T : OpenAIServiceException> serviceFailure(type: Class<T>, statusCode: Int): T {
        val failure = mock(type)
        `when`(failure.statusCode()).thenReturn(statusCode)
        return failure
    }
}
