package com.ranikunlabs.platform.sharedai.adapters.model

import com.openai.errors.OpenAIIoException
import com.openai.errors.OpenAIServiceException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.http.HttpTimeoutException
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeoutException

internal enum class OpenAiFailureReason {
    AUTHENTICATION,
    RATE_LIMITED,
    TIMEOUT,
    PROVIDER_UNAVAILABLE,
    INVALID_REQUEST,
    CANCELLED,
    INVALID_RESPONSE,
    EMPTY_TEXT,
    UNKNOWN_PROVIDER_FAILURE,
}

internal object OpenAiProviderFailureMapper {
    fun map(failure: Throwable): OpenAiFailureReason {
        val causes = generateSequence(failure) { it.cause }.toList()
        val statusCode = causes
            .filterIsInstance<OpenAIServiceException>()
            .firstOrNull()
            ?.statusCode()

        return when {
            causes.any { it is CancellationException } -> OpenAiFailureReason.CANCELLED
            causes.any { it is TimeoutException || it is HttpTimeoutException || it is SocketTimeoutException } ->
                OpenAiFailureReason.TIMEOUT
            statusCode == 401 || statusCode == 403 -> OpenAiFailureReason.AUTHENTICATION
            statusCode == 429 -> OpenAiFailureReason.RATE_LIMITED
            statusCode != null && statusCode >= 500 -> OpenAiFailureReason.PROVIDER_UNAVAILABLE
            statusCode != null && statusCode in 400..499 -> OpenAiFailureReason.INVALID_REQUEST
            causes.any { it is OpenAIIoException || it is IOException } ->
                OpenAiFailureReason.PROVIDER_UNAVAILABLE
            causes.any { it is IllegalArgumentException } -> OpenAiFailureReason.INVALID_REQUEST
            else -> OpenAiFailureReason.UNKNOWN_PROVIDER_FAILURE
        }
    }
}

internal class OpenAiProviderFailure(
    val reason: OpenAiFailureReason,
    cause: Throwable? = null,
) : RuntimeException("OpenAI provider call failed: ${reason.name}", cause)
