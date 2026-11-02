package com.sutra.common.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Reads {@code Authorization: Bearer <jwt>} and authenticates the request from the token's claims. Whether the
 * user is still active with the same roles is checked once, at the gateway, against the token version.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtCodec jwt;

    public JwtAuthenticationFilter(JwtCodec jwt) {
        this.jwt = jwt;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith("Bearer ")
                && SecurityContextHolder.getContext().getAuthentication() == null) {
            jwt.verify(header.substring(7)).ifPresent(token -> {
                SutraUserPrincipal p = token.principal();
                SecurityContextHolder.getContext().setAuthentication(
                        new UsernamePasswordAuthenticationToken(p, null, p.getAuthorities()));
            });
        }
        chain.doFilter(request, response);
    }
}
