package com.ranikunlabs.platform.identity.adapter.http.security;

import com.ranikunlabs.platform.identity.adapter.http.IdentityErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.OffsetDateTime;

/** Writes the canonical unauthenticated failure body for the Identity Auth namespace. */
public class IdentityAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private static final Logger log = LoggerFactory.getLogger(IdentityAuthenticationEntryPoint.class);
    private static final String MESSAGE = "인증이 필요합니다. 로그인 후 다시 시도해주세요.";

    private final ObjectMapper objectMapper;
    private final Clock clock;

    public IdentityAuthenticationEntryPoint(ObjectMapper objectMapper, Clock clock) {
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authenticationException
    ) throws IOException {
        log.warn("Identity Auth authentication required: {}", request.getRequestURI());

        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);

        IdentityErrorResponse body = new IdentityErrorResponse(
                OffsetDateTime.now(clock),
                HttpServletResponse.SC_UNAUTHORIZED,
                "Unauthorized",
                MESSAGE,
                request.getRequestURI()
        );
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
