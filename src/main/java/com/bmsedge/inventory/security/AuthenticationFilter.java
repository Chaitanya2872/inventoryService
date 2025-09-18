package com.bmsedge.inventory.security;

import com.bmsedge.inventory.service.AuthenticationService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;


import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;

@Component
public class AuthenticationFilter extends OncePerRequestFilter {

    @Autowired
    private AuthenticationService authenticationService;

    @Value("${spring.profiles.active:dev}")
    private String activeProfile;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        // Skip authentication for certain endpoints
        String path = request.getRequestURI();
        if (path.startsWith("/api/health") || path.startsWith("/actuator")) {
            filterChain.doFilter(request, response);
            return;
        }

        // Handle OPTIONS requests (CORS preflight)
        if ("OPTIONS".equals(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }

        // In development mode, skip authentication for all API endpoints
        if (isDevelopmentMode() && path.startsWith("/api/")) {
            // Set default user for development
            request.setAttribute("userId", 1L);
            request.setAttribute("userEmail", "test@example.com");
            request.setAttribute("userRoles", "USER");
            filterChain.doFilter(request, response);
            return;
        }

        // Production authentication logic
        String token = parseJwt(request);
        if (token != null) {
            try {
                Map<String, Object> validation = authenticationService.validateToken(token);
                if (Boolean.TRUE.equals(validation.get("valid"))) {
                    // Set user information in request attributes
                    request.setAttribute("userId", validation.get("userId"));
                    request.setAttribute("userEmail", validation.get("email"));
                    request.setAttribute("userRoles", validation.get("roles"));
                    filterChain.doFilter(request, response);
                    return;
                } else {
                    response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid token");
                    return;
                }
            } catch (Exception e) {
                System.err.println("Error validating token: " + e.getMessage());
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Token validation failed");
                return;
            }
        } else {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "No token provided");
            return;
        }
    }

    private boolean isDevelopmentMode() {
        return "dev".equals(activeProfile) || "development".equals(activeProfile) || "test".equals(activeProfile);
    }

    private String parseJwt(HttpServletRequest request) {
        String headerAuth = request.getHeader("Authorization");

        if (StringUtils.hasText(headerAuth) && headerAuth.startsWith("Bearer ")) {
            return headerAuth.substring(7);
        }

        return null;
    }
}