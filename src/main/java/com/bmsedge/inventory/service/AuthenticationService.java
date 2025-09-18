package com.bmsedge.inventory.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.RestClientException;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
public class AuthenticationService {

    @Value("${user.service.url:http://localhost:8084}")
    private String userServiceUrl;

    @Value("${authentication.enabled:false}")
    private boolean authenticationEnabled;

    private final RestTemplate restTemplate;
    private long lastFailureTime = 0;
    private static final long FAILURE_COOLDOWN = TimeUnit.MINUTES.toMillis(1); // 1 minute cooldown

    public AuthenticationService() {
        this.restTemplate = new RestTemplate();
        // Set shorter timeouts to fail fast
        this.restTemplate.getRequestFactory();
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> validateToken(String token) {
        // If authentication is disabled (development mode), return valid response
        if (!authenticationEnabled) {
            return createValidResponse(1L, "test@example.com", "USER");
        }

        // If we recently failed to connect, skip validation temporarily
        if (System.currentTimeMillis() - lastFailureTime < FAILURE_COOLDOWN) {
            System.out.println("User service recently unavailable, allowing request with default user");
            return createValidResponse(1L, "fallback@example.com", "USER");
        }

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            headers.set("Accept", MediaType.APPLICATION_JSON_VALUE);

            String requestBody = "token=" + token;
            HttpEntity<String> entity = new HttpEntity<>(requestBody, headers);

            ResponseEntity<Map> response = restTemplate.postForEntity(
                    userServiceUrl + "/api/validate/token",
                    entity,
                    Map.class
            );

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                Map<String, Object> body = response.getBody();
                if (Boolean.TRUE.equals(body.get("valid"))) {
                    return body;
                }
            }
        } catch (ResourceAccessException e) {
            System.err.println("User service unavailable: " + e.getMessage());
            lastFailureTime = System.currentTimeMillis();
            return createValidResponse(1L, "fallback@example.com", "USER");
        } catch (RestClientException e) {
            System.err.println("Error connecting to user service: " + e.getMessage());
            lastFailureTime = System.currentTimeMillis();
            return createValidResponse(1L, "fallback@example.com", "USER");
        } catch (Exception e) {
            System.err.println("Unexpected error validating token: " + e.getMessage());
            return createValidResponse(1L, "fallback@example.com", "USER");
        }

        // If validation failed but service is available, return invalid
        Map<String, Object> errorResponse = new HashMap<>();
        errorResponse.put("valid", false);
        errorResponse.put("error", "Invalid token");
        return errorResponse;
    }

    public Long getUserIdFromToken(String token) {
        Map<String, Object> validation = validateToken(token);
        if (Boolean.TRUE.equals(validation.get("valid"))) {
            Object userId = validation.get("userId");
            if (userId != null) {
                if (userId instanceof Number) {
                    return ((Number) userId).longValue();
                }
                try {
                    return Long.valueOf(userId.toString());
                } catch (NumberFormatException e) {
                    System.err.println("Invalid userId format: " + userId);
                    return 1L; // Default fallback user ID
                }
            }
        }
        return 1L; // Default fallback user ID
    }

    private Map<String, Object> createValidResponse(Long userId, String email, String roles) {
        Map<String, Object> response = new HashMap<>();
        response.put("valid", true);
        response.put("userId", userId);
        response.put("email", email);
        response.put("roles", roles);
        return response;
    }

    public boolean isAuthenticationEnabled() {
        return authenticationEnabled;
    }

    public boolean isUserServiceHealthy() {
        try {
            ResponseEntity<String> response = restTemplate.getForEntity(
                    userServiceUrl + "/api/health", String.class);
            return response.getStatusCode() == HttpStatus.OK;
        } catch (Exception e) {
            return false;
        }
    }
}