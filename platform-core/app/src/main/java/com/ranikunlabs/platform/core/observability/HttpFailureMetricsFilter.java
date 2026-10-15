package com.ranikunlabs.platform.core.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Counts request failures and typed timeouts without request, identity, or URL labels. */
@Component
public class HttpFailureMetricsFilter extends OncePerRequestFilter {

    static final String RESOLVED_EXCEPTION_ATTRIBUTE =
            HttpFailureMetricsFilter.class.getName() + ".resolvedException";

    private static final String REQUEST_STATE_ATTRIBUTE =
            HttpFailureMetricsFilter.class.getName() + ".requestState";
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
        RequestState state = requestState(request, response);
        HttpServletRequest asyncAwareRequest = new HttpServletRequestWrapper(request) {
            @Override
            public AsyncContext startAsync() throws IllegalStateException {
                AsyncContext context = super.startAsync();
                state.register(context);
                return context;
            }

            @Override
            public AsyncContext startAsync(ServletRequest servletRequest, ServletResponse servletResponse)
                    throws IllegalStateException {
                AsyncContext context = super.startAsync(servletRequest, servletResponse);
                state.register(context);
                return context;
            }
        };

        try {
            filterChain.doFilter(asyncAwareRequest, response);
        } catch (ServletException | IOException | RuntimeException failure) {
            state.recordPropagatedFailure(failure);
            throw failure;
        } finally {
            if (request.isAsyncStarted()) {
                try {
                    state.register(request.getAsyncContext());
                } catch (IllegalStateException completedBeforeRegistration) {
                    state.recordHandledException(
                            resolvedException(request), response.getStatus());
                }
            } else {
                state.recordHandledException(resolvedException(request), response.getStatus());
            }
        }
    }

    private RequestState requestState(HttpServletRequest request, HttpServletResponse response) {
        Object existing = request.getAttribute(REQUEST_STATE_ATTRIBUTE);
        if (existing instanceof RequestState state) {
            return state;
        }
        RequestState state = new RequestState(request, response);
        request.setAttribute(REQUEST_STATE_ATTRIBUTE, state);
        return state;
    }

    private static Throwable resolvedException(HttpServletRequest request) {
        Object exception = request.getAttribute(RESOLVED_EXCEPTION_ATTRIBUTE);
        return exception instanceof Throwable throwable ? throwable : null;
    }

    private boolean isActualTimeout(Throwable failure) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable cause = failure;
        while (cause != null && seen.add(cause)) {
            if (cause instanceof TimeoutException
                    || cause instanceof SocketTimeoutException
                    || cause instanceof HttpTimeoutException) {
                return true;
            }
            if (NETTY_TIMEOUT_TYPES.contains(cause.getClass().getName())) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    private final class RequestState {

        private final HttpServletRequest request;
        private final HttpServletResponse response;
        private final AtomicBoolean recorded = new AtomicBoolean();
        private final Set<AsyncContext> registeredContexts =
                Collections.newSetFromMap(new IdentityHashMap<>());
        private final AsyncListener listener = new AsyncListener() {
            @Override
            public void onComplete(AsyncEvent event) {
                HttpServletResponse asyncResponse = response(event);
                HttpServletRequest asyncRequest = request(event);
                recordHandledException(resolvedException(asyncRequest), asyncResponse.getStatus());
            }

            @Override
            public void onTimeout(AsyncEvent event) {
                recordTimeout();
            }

            @Override
            public void onError(AsyncEvent event) {
                Throwable failure = event.getThrowable();
                if (failure != null) {
                    recordPropagatedFailure(failure);
                } else {
                    HttpServletResponse asyncResponse = response(event);
                    recordHandledException(resolvedException(request(event)), asyncResponse.getStatus());
                }
            }

            @Override
            public void onStartAsync(AsyncEvent event) {
                synchronized (registeredContexts) {
                    registeredContexts.remove(event.getAsyncContext());
                }
                register(event.getAsyncContext());
            }
        };

        private RequestState(HttpServletRequest request, HttpServletResponse response) {
            this.request = request;
            this.response = response;
        }

        private void register(AsyncContext context) {
            synchronized (registeredContexts) {
                if (!registeredContexts.add(context)) {
                    return;
                }
            }
            try {
                context.addListener(listener);
            } catch (IllegalStateException completedBeforeRegistration) {
                recordHandledException(resolvedException(request), response.getStatus());
            }
        }

        private HttpServletRequest request(AsyncEvent event) {
            ServletRequest supplied = event.getSuppliedRequest();
            return supplied instanceof HttpServletRequest httpRequest ? httpRequest : request;
        }

        private HttpServletResponse response(AsyncEvent event) {
            ServletResponse supplied = event.getSuppliedResponse();
            return supplied instanceof HttpServletResponse httpResponse ? httpResponse : response;
        }

        private void recordPropagatedFailure(Throwable failure) {
            if (!recorded.compareAndSet(false, true)) {
                return;
            }
            if (isActualTimeout(failure)) {
                timeouts.increment();
            } else {
                errors.increment();
            }
        }

        private void recordHandledException(Throwable failure, int status) {
            if (!recorded.compareAndSet(false, true)) {
                return;
            }
            if (failure != null && isActualTimeout(failure)) {
                timeouts.increment();
            } else if (status >= 500) {
                errors.increment();
            }
        }

        private void recordTimeout() {
            if (recorded.compareAndSet(false, true)) {
                timeouts.increment();
            }
        }
    }
}
