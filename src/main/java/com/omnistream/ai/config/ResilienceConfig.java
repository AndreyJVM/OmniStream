package com.omnistream.ai.config;

import com.github.benmanes.caffeine.cache.*;
import io.github.resilience4j.ratelimiter.*;
import org.springframework.context.annotation.*;
import java.time.Duration;

@Configuration
public class ResilienceConfig {
    @Bean
    Cache<String, RateLimiter> clientLimits() {
        return Caffeine.newBuilder().maximumSize(20000).expireAfterAccess(Duration.ofMinutes(2)).build();
    }

    @Bean
    RateLimiterConfig rateConfig() {
        return RateLimiterConfig.custom().limitForPeriod(50).limitRefreshPeriod(Duration.ofSeconds(1))
            .timeoutDuration(Duration.ZERO).build();
    }
}
