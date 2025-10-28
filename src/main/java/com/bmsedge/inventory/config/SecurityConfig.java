package com.bmsedge.inventory.config;

import com.bmsedge.inventory.security.AuthenticationFilter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

@Configuration
public class SecurityConfig {

    @Autowired
    private AuthenticationFilter authenticationFilter;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // Enable CORS and disable CSRF for stateless JWT security
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(AbstractHttpConfigurer::disable)

                // Make security stateless since we're using JWT tokens
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                // Define route-level authorization
                .authorizeHttpRequests(auth -> auth
                        // 🌐 Public endpoints (no authentication required)
                        .requestMatchers(
                                "/",                      // allow Render or root checks
                                "/error",                 // avoid 403 spam
                                "/api/health/**",         // public health checks
                                "/actuator/**",           // Spring Boot actuator
                                "/api/auth/**",           // login/signup endpoints
                                "/api/items/**",
                                "/api/analytics/**",
                                "/api/categories/**",
                                "/api/upload/**",
                                "/api/statistics",
                                "/api/templates/**",
                                "/api/units/**"
                        ).permitAll()

                        // 🔐 Everything else requires authentication
                        .anyRequest().authenticated()
                )

                // Add your custom JWT authentication filter
                .addFilterBefore(authenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * CORS configuration allowing cross-origin requests for your APIs.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOriginPatterns(List.of("*")); // for local & deployed clients
        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setExposedHeaders(List.of("Authorization"));
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        // Apply CORS to all routes (not just /api)
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
