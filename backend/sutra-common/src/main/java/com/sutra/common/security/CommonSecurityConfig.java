package com.sutra.common.security;

import com.sutra.common.error.ApiError;
import com.sutra.common.web.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * Stateless JWT security shared by every service. {@code /internal/**} is only for other SUTRA services (the
 * gateway never forwards it); {@code /api/**} needs a signed-in user; fine-grained rules are {@code @PreAuthorize}
 * annotations on the controllers. CORS is handled once, at the gateway.
 */
@Configuration
@EnableMethodSecurity
@EnableConfigurationProperties(SecurityProperties.class)
public class CommonSecurityConfig {

    @Bean
    JwtCodec jwtCodec(SecurityProperties properties) {
        return new JwtCodec(properties);
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, JwtCodec jwt, SecurityProperties properties,
                                    ObjectMapper objectMapper) throws Exception {
        String[] publicPaths = properties.publicPaths().toArray(String[]::new);
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> {
                    if (publicPaths.length > 0) {
                        auth.requestMatchers(publicPaths).permitAll();
                    }
                    auth.requestMatchers("/actuator/health", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
                            .permitAll()
                            .requestMatchers("/internal/**").hasRole(RoleName.SERVICE.name())
                            .requestMatchers("/api/**").authenticated()
                            .anyRequest().denyAll();
                })
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) ->
                                writeError(objectMapper, res, 401, "Unauthorized", "UNAUTHENTICATED",
                                        "Authentication is required", req.getRequestURI()))
                        .accessDeniedHandler((req, res, ex) ->
                                writeError(objectMapper, res, 403, "Forbidden", "FORBIDDEN",
                                        "You do not have permission to perform this action", req.getRequestURI())))
                .addFilterBefore(new JwtAuthenticationFilter(jwt), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    private static void writeError(ObjectMapper mapper, HttpServletResponse res, int status, String error,
                                   String code, String message, String path) throws IOException {
        res.setStatus(status);
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        mapper.writeValue(res.getOutputStream(), new ApiError(Instant.now(), status, error, code, message, path, null,
                CorrelationIdFilter.current()));
    }
}
