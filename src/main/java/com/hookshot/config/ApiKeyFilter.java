package com.hookshot.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Optional shared-secret protection for the management API. Disabled when no key is configured. */
@Component
public class ApiKeyFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyFilter.class);

    private final byte[] apiKey;

    public ApiKeyFilter(HookshotProperties properties) {
        this.apiKey = properties.apiKey().getBytes(StandardCharsets.UTF_8);
        if (apiKey.length == 0) {
            log.warn("hookshot.api-key is not set: the /api endpoints are unauthenticated");
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return apiKey.length == 0 || !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String supplied = request.getHeader("X-API-Key");
        if (supplied != null && MessageDigest.isEqual(apiKey, supplied.getBytes(StandardCharsets.UTF_8))) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/problem+json");
        response.getWriter().write("{\"title\":\"Unauthorized\",\"status\":401,\"detail\":\"Missing or invalid X-API-Key\"}");
    }
}
