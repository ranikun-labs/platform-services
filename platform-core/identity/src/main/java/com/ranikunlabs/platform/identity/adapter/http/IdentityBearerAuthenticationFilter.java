package com.ranikunlabs.platform.identity.adapter.http;

import com.ranikunlabs.platform.identity.application.port.TokenProviderPort;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/** Authenticates only verified Bearer tokens for Identity-owned protected endpoints. */
public class IdentityBearerAuthenticationFilter extends OncePerRequestFilter {

    private final TokenProviderPort tokenProvider;

    public IdentityBearerAuthenticationFilter(TokenProviderPort tokenProvider) {
        this.tokenProvider = tokenProvider;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            authenticateBearerToken(request);
        }
        filterChain.doFilter(request, response);
    }

    private void authenticateBearerToken(HttpServletRequest request) {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return;
        }

        String rawToken = authorization.substring(7);
        if (rawToken.isBlank() || !tokenProvider.validateAccessToken(rawToken)) {
            return;
        }

        try {
            UUID accountId = tokenProvider.getAccountIdFromToken(rawToken);
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(accountId.toString(), null, List.of()));
        } catch (IllegalArgumentException ignored) {
            // A token with an unusable Identity subject remains unauthenticated and is rejected by
            // the security chain.  No token detail is exposed or logged at this boundary.
        }
    }
}
