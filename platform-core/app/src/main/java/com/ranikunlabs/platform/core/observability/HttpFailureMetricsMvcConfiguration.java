package com.ranikunlabs.platform.core.observability;

import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Captures the original exception before MVC advice resolves it, without changing its response. */
@Configuration(proxyBeanMethods = false)
public class HttpFailureMetricsMvcConfiguration implements WebMvcConfigurer {

    @Override
    public void extendHandlerExceptionResolvers(List<HandlerExceptionResolver> resolvers) {
        resolvers.add(0, (request, response, handler, exception) -> {
            if (request.getAttribute(HttpFailureMetricsFilter.RESOLVED_EXCEPTION_ATTRIBUTE) == null) {
                request.setAttribute(HttpFailureMetricsFilter.RESOLVED_EXCEPTION_ATTRIBUTE, exception);
            }
            return null;
        });
    }
}
