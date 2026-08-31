package com.ranikunlabs.platform.identity.adapter.http;

import com.ranikunlabs.platform.identity.adapter.http.dto.response.SecurityErrorResponse;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.OffsetDateTime;

/** Canonical unauthenticated response for protected Identity HTTP routes. */
public class IdentityAuthenticationEntryPoint implements AuthenticationEntryPoint {

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
            AuthenticationException ignored
    ) throws IOException, ServletException {
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.getWriter().write(objectMapper.writeValueAsString(new SecurityErrorResponse(
                OffsetDateTime.now(clock),
                HttpServletResponse.SC_UNAUTHORIZED,
                "Unauthorized",
                "인증이 필요합니다. 로그인 후 다시 시도해주세요.",
                request.getRequestURI()
        )));
    }
}
