package com.ranikunlabs.platform.gateway.filter

import java.net.SocketTimeoutException
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.TimeoutException

internal object PlatformTimeoutClassifier {

    private val networkTimeoutTypes = setOf(
        "io.netty.handler.timeout.ReadTimeoutException",
        "io.netty.handler.timeout.WriteTimeoutException",
        "io.netty.channel.ConnectTimeoutException"
    )

    fun isActualTimeout(failure: Throwable): Boolean {
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        var cause: Throwable? = failure
        while (cause != null && seen.add(cause)) {
            if (cause is TimeoutException || cause is SocketTimeoutException) return true
            if (cause.javaClass.name in networkTimeoutTypes) return true
            cause = cause.cause
        }
        return false
    }
}
