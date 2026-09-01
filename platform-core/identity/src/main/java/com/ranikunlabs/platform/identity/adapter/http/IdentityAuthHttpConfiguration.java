package com.ranikunlabs.platform.identity.adapter.http;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Composes the Identity Auth/OAuth HTTP compatibility boundary.
 *
 * <p>Only registered when the Identity Auth runtime is enabled and Identity is hosted in a Servlet
 * context, so the RPL-105 foundation host and the non-web Identity slices stay unaffected.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@Import({IdentityAuthController.class, IdentityAuthExceptionHandler.class})
public class IdentityAuthHttpConfiguration {
}
