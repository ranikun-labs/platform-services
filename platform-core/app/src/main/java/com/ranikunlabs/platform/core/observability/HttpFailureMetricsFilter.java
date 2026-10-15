package com.ranikunlabs.platform.core.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Counts HTTP failures and typed timeouts without request, identity, or URL labels. */
@Component
public class HttpFailureMetricsFilter extends OncePerRequestFilter {

    private static final Set<String> NETTY_TIMEOUT_TYPES = Set.of(
            "io.netty.handler.timeout.ReadTimeoutException",
            "io.netty.handler.timeout.WriteTimeoutException",
            "io.netty.channel.ConnectTimeoutException");

    private final Counter errors;
    private final Counter timeouts;

    public HttpFailureMetricsFilter(MeterRegistry meterRegistry) {
        this.errors = meterRegistry.counter("platform.http.server.errors");
        this.timeouts = meterRegistry.counter("platform.http.server.timeouts");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        boolean failureRecorded = false;
        try {
            filterChain.doFilter(request, response);
        } catch (ServletException | IOException | RuntimeException failure) {
            incrementFor(failure);
            failureRecorded = true;
            throw failure;
        } finally {
            if (!failureRecorded && response.getStatus() >= 500) {
                errors.increment();
            }
        }
    }

    private void incrementFor(Throwable failure) {
        if (isActualTimeout(failure)) {
            timeouts.increment();
        } else {
            errors.increment();
        }
    }

    private boolean isActualTimeout(Throwable failure) {
        Set<Throwable> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        Throwable cause = failure;
        while (cause != null && seen.add(cause)) {
            if (cause instanceof TimeoutException || cause instanceof SocketTimeoutException) {
                return true;
            }
            if (NETTY_TIMEOUT_TYPES.contains(cause.getClass().getName())) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }
}
