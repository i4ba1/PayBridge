package com.paybridge.simulator.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Every /v1/** and /internal/** request needs the simulator bearer token (Secret paybridge-simulator). The comparison is
 * constant time. A missing or wrong token is 401 {@code unauthorized}; scenario endpoints are additionally never routed
 * publicly, which is a network concern (ClusterIP only).
 */
@Component
public class BearerAuthFilter extends OncePerRequestFilter {
    private final byte[] expected;

    public BearerAuthFilter(SimulatorProperties props) {
        if (props.getBearerToken() == null || props.getBearerToken().isBlank()) {
            throw new IllegalStateException("paybridge.simulator.bearer-token must be set");
        }
        this.expected = props.getBearerToken().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !(path.startsWith("/v1/") || path.startsWith("/internal/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        byte[] presented = header != null && header.startsWith("Bearer ")
                ? header.substring(7).getBytes(StandardCharsets.UTF_8) : new byte[0];
        if (!MessageDigest.isEqual(presented, expected)) {
            response.setStatus(401);
            response.setContentType("application/json;charset=utf-8");
            response.getOutputStream().write("{\"error\":{\"code\":\"unauthorized\",\"message\":\"Missing or wrong bearer token\"}}"
                    .getBytes(StandardCharsets.UTF_8));
            return;
        }
        chain.doFilter(request, response);
    }
}
