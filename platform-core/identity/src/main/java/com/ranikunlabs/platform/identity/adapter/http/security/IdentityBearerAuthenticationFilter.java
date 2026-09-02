package com.ranikunlabs.platform.identity.adapter.http.security;

import com.ranikunlabs.platform.identity.application.port.TokenProviderPort;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * Establishes an authenticated principal for the Identity Auth namespace from a Bearer access token.
 *
 * <p>This is defense-in-depth for Platform Core and does not replace Shared Gateway. Only the
 * signed token is trusted: client-supplied identity headers are never read, and the principal name
 * is the Platform account UUID carried in the token subject. Any malformed, expired, or otherwise
 * invalid token leaves the request unauthenticated so the authorization rules fail closed.
 */
public class IdentityBearerAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

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
        authenticate(request);
        filterChain.doFilter(request, response);
    }

    private void authenticate(HttpServletRequest request) {
        if (SecurityContextHolder.getContext().getAuthentication() != null) {
            return;
        }

        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return;
        }

        String token = header.substring(BEARER_PREFIX.length());
        // Access-token validation, not generic token validation: a refresh token is also an
        // authentic signed token and must never authenticate a request.
        if (token.isBlank() || !tokenProvider.validateAccessToken(token)) {
            return;
        }

        UUID accountId;
        try {
            accountId = tokenProvider.getAccountIdFromToken(token);
        } catch (RuntimeException exception) {
            // A signed token whose subject is not a Platform account id cannot authenticate.
            return;
        }

        var authentication = new UsernamePasswordAuthenticationToken(
                accountId.toString(), null, List.of());
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
