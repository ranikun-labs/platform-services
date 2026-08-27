package com.ranikunlabs.platform.sharedai.adapters.model

import com.ranikunlabs.platform.sharedai.ports.ModelFailureCategory
import software.amazon.awssdk.awscore.exception.AwsServiceException
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeoutException

object ModelProviderFailureMapper {
    fun map(failure: Throwable): ModelFailureCategory {
        val causes = generateSequence(failure) { it.cause }.toList()
        val names = causes.map { it.javaClass.simpleName }
        val serviceFailure = causes.filterIsInstance<AwsServiceException>().firstOrNull()
        val statusCode = serviceFailure?.statusCode()
        val errorCode = serviceFailure?.awsErrorDetails()?.errorCode().orEmpty()

        return when {
            causes.any { it is CancellationException } -> ModelFailureCategory.CANCELLED
            causes.any { it is TimeoutException } || names.any { it.contains("Timeout", ignoreCase = true) } ->
                ModelFailureCategory.TIMEOUT
            statusCode == 401 || statusCode == 403 ||
                names.any { it.contains("AccessDenied") || it.contains("UnrecognizedClient") } ||
                errorCode.contains("AccessDenied", ignoreCase = true) -> ModelFailureCategory.AUTHENTICATION
            statusCode == 429 || names.any { it.contains("Throttl", ignoreCase = true) } ->
                ModelFailureCategory.RATE_LIMITED
            statusCode != null && statusCode >= 500 ||
                names.any { it.contains("ServiceUnavailable") || it.contains("InternalServer") } ||
                causes.any { it is IOException } -> ModelFailureCategory.PROVIDER_UNAVAILABLE
            statusCode != null && statusCode in 400..499 ||
                names.any { it.contains("Validation", ignoreCase = true) } ||
                causes.any { it is IllegalArgumentException } -> ModelFailureCategory.INVALID_REQUEST
            causes.any { cause -> cause.message?.contains("credential", ignoreCase = true) == true } ->
                ModelFailureCategory.AUTHENTICATION
            else -> ModelFailureCategory.UNKNOWN_PROVIDER_FAILURE
        }
    }
}
