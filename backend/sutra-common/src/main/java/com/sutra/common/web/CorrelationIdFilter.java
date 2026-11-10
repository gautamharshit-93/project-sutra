package com.sutra.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Gives every request a correlation id: taken from a well-formed {@code X-Correlation-Id} header (the gateway sets
 * one on every request; the older {@code X-Request-Id} is still accepted) or generated. It is echoed in the
 * response, written to every log line ({@code %X{correlationId}}), carried on calls to other services, stored on
 * audit events and included in error bodies, so one user action can be followed through every service.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Correlation-Id";
    public static final String LEGACY_HEADER = "X-Request-Id";
    public static final String MDC_KEY = "correlationId";
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]{8,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String incoming = request.getHeader(HEADER);
        if (incoming == null) {
            incoming = request.getHeader(LEGACY_HEADER);
        }
        String correlationId = incoming != null && SAFE_ID.matcher(incoming).matches() ? incoming : newId();
        MDC.put(MDC_KEY, correlationId);
        response.setHeader(HEADER, correlationId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    /** The current request's correlation id, or null outside a request (e.g. background extraction). */
    public static String current() {
        return MDC.get(MDC_KEY);
    }

    /** The current correlation id, or a new one for work that did not start from a request. */
    public static String currentOrNew() {
        String id = current();
        return id != null ? id : newId();
    }

    private static String newId() {
        return UUID.randomUUID().toString();
    }
}
