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
import com.omnistream.ai.service.DemoPolicy;
import java.time.Duration;

@Configuration
public class SecurityConfig {
    @Bean
    SecurityFilterChain chain(HttpSecurity http, @Value("${omnistream.api-key}") String key,
                              Cache<String, RateLimiter> limits, RateLimiterConfig rate,
                              @Value("${omnistream.public-workspace:false}") boolean publicWorkspace, DemoPolicy demo) throws Exception {
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
                    boolean publicEvent = publicWorkspace && (path.equals("/api/v1/events") || path.startsWith("/api/v1/events/"));
                    boolean administrator = provided != null && MessageDigest.isEqual(expected, provided.getBytes(StandardCharsets.UTF_8));
                    if (!publicEvent && !administrator) {
                        problem(res, 401, "A valid X-API-KEY is required");
                        return;
                    }
                    if (publicEvent && !administrator) {
                        if ("cross-site".equals(req.getHeader("Sec-Fetch-Site"))) {
                            problem(res, 403, "Use the demo from its own website");
                            return;
                        }
                        demo.identify(req, res);
                        var readLimit = limits.get("demo:" + req.getAttribute(DemoPolicy.OWNER), ignored ->
                            RateLimiter.of("demo", RateLimiterConfig.custom().limitForPeriod(60)
                                .limitRefreshPeriod(Duration.ofMinutes(1)).timeoutDuration(Duration.ZERO).build()));
                        if (!readLimit.acquirePermission()) {
                            res.setHeader("Retry-After", "60");
                            problem(res, 429, "Demo request quota reached");
                            return;
                        }
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
                "/workspace-config", "/ai-workspace.js", "/ai-workspace.css",
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
