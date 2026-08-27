package com.ranikunlabs.platform.sharedai.adapters.model

import com.openai.errors.OpenAIIoException
import com.openai.errors.OpenAIServiceException
import com.ranikunlabs.platform.sharedai.ports.ModelFailureCategory
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeoutException
import org.springframework.web.client.RestClientResponseException

object ModelProviderFailureMapper {
    fun map(failure: Throwable): ModelFailureCategory {
        val causes = generateSequence(failure) { it.cause }.toList()
        val names = causes.map { it.javaClass.simpleName }
        val statusCode = causes.filterIsInstance<OpenAIServiceException>()
            .firstOrNull()
            ?.statusCode()
            ?: causes.filterIsInstance<RestClientResponseException>()
                .firstOrNull()
                ?.statusCode
                ?.value()

        return when {
            causes.any { it is CancellationException } -> ModelFailureCategory.CANCELLED
            causes.any { it is TimeoutException } || names.any { it.contains("Timeout", ignoreCase = true) } ->
                ModelFailureCategory.TIMEOUT
            statusCode == 401 || statusCode == 403 ||
                names.any {
                    it.contains("Authentication", ignoreCase = true) ||
                        it.contains("Unauthorized", ignoreCase = true) ||
                        it.contains("PermissionDenied", ignoreCase = true)
                } -> ModelFailureCategory.AUTHENTICATION
            statusCode == 429 || names.any {
                it.contains("RateLimit", ignoreCase = true) || it.contains("Throttl", ignoreCase = true)
            } ->
                ModelFailureCategory.RATE_LIMITED
            statusCode != null && statusCode >= 500 ||
                names.any {
                    it.contains("APIConnection", ignoreCase = true) ||
                        it.contains("ServiceUnavailable", ignoreCase = true) ||
                        it.contains("InternalServer", ignoreCase = true)
                } ||
                causes.any { it is IOException || it is OpenAIIoException } ->
                ModelFailureCategory.PROVIDER_UNAVAILABLE
            statusCode != null && statusCode in 400..499 ||
                names.any {
                    it.contains("BadRequest", ignoreCase = true) ||
                        it.contains("Conflict", ignoreCase = true) ||
                        it.contains("NotFound", ignoreCase = true) ||
                        it.contains("Unprocessable", ignoreCase = true) ||
                        it.contains("Validation", ignoreCase = true)
                } ||
                causes.any { it is IllegalArgumentException } -> ModelFailureCategory.INVALID_REQUEST
            causes.any { cause ->
                cause.message?.contains("api key", ignoreCase = true) == true ||
                    cause.message?.contains("credential", ignoreCase = true) == true
            } ->
                ModelFailureCategory.AUTHENTICATION
            else -> ModelFailureCategory.UNKNOWN_PROVIDER_FAILURE
        }
    }
}
