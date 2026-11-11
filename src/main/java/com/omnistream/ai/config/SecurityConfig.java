package com.omnistream.ai.config;

import com.github.benmanes.caffeine.cache.Cache;
import io.github.resilience4j.ratelimiter.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.*;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

@Configuration
public class SecurityConfig {
    @Bean
    SecurityFilterChain chain(HttpSecurity http, @Value("${omnistream.api-key}") String key,
                              Cache<String, RateLimiter> limits, RateLimiterConfig rate) throws Exception {
        if (key.isBlank()) throw new IllegalArgumentException("API_KEY is required");
        byte[] expected = key.getBytes(StandardCharsets.UTF_8);
        var filter = new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain next)
                throws ServletException, IOException {
                String path = req.getServletPath();
                if (path.startsWith("/api/") || path.startsWith("/actuator/metrics")) {
                    var limiter = limits.get(req.getRemoteAddr(), ip -> RateLimiter.of("client", rate));
                    if (!limiter.acquirePermission()) {
                        res.setHeader("Retry-After", "1");
                        problem(res, 429, "Rate limit exceeded");
                        return;
                    }
                    String provided = req.getHeader("X-API-KEY");
                    if (provided == null || !MessageDigest.isEqual(expected, provided.getBytes(StandardCharsets.UTF_8))) {
                        problem(res, 401, "A valid X-API-KEY is required");
                        return;
                    }
                    var token = UsernamePasswordAuthenticationToken.authenticated("api-client", null, List.of());
                    SecurityContextHolder.getContext().setAuthentication(token);
                }
                next.doFilter(req, res);
            }
        };
        return http.csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a -> a.requestMatchers("/", "/index.html", "/landing.css", "/landing.js",
                "/ai-workspace.js", "/ai-workspace.css",
                "/workspace", "/workspace/", "/workspace.html", "/console.css", "/console.js",
                "/mark.svg", "/health", "/error", "/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**",
                "/actuator/health", "/actuator/health/**").permitAll().anyRequest().authenticated())
            .addFilterBefore(filter, UsernamePasswordAuthenticationFilter.class).build();
    }

    private static void problem(HttpServletResponse res, int status, String title) throws IOException {
        res.setStatus(status);
        res.setContentType("application/problem+json");
        res.getWriter().write("{\"status\":" + status + ",\"title\":\"" + title + "\"}");
    }
}
